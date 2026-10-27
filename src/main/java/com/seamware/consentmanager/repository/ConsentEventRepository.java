package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.ConsentEvent;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.List;
import java.util.UUID;

/** Repository over {@code consent_events}, the immutable consent audit trail. */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ConsentEventRepository extends CrudRepository<ConsentEvent, UUID> {

    /** Every event of the consent, oldest first. */
    List<ConsentEvent> findByConsentIdOrderByOccurredAtAsc(UUID consentId);

    /** One page of the consent's events. */
    Page<ConsentEvent> findByConsentId(UUID consentId, Pageable pageable);
}
