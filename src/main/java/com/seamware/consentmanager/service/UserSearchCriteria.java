package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;

/**
 * What a user search filters on. Criteria combine conjunctively; a set naming none is rejected
 * rather than treated as "everybody".
 *
 * <p>A criterion present but blank is normalised to {@code null} on construction, so every consumer
 * sees "absent" and "empty string" the same way.
 *
 * @param identifier exact, case-sensitive global user identifier
 * @param email exact contact address, matched case-insensitively and possibly matching several
 *     users, because email is neither unique nor authoritative here
 * @param participantIdentifier participant whose linked users the result is confined to; for a
 *     {@code PARTICIPANT} caller this can only narrow what {@link CallerScope} already allows
 */
public record UserSearchCriteria(
        @Nullable String identifier,
        @Nullable String email,
        @Nullable String participantIdentifier) {

    public UserSearchCriteria {
        identifier = nullIfBlank(identifier);
        email = nullIfBlank(email);
        participantIdentifier = nullIfBlank(participantIdentifier);
    }

    /** True when no criterion was supplied, which the API answers with {@code 400}. */
    public boolean isEmpty() {
        return identifier == null && email == null && participantIdentifier == null;
    }

    private static @Nullable String nullIfBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
