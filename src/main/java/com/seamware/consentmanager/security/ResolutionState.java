package com.seamware.consentmanager.security;

/**
 * Where one trust-list entry stands in the discovery of its OpenID Provider Metadata.
 *
 * <p>The state describes <em>metadata</em> only. Membership of the trust list is fixed at startup
 * and no state here adds, removes or replaces an entry: a {@link #FAILED} provider is still a
 * configured provider, it simply cannot be used to validate a token.
 *
 * <p>Only {@link #RESOLVED} makes an entry usable. {@link
 * IdentityProviderRegistry#findByIssuer(String)} hides the other two, so a token from a provider
 * that is configured but not yet resolved is rejected exactly like a token from an issuer nobody
 * configured - the caller is told nothing about the trust list or about this service's internal
 * progress through it.
 */
public enum ResolutionState {

    /**
     * Discovery has not yet succeeded, and another attempt is scheduled.
     *
     * <p>This is the state every entry starts in, and the state it returns to after a retryable
     * failure such as a connection refusal, a timeout or a 5xx response. An entry can leave it for
     * {@link #RESOLVED} or {@link #FAILED} at any time.
     */
    PENDING,

    /**
     * The provider's metadata was fetched and its {@code issuer} matched the configured one.
     *
     * <p>The entry carries a {@code jwks_uri} and is usable for token validation.
     */
    RESOLVED,

    /**
     * Discovery failed permanently and will not be retried.
     *
     * <p>Reached only when the discovery document's {@code issuer} disagrees with the configured
     * issuer. That is a misconfiguration rather than an outage: retrying cannot fix it, and
     * continuing to poll would hide the problem behind a stream of identical log lines. Recovery
     * requires a configuration change and a restart, consistent with the trust list being immutable
     * for the life of the process.
     */
    FAILED
}
