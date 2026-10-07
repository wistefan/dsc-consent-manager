package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.error.ConflictException;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Exercises {@link ParticipantService#deregister} against a real PostgreSQL, because the two
 * branches it turns on - delete the record or retain it marked - are decided by foreign keys
 * declared {@code ON DELETE RESTRICT}, which only a real database enforces.
 *
 * <p>Every case puts the departing participant on both sides of the consent or notice in turn: the
 * cascade must not care whether the organization was the one sharing data or the one receiving it.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant deregistration")
class ParticipantDeregistrationIT extends PostgresTestResource {

    private static final String IDENTIFIER_PREFIX = "urn:test:participant:deregistration:";

    private static final String USER_IDENTIFIER_PREFIX = "urn:test:user:deregistration:";

    private static final String LEGAL_NAME = "Departing Clinic Ltd";

    private static final String SELF_DESCRIPTION_URI = "https://departing.example.org/sd.json";

    private static final String CONTACT_EMAIL = "privacy-officer@departing.example.org";

    private static final Map<String, Object> ENDPOINTS =
            Map.of("consentNotification", "https://departing.example.org/notify");

    private static final Map<String, Object> LEGAL_PERSON = Map.of("registrationNumber", "DE4711");

    private static final String REREGISTERED_LEGAL_NAME = "Returning Clinic Ltd";

    private static final String REREGISTERED_EMAIL = "dpo@returning.example.org";

    private static final String CONTRACT_URI = "urn:test:contract:deregistration";

    private static final String LOCAL_IDENTIFIER = "patient-0042";

    /** An event already on the trail before the cascade runs, so retention can be told apart. */
    private static final ConsentEventState PRIOR_EVENT_STATE = ConsentEventState.CONSENT_REQUESTED;

    @Inject ParticipantService service;

    @Inject ParticipantRepository participants;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject UserParticipantRepository links;

    @Inject UserRepository users;

    /**
     * Rows this test commits outlive its own transaction, so they are tracked and removed by hand.
     */
    private final List<Participant> seeded = new ArrayList<>();

    private User user;

    @BeforeEach
    void seedUser() {
        user = users.save(new User(USER_IDENTIFIER_PREFIX + UUID.randomUUID(), null, null, null));
    }

    @AfterEach
    void removeCommittedRows() {
        Set<UUID> ids = new HashSet<>();
        seeded.forEach(participant -> ids.add(participant.getId()));
        consents.findAll()
                .forEach(
                        consent -> {
                            if (names(ids, consent.getProviderId(), consent.getConsumerId())) {
                                events.findByConsentIdOrderByOccurredAtAsc(consent.getId())
                                        .forEach(events::delete);
                                consents.delete(consent);
                            }
                        });
        notices.findAll()
                .forEach(
                        notice -> {
                            if (names(ids, notice.getProviderId(), notice.getConsumerId())) {
                                notices.delete(notice);
                            }
                        });
        ids.forEach(links::deleteByIdParticipantId);
        ids.forEach(id -> participants.findById(id).ifPresent(participants::delete));
        users.delete(user);
        seeded.clear();
    }

    @ParameterizedTest(name = "as {0}")
    @EnumSource(Side.class)
    @DisplayName("refuses while a consent naming the participant is still granted")
    void refusesWhileAConsentIsGranted(Side side) {
        Participant subject = seedParticipant();
        Participant other = seedParticipant();
        UUID consentId = seedConsent(subject, other, side, ConsentStatus.GRANTED).getId();
        link(subject);

        assertThatThrownBy(() -> service.deregister(subject))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(subject.getIdentifier())
                .hasMessageContaining(consentId.toString());

        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .as("the refusal leaves the permission the user gave exactly as it was")
                .isEqualTo(ConsentStatus.GRANTED);
        assertThat(links.findByIdParticipantId(subject.getId()))
                .as("and nothing else of the cascade ran either")
                .hasSize(1);
    }

    @ParameterizedTest(name = "{0} as {1}")
    @CsvSource({"PENDING, PROVIDER", "PENDING, CONSUMER", "DRAFT, PROVIDER", "DRAFT, CONSUMER"})
    @DisplayName("terminates an unanswered consent so nothing can advance it afterwards")
    void terminatesUnansweredConsents(ConsentStatus status, Side side) {
        Participant subject = seedParticipant();
        UUID consentId = seedConsent(subject, seedParticipant(), side, status).getId();

        DeregistrationResult result = service.deregister(subject);

        assertThat(result.consentsTerminated()).isEqualTo(1);
        Consent stored = consents.findById(consentId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ConsentStatus.TERMINATED);
        assertThat(stored.isConsented()).isFalse();
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .extracting(ConsentEvent::getEventState)
                .containsExactly(PRIOR_EVENT_STATE, ConsentEventState.CONSENT_TERMINATED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = ConsentStatus.class,
            names = {"REVOKED", "EXPIRED", "TERMINATED", "REFUSED"})
    @DisplayName("leaves a consent that was already decided untouched")
    void leavesDecidedConsentsAlone(ConsentStatus status) {
        Participant subject = seedParticipant();
        UUID consentId = seedConsent(subject, seedParticipant(), Side.PROVIDER, status).getId();

        DeregistrationResult result = service.deregister(subject);

        assertThat(result.consentsTerminated()).isZero();
        assertThat(result.consentsRetained())
                .as("a closed consent is evidence of what was permitted and is kept")
                .isEqualTo(1);
        assertThat(consents.findById(consentId).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .extracting(ConsentEvent::getEventState)
                .containsExactly(PRIOR_EVENT_STATE);
    }

    @Test
    @DisplayName("removes every affiliation on the branch that keeps the record")
    void removesLinksWhenTheRecordIsRetained() {
        Participant subject = seedParticipant();
        seedNotice(subject, seedParticipant(), Side.PROVIDER, null);
        link(subject);

        DeregistrationResult result = service.deregister(subject);

        assertThat(result.linksRemoved()).isEqualTo(1);
        assertThat(links.findByIdParticipantId(subject.getId())).isEmpty();
        assertThat(participants.findById(subject.getId()))
                .as("the notice still refers to the record, so it has to stay")
                .isPresent();
    }

    @Test
    @DisplayName("deletes the record outright when nothing refers to it")
    void deletesTheRecordWhenUnreferenced() {
        Participant subject = seedParticipant();
        link(subject);

        DeregistrationResult result = service.deregister(subject);

        assertThat(result.deregisteredAt()).isNull();
        assertThat(result.linksRemoved()).isEqualTo(1);
        assertThat(result.consentsRetained()).isZero();
        assertThat(participants.findById(subject.getId())).isEmpty();
        assertThat(links.findByIdParticipantId(subject.getId())).isEmpty();
    }

    @ParameterizedTest(name = "as {0}")
    @EnumSource(Side.class)
    @DisplayName("archives the live notices and leaves the archived ones at their own timestamp")
    void archivesLiveNoticesOnly(Side side) {
        Participant subject = seedParticipant();
        Participant other = seedParticipant();
        Instant archivedEarlier = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        UUID live = seedNotice(subject, other, side, null).getId();
        UUID archived = seedNotice(subject, other, side, archivedEarlier).getId();

        DeregistrationResult result = service.deregister(subject);

        assertThat(result.noticesArchived()).isEqualTo(1);
        assertThat(notices.findById(live).orElseThrow().getArchivedAt()).isNotNull();
        assertThat(notices.findById(archived).orElseThrow().getArchivedAt())
                .isEqualTo(archivedEarlier);
    }

    @Test
    @DisplayName("keeps the retained record legible and clears only what is a person's")
    void retainsIdentityAndClearsContactDetails() {
        Participant subject = registerThroughTheService();
        seedNotice(subject, seedParticipant(), Side.PROVIDER, null);

        DeregistrationResult result = service.deregister(subject);

        Participant stored = participants.findById(subject.getId()).orElseThrow();
        assertThat(stored.getDeregisteredAt()).isNotNull().isEqualTo(result.deregisteredAt());
        assertThat(stored.getIdentifier()).isEqualTo(subject.getIdentifier());
        assertThat(stored.getLegalName()).isEqualTo(LEGAL_NAME);
        assertThat(stored.getSelfDescriptionUri()).isEqualTo(SELF_DESCRIPTION_URI);
        assertThat(stored.getLegalPerson()).isEqualTo(LEGAL_PERSON);
        assertThat(stored.getEmail())
                .as("the one field on the row that is a natural person's")
                .isNull();
        assertThat(stored.getEndpoints())
                .as("a departed participant must not keep advertising a callback")
                .isEmpty();
    }

    @Test
    @DisplayName("leaves the consents and their trails readable afterwards")
    void keepsConsentsAndTrailsReadable() {
        Participant subject = seedParticipant();
        UUID consentId =
                seedConsent(subject, seedParticipant(), Side.CONSUMER, ConsentStatus.PENDING)
                        .getId();

        DeregistrationResult result = service.deregister(subject);

        assertThat(result.consentsRetained()).isEqualTo(1);
        assertThat(consents.findById(consentId)).isPresent();
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).hasSize(2);
    }

    @Test
    @DisplayName("reactivates the record when the same identifier registers again")
    void reactivatesADeregisteredIdentifier() {
        Participant subject = registerThroughTheService();
        seedNotice(subject, seedParticipant(), Side.PROVIDER, null);
        service.deregister(subject);

        Participant reactivated =
                service.register(
                        subject.getIdentifier(),
                        new ParticipantRegistration(
                                REREGISTERED_LEGAL_NAME,
                                SELF_DESCRIPTION_URI,
                                REREGISTERED_EMAIL,
                                ENDPOINTS,
                                LEGAL_PERSON));

        assertThat(reactivated.getId())
                .as("the unique identifier makes a second row impossible")
                .isEqualTo(subject.getId());
        Participant stored = participants.findById(subject.getId()).orElseThrow();
        assertThat(stored.getDeregisteredAt()).isNull();
        assertThat(stored.getLegalName()).isEqualTo(REREGISTERED_LEGAL_NAME);
        assertThat(stored.getEmail()).isEqualTo(REREGISTERED_EMAIL);
        assertThat(stored.getEndpoints()).isEqualTo(ENDPOINTS);
    }

    @Test
    @DisplayName("refuses a second registration while the record is still active")
    void refusesAReRegistrationWhileActive() {
        Participant subject = registerThroughTheService();

        assertThatThrownBy(
                        () ->
                                service.register(
                                        subject.getIdentifier(),
                                        new ParticipantRegistration(
                                                REREGISTERED_LEGAL_NAME,
                                                null,
                                                null,
                                                Map.of(),
                                                null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(subject.getIdentifier());
    }

    @Test
    @DisplayName("refuses a second deregistration rather than moving the recorded departure")
    void refusesASecondDeregistration() {
        Participant subject = registerThroughTheService();
        seedNotice(subject, seedParticipant(), Side.PROVIDER, null);
        Instant departedAt = service.deregister(subject).deregisteredAt();

        assertThatThrownBy(() -> service.deregister(subject))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(subject.getIdentifier());

        assertThat(participants.findById(subject.getId()).orElseThrow().getDeregisteredAt())
                .isEqualTo(departedAt);
    }

    /**
     * The caller hands in the row as it stood when the request was authenticated. Writing that
     * snapshot back would revert anything committed since, so the cascade re-reads under a lock.
     */
    @Test
    @DisplayName("writes the row as it stands, not the stale snapshot it was handed")
    void doesNotRevertAnUpdateCommittedSinceAuthentication() {
        Participant snapshot = registerThroughTheService();
        seedNotice(snapshot, seedParticipant(), Side.PROVIDER, null);
        service.update(
                snapshot,
                new ParticipantUpdate(
                        REREGISTERED_LEGAL_NAME,
                        SELF_DESCRIPTION_URI,
                        CONTACT_EMAIL,
                        ENDPOINTS,
                        LEGAL_PERSON));

        service.deregister(snapshot);

        assertThat(participants.findById(snapshot.getId()).orElseThrow().getLegalName())
                .isEqualTo(REREGISTERED_LEGAL_NAME);
    }

    /** Which side of a consent or privacy notice the departing participant stands on. */
    enum Side {
        PROVIDER,
        CONSUMER
    }

    private static boolean names(Set<UUID> ids, UUID providerId, UUID consumerId) {
        return ids.contains(providerId) || ids.contains(consumerId);
    }

    private Participant registerThroughTheService() {
        Participant registered =
                service.register(
                        IDENTIFIER_PREFIX + UUID.randomUUID(),
                        new ParticipantRegistration(
                                LEGAL_NAME,
                                SELF_DESCRIPTION_URI,
                                CONTACT_EMAIL,
                                ENDPOINTS,
                                LEGAL_PERSON));
        seeded.add(registered);
        return registered;
    }

    private Participant seedParticipant() {
        Participant saved =
                participants.save(
                        new Participant(
                                IDENTIFIER_PREFIX + UUID.randomUUID(),
                                LEGAL_NAME,
                                SELF_DESCRIPTION_URI,
                                CONTACT_EMAIL,
                                ENDPOINTS,
                                LEGAL_PERSON));
        seeded.add(saved);
        return saved;
    }

    /** Affiliates the test's user with the participant, so the cascade has a link to remove. */
    private void link(Participant participant) {
        links.save(new UserParticipant(user.getId(), participant.getId(), LOCAL_IDENTIFIER));
    }

    /** A notice between the two participants, with the subject on the requested side. */
    private PrivacyNotice seedNotice(
            Participant subject, Participant other, Side side, Instant archivedAt) {
        PrivacyNotice notice =
                new PrivacyNotice(
                        CONTRACT_URI,
                        null,
                        side == Side.PROVIDER ? subject.getId() : other.getId(),
                        side == Side.PROVIDER ? other.getId() : subject.getId(),
                        new PrivacyNoticePayload(
                                null, null, null, null, null, null, null, null, null));
        notice.setArchivedAt(archivedAt);
        return notices.save(notice);
    }

    /** A consent in the given status, with one event already on its trail. */
    private Consent seedConsent(
            Participant subject, Participant other, Side side, ConsentStatus status) {
        PrivacyNotice notice = seedNotice(subject, other, side, null);
        Consent consent =
                consents.save(
                        Consent.withStatus(
                                status,
                                user.getId(),
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                new ConsentSnapshot(null, null, null, null, null)));
        events.save(new ConsentEvent(consent.getId(), PRIOR_EVENT_STATE, null, null));
        return consent;
    }
}
