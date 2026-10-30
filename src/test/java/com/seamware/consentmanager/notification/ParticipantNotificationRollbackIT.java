package com.seamware.consentmanager.notification;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.IssuedReceipt;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.service.ConsentDecision;
import com.seamware.consentmanager.service.ConsentReceiptIssuer;
import com.seamware.consentmanager.service.ConsentService;
import com.seamware.consentmanager.service.ConsentWriter;
import com.seamware.consentmanager.service.RecordedConsent;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import com.seamware.consentmanager.support.RawSql;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A grant whose receipt fails after its notifications were queued leaves neither the move nor a row
 * behind (AC2). The failing issuer first observes the rows inside the transaction, so moving the
 * enqueue after the receipt fails this test instead of passing it vacuously.
 */
@MicronautTest(transactional = false)
@Property(name = ParticipantNotificationRollbackIT.FAILING_ISSUER, value = "true")
@DisplayName("Participant notification rollback")
class ParticipantNotificationRollbackIT extends PostgresTestResource {

    static final String FAILING_ISSUER = "test.receipts.fail-after-enqueue";

    private static final String PROVIDER = "urn:test:participant:rollback-provider";

    private static final String CONSUMER = "urn:test:participant:rollback-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:rollback";

    private static final String SYSTEM_ACTOR = "system";

    private static final String OUTBOX_ROWS = "SELECT count(*) FROM notification_outbox";

    private static final String CONSENT_EVENTS =
            "SELECT count(*) FROM consent_events WHERE consent_id = ?";

    @Inject ConsentService service;

    @Inject ConsentWriter writer;

    @Inject ConsentRepository consents;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject FailingIssuer issuer;

    private PrivacyNotice notice;

    private User subject;

    private Participant provider;

    private Participant consumer;

    /** The two ways a grant is written: a new consent, or a move of a stored one. */
    enum GrantWrite {
        NEW,
        MOVED
    }

    /** The receipt issuer, replaced by one that counts the queued rows and then fails. */
    @Singleton
    @Replaces(ConsentReceiptIssuer.class)
    @Requires(property = FAILING_ISSUER, value = "true")
    static class FailingIssuer extends ConsentReceiptIssuer {

        private final NotificationOutboxRepository outbox;

        /** Rows for the granted consent visible inside its transaction; -1 until asked to issue. */
        private volatile long queuedBeforeFailure = -1;

        FailingIssuer(NotificationOutboxRepository outbox) {
            super(null, null, null, null);
            this.outbox = outbox;
        }

        @Override
        public IssuedReceipt issue(@NonNull RecordedConsent granted) {
            UUID consentId = granted.consent().getId();
            queuedBeforeFailure =
                    StreamSupport.stream(outbox.findAll().spliterator(), false)
                            .filter(row -> row.getConsentId().equals(consentId))
                            .count();
            throw new ReceiptFailure();
        }
    }

    /** The failure the replaced issuer raises. */
    static final class ReceiptFailure extends RuntimeException {
        ReceiptFailure() {
            super("receipt signing failed");
        }
    }

    @BeforeEach
    void seed() {
        provider = participant(participants, PROVIDER);
        consumer = participant(participants, CONSUMER);
        notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                "Rollback notice",
                                provider.getId(),
                                consumer.getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        subject =
                users.save(
                        new User("urn:test:user:rollback:" + UUID.randomUUID(), null, null, null));
        issuer.queuedBeforeFailure = -1;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(GrantWrite.class)
    @DisplayName("a receipt failure rolls back the queued notifications with the grant")
    void receiptFailureRollsBackTheRows(GrantWrite write) {
        UUID pending = write == GrantWrite.MOVED ? seedPending() : null;

        assertThatThrownBy(
                        () -> {
                            if (pending == null) {
                                service.decide(
                                        subject,
                                        new ConsentDecision(
                                                notice.getId(), ConsentStatus.GRANTED, null, null));
                            } else {
                                writer.move(
                                        pending,
                                        ConsentStatus.GRANTED,
                                        SYSTEM_ACTOR,
                                        ConsentEventTrigger.SYSTEM);
                            }
                        })
                .isInstanceOf(ReceiptFailure.class);

        assertThat(issuer.queuedBeforeFailure)
                .as("the rows were queued before the receipt was attempted")
                .isPositive();
        assertThat(RawSql.count(OUTBOX_ROWS)).isZero();
        if (pending == null) {
            assertThat(consents.count()).as("the new grant was not stored").isZero();
        } else {
            Consent unchanged = consents.findById(pending).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(ConsentStatus.PENDING);
            assertThat(RawSql.count(CONSENT_EVENTS, pending)).isZero();
        }
    }

    /** A pending consent stored with no event or outbox row. */
    private UUID seedPending() {
        return consents.save(
                        Consent.withStatus(
                                ConsentStatus.PENDING,
                                subject.getId(),
                                notice.getId(),
                                provider.getId(),
                                consumer.getId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }
}
