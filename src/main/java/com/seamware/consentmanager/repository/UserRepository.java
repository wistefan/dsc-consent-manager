package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.User;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.Collection;
import java.util.List;
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
     * Every user holding the email address, matched case-insensitively against the partial index on
     * {@code lower(email)}.
     *
     * <p>Returns a list rather than an {@code Optional} because the schema deliberately permits
     * several users to share an address - {@code idx_users_email_lower} is not unique - and a
     * single-result finder would raise {@code NonUniqueResultException} the moment two do.
     */
    List<User> findAllByEmailIgnoreCase(String email);

    /** Loads several users by primary key, so a link set resolves in one query. */
    List<User> findByIdIn(Collection<UUID> ids);

    /**
     * Checks whether a user with the given external identifier exists.
     *
     * @param identifier the external identifier to check
     * @return {@code true} if a user with the identifier exists, {@code false} otherwise
     */
    boolean existsByIdentifier(String identifier);
}
