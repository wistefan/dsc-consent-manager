package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.NonNull;

/**
 * What caused a consent event: a deliberate act of the actor it names, or an automated job acting
 * on its behalf.
 *
 * <p>Carried separately from {@link ConsentEventState} because the two are independent: an expiry
 * and a termination are each reachable from a job and from a person, and an audit trail that
 * records a scheduler's expiry as explicit misstates who decided.
 */
public enum ConsentEventTrigger {

    /** A decision taken by the identified actor. */
    EXPLICIT(ConsentEvent.DEFAULT_EVENT_TYPE),

    /** A change applied by an automated job, such as the TTL expiry sweep. */
    SYSTEM("system");

    private final String eventType;

    ConsentEventTrigger(String eventType) {
        this.eventType = eventType;
    }

    /** The value written to {@code consent_events.event_type}. */
    @NonNull
    public String eventType() {
        return eventType;
    }
}
