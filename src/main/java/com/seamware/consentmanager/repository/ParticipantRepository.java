package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.Participant;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository over {@code participants}, including lookup by external identifier. */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ParticipantRepository extends PageableRepository<Participant, UUID> {

    /** The participant with the external identifier, if any. */
    Optional<Participant> findByIdentifier(String identifier);

    /** Whether a participant with the external identifier exists. */
    boolean existsByIdentifier(String identifier);

    /**
     * One participant, row-locked so a self-service write and deregistration serialize. {@code NO
     * KEY} so a racing grant's outbox FK check is not blocked into a deadlock with its consent
     * lock.
     */
    @Query("SELECT * FROM participants WHERE id = :id FOR NO KEY UPDATE")
    Optional<Participant> findByIdForUpdate(UUID id);

    /** One page of active participants; deregistered rows are excluded in SQL. */
    Page<Participant> findByDeregisteredAtIsNull(Pageable pageable);

    /** The page narrowed to one exact, case-sensitive identifier, deregistered included. */
    Page<Participant> findByIdentifier(String identifier, Pageable pageable);

    /** Several participants by primary key in one query. */
    List<Participant> findByIdIn(Collection<UUID> ids);
}
