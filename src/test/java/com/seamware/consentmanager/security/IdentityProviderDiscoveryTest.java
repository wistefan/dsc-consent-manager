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
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.runtime.server.EmbeddedServer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

/**
 * Tests trust-list discovery against WireMock providers: what the registry learns or refuses,
 * retrying an outage but not a misconfiguration, and how an unresolved provider affects readiness.
 */
@DisplayName("OpenID Connect discovery and readiness")
class IdentityProviderDiscoveryTest {

    /** Longest a test waits for a state discovery is expected to reach. */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Window a permanently failed provider is watched over to show it is not polled again; well
     * beyond {@link IdentityProviderRegistry#MIN_DISCOVERY_RETRY_DELAY}.
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

    /** Entry name of the provider {@code application.yml} declares, filled in by every context. */
    private static final String PRIMARY = "primary";

    /** Entry name of the extra provider the multi-provider test adds. */
    private static final String SECONDARY = "secondary";

    /** Prefix of a trust-list entry's configuration properties. */
    private static final String PROVIDER_PREFIX = "consent-manager.identity-providers.";

    /** Property that sets the log level of the class under test. */
    private static final String REGISTRY_LOG_LEVEL_PROPERTY =
            "logger.levels.com.seamware.consentmanager.security.IdentityProviderRegistry";

    /** Registry log level for this suite, overriding the suite-wide {@code OFF}. */
    private static final String REGISTRY_LOG_LEVEL = "DEBUG";

    /** Audience every stub provider's entry declares; discovery never looks at it. */
    private static final String STUB_AUDIENCE = "consent-manager";

    /** Roles claim path the stub entries declare; discovery never looks at it either. */
    private static final List<String> STUB_ROLES_CLAIM = List.of("realm_access", "roles");

    /** Participant identifier claim path the stub entries declare. */
    private static final String STUB_PARTICIPANT_CLAIM = "participant_id";

    /** Raw role string the stub entries map {@link Role#USER} to. */
    private static final String STUB_USER_ROLE = "consent-user";

    /** Detail key under which the health indicator reports an entry's resolution state. */
    private static final String DETAIL_STATE_KEY = "state";

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

    @ParameterizedTest(name = "{0} is retried rather than condemning the provider")
    @EnumSource(DegenerateDocument.class)
    @DisplayName("a document that cannot confirm the issuer is an outage, not a misconfiguration")
    void degenerateDocumentIsRetriedRatherThanFailedPermanently(DegenerateDocument document) {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub()) {
            document.serveFrom(stub);

            try (ApplicationContext context = startContext(stub, Map.of())) {
                IdentityProviderRegistry registry = context.getBean(IdentityProviderRegistry.class);

                Await.until(
                        "the first discovery attempt to fail",
                        RESOLUTION_TIMEOUT,
                        () -> onlyEntry(registry).attempts() > 0);

                assertThat(onlyEntry(registry).state())
                        .as(
                                "only a document naming a *different* issuer is permanent; a"
                                        + " response that says nothing about the issuer is an"
                                        + " upstream blip and must not need a restart to clear")
                        .isEqualTo(ResolutionState.PENDING);
                assertThat(registry.findByIssuer(stub.issuer()))
                        .as("an unconfirmed provider is never handed to the validator")
                        .isEmpty();

                stub.serveMatchingMetadata();

                awaitState(registry, ResolutionState.RESOLVED);
                assertThat(onlyEntry(registry).jwksUri())
                        .as("the retry recovered the provider without restarting the service")
                        .isEqualTo(stub.jwksUri());
            }
        }
    }

    @Test
    @DisplayName("one provider resolving does not make a partly-resolved trust list ready")
    void partiallyResolvedTrustListIsNotReady() {
        try (OidcDiscoveryStub healthy = new OidcDiscoveryStub();
                OidcDiscoveryStub unreachable = new OidcDiscoveryStub()) {
            unreachable.serveFailure(HttpStatus.SERVICE_UNAVAILABLE.getCode());

            Map<String, Object> properties = new LinkedHashMap<>();
            properties.putAll(providerProperties(PRIMARY, healthy));
            properties.putAll(providerProperties(SECONDARY, unreachable));

            try (ApplicationContext context = startContext(properties)) {
                IdentityProviderRegistry registry = context.getBean(IdentityProviderRegistry.class);

                Await.until(
                        "the reachable provider to resolve",
                        RESOLUTION_TIMEOUT,
                        () -> registry.findByIssuer(healthy.issuer()).isPresent());

                assertThat(registry.isFullyResolved())
                        .as(
                                "readiness is all-or-nothing: an instance that would reject every"
                                        + " token from the provider that did not resolve is worse"
                                        + " than one that is plainly not ready")
                        .isFalse();
                assertThat(registry.findByIssuer(unreachable.issuer()))
                        .as(
                                "the unresolved provider's tokens get the same generic 401 as an"
                                        + " unregistered issuer's")
                        .isEmpty();

                assertThat(registry.snapshot().stream().map(ResolvedIdentityProvider::name))
                        .as("the trust list is reported ordered by provider name")
                        .containsExactly(PRIMARY, SECONDARY);
                assertThat(stateByProviderName(context))
                        .as(
                                "the health detail names every provider, ordered by provider name,"
                                        + " so an operator can see which one is holding readiness"
                                        + " down")
                        .containsExactly(
                                Map.entry(PRIMARY, ResolutionState.RESOLVED.name()),
                                Map.entry(SECONDARY, ResolutionState.PENDING.name()));
            }
        }
    }

    /**
     * Starts a context whose single configured provider is the given stub. The datasource, Flyway
     * and HTTP server are left out unless a test asks for them.
     */
    private static ApplicationContext startContext(
            OidcDiscoveryStub stub, Map<String, Object> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.putAll(providerProperties(PRIMARY, stub));
        properties.putAll(overrides);
        return startContext(properties);
    }

    /** Starts a context whose trust list is exactly the given properties' providers. */
    private static ApplicationContext startContext(Map<String, Object> properties) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("datasources.default.enabled", false);
        all.put("flyway.enabled", false);
        all.put("micronaut.server.port", -1);
        // application-test.yml silences the registry suite-wide; this suite is about discovery
        // failure and retry timing, so it re-enables the logger to keep flaky runs diagnosable.
        all.put(REGISTRY_LOG_LEVEL_PROPERTY, REGISTRY_LOG_LEVEL);
        all.putAll(properties);
        return ApplicationContext.builder().environments("test").properties(all).start();
    }

    /**
     * Renders one fully specified trust-list entry pointing at a stub. Every required property is
     * written out, since a second entry inherits nothing from {@code application.yml}.
     */
    private static Map<String, Object> providerProperties(String name, OidcDiscoveryStub stub) {
        String prefix = PROVIDER_PREFIX + name + ".";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(prefix + "issuer", stub.issuer());
        properties.put(prefix + "discovery-url", stub.discoveryUrl());
        properties.put(prefix + "audience", STUB_AUDIENCE);
        // WireMock serves plain http, so every entry here has to opt out of the https requirement.
        properties.put(prefix + "allow-insecure-transport", true);
        properties.put(prefix + "claims.participant-identifier", List.of(STUB_PARTICIPANT_CLAIM));
        properties.put(prefix + "claims.roles", STUB_ROLES_CLAIM);
        properties.put(prefix + "role-mapping.user", STUB_USER_ROLE);
        return properties;
    }

    /** Returns the one entry every context in this suite configures, resolved or not. */
    private static ResolvedIdentityProvider onlyEntry(IdentityProviderRegistry registry) {
        assertThat(registry.snapshot()).as("these tests configure exactly one provider").hasSize(1);
        return registry.snapshot().getFirst();
    }

    /** Waits for the single configured provider to reach a resolution state. */
    private static void awaitState(IdentityProviderRegistry registry, ResolutionState expected) {
        Await.until(
                "the configured provider to reach " + expected,
                RESOLUTION_TIMEOUT,
                () -> onlyEntry(registry).state() == expected);
    }

    /**
     * Issues a GET and returns the numeric status, error responses included. Numeric because {@link
     * HttpStatus} is a {@link CharSequence}, which makes {@code assertThat} ambiguous.
     */
    private static int statusOf(HttpClient client, String path) {
        return response(client, path).code();
    }

    /** Issues a GET and returns the {@code status} member its health body declares. */
    private static String healthStatusOf(HttpClient client, String path) {
        Map<String, Object> body = response(client, path).getBody(HEALTH_BODY).orElseThrow();
        return String.valueOf(body.get(STATUS_KEY));
    }

    /**
     * Performs a blocking GET, returning the response whatever its status; a {@code DOWN} health
     * endpoint answers 503 by design.
     */
    private static HttpResponse<?> response(HttpClient client, String path) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(path), HEALTH_BODY);
        } catch (HttpClientResponseException error) {
            return error.getResponse();
        }
    }

    /**
     * Reads each provider's state name from the readiness indicator bean, in emission order. Read
     * from the bean because the per-provider detail is shown only to authenticated callers.
     */
    @SuppressWarnings("unchecked")
    private static List<Map.Entry<String, String>> stateByProviderName(ApplicationContext context) {
        HealthResult result =
                Mono.from(context.getBean(IdentityProviderHealthIndicator.class).getResult())
                        .block();
        Map<String, Object> details = (Map<String, Object>) result.getDetails();
        return details.entrySet().stream()
                .map(
                        entry ->
                                Map.entry(
                                        entry.getKey(),
                                        String.valueOf(
                                                ((Map<String, Object>) entry.getValue())
                                                        .get(DETAIL_STATE_KEY))))
                .toList();
    }

    /**
     * Degenerate discovery responses a healthy deployment produces transiently; none may move the
     * entry to {@link ResolutionState#FAILED}, which only a restart clears.
     */
    private enum DegenerateDocument {
        /** A 200 carrying no body at all, which never reaches the issuer check. */
        EMPTY_BODY(OidcDiscoveryStub::serveEmptyBody),
        /** Valid JSON that simply does not say who the provider is. */
        MISSING_ISSUER(OidcDiscoveryStub::serveMetadataWithoutIssuer),
        /** A document that confirms the issuer but names no JWK Set URL. */
        NO_JWKS_URI(OidcDiscoveryStub::serveMetadataWithoutJwksUri);

        private final Consumer<OidcDiscoveryStub> stubbing;

        DegenerateDocument(Consumer<OidcDiscoveryStub> stubbing) {
            this.stubbing = stubbing;
        }

        /** Configures the stub to serve this response. */
        void serveFrom(OidcDiscoveryStub stub) {
            stubbing.accept(stub);
        }
    }

    /** Aggregate health status names asserted on, kept out of the assertions as bare strings. */
    private enum HealthStatusName {
        /** Every indicator reported healthy. */
        UP,
        /** At least one indicator reported unhealthy. */
        DOWN
    }
}
