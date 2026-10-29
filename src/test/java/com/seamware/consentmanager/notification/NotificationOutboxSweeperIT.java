package com.seamware.consentmanager.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests the age backstop and the retention sweep with delivery disabled, as on a replica whose
 * dispatcher is off: the sweep must redact without sending anything.
 */
@MicronautTest(transactional = false)
@DisplayName("Notification outbox sweeper")
class NotificationOutboxSweeperIT extends PostgresTestResource {

    private static final String HOOK_PATH = "/hooks/consent";

    private static final String CONTRACT_URI = "urn:test:contract:sweep";

    /** How far past a limit an aged row is. */
    private static final Duration PAST_LIMIT = Duration.ofHours(1);

    private static final String SERVER_ERROR = "HTTP 503";

    private static WireMockServer stub;

    @Inject NotificationOutboxSweeper sweeper;

    @Inject ParticipantNotifications settings;

    @Inject NotificationOutboxRepository outbox;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject DataSource dataSource;

    @Inject DataSourceResolver dataSources;

    private UUID recipient;

    private UUID consentId;

    @BeforeAll
    static void startStub() {
        stub = new WireMockServer(options().dynamicPort());
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        stub.stop();
    }

    @BeforeEach
    void seed() {
        stub.resetAll();
        stub.stubFor(post(anyUrl()).willReturn(aResponse()));
        execute("DELETE FROM notification_outbox");
        UUID party = participant(participants, "urn:test:participant:sweep-party").getId();
        String identifier = "urn:test:participant:sweep:" + UUID.randomUUID();
        recipient =
                participants
                        .save(
                                new Participant(
                                        identifier,
                                        identifier,
                                        null,
                                        null,
                                        Map.of(
                                                NotificationDispatcher.ENDPOINT_KEY,
                                                stub.url(HOOK_PATH)),
                                        null))
                        .getId();
        User subject =
                users.save(new User("urn:test:user:sweep:" + UUID.randomUUID(), null, null, null));
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI + ":" + UUID.randomUUID(),
                                "Sweep notice",
                                party,
                                party,
                                new PrivacyNoticePayload(
                                        null, null, null, null, null, null, null, null, null)));
        consentId =
                consents.save(
                                Consent.withStatus(
                                        ConsentStatus.GRANTED,
                                        subject.getId(),
                                        notice.getId(),
                                        party,
                                        party,
                                        null,
                                        notice.getContractUri(),
                                        noTerms()))
                        .getId();
    }

    /** A row still owed a delivery, as each due status leaves it. */
    enum DueRow {
        PENDING {
            @Override
            void prepare(NotificationOutboxSweeperIT test, UUID id) {}
        },
        FAILED {
            @Override
            void prepare(NotificationOutboxSweeperIT test, UUID id) {
                test.execute(
                        "UPDATE notification_outbox SET status = 'FAILED', attempts = 1,"
                                + " last_error = ? WHERE id = ?",
                        SERVER_ERROR,
                        id);
            }
        };

        abstract void prepare(NotificationOutboxSweeperIT test, UUID id);
    }

    @ParameterizedTest(name = "a {0} row past max age is abandoned and redacted, unsent")
    @EnumSource(DueRow.class)
    void abandonsAnAgedDueRow(DueRow due) {
        UUID aged = enqueue();
        due.prepare(this, aged);
        age(aged, settings.getMaxAge().plus(PAST_LIMIT));
        UUID young = enqueue();
        due.prepare(this, young);

        sweeper.sweepOnce();

        NotificationOutboxEntry abandoned = outbox.findById(aged).orElseThrow();
        assertThat(abandoned.getStatus()).isEqualTo(NotificationOutboxStatus.ABANDONED);
        assertThat(abandoned.getPayload()).isNull();
        assertThat(abandoned.getLeaseToken()).isNull();
        assertThat(abandoned.getLastError())
                .isEqualTo(NotificationOutboxRepository.AbandonReason.MAX_AGE_EXCEEDED);
        NotificationOutboxEntry untouched = outbox.findById(young).orElseThrow();
        assertThat(untouched.getStatus().name()).isEqualTo(due.name());
        assertThat(untouched.getPayload()).isNotNull();
        stub.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("a terminal row past retention is deleted; a younger one is kept")
    void deletesHistoryPastRetention() {
        UUID expired = enqueue();
        UUID kept = enqueue();
        execute(
                "UPDATE notification_outbox SET status = 'DELIVERED', payload = NULL,"
                        + " delivered_at = now(), attempts = 1, lease_token = NULL"
                        + " WHERE id IN (?, ?)",
                expired,
                kept);
        age(expired, settings.getRetention().plus(PAST_LIMIT));

        sweeper.sweepOnce();

        assertThat(outbox.findById(expired)).isEmpty();
        assertThat(outbox.findById(kept)).isPresent();
        stub.verify(0, anyRequestedFor(anyUrl()));
    }

    private UUID enqueue() {
        ConsentEvent event =
                events.save(
                        new ConsentEvent(consentId, ConsentEventState.CONSENT_GIVEN, null, null));
        NotificationPayload payload =
                new NotificationPayload(
                        NotificationEventType.CONSENT_GRANTED,
                        event.getId(),
                        Instant.now().truncatedTo(ChronoUnit.MICROS),
                        consentId,
                        UUID.randomUUID(),
                        "urn:test:user:sweep",
                        ConsentStatus.GRANTED,
                        true);
        return outbox.save(NotificationOutboxEntry.pending(recipient, payload)).getId();
    }

    private void age(UUID id, Duration by) {
        execute(
                "UPDATE notification_outbox SET created_at = now() - make_interval(secs => ?)"
                        + " WHERE id = ?",
                by.toSeconds(),
                id);
    }

    private void execute(String sql, Object... parameters) {
        try (Connection connection = dataSources.resolve(dataSource).getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
