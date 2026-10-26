package com.seamware.consentmanager.security;

import io.micronaut.core.annotation.Nullable;
import java.util.Objects;

/**
 * An immutable snapshot of one trust-list entry and what discovery has learned about it so far. The
 * registry replaces it whole, so a holder always sees a consistent view. {@code jwksUri} is
 * non-{@code null} iff {@link ResolutionState#RESOLVED}; {@code failureReason} is the latest one.
 */
public record ResolvedIdentityProvider(
        IdentityProviderConfiguration configuration,
        ResolutionState state,
        @Nullable String jwksUri,
        @Nullable String failureReason,
        int attempts) {

    /**
     * Checks the invariant that ties {@link #jwksUri()} to {@link #state()}.
     *
     * @throws IllegalArgumentException if a JWK Set URL is present without RESOLVED, or vice versa
     */
    public ResolvedIdentityProvider {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(state, "state");
        boolean resolved = state == ResolutionState.RESOLVED;
        if (resolved && (jwksUri == null || jwksUri.isBlank())) {
            throw new IllegalArgumentException(
                    "A RESOLVED identity provider must carry the jwks_uri discovery returned");
        }
        if (!resolved && jwksUri != null) {
            throw new IllegalArgumentException(
                    "Only a RESOLVED identity provider may carry a jwks_uri; state was " + state);
        }
    }

    /** The {@link ResolutionState#PENDING} snapshot every entry starts in, with no attempts. */
    public static ResolvedIdentityProvider pending(IdentityProviderConfiguration configuration) {
        return new ResolvedIdentityProvider(configuration, ResolutionState.PENDING, null, null, 0);
    }

    /** A provider whose metadata was fetched and issuer matched; attempts include the success. */
    public static ResolvedIdentityProvider resolved(
            IdentityProviderConfiguration configuration, String jwksUri, int attempts) {
        return new ResolvedIdentityProvider(
                configuration, ResolutionState.RESOLVED, jwksUri, null, attempts);
    }

    /** A {@link ResolutionState#PENDING} provider whose latest attempt failed and will retry. */
    public static ResolvedIdentityProvider retrying(
            IdentityProviderConfiguration configuration, String failureReason, int attempts) {
        return new ResolvedIdentityProvider(
                configuration, ResolutionState.PENDING, null, failureReason, attempts);
    }

    /** A {@link ResolutionState#FAILED} provider that will never be retried. */
    public static ResolvedIdentityProvider failed(
            IdentityProviderConfiguration configuration, String failureReason, int attempts) {
        return new ResolvedIdentityProvider(
                configuration, ResolutionState.FAILED, null, failureReason, attempts);
    }

    /** The configured issuer this entry is keyed by; the discovered one must equal it. */
    public String issuer() {
        return configuration.getIssuer();
    }

    /** The operator's name for this entry, from its configuration key (e.g. {@code primary}). */
    public String name() {
        return configuration.getName();
    }

    /** Whether this entry may validate a token, i.e. is {@link ResolutionState#RESOLVED}. */
    public boolean isUsable() {
        return state == ResolutionState.RESOLVED;
    }
}
