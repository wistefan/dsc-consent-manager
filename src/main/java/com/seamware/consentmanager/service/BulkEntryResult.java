package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;

/**
 * What a bulk registration did with one entry of a batch.
 *
 * <p>{@code outcome} is null exactly when the entry was rejected, and {@code reason} is set exactly
 * then: {@link UserService#registerForParticipant} refuses an entry it cannot apply instead of
 * returning an outcome for it, so rejection is a state of the batch rather than a {@link
 * RegistrationOutcome}. {@code identifier} is echoed from the entry and is null when it named none.
 * The reason is published to the caller, so it must carry no detail a problem detail would not.
 */
public record BulkEntryResult(
        @Nullable String identifier,
        @Nullable RegistrationOutcome outcome,
        @Nullable String reason) {

    /** An entry that was applied, whatever it did. */
    static BulkEntryResult applied(String identifier, RegistrationOutcome outcome) {
        return new BulkEntryResult(identifier, outcome, null);
    }

    /** An entry that was not applied, and why. */
    static BulkEntryResult rejected(@Nullable String identifier, String reason) {
        return new BulkEntryResult(identifier, null, reason);
    }
}
