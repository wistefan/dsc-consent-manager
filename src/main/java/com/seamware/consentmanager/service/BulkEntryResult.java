package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;

/**
 * What a bulk registration did with one entry of a batch.
 *
 * <p>{@code identifier} is echoed from the entry and is null when it named none. {@code reason} is
 * set for {@link RegistrationOutcome#REJECTED} and null for every other outcome; it is published to
 * the caller, so it must carry no detail a problem detail would not.
 */
public record BulkEntryResult(
        @Nullable String identifier, RegistrationOutcome outcome, @Nullable String reason) {

    /** An entry that was applied, whatever it did. */
    static BulkEntryResult of(@Nullable String identifier, RegistrationOutcome outcome) {
        return new BulkEntryResult(identifier, outcome, null);
    }

    /** An entry that was not applied, and why. */
    static BulkEntryResult rejected(@Nullable String identifier, String reason) {
        return new BulkEntryResult(identifier, RegistrationOutcome.REJECTED, reason);
    }
}
