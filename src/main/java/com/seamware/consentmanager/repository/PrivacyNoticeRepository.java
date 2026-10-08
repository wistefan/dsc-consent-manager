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

/**
 * Repository for {@link PrivacyNotice} entities, backed by the {@code privacy_notices} database
 * table.
 *
 * <p>Extends {@link PageableRepository} to provide standard CRUD operations plus pagination support
 * (including {@code findAll(Pageable)}). Includes methods for querying active (non-archived)
 * privacy notices by provider and contract URI.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface PrivacyNoticeRepository extends PageableRepository<PrivacyNotice, UUID> {

    /**
     * Finds all active (non-archived) privacy notices for the given provider.
     *
     * <p>An active privacy notice is one where {@code archived_at IS NULL}.
     *
     * @param providerId the UUID of the data provider participant
     * @return a list of active privacy notices for the provider, or an empty list if none exist
     */
    List<PrivacyNotice> findByProviderIdAndArchivedAtIsNull(UUID providerId);

    /**
     * Finds an active (non-archived) privacy notice by contract URI and provider.
     *
     * <p>This method is used to check for existing active notices before creating a new one, since
     * the database enforces a partial unique index on {@code (contract_uri, provider_id)} where
     * {@code archived_at IS NULL}.
     *
     * @param contractUri the contract URI to search for
     * @param providerId the UUID of the data provider participant
     * @return an {@link Optional} containing the active privacy notice if found, or empty
     */
    Optional<PrivacyNotice> findByContractUriAndProviderIdAndArchivedAtIsNull(
            String contractUri, UUID providerId);

    /**
     * Returns a paginated list of active (non-archived) privacy notices for the given provider.
     *
     * @param providerId the UUID of the data provider participant
     * @param pageable the pagination parameters (page number, size, sort)
     * @return a page of active privacy notices matching the criteria
     */
    Page<PrivacyNotice> findByProviderIdAndArchivedAtIsNull(UUID providerId, Pageable pageable);

    /**
     * Active (non-archived) privacy notices where the participant is the consumer.
     *
     * <p>The provider-side counterpart above was all this service needed until deregistration,
     * which archives a departing participant's notices from whichever side it stands on.
     */
    List<PrivacyNotice> findByConsumerIdAndArchivedAtIsNull(UUID consumerId);

    /**
     * How many privacy notices name the participant on either side, archived ones included.
     *
     * <p>Both participant foreign keys here are {@code ON DELETE RESTRICT}, so a non-zero count is
     * what forces deregistration to retain the record rather than delete it. Archiving a notice
     * does not release the reference, which is why this counts them too.
     */
    @Query(
            "SELECT COUNT(*) FROM privacy_notices"
                    + " WHERE provider_id = :participantId OR consumer_id = :participantId")
    int countByParticipant(UUID participantId);
}
