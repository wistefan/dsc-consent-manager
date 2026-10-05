package com.seamware.consentmanager.service;

/** What {@link UserService#registerForParticipant} did, which the API maps onto a status code. */
public enum RegistrationOutcome {

    /** No user carried the identifier, so one was created and linked to the participant. */
    CREATED,

    /** The user already existed and this call linked it to the participant. */
    LINKED,

    /** The user was already linked; only {@code localIdentifier} may have changed. */
    ALREADY_LINKED
}
