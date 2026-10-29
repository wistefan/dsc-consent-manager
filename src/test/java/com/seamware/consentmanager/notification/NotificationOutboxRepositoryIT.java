package com.seamware.consentmanager.notification;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.Race;
import io.micronaut.data.model.Pageable;
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests the outbox storage contract: the claim's due set, per-participant cap, disjointness under
 * concurrency and EvalPlanQual re-check, the lease fence, and the payload and age invariants.
 */
@MicronautTest(transactional = false)
@DisplayName("Notification outbox repository")
class NotificationOutboxRepositoryIT extends PostgresTestResource {

    private static final String PROVIDER = "urn:test:participant:outbox-provider";

    private static final String CONSUMER = "urn:test:participant:outbox-consumer";

    private static final String BYSTANDER = "urn:test:participant:outbox-bystander";

    private static final String CONTRACT_URI = "urn:test:contract:outbox";

    /** Wide enough that neither the batch size nor the cap limits a claim unless meant to. */
    private static final int WIDE = 100;

    /** A max age every freshly enqueued row is well inside. */
    private static final long MAX_AGE_SECONDS = Duration.ofHours(1).toSeconds();

    /** How far past the max age an aged row's {@code created_at} is rewound. */
    private static final long AGED_BY_SECONDS = MAX_AGE_SECONDS * 2;

    private static final Duration LEASE = Duration.ofMinutes(1);

    /** Long enough for a blocked statement to register as waiting, short enough to fail fast. */
    private static final Duration BLOCKED = Duration.ofSeconds(10);

    private static final String FAILURE = "HTTP 503";

    /** A backend of this database blocked on a lock while claiming outbox rows. */
    private static final String WAITING_CLAIMS =
            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                    + " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'"
                    + " AND query ILIKE '%notification_outbox%'";

    /** What another replica's {@link NotificationOutboxRepository#lease} writes, held open. */
    private static final String LEASE_ONE =
            "UPDATE notification_outbox SET lease_token = ?, next_attempt_at = now() + interval"
                    + " '1 minute' WHERE id = ?";

    @Inject NotificationOutboxRepository outbox;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject TransactionOperations<Connection> transactions;

    @Inject DataSource dataSource;

    @Inject DataSourceResolver dataSources;

    private UUID provider;

    private UUID consumer;

    private UUID bystander;

    private UUID consentId;

    @BeforeEach
    void seedConsent() {
        provider = participant(participants, PROVIDER).getId();
        consumer = participant(participants, CONSUMER).getId();
        bystander = participant(participants, BYSTANDER).getId();
        User subject =
                users.save(new User("urn:test:user:outbox:" + UUID.randomUUID(), null, null, null));
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                "Outbox notice",
                                provider,
                                consumer,
                                new PrivacyNoticePayload(
                                        null, null, null, null, null, null, null, null, null)));
        consentId =
                consents.save(
                                Consent.withStatus(
                                        ConsentStatus.GRANTED,
                                        subject.getId(),
                                        notice.getId(),
                                        provider,
                                        consumer,
                                        null,
                                        CONTRACT_URI,
                                        noTerms()))
                        .getId();
    }

    @Test
    @DisplayName("the jsonb payload reads back exactly as it was frozen")
    void roundTripsThePayload() {
        NotificationPayload payload = payload(NotificationEventType.CONSENT_REVOKED);
        UUID id = outbox.save(NotificationOutboxEntry.pending(provider, payload)).getId();

        NotificationOutboxEntry stored = outbox.findById(id).orElseThrow();

        assertThat(stored.getPayload()).isEqualTo(payload);
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(stored.getEventId()).isEqualTo(payload.eventId());
        assertThat(stored.getCreatedAt()).as("stamped by the database").isNotNull();
        assertThat(
                        outbox.findByParticipantIdOrderByCreatedAtDesc(provider, Pageable.from(0))
                                .getContent())
                .extracting(NotificationOutboxEntry::getId)
                .containsExactly(id);
    }

    @Test
    @DisplayName("a claim returns the due PENDING and FAILED rows and nothing else")
    void claimsOnlyDueRows() {
        UUID pending = enqueue(provider);
        UUID failed = enqueue(provider);
        UUID notYetDue = enqueue(provider);
        UUID delivered = enqueue(provider);
        UUID abandoned = enqueue(provider);
        outbox.markFailed(failed, lease(failed), 0, FAILURE);
        execute(
                "UPDATE notification_outbox SET next_attempt_at = now() + interval '1 hour'"
                        + " WHERE id = ?",
                notYetDue);
        outbox.markDelivered(delivered, lease(delivered));
        outbox.markAbandoned(abandoned, lease(abandoned), FAILURE);

        assertThat(claimedIds(WIDE, WIDE)).containsExactlyInAnyOrder(pending, failed);
        assertThat(outbox.countDueByParticipant())
                .as("the gauge counts due rows whether or not they are leased")
                .containsExactly(new DueCount(provider, 3));
    }

    @Test
    @DisplayName("a claim takes at most the cap per participant, without starving the others")
    void capsRowsPerParticipant() {
        int cap = 2;
        IntStream.range(0, cap * 3).forEach(i -> enqueue(provider));
        UUID other = enqueue(consumer);

        List<NotificationOutboxEntry> claimed = outbox.claimDue(WIDE, cap, MAX_AGE_SECONDS);

        assertThat(claimed)
                .filteredOn(entry -> entry.getParticipantId().equals(provider))
                .as("the cap is an upper bound on one participant's share of a batch")
                .hasSizeLessThanOrEqualTo(cap)
                .isNotEmpty();
        assertThat(claimed).extracting(NotificationOutboxEntry::getId).contains(other);
    }

    @Test
    @DisplayName("two replicas claiming at once never receive the same row")
    void concurrentClaimsAreDisjoint() {
        int batch = 4;
        IntStream.range(0, batch * 2).forEach(i -> enqueue(provider));
        // Both claims hold their locks until both have claimed, so neither can commit early.
        CyclicBarrier bothClaimed = new CyclicBarrier(2);

        List<Race.Outcome<List<UUID>>> outcomes =
                Race.run(
                        2,
                        () ->
                                transactions.executeWrite(
                                        status -> {
                                            List<UUID> ids = claimedIds(batch, WIDE);
                                            awaitQuietly(bothClaimed);
                                            return ids;
                                        }));

        assertThat(outcomes).allMatch(Race.Outcome::succeeded);
        List<UUID> first = outcomes.get(0).value();
        List<UUID> second = outcomes.get(1).value();
        assertThat(first).hasSize(batch).doesNotContainAnyElementsOf(second);
        assertThat(second).hasSize(batch);
    }

    @Test
    @DisplayName("a claim blocked behind another replica's lease drops the row once it commits")
    void recheckDropsARowLeasedAfterTheSnapshot() throws Exception {
        UUID contested = enqueue(provider);
        UUID free = enqueue(consumer);
        DataSource pool = dataSources.resolve(dataSource);

        try (ExecutorService replicaB = Executors.newSingleThreadExecutor();
                Connection replicaA = pool.getConnection()) {
            replicaA.setAutoCommit(false);
            try (PreparedStatement lease = replicaA.prepareStatement(LEASE_ONE)) {
                lease.setObject(1, UUID.randomUUID());
                lease.setObject(2, contested);
                lease.executeUpdate();
            }
            Future<List<UUID>> claimedByB = replicaB.submit(() -> claimWaiting(pool));
            Await.until(
                    "replica B to block on the row replica A holds",
                    BLOCKED,
                    () -> isWaitingOnALock(pool));

            replicaA.commit();

            assertThat(claimedByB.get(BLOCKED.toSeconds(), TimeUnit.SECONDS))
                    .as("the re-check must see the lease, not the snapshot in which it was due")
                    .containsExactly(free);
        }
    }

    @Test
    @DisplayName("an outcome written under a stale lease token updates nothing")
    void staleTokenIsFenced() {
        UUID id = enqueue(provider);
        UUID token = lease(id);

        assertThat(outbox.markDelivered(id, UUID.randomUUID())).isZero();
        assertThat(outbox.markFailed(id, UUID.randomUUID(), 0, FAILURE)).isZero();
        assertThat(outbox.markAbandoned(id, UUID.randomUUID(), FAILURE)).isZero();

        NotificationOutboxEntry row = outbox.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(row.getLeaseToken()).isEqualTo(token);
        assertThat(row.getPayload()).isNotNull();
        assertThat(row.getAttempts()).isZero();
    }

    /** A fenced dispatch outcome and what it must leave on the row. */
    enum FencedOutcome {
        DELIVERED(
                NotificationOutboxStatus.DELIVERED,
                false,
                (repo, row) -> repo.markDelivered(row.id(), row.token())),
        FAILED(
                NotificationOutboxStatus.FAILED,
                true,
                (repo, row) -> repo.markFailed(row.id(), row.token(), 0, FAILURE)),
        ABANDONED(
                NotificationOutboxStatus.ABANDONED,
                false,
                (repo, row) -> repo.markAbandoned(row.id(), row.token(), FAILURE));

        final NotificationOutboxStatus status;

        final boolean keepsPayload;

        final BiFunction<NotificationOutboxRepository, Leased, Integer> write;

        FencedOutcome(
                NotificationOutboxStatus status,
                boolean keepsPayload,
                BiFunction<NotificationOutboxRepository, Leased, Integer> write) {
            this.status = status;
            this.keepsPayload = keepsPayload;
            this.write = write;
        }
    }

    /** A row and the token of the lease it is held under. */
    record Leased(UUID id, UUID token) {}

    @ParameterizedTest(name = "{0}")
    @EnumSource(FencedOutcome.class)
    @DisplayName(
            "a fenced outcome counts one attempt, clears the lease and keeps a payload only if due")
    void fencedOutcomeCountsAnAttempt(FencedOutcome outcome) {
        UUID id = enqueue(provider);

        assertThat(outcome.write.apply(outbox, new Leased(id, lease(id)))).isOne();

        NotificationOutboxEntry row = outbox.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(outcome.status);
        assertThat(row.getAttempts()).isOne();
        assertThat(row.getLeaseToken()).isNull();
        assertThat(row.getPayload() != null).isEqualTo(outcome.keepsPayload);
        assertThat(row.getDeliveredAt() != null).isEqualTo(outcome == FencedOutcome.DELIVERED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = NotificationOutboxStatus.class,
            names = {"DELIVERED", "ABANDONED"})
    @DisplayName("the schema refuses a terminal row that still carries a payload")
    void terminalRowMayNotCarryAPayload(NotificationOutboxStatus terminal) {
        UUID id = enqueue(provider);

        assertThatThrownBy(
                        () ->
                                execute(
                                        "UPDATE notification_outbox SET status = ? WHERE id = ?",
                                        terminal.name(),
                                        id))
                .hasMessageContaining("chk_notification_outbox_terminal_payload");
    }

    @Test
    @DisplayName("a row past the max age is never claimed and is abandoned, leased or not")
    void agedRowsAreAbandonedNotClaimed() {
        UUID aged = enqueue(provider);
        UUID agedAndLeased = enqueue(provider);
        UUID fresh = enqueue(provider);
        UUID token = lease(agedAndLeased);
        execute(
                "UPDATE notification_outbox SET next_attempt_at = now() WHERE id = ?",
                agedAndLeased);
        rewindCreatedAt(aged);
        rewindCreatedAt(agedAndLeased);

        assertThat(claimedIds(WIDE, WIDE)).containsExactly(fresh);
        assertThat(outbox.abandonOlderThan(MAX_AGE_SECONDS)).isEqualTo(2);

        for (UUID id : List.of(aged, agedAndLeased)) {
            NotificationOutboxEntry row = outbox.findById(id).orElseThrow();
            assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.ABANDONED);
            assertThat(row.getPayload()).isNull();
            assertThat(row.getLeaseToken()).isNull();
            assertThat(row.getAttempts()).as("a sweep is not a dispatch outcome").isZero();
            assertThat(row.getLastError())
                    .isEqualTo(NotificationOutboxRepository.AbandonReason.MAX_AGE_EXCEEDED);
        }
        assertThat(outbox.markDelivered(agedAndLeased, token))
                .as("the holder of the swept lease can no longer write its outcome")
                .isZero();
        assertThat(outbox.findById(fresh).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.PENDING);
    }

    @Test
    @DisplayName("deregistration abandons only the participant's due rows")
    void abandonForParticipantTouchesOnlyItsDueRows() {
        UUID due = enqueue(provider);
        UUID leased = enqueue(provider);
        UUID delivered = enqueue(provider);
        UUID othersDue = enqueue(bystander);
        lease(leased);
        outbox.markDelivered(delivered, lease(delivered));

        assertThat(outbox.abandonForParticipant(provider)).isEqualTo(2);

        for (UUID id : List.of(due, leased)) {
            NotificationOutboxEntry row = outbox.findById(id).orElseThrow();
            assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.ABANDONED);
            assertThat(row.getPayload()).isNull();
            assertThat(row.getLeaseToken()).isNull();
            assertThat(row.getAttempts()).as("a sweep is not a dispatch outcome").isZero();
            assertThat(row.getLastError())
                    .isEqualTo(NotificationOutboxRepository.AbandonReason.PARTICIPANT_DEREGISTERED);
        }
        NotificationOutboxEntry kept = outbox.findById(delivered).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(NotificationOutboxStatus.DELIVERED);
        assertThat(kept.getLastError()).isNull();
        assertThat(outbox.findById(othersDue).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.PENDING);
    }

    @Test
    @DisplayName("retention deletes only terminal rows older than the cut-off")
    void retentionDeletesOldTerminalRows() {
        UUID oldDelivered = enqueue(provider);
        UUID oldDue = enqueue(provider);
        UUID recentDelivered = enqueue(provider);
        outbox.markDelivered(oldDelivered, lease(oldDelivered));
        outbox.markDelivered(recentDelivered, lease(recentDelivered));
        rewindCreatedAt(oldDelivered);
        rewindCreatedAt(oldDue);

        assertThat(outbox.deleteTerminalOlderThan(MAX_AGE_SECONDS)).isOne();

        assertThat(outbox.existsById(oldDelivered)).isFalse();
        assertThat(outbox.existsById(oldDue)).isTrue();
        assertThat(outbox.existsById(recentDelivered)).isTrue();
    }

    private UUID enqueue(UUID participantId) {
        return outbox.save(
                        NotificationOutboxEntry.pending(
                                participantId, payload(NotificationEventType.CONSENT_GRANTED)))
                .getId();
    }

    /** A payload reporting a fresh, stored event on the seeded consent. */
    private NotificationPayload payload(NotificationEventType type) {
        ConsentEvent event =
                events.save(
                        new ConsentEvent(consentId, ConsentEventState.CONSENT_GIVEN, null, null));
        return new NotificationPayload(
                type,
                event.getId(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                consentId,
                UUID.randomUUID(),
                "urn:test:user:outbox",
                ConsentStatus.GRANTED,
                true);
    }

    private UUID lease(UUID id) {
        UUID token = UUID.randomUUID();
        outbox.lease(List.of(id), token, LEASE);
        return token;
    }

    private List<UUID> claimedIds(int batchSize, int perParticipant) {
        return outbox.claimDue(batchSize, perParticipant, MAX_AGE_SECONDS).stream()
                .map(NotificationOutboxEntry::getId)
                .toList();
    }

    private void rewindCreatedAt(UUID id) {
        execute(
                "UPDATE notification_outbox SET created_at = now() - make_interval(secs => ?)"
                        + " WHERE id = ?",
                AGED_BY_SECONDS,
                id);
    }

    /** Runs the claim with a waiting lock in its own transaction, as replica B. */
    private static List<UUID> claimWaiting(DataSource pool) throws SQLException {
        String sql =
                (NotificationOutboxRepository.CLAIM_DUE + " FOR UPDATE OF o")
                        .replace(":maxAgeSeconds", Long.toString(MAX_AGE_SECONDS))
                        .replace(":perParticipant", Integer.toString(WIDE))
                        .replace(":batchSize", Integer.toString(WIDE));
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getObject("id", UUID.class));
                }
            }
            connection.rollback();
            return ids;
        }
    }

    private static boolean isWaitingOnALock(DataSource pool) {
        try (Connection probe = pool.getConnection();
                PreparedStatement statement = probe.prepareStatement(WAITING_CLAIMS);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() && rows.getLong(1) > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read what the backends are waiting on", e);
        }
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

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(Race.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The other claim never arrived", e);
        }
    }
}
