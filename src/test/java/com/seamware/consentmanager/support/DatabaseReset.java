package com.seamware.consentmanager.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Empties every application table of the shared container in one {@code TRUNCATE}, on its own JDBC
 * connection so it works whether or not a Micronaut context is running.
 *
 * <p>A test that still holds a lock on a table when the reset runs fails with a lock-timeout error
 * after {@link #LOCK_TIMEOUT} rather than hanging the suite; see {@code docs/testing.md}.
 */
public final class DatabaseReset {

    /** How long the reset waits for a table lock before failing the test that left it held. */
    public static final String LOCK_TIMEOUT = "5s";

    /** Flyway's bookkeeping, kept so the schema is never re-migrated mid-suite. */
    static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    private static final String SCHEMA = "public";

    private static final String LIST_TABLES =
            "SELECT tablename FROM pg_tables WHERE schemaname = '"
                    + SCHEMA
                    + "' AND tablename <> '"
                    + FLYWAY_HISTORY_TABLE
                    + "' ORDER BY tablename";

    private static final String SET_LOCK_TIMEOUT = "SET lock_timeout = '" + LOCK_TIMEOUT + "'";

    /** The schema is fixed once Flyway has run, so the list is read once per JVM. */
    private static volatile String truncateStatement;

    private DatabaseReset() {}

    /** Truncates every table but Flyway's history and restarts their identity sequences. */
    public static void reset() {
        try (Connection connection = PostgresTestResource.openConnection();
                Statement statement = connection.createStatement()) {
            String truncate = truncateStatement(statement);
            if (truncate == null) {
                return;
            }
            statement.execute(SET_LOCK_TIMEOUT);
            statement.execute(truncate);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DatabaseReset could not truncate the shared schema; a test left a table"
                            + " locked or the database is unreachable",
                    e);
        }
    }

    /** Null until the schema has tables, so a reset before the first migration is a no-op. */
    private static String truncateStatement(Statement statement) throws SQLException {
        String cached = truncateStatement;
        if (cached != null) {
            return cached;
        }
        List<String> tables = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(LIST_TABLES)) {
            while (rows.next()) {
                tables.add(SCHEMA + ".\"" + rows.getString(1) + "\"");
            }
        }
        if (tables.isEmpty()) {
            return null;
        }
        cached = "TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY";
        truncateStatement = cached;
        return cached;
    }
}
