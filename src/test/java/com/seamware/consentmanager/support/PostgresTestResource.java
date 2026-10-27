package com.seamware.consentmanager.support;

import io.micronaut.test.support.TestPropertyProvider;
import java.util.Map;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers PostgreSQL 16 fixture supplying the datasource to {@code @MicronautTest}
 * subclasses, which inherit the {@code PER_CLASS} lifecycle {@link TestPropertyProvider} needs.
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
}
