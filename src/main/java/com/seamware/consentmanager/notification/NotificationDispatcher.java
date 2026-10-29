package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.UuidGenerator;
import com.seamware.consentmanager.repository.ParticipantRepository;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.transaction.TransactionOperations;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drains the outbox: claims and leases a batch, delivers it on a bounded pool, and writes each
 * outcome fenced on the lease token, so a replica that lost its lease can never overwrite a row.
 *
 * <p>The local send deadline is read from a monotonic clock taken before the claim, never from a
 * wall clock, so it always precedes the lease expiry the database stamped. A row whose lease has
 * less than one send plus {@link ParticipantNotifications#LEASE_MARGIN} left is not sent and is
 * re-claimed once the lease expires.
 */
@Singleton
@Requires(beans = DataSource.class)
public class NotificationDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);

    /** Key of the webhook URL in a participant's {@code endpoints}. */
    static final String ENDPOINT_KEY = "consentNotification";

    static final String NO_ENDPOINT = "no consentNotification endpoint";

    static final String NO_PAYLOAD = "payload missing";

    private static final String THREAD_NAME_PREFIX = "notification-dispatcher-";

    private final NotificationOutboxRepository outbox;
    private final ParticipantRepository participants;
    private final ParticipantWebhookClient webhooks;
    private final TransactionOperations<Connection> transactions;
    private final ParticipantNotifications settings;
    private final NotificationBackoff backoff;
    private final LongSupplier nanoClock;
    private final RandomGenerator random;
    private final ThreadPoolExecutor pool;
    private final long sendReserveNanos;

    @Inject
    NotificationDispatcher(
            NotificationOutboxRepository outbox,
            ParticipantRepository participants,
            ParticipantWebhookClient webhooks,
            TransactionOperations<Connection> transactions,
            ParticipantNotifications settings) {
        this(
                outbox,
                participants,
                webhooks,
                transactions,
                settings,
                System::nanoTime,
                new SecureRandom());
    }

    /** {@code nanoClock} is the monotonic source of the send deadline; tests stub it. */
    NotificationDispatcher(
            NotificationOutboxRepository outbox,
            ParticipantRepository participants,
            ParticipantWebhookClient webhooks,
            TransactionOperations<Connection> transactions,
            ParticipantNotifications settings,
            LongSupplier nanoClock,
            RandomGenerator random) {
        this.outbox = outbox;
        this.participants = participants;
        this.webhooks = webhooks;
        this.transactions = transactions;
        this.settings = settings;
        this.backoff = new NotificationBackoff(settings.getBackoff(), settings.getJitter());
        this.nanoClock = nanoClock;
        this.random = random;
        this.sendReserveNanos =
                settings.sendBound().plus(ParticipantNotifications.LEASE_MARGIN).toNanos();
        this.pool =
                new ThreadPoolExecutor(
                        settings.getConcurrency(),
                        settings.getConcurrency(),
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(settings.getBatchSize()),
                        daemonThreads());
    }

    /** The scheduled tick; does nothing on a replica with delivery disabled. */
    @Scheduled(fixedDelay = "${consent-manager.participant-notifications.poll-interval}")
    void poll() {
        if (settings.isEnabled()) {
            drainOnce();
        }
    }

    /**
     * Claims one batch and delivers it, returning once every send finished or the local deadline
     * passed; sends not yet started by then are cancelled and their rows left to the lease.
     */
    public void drainOnce() {
        long claimStarted = nanoClock.getAsLong();
        UUID token = UuidGenerator.uuidV7();
        List<NotificationOutboxEntry> batch = transactions.executeWrite(status -> claim(token));
        if (batch.isEmpty()) {
            return;
        }
        long deadline = claimStarted + settings.getLease().toNanos();
        List<Future<?>> sends = new ArrayList<>(batch.size());
        for (NotificationOutboxEntry row : batch) {
            try {
                sends.add(pool.submit(() -> deliver(row, token, deadline)));
            } catch (RejectedExecutionException e) {
                LOG.debug("Notification {} not queued; left to its lease", row.getId());
            }
        }
        awaitUntil(sends, deadline);
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    private List<NotificationOutboxEntry> claim(UUID token) {
        List<NotificationOutboxEntry> rows =
                outbox.claimDue(
                        settings.getBatchSize(),
                        settings.getPerParticipantBatchLimit(),
                        settings.getMaxAge().toSeconds());
        if (rows.isEmpty()) {
            return rows;
        }
        Instant leasedUntil =
                outbox.lease(
                        rows.stream().map(NotificationOutboxEntry::getId).toList(),
                        token,
                        settings.getLease());
        LOG.debug("Claimed {} notifications, leased until {}", rows.size(), leasedUntil);
        return rows;
    }

    private void awaitUntil(List<Future<?>> sends, long deadline) {
        try {
            for (Future<?> send : sends) {
                long remaining = deadline - nanoClock.getAsLong();
                send.get(Math.max(remaining, 0L), TimeUnit.NANOSECONDS);
            }
        } catch (TimeoutException e) {
            LOG.warn("Notification batch outlived its lease; unstarted sends cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // deliver() catches everything, so this is unreachable in practice.
            LOG.error("Notification send failed unexpectedly", e.getCause());
        } finally {
            // Never interrupts a running send; its outcome write is still fenced.
            sends.forEach(send -> send.cancel(false));
        }
    }

    private void deliver(NotificationOutboxEntry row, UUID token, long deadline) {
        if (deadline - nanoClock.getAsLong() < sendReserveNanos) {
            LOG.debug("Notification {} not sent; too little lease left", row.getId());
            return;
        }
        try {
            record(row, token, attempt(row));
        } catch (RuntimeException e) {
            // The row stays leased and is re-claimed once the lease expires.
            LOG.error("Notification {} could not be processed", row.getId(), e);
        }
    }

    private DeliveryOutcome attempt(NotificationOutboxEntry row) {
        Optional<Participant> recipient = participants.findById(row.getParticipantId());
        if (recipient.isEmpty() || recipient.get().getDeregisteredAt() != null) {
            return DeliveryOutcome.abandon(
                    NotificationOutboxRepository.AbandonReason.PARTICIPANT_DEREGISTERED);
        }
        Object endpoint = recipient.get().getEndpoints().get(ENDPOINT_KEY);
        if (!(endpoint instanceof String url) || url.isBlank()) {
            return DeliveryOutcome.abandon(NO_ENDPOINT);
        }
        if (row.getPayload() == null) {
            return DeliveryOutcome.abandon(NO_PAYLOAD);
        }
        return webhooks.send(url, row.getPayload());
    }

    private void record(NotificationOutboxEntry row, UUID token, DeliveryOutcome outcome) {
        int updated =
                switch (outcome.result()) {
                    case DELIVERED -> outbox.markDelivered(row.getId(), token);
                    case RETRY -> retryOrAbandon(row, token, outcome.reason());
                    case ABANDON -> abandon(row, token, outcome.reason());
                };
        if (updated == 0) {
            LOG.debug("Lease on notification {} lost; outcome discarded", row.getId());
        }
    }

    private int retryOrAbandon(NotificationOutboxEntry row, UUID token, String reason) {
        Optional<Duration> delay = backoff.delay(row.getAttempts() + 1, random);
        if (delay.isEmpty()) {
            return abandon(row, token, reason);
        }
        return outbox.markFailed(row.getId(), token, delay.get().toSeconds(), reason);
    }

    private int abandon(NotificationOutboxEntry row, UUID token, String reason) {
        int updated = outbox.markAbandoned(row.getId(), token, reason);
        if (updated > 0) {
            LOG.warn(
                    "Notification abandoned: participant={} consent={} reason={}",
                    row.getParticipantId(),
                    row.getConsentId(),
                    reason);
        }
        return updated;
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
