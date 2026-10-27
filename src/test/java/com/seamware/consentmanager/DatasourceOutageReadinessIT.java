package com.seamware.consentmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A database outage drains the instance rather than restarting it: readiness reports the {@code
 * jdbc} component {@code DOWN} with 503 while liveness stays 200 {@code UP}.
 *
 * <p>Runs over its own container, never {@code PostgresTestResource}'s JVM-wide one, because it
 * stops the database. Asserts the {@code jdbc} component rather than the top-level readiness, which
 * the test profile's unreachable identity provider already holds {@code DOWN}.
 */
@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Datasource outage flips readiness, not liveness")
class DatasourceOutageReadinessIT implements TestPropertyProvider {

    private static final String READINESS_PATH = "/health/readiness";

    private static final String LIVENESS_PATH = "/health/liveness";

    private static final String JDBC_COMPONENT = "jdbc";

    private static final String UP = "UP";

    private static final String DOWN = "DOWN";

    /** Hikari's wait for a connection; short so a probe during the outage answers promptly. */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(2);

    /** Longest the outage may take to surface: a few connection timeouts. */
    private static final Duration OUTAGE_DETECTION_TIMEOUT = Duration.ofSeconds(30);

    private static final Argument<Map<String, Object>> MAP_TYPE =
            Argument.mapOf(String.class, Object.class);

    @SuppressWarnings("resource") // closed in stopDatabase()
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(PostgresTestResource.POSTGRES_IMAGE);

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Override
    public Map<String, String> getProperties() {
        postgres.start();
        return Map.of(
                "datasources.default.url",
                postgres.getJdbcUrl(),
                "datasources.default.username",
                postgres.getUsername(),
                "datasources.default.password",
                postgres.getPassword(),
                "datasources.default.driver-class-name",
                "org.postgresql.Driver",
                "datasources.default.connection-timeout",
                String.valueOf(CONNECTION_TIMEOUT.toMillis()));
    }

    @AfterAll
    void stopDatabase() {
        postgres.close();
    }

    @Test
    @DisplayName("readiness reports jdbc DOWN with 503 while liveness stays UP")
    void outageDrainsWithoutRestart() {
        assertThat(jdbcStatus(get(READINESS_PATH))).as("jdbc before the outage").isEqualTo(UP);
        assertLivenessUp();

        postgres.stop();

        Await.until(
                "readiness to report the jdbc component DOWN",
                OUTAGE_DETECTION_TIMEOUT,
                () -> DOWN.equals(jdbcStatus(get(READINESS_PATH))));
        HealthReport readiness = get(READINESS_PATH);
        assertThat((Object) readiness.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readiness.body()).containsEntry("status", DOWN);
        assertLivenessUp();
    }

    private void assertLivenessUp() {
        HealthReport liveness = get(LIVENESS_PATH);
        assertThat((Object) liveness.status()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.body()).containsEntry("status", UP);
    }

    /** Issues an anonymous GET, reading a 503's body rather than throwing it. */
    private HealthReport get(String path) {
        try {
            HttpResponse<Map<String, Object>> response =
                    httpClient.toBlocking().exchange(HttpRequest.GET(path), MAP_TYPE);
            return new HealthReport(response.getStatus(), response.body());
        } catch (HttpClientResponseException e) {
            return new HealthReport(e.getStatus(), e.getResponse().getBody(MAP_TYPE).orElseThrow());
        }
    }

    /** One health response: its HTTP status and decoded body. */
    private record HealthReport(HttpStatus status, Map<String, Object> body) {}

    @SuppressWarnings("unchecked") // the health report is a tree of nested maps
    private static Object jdbcStatus(HealthReport report) {
        Map<String, Object> details = (Map<String, Object>) report.body().get("details");
        Map<String, Object> jdbc = (Map<String, Object>) details.get(JDBC_COMPONENT);
        return jdbc.get("status");
    }
}
