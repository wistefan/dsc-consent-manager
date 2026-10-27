package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;

/**
 * Conjunctive user search criteria; none at all is rejected, and blank normalises to {@code null}.
 *
 * @param identifier exact, case-sensitive global user identifier
 * @param email exact address, case-insensitive; may match several users
 * @param participantIdentifier confines to that participant's users; only narrows {@link
 *     CallerScope}
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
