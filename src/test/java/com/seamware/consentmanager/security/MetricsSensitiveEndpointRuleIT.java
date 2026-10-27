package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins that a real token opens the sensitive metrics endpoints and no other sensitive one. */
@MicronautTest(transactional = false)
@DisplayName("Sensitive endpoint access with a bearer token")
class MetricsSensitiveEndpointRuleIT extends KeycloakAndPostgresTestResource {

    private static final String METRICS_PATH = "/metrics";

    private static final String PROMETHEUS_PATH = "/prometheus";

    /** A sensitive endpoint outside the metrics pair, which a token must not open. */
    private static final String BEANS_PATH = "/beans";

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put("endpoints.beans.enabled", "true");
        properties.put("endpoints.beans.sensitive", "true");
        return properties;
    }

    @BeforeEach
    void awaitTheRealm() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
    }

    private static Stream<Arguments> accessMatrix() {
        Stream<Arguments> metrics =
                Stream.of(METRICS_PATH, PROMETHEUS_PATH)
                        .flatMap(
                                path ->
                                        Stream.of(
                                                Arguments.of(
                                                        path,
                                                        Optional.empty(),
                                                        HttpStatus.UNAUTHORIZED),
                                                Arguments.of(
                                                        path,
                                                        Optional.of(RealmPrincipal.USER),
                                                        HttpStatus.OK),
                                                Arguments.of(
                                                        path,
                                                        Optional.of(RealmPrincipal.CATALOG),
                                                        HttpStatus.OK)));
        return Stream.concat(
                metrics,
                Stream.of(
                        Arguments.of(BEANS_PATH, Optional.empty(), HttpStatus.UNAUTHORIZED),
                        Arguments.of(
                                BEANS_PATH,
                                Optional.of(RealmPrincipal.USER),
                                HttpStatus.FORBIDDEN)));
    }

    @ParameterizedTest(name = "GET {0} as {1} answers {2}")
    @MethodSource("accessMatrix")
    @DisplayName("any token reads metrics; other sensitive endpoints stay refused")
    void tokenOpensOnlyTheMetricsEndpoints(
            String path, Optional<RealmPrincipal> caller, HttpStatus expected) {
        MutableHttpRequest<?> request = HttpRequest.GET(path);
        caller.map(KeycloakTestResource::accessToken).ifPresent(request::bearerAuth);

        assertThat(statusOf(request).getCode()).isEqualTo(expected.getCode());
    }

    private HttpStatus statusOf(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class).getStatus();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }
}
