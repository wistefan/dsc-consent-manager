package com.seamware.consentmanager.security;

import com.nimbusds.jose.jwk.KeyType;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.security.token.jwt.signature.jwks.JwksSignatureConfiguration;
import java.util.Objects;

/**
 * Presents one resolved trust-list entry in the shape {@code micronaut-security-jwt} describes a
 * JWK Set endpoint.
 *
 * <p>This is the same move {@link OpenIdClientConfigurationAdapter} makes for discovery, for the
 * same reason: the module's JWKS components consume a public interface, and that interface is
 * reachable without the declarative {@code micronaut.security.token.jwt.signatures.jwks.<name>}
 * configuration block. That block cannot be used here - it binds a <em>fixed</em> set of URLs at
 * startup, while a {@code jwks_uri} only becomes known once the registry's asynchronous discovery
 * resolves it, and it registers each resulting signature as a <em>global</em> verifier that any
 * token may be checked against regardless of its issuer. Adapting the interface instead keeps the
 * module's fetching, caching, key matching and signature verification while leaving the routing -
 * which issuer's keys a token is allowed to be checked against - with {@link
 * IssuerSignatureVerifier}.
 *
 * @see IssuerSignatureVerifier
 */
final class JwksSignatureConfigurationAdapter implements JwksSignatureConfiguration {

    /**
     * Value reported for the module's deprecated per-configuration cache knob.
     *
     * <p>{@code JwksSignatureConfiguration#getCacheExpiration()} is {@code @Deprecated(forRemoval =
     * true, since = "4.11.0")} and is not read by the fetcher this service runs on: with a {@code
     * jwks} cache configured - which {@code application.yml} always configures - caching goes
     * through Micronaut Cache and the lifetime is {@code micronaut.caches.jwks.expire-after-write}.
     * The interface still declares the accessor as non-null, so a value has to be returned; it is
     * deliberately the module's own default rather than a number invented here, so that nothing
     * appears to be configurable through a knob that is not.
     */
    static final int UNUSED_CACHE_EXPIRATION_SECONDS = 60;

    private final String name;
    private final String url;

    /**
     * Adapts a resolved provider.
     *
     * @param provider the trust-list entry, which must be {@link ResolutionState#RESOLVED} so that
     *     it carries the {@code jwks_uri} discovery returned
     * @throws NullPointerException if the provider is {@code null}
     * @throws IllegalArgumentException if the provider carries no JWK Set URL
     */
    JwksSignatureConfigurationAdapter(ResolvedIdentityProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String jwksUri = provider.jwksUri();
        if (jwksUri == null || jwksUri.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider '"
                            + provider.name()
                            + "' carries no jwks_uri; only a RESOLVED provider may be adapted");
        }
        this.name = provider.name();
        this.url = jwksUri;
    }

    /**
     * Returns the JWK Set URL discovery published for this provider.
     *
     * @return an absolute URL, never {@code null}
     */
    @Override
    @NonNull
    public String getUrl() {
        return url;
    }

    /**
     * Returns the key type the module should restrict its matching to.
     *
     * <p>Always {@code null}, meaning "do not restrict". A provider may publish RSA and EC keys
     * side by side, and pinning one type here would make the other unusable; what a key is allowed
     * to be is decided by the signature verification itself, not by filtering the key set.
     *
     * @return {@code null}, always
     */
    @Override
    @Nullable
    public KeyType getKeyType() {
        return null;
    }

    /**
     * Returns the module's deprecated per-configuration cache lifetime.
     *
     * @return {@link #UNUSED_CACHE_EXPIRATION_SECONDS}
     */
    @Override
    @NonNull
    @SuppressWarnings("removal")
    public Integer getCacheExpiration() {
        return UNUSED_CACHE_EXPIRATION_SECONDS;
    }

    /**
     * Returns the configured name of the provider.
     *
     * <p>The module passes this to its JWKS client so an operator can give one provider its own
     * HTTP client configuration, and uses it as part of the cache key.
     *
     * @return the trust-list entry's name, never {@code null}
     */
    @Override
    @NonNull
    public String getName() {
        return name;
    }
}
