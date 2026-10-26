package com.seamware.consentmanager.service;

/**
 * What a registration did with one user; mapped to a status code or a per-entry outcome. Refusal is
 * not a constant here but a null outcome on {@link BulkEntryResult}.
 */
public enum RegistrationOutcome {

    /** No user carried the identifier, so one was created and linked to the participant. */
    CREATED,

    /** The user already existed and this call linked it to the participant. */
    LINKED,

    /** The user was already linked; only {@code localIdentifier} may have changed. */
    ALREADY_LINKED
}
