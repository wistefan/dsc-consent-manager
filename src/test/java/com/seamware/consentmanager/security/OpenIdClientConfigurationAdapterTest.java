package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.security.oauth2.configuration.OpenIdClientConfiguration;
import java.net.URL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link OpenIdClientConfigurationAdapter}.
 *
 * <p>The adapter exists so {@code micronaut-security-oauth2}'s metadata fetcher can issue this
 * service's discovery request. The fetcher composes the document URL as {@code issuer +
 * configuration-path}, while this service configures one absolute {@code discovery-url}, so the
 * single property the adapter has to guarantee is that splitting and recomposing is lossless: the
 * operator's URL is what gets fetched, verbatim, and no {@code .well-known} path is ever invented
 * here. That is a pure function of the configured string, so it is asserted without a network.
 */
class OpenIdClientConfigurationAdapterTest {

    /** Entry name used for every fixture; the fetcher uses it only for log lines. */
    private static final String PROVIDER_NAME = "primary";

    @ParameterizedTest(name = "{0} is split and recomposed unchanged")
    @ValueSource(
            strings = {
                "https://idp.example.com/.well-known/openid-configuration",
                "https://idp.example.com/realms/dataspace/.well-known/openid-configuration",
                "https://idp.example.com:8443/realms/dataspace/.well-known/openid-configuration",
                "http://localhost:8080/realms/dataspace/.well-known/openid-configuration",
                "https://idp.example.com/discovery?realm=dataspace",
                "https://idp.example.com/oidc/metadata",
            })
    @DisplayName("recomposing the adapted origin and path yields the configured discovery URL")
    void configuredDiscoveryUrlIsFetchedVerbatim(String discoveryUrl) {
        OpenIdClientConfiguration adapted = adapt(discoveryUrl);

        assertThat(adapted.getIssuer())
                .as("the fetcher needs an origin to send the request to")
                .isPresent();
        assertThat(
                        adapted.getIssuer().map(URL::toString).orElseThrow()
                                + adapted.getConfigurationPath())
                .as(
                        "the fetcher composes issuer + configuration-path, so the two halves must"
                                + " recompose into exactly the configured URL - this service"
                                + " composes no .well-known path of its own")
                .isEqualTo(discoveryUrl);
    }

    @Test
    @DisplayName("no login-client endpoint is claimed, because a resource server calls none")
    void loginClientEndpointsAreReportedAbsent() {
        OpenIdClientConfiguration adapted =
                adapt("https://idp.example.com/.well-known/openid-configuration");

        assertThat(adapted.getName()).isEqualTo(PROVIDER_NAME);
        assertThat(adapted.getJwksUri())
                .as(
                        "the JWK Set URL is taken from the discovered document, never from"
                                + " configuration")
                .isEmpty();
        assertThat(adapted.getRegistration()).isEmpty();
        assertThat(adapted.getUserInfo()).isEmpty();
        assertThat(adapted.getAuthorization()).isEmpty();
        assertThat(adapted.getToken()).isEmpty();
        assertThat(adapted.getEndSession().isEnabled())
                .as("a service that keeps no sessions ends none")
                .isFalse();
        assertThat(adapted.getEndSession().getUrl()).isEmpty();
    }

    /**
     * Builds the adapter for a trust-list entry carrying the given discovery URL.
     *
     * @param discoveryUrl the configured {@code discovery-url}
     * @return the adapted view the metadata fetcher would be constructed against
     */
    private static OpenIdClientConfiguration adapt(String discoveryUrl) {
        IdentityProviderConfiguration configuration =
                new IdentityProviderConfiguration(PROVIDER_NAME);
        configuration.setDiscoveryUrl(discoveryUrl);
        return OpenIdClientConfigurationAdapter.forEntry(configuration);
    }
}
