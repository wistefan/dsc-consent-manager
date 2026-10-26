package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.User;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository over {@code users}, with lookup by external identifier or email. */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserRepository extends CrudRepository<User, UUID> {

    /** The user with the external identifier, if any. */
    Optional<User> findByIdentifier(String identifier);

    /** Every user with the email, case-insensitively; addresses are not unique. */
    List<User> findAllByEmailIgnoreCase(String email);

    /** Several users by primary key in one query. */
    List<User> findByIdIn(Collection<UUID> ids);

    /** Whether a user with the external identifier exists. */
    boolean existsByIdentifier(String identifier);
}
