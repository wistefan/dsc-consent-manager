package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;

/**
 * What a bulk registration did with one entry of a batch. {@code outcome} is null exactly when
 * rejected, and then {@code reason} is set; the reason is published, so it must carry no detail a
 * problem detail would not.
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
