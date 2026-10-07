package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.Participant;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Participant} entities, backed by the {@code participants} database table.
 *
 * <p>Extends {@link PageableRepository} to provide standard CRUD operations plus pagination support
 * (including {@code findAll(Pageable)}). Includes finder methods for looking up participants by
 * their external identifier.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ParticipantRepository extends PageableRepository<Participant, UUID> {

    /**
     * Finds a participant by their external unique identifier.
     *
     * @param identifier the external identifier to search for
     * @return an {@link Optional} containing the participant if found, or empty if no match
     */
    Optional<Participant> findByIdentifier(String identifier);

    /**
     * Checks whether a participant with the given external identifier exists.
     *
     * @param identifier the external identifier to check
     * @return {@code true} if a participant with the identifier exists, {@code false} otherwise
     */
    boolean existsByIdentifier(String identifier);

    /**
     * Loads a participant by primary key and holds a row lock until the transaction ends.
     *
     * <p>A self-service write reads the row this way so a concurrent deregistration can neither
     * commit between the read and the write nor be overwritten by it: it waits for the lock, and
     * the write that waits for it instead sees the deregistration and refuses.
     */
    @Query("SELECT * FROM participants WHERE id = :id FOR UPDATE")
    Optional<Participant> findByIdForUpdate(UUID id);

    /** Loads several participants by primary key, so a link set resolves in one query. */
    List<Participant> findByIdIn(Collection<UUID> ids);
}
