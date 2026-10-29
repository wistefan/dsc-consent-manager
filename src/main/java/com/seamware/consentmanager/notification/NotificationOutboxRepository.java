package com.seamware.consentmanager.notification;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository over {@code notification_outbox}. Every time it stores or compares is the database's
 * {@code now()}, so replica clock skew never moves a lease, retry or cut-off.
 *
 * <p>Outcome writes ({@code mark*}) are fenced on the claim's lease token, record one attempt and
 * clear the token; 0 rows updated means the lease was lost and the outcome must be discarded. The
 * sweeps ({@code abandon*}) are unfenced and leave {@code attempts} alone.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface NotificationOutboxRepository
        extends CrudRepository<NotificationOutboxEntry, UUID> {

    /**
     * Due rows, at most {@code :perParticipant} per participant, without a lock clause.
     *
     * <p>The due predicates appear twice on purpose; never drop the outer copy. {@code r} is not
     * locked, so it is evaluated once against the statement snapshot, while the READ COMMITTED
     * re-check of a row another replica leased meanwhile evaluates only the quals on {@code o}. The
     * ranking sits in a joined subquery because Postgres refuses {@code FOR UPDATE} over a window
     * function.
     */
    String CLAIM_DUE =
            "SELECT o.* FROM notification_outbox o"
                    + " JOIN (SELECT id, row_number() OVER"
                    + " (PARTITION BY participant_id ORDER BY next_attempt_at) AS rn"
                    + " FROM notification_outbox"
                    + " WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= now()"
                    + " AND created_at > now() - make_interval(secs => :maxAgeSeconds)) r"
                    + " ON r.id = o.id"
                    + " WHERE r.rn <= :perParticipant"
                    + " AND o.status IN ('PENDING', 'FAILED') AND o.next_attempt_at <= now()"
                    + " AND o.created_at > now() - make_interval(secs => :maxAgeSeconds)"
                    + " ORDER BY o.next_attempt_at"
                    + " LIMIT :batchSize";

    /** One participant's rows, in the order and page {@code pageable} names. */
    Page<NotificationOutboxEntry> findByParticipantId(UUID participantId, Pageable pageable);

    /** Lock clause of {@link #claimDue}: rows another replica is claiming are skipped. */
    String SKIP_LOCKED = " FOR UPDATE OF o SKIP LOCKED";

    /**
     * Locks up to {@code batchSize} due rows younger than the max age; lease them before commit.
     */
    @Query(CLAIM_DUE + SKIP_LOCKED)
    List<NotificationOutboxEntry> claimDue(int batchSize, int perParticipant, long maxAgeSeconds);

    /**
     * Stamps {@code token} on the rows and pushes them out of reach until the lease expires.
     * Returns the leased-until time, for logs only; never base a send decision on it.
     */
    default Instant lease(List<UUID> ids, UUID token, Duration lease) {
        return leaseForMillis(ids, token, lease.toMillis());
    }

    /** {@link #lease} in milliseconds; a CTE so the update can return its expiry. */
    @Query(
            "WITH leased AS (UPDATE notification_outbox SET lease_token = :token,"
                    + " next_attempt_at = now() + :leaseMillis * INTERVAL '1 millisecond'"
                    + " WHERE id IN (:ids) RETURNING next_attempt_at)"
                    + " SELECT max(next_attempt_at) FROM leased")
    Instant leaseForMillis(List<UUID> ids, UUID token, long leaseMillis);

    /**
     * Records a successful delivery and drops the payload. Returns the stamped {@code
     * delivered_at}, or empty when the lease was lost.
     */
    @Query(
            "WITH delivered AS (UPDATE notification_outbox SET status = 'DELIVERED',"
                    + " delivered_at = now(), payload = NULL, last_error = NULL,"
                    + " lease_token = NULL, attempts = attempts + 1"
                    + " WHERE id = :id AND lease_token = :token RETURNING delivered_at)"
                    + " SELECT delivered_at FROM delivered")
    Optional<Instant> markDelivered(UUID id, UUID token);

    /** Records a failed attempt and schedules the retry {@code delayMillis} from now. */
    @Query(
            "UPDATE notification_outbox SET status = 'FAILED',"
                    + " next_attempt_at = now() + :delayMillis * INTERVAL '1 millisecond',"
                    + " last_error = :reason, lease_token = NULL, attempts = attempts + 1"
                    + " WHERE id = :id AND lease_token = :token")
    int markFailed(UUID id, UUID token, long delayMillis, String reason);

    /** Gives up on the row after an attempt and drops the payload. */
    @Query(
            "UPDATE notification_outbox SET status = 'ABANDONED', payload = NULL,"
                    + " last_error = :reason, lease_token = NULL, attempts = attempts + 1"
                    + " WHERE id = :id AND lease_token = :token")
    int markAbandoned(UUID id, UUID token, String reason);

    /** The participant's delivery history, newest first. */
    Page<NotificationOutboxEntry> findByParticipantIdOrderByCreatedAtDesc(
            UUID participantId, Pageable pageable);

    /** Due rows per participant, for the queue-depth gauge. */
    @Query(
            "SELECT participant_id, COUNT(*) AS due FROM notification_outbox"
                    + " WHERE status IN ('PENDING', 'FAILED') GROUP BY participant_id")
    List<DueCount> countDueByParticipant();

    /** Deletes terminal rows enqueued more than {@code retentionSeconds} ago. */
    @Query(
            "DELETE FROM notification_outbox WHERE status IN ('DELIVERED', 'ABANDONED')"
                    + " AND created_at <= now() - make_interval(secs => :retentionSeconds)")
    int deleteTerminalOlderThan(long retentionSeconds);

    /**
     * The age backstop: abandons every due row past the max age, leased or not, and returns the
     * rows it abandoned.
     */
    @Query(
            "UPDATE notification_outbox SET status = 'ABANDONED', payload = NULL,"
                    + " last_error = '"
                    + AbandonReason.MAX_AGE_EXCEEDED
                    + "', lease_token = NULL"
                    + " WHERE status IN ('PENDING', 'FAILED')"
                    + " AND created_at <= now() - make_interval(secs => :maxAgeSeconds)"
                    + " RETURNING participant_id, consent_id")
    List<AbandonedRow> abandonOlderThan(long maxAgeSeconds);

    /** The deregistration sweep: abandons every due row of the participant, leased or not. */
    @Query(
            "UPDATE notification_outbox SET status = 'ABANDONED', payload = NULL,"
                    + " last_error = '"
                    + AbandonReason.PARTICIPANT_DEREGISTERED
                    + "', lease_token = NULL"
                    + " WHERE status IN ('PENDING', 'FAILED') AND participant_id = :participantId")
    int abandonForParticipant(UUID participantId);

    /** Fixed {@code last_error} texts of the unfenced sweeps. */
    final class AbandonReason {

        /** Set by {@link #abandonOlderThan}. */
        public static final String MAX_AGE_EXCEEDED = "max age exceeded";

        /** Set by {@link #abandonForParticipant}. */
        public static final String PARTICIPANT_DEREGISTERED = "participant deregistered";

        private AbandonReason() {}
    }
}
