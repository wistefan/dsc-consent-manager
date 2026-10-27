package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static com.seamware.consentmanager.support.ConsentFixtures.principalFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.CatalogPrincipal;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Each lifecycle move's own rules: who may ask, what it supersedes, and the 404/403 for a caller
 * with no standing. Permitted starting statuses belong to {@code ConsentLifecycleMatrixIT}.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent lifecycle moves")
class ConsentLifecycleIT extends PostgresTestResource {

    private static final String PROVIDER = "urn:test:participant:consent-lifecycle-provider";

    private static final String CONSUMER = "urn:test:participant:consent-lifecycle-consumer";

    /** A participant the seeded consents name on neither side, so it has no standing to end one. */
    private static final String STRANGER = "urn:test:participant:consent-lifecycle-stranger";

    private static final String CONTRACT_URI = "urn:test:contract:consent-lifecycle";

    /** A notice archived since the request was opened, which no grant may be recorded against. */
    private static final String ARCHIVED_CONTRACT_URI = "urn:test:contract:consent-lifecycle-gone";

    /** A notice that names no consumer. */
    private static final String UNNAMED_CONSUMER_CONTRACT_URI =
            "urn:test:contract:consent-lifecycle-unnamed";

    private static final String NOTICE_TITLE = "Notice the lifecycle moves act on";

    /** The one elected term a resumed request carries, so keeping it can be asserted. */
    private static final String SEEDED_PURPOSE = "research the request was opened for";

    /** What {@code consent_events.event_type} reads for a move an identified caller asked for. */
    private static final String EXPLICIT_EVENT_TYPE = "explicit";

    /** What it reads for a move no one asked for, such as closing a redundant request. */
    private static final String CLEANUP_EVENT_TYPE = "system";

    /** Issuer of a token this suite never validates; only its shape matters. */
    private static final String ISSUER = "https://issuer.test/realms/consent-lifecycle";

    private static final String CATALOG_SUBJECT = "catalog-service";

    /** How a relaying catalog names itself, which is the one thing a relayed grant reads by. */
    private static final String CATALOG_ACTOR = "catalog:" + ISSUER + ":" + CATALOG_SUBJECT;

    @Inject ConsentLifecycleService lifecycle;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @ParameterizedTest(name = "{0}")
    @EnumSource(Move.class)
    @DisplayName("a consent belonging to another subject is the 404 an unknown one is")
    void refusesAConsentBelongingToAnotherSubject(Move move) {
        User subject = user();
        User other = user();
        UUID theirs = seedConsent(other, notice(), ConsentStatus.GRANTED);

        assertThatThrownBy(() -> move.apply(lifecycle, subject, theirs))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(theirs.toString());

        assertThat(consents.findById(theirs).orElseThrow().getStatus())
                .as("the other subject's record is untouched by the probe")
                .isEqualTo(ConsentStatus.GRANTED);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(theirs)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Move.class)
    @DisplayName("a consent nothing stores is a 404")
    void refusesAnUnknownConsent(Move move) {
        User subject = user();
        UUID absent = UUID.randomUUID();

        assertThatThrownBy(() -> move.apply(lifecycle, subject, absent))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(absent.toString());
    }

    @ParameterizedTest(name = "{0} terminates")
    @ValueSource(strings = {PROVIDER, CONSUMER})
    @DisplayName("either party terminates the consent and the trail names the one that did")
    void terminatesAsEitherParty(String party) {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.GRANTED);

        ConsentDetails terminated =
                lifecycle.terminate(participant(participants, party), consentId);

        assertThat(terminated.consent().getStatus()).isEqualTo(ConsentStatus.TERMINATED);
        assertThat(consents.findById(consentId).orElseThrow())
                .satisfies(
                        stored -> {
                            assertThat(stored.getStatus()).isEqualTo(ConsentStatus.TERMINATED);
                            assertThat(stored.isConsented()).isFalse();
                        });
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_TERMINATED);
                            assertThat(event.getEventType()).isEqualTo(EXPLICIT_EVENT_TYPE);
                            assertThat(event.getActor())
                                    .as("the trail names the participant that ended it")
                                    .isEqualTo(party);
                        });
    }

    @Test
    @DisplayName(
            "a participant the consent names on neither side is refused, not told it is absent")
    void refusesAParticipantThatIsNoParty() {
        UUID consentId = seedConsent(user(), notice(), ConsentStatus.GRANTED);
        Participant stranger = participant(participants, STRANGER);

        assertThatThrownBy(() -> lifecycle.terminate(stranger, consentId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(STRANGER)
                .hasMessageContaining(consentId.toString());

        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.GRANTED);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @Test
    @DisplayName("a consent nothing stores is a 404 before the caller's standing is weighed")
    void refusesTerminatingAnUnknownConsent() {
        UUID absent = UUID.randomUUID();
        Participant stranger = participant(participants, STRANGER);

        assertThatThrownBy(() -> lifecycle.terminate(stranger, absent))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(absent.toString());
    }

    @Test
    @DisplayName("re-confirmation appends one event and leaves the record it affirms alone")
    void reConfirmsWithoutTouchingTheRecord() {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.GRANTED);
        Instant seededAt = consents.findById(consentId).orElseThrow().getUpdatedAt();

        ConsentDetails affirmed = lifecycle.reConfirm(subject, consentId);

        assertThat(affirmed.consent().getStatus()).isEqualTo(ConsentStatus.GRANTED);
        assertThat(affirmed.created()).isFalse();
        assertThat(consents.findById(consentId).orElseThrow())
                .satisfies(
                        stored -> {
                            assertThat(stored.getStatus()).isEqualTo(ConsentStatus.GRANTED);
                            assertThat(stored.isConsented()).isTrue();
                            assertThat(stored.getUpdatedAt())
                                    .as("updatedAt dates state changes, and this is not one")
                                    .isEqualTo(seededAt);
                        });
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .as("the affirmation shows only in the trail, dated in its own right")
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_RE_CONFIRMED);
                            assertThat(event.getEventType()).isEqualTo(EXPLICIT_EVENT_TYPE);
                            assertThat(event.getActor()).isEqualTo(subject.getIdentifier());
                        });
    }

    @Test
    @DisplayName("re-confirming another subject's consent is the 404 an unknown one is")
    void refusesReConfirmingAnotherSubjectsConsent() {
        User subject = user();
        UUID theirs = seedConsent(user(), notice(), ConsentStatus.GRANTED);

        assertThatThrownBy(() -> lifecycle.reConfirm(subject, theirs))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(theirs.toString());

        assertThat(events.findByConsentIdOrderByOccurredAtAsc(theirs))
                .as("the other subject's trail is untouched by the probe")
                .isEmpty();
    }

    @Test
    @DisplayName("resuming revokes the grant in force and names it as what the decision replaced")
    void supersedesTheGrantInForce() {
        User subject = user();
        PrivacyNotice notice = notice();
        UUID inForce = seedConsent(subject, notice, ConsentStatus.GRANTED);
        UUID requested = seedConsent(subject, notice, ConsentStatus.PENDING);

        lifecycle.resume(principalFor(ISSUER, subject), requested, null);

        assertThat(consents.findById(requested).orElseThrow())
                .satisfies(
                        stored -> {
                            assertThat(stored.getStatus()).isEqualTo(ConsentStatus.GRANTED);
                            assertThat(stored.getParentConsentId())
                                    .as("the resumed record names the grant its decision closed")
                                    .isEqualTo(inForce);
                        });
        assertThat(consents.findById(inForce).orElseThrow())
                .satisfies(
                        stored -> {
                            assertThat(stored.getStatus()).isEqualTo(ConsentStatus.REVOKED);
                            assertThat(stored.isConsented()).isFalse();
                        });
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(inForce))
                .as("the closing shows in the closed record's own trail")
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_REVOKED);
                            assertThat(event.getActor()).isEqualTo(subject.getIdentifier());
                        });
    }

    @Test
    @DisplayName("resuming with nothing in force leaves the parent the request was opened with")
    void keepsTheParentWhereNothingWasInForce() {
        User subject = user();
        PrivacyNotice notice = notice();
        UUID closed = seedConsent(subject, notice, ConsentStatus.REFUSED);
        UUID requested = seedConsent(subject, notice, ConsentStatus.PENDING, closed, noTerms());

        lifecycle.resume(principalFor(ISSUER, subject), requested, null);

        assertThat(consents.findById(requested).orElseThrow().getParentConsentId())
                .as("the link records what a decision closed, so nothing closed writes nothing")
                .isEqualTo(closed);
        assertThat(consents.findById(closed).orElseThrow().getStatus())
                .as("a record already closed is not closed again")
                .isEqualTo(ConsentStatus.REFUSED);
    }

    @Test
    @DisplayName("a relayed resume grants the consent and the trail names the relaying catalog")
    void relayedResumeNamesTheCatalog() {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.PENDING);

        ConsentDetails resumed =
                lifecycle.resume(
                        new CatalogPrincipal(ISSUER, CATALOG_SUBJECT),
                        consentId,
                        subject.getIdentifier());

        assertThat(resumed.consent().getStatus()).isEqualTo(ConsentStatus.GRANTED);
        assertThat(consents.findById(consentId).orElseThrow().isConsented()).isTrue();
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_RESUMED);
                            assertThat(event.getEventType()).isEqualTo(EXPLICIT_EVENT_TYPE);
                            assertThat(event.getActor())
                                    .as("a relayed grant never reads as one the subject made here")
                                    .isEqualTo(CATALOG_ACTOR);
                        });
    }

    @Test
    @DisplayName("a relayed resume names the relaying catalog on the grant it closed too")
    void relayedResumeNamesTheCatalogOnWhatItClosed() {
        User subject = user();
        PrivacyNotice notice = notice();
        UUID inForce = seedConsent(subject, notice, ConsentStatus.GRANTED);
        UUID requested = seedConsent(subject, notice, ConsentStatus.PENDING);

        lifecycle.resume(
                new CatalogPrincipal(ISSUER, CATALOG_SUBJECT), requested, subject.getIdentifier());

        assertThat(consents.findById(requested).orElseThrow().getParentConsentId())
                .as("the resumed record names the grant the relayed decision closed")
                .isEqualTo(inForce);
        assertThat(consents.findById(inForce).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.REVOKED);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(inForce))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_REVOKED);
                            assertThat(event.getActor())
                                    .as("the trail shows the relay closed it, not the subject")
                                    .isEqualTo(CATALOG_ACTOR);
                        });
    }

    @Test
    @DisplayName("resuming closes the open requests its answer leaves redundant")
    void closesTheRequestsTheAnswerMakesRedundant() {
        User subject = user();
        PrivacyNotice notice = notice();
        UUID straggler = seedConsent(subject, notice, ConsentStatus.PENDING);
        UUID requested = seedConsent(subject, notice, ConsentStatus.PENDING);

        lifecycle.resume(principalFor(ISSUER, subject), requested, null);

        assertThat(consents.findById(requested).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.GRANTED);
        assertThat(consents.findById(straggler).orElseThrow().getStatus())
                .as("a question left standing refuses every later request for the pair")
                .isEqualTo(ConsentStatus.TERMINATED);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(straggler))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_TERMINATED);
                            assertThat(event.getEventType())
                                    .as("no one asked for this close; the answer made it redundant")
                                    .isEqualTo(CLEANUP_EVENT_TYPE);
                        });
    }

    @ParameterizedTest(name = "userIdentifier [{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("a relay naming no subject is a 400 and writes nothing")
    void refusesARelayNamingNoSubject(String userIdentifier) {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.PENDING);

        assertThatThrownBy(
                        () ->
                                lifecycle.resume(
                                        new CatalogPrincipal(ISSUER, CATALOG_SUBJECT),
                                        consentId,
                                        userIdentifier))
                .isInstanceOf(BadRequestException.class);

        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.PENDING);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @Test
    @DisplayName("a relay naming anybody but the consent's subject is the 404 an unknown one is")
    void refusesARelayNamingAnotherSubject() {
        UUID consentId = seedConsent(user(), notice(), ConsentStatus.PENDING);
        User stranger = user();

        assertThatThrownBy(
                        () ->
                                lifecycle.resume(
                                        new CatalogPrincipal(ISSUER, CATALOG_SUBJECT),
                                        consentId,
                                        stranger.getIdentifier()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(consentId.toString());

        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.PENDING);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @Test
    @DisplayName("resuming another subject's consent is the 404 an unknown one is")
    void refusesResumingAnotherSubjectsConsent() {
        User subject = user();
        UUID theirs = seedConsent(user(), notice(), ConsentStatus.PENDING);

        assertThatThrownBy(() -> lifecycle.resume(principalFor(ISSUER, subject), theirs, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(theirs.toString());

        assertThat(events.findByConsentIdOrderByOccurredAtAsc(theirs)).isEmpty();
    }

    @Test
    @DisplayName("a participant records no decision, which the route's roles already refuse")
    void refusesAParticipantResuming() {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.PENDING);
        Participant party = participant(participants, PROVIDER);

        assertThatThrownBy(
                        () ->
                                lifecycle.resume(
                                        new ParticipantPrincipal(ISSUER, PROVIDER, PROVIDER, party),
                                        consentId,
                                        subject.getIdentifier()))
                .isInstanceOf(ForbiddenException.class);

        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @ParameterizedTest(name = "a notice that {0}")
    @EnumSource(Unconsentable.class)
    @DisplayName("a request outliving the terms it was opened against is a 400 and writes nothing")
    void refusesResumingAgainstANoticeNoGrantMayName(Unconsentable kind) {
        User subject = user();
        PrivacyNotice notice = unconsentable(kind);
        UUID consentId = seedConsent(subject, notice, ConsentStatus.PENDING);

        assertThatThrownBy(() -> lifecycle.resume(principalFor(ISSUER, subject), consentId, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(notice.getId().toString());

        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.PENDING);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @Test
    @DisplayName("resuming keeps the terms the request was opened with")
    void keepsTheTermsTheRequestWasOpenedWith() {
        User subject = user();
        ConsentSnapshot opened =
                new ConsentSnapshot(
                        NOTICE_TITLE,
                        List.of(SEEDED_PURPOSE),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.PENDING, null, opened);

        lifecycle.resume(principalFor(ISSUER, subject), consentId, null);

        assertThat(consents.findById(consentId).orElseThrow().getSnapshot())
                .as("a resume records a decision taken elsewhere rather than electing scope here")
                .isEqualTo(opened);
    }

    /** The two ways a notice stops being one a grant may be recorded against. */
    private enum Unconsentable {
        HAS_BEEN_ARCHIVED,
        NAMES_NO_CONSUMER
    }

    /** A notice the guard refuses, on its own contract so the active-notice index stays free. */
    private PrivacyNotice unconsentable(Unconsentable kind) {
        PrivacyNotice notice =
                new PrivacyNotice(
                        switch (kind) {
                            case HAS_BEEN_ARCHIVED -> ARCHIVED_CONTRACT_URI;
                            case NAMES_NO_CONSUMER -> UNNAMED_CONSUMER_CONTRACT_URI;
                        },
                        NOTICE_TITLE,
                        participant(participants, PROVIDER).getId(),
                        kind == Unconsentable.NAMES_NO_CONSUMER
                                ? null
                                : participant(participants, CONSUMER).getId(),
                        PrivacyNoticeFixtures.emptyPayload());
        if (kind == Unconsentable.HAS_BEEN_ARCHIVED) {
            notice.setArchivedAt(Instant.now());
        }
        PrivacyNotice saved = notices.save(notice);
        return saved;
    }

    /** A consent for the pair as some other flow would have left it, with no trail of its own. */
    private UUID seedConsent(User subject, PrivacyNotice notice, ConsentStatus status) {
        return seedConsent(subject, notice, status, null, noTerms());
    }

    /**
     * The same, with a parent and terms. The row always names a consumer, as {@code
     * consents.consumer_id} is {@code NOT NULL}; the guard reads the notice, not the row.
     */
    private UUID seedConsent(
            User subject,
            PrivacyNotice notice,
            ConsentStatus status,
            UUID parentConsentId,
            ConsentSnapshot snapshot) {
        UUID consumerId =
                notice.getConsumerId() == null
                        ? participant(participants, CONSUMER).getId()
                        : notice.getConsumerId();
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                notice.getId(),
                                notice.getProviderId(),
                                consumerId,
                                parentConsentId,
                                CONTRACT_URI,
                                snapshot))
                .getId();
    }

    private User user() {
        User subject =
                users.save(
                        new User(
                                "urn:test:user:consent-lifecycle:" + UUID.randomUUID(),
                                null,
                                null,
                                null));
        return subject;
    }

    private PrivacyNotice notice() {
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                NOTICE_TITLE,
                                participant(participants, PROVIDER).getId(),
                                participant(participants, CONSUMER).getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        return notice;
    }

    /** The moves a subject asks for; transition rules stay in {@code ConsentLifecycleMatrixIT}. */
    private enum Move {
        REVOKE,
        REFUSE,
        RESUME;

        ConsentDetails apply(ConsentLifecycleService lifecycle, User subject, UUID consentId) {
            return switch (this) {
                case REVOKE -> lifecycle.revoke(subject, consentId);
                case REFUSE -> lifecycle.refuse(subject, consentId);
                case RESUME -> lifecycle.resume(principalFor(ISSUER, subject), consentId, null);
            };
        }
    }
}
