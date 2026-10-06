package com.seamware.consentmanager.service;

/**
 * What a registration did with one user, which the API maps onto a status code or a per-entry
 * outcome.
 */
public enum RegistrationOutcome {

    /** No user carried the identifier, so one was created and linked to the participant. */
    CREATED,

    /** The user already existed and this call linked it to the participant. */
    LINKED,

    /** The user was already linked; only {@code localIdentifier} may have changed. */
    ALREADY_LINKED,

    /**
     * The registration was not applied. Reachable only through {@link
     * UserService#registerBulkForParticipant}, which reports a failed entry rather than failing the
     * batch; {@link UserService#registerForParticipant} throws instead of returning this.
     */
    REJECTED
}
