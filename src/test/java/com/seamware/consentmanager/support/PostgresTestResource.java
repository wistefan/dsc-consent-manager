package com.seamware.consentmanager.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers PostgreSQL 16 fixture supplying the datasource to {@code @MicronautTest}
 * subclasses, which inherit the {@code PER_CLASS} lifecycle {@link TestPropertyProvider} needs.
 * Every subclass test method ends with {@link DatabaseReset}, so no row outlives the method, and
 * with a check that every response it received conformed to the spec.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PostgresTestResource implements TestPropertyProvider {

    /** The PostgreSQL image every IT runs against, shared so a version bump moves them together. */
    public static final String POSTGRES_IMAGE = "postgres:16";

    private static final String DATABASE_NAME = "consent_manager_test";

    private static final String DATABASE_USER = "test";

    private static final String DATABASE_PASSWORD = "test";

    /** Shared container, started once per JVM; Ryuk reaps it on shutdown. */
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername(DATABASE_USER)
                    .withPassword(DATABASE_PASSWORD);

    static {
        POSTGRES.start();
    }

    /** Datasource properties for the shared container, overriding {@code application-test.yml}. */
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "datasources.default.url", POSTGRES.getJdbcUrl(),
                "datasources.default.username", POSTGRES.getUsername(),
                "datasources.default.password", POSTGRES.getPassword(),
                "datasources.default.driver-class-name", "org.postgresql.Driver");
    }

    /** Filled by {@link OpenApiResponseValidationFilter}; every subclass is a Micronaut test. */
    @Inject protected ResponseViolations responseViolations;

    /** Fails a test whose responses broke the spec; final so no subclass can switch it off. */
    @AfterEach
    protected final void assertResponsesMatchSpec() {
        assertThat(responseViolations)
                .as("response collector; is the subclass missing @MicronautTest?")
                .isNotNull();
        assertThat(responseViolations.drain()).as("responses violating the spec").isEmpty();
    }

    /** Empties the schema after every test method; final so no subclass can switch it off. */
    @AfterEach
    protected final void resetSharedSchema() {
        DatabaseReset.reset();
    }

    /** Opens a connection to the shared container outside any Micronaut context. */
    static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
