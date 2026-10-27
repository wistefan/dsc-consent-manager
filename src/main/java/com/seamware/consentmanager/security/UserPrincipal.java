package com.seamware.consentmanager.security;

import com.seamware.consentmanager.domain.User;
import io.micronaut.core.annotation.Nullable;

/**
 * A natural person acting for themselves.
 *
 * <p>{@code identifier} (from {@code claims.user-identifier}) is globally unique and keys
 * user-owned records; {@code subject} is a display value. Profile claims are optional ({@code null}
 * when absent; {@code emailVerified} is {@code false}). {@code user} is {@code null} only before
 * {@link #withUser}; {@code created} says this request's insert created it ({@code 201} vs {@code
 * 200}).
 */
public record UserPrincipal(
        String issuer,
        String subject,
        String identifier,
        @Nullable String email,
        boolean emailVerified,
        @Nullable String name,
        @Nullable String givenName,
        @Nullable String familyName,
        @Nullable User user,
        boolean created)
        implements ConsentManagerPrincipal {

    /** Returns {@link Role#USER}. */
    @Override
    public Role role() {
        return Role.USER;
    }

    /**
     * Returns a copy carrying the provisioned row and whether this request's insert created it.
     * Propagated, not re-derived: provisioning runs in the filter, so a handler would see the row.
     */
    public UserPrincipal withUser(User provisioned, boolean created) {
        return new UserPrincipal(
                issuer,
                subject,
                identifier,
                email,
                emailVerified,
                name,
                givenName,
                familyName,
                provisioned,
                created);
    }
}
