package com.seamware.consentmanager.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Per-participant webhook delivery meters, tagged by the stable {@code participants.id}, so their
 * cardinality is bounded by the registered participants. Called only after the outcome commits.
 */
@Singleton
public class NotificationMetrics {

    /** Counter of dispatch outcomes, tagged {@link #PARTICIPANT_TAG} and {@link #OUTCOME_TAG}. */
    public static final String ATTEMPTS_METRIC =
            "consentmanager.participant.notifications.attempts";

    /** Counter of abandoned rows, tagged {@link #PARTICIPANT_TAG} and {@link #REASON_TAG}. */
    public static final String ABANDONED_METRIC =
            "consentmanager.participant.notifications.abandoned";

    /** Timer from enqueue to delivery, both stamped by the database. */
    public static final String LATENCY_METRIC = "consentmanager.participant.notifications.latency";

    /** Gauge of due rows per participant; a participant with none due has no series. */
    public static final String QUEUE_DEPTH_METRIC =
            "consentmanager.participant.notifications.queue.depth";

    /** Tag carrying the recipient's {@code participants.id}. */
    public static final String PARTICIPANT_TAG = "participant";

    /** Tag carrying an {@link Outcome}. */
    public static final String OUTCOME_TAG = "outcome";

    /** Tag carrying an {@link AbandonReason}. */
    public static final String REASON_TAG = "reason";

    /** What one dispatch attempt came to. */
    public enum Outcome {
        DELIVERED,
        RETRY,
        ABANDONED;

        /** The tag value. */
        public String tag() {
            return tagValue(this);
        }
    }

    /** Why a row was abandoned; bounded, unlike the free-text {@code last_error}. */
    public enum AbandonReason {
        /** The endpoint answered a status that will never succeed (3xx or a final 4xx). */
        REJECTED,
        /** Every scheduled retry failed. */
        RETRIES_EXHAUSTED,
        /** The participant has no {@code consentNotification} endpoint. */
        NO_ENDPOINT,
        /** The endpoint resolves to an address the SSRF fence refuses. */
        ENDPOINT_NOT_PERMITTED,
        /** The participant is gone or deregistered. */
        PARTICIPANT_DEREGISTERED,
        /** The row reached dispatch without a payload. */
        PAYLOAD_MISSING,
        /** The age backstop abandoned it. */
        MAX_AGE_EXCEEDED;

        /** The tag value. */
        public String tag() {
            return tagValue(this);
        }
    }

    private final MeterRegistry registry;
    private final MultiGauge queueDepth;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.queueDepth = MultiGauge.builder(QUEUE_DEPTH_METRIC).register(registry);
    }

    /** Counts one committed dispatch outcome. */
    public void attempted(@NonNull UUID participantId, @NonNull Outcome outcome) {
        Counter.builder(ATTEMPTS_METRIC)
                .tag(PARTICIPANT_TAG, participantId.toString())
                .tag(OUTCOME_TAG, outcome.tag())
                .register(registry)
                .increment();
    }

    /** Counts one committed abandonment; the dispatcher also counts it as an attempt. */
    public void abandoned(@NonNull UUID participantId, @NonNull AbandonReason reason) {
        abandoned(participantId, reason, 1);
    }

    /** Counts {@code rows} committed abandonments of a sweep, which are not attempts. */
    public void abandoned(@NonNull UUID participantId, @NonNull AbandonReason reason, int rows) {
        Counter.builder(ABANDONED_METRIC)
                .tag(PARTICIPANT_TAG, participantId.toString())
                .tag(REASON_TAG, reason.tag())
                .register(registry)
                .increment(rows);
    }

    /** Records one delivery's time from enqueue. */
    public void delivered(@NonNull UUID participantId, @NonNull Duration latency) {
        Timer.builder(LATENCY_METRIC)
                .tag(PARTICIPANT_TAG, participantId.toString())
                .register(registry)
                .record(latency);
    }

    /**
     * Replaces every queue-depth reading; participants absent from {@code due} lose their series.
     */
    public void queueDepth(@NonNull Map<UUID, Long> due) {
        queueDepth.register(
                due.entrySet().stream()
                        .map(
                                entry ->
                                        MultiGauge.Row.of(
                                                Tags.of(PARTICIPANT_TAG, entry.getKey().toString()),
                                                entry.getValue()))
                        .toList(),
                true);
    }

    private static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
