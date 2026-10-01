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
 *       and the bare {@code :${MANAGEMENT_PORT}/health} is a 404. The Compose healthcheck must
 *       therefore carry {@code ${API_PREFIX}}; an earlier comment in {@code application.yml}
 *       claimed the opposite and was wrong.
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

    /** Static-resource mapping serving the Swagger UI entry page. */
    private static final String SWAGGER_UI_PATH = "/swagger-ui/index.html";

    /** Static-resource mapping serving the published OpenAPI specification. */
    private static final String OPENAPI_SPEC_PATH = "/static/openapi.yaml";

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
                        API_CONTEXT_PATH + HEALTH_PATH,
                        HttpStatus.OK,
                        "the Compose healthcheck probes the management port under the API prefix,"
                                + " without credentials"),
                Arguments.of(
                        MANAGEMENT,
                        HEALTH_PATH,
                        HttpStatus.NOT_FOUND,
                        "a dedicated management port does not remove the server context path, so a"
                                + " probe omitting the API prefix never reaches the endpoint"),
                Arguments.of(
                        API,
                        API_CONTEXT_PATH + HEALTH_PATH,
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
                                + " enabled"));
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
