package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for the decisions {@link IdentityProviderRegistry} makes without talking to anything.
 *
 * <p>The registry's network behaviour - discovery, retries, readiness - is covered by {@code
 * IdentityProviderDiscoveryIT}, which needs a stub provider and real elapsed time. What is asserted
 * here are the two pure functions that decide <em>how long</em> to wait and <em>whether</em> a
 * discovered JWK Set URL may be trusted. Both are plain functions of their arguments, so they
 * belong in the fast phase: a regression in the backoff curve or in the transport rule should fail
 * {@code ./mvnw test}, not only the integration phase.
 *
 * <p>The cleartext rule in particular cannot be reached end-to-end at all: a WireMock stub is
 * served over {@code http}, so any context pointed at one has already set {@code
 * allow-insecure-transport}, which is exactly the flag that suppresses the rejection.
 */
class IdentityProviderRegistryTest {

    @ParameterizedTest(name = "{0} consecutive failures wait {1}")
    @CsvSource({
        "1, PT2S",
        "2, PT4S",
        "3, PT8S",
        "4, PT16S",
        "9, PT5M",
        "100, PT5M",
    })
    @DisplayName("the retry delay doubles per failure and is capped")
    void retryDelayGrowsExponentiallyAndIsCapped(int attempt, Duration expected) {
        assertThat(IdentityProviderRegistry.retryDelay(attempt))
                .as(
                        "backoff starts at the configured minimum and never exceeds the configured"
                                + " maximum")
                .isEqualTo(expected);
        assertThat(IdentityProviderRegistry.retryDelay(attempt))
                .isBetween(
                        IdentityProviderRegistry.MIN_DISCOVERY_RETRY_DELAY,
                        IdentityProviderRegistry.MAX_DISCOVERY_RETRY_DELAY);
    }

    @ParameterizedTest(name = "{0} (insecure transport allowed: {1}) is fetchable")
    @CsvSource({
        "https://idp.example.com/protocol/openid-connect/certs, false",
        "https://idp.example.com/protocol/openid-connect/certs, true",
        "http://localhost:8080/realms/test/protocol/openid-connect/certs, true",
    })
    @DisplayName("an https JWK Set URL is accepted, and a cleartext one only under the opt-in")
    void fetchableJwksUriIsAccepted(String jwksUri, boolean allowInsecureTransport) {
        assertThat(IdentityProviderRegistry.jwksUriProblem(jwksUri, allowInsecureTransport))
                .as("nothing about this URL stops signing keys being fetched from it")
                .isEmpty();
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("unusableJwksUris")
    @DisplayName("an unusable JWK Set URL is reported with a reason naming what is wrong")
    void unusableJwksUriIsReported(
            String jwksUri,
            boolean allowInsecureTransport,
            String expectedReasonFragment,
            String description) {
        Optional<String> problem =
                IdentityProviderRegistry.jwksUriProblem(jwksUri, allowInsecureTransport);

        assertThat(problem).as(description).isPresent();
        assertThat(problem.orElseThrow())
                .as("the reason is logged verbatim, so it has to say which rule was broken")
                .contains(expectedReasonFragment);
    }

    /**
     * The JWK Set URLs a discovery document must not be trusted with.
     *
     * <p>The cleartext row is the security-relevant one: the document itself may well have arrived
     * over https, so the entry's secure-transport opt-in would be bypassed by a provider that
     * simply points at an {@code http} key set.
     *
     * @return rows of {@code (jwks_uri, allow-insecure-transport, expected reason fragment,
     *     description)}
     */
    private static Stream<Arguments> unusableJwksUris() {
        return Stream.of(
                Arguments.of(null, false, "no jwks_uri", "a document with no jwks_uri member"),
                Arguments.of("", false, "no jwks_uri", "an empty jwks_uri"),
                Arguments.of("   ", false, "no jwks_uri", "a blank jwks_uri"),
                Arguments.of(
                        "/realms/test/protocol/openid-connect/certs",
                        false,
                        "absolute",
                        "a relative jwks_uri, which there is no base URL to resolve"),
                Arguments.of(
                        "ftp://idp.example.com/certs",
                        false,
                        "absolute",
                        "a jwks_uri this service has no way to fetch"),
                Arguments.of(
                        "https:/realms/test/protocol/openid-connect/certs",
                        false,
                        "absolute",
                        "a jwks_uri with a scheme but no host to connect to"),
                Arguments.of(
                        "https://",
                        false,
                        "unparseable",
                        "a jwks_uri whose authority is empty, which java.net.URI rejects outright"),
                Arguments.of(
                        "https://idp.example.com/cer ts",
                        false,
                        "unparseable",
                        "a jwks_uri that is not a URL at all"),
                Arguments.of(
                        "http://idp.example.com/protocol/openid-connect/certs",
                        false,
                        "cleartext",
                        "a cleartext jwks_uri from an entry that did not opt in"));
    }
}
