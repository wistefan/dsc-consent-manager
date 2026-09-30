package com.seamware.consentmanager.domain;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Represents the type of state transition recorded in a consent event.
 *
 * <p>Each value corresponds to a significant change in the consent lifecycle. These states are
 * stored in the {@code event_state} column of the {@code consent_events} table and provide an audit
 * trail of all consent lifecycle transitions.
 */
@Serdeable
public enum ConsentEventState {

    /** Consent was granted by the user. */
    CONSENT_GIVEN,

    /** Consent was requested from the user. */
    CONSENT_REQUESTED,

    /** Consent was revoked by the user. */
    CONSENT_REVOKED,

    /** Consent request was refused by the user. */
    CONSENT_REFUSED,

    /** Consent was terminated by the system or an administrator. */
    CONSENT_TERMINATED,

    /** Consent was re-confirmed by the user (e.g. after a policy update). */
    CONSENT_RE_CONFIRMED,

    /** Consent was resumed after a suspension or pause. */
    CONSENT_RESUMED,

    /** Consent expired according to its retention period. */
    CONSENT_EXPIRED
}
