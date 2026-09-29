package com.seamware.consentmanager.support;

import io.micronaut.test.support.TestPropertyProvider;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Testcontainers-based PostgreSQL fixture that provides a real PostgreSQL 16 database for
 * integration tests.
 *
 * <p>This class implements {@link TestPropertyProvider} so that {@code @MicronautTest} classes can
 * obtain a properly configured datasource pointing at a Testcontainers-managed PostgreSQL instance.
 *
 * <p>The container is shared across all test classes within a JVM to avoid the overhead of starting
 * a new PostgreSQL container for each test class. The shared container is started once (lazily on
 * first access) and stopped when the JVM shuts down.
 *
 * <h3>Usage</h3>
 *
 * <p>Test classes should extend this class and use {@code @MicronautTest}:
 *
 * <pre>{@code
 * @MicronautTest
 * class MyIntegrationTest extends PostgresTestResource {
 *     // ...
 * }
 * }</pre>
 */
public abstract class PostgresTestResource implements TestPropertyProvider {

    /** Docker image name for the PostgreSQL container. */
    private static final String POSTGRES_IMAGE = "postgres:16";

    /** Database name used in the test container. */
    private static final String DATABASE_NAME = "consent_manager_test";

    /** Username for the test database. */
    private static final String DATABASE_USER = "test";

    /** Password for the test database. */
    private static final String DATABASE_PASSWORD = "test";

    /**
     * Shared PostgreSQL container instance. Started once per JVM and reused across all test classes
     * that extend this resource. The {@code static} initializer block ensures the container is
     * started eagerly and the Ryuk resource reaper handles cleanup on JVM shutdown.
     */
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername(DATABASE_USER)
                    .withPassword(DATABASE_PASSWORD);

    static {
        POSTGRES.start();
    }

    /**
     * Provides datasource configuration properties pointing to the Testcontainers PostgreSQL
     * instance.
     *
     * <p>These properties override the default datasource configuration in {@code
     * application-test.yml}, allowing Micronaut's data layer and Flyway to connect to the test
     * container.
     *
     * @return a map of configuration properties for the test application context
     */
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "datasources.default.url", POSTGRES.getJdbcUrl(),
                "datasources.default.username", POSTGRES.getUsername(),
                "datasources.default.password", POSTGRES.getPassword(),
                "datasources.default.driver-class-name", "org.postgresql.Driver");
    }
}
