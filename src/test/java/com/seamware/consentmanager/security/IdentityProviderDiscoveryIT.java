package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the discovery half of the trust list: what the registry learns from an OpenID provider, what
 * it refuses to learn, and what an unresolved provider does to this instance's readiness.
 *
 * <p>Every test drives a real {@link IdentityProviderRegistry} inside a started application context
 * against a WireMock provider, because the behaviour under test is almost entirely about timing and
 * failure: that startup does not wait for discovery, that an outage is retried while a
 * misconfiguration is not, and that an instance which cannot yet validate tokens takes itself out
 * of a load balancer's rotation instead of answering 401s. None of that is observable from a unit
 * test of the parsing.
 *
 * <p>Each test owns its context and its stub so that one test's backoff schedule cannot leak into
 * another's request counts.
 */
@DisplayName("OpenID Connect discovery and readiness")
class IdentityProviderDiscoveryIT {

    /** Longest a test waits for a state discovery is expected to reach. */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Window a permanently failed provider is watched over to show it is not polled again.
     *
     * <p>Comfortably longer than {@link IdentityProviderRegistry#MIN_DISCOVERY_RETRY_DELAY}, which
     * is when a retryable failure would have produced its second request.
     */
    private static final Duration NO_RETRY_WINDOW =
            IdentityProviderRegistry.MIN_DISCOVERY_RETRY_DELAY.plusSeconds(2);

    /** Health endpoint route reporting whether the process is alive. */
    private static final String LIVENESS_PATH = "/health/liveness";

    /** Health endpoint route reporting whether this instance can serve traffic. */
    private static final String READINESS_PATH = "/health/readiness";

    /** Key carrying the aggregate status in a health response body. */
    private static final String STATUS_KEY = "status";

    /** Reusable type argument for the health endpoint's JSON body. */
    private static final Argument<Map<String, Object>> HEALTH_BODY =
            Argument.mapOf(String.class, Object.class);

    @Test
    @DisplayName("a well-formed document resolves the provider's JWK Set URL")
    void wellFormedDocumentResolvesTheJwksUri() {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            IdentityProviderRegistry registry = context.getBean(IdentityProviderRegistry.class);

            awaitState(registry, ResolutionState.RESOLVED);

            Optional<ResolvedIdentityProvider> resolved = registry.findByIssuer(stub.issuer());
            assertThat(resolved)
                    .as("a provider whose document matches is usable for token validation")
                    .isPresent();
            assertThat(resolved.orElseThrow().jwksUri())
                    .as("the signing keys are looked up where the document says they are")
                    .isEqualTo(stub.jwksUri());
            assertThat(registry.isFullyResolved())
                    .as("the only configured provider resolved, so the trust list is complete")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an issuer mismatch fails the provider permanently and is never retried")
    void issuerMismatchIsPermanentAndNeverRetried() {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub()) {
            String foreignIssuer = stub.issuer() + "-somebody-else";
            stub.serveMetadataDeclaring(foreignIssuer);

            try (ApplicationContext context = startContext(stub, Map.of())) {
                IdentityProviderRegistry registry = context.getBean(IdentityProviderRegistry.class);

                awaitState(registry, ResolutionState.FAILED);

                assertThat(onlyEntry(registry).failureReason())
                        .as("the operator is told which issuer the document actually declared")
                        .contains(foreignIssuer);
                assertThat(registry.findByIssuer(stub.issuer()))
                        .as(
                                "a provider pointing at somebody else's keys is never handed to the"
                                        + " validator")
                        .isEmpty();

                int requestsSoFar = stub.discoveryRequestCount();
                Await.never(
                        "a second discovery request for a permanently failed provider",
                        NO_RETRY_WINDOW,
                        () -> stub.discoveryRequestCount() > requestsSoFar);
            }
        }
    }

    @ParameterizedTest(name = "discovery answering HTTP {0} leaves the provider unusable")
    @ValueSource(ints = {404, 500, 503})
    @DisplayName("an unreachable provider is retried and stays unusable meanwhile")
    void discoveryFailureKeepsTheProviderPendingAndRetried(int statusCode) {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub()) {
            stub.serveFailure(statusCode);

            try (ApplicationContext context = startContext(stub, Map.of())) {
                IdentityProviderRegistry registry = context.getBean(IdentityProviderRegistry.class);

                Await.until(
                        "the first discovery attempt to fail",
                        RESOLUTION_TIMEOUT,
                        () -> onlyEntry(registry).attempts() > 0);

                assertThat(onlyEntry(registry).state())
                        .as(
                                "an outage is retryable, so the entry stays pending rather than failing")
                        .isEqualTo(ResolutionState.PENDING);
                assertThat(registry.findByIssuer(stub.issuer()))
                        .as(
                                "a configured but unresolved issuer is indistinguishable from an"
                                        + " unregistered one, so its tokens get the same generic"
                                        + " 401")
                        .isEmpty();

                Await.until(
                        "discovery to be retried after the backoff delay",
                        RESOLUTION_TIMEOUT,
                        () -> stub.discoveryRequestCount() > 1);
            }
        }
    }

    @Test
    @DisplayName("an outage leaves the context started, liveness UP and readiness DOWN")
    void outageDrainsTheInstanceWithoutKillingIt() {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub()) {
            stub.serveFailure(HttpStatus.SERVICE_UNAVAILABLE.getCode());

            Map<String, Object> onItsOwnPort = Map.of("micronaut.server.port", -1);
            try (ApplicationContext context = startContext(stub, onItsOwnPort)) {
                EmbeddedServer server = context.getBean(EmbeddedServer.class).start();
                try (HttpClient client = HttpClient.create(server.getURL())) {
                    assertThat(context.isRunning())
                            .as("an identity provider's outage must never stop this service")
                            .isTrue();
                    assertThat(statusOf(client, LIVENESS_PATH))
                            .as(
                                    "liveness stays UP: restarting this process cannot fix somebody"
                                            + " else's outage")
                            .isEqualTo(HttpStatus.OK.getCode());
                    assertThat(statusOf(client, READINESS_PATH))
                            .as(
                                    "readiness is DOWN so an orchestrator drains the instance"
                                            + " instead of letting it answer misleading 401s")
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.getCode());
                    assertThat(healthStatusOf(client, READINESS_PATH))
                            .as("the readiness body names the aggregate state")
                            .isEqualTo(HealthStatusName.DOWN.name());

                    stub.serveMatchingMetadata();

                    Await.until(
                            "readiness to recover once the provider is reachable again",
                            RESOLUTION_TIMEOUT,
                            () -> statusOf(client, READINESS_PATH) == HttpStatus.OK.getCode());
                    assertThat(healthStatusOf(client, READINESS_PATH))
                            .as("a retry succeeded without a restart, so the instance can serve")
                            .isEqualTo(HealthStatusName.UP.name());
                }
            }
        }
    }

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

    /**
     * Starts a context whose single configured provider is the given stub.
     *
     * <p>The datasource, Flyway and the HTTP server are left out unless a test asks for them: this
     * suite is about discovery, and a PostgreSQL container would only slow it down.
     *
     * @param stub the provider the trust list points at
     * @param overrides extra properties layered on top
     * @return the started context
     */
    private static ApplicationContext startContext(
            OidcDiscoveryStub stub, Map<String, Object> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.put("micronaut.server.port", -1);
        properties.put("consent-manager.identity-providers.primary.issuer", stub.issuer());
        properties.put(
                "consent-manager.identity-providers.primary.discovery-url", stub.discoveryUrl());
        properties.putAll(overrides);
        return ApplicationContext.builder().environments("test").properties(properties).start();
    }

    /**
     * Returns the one entry every context in this suite configures.
     *
     * @param registry the registry to read
     * @return the single trust-list entry, resolved or not
     */
    private static ResolvedIdentityProvider onlyEntry(IdentityProviderRegistry registry) {
        assertThat(registry.snapshot()).as("these tests configure exactly one provider").hasSize(1);
        return registry.snapshot().getFirst();
    }

    /**
     * Waits for the single configured provider to reach a resolution state.
     *
     * @param registry the registry to poll
     * @param expected the state discovery is expected to reach
     */
    private static void awaitState(IdentityProviderRegistry registry, ResolutionState expected) {
        Await.until(
                "the configured provider to reach " + expected,
                RESOLUTION_TIMEOUT,
                () -> onlyEntry(registry).state() == expected);
    }

    /**
     * Issues a GET and reports its status, treating an error response as a result rather than a
     * failure.
     *
     * <p>Reports the numeric code rather than {@link HttpStatus}: Micronaut's enum implements
     * {@link CharSequence}, which makes {@code assertThat(HttpStatus)} ambiguous between AssertJ's
     * generic and {@code CharSequence} overloads.
     *
     * @param client the client to issue the request with
     * @param path the path to probe
     * @return the response status code
     */
    private static int statusOf(HttpClient client, String path) {
        return response(client, path).code();
    }

    /**
     * Issues a GET and reports the aggregate status its health body declares.
     *
     * @param client the client to issue the request with
     * @param path the health route to probe
     * @return the value of the body's {@code status} member
     */
    private static String healthStatusOf(HttpClient client, String path) {
        Map<String, Object> body = response(client, path).getBody(HEALTH_BODY).orElseThrow();
        return String.valueOf(body.get(STATUS_KEY));
    }

    /**
     * Performs a blocking GET, unwrapping the exception Micronaut raises for a non-2xx response.
     *
     * <p>A {@code DOWN} health endpoint answers 503 by design, so an error status is the expected
     * outcome of half the probes here and must not abort the test.
     *
     * @param client the client to issue the request with
     * @param path the path to probe
     * @return the response, whatever its status
     */
    private static HttpResponse<?> response(HttpClient client, String path) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(path), HEALTH_BODY);
        } catch (HttpClientResponseException error) {
            return error.getResponse();
        }
    }

    /**
     * The aggregate health status names this test asserts on.
     *
     * <p>Micronaut renders {@link io.micronaut.health.HealthStatus} as its name in the response
     * body; naming the two values here keeps them out of the assertions as bare strings.
     */
    private enum HealthStatusName {
        /** Every indicator reported healthy. */
        UP,
        /** At least one indicator reported unhealthy. */
        DOWN
    }
}
