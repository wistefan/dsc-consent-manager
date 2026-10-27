package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests what a data subject's decision is checked against and what it commits: its notice, scope,
 * superseded consent and chain. Assertions read the database, not the returned record.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent decisions")
class ConsentServiceIT extends PostgresTestResource {

    private static final String PROVIDER = "urn:test:participant:consent-service-provider";

    private static final String CONSUMER = "urn:test:participant:consent-service-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:consent-service";

    private static final String NOTICE_TITLE = "Shared clinical research notice";

    private static final List<String> NOTICE_PURPOSES = List.of("research", "billing");

    private static final List<String> NOTICE_CATEGORIES = List.of("contact", "clinical");

    /** Outside both notice lists, so an election naming it can only be refused. */
    private static final String UNDECLARED = "marketing";

    /** What {@code consent_events.event_type} reads for a move no actor asked for. */
    private static final String SYSTEM_EVENT_TYPE = "system";

    @Inject ConsentService service;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    /** Decisions a subject can reach this service with, and the trail each leaves. */
    static List<Arguments> decisions() {
        return List.of(
                Arguments.of(ConsentStatus.GRANTED, true, ConsentEventState.CONSENT_GIVEN),
                Arguments.of(ConsentStatus.REFUSED, false, ConsentEventState.CONSENT_REFUSED));
    }

    @ParameterizedTest(name = "{0} is stored with consented={1} and one {2} event")
    @MethodSource("decisions")
    @DisplayName("a decision is stored with the flag and the single event its status implies")
    void storesTheDecisionWithItsTrail(
            ConsentStatus decision, boolean consented, ConsentEventState state) {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload());

        ConsentDetails details = decide(subject, notice, decision, null, null);

        Consent stored = consents.findById(details.consent().getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(decision);
        assertThat(stored.isConsented())
                .as("the flag is derived from the status, never from the request")
                .isEqualTo(consented);
        assertThat(stored.getContractUri()).isEqualTo(CONTRACT_URI);
        assertThat(details.providerIdentifier()).isEqualTo(PROVIDER);
        assertThat(details.consumerIdentifier()).isEqualTo(CONSUMER);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(stored.getId()))
                .as("a creation leaves exactly one event, recording the decision taken")
                .singleElement()
                .satisfies(event -> assertThat(event.getEventState()).isEqualTo(state));
    }

    @Test
    @DisplayName("an absent election freezes the notice's own scope")
    void freezesTheWholeNoticeScopeWhenNothingIsElected() {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload());

        ConsentDetails details = decide(subject, notice, ConsentStatus.GRANTED, null, null);

        assertThat(details.consent().getSnapshot().title()).isEqualTo(NOTICE_TITLE);
        assertThat(details.consent().getSnapshot().purposes()).isEqualTo(NOTICE_PURPOSES);
        assertThat(details.consent().getSnapshot().dataCategories()).isEqualTo(NOTICE_CATEGORIES);
    }

    @Test
    @DisplayName("an election narrows the frozen scope to what was elected")
    void freezesOnlyTheElectedScope() {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload());
        List<String> purposes = List.of(NOTICE_PURPOSES.getFirst());
        List<String> categories = List.of(NOTICE_CATEGORIES.getLast());

        ConsentDetails details =
                decide(subject, notice, ConsentStatus.GRANTED, purposes, categories);

        assertThat(details.consent().getSnapshot().purposes()).isEqualTo(purposes);
        assertThat(details.consent().getSnapshot().dataCategories()).isEqualTo(categories);
    }

    @Test
    @DisplayName("a notice that does not exist is a 404")
    void refusesAnUnknownNotice() {
        User subject = user();

        assertThatThrownBy(
                        () ->
                                service.decide(
                                        subject,
                                        new ConsentDecision(
                                                UUID.randomUUID(),
                                                ConsentStatus.GRANTED,
                                                null,
                                                null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("terms no longer in force cannot be consented to")
    void refusesAnArchivedNotice() {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload());
        archive(notice);

        assertThatThrownBy(() -> decide(subject, notice, ConsentStatus.GRANTED, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("archived");
    }

    @Test
    @DisplayName("a notice naming no consumer is refused rather than written through")
    void refusesANoticeWithoutAConsumer() {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload(), null);

        assertThatThrownBy(() -> decide(subject, notice, ConsentStatus.GRANTED, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no data consumer");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedElections")
    @DisplayName("an election the notice cannot honour is a 400")
    void refusesAnElectionOutsideTheNotice(
            String name,
            PrivacyNoticePayload payload,
            List<String> purposes,
            List<String> categories,
            String expectedMessage) {
        User subject = user();
        PrivacyNotice notice = notice(payload);

        assertThatThrownBy(
                        () -> decide(subject, notice, ConsentStatus.GRANTED, purposes, categories))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(expectedMessage);
        assertThat(consents.findByPrivacyNoticeId(notice.getId()))
                .as("a refused decision writes nothing")
                .isEmpty();
    }

    /** Every way an election can fail, each naming the heading the message must point at. */
    static List<Arguments> malformedElections() {
        return List.of(
                Arguments.of(
                        "a purpose the notice never declared",
                        fullPayload(),
                        List.of(UNDECLARED),
                        null,
                        "purposes"),
                Arguments.of(
                        "a data category the notice never declared",
                        fullPayload(),
                        null,
                        List.of(UNDECLARED),
                        "categories of data"),
                Arguments.of(
                        "electing no purpose at all",
                        fullPayload(),
                        List.of(),
                        null,
                        "electing none is not a narrowing"),
                Arguments.of(
                        "electing no data category at all",
                        fullPayload(),
                        null,
                        List.of(),
                        "electing none is not a narrowing"),
                Arguments.of(
                        "a purpose against a notice declaring none",
                        payload(null, NOTICE_CATEGORIES),
                        List.of(UNDECLARED),
                        null,
                        "declares no purposes"),
                Arguments.of(
                        "a data category against a notice declaring none",
                        payload(NOTICE_PURPOSES, null),
                        null,
                        List.of(UNDECLARED),
                        "declares no categories of data"));
    }

    @Test
    @DisplayName("a notice that declares no scope freezes an absent one, not an empty one")
    void freezesAnAbsentScopeForANoticeThatDeclaresNone() {
        User subject = user();
        PrivacyNotice notice = notice(payload(null, null));

        ConsentDetails details = decide(subject, notice, ConsentStatus.GRANTED, null, null);

        assertThat(details.consent().getSnapshot().purposes())
                .as(
                        "a consent against a notice naming no purposes says so, rather than claiming"
                                + " an empty set was elected")
                .isNull();
        assertThat(details.consent().getSnapshot().dataCategories()).isNull();
    }

    @Test
    @DisplayName("granting again revokes the consent in force and links the new one to it")
    void supersedesTheConsentInForce() {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload());
        UUID first = decide(subject, notice, ConsentStatus.GRANTED, null, null).consent().getId();

        UUID second = decide(subject, notice, ConsentStatus.GRANTED, null, null).consent().getId();

        assertThat(second).isNotEqualTo(first);
        assertThat(consents.findById(first).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.REVOKED);
        assertThat(consents.findById(second).orElseThrow().getParentConsentId()).isEqualTo(first);
        assertThat(consents.findByUserIdAndStatus(subject.getId(), ConsentStatus.GRANTED))
                .as("the index admits one grant per pair, and the service leaves exactly that")
                .singleElement()
                .satisfies(consent -> assertThat(consent.getId()).isEqualTo(second));
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(first))
                .extracting(ConsentEvent::getEventState)
                .containsExactly(
                        ConsentEventState.CONSENT_GIVEN, ConsentEventState.CONSENT_REVOKED);
    }

    @ParameterizedTest(name = "after a consent left {0}")
    @EnumSource(
            value = ConsentStatus.class,
            names = {"REFUSED", "REVOKED", "EXPIRED", "TERMINATED"})
    @DisplayName("a grant descends from the last consent for the pair, whatever its status")
    void linksToTheLastConsentWhateverItsStatus(ConsentStatus terminal) {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload());
        UUID previous = seedConsent(subject, notice, terminal);

        ConsentDetails details = decide(subject, notice, ConsentStatus.GRANTED, null, null);

        assertThat(details.consent().getId()).isNotEqualTo(previous);
        assertThat(details.created())
                .as("a decision with no open request to answer brings a record into being")
                .isTrue();
        assertThat(consents.findById(details.consent().getId()).orElseThrow().getParentConsentId())
                .as("a terminal status ends the consent, not the lineage")
                .isEqualTo(previous);
    }

    @ParameterizedTest(name = "answering it {0}")
    @EnumSource(
            value = ConsentStatus.class,
            names = {"GRANTED", "REFUSED"})
    @DisplayName("a decision answers an open request in place instead of stranding it")
    void adoptsAnOpenRequest(ConsentStatus decision) {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload());
        UUID requested = seedRequest(subject, notice);

        ConsentDetails details = decide(subject, notice, decision, null, null);

        assertThat(details.consent().getId())
                .as("the request a participant opened is the consent that answers it")
                .isEqualTo(requested);
        assertThat(details.created())
                .as("answering an open request creates nothing, and the API owes a 200 for it")
                .isFalse();
        assertThat(consents.findByPrivacyNoticeId(notice.getId()))
                .as("answering a request creates no second consent for the pair")
                .singleElement()
                .satisfies(
                        consent -> {
                            assertThat(consent.getStatus()).isEqualTo(decision);
                            assertThat(consent.getSnapshot().title())
                                    .as("the terms are re-frozen as the subject elected them now")
                                    .isEqualTo(NOTICE_TITLE);
                        });
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(requested))
                .as("the answer appends one event to the trail the request opened")
                .hasSize(2);
        assertThat(
                        consents.findByUserIdAndStatusIn(
                                subject.getId(), List.of(ConsentStatus.PENDING)))
                .as("nothing is left unanswered, so no later request collides with it")
                .isEmpty();
        assertThat(
                        consents.findByUserIdAndStatusIn(
                                subject.getId(), ConsentStateMachine.OPEN_STATUSES))
                .as("a refusal reopens the pair to a new request; a grant stands in its way")
                .hasSize(decision == ConsentStatus.GRANTED ? 1 : 0);
    }

    @ParameterizedTest(name = "deciding {0}")
    @EnumSource(
            value = ConsentStatus.class,
            names = {"GRANTED", "REFUSED"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a status that is not a decision is refused by the service, not only by the spec")
    void refusesAStatusThatIsNotADecision(ConsentStatus notADecision) {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload());

        assertThatThrownBy(() -> decide(subject, notice, notADecision, null, null))
                .as("a subject consents or does not; nothing else is theirs to record")
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(notADecision.name());
        assertThat(consents.findByPrivacyNoticeId(notice.getId()))
                .as("a refused decision writes nothing at all")
                .isEmpty();
    }

    @Test
    @DisplayName("a decision closes every open request for the pair, not only the one it answers")
    void closesTheRequestsADecisionLeavesRedundant() {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload());
        List<UUID> requested = List.of(seedRequest(subject, notice), seedRequest(subject, notice));

        ConsentDetails details = decide(subject, notice, ConsentStatus.GRANTED, null, null);

        assertThat(details.consent().getId())
                .as("the decision answers one of the requests rather than opening a third consent")
                .isIn(requested);
        assertThat(consents.findByPrivacyNoticeId(notice.getId()))
                .as("the answered request stands; the duplicate is closed, never left stranded")
                .extracting(Consent::getStatus)
                .containsExactlyInAnyOrder(ConsentStatus.GRANTED, ConsentStatus.TERMINATED);
        assertThat(
                        consents.findByUserIdAndStatusIn(
                                subject.getId(), List.of(ConsentStatus.PENDING)))
                .as("no request survives to block every later request for the pair")
                .isEmpty();
        UUID closed =
                requested.stream()
                        .filter(id -> !id.equals(details.consent().getId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(closed))
                .as("the subject answered one offer; closing the rest is the system's doing")
                .last()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_TERMINATED);
                            assertThat(event.getEventType()).isEqualTo(SYSTEM_EVENT_TYPE);
                        });
    }

    @Test
    @DisplayName("the frozen terms outlive the notice they were taken from")
    void keepsTheSnapshotAfterTheNoticeIsArchived() {
        User subject = user();
        PrivacyNotice notice = notice(fullPayload());
        UUID consentId =
                decide(subject, notice, ConsentStatus.GRANTED, null, null).consent().getId();

        archive(notice);

        assertThat(consents.findById(consentId).orElseThrow().getSnapshot())
                .as("an auditor reads what was consented to, not what the notice says now")
                .satisfies(
                        snapshot -> {
                            assertThat(snapshot.title()).isEqualTo(NOTICE_TITLE);
                            assertThat(snapshot.purposes()).isEqualTo(NOTICE_PURPOSES);
                        });
    }

    private ConsentDetails decide(
            User subject,
            PrivacyNotice notice,
            ConsentStatus decision,
            List<String> purposes,
            List<String> categories) {
        return service.decide(
                subject, new ConsentDecision(notice.getId(), decision, purposes, categories));
    }

    /** An unanswered request for the pair, as a participant's ask would have left it. */
    private UUID seedRequest(User subject, PrivacyNotice notice) {
        UUID requested = seedConsent(subject, notice, ConsentStatus.PENDING);
        events.save(
                new ConsentEvent(requested, ConsentEventState.CONSENT_REQUESTED, PROVIDER, null));
        return requested;
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

    private User user() {
        User subject =
                users.save(
                        new User(
                                "urn:test:user:consent-service:" + UUID.randomUUID(),
                                null,
                                null,
                                null));
        return subject;
    }

    private PrivacyNotice notice(PrivacyNoticePayload payload) {
        return notice(payload, participant(CONSUMER).getId());
    }

    private PrivacyNotice notice(PrivacyNoticePayload payload, UUID consumerId) {
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                NOTICE_TITLE,
                                participant(PROVIDER).getId(),
                                consumerId,
                                payload));
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
