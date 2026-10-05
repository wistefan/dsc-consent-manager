package com.seamware.consentmanager.security;

import io.micronaut.security.oauth2.configuration.OpenIdClientConfiguration;
import io.micronaut.security.oauth2.configuration.endpoints.AuthorizationEndpointConfiguration;
import io.micronaut.security.oauth2.configuration.endpoints.EndSessionEndpointConfiguration;
import io.micronaut.security.oauth2.configuration.endpoints.EndpointConfiguration;
import io.micronaut.security.oauth2.configuration.endpoints.TokenEndpointConfiguration;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Optional;

/**
 * Presents one trust-list entry in the shape {@code micronaut-security-oauth2} expects, so that its
 * {@link io.micronaut.security.oauth2.client.OpenIdProviderMetadataFetcher} can perform this
 * service's OpenID Connect discovery.
 *
 * <p>The module's own {@code micronaut.security.oauth2.clients.*} binding is not used, because it
 * models a provider as a <em>login client</em>: it demands a client id, and the beans it switches
 * on add authorization-code login routes and an anonymous {@code
 * /.well-known/oauth-protected-resource} document to a service that issues no tokens, holds no
 * credentials and keeps no sessions. Only the discovery half of the module is wanted here, and
 * {@link OpenIdClientConfiguration} is the public interface that half consumes - so a trust-list
 * entry is adapted onto it directly and the fetcher is constructed against the result.
 *
 * <p><strong>How the discovery URL is reconstructed.</strong> The fetcher composes the document URL
 * as {@code issuer + configuration-path}. This service configures the discovery URL explicitly, as
 * one absolute URL, because a provider's metadata is not always published under the issuer's own
 * path. The configured URL is therefore split at its origin: {@link #getIssuer()} returns the
 * scheme, host and port, {@link #getConfigurationPath()} returns everything after them. Recomposing
 * those two yields exactly the configured URL, so the operator's value is fetched verbatim and this
 * class never builds a {@code .well-known} path of its own.
 *
 * <p>The remaining members of {@link OpenIdClientConfiguration} describe the authorization, token,
 * registration, user-info and end-session endpoints of a login client. A resource server calls none
 * of them, and the metadata fetcher reads none of them, so they are reported as absent rather than
 * guessed at.
 */
final class OpenIdClientConfigurationAdapter implements OpenIdClientConfiguration {

    /**
     * End-session configuration reported for every entry: no URL, and disabled.
     *
     * <p>{@link OpenIdClientConfiguration#getEndSession()} is the one accessor that is not {@link
     * Optional}, so it is answered with a disabled endpoint rather than with {@code null}.
     */
    private static final EndSessionEndpointConfiguration DISABLED_END_SESSION =
            new EndSessionEndpointConfiguration() {

                @Override
                public Optional<String> getUrl() {
                    return Optional.empty();
                }

                @Override
                public boolean isEnabled() {
                    return false;
                }
            };

    /** The provider name, used by the fetcher only for its log lines. */
    private final String name;

    /** Scheme, host and port of the configured discovery URL. */
    private final URL origin;

    /** Path, query and fragment of the configured discovery URL, relative to {@link #origin}. */
    private final String configurationPath;

    /**
     * @param name the provider name this entry is configured under
     * @param origin the origin the discovery request is sent to
     * @param configurationPath the rest of the configured discovery URL
     */
    private OpenIdClientConfigurationAdapter(String name, URL origin, String configurationPath) {
        this.name = name;
        this.origin = origin;
        this.configurationPath = configurationPath;
    }

    /**
     * Adapts a validated trust-list entry.
     *
     * @param configuration the entry whose {@code discovery-url} is to be fetched; {@link
     *     IdentityProviderRegistryValidator} has already asserted that the URL is absolute, carries
     *     a supported scheme and names a host
     * @return the adapted view the metadata fetcher is constructed against
     * @throws IllegalStateException if the configured discovery URL cannot be turned into a {@link
     *     URL}, which startup validation is expected to have ruled out
     */
    static OpenIdClientConfigurationAdapter forEntry(IdentityProviderConfiguration configuration) {
        URI discoveryUri = URI.create(configuration.getDiscoveryUrl());
        StringBuilder path = new StringBuilder(discoveryUri.getRawPath());
        if (discoveryUri.getRawQuery() != null) {
            path.append('?').append(discoveryUri.getRawQuery());
        }
        if (discoveryUri.getRawFragment() != null) {
            path.append('#').append(discoveryUri.getRawFragment());
        }
        try {
            URL origin =
                    new URI(
                                    discoveryUri.getScheme(),
                                    null,
                                    discoveryUri.getHost(),
                                    discoveryUri.getPort(),
                                    null,
                                    null,
                                    null)
                            .toURL();
            return new OpenIdClientConfigurationAdapter(
                    configuration.getName(), origin, path.toString());
        } catch (MalformedURLException | URISyntaxException unusable) {
            throw new IllegalStateException(
                    "The discovery URL configured at "
                            + configuration.getPropertyPath()
                            + " is not a usable URL: '"
                            + configuration.getDiscoveryUrl()
                            + "'",
                    unusable);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return name;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<URL> getIssuer() {
        return Optional.of(origin);
    }

    /** {@inheritDoc} */
    @Override
    public String getConfigurationPath() {
        return configurationPath;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> getJwksUri() {
        return Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EndpointConfiguration> getRegistration() {
        return Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EndpointConfiguration> getUserInfo() {
        return Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AuthorizationEndpointConfiguration> getAuthorization() {
        return Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TokenEndpointConfiguration> getToken() {
        return Optional.empty();
    }

    /** {@inheritDoc} */
    @Override
    public EndSessionEndpointConfiguration getEndSession() {
        return DISABLED_END_SESSION;
    }
}
