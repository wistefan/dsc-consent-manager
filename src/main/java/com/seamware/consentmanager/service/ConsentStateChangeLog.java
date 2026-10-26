package com.seamware.consentmanager.service;

import static net.logstash.logback.argument.StructuredArguments.v;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.observability.ConsentMetrics;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs and counts one event per committed consent creation or move; the participant-notification
 * seam. A log, not a queue: nothing is buffered, retried or delivered. See ADR-0011 and ADR-0015.
 */
@Singleton
public class ConsentStateChangeLog {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentStateChangeLog.class);

    /** Message of a move's log line; its values are also emitted as discrete JSON fields. */
    private static final String MOVED =
            "Consent {} of subject {} between provider {} and consumer {}: {} -> {}, recorded as"
                    + " {} ({})";

    private final ConsentMetrics metrics;

    public ConsentStateChangeLog(ConsentMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Records a committed move; {@code held} is the status read under the row lock.
     *
     * <p>The subject is logged only by opaque {@code userId} and the actor not at all: erasure
     * cannot reach a shipped log line, so identifiers must never enter it. See ADR-0007.
     */
    public void recorded(
            @NonNull ConsentStatus held,
            @NonNull ConsentDetails moved,
            @NonNull ConsentEvent event) {
        emit(Optional.of(held), moved, event);
    }

    /** Records an inserted consent; its line omits {@code heldStatus} rather than logging null. */
    public void created(@NonNull ConsentDetails created, @NonNull ConsentEvent event) {
        emit(Optional.empty(), created, event);
    }

    /** Logs and counts the write; an affirmation moves no status, so it is logged, not counted. */
    private void emit(Optional<ConsentStatus> held, ConsentDetails written, ConsentEvent event) {
        Consent consent = written.consent();
        // A plain argument is interpolated but, unlike v(...), adds no JSON field.
        Object heldArgument =
                held.<Object>map(status -> v("heldStatus", status))
                        .orElse(ConsentMetrics.NO_STATUS);
        LOG.info(
                MOVED,
                v("consentId", consent.getId()),
                v("subjectUserId", consent.getUserId()),
                v("provider", written.providerIdentifier()),
                v("consumer", written.consumerIdentifier()),
                heldArgument,
                v("newStatus", consent.getStatus()),
                v("eventState", event.getEventState()),
                v("eventType", event.getEventType()));
        if (held.filter(consent.getStatus()::equals).isEmpty()) {
            metrics.transitioned(held, consent.getStatus());
        }
    }
}
