package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.User;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link User} entities, backed by the {@code users} database table.
 *
 * <p>Provides standard CRUD operations plus finder methods for looking up users by their external
 * identifier or email address.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserRepository extends CrudRepository<User, UUID> {

    /**
     * Finds a user by their external unique identifier (e.g. Keycloak subject ID).
     *
     * @param identifier the external identifier to search for
     * @return an {@link Optional} containing the user if found, or empty if no match
     */
    Optional<User> findByIdentifier(String identifier);

    /**
     * Finds a user by their email address, ignoring case.
     *
     * <p>This leverages the partial index on {@code lower(email)} in the database schema for
     * efficient lookups.
     *
     * @param email the email address to search for (case-insensitive)
     * @return an {@link Optional} containing the user if found, or empty if no match
     */
    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * Checks whether a user with the given external identifier exists.
     *
     * @param identifier the external identifier to check
     * @return {@code true} if a user with the identifier exists, {@code false} otherwise
     */
    boolean existsByIdentifier(String identifier);
}
