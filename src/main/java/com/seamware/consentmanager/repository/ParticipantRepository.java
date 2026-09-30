package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.Participant;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Participant} entities, backed by the {@code participants} database table.
 *
 * <p>Extends {@link PageableRepository} to provide standard CRUD operations plus pagination
 * support. Includes finder methods for looking up participants by their external identifier.
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
     * Returns a paginated list of all participants.
     *
     * <p>The returned {@link Page} includes the total count of participants for pagination
     * metadata.
     *
     * @param pageable the pagination parameters (page number, size, sort)
     * @return a page of participants matching the pagination criteria
     */
    Page<Participant> findAll(Pageable pageable);
}
