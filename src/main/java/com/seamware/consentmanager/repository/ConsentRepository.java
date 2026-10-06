package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.PageableRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link Consent} entities, backed by the {@code consents} database table.
 *
 * <p>Extends {@link PageableRepository} to provide standard CRUD operations plus pagination
 * support. All paginated queries return {@link Page} instances whose {@code getTotalSize()}
 * reflects the <em>filtered</em> result count (not the unfiltered table total), which is the
 * correct behavior from Micronaut Data's derived query methods (bug 18.3 prevention).
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ConsentRepository extends PageableRepository<Consent, UUID> {

    /**
     * Finds all consents for the given user, paginated.
     *
     * <p>The total count in the returned page reflects only consents for this user.
     *
     * @param userId the UUID of the user
     * @param pageable the pagination parameters (page number, size, sort)
     * @return a page of consents belonging to the user
     */
    Page<Consent> findByUserId(UUID userId, Pageable pageable);

    /**
     * Finds all consents for the given user with the specified status.
     *
     * @param userId the UUID of the user
     * @param status the consent status to filter by
     * @return a list of consents matching the user and status criteria
     */
    List<Consent> findByUserIdAndStatus(UUID userId, ConsentStatus status);

    /**
     * Finds all consents for the given user in any of the given statuses.
     *
     * <p>The erasure path uses this to sweep every consent still open - granted ones and unanswered
     * ones alike - in a single statement.
     *
     * @param userId the UUID of the user
     * @param statuses the consent statuses to filter by
     * @return a list of consents matching the user and any of the statuses
     */
    List<Consent> findByUserIdAndStatusIn(UUID userId, Collection<ConsentStatus> statuses);

    /**
     * Checks whether any consent at all refers to the given user.
     *
     * <p>This is what decides whether the {@code users} row has to survive an erasure: the row is
     * kept only because {@code consents.user_id} is {@code ON DELETE RESTRICT}, so a user no
     * consent names can be deleted outright.
     *
     * @param userId the UUID of the user
     * @return {@code true} if at least one consent references the user
     */
    boolean existsByUserId(UUID userId);

    /**
     * Finds all consents for the given data provider, paginated.
     *
     * <p>The total count in the returned page reflects only consents for this provider.
     *
     * @param providerId the UUID of the data provider participant
     * @param pageable the pagination parameters (page number, size, sort)
     * @return a page of consents where the specified participant is the provider
     */
    Page<Consent> findByProviderId(UUID providerId, Pageable pageable);

    /**
     * Finds all consents for the given data consumer, paginated.
     *
     * <p>The total count in the returned page reflects only consents for this consumer.
     *
     * @param consumerId the UUID of the data consumer participant
     * @param pageable the pagination parameters (page number, size, sort)
     * @return a page of consents where the specified participant is the consumer
     */
    Page<Consent> findByConsumerId(UUID consumerId, Pageable pageable);

    /**
     * Finds all consents associated with the given privacy notice.
     *
     * @param privacyNoticeId the UUID of the privacy notice
     * @return a list of consents linked to the specified privacy notice
     */
    List<Consent> findByPrivacyNoticeId(UUID privacyNoticeId);

    /**
     * Finds all consents that reference the given parent consent.
     *
     * @param parentConsentId the UUID of the parent consent
     * @return a list of child consents referencing the specified parent
     */
    List<Consent> findByParentConsentId(UUID parentConsentId);
}
