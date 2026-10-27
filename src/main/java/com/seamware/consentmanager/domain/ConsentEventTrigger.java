package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.NonNull;

/**
 * What caused a consent event: the named actor's own act, or an automated job on its behalf.
 * Independent of {@link ConsentEventState}, since expiry and termination arise from either.
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
