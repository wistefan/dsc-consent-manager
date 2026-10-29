package com.seamware.consentmanager.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the shared container over plain JDBC, bypassing repositories and the Micronaut context, so
 * a test asserts the rows PostgreSQL committed rather than what the application believes it wrote.
 */
public final class RawSql {

    private RawSql() {}

    /** Maps the current row of a result set. */
    @FunctionalInterface
    public interface RowMapper<T> {

        /** Reads the row the cursor is on; must not advance it. */
        T map(ResultSet row) throws SQLException;
    }

    /** Runs a {@code SELECT count(*) ...} with positional parameters. */
    public static long count(String sql, Object... parameters) {
        return query(sql, row -> row.getLong(1), parameters).getFirst();
    }

    /** Runs a query with positional parameters, mapping every row. */
    public static <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
        try (Connection connection = PostgresTestResource.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            List<T> mapped = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    mapped.add(mapper.map(rows));
                }
            }
            return mapped;
        } catch (SQLException e) {
            throw new IllegalStateException("Raw SQL query failed: " + sql, e);
        }
    }
}
