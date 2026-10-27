package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.PrivacyNotice;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository over {@code privacy_notices}; "active" means {@code archived_at IS NULL}. */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface PrivacyNoticeRepository extends PageableRepository<PrivacyNotice, UUID> {

    /** Every active notice the participant provides. */
    List<PrivacyNotice> findByProviderIdAndArchivedAtIsNull(UUID providerId);

    /** The active notice for the contract and provider; the partial unique index allows one. */
    Optional<PrivacyNotice> findByContractUriAndProviderIdAndArchivedAtIsNull(
            String contractUri, UUID providerId);

    /** One page of active notices the participant provides. */
    Page<PrivacyNotice> findByProviderIdAndArchivedAtIsNull(UUID providerId, Pageable pageable);

    /** Every active notice naming the participant as consumer. */
    List<PrivacyNotice> findByConsumerIdAndArchivedAtIsNull(UUID consumerId);

    /** Notices naming the participant on either side, archived included; non-zero retains it. */
    @Query(
            "SELECT COUNT(*) FROM privacy_notices"
                    + " WHERE provider_id = :participantId OR consumer_id = :participantId")
    int countByParticipant(UUID participantId);

    /** Distinct contract URIs of active notices the participant provides; discovery's input. */
    @Query(
            "SELECT DISTINCT contract_uri FROM privacy_notices"
                    + " WHERE provider_id = :participantId AND archived_at IS NULL")
    List<String> findActiveContractUrisByProvider(UUID participantId);

    /** Distinct contract URIs of the active notices naming the participant as consumer. */
    @Query(
            "SELECT DISTINCT contract_uri FROM privacy_notices"
                    + " WHERE consumer_id = :participantId AND archived_at IS NULL")
    List<String> findActiveContractUrisByConsumer(UUID participantId);
}
