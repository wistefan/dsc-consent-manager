package com.seamware.consentmanager;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins where the health endpoint actually lives, and that it stays anonymous, once security is
 * enabled.
 *
 * <p>The dev profile now starts with {@code micronaut.security.enabled: true}, which puts every
 * route behind the security filter for the first time. That makes two previously untested
 * assumptions of the Docker Compose stack load-bearing, and this test settles both empirically
 * rather than by reading the framework:
 *
 * <ul>
 *   <li><strong>{@code endpoints.all.port} moves management endpoints to their own port but does
 *       <em>not</em> take them out of {@code micronaut.server.context-path}.</strong> With a
 *       context path of {@code /v1} the health endpoint is at {@code :${MANAGEMENT_PORT}/v1/health}
 *       and the bare {@code :${MANAGEMENT_PORT}/health} is a 404 - the same holds for the {@code
 *       /health/liveness} and {@code /health/readiness} routes served off it. The Compose
 *       healthcheck must therefore carry {@code ${API_PREFIX}}; an earlier comment in {@code
 *       application.yml} claimed the opposite and was wrong.
 *   <li><strong>{@code intercept-url-map} patterns are matched against the path with the context
 *       path already stripped.</strong> {@code /swagger-ui/**} therefore still covers {@code
 *       /v1/swagger-ui/**} once a context path is configured. That is asserted rather than assumed:
 *       the control case {@link #swaggerUiIsReachableOnlyBecauseItsInterceptEntryAllowsIt()} flips
 *       that single entry to {@code isAuthenticated()} and requires the same anonymous request to
 *       be challenged, which fails if the pattern were silently matching nothing.
 * </ul>
 *
 * <p>Every other integration test runs with {@code micronaut.security.enabled: false} and a root
 * context path, so none of this is covered anywhere else. This context deliberately sets a non-root
 * context path and a separate management port so that a regression in either is visible.
 */
@DisplayName("Management endpoint access under enabled security")
class ManagementEndpointSecurityIT {

    /** Context path applied to the API, mirroring the {@code API_PREFIX} default. */
    private static final String API_CONTEXT_PATH = "/v1";

    /** Path the health endpoint is mapped to, before the context path is prepended. */
    private static final String HEALTH_PATH = "/health";

    /** Health route reporting whether the process is alive, served off {@link #HEALTH_PATH}. */
    private static final String LIVENESS_PATH = HEALTH_PATH + "/liveness";

    /** Health route reporting whether this instance can serve, served off {@link #HEALTH_PATH}. */
    private static final String READINESS_PATH = HEALTH_PATH + "/readiness";

    /** Static-resource mapping serving the Swagger UI entry page. */
    private static final String SWAGGER_UI_PATH = "/swagger-ui/index.html";

    /** Static-resource mapping serving the published OpenAPI specification. */
    private static final String OPENAPI_SPEC_PATH = "/static/openapi.yaml";

    /** The one real API operation, declared {@code security: []} in the specification. */
    private static final String API_STATUS_PATH = "/api-status";

    /** Metrics endpoint, protected only by its {@code intercept-url-map} entry in dev. */
    private static final String METRICS_PATH = "/metrics";

    /** Prometheus scrape endpoint, protected only by its {@code intercept-url-map} entry in dev. */
    private static final String PROMETHEUS_PATH = "/prometheus";

    /** Selects the client bound to the dedicated management port. */
    private static final String MANAGEMENT = "management port";

    /** Selects the client bound to the API port. */
    private static final String API = "API port";

    /**
     * Index of the {@code /swagger-ui/**} entry in {@code application.yml}'s {@code
     * intercept-url-map}. The control case overrides exactly this entry; {@link
     * #SWAGGER_UI_INTERCEPT_PATTERN_PROPERTY} is read first so a reordering of that list fails the
     * test with a clear message instead of quietly overriding the wrong rule.
     */
    private static final int SWAGGER_UI_INTERCEPT_INDEX = 2;

    /** Property holding the pattern of the intercept entry the control case overrides. */
    private static final String SWAGGER_UI_INTERCEPT_PATTERN_PROPERTY =
            "micronaut.security.intercept-url-map[" + SWAGGER_UI_INTERCEPT_INDEX + "].pattern";

    /** Property holding the access expression of that same intercept entry. */
    private static final String SWAGGER_UI_INTERCEPT_ACCESS_PROPERTY =
            "micronaut.security.intercept-url-map[" + SWAGGER_UI_INTERCEPT_INDEX + "].access[0]";

    /** Pattern the entry at {@link #SWAGGER_UI_INTERCEPT_INDEX} is expected to carry. */
    private static final String SWAGGER_UI_INTERCEPT_PATTERN = "/swagger-ui/**";

    /**
     * Index of the {@code /metrics/**} entry in {@code application.yml}'s {@code
     * intercept-url-map}. Read back before the control case relaxes it, so a reordering of that
     * list fails with a clear message instead of quietly relaxing the wrong rule.
     */
    private static final int METRICS_INTERCEPT_INDEX = 4;

    /** Property holding the pattern of the metrics intercept entry. */
    private static final String METRICS_INTERCEPT_PATTERN_PROPERTY =
            "micronaut.security.intercept-url-map[" + METRICS_INTERCEPT_INDEX + "].pattern";

    /** Property holding the access expression of the metrics intercept entry. */
    private static final String METRICS_INTERCEPT_ACCESS_PROPERTY =
            "micronaut.security.intercept-url-map[" + METRICS_INTERCEPT_INDEX + "].access[0]";

    /** Pattern the entry at {@link #METRICS_INTERCEPT_INDEX} is expected to carry. */
    private static final String METRICS_INTERCEPT_PATTERN = "/metrics/**";

    /** Access expression the control case substitutes for {@code isAuthenticated()}. */
    private static final String ANONYMOUS_ACCESS = "isAnonymous()";

    /** Access expression the control case substitutes for {@code isAnonymous()}. */
    private static final String AUTHENTICATED_ACCESS = "isAuthenticated()";

    private static int managementPort;
    private static ApplicationContext context;
    private static EmbeddedServer server;
    private static HttpClient managementClient;
    private static HttpClient apiClient;

    /**
     * Starts a security-enabled context with the API behind a context path and management endpoints
     * on their own port.
     *
     * @throws Exception if the management URL cannot be built
     */
    @BeforeAll
    static void startContext() throws Exception {
        managementPort = SocketUtils.findAvailableTcpPort();

        context = start(Map.of(), managementPort);
        server = context.getBean(EmbeddedServer.class).start();
        managementClient =
                HttpClient.create(URI.create("http://localhost:" + managementPort).toURL());
        apiClient = HttpClient.create(server.getURL());
    }

    /**
     * Starts a security-enabled context with the API behind a context path and management endpoints
     * on their own port.
     *
     * @param overrides properties layered on top of the shared base, for control cases
     * @param port the port management endpoints are bound to
     * @return the started context
     */
    private static ApplicationContext start(Map<String, Object> overrides, int port) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("micronaut.security.enabled", true);
        properties.put("micronaut.server.context-path", API_CONTEXT_PATH);
        properties.put("micronaut.server.port", -1);
        properties.put("endpoints.all.enabled", true);
        properties.put("endpoints.all.port", port);
        // Mirror application-dev.yml, which waives Micronaut's own endpoint-level
        // `sensitive` check for these two. That leaves the intercept-url-map entries as
        // the only thing standing between an anonymous caller and the metrics, so the
        // 401 rows below fail if the security filter is not applied on this listener —
        // and fail if the patterns do not cover the paths actually served.
        properties.put("endpoints.metrics.sensitive", false);
        properties.put("endpoints.prometheus.sensitive", false);
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.putAll(overrides);

        ApplicationContext started =
                ApplicationContext.builder().environments("test").properties(properties).build();
        started.start();
        return started;
    }

    /** Releases the clients, the server and the context. */
    @AfterAll
    static void stopContext() {
        closeQuietly(managementClient);
        closeQuietly(apiClient);
        closeQuietly(server);
        closeQuietly(context);
    }

    /**
     * Closes a resource, ignoring failures so one bad close cannot mask a test result.
     *
     * @param closeable the resource to close, may be {@code null}
     */
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Nothing useful to do while tearing down.
        }
    }

    /**
     * Supplies the (port, path, expected status) matrix that fixes the routing contract.
     *
     * @return one argument row per probe
     */
    private static Stream<Arguments> routingMatrix() {
        return Stream.of(
                Arguments.of(
                        MANAGEMENT,
                        API_CONTEXT_PATH + LIVENESS_PATH,
                        HttpStatus.OK,
                        "liveness carries no indicator that depends on an external system, so it"
                                + " answers UP anonymously on the management port under the API"
                                + " prefix even though this context's trust list points at a dead"
                                + " port"),
                Arguments.of(
                        MANAGEMENT,
                        API_CONTEXT_PATH + READINESS_PATH,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "the Compose healthcheck probes readiness on the management port under the"
                                + " API prefix, without credentials; this context's identity"
                                + " provider never resolves, and a 503 - rather than the 401 a"
                                + " protected route would give or the 404 a missing one would -"
                                + " shows the route resolved and was answered anonymously"),
                Arguments.of(
                        MANAGEMENT,
                        LIVENESS_PATH,
                        HttpStatus.NOT_FOUND,
                        "a dedicated management port does not remove the server context path, so a"
                                + " probe omitting the API prefix never reaches the endpoint"),
                Arguments.of(
                        API,
                        API_CONTEXT_PATH + LIVENESS_PATH,
                        HttpStatus.NOT_FOUND,
                        "endpoints.all.port moves management endpoints off the API port entirely"),
                Arguments.of(
                        API,
                        API_CONTEXT_PATH + SWAGGER_UI_PATH,
                        HttpStatus.OK,
                        "the Swagger UI stays anonymously readable with security enabled"),
                Arguments.of(
                        API,
                        API_CONTEXT_PATH + OPENAPI_SPEC_PATH,
                        HttpStatus.OK,
                        "the published specification stays anonymously readable with security"
                                + " enabled"),
                Arguments.of(
                        API,
                        API_CONTEXT_PATH + API_STATUS_PATH,
                        HttpStatus.OK,
                        "the generated controller carries @Secured(IS_ANONYMOUS) for the"
                                + " specification's `security: []`, so the reachability probe stays"
                                + " open once the filter is enabled"),
                Arguments.of(
                        MANAGEMENT,
                        API_CONTEXT_PATH + METRICS_PATH,
                        HttpStatus.UNAUTHORIZED,
                        "the security filter applies on the dedicated management listener, so"
                                + " metrics are not anonymously readable even with Micronaut's own"
                                + " endpoint-level check waived"),
                Arguments.of(
                        MANAGEMENT,
                        API_CONTEXT_PATH + PROMETHEUS_PATH,
                        HttpStatus.UNAUTHORIZED,
                        "the security filter applies on the dedicated management listener, so the"
                                + " scrape endpoint is not anonymously readable even with"
                                + " Micronaut's own endpoint-level check waived"));
    }

    @ParameterizedTest(name = "GET {1} on the {0} answers {2}")
    @MethodSource("routingMatrix")
    @DisplayName("unauthenticated probes resolve exactly where the Compose stack expects them")
    void routesResolveWhereTheComposeStackExpectsThem(
            String port, String path, HttpStatus expected, String reason) {
        HttpClient client = MANAGEMENT.equals(port) ? managementClient : apiClient;

        assertThat(statusOf(client, path).getCode()).as(reason).isEqualTo(expected.getCode());
    }

    @Test
    @DisplayName(
            "control: the Swagger UI is challenged once its intercept entry stops being anonymous")
    void swaggerUiIsReachableOnlyBecauseItsInterceptEntryAllowsIt() throws Exception {
        assertThat(
                        context.getEnvironment()
                                .getProperty(SWAGGER_UI_INTERCEPT_PATTERN_PROPERTY, String.class))
                .as(
                        "the control case overrides intercept entry %d, which must still be the"
                                + " Swagger UI rule",
                        SWAGGER_UI_INTERCEPT_INDEX)
                .hasValue(SWAGGER_UI_INTERCEPT_PATTERN);

        int port = SocketUtils.findAvailableTcpPort();
        try (ApplicationContext restricted =
                start(Map.of(SWAGGER_UI_INTERCEPT_ACCESS_PROPERTY, AUTHENTICATED_ACCESS), port)) {
            EmbeddedServer restrictedServer = restricted.getBean(EmbeddedServer.class).start();
            try (HttpClient client = HttpClient.create(restrictedServer.getURL())) {
                assertThat(statusOf(client, API_CONTEXT_PATH + SWAGGER_UI_PATH).getCode())
                        .as(
                                "the %s pattern is matched with the context path stripped, so"
                                        + " tightening it must challenge %s",
                                SWAGGER_UI_INTERCEPT_PATTERN, API_CONTEXT_PATH + SWAGGER_UI_PATH)
                        .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
            } finally {
                closeQuietly(restrictedServer);
            }
        }
    }

    /**
     * Shows that the metrics 401 is produced by the {@code /metrics/**} intercept entry, and not by
     * a rule that would protect the path whatever that entry said.
     *
     * <p>The matrix above proves metrics are not anonymously readable, but a 401 alone cannot say
     * which rule refused: Micronaut also rejects a request that no rule matched at all. That
     * distinction is load-bearing for the dev profile, which waives {@code sensitive} on these two
     * endpoints and is therefore relying on the intercept entry specifically. Relaxing exactly that
     * entry to {@code isAnonymous()} must make the same request succeed — if the pattern did not
     * cover the served path, the response would stay a 401 and this test fails.
     *
     * @throws Exception if the management URL cannot be built
     */
    @Test
    @DisplayName("control: metrics become readable once their intercept entry turns anonymous")
    void metricsAreRefusedByTheirInterceptEntry() throws Exception {
        assertThat(
                        context.getEnvironment()
                                .getProperty(METRICS_INTERCEPT_PATTERN_PROPERTY, String.class))
                .as(
                        "the control case relaxes intercept entry %d, which must still be the"
                                + " metrics rule",
                        METRICS_INTERCEPT_INDEX)
                .hasValue(METRICS_INTERCEPT_PATTERN);

        int port = SocketUtils.findAvailableTcpPort();
        try (ApplicationContext relaxed =
                start(Map.of(METRICS_INTERCEPT_ACCESS_PROPERTY, ANONYMOUS_ACCESS), port)) {
            relaxed.getBean(EmbeddedServer.class).start();
            try (HttpClient client =
                    HttpClient.create(URI.create("http://localhost:" + port).toURL())) {
                assertThat(statusOf(client, API_CONTEXT_PATH + METRICS_PATH).getCode())
                        .as(
                                "the %s entry is what refuses %s, so relaxing it must let the same"
                                        + " anonymous request through",
                                METRICS_INTERCEPT_PATTERN, API_CONTEXT_PATH + METRICS_PATH)
                        .isEqualTo(HttpStatus.OK.getCode());
            }
        }
    }

    /**
     * Issues an anonymous GET and reports the status, treating an error response as a result rather
     * than a failure.
     *
     * @param client the client to issue the request with
     * @param path the path to request
     * @return the status the server answered with
     */
    private static HttpStatus statusOf(HttpClient client, String path) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(path), String.class).getStatus();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }
}
