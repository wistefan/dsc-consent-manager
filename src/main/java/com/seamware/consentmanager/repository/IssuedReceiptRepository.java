package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.IssuedReceipt;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.UUID;

/** Stored grant receipts, keyed by the consent they attest. */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface IssuedReceiptRepository extends CrudRepository<IssuedReceipt, UUID> {

    /** Deletes every receipt naming the user as subject and reports how many went (ADR-0007). */
    @Query(
            "DELETE FROM consent_receipts"
                    + " WHERE consent_id IN (SELECT id FROM consents WHERE user_id = :userId)")
    int deleteByUserId(UUID userId);
}
