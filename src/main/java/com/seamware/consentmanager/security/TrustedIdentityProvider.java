package com.seamware.consentmanager.security;

/**
 * One entry of the trust list: an issuer the Consent Manager accepts tokens from, paired with this
 * service's own settings for it.
 *
 * <p>The two halves come from two different configuration blocks, joined by {@link #name()}:
 *
 * <ul>
 *   <li>{@link #issuer()} is declared as {@code
 *       micronaut.security.oauth2.clients.<name>.openid.issuer} and is owned by Micronaut Security,
 *       which also performs OpenID discovery against it and maintains its signing-key cache.
 *   <li>{@link #settings()} is declared as {@code consent-manager.identity-providers.<name>} and
 *       carries only what Micronaut cannot express: the per-provider audience, clock skew, claim
 *       paths and role mapping.
 * </ul>
 *
 * <p>Instances are created once, during startup validation, and are immutable thereafter.
 *
 * @param name the shared configuration key of the two blocks, for example {@code keycloak}
 * @param issuer the absolute issuer URL, compared byte-for-byte against a token's {@code iss}
 * @param settings this service's claim and role settings for the issuer
 */
public record TrustedIdentityProvider(
        String name, String issuer, IdentityProviderConfiguration settings) {}
