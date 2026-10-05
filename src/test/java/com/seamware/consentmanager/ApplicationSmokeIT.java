package com.seamware.consentmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke integration test that verifies the application context starts correctly and can connect to
 * a real PostgreSQL database via Testcontainers.
 *
 * <p>This test runs as part of the Failsafe integration test phase ({@code ./mvnw verify}) because
 * it follows the {@code *IT.java} naming convention. It uses a shared Testcontainers PostgreSQL
 * instance provided by {@link PostgresTestResource}.
 *
 * <p>Verifications performed:
 *
 * <ul>
 *   <li>Application context starts successfully
 *   <li>Health endpoint returns HTTP 200 with {@code UP} status once OpenID Connect discovery has
 *       resolved the configured identity provider
 *   <li>Datasource health indicator reports {@code UP}
 *   <li>Flyway has executed (schema history table exists)
 *   <li>DataSource bean is injectable and functional
 * </ul>
 */
@MicronautTest
@DisplayName("Application Smoke Test")
class ApplicationSmokeIT extends PostgresTestResource {

    /**
     * Stub identity provider the trust list points at.
     *
     * <p>{@code application-test.yml} points the trust list at a port nothing listens on, which is
     * right for tests that only need a context to start - but it would hold readiness, and with it
     * the aggregate {@code /health}, permanently {@code DOWN}. This smoke test asserts the shape a
     * deployment actually runs in, where every configured provider does resolve, so it supplies a
     * provider that answers.
     */
    private static final OidcDiscoveryStub IDENTITY_PROVIDER = new OidcDiscoveryStub();

    /** Longest the health assertions wait for discovery, which runs off the startup thread. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Points the configured trust-list entry at {@link #IDENTITY_PROVIDER}.
     *
     * @return the datasource properties of the shared container, plus this provider's coordinates
     */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put(
                "consent-manager.identity-providers.primary.issuer", IDENTITY_PROVIDER.issuer());
        properties.put(
                "consent-manager.identity-providers.primary.discovery-url",
                IDENTITY_PROVIDER.discoveryUrl());
        return properties;
    }

    /** Stops the stub provider once every test in this class has run. */
    @AfterAll
    static void stopIdentityProvider() {
        IDENTITY_PROVIDER.close();
    }

    /** HTTP client targeting the embedded test server. */
    @Inject
    @Client("/")
    HttpClient httpClient;

    /** The Micronaut application context. */
    @Inject ApplicationContext applicationContext;

    /** The datasource bean, expected to be connected to the Testcontainers PostgreSQL instance. */
    @Inject DataSource dataSource;

    @Test
    @DisplayName("Application context starts successfully")
    void applicationContextStarts() {
        assertThat(applicationContext.isRunning())
                .as("Application context should be running")
                .isTrue();
    }

    @Test
    @DisplayName("DataSource bean is available and connected")
    void dataSourceIsAvailable() throws Exception {
        assertThat(dataSource).as("DataSource bean should be injectable").isNotNull();

        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(5)).as("DataSource connection should be valid").isTrue();
        }
    }

    /** Reusable type argument for {@code Map<String, Object>} to avoid raw types. */
    private static final Argument<Map<String, Object>> MAP_TYPE =
            Argument.mapOf(String.class, Object.class);

    /**
     * Waits for the aggregate health endpoint to report {@code UP}.
     *
     * <p>Readiness covers the identity provider registry, whose discovery deliberately runs after
     * the context is up, so the aggregate is {@code DOWN} for a moment after startup.
     */
    private void awaitHealthUp() {
        Await.until(
                "the aggregate health endpoint to report UP once discovery has resolved",
                DISCOVERY_TIMEOUT,
                () -> {
                    try {
                        Map<String, Object> body =
                                httpClient
                                        .toBlocking()
                                        .retrieve(HttpRequest.GET("/health"), MAP_TYPE);
                        return "UP".equals(body.get("status"));
                    } catch (RuntimeException notYetHealthy) {
                        return false;
                    }
                });
    }

    @Test
    @DisplayName("Health endpoint returns 200 with UP status")
    void healthEndpointReturnsUp() {
        awaitHealthUp();

        HttpResponse<Map<String, Object>> response =
                httpClient.toBlocking().exchange(HttpRequest.GET("/health"), MAP_TYPE);

        assertThat((Object) response.status())
                .as("Health endpoint should return HTTP 200")
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> body = response.body();
        assertThat(body).as("Health response body should not be null").isNotNull();
        assertThat(body.get("status")).as("Health status should be UP").isEqualTo("UP");
    }

    @Test
    @DisplayName("Datasource health indicator reports UP")
    @SuppressWarnings("unchecked") // Micronaut returns nested maps; inner casts are unavoidable
    void datasourceHealthIndicatorReportsUp() {
        awaitHealthUp();

        Map<String, Object> body =
                httpClient.toBlocking().retrieve(HttpRequest.GET("/health"), MAP_TYPE);

        assertThat(body).as("Health response body should not be null").isNotNull();

        // The health details contain individual indicator statuses.
        // With details-visible: ANONYMOUS, the response includes a "details" map.
        Object details = body.get("details");
        assertThat(details)
                .as("Health details should be present (details-visible: ANONYMOUS)")
                .isNotNull()
                .isInstanceOf(Map.class);

        Map<String, Object> detailsMap = (Map<String, Object>) details;

        // The JDBC health indicator key is "jdbc" in Micronaut
        assertThat(detailsMap)
                .as("Health details should contain a JDBC health indicator")
                .containsKey("jdbc");

        Map<String, Object> jdbcDetails = (Map<String, Object>) detailsMap.get("jdbc");
        assertThat(jdbcDetails.get("status"))
                .as("JDBC health indicator status should be UP")
                .isEqualTo("UP");
    }

    @Test
    @DisplayName("Flyway has executed and created the schema history table")
    void flywaySchemaHistoryTableExists() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var resultSet =
                        statement.executeQuery(
                                "SELECT EXISTS ("
                                        + "SELECT FROM information_schema.tables "
                                        + "WHERE table_name = 'flyway_schema_history'"
                                        + ")")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getBoolean(1))
                    .as("Flyway schema history table should exist after startup")
                    .isTrue();
        }
    }
}
