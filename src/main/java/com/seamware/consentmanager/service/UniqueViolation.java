package com.seamware.consentmanager.service;

import io.micronaut.data.exceptions.DataAccessException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Tells a write rejected by a unique index apart from any other data access failure.
 *
 * <p>One definition for the whole service layer, so the SQLState appears once: every caller races
 * for a different constraint, but all of them have to recognise the same loss, and a deadlock or a
 * severed connection must not be mistaken for one.
 */
final class UniqueViolation {

    /** SQLState Postgres raises when a unique index rejects a write. */
    private static final String SQL_STATE = "23505";

    private UniqueViolation() {}

    /** Whether {@code e} was caused, at any depth, by a unique index rejecting the write. */
    static boolean rejected(DataAccessException e) {
        return Stream.iterate(
                        (Throwable) e,
                        Objects::nonNull,
                        cause -> cause.getCause() == cause ? null : cause.getCause())
                .anyMatch(
                        cause ->
                                cause instanceof SQLException sql
                                        && SQL_STATE.equals(sql.getSQLState()));
    }
}
