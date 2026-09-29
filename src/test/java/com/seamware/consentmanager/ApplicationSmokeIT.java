package com.seamware.consentmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

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
 *   <li>Health endpoint returns HTTP 200 with {@code UP} status
 *   <li>Datasource health indicator reports {@code UP}
 *   <li>Flyway has executed (schema history table exists)
 *   <li>DataSource bean is injectable and functional
 * </ul>
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Application Smoke Test")
class ApplicationSmokeIT extends PostgresTestResource {

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

    @Test
    @DisplayName("Health endpoint returns 200 with UP status")
    @SuppressWarnings("unchecked")
    void healthEndpointReturnsUp() {
        HttpResponse<Map> response =
                httpClient.toBlocking().exchange(HttpRequest.GET("/health"), Map.class);

        assertThat((Object) response.status())
                .as("Health endpoint should return HTTP 200")
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> body = response.body();
        assertThat(body).as("Health response body should not be null").isNotNull();
        assertThat(body.get("status")).as("Health status should be UP").isEqualTo("UP");
    }

    @Test
    @DisplayName("Datasource health indicator reports UP")
    @SuppressWarnings("unchecked")
    void datasourceHealthIndicatorReportsUp() {
        Map<String, Object> body =
                httpClient.toBlocking().retrieve(HttpRequest.GET("/health"), Map.class);

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
