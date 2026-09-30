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
 * <p><strong>The issuer is not yet matched exactly.</strong> Until the per-provider validator lands
 * (step 5), a token's {@code iss} is checked by Micronaut's global {@code
 * IssuerJwtClaimsValidator}, which does not compare for equality: verified against
 * micronaut-security 5.4.0, it strips {@code http://}/{@code https://} and one trailing slash from
 * both sides and then tests {@code expected.endsWith(actual)}. The token's value therefore only has
 * to be a <em>suffix</em> of the configured one, with the scheme discarded — with an issuer of
 * {@code http://keycloak:8180/realms/consent-manager}, the values {@code consent-manager} and
 * {@code https://keycloak:8180/realms/consent-manager} are both accepted. That is not exploitable
 * on its own, because the token must still carry a valid signature from the trusted provider's
 * JWKS, but step 5 must replace it with a byte-for-byte comparison rather than assume one is
 * already in force.
 *
 * @param name the shared configuration key of the two blocks, for example {@code keycloak}
 * @param issuer the absolute issuer URL; matched against a token's {@code iss} only as loosely as
 *     described above until step 5 tightens it to a byte-for-byte comparison
 * @param settings this service's claim and role settings for the issuer
 */
public record TrustedIdentityProvider(
        String name, String issuer, IdentityProviderConfiguration settings) {}
