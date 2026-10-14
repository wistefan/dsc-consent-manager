package com.seamware.consentmanager.service;

import static net.logstash.logback.argument.StructuredArguments.v;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits one event per lifecycle move, and is the seam a notification subsystem attaches to.
 *
 * <p>Every move {@link ConsentLifecycleService} records passes through here once the write has
 * committed, so TICKET-013's subject notification and TICKET-020's participant notification have a
 * single call site to grow from. It is a log and not a queue: nothing here is buffered, retried or
 * delivered to anyone, and a deployment that needs a revocation to reach the parties needs the
 * subsystem those tickets own — see ADR-0011.
 */
@Singleton
public class ConsentStateChangeLog {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentStateChangeLog.class);

    /**
     * The human-readable half of the one line a move leaves on stdout; the values it interpolates
     * are also emitted as discrete JSON fields (see {@link #recorded}).
     *
     * <p>Held and new status are both named because a reader of the stream reconstructs the
     * lifecycle from them without joining back to the database, which is what makes the line worth
     * emitting at all rather than counting the move in a metric.
     */
    private static final String MOVED =
            "Consent {} of subject {} between provider {} and consumer {}: {} -> {}, recorded as"
                    + " {} ({})";

    /**
     * Records a move that has been written, naming what it concerns and the trail entry it left.
     *
     * <p>Each value is passed through {@link net.logstash.logback.argument.StructuredArguments#v}
     * so the configured {@code LogstashEncoder} emits it as a named JSON field as well as
     * interpolating it into the message, which is what lets a consumer read {@code consentId} or
     * {@code newStatus} as data rather than regexing the prose — the stated point of the line.
     *
     * <p>The subject is named by its opaque {@code userId}, never by its identifier, and the actor
     * is left to {@code consent_events.actor}: an erasure rewrites the database and cannot reach a
     * log line already shipped, so ADR-0007's boundary holds only if the identifiers stay out of
     * here. The {@code event} argument carries both regardless, because a notification growing from
     * this seam needs to know who acted even though the log line does not say.
     *
     * <p>{@code held} is the status {@link ConsentWriter} read under the row lock, not an earlier
     * unlocked read, so a concurrent move of the same record cannot leave it naming a status the
     * write did not actually move from.
     */
    public void recorded(
            @NonNull ConsentStatus held,
            @NonNull ConsentDetails moved,
            @NonNull ConsentEvent event) {
        Consent consent = moved.consent();
        LOG.info(
                MOVED,
                v("consentId", consent.getId()),
                v("subjectUserId", consent.getUserId()),
                v("provider", moved.providerIdentifier()),
                v("consumer", moved.consumerIdentifier()),
                v("heldStatus", held),
                v("newStatus", consent.getStatus()),
                v("eventState", event.getEventState()),
                v("eventType", event.getEventType()));
    }
}
