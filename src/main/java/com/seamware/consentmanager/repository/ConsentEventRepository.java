package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.ConsentEvent;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link ConsentEvent} entities, backed by the {@code consent_events} database
 * table.
 *
 * <p>Provides standard CRUD operations plus methods for querying the consent audit trail. Events
 * are immutable once created; they record state transitions and actions taken on a consent record.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ConsentEventRepository extends CrudRepository<ConsentEvent, UUID> {

    /**
     * Finds all events for the given consent, ordered by occurrence time ascending.
     *
     * <p>Returns the full audit trail for a consent in chronological order, which is useful for
     * reconstructing the consent's lifecycle history. Ordering is enforced by the explicit
     * {@code @Query} annotation containing an {@code ORDER BY occurred_at ASC} clause.
     *
     * @param consentId the UUID of the consent record
     * @return a list of events in ascending chronological order, or an empty list if none exist
     */
    @Query(
            value =
                    "SELECT * FROM consent_events WHERE consent_id = :consentId"
                            + " ORDER BY occurred_at ASC",
            nativeQuery = true)
    List<ConsentEvent> findByConsentIdOrderByOccurredAtAsc(UUID consentId);

    /**
     * Finds all events for the given consent, paginated.
     *
     * <p>The total count in the returned page reflects only events for this consent.
     *
     * @param consentId the UUID of the consent record
     * @param pageable the pagination parameters (page number, size, sort)
     * @return a page of events for the specified consent
     */
    Page<ConsentEvent> findByConsentId(UUID consentId, Pageable pageable);
}
