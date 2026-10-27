package com.seamware.consentmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.PostgresTestResource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Replicas starting together migrate one database safely: Flyway's PostgreSQL advisory lock lets
 * exactly one apply each version while the others wait and find nothing pending.
 *
 * <p>Plain JUnit over its own container, so each replica count migrates a fresh database.
 */
@DisplayName("Concurrent Flyway migrations apply each version exactly once")
class FlywayConcurrentMigrationIT {

    /** The location Micronaut Flyway reads by default, so the test migrates what the app does. */
    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final int TWO_REPLICAS = 2;

    private static final int FOUR_REPLICAS = 4;

    private static final Duration MIGRATION_TIMEOUT = Duration.ofMinutes(2);

    private static final String DATABASE_PREFIX = "concurrent_migration_";

    private static final String HISTORY_QUERY =
            "SELECT version, count(*) AS runs, bool_and(success) AS succeeded"
                    + " FROM flyway_schema_history WHERE version IS NOT NULL GROUP BY version";

    @SuppressWarnings("resource") // closed in stopDatabase()
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(PostgresTestResource.POSTGRES_IMAGE);

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @ParameterizedTest(name = "{0} replicas")
    @ValueSource(ints = {TWO_REPLICAS, FOUR_REPLICAS})
    @DisplayName("every version is recorded once and successfully, and no replica fails")
    void concurrentReplicasApplyEachVersionOnce(int replicas) throws Exception {
        String url = createDatabase(DATABASE_PREFIX + replicas);
        Set<String> expectedVersions =
                Arrays.stream(flyway(url).info().pending())
                        .map(MigrationInfo::getVersion)
                        .map(Object::toString)
                        .collect(Collectors.toSet());
        assertThat(expectedVersions).as("resolved migrations").isNotEmpty();

        List<MigrateResult> results = migrateConcurrently(url, replicas);

        assertThat(results).hasSize(replicas).allMatch(result -> result.success);
        assertThat(results.stream().mapToInt(result -> result.migrationsExecuted).sum())
                .as("migrations executed across all replicas")
                .isEqualTo(expectedVersions.size());

        Map<String, Integer> runsByVersion = new HashMap<>();
        Map<String, Boolean> successByVersion = new HashMap<>();
        try (Connection connection = connect(url);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(HISTORY_QUERY)) {
            while (rows.next()) {
                runsByVersion.put(rows.getString("version"), rows.getInt("runs"));
                successByVersion.put(rows.getString("version"), rows.getBoolean("succeeded"));
            }
        }
        assertThat(runsByVersion.keySet()).containsExactlyInAnyOrderElementsOf(expectedVersions);
        assertThat(runsByVersion).allSatisfy((version, runs) -> assertThat(runs).isOne());
        assertThat(successByVersion).allSatisfy((version, ok) -> assertThat(ok).isTrue());
    }

    /** Runs one migration per replica, all released together; a failed run fails the test. */
    private static List<MigrateResult> migrateConcurrently(String url, int replicas)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(replicas);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MigrateResult>> runs =
                    IntStream.range(0, replicas)
                            .mapToObj(
                                    replica ->
                                            pool.submit(
                                                    () -> {
                                                        start.await();
                                                        return flyway(url).migrate();
                                                    }))
                            .toList();
            start.countDown();
            List<MigrateResult> results = new ArrayList<>();
            for (Future<MigrateResult> run : runs) {
                results.add(run.get(MIGRATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Flyway flyway(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(MIGRATION_LOCATION)
                .load();
    }

    private static String createDatabase(String name) throws SQLException {
        try (Connection connection = connect(POSTGRES.getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return "jdbc:postgresql://%s:%d/%s"
                .formatted(
                        POSTGRES.getHost(),
                        POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                        name);
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
