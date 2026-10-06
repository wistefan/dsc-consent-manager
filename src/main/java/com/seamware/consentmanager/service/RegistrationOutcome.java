package com.seamware.consentmanager.service;

/**
 * What a registration did with one user, which the API maps onto a status code or a per-entry
 * outcome.
 *
 * <p>Every constant is reachable: a registration that cannot be applied is refused rather than
 * described, so refusal is not modelled here but as a null outcome on {@link BulkEntryResult}.
 */
public enum RegistrationOutcome {

    /** No user carried the identifier, so one was created and linked to the participant. */
    CREATED,

    /** The user already existed and this call linked it to the participant. */
    LINKED,

    /** The user was already linked; only {@code localIdentifier} may have changed. */
    ALREADY_LINKED
}
