package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that concurrent decisions on one (user, notice) pair leave neither two live consents nor a
 * consent recorded twice: the unique index settles a fresh pair ({@code 409} to the loser), the
 * writer's {@code FOR UPDATE} read settles a pair holding an unanswered request.
 */
@MicronautTest(transactional = false)
@DisplayName("Concurrent consent decisions")
class ConsentGrantAtomicityIT extends PostgresTestResource {

    /** Two is the whole hazard: one decision wins the row, the other must re-read it. */
    private static final int RACERS = 2;

    private static final String PROVIDER = "urn:test:participant:consent-atomicity-provider";

    private static final String CONSUMER = "urn:test:participant:consent-atomicity-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:consent-atomicity";

    @Inject ConsentService service;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    private User subject;

    private PrivacyNotice notice;

    /** What the racers left behind: the consents recorded, and the refusals the losers got. */
    private record Outcomes(List<UUID> recorded, List<Throwable> refused) {}

    /** Rows these cases commit outlive their transactions, and a notice holds its parties. */
    @AfterEach
    void removeCommittedRows() {
        if (subject != null) {
            consents.findByUserIdAndStatusIn(subject.getId(), List.of(ConsentStatus.values()))
                    .forEach(
                            consent -> {
                                events.findByConsentIdOrderByOccurredAtAsc(consent.getId())
                                        .forEach(events::delete);
                                consents.delete(consent);
                            });
            users.delete(subject);
            subject = null;
        }
        if (notice != null) {
            notices.deleteById(notice.getId());
            notice = null;
        }
        List.of(PROVIDER, CONSUMER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @Test
    @DisplayName("two first grants leave one consent and answer the loser with a conflict")
    void concurrentFirstGrantsLeaveExactlyOneConsent() throws Exception {
        seedPair();

        Outcomes outcomes = race();

        assertThat(outcomes.refused())
                .as("losing the one-grant-per-notice index is a conflict, not a server error")
                .singleElement()
                .isInstanceOf(ConflictException.class);
        assertThat(outcomes.recorded()).hasSize(RACERS - 1);
        assertThat(granted())
                .as("the user ends up with exactly one consent in force, never zero or two")
                .singleElement()
                .satisfies(
                        consent ->
                                assertThat(consent.getId())
                                        .isEqualTo(outcomes.recorded().getFirst()));
    }

    @Test
    @DisplayName("two grants against one open request leave nothing unanswered and nothing twice")
    void concurrentGrantsAgainstOneRequestSettleOnOneConsent() throws Exception {
        seedPair();
        seedRequest();

        Outcomes outcomes = race();

        assertThat(outcomes.recorded())
                .as("the lock serialises both decisions rather than making the second one fail")
                .hasSize(RACERS);
        assertThat(outcomes.refused()).isEmpty();
        assertThat(
                        consents.findByUserIdAndStatusIn(
                                subject.getId(), List.of(ConsentStatus.PENDING)))
                .as("the request is answered once rather than adopted twice or left stranded")
                .isEmpty();
        assertThat(granted())
                .as("the lock forces the second decision to supersede the first, not repeat it")
                .hasSize(1);
        consents.findByPrivacyNoticeId(notice.getId())
                .forEach(
                        consent ->
                                assertThat(
                                                events
                                                        .findByConsentIdOrderByOccurredAtAsc(
                                                                consent.getId())
                                                        .stream()
                                                        .map(ConsentEvent::getEventState)
                                                        .toList())
                                        .as(
                                                "no consent is recorded as having reached a state twice")
                                        .doesNotHaveDuplicates());
    }

    /** Runs {@link #RACERS} grants that start together. */
    private Outcomes race() throws Exception {
        CyclicBarrier start = new CyclicBarrier(RACERS);
        Callable<UUID> grant =
                () -> {
                    start.await();
                    return service.decide(
                                    subject,
                                    new ConsentDecision(
                                            notice.getId(), ConsentStatus.GRANTED, null, null))
                            .consent()
                            .getId();
                };
        try (ExecutorService pool = Executors.newFixedThreadPool(RACERS)) {
            List<Future<UUID>> futures = pool.invokeAll(Collections.nCopies(RACERS, grant));
            List<UUID> recorded = new ArrayList<>(RACERS);
            List<Throwable> refused = new ArrayList<>(RACERS);
            for (Future<UUID> future : futures) {
                try {
                    recorded.add(future.get());
                } catch (ExecutionException e) {
                    refused.add(e.getCause());
                }
            }
            return new Outcomes(recorded, refused);
        }
    }

    private List<Consent> granted() {
        return consents.findByUserIdAndStatus(subject.getId(), ConsentStatus.GRANTED);
    }

    private void seedPair() {
        subject = users.save(new User("urn:test:user:consent-atomicity", null, null, null));
        notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                null,
                                participant(PROVIDER).getId(),
                                participant(CONSUMER).getId(),
                                new PrivacyNoticePayload(
                                        null, null, null, null, null, null, null, null, null)));
    }

    /** An unanswered request for the pair, as a participant's ask would have left it. */
    private void seedRequest() {
        Consent requested =
                consents.save(
                        Consent.withStatus(
                                ConsentStatus.PENDING,
                                subject.getId(),
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()));
        events.save(
                new ConsentEvent(
                        requested.getId(), ConsentEventState.CONSENT_REQUESTED, PROVIDER, null));
    }

    private Participant participant(String identifier) {
        return participants
                .findByIdentifier(identifier)
                .orElseGet(
                        () ->
                                participants.save(
                                        new Participant(
                                                identifier,
                                                identifier,
                                                null,
                                                null,
                                                Map.of(),
                                                null)));
    }
}
