package com.seamware.consentmanager.notification;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static com.seamware.consentmanager.support.ConsentFixtures.principalFor;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.service.ConsentDecision;
import com.seamware.consentmanager.service.ConsentLifecycleService;
import com.seamware.consentmanager.service.ConsentRequestCommand;
import com.seamware.consentmanager.service.ConsentService;
import com.seamware.consentmanager.service.ConsentWriter;
import com.seamware.consentmanager.service.ParticipantService;
import com.seamware.consentmanager.service.UserService;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import com.seamware.consentmanager.support.RawSql;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Drives every consent write path against a real database and asserts the exact outbox rows it
 * committed: which moves notify, whom, and with what frozen payload (AC1–AC6).
 */
@MicronautTest(transactional = false)
@DisplayName("Participant notification enqueue")
class ParticipantNotificationEnqueueIT extends PostgresTestResource {

    private static final String PROVIDER = "urn:test:participant:enqueue-provider";

    private static final String CONSUMER = "urn:test:participant:enqueue-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:enqueue";

    private static final String ISSUER = "urn:test:issuer:enqueue";

    private static final String SYSTEM_ACTOR = "system";

    private static final String LOCAL_IDENTIFIER = "enqueue-local";

    /** Every key a payload may carry; anything else (snapshot, notice content) is a leak. */
    private static final Set<String> PAYLOAD_KEYS =
            Set.of(
                    "eventType",
                    "eventId",
                    "occurredAt",
                    "consentId",
                    "privacyNoticeId",
                    "userIdentifier",
                    "status",
                    "consented");

    @Inject ConsentService service;

    @Inject ConsentLifecycleService lifecycle;

    @Inject ConsentWriter writer;

    @Inject UserService userService;

    @Inject ParticipantService participantService;

    @Inject NotificationOutboxRepository outbox;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject UserParticipantRepository links;

    private Participant provider;

    private Participant consumer;

    private PrivacyNotice notice;

    private User subject;

    /** The subject's identifier as recipients know it, captured before erasure can change it. */
    private String subjectIdentifier;

    /** The side of the notice a recipient sits on. */
    enum Party {
        PROVIDER,
        CONSUMER
    }

    /**
     * One write path, the status it leaves on the consent it returns, and the notification it owes
     * for that consent; {@code eventType} is null for a path that changes no permission.
     */
    enum WritePath {
        GRANT(ConsentStatus.GRANTED, NotificationEventType.CONSENT_GRANTED, Party.values()),
        REFUSAL_SUPERSEDING_A_GRANT(
                ConsentStatus.REVOKED, NotificationEventType.CONSENT_REVOKED, Party.values()),
        REVOKE(ConsentStatus.REVOKED, NotificationEventType.CONSENT_REVOKED, Party.values()),
        TERMINATE_BY_PROVIDER(
                ConsentStatus.TERMINATED, NotificationEventType.CONSENT_TERMINATED, Party.CONSUMER),
        TERMINATE_BY_CONSUMER(
                ConsentStatus.TERMINATED, NotificationEventType.CONSENT_TERMINATED, Party.PROVIDER),
        SYSTEM_TERMINATE(
                ConsentStatus.TERMINATED, NotificationEventType.CONSENT_TERMINATED, Party.values()),
        RESUME(ConsentStatus.GRANTED, NotificationEventType.CONSENT_GRANTED, Party.values()),
        EXPIRY(ConsentStatus.EXPIRED, NotificationEventType.CONSENT_EXPIRED, Party.values()),
        ERASURE(ConsentStatus.REVOKED, NotificationEventType.CONSENT_REVOKED, Party.values()),
        REQUEST(ConsentStatus.PENDING, null),
        REFUSE(ConsentStatus.REFUSED, null),
        RE_CONFIRM(ConsentStatus.GRANTED, null),
        CLEANUP_TERMINATE(ConsentStatus.TERMINATED, null),
        DEREGISTRATION(ConsentStatus.TERMINATED, null);

        private final ConsentStatus stored;
        private final NotificationEventType eventType;
        private final Set<Party> recipients;

        WritePath(ConsentStatus stored, NotificationEventType eventType, Party... recipients) {
            this.stored = stored;
            this.eventType = eventType;
            this.recipients =
                    recipients.length == 0
                            ? EnumSet.noneOf(Party.class)
                            : EnumSet.copyOf(List.of(recipients));
        }
    }

    /** One committed outbox row, reduced to what the table decides. */
    record Row(UUID consentId, NotificationEventType eventType, UUID participantId) {}

    @BeforeEach
    void seedTheNotice() {
        provider = participant(participants, PROVIDER);
        consumer = participant(participants, CONSUMER);
        notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                "Enqueue notice",
                                provider.getId(),
                                consumer.getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        subjectIdentifier = "urn:test:user:enqueue:" + UUID.randomUUID();
        subject = users.save(new User(subjectIdentifier, null, null, null));
        links.save(new UserParticipant(subject.getId(), provider.getId(), LOCAL_IDENTIFIER));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(WritePath.class)
    @DisplayName("each write path commits exactly the rows its move owes, pending and undispatched")
    void enqueuesWhatTheMoveOwes(WritePath path) {
        UUID consentId = perform(path);

        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .as("the path moved the consent, so an empty outbox is a decision")
                .isEqualTo(path.stored);
        List<NotificationOutboxEntry> rows = all();
        assertThat(rows)
                .map(row -> new Row(row.getConsentId(), row.getEventType(), row.getParticipantId()))
                .containsExactlyInAnyOrderElementsOf(expected(path, consentId));
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
                            assertThat(row.getAttempts()).isZero();
                            assertThat(row.getLastError()).isNull();
                            assertThat(row.getLeaseToken()).isNull();
                            assertThat(payloadKeys(row.getId()))
                                    .containsExactlyInAnyOrderElementsOf(PAYLOAD_KEYS);
                            assertFrozenPayload(row);
                        });
        assertThat(rows.stream().map(NotificationOutboxEntry::getEventId).distinct())
                .as("one move fans out under one event id")
                .hasSizeLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("deregistration abandons only the departing participant's queued rows")
    void deregistrationAbandonsTheDepartingParticipantsRows() {
        UUID consentId = seedConsent(ConsentStatus.GRANTED);
        lifecycle.revoke(subject, consentId);
        RawSql.query(
                "UPDATE notification_outbox SET status = 'FAILED' RETURNING id",
                row -> row.getObject(1));

        participantService.deregister(provider);

        assertThat(all())
                .hasSize(Party.values().length)
                .allSatisfy(
                        row -> {
                            boolean departed = row.getParticipantId().equals(provider.getId());
                            assertThat(row.getStatus())
                                    .isEqualTo(
                                            departed
                                                    ? NotificationOutboxStatus.ABANDONED
                                                    : NotificationOutboxStatus.FAILED);
                            assertThat(row.getLastError())
                                    .isEqualTo(
                                            departed
                                                    ? NotificationOutboxRepository.AbandonReason
                                                            .PARTICIPANT_DEREGISTERED
                                                    : null);
                            assertThat(row.getPayload() == null).isEqualTo(departed);
                        });
    }

    /** Runs the path against freshly seeded state, returning the consent its table row is about. */
    private UUID perform(WritePath path) {
        return switch (path) {
            case GRANT ->
                    service.decide(subject, decision(ConsentStatus.GRANTED)).consent().getId();
            case REFUSAL_SUPERSEDING_A_GRANT -> {
                UUID granted = seedConsent(ConsentStatus.GRANTED);
                service.decide(subject, decision(ConsentStatus.REFUSED));
                yield granted;
            }
            case REVOKE -> on(ConsentStatus.GRANTED, id -> lifecycle.revoke(subject, id));
            case TERMINATE_BY_PROVIDER ->
                    on(ConsentStatus.GRANTED, id -> lifecycle.terminate(provider, id));
            case TERMINATE_BY_CONSUMER ->
                    on(ConsentStatus.GRANTED, id -> lifecycle.terminate(consumer, id));
            case SYSTEM_TERMINATE -> systemMove(ConsentStatus.TERMINATED);
            case RESUME ->
                    on(
                            ConsentStatus.PENDING,
                            id -> lifecycle.resume(principalFor(ISSUER, subject), id, null));
            case EXPIRY -> systemMove(ConsentStatus.EXPIRED);
            case ERASURE -> on(ConsentStatus.GRANTED, id -> userService.erase(subject));
            case REQUEST ->
                    service.request(
                                    provider,
                                    new ConsentRequestCommand(notice.getId(), subjectIdentifier))
                            .consent()
                            .getId();
            case REFUSE -> on(ConsentStatus.PENDING, id -> lifecycle.refuse(subject, id));
            case RE_CONFIRM -> on(ConsentStatus.GRANTED, id -> lifecycle.reConfirm(subject, id));
            case CLEANUP_TERMINATE -> {
                List<UUID> unanswered =
                        List.of(
                                seedConsent(ConsentStatus.PENDING),
                                seedConsent(ConsentStatus.PENDING));
                service.decide(subject, decision(ConsentStatus.REFUSED));
                yield unanswered.stream()
                        .filter(
                                id ->
                                        consents.findById(id).orElseThrow().getStatus()
                                                == ConsentStatus.TERMINATED)
                        .findFirst()
                        .orElseThrow();
            }
            case DEREGISTRATION ->
                    on(ConsentStatus.PENDING, id -> participantService.deregister(provider));
        };
    }

    /** The rows the table promises for {@code consentId}. */
    private Set<Row> expected(WritePath path, UUID consentId) {
        return path.recipients.stream()
                .map(
                        party ->
                                new Row(
                                        consentId,
                                        path.eventType,
                                        party == Party.PROVIDER
                                                ? provider.getId()
                                                : consumer.getId()))
                .collect(Collectors.toSet());
    }

    /** Asserts the payload reports the move's own event and names the subject as known before. */
    private void assertFrozenPayload(NotificationOutboxEntry row) {
        Consent consent = consents.findById(row.getConsentId()).orElseThrow();
        ConsentEvent event = events.findById(row.getEventId()).orElseThrow();
        assertThat(event.getConsentId()).isEqualTo(consent.getId());
        assertThat(row.getPayload())
                .isEqualTo(
                        new NotificationPayload(
                                row.getEventType(),
                                event.getId(),
                                event.getOccurredAt(),
                                consent.getId(),
                                notice.getId(),
                                subjectIdentifier,
                                consent.getStatus(),
                                consent.isConsented()));
    }

    /** Seeds a consent in {@code held} and applies {@code move} to it. */
    private UUID on(ConsentStatus held, Function<UUID, ?> move) {
        UUID consentId = seedConsent(held);
        move.apply(consentId);
        return consentId;
    }

    /** A move of a seeded grant that no party asked for, as an expiry job would make it. */
    private UUID systemMove(ConsentStatus target) {
        return on(
                ConsentStatus.GRANTED,
                id -> writer.move(id, target, SYSTEM_ACTOR, ConsentEventTrigger.SYSTEM));
    }

    private ConsentDecision decision(ConsentStatus decision) {
        return new ConsentDecision(notice.getId(), decision, null, null);
    }

    /** A consent stored without events or outbox rows, so only the path under test enqueues. */
    private UUID seedConsent(ConsentStatus status) {
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                notice.getId(),
                                provider.getId(),
                                consumer.getId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }

    private List<NotificationOutboxEntry> all() {
        return StreamSupport.stream(outbox.findAll().spliterator(), false).toList();
    }

    /** The top-level keys PostgreSQL stored, read past the record so an extra key shows. */
    private static List<String> payloadKeys(UUID id) {
        return RawSql.query(
                "SELECT jsonb_object_keys(payload) FROM notification_outbox WHERE id = ?",
                row -> row.getString(1),
                id);
    }
}
