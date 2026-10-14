package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.ApiException;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ProblemType;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
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
 * Proves that two lifecycle moves sent at the same instant cannot leave a pair holding two live
 * consents, nor one consent carrying a move recorded twice.
 *
 * <p>Two mechanisms are under test. A move addressing one record is settled by {@code
 * ConsentWriter}'s {@code FOR UPDATE} re-read of that row: the loser is guarded on the status the
 * winner left, so the second revocation is refused rather than appending a second event to a
 * consent already withdrawn. A move that may land in {@code GRANTED} is settled by the {@code
 * (user, notice)} lock the writer takes before reading, which is the same lock {@code
 * ConsentService.decide} takes - so a resume and a decision for one pair serialise, and the second
 * sees what the first left rather than racing it to {@code uq_consents_granted_per_user_notice}.
 *
 * <p>That lock is why no case here expects the {@code 409} {@code ConsentWriter#resume} documents:
 * every writer that can insert a grant for the pair takes it, and a resume always locks at least
 * the row it addresses, so the index is a backstop rather than the arbiter. What the assertions
 * therefore pin is the property AC 16 is about - a loser is answered by a client error naming what
 * it may do instead, never by a server error - rather than one particular status the interleaving
 * happens to produce.
 */
@MicronautTest(transactional = false)
@DisplayName("Concurrent consent lifecycle moves")
class ConsentLifecycleAtomicityIT extends PostgresTestResource {

    /** Two is the whole hazard: one move wins the row, the other must re-read it. */
    private static final int RACERS = 2;

    private static final String PROVIDER = "urn:test:participant:lifecycle-atomicity-provider";

    private static final String CONSUMER = "urn:test:participant:lifecycle-atomicity-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:lifecycle-atomicity";

    /** The provider of a token this suite never validates; only its shape matters here. */
    private static final String ISSUER = "https://issuer.test/realms/lifecycle-atomicity";

    @Inject ConsentLifecycleService lifecycle;

    @Inject ConsentService consentService;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    private User subject;

    private PrivacyNotice notice;

    /** What the racers left behind: the consents they moved, and the refusals the losers got. */
    private record Outcomes(List<UUID> moved, List<Throwable> refused) {}

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
    @DisplayName("a revocation racing a fresh grant leaves the pair exactly one consent in force")
    void concurrentRevocationAndGrantLeaveOneConsentInForce() throws Exception {
        seedPair();
        UUID inForce = seedConsent(ConsentStatus.GRANTED);

        Outcomes outcomes = race(() -> revoke(inForce), this::grant);

        assertThat(outcomes.refused())
                .as("a revocation the decision beat to the row is refused, never a server error")
                .allSatisfy(ConsentLifecycleAtomicityIT::isClientError)
                .allSatisfy(refusal -> assertThat(refusal).isInstanceOf(BadRequestException.class));
        assertThat(granted())
                .as("whichever order they land in, the pair ends with one consent in force")
                .hasSize(1);
        assertNoConsentReachedAStateTwice();
    }

    @Test
    @DisplayName("a resume racing a grant of the same pair leaves exactly one consent in force")
    void concurrentResumeAndGrantLeaveOneConsentInForce() throws Exception {
        seedPair();
        UUID requested = seedConsent(ConsentStatus.PENDING);

        Outcomes outcomes = race(() -> resume(requested), this::grant);

        assertThat(outcomes.refused())
                .as("a resume of a request the decision already answered is refused, not a 500")
                .allSatisfy(ConsentLifecycleAtomicityIT::isClientError);
        assertThat(granted())
                .as("the lock makes the second move supersede the first rather than double it")
                .hasSize(1);
        assertThat(
                        consents.findByUserIdAndStatusIn(
                                subject.getId(), List.of(ConsentStatus.PENDING)))
                .as("the request is answered once rather than left stranded behind the grant")
                .isEmpty();
        assertNoConsentReachedAStateTwice();
    }

    @Test
    @DisplayName("two revocations of one consent withdraw it once and refuse the loser")
    void concurrentRevocationsAppendOneEvent() throws Exception {
        seedPair();
        UUID inForce = seedConsent(ConsentStatus.GRANTED);

        Outcomes outcomes = race(() -> revoke(inForce), () -> revoke(inForce));

        assertThat(outcomes.moved()).containsExactly(inForce);
        assertThat(outcomes.refused())
                .as("the re-read shows the loser the status the winner left, which is a 400")
                .singleElement()
                .isInstanceOf(BadRequestException.class);
        assertThat(consents.findById(inForce).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.REVOKED);
        assertThat(
                        events.findByConsentIdOrderByOccurredAtAsc(inForce).stream()
                                .map(ConsentEvent::getEventState)
                                .toList())
                .as("the seeded grant, then one withdrawal: the loser appends nothing")
                .containsExactly(
                        ConsentEventState.CONSENT_GIVEN, ConsentEventState.CONSENT_REVOKED);
    }

    /** Fails unless the refusal is one the API renders as a client error the caller can act on. */
    private static void isClientError(Throwable refusal) {
        assertThat(refusal).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refusal).problemType().status().getCode())
                .isLessThan(ProblemType.LOWEST_SERVER_ERROR_STATUS);
    }

    /** Fails if any consent of the pair records the same state twice, which no move may do. */
    private void assertNoConsentReachedAStateTwice() {
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

    /** Runs the two moves so that neither starts before the other, separating win from refusal. */
    private Outcomes race(Callable<UUID> first, Callable<UUID> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(RACERS);
        List<Callable<UUID>> racers = List.of(started(start, first), started(start, second));
        try (ExecutorService pool = Executors.newFixedThreadPool(RACERS)) {
            List<Future<UUID>> futures = pool.invokeAll(racers);
            List<UUID> moved = new ArrayList<>(RACERS);
            List<Throwable> refused = new ArrayList<>(RACERS);
            for (Future<UUID> future : futures) {
                try {
                    moved.add(future.get());
                } catch (ExecutionException e) {
                    refused.add(e.getCause());
                }
            }
            return new Outcomes(moved, refused);
        }
    }

    private static Callable<UUID> started(CyclicBarrier start, Callable<UUID> move) {
        return () -> {
            start.await();
            return move.call();
        };
    }

    private UUID revoke(UUID consentId) {
        return lifecycle.revoke(subject, consentId).consent().getId();
    }

    private UUID resume(UUID consentId) {
        return lifecycle.resume(principalFor(subject), consentId, null).consent().getId();
    }

    private UUID grant() {
        return consentService
                .decide(
                        subject,
                        new ConsentDecision(notice.getId(), ConsentStatus.GRANTED, null, null))
                .consent()
                .getId();
    }

    private List<Consent> granted() {
        return consents.findByUserIdAndStatus(subject.getId(), ConsentStatus.GRANTED);
    }

    /** The subject's own token, as the resolution filter would have handed it to a handler. */
    private static UserPrincipal principalFor(User subject) {
        return new UserPrincipal(
                ISSUER,
                subject.getIdentifier(),
                subject.getIdentifier(),
                null,
                false,
                null,
                null,
                null,
                subject,
                false);
    }

    private void seedPair() {
        subject = users.save(new User("urn:test:user:lifecycle-atomicity", null, null, null));
        notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                null,
                                participant(PROVIDER).getId(),
                                participant(CONSUMER).getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
    }

    /**
     * A consent for the pair as an earlier flow would have left it, with the trail it would hold.
     */
    private UUID seedConsent(ConsentStatus status) {
        Consent seeded =
                consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()));
        events.save(
                new ConsentEvent(
                        seeded.getId(),
                        status == ConsentStatus.GRANTED
                                ? ConsentEventState.CONSENT_GIVEN
                                : ConsentEventState.CONSENT_REQUESTED,
                        PROVIDER,
                        null));
        return seeded.getId();
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
