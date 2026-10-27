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
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: the context starts against real PostgreSQL, Flyway has run, and {@code /health}
 * reports {@code UP} once discovery has resolved the configured identity provider.
 */
@MicronautTest(transactional = false)
@DisplayName("Application Smoke Test")
class ApplicationSmokeIT extends PostgresTestResource {

    /** Answering provider; the test default points at a dead port, which holds readiness DOWN. */
    private static final OidcDiscoveryStub IDENTITY_PROVIDER = new OidcDiscoveryStub();

    /** Longest the health assertions wait for discovery, which runs off the startup thread. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(30);

    /** Adds the coordinates of {@link #IDENTITY_PROVIDER} to the shared datasource properties. */
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

    @AfterEach
    void resetIdentityProvider() {
        IDENTITY_PROVIDER.reset();
    }

    @AfterAll
    static void stopIdentityProvider() {
        IDENTITY_PROVIDER.close();
    }

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject ApplicationContext applicationContext;

    @Inject DataSource dataSource;

    @Inject DataSourceResolver dataSources;

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

        try (var connection = connect()) {
            assertThat(connection.isValid(5)).as("DataSource connection should be valid").isTrue();
        }
    }

    /** A pooled connection; the transaction-aware proxy refuses one outside a transaction. */
    private Connection connect() throws SQLException {
        return dataSources.resolve(dataSource).getConnection();
    }

    private static final Argument<Map<String, Object>> MAP_TYPE =
            Argument.mapOf(String.class, Object.class);

    /** Waits for {@code UP}; discovery runs after startup, so health is briefly {@code DOWN}. */
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

        Object details = body.get("details");
        assertThat(details)
                .as("Health details should be present (details-visible: ANONYMOUS)")
                .isNotNull()
                .isInstanceOf(Map.class);

        Map<String, Object> detailsMap = (Map<String, Object>) details;

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
        try (var connection = connect();
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
