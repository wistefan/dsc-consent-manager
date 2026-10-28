package com.seamware.consentmanager;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.yaml.YamlPropertySourceLoader;
import io.micronaut.core.io.socket.SocketUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.MalformedURLException;
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
 * Pins where management endpoints, the Swagger UI and the API resolve, and who may read them, with
 * security enabled; the only such coverage, as every other IT runs with security off.
 */
@DisplayName("Management endpoint access under enabled security")
class ManagementEndpointSecurityIT {

    /** Prefix of the generated controllers, mirroring the {@code API_PREFIX} default. */
    private static final String API_PREFIX = "/v1";

    /** Aggregate health route, at the management port's origin root. */
    private static final String HEALTH_PATH = "/health";

    /** Health route reporting whether the process is alive, served off {@link #HEALTH_PATH}. */
    private static final String LIVENESS_PATH = HEALTH_PATH + "/liveness";

    /** Health route reporting whether this instance can serve, served off {@link #HEALTH_PATH}. */
    private static final String READINESS_PATH = HEALTH_PATH + "/readiness";

    /** Static-resource mapping serving the Swagger UI entry page. */
    private static final String SWAGGER_UI_PATH = "/swagger-ui/index.html";

    /** Static-resource mapping serving the published OpenAPI specification. */
    private static final String OPENAPI_SPEC_PATH = "/openapi.yaml";

    /** The one real API operation, declared {@code security: []} in the specification. */
    private static final String API_STATUS_PATH = "/api-status";

    /** Well-known JWKS consent receipts verify against, declared {@code security: []}. */
    private static final String RECEIPT_JWKS_PATH = "/.well-known/jwks.json";

    /** Micrometer metrics endpoint, governed by {@code endpoints.metrics.sensitive}. */
    private static final String METRICS_PATH = "/metrics";

    /** Prometheus scrape endpoint, governed by {@code endpoints.prometheus.sensitive}. */
    private static final String PROMETHEUS_PATH = "/prometheus";

    /** Micronaut's switch for whether {@link #METRICS_PATH} needs authentication. */
    private static final String METRICS_SENSITIVE_PROPERTY = "endpoints.metrics.sensitive";

    /** Micronaut's switch for whether {@link #PROMETHEUS_PATH} needs authentication. */
    private static final String PROMETHEUS_SENSITIVE_PROPERTY = "endpoints.prometheus.sensitive";

    /** Health detail visibility, which {@code application-test.yml} relaxes. */
    private static final String HEALTH_DETAILS_VISIBLE_PROPERTY =
            "endpoints.health.details-visible";

    /** Production configuration, read directly so the test environment cannot mask it. */
    private static final String BASE_CONFIGURATION = "application.yml";

    /** Health detail visibility production must ship. */
    private static final String SHIPPED_DETAILS_VISIBLE = "AUTHENTICATED";

    /** Member of a health body that carries per-indicator detail. */
    private static final String HEALTH_DETAILS_MEMBER = "\"details\"";

    /** Index of the {@code /swagger-ui/**} entry in {@code application.yml}'s intercept map. */
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

    /** Prefix of the one trust-list entry; {@code application.yml} leaves it blank. */
    private static final String PROVIDER_PREFIX = "consent-manager.identity-providers.primary.";

    /** Port 1 never listens, so readiness stays {@code DOWN} and answers 503 anonymously. */
    private static final String UNREACHABLE_PROVIDER_BASE_URL = "http://localhost:1/realms/dead";

    /** Audience the unreachable provider's entry declares; never used, but must not be blank. */
    private static final String UNREACHABLE_PROVIDER_AUDIENCE = "consent-manager";

    /** Management ports probed before giving up; each lost only to a concurrent bind. */
    private static final int MANAGEMENT_BIND_ATTEMPTS = 5;

    /** The port a probe is sent to. */
    private enum Port {
        MANAGEMENT,
        API
    }

    /** A started context with a client on each of its two ports. */
    private record Deployment(
            ApplicationContext context,
            EmbeddedServer server,
            HttpClient managementClient,
            HttpClient apiClient)
            implements AutoCloseable {

        /**
         * Starts on a probed management port, re-probing when the bind loses the port to another
         * socket in between: Micronaut binds an exposed port only as given, never at random.
         */
        static Deployment start(Map<String, Object> overrides) throws MalformedURLException {
            for (int attempt = 1; ; attempt++) {
                int managementPort = SocketUtils.findAvailableTcpPort();
                ApplicationContext context = startContext(overrides, managementPort);
                EmbeddedServer server;
                try {
                    server = context.getBean(EmbeddedServer.class).start();
                } catch (ServerStartupException e) {
                    closeQuietly(context);
                    if (!(e.getCause() instanceof BindException)
                            || attempt == MANAGEMENT_BIND_ATTEMPTS) {
                        throw e;
                    }
                    continue;
                }
                return connect(context, server, managementPort);
            }
        }

        private static Deployment connect(
                ApplicationContext context, EmbeddedServer server, int managementPort)
                throws MalformedURLException {
            return new Deployment(
                    context,
                    server,
                    HttpClient.create(URI.create("http://localhost:" + managementPort).toURL()),
                    HttpClient.create(server.getURL()));
        }

        HttpClient client(Port port) {
            return port == Port.MANAGEMENT ? managementClient : apiClient;
        }

        @Override
        public void close() {
            closeQuietly(managementClient);
            closeQuietly(apiClient);
            closeQuietly(server);
            closeQuietly(context);
        }
    }

    private static Deployment metricsAuthenticated;
    private static Deployment metricsAnonymous;

    @BeforeAll
    static void startDeployments() throws MalformedURLException {
        // No override: the endpoints keep application.yml's sensitive default.
        metricsAuthenticated = Deployment.start(Map.of());
        metricsAnonymous =
                Deployment.start(
                        Map.of(
                                METRICS_SENSITIVE_PROPERTY,
                                false,
                                PROMETHEUS_SENSITIVE_PROPERTY,
                                false));
    }

    /** Starts a security-enabled context: API under {@link #API_PREFIX}, management on its port. */
    private static ApplicationContext startContext(Map<String, Object> overrides, int port) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("micronaut.security.enabled", true);
        properties.put("consent-manager.api-prefix", API_PREFIX);
        properties.put("micronaut.server.port", -1);
        properties.put("endpoints.all.enabled", true);
        properties.put("endpoints.all.port", port);
        properties.put(
                HEALTH_DETAILS_VISIBLE_PROPERTY, shippedProperty(HEALTH_DETAILS_VISIBLE_PROPERTY));
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.put(PROVIDER_PREFIX + "issuer", UNREACHABLE_PROVIDER_BASE_URL);
        properties.put(
                PROVIDER_PREFIX + "discovery-url",
                UNREACHABLE_PROVIDER_BASE_URL + "/.well-known/openid-configuration");
        properties.put(PROVIDER_PREFIX + "audience", UNREACHABLE_PROVIDER_AUDIENCE);
        // That address is cleartext http, which the validator refuses without this opt-in.
        properties.put(PROVIDER_PREFIX + "allow-insecure-transport", true);
        properties.putAll(overrides);

        ApplicationContext started =
                ApplicationContext.builder().environments("test").properties(properties).build();
        started.start();
        return started;
    }

    /** Reads a property from {@link #BASE_CONFIGURATION} alone, without any environment overlay. */
    private static Object shippedProperty(String name) {
        try (InputStream yaml =
                ManagementEndpointSecurityIT.class
                        .getClassLoader()
                        .getResourceAsStream(BASE_CONFIGURATION)) {
            return new YamlPropertySourceLoader().read(BASE_CONFIGURATION, yaml).get(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopDeployments() {
        closeQuietly(metricsAuthenticated);
        closeQuietly(metricsAnonymous);
    }

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

    /** Each path the Compose stack probes, its port, the anonymous status expected and why. */
    private static Stream<Arguments> routingMatrix() {
        return Stream.of(
                Arguments.of(
                        Port.MANAGEMENT,
                        LIVENESS_PATH,
                        HttpStatus.OK,
                        "liveness depends on no external system, so it answers UP anonymously at"
                                + " the management port's origin root"),
                Arguments.of(
                        Port.MANAGEMENT,
                        READINESS_PATH,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "the Compose healthcheck probes readiness without credentials; with the"
                                + " only provider unreachable a 503 - not a 401 or 404 - shows the"
                                + " route resolved and was answered anonymously"),
                Arguments.of(
                        Port.MANAGEMENT,
                        API_PREFIX + LIVENESS_PATH,
                        HttpStatus.UNAUTHORIZED,
                        "management endpoints are not under the API prefix, so this path is"
                                + " unrouted and refused like any unrouted path"),
                Arguments.of(
                        Port.API,
                        LIVENESS_PATH,
                        HttpStatus.NOT_FOUND,
                        "endpoints.all.port moves management endpoints off the API port entirely"),
                Arguments.of(
                        Port.API,
                        SWAGGER_UI_PATH,
                        HttpStatus.OK,
                        "the Swagger UI sits at the origin root and stays anonymously readable"),
                Arguments.of(
                        Port.API,
                        OPENAPI_SPEC_PATH,
                        HttpStatus.OK,
                        "the published specification stays anonymously readable"),
                Arguments.of(
                        Port.API,
                        API_PREFIX + API_STATUS_PATH,
                        HttpStatus.OK,
                        "generated controllers sit under the API prefix, and the specification's"
                                + " `security: []` keeps the reachability probe open"),
                Arguments.of(
                        Port.API,
                        RECEIPT_JWKS_PATH,
                        HttpStatus.OK,
                        "the receipt JWKS sits at the origin root RFC 8615 defines and stays"
                                + " anonymously readable now that the security filter sees it"),
                Arguments.of(
                        Port.API,
                        API_PREFIX + RECEIPT_JWKS_PATH,
                        HttpStatus.UNAUTHORIZED,
                        "@OriginRoot takes the JWKS out of the API prefix rather than serving it"
                                + " twice, so under the prefix it is an unrouted path"));
    }

    @ParameterizedTest(name = "GET {1} on the {0} port answers {2}")
    @MethodSource("routingMatrix")
    @DisplayName("unauthenticated probes resolve exactly where the Compose stack expects them")
    void routesResolveWhereTheComposeStackExpectsThem(
            Port port, String path, HttpStatus expected, String reason) {
        assertThat(statusOf(metricsAuthenticated.client(port), path).getCode())
                .as(reason)
                .isEqualTo(expected.getCode());
    }

    private static Stream<Arguments> metricsMatrix() {
        return Stream.of(true, false)
                .flatMap(
                        sensitive ->
                                Stream.of(METRICS_PATH, PROMETHEUS_PATH)
                                        .flatMap(path -> metricsRows(sensitive, path)));
    }

    private static Stream<Arguments> metricsRows(boolean sensitive, String path) {
        HttpStatus onManagementPort = sensitive ? HttpStatus.UNAUTHORIZED : HttpStatus.OK;
        return Stream.of(
                Arguments.of(sensitive, Port.MANAGEMENT, path, onManagementPort),
                Arguments.of(sensitive, Port.API, path, HttpStatus.UNAUTHORIZED));
    }

    /** API-port metrics are unrouted; token reads are covered by MetricsSensitiveEndpointRuleIT. */
    @ParameterizedTest(name = "sensitive={0}: anonymous GET {2} on the {1} port answers {3}")
    @MethodSource("metricsMatrix")
    @DisplayName("anonymous metrics access follows endpoint sensitivity on the management port")
    void metricsAccessFollowsSensitivity(
            boolean sensitive, Port port, String path, HttpStatus expected) {
        Deployment deployment = sensitive ? metricsAuthenticated : metricsAnonymous;

        assertThat(statusOf(deployment.client(port), path).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    @DisplayName("production ships metrics sensitive and health details hidden from anonymous")
    void productionDefaultsAreTheSecureOnes() {
        assertThat(shippedProperty(HEALTH_DETAILS_VISIBLE_PROPERTY))
                .isEqualTo(SHIPPED_DETAILS_VISIBLE);
        assertThat(Stream.of(METRICS_SENSITIVE_PROPERTY, PROMETHEUS_SENSITIVE_PROPERTY))
                .allSatisfy(
                        property ->
                                assertThat(
                                                metricsAuthenticated
                                                        .context()
                                                        .getProperty(property, Boolean.class))
                                        .as(property)
                                        .hasValue(true));
    }

    @Test
    @DisplayName("anonymous health shows the aggregate status but no indicator details")
    void anonymousHealthHidesDetails() {
        HttpResponse<String> response =
                exchange(metricsAuthenticated.managementClient(), HEALTH_PATH);

        assertThat(response.getBody(String.class))
                .hasValueSatisfying(
                        body ->
                                assertThat(body)
                                        .contains("\"status\"")
                                        .doesNotContain(HEALTH_DETAILS_MEMBER));
    }

    @Test
    @DisplayName(
            "control: the Swagger UI is challenged once its intercept entry stops being anonymous")
    void swaggerUiIsReachableOnlyBecauseItsInterceptEntryAllowsIt() throws Exception {
        assertThat(
                        metricsAuthenticated
                                .context()
                                .getEnvironment()
                                .getProperty(SWAGGER_UI_INTERCEPT_PATTERN_PROPERTY, String.class))
                .as(
                        "the control case overrides intercept entry %d, which must still be the"
                                + " Swagger UI rule",
                        SWAGGER_UI_INTERCEPT_INDEX)
                .hasValue(SWAGGER_UI_INTERCEPT_PATTERN);

        try (Deployment restricted =
                Deployment.start(
                        Map.of(SWAGGER_UI_INTERCEPT_ACCESS_PROPERTY, AUTHENTICATED_ACCESS))) {
            assertThat(statusOf(restricted.apiClient(), SWAGGER_UI_PATH).getCode())
                    .as(
                            "the %s pattern is matched against the full path, so tightening it"
                                    + " must challenge %s",
                            SWAGGER_UI_INTERCEPT_PATTERN, SWAGGER_UI_PATH)
                    .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
        }
    }

    private static HttpStatus statusOf(HttpClient client, String path) {
        return exchange(client, path).getStatus();
    }

    /** Issues an anonymous GET, returning an error response rather than throwing it. */
    private static HttpResponse<String> exchange(HttpClient client, String path) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(path), String.class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = (HttpResponse<String>) e.getResponse();
            return response;
        }
    }
}
