package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves what a participant's ask is checked against and what it confers, which is nothing: a
 * request is recorded unanswered, over the notice's own terms, in the asking participant's name.
 *
 * <p>Every refusal asserts the database is untouched as well as the status, because a request that
 * fails halfway would leave the pair holding an open row no later request could get past.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent requests")
class ConsentRequestIT extends PostgresTestResource {

    private static final String PROVIDER = "urn:test:participant:consent-request-provider";

    private static final String CONSUMER = "urn:test:participant:consent-request-consumer";

    /** Linked to the subject but named by no notice, so only the party rule can refuse it. */
    private static final String BYSTANDER = "urn:test:participant:consent-request-bystander";

    private static final String CONTRACT_URI = "urn:test:contract:consent-request";

    /** A second contract, so the notice naming no consumer does not collide with the first. */
    private static final String UNCLAIMED_CONTRACT_URI = "urn:test:contract:consent-request-orphan";

    private static final String NOTICE_TITLE = "Shared clinical research notice";

    private static final List<String> NOTICE_PURPOSES = List.of("research", "billing");

    private static final List<String> NOTICE_CATEGORIES = List.of("contact", "clinical");

    private static final List<String> PARTICIPANTS = List.of(PROVIDER, CONSUMER, BYSTANDER);

    private static final List<ConsentStatus> ALL_STATUSES = List.of(ConsentStatus.values());

    @Inject ConsentService service;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject UserParticipantRepository links;

    private final List<User> seededUsers = new ArrayList<>();

    private final List<UUID> seededNotices = new ArrayList<>();

    /** The ways an ask is turned away, and what each is answered with. */
    private enum Refusal {
        UNKNOWN_NOTICE(NotFoundException.class),
        ARCHIVED_NOTICE(BadRequestException.class),
        NOTICE_WITHOUT_CONSUMER(BadRequestException.class),
        CALLER_NAMED_BY_NO_NOTICE(ForbiddenException.class),
        UNKNOWN_USER(NotFoundException.class),
        USER_OF_ANOTHER_PARTICIPANT(NotFoundException.class);

        private final Class<? extends RuntimeException> expected;

        Refusal(Class<? extends RuntimeException> expected) {
            this.expected = expected;
        }
    }

    /** Rows this suite commits outlive its transaction, and a notice holds its parties. */
    @AfterEach
    void removeCommittedRows() {
        seededUsers.forEach(
                user -> {
                    consents.findByUserIdAndStatusIn(user.getId(), ALL_STATUSES)
                            .forEach(
                                    consent -> {
                                        events.findByConsentIdOrderByOccurredAtAsc(consent.getId())
                                                .forEach(events::delete);
                                        consents.delete(consent);
                                    });
                    links.deleteByIdUserId(user.getId());
                    users.delete(user);
                });
        seededUsers.clear();
        seededNotices.forEach(notices::deleteById);
        seededNotices.clear();
    }

    @AfterAll
    void removeParticipants() {
        PARTICIPANTS.forEach(
                identifier ->
                        participants.findByIdentifier(identifier).ifPresent(participants::delete));
    }

    @ParameterizedTest(name = "{0} is refused and writes nothing")
    @EnumSource(Refusal.class)
    @DisplayName("an ask that fails a rule leaves the pair holding nothing")
    void refusesAndWritesNothing(Refusal refusal) {
        User subject = linkedUser(PROVIDER, BYSTANDER);
        PrivacyNotice notice = notice(fullPayload());
        Participant caller = participant(PROVIDER);
        UUID noticeId = notice.getId();
        String userIdentifier = subject.getIdentifier();

        switch (refusal) {
            case UNKNOWN_NOTICE -> noticeId = UUID.randomUUID();
            case ARCHIVED_NOTICE -> archive(notice);
            case NOTICE_WITHOUT_CONSUMER ->
                    noticeId = notice(fullPayload(), null, UNCLAIMED_CONTRACT_URI).getId();
            case CALLER_NAMED_BY_NO_NOTICE -> caller = participant(BYSTANDER);
            case UNKNOWN_USER -> userIdentifier = "urn:test:user:consent-request:absent";
            case USER_OF_ANOTHER_PARTICIPANT ->
                    userIdentifier = linkedUser(BYSTANDER).getIdentifier();
        }

        Participant asking = caller;
        ConsentRequestCommand command = new ConsentRequestCommand(noticeId, userIdentifier);
        assertThatThrownBy(() -> service.request(asking, command)).isInstanceOf(refusal.expected);
        assertThat(heldByAnyone())
                .as("a refused ask writes no consent, for the user it named or for any other")
                .isEmpty();
    }

    @ParameterizedTest(name = "the notice''s {0} may ask")
    @ValueSource(strings = {PROVIDER, CONSUMER})
    @DisplayName("either party may ask, and the request is recorded in that party's name")
    void recordsAnUnansweredRequestInTheCallersName(String callerIdentifier) {
        User subject = linkedUser(PROVIDER, CONSUMER);
        PrivacyNotice notice = notice(fullPayload());

        ConsentDetails details = request(participant(callerIdentifier), notice, subject);

        Consent stored = consents.findById(details.consent().getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ConsentStatus.PENDING);
        assertThat(stored.isConsented()).as("an ask is not an answer, whoever puts it").isFalse();
        assertThat(stored.getContractUri()).isEqualTo(CONTRACT_URI);
        assertThat(details.providerIdentifier()).isEqualTo(PROVIDER);
        assertThat(details.consumerIdentifier()).isEqualTo(CONSUMER);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(stored.getId()))
                .as("a request leaves exactly one event, naming who asked")
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_REQUESTED);
                            assertThat(event.getActor()).isEqualTo(callerIdentifier);
                        });
    }

    @Test
    @DisplayName("a request freezes the notice's full scope, which only the subject may narrow")
    void freezesTheWholeNoticeScope() {
        User subject = linkedUser(PROVIDER);
        PrivacyNotice notice = notice(fullPayload());

        ConsentDetails details = request(participant(PROVIDER), notice, subject);

        assertThat(details.consent().getSnapshot().title()).isEqualTo(NOTICE_TITLE);
        assertThat(details.consent().getSnapshot().purposes()).isEqualTo(NOTICE_PURPOSES);
        assertThat(details.consent().getSnapshot().dataCategories()).isEqualTo(NOTICE_CATEGORIES);
    }

    @Test
    @DisplayName("a heading the notice never declared stays absent rather than becoming empty")
    void leavesUndeclaredHeadingsAbsent() {
        User subject = linkedUser(PROVIDER);
        PrivacyNotice notice = notice(payload(NOTICE_PURPOSES, null));

        ConsentDetails details = request(participant(PROVIDER), notice, subject);

        assertThat(details.consent().getSnapshot().dataCategories())
                .as("absent is not the same as consented to nothing")
                .isNull();
    }

    @ParameterizedTest(name = "a pair already holding a {0} consent is refused")
    @EnumSource(
            value = ConsentStatus.class,
            names = {"GRANTED", "PENDING"})
    @DisplayName("an open consent for the pair refuses a further ask")
    void refusesASecondAskWhileOneStands(ConsentStatus open) {
        User subject = linkedUser(PROVIDER);
        PrivacyNotice notice = notice(fullPayload());
        UUID standing = seedConsent(subject, notice, open);

        assertThatThrownBy(() -> request(participant(PROVIDER), notice, subject))
                .isInstanceOf(ConflictException.class);

        assertThat(heldBy(subject))
                .as("the standing consent is read back, not duplicated")
                .singleElement()
                .satisfies(consent -> assertThat(consent.getId()).isEqualTo(standing));
    }

    @ParameterizedTest(name = "a pair whose last consent is {0} may be asked again")
    @EnumSource(
            value = ConsentStatus.class,
            names = {"REFUSED", "REVOKED", "EXPIRED", "TERMINATED"})
    @DisplayName(
            "a closed consent does not block a later ask, and is what the new one descends from")
    void linksTheRequestToWhateverThePairLastHeld(ConsentStatus closed) {
        User subject = linkedUser(PROVIDER);
        PrivacyNotice notice = notice(fullPayload());
        UUID previous = seedConsent(subject, notice, closed);

        ConsentDetails details = request(participant(PROVIDER), notice, subject);

        assertThat(details.consent().getParentConsentId())
                .as("the chain records what the pair last held, whatever closed it")
                .isEqualTo(previous);
    }

    private ConsentDetails request(Participant caller, PrivacyNotice notice, User subject) {
        return service.request(
                caller, new ConsentRequestCommand(notice.getId(), subject.getIdentifier()));
    }

    private List<Consent> heldBy(User subject) {
        return consents.findByUserIdAndStatusIn(subject.getId(), ALL_STATUSES);
    }

    /**
     * Everything held by every user this test seeded, which is what a refusal must leave empty: a
     * refused ask names a user of its own, so asserting over one chosen user would pass even where
     * the row was written against the user the command actually targeted.
     */
    private List<Consent> heldByAnyone() {
        return seededUsers.stream().flatMap(user -> heldBy(user).stream()).toList();
    }

    /** A consent for the pair as some other flow would have left it, with no trail of its own. */
    private UUID seedConsent(User subject, PrivacyNotice notice, ConsentStatus status) {
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }

    private void archive(PrivacyNotice notice) {
        notice.setArchivedAt(Instant.now());
        notices.update(notice);
    }

    /** A user affiliated with each named participant, and with nobody else. */
    private User linkedUser(String... affiliations) {
        User subject =
                users.save(
                        new User(
                                "urn:test:user:consent-request:" + UUID.randomUUID(),
                                null,
                                null,
                                null));
        seededUsers.add(subject);
        for (String affiliation : affiliations) {
            links.save(
                    new UserParticipant(subject.getId(), participant(affiliation).getId(), null));
        }
        return subject;
    }

    private PrivacyNotice notice(PrivacyNoticePayload payload) {
        return notice(payload, participant(CONSUMER).getId(), CONTRACT_URI);
    }

    private PrivacyNotice notice(
            PrivacyNoticePayload payload, UUID consumerId, String contractUri) {
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                contractUri,
                                NOTICE_TITLE,
                                participant(PROVIDER).getId(),
                                consumerId,
                                payload));
        seededNotices.add(notice.getId());
        return notice;
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

    private static PrivacyNoticePayload fullPayload() {
        return payload(NOTICE_PURPOSES, NOTICE_CATEGORIES);
    }

    private static PrivacyNoticePayload payload(List<String> purposes, List<String> categories) {
        return new PrivacyNoticePayload(
                purposes, categories, null, null, null, null, null, null, null);
    }
}
