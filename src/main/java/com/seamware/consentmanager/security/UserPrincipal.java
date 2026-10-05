package com.seamware.consentmanager.security;

import com.seamware.consentmanager.domain.User;
import io.micronaut.core.annotation.Nullable;

/**
 * A natural person acting for themselves.
 *
 * <p>{@code identifier} is the globally unique user identity read from the issuing provider's
 * configured {@code claims.user-identifier} and is what user-owned records key on; {@link
 * #subject()} is unique per issuer only and is a display and log value. The profile fields are the
 * standard OpenID Connect claims and are all optional - a provider that publishes none of them
 * still yields a usable principal.
 *
 * @param issuer the verified {@code iss}
 * @param subject the verified {@code sub}
 * @param identifier the globally unique user identifier
 * @param email the {@code email} claim, or {@code null} if the token carries none
 * @param emailVerified the {@code email_verified} claim; {@code false} when the claim is absent,
 *     because an unstated verification is not a verification
 * @param name the {@code name} claim, or {@code null}
 * @param givenName the {@code given_name} claim, or {@code null}
 * @param familyName the {@code family_name} claim, or {@code null}
 * @param user the provisioned {@code users} row; {@code null} only before {@link #withUser}
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
        @Nullable User user)
        implements ConsentManagerPrincipal {

    /** Returns {@link Role#USER}. */
    @Override
    public Role role() {
        return Role.USER;
    }

    /**
     * Returns a copy carrying the provisioned user row.
     *
     * @param provisioned the row this principal's identifier resolved to
     */
    public UserPrincipal withUser(User provisioned) {
        return new UserPrincipal(
                issuer,
                subject,
                identifier,
                email,
                emailVerified,
                name,
                givenName,
                familyName,
                provisioned);
    }
}
