package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;

/**
 * Checks the participant-notification settings at startup, so a lease too short for its batch, a
 * pool a few dead endpoints could fill, or a max age that cuts the retry schedule fails boot.
 */
@Context
public class NotificationConfigurationValidator {

    private final ParticipantNotifications settings;

    NotificationConfigurationValidator(ParticipantNotifications settings) {
        this.settings = settings;
    }

    @PostConstruct
    void validate() {
        check(settings);
    }

    /** Throws a {@link ConfigurationException} naming the settings of the first rule broken. */
    static void check(ParticipantNotifications s) {
        requirePositive("PARTICIPANT_NOTIFICATIONS_BATCH_SIZE", s.getBatchSize());
        requirePositive("PARTICIPANT_NOTIFICATIONS_CONCURRENCY", s.getConcurrency());
        requirePositive(
                "PARTICIPANT_NOTIFICATIONS_PER_PARTICIPANT_BATCH_LIMIT",
                s.getPerParticipantBatchLimit());
        requirePositive("PARTICIPANT_NOTIFICATIONS_RESOLVER_THREADS", s.getResolverThreads());
        requirePositive("PARTICIPANT_NOTIFICATIONS_POLL_INTERVAL", s.getPollInterval());
        requirePositive("PARTICIPANT_NOTIFICATIONS_SWEEP_INTERVAL", s.getSweepInterval());
        requirePositive("PARTICIPANT_NOTIFICATIONS_RETENTION", s.getRetention());
        if (s.getPerParticipantBatchLimit() >= s.getConcurrency()) {
            throw new ConfigurationException(
                    "PARTICIPANT_NOTIFICATIONS_PER_PARTICIPANT_BATCH_LIMIT (%d) must be below PARTICIPANT_NOTIFICATIONS_CONCURRENCY (%d), or one dead endpoint can hold every delivery worker"
                            .formatted(s.getPerParticipantBatchLimit(), s.getConcurrency()));
        }
        int isolatedDeadHosts = (s.getConcurrency() - 1) / s.getPerParticipantBatchLimit();
        if (s.getResolverThreads() <= isolatedDeadHosts) {
            throw new ConfigurationException(
                    "PARTICIPANT_NOTIFICATIONS_RESOLVER_THREADS (%d) must exceed (PARTICIPANT_NOTIFICATIONS_CONCURRENCY - 1) / PARTICIPANT_NOTIFICATIONS_PER_PARTICIPANT_BATCH_LIMIT (%d), or stalled lookups can block every healthy host"
                            .formatted(s.getResolverThreads(), isolatedDeadHosts));
        }
        checkBackoff(s.getBackoff(), s.getJitter());
        int waves = Math.ceilDiv(s.getBatchSize(), s.getConcurrency());
        Duration batchBound =
                s.sendBound().multipliedBy(waves).plus(ParticipantNotifications.LEASE_MARGIN);
        if (s.getLease().compareTo(batchBound) <= 0) {
            throw new ConfigurationException(
                    "PARTICIPANT_NOTIFICATIONS_LEASE (%s) must exceed %s: ceil(PARTICIPANT_NOTIFICATIONS_BATCH_SIZE / PARTICIPANT_NOTIFICATIONS_CONCURRENCY) waves of (PARTICIPANT_NOTIFICATIONS_RESOLVE_TIMEOUT + CONNECT_TIMEOUT + READ_TIMEOUT) plus %s"
                            .formatted(
                                    s.getLease(),
                                    batchBound,
                                    ParticipantNotifications.LEASE_MARGIN));
        }
        Duration schedule = retrySchedule(s);
        if (s.getMaxAge().compareTo(schedule) < 0) {
            throw new ConfigurationException(
                    "PARTICIPANT_NOTIFICATIONS_MAX_AGE (%s) must be at least %s, the whole retry schedule of PARTICIPANT_NOTIFICATIONS_BACKOFF at full PARTICIPANT_NOTIFICATIONS_JITTER plus a PARTICIPANT_NOTIFICATIONS_LEASE and PARTICIPANT_NOTIFICATIONS_POLL_INTERVAL per attempt"
                            .formatted(s.getMaxAge(), schedule));
        }
    }

    /**
     * Longest a row can stay due when every attempt fails: each delay at full jitter, plus a lease
     * in flight and a poll interval waiting to be claimed per attempt.
     */
    static Duration retrySchedule(ParticipantNotifications s) {
        long delayNanos =
                s.getBackoff().stream().mapToLong(Duration::toNanos).reduce(0L, Math::addExact);
        Duration delays = Duration.ofNanos(Math.round(delayNanos * (1 + s.getJitter())));
        int attempts = s.getBackoff().size() + 1;
        return delays.plus(s.getLease().plus(s.getPollInterval()).multipliedBy(attempts));
    }

    private static void checkBackoff(List<Duration> backoff, double jitter) {
        if (backoff == null
                || backoff.isEmpty()
                || backoff.stream().anyMatch(delay -> delay == null || !delay.isPositive())) {
            throw new ConfigurationException(
                    "PARTICIPANT_NOTIFICATIONS_BACKOFF (%s) must list at least one positive delay"
                            .formatted(backoff));
        }
        if (!(jitter >= 0 && jitter < 1)) {
            throw new ConfigurationException(
                    "PARTICIPANT_NOTIFICATIONS_JITTER (%s) must be at least 0 and below 1"
                            .formatted(jitter));
        }
    }

    private static void requirePositive(String variable, int value) {
        if (value < 1) {
            throw new ConfigurationException(
                    "%s (%d) must be at least 1".formatted(variable, value));
        }
    }

    private static void requirePositive(String variable, Duration value) {
        if (value == null || !value.isPositive()) {
            throw new ConfigurationException("%s (%s) must be positive".formatted(variable, value));
        }
    }
}
