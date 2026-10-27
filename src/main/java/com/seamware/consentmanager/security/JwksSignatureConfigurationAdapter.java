package com.seamware.consentmanager.security;

import com.nimbusds.jose.jwk.KeyType;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.security.token.jwt.signature.jwks.JwksSignatureConfiguration;
import java.util.Objects;

/**
 * Adapts one resolved trust-list entry as a {@code micronaut-security-jwt} JWK Set endpoint. Which
 * issuer's keys a token may be checked against stays with {@link IssuerSignatureVerifier}. See
 * ADR-0004.
 */
final class JwksSignatureConfigurationAdapter implements JwksSignatureConfiguration {

    /**
     * The module default for its deprecated, unread cache knob; the real lifetime is {@code
     * micronaut.caches.jwks.expire-after-write}.
     */
    static final int UNUSED_CACHE_EXPIRATION_SECONDS = 60;

    private final String name;
    private final String url;

    /**
     * Adapts a {@link ResolutionState#RESOLVED} provider.
     *
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

    /** The absolute JWK Set URL discovery published for this provider. */
    @Override
    @NonNull
    public String getUrl() {
        return url;
    }

    /** Always {@code null} ("do not restrict"): a provider may publish RSA and EC side by side. */
    @Override
    @Nullable
    public KeyType getKeyType() {
        return null;
    }

    /** Returns {@link #UNUSED_CACHE_EXPIRATION_SECONDS}. */
    @Override
    @NonNull
    @SuppressWarnings("removal")
    public Integer getCacheExpiration() {
        return UNUSED_CACHE_EXPIRATION_SECONDS;
    }

    /** The trust-list entry's name; also selects its HTTP client config and keys the cache. */
    @Override
    @NonNull
    public String getName() {
        return name;
    }
}
