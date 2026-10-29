package com.seamware.consentmanager.notification;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The retry schedule: one delay per retry, each spread by up to {@code ±jitter} of itself. Pure;
 * the caller adds the delay to the database clock. Not Micronaut's {@code @Retryable}: these
 * retries span hours and must outlive the process and its lease, so their state is the outbox row.
 */
public final class NotificationBackoff {

    private final List<Duration> schedule;
    private final double jitter;

    /** A schedule of {@code schedule} spread by the fraction {@code jitter}. */
    public NotificationBackoff(List<Duration> schedule, double jitter) {
        this.schedule = List.copyOf(schedule);
        this.jitter = jitter;
    }

    /**
     * The delay before the next attempt once {@code attemptsMade} attempts have failed, counting
     * the one just made; empty once the schedule is exhausted, which abandons the row.
     */
    public Optional<Duration> delay(int attemptsMade, RandomGenerator random) {
        int retry = attemptsMade - 1;
        if (retry < 0 || retry >= schedule.size()) {
            return Optional.empty();
        }
        double spread = jitter * (2 * random.nextDouble() - 1);
        long nanos = schedule.get(retry).toNanos();
        return Optional.of(Duration.ofNanos(Math.round(nanos * (1 + spread))));
    }
}
