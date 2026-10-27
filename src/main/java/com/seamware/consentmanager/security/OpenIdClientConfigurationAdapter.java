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
 * Adapts one trust-list entry so the module's {@link
 * io.micronaut.security.oauth2.client.OpenIdProviderMetadataFetcher} can run discovery. The
 * discovery URL is split at its origin so {@code issuer + configuration-path} recomposes it
 * verbatim; the login-client endpoints are reported absent. See ADR-0003.
 */
final class OpenIdClientConfigurationAdapter implements OpenIdClientConfiguration {

    /** Disabled end-session endpoint; {@link #getEndSession()} is not {@link Optional}. */
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

    private OpenIdClientConfigurationAdapter(String name, URL origin, String configurationPath) {
        this.name = name;
        this.origin = origin;
        this.configurationPath = configurationPath;
    }

    /**
     * Adapts an entry {@link IdentityProviderRegistryValidator} has already validated.
     *
     * @throws IllegalStateException if the discovery URL is not a usable {@link URL}
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
