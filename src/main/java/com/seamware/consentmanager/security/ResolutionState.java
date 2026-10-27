package com.seamware.consentmanager.security;

/**
 * Where one trust-list entry stands in discovering its OpenID Provider Metadata. Only {@link
 * #RESOLVED} is usable; {@link IdentityProviderRegistry#findByIssuer(String)} hides the rest, so an
 * unresolved provider's token is rejected exactly like an unknown issuer's.
 */
public enum ResolutionState {

    /** Discovery has not yet succeeded; another attempt is scheduled. The initial state. */
    PENDING,

    /** Metadata fetched and its {@code issuer} matched; usable for token validation. */
    RESOLVED,

    /** The discovered {@code issuer} disagrees with the configured one; never retried. */
    FAILED
}
