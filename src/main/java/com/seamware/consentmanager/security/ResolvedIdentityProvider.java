package com.seamware.consentmanager.security;

import io.micronaut.core.annotation.Nullable;
import java.util.Objects;

/**
 * An immutable snapshot of one trust-list entry and of what discovery has so far learned about it.
 *
 * <p>Two things are deliberately bundled here. The {@code configuration} half is fixed for the
 * lifetime of the process - it is what the operator wrote in {@code
 * consent-manager.identity-providers.<name>}. The {@code state} / {@code jwksUri} / {@code
 * failureReason} half is what {@link IdentityProviderRegistry} discovered about that provider at
 * runtime, and is the only part that ever changes.
 *
 * <p>Being a record, an instance never changes: the registry replaces the whole snapshot when
 * discovery advances. A caller holding one therefore sees a self-consistent view - it can never
 * observe {@link ResolutionState#RESOLVED} next to a {@code null} {@code jwksUri} because a later
 * attempt overwrote one field but not the other.
 *
 * @param configuration the operator-supplied settings for this provider, never {@code null}
 * @param state how far discovery has got for this provider, never {@code null}
 * @param jwksUri the URL of the provider's JWK Set, as published in its discovery document;
 *     non-{@code null} if and only if {@code state} is {@link ResolutionState#RESOLVED}
 * @param failureReason a human-readable account of the most recent discovery failure, for logs and
 *     for the readiness report; {@code null} while no attempt has failed yet
 * @param attempts how many discovery attempts have been made for this provider, used to derive the
 *     retry backoff and to show in the readiness report how long a provider has been struggling
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
     * @throws NullPointerException if the configuration or the state is {@code null}
     * @throws IllegalArgumentException if a {@link ResolutionState#RESOLVED} snapshot carries no
     *     JWK Set URL, or a snapshot that is not resolved carries one
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

    /**
     * Creates the snapshot every entry starts in: known, configured, not yet discovered.
     *
     * @param configuration the operator-supplied settings for this provider
     * @return a {@link ResolutionState#PENDING} snapshot with no attempts recorded
     */
    public static ResolvedIdentityProvider pending(IdentityProviderConfiguration configuration) {
        return new ResolvedIdentityProvider(configuration, ResolutionState.PENDING, null, null, 0);
    }

    /**
     * Creates the snapshot of a provider whose metadata was fetched and whose issuer matched.
     *
     * @param configuration the operator-supplied settings for this provider
     * @param jwksUri the JWK Set URL the discovery document published
     * @param attempts how many attempts it took, the successful one included
     * @return a {@link ResolutionState#RESOLVED} snapshot
     */
    public static ResolvedIdentityProvider resolved(
            IdentityProviderConfiguration configuration, String jwksUri, int attempts) {
        return new ResolvedIdentityProvider(
                configuration, ResolutionState.RESOLVED, jwksUri, null, attempts);
    }

    /**
     * Creates the snapshot of a provider whose latest attempt failed but will be retried.
     *
     * @param configuration the operator-supplied settings for this provider
     * @param failureReason what went wrong on the most recent attempt
     * @param attempts how many attempts have been made, the failed one included
     * @return a {@link ResolutionState#PENDING} snapshot carrying the failure
     */
    public static ResolvedIdentityProvider retrying(
            IdentityProviderConfiguration configuration, String failureReason, int attempts) {
        return new ResolvedIdentityProvider(
                configuration, ResolutionState.PENDING, null, failureReason, attempts);
    }

    /**
     * Creates the snapshot of a provider that will never be retried.
     *
     * @param configuration the operator-supplied settings for this provider
     * @param failureReason why the provider is permanently unusable
     * @param attempts how many attempts have been made, the fatal one included
     * @return a {@link ResolutionState#FAILED} snapshot carrying the failure
     */
    public static ResolvedIdentityProvider failed(
            IdentityProviderConfiguration configuration, String failureReason, int attempts) {
        return new ResolvedIdentityProvider(
                configuration, ResolutionState.FAILED, null, failureReason, attempts);
    }

    /**
     * Returns the issuer this entry is keyed by.
     *
     * <p>This is always the <em>configured</em> issuer. The discovered one is never stored, because
     * an entry only becomes {@link ResolutionState#RESOLVED} when the two are equal.
     *
     * @return the configured issuer identifier
     */
    public String issuer() {
        return configuration.getIssuer();
    }

    /**
     * Returns the operator's name for this entry, as written in the configuration key.
     *
     * @return the provider name, for example {@code primary}
     */
    public String name() {
        return configuration.getName();
    }

    /**
     * Reports whether this entry may be used to validate a token.
     *
     * @return {@code true} if the state is {@link ResolutionState#RESOLVED}
     */
    public boolean isUsable() {
        return state == ResolutionState.RESOLVED;
    }
}
