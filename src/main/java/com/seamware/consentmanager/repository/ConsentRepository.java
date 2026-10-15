package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.Nullable;
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
     * One page of the user's consents in any of the given statuses, filtered total included.
     *
     * <p>Backs the status-only filter of {@code GET /consents/me}. The page's total is counted
     * under the same predicate as its content, so it is the number of the user's consents in these
     * statuses rather than their whole history (bug 18.3 prevention). Called only for a non-empty
     * status set, so no empty {@code IN ()} ever reaches Postgres.
     */
    Page<Consent> findByUserIdAndStatusIn(
            UUID userId, Collection<ConsentStatus> statuses, Pageable pageable);

    /**
     * One page of the user's consents decided against the given notice, filtered total included.
     *
     * <p>Backs the notice-only filter of {@code GET /consents/me}, with the same filtered-total
     * guarantee as the status variants.
     */
    Page<Consent> findByUserIdAndPrivacyNoticeId(
            UUID userId, UUID privacyNoticeId, Pageable pageable);

    /**
     * One page of the user's consents matching both the status set and the notice, filtered total
     * included.
     *
     * <p>Backs the combined filter of {@code GET /consents/me}; called only for a non-empty status
     * set.
     */
    Page<Consent> findByUserIdAndStatusInAndPrivacyNoticeId(
            UUID userId,
            Collection<ConsentStatus> statuses,
            UUID privacyNoticeId,
            Pageable pageable);

    /**
     * One consent the given user owns, or empty when no such consent exists.
     *
     * <p>Ownership is a predicate rather than a check after the read, so {@code GET
     * /consents/me/id} cannot tell an unknown id from one owned by another subject: both resolve to
     * empty and the same {@code 404}, disclosing nothing about consents the caller does not own.
     */
    Optional<Consent> findByIdAndUserId(UUID id, UUID userId);

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

    /**
     * Consents in any of the given statuses where the participant is the provider.
     *
     * <p>Deregistration reads the two sides separately rather than through one {@code OR} query
     * because a derived query binds the status enums for us; a consent naming the participant on
     * both sides comes back from both calls and is de-duplicated by id.
     */
    List<Consent> findByProviderIdAndStatusIn(UUID providerId, Collection<ConsentStatus> statuses);

    /** Consents in any of the given statuses where the participant is the consumer. */
    List<Consent> findByConsumerIdAndStatusIn(UUID consumerId, Collection<ConsentStatus> statuses);

    /**
     * Every consent for the pair, newest first, locked against concurrent decisions.
     *
     * <p>The writer's single read, following {@link ParticipantRepository#findByIdForUpdate}: a
     * decision taken against this result cannot be overtaken between the read and the write, which
     * no index could cover because the writer also updates an unanswered consent in place.
     *
     * <p>Unfiltered by status deliberately. The open rows are what a decision supersedes and the
     * most recent row of any status is what it links as its parent, so a status predicate would
     * both lose the parent and, under {@code READ COMMITTED}, drop a row whose lock it waited for
     * precisely because another transaction had just moved it out of the filter. The id breaks a
     * tie within one microsecond, and a UUIDv7 orders by creation time like {@code created_at}.
     */
    @Query(
            "SELECT * FROM consents WHERE user_id = :userId"
                    + " AND privacy_notice_id = :privacyNoticeId"
                    + " ORDER BY created_at DESC, id DESC FOR UPDATE")
    List<Consent> findByUserIdAndPrivacyNoticeIdForUpdate(UUID userId, UUID privacyNoticeId);

    /**
     * One consent by id, locked against concurrent lifecycle moves.
     *
     * <p>Every lifecycle write re-reads its row through this inside its transaction and guards the
     * transition on <em>that</em> read rather than on the one that authorised the caller, so two
     * moves sent at once cannot both append an event: the loser waits, sees the status the winner
     * left, and is refused by the state machine.
     */
    @Query("SELECT * FROM consents WHERE id = :id FOR UPDATE")
    Optional<Consent> findByIdForUpdate(UUID id);

    /**
     * How many consents name the participant on either side, counting a self-dealing consent once.
     *
     * <p>Deregistration needs this twice over: it is the retained-consent tally reported back, and
     * a non-zero count is what forces the record to be kept, since both participant foreign keys on
     * {@code consents} are {@code ON DELETE RESTRICT}. Counted as an {@code int} because the tally
     * is published through an {@code int32} field and could never have carried more.
     */
    @Query(
            "SELECT COUNT(*) FROM consents"
                    + " WHERE provider_id = :participantId OR consumer_id = :participantId")
    int countByParticipant(UUID participantId);

    /** How many consents in the given status name the participant on either side. */
    @Query(
            "SELECT COUNT(*) FROM consents"
                    + " WHERE (provider_id = :participantId OR consumer_id = :participantId)"
                    + " AND status = :status")
    int countByParticipantAndStatus(UUID participantId, ConsentStatus status);

    /**
     * The first {@code limit} consent ids in the given status naming the participant, in id order.
     *
     * <p>Deregistration's refusal names the consents blocking it and reports the rest as a count,
     * so only as many ids as it prints are ever read; a long-lived participant would otherwise
     * materialise its whole granted history to produce one problem detail. The order is the id
     * order the detail prints in, so the listed ids are stable across retries.
     */
    @Query(
            "SELECT id FROM consents"
                    + " WHERE (provider_id = :participantId OR consumer_id = :participantId)"
                    + " AND status = :status ORDER BY id LIMIT :limit")
    List<UUID> findIdsByParticipantAndStatus(UUID participantId, ConsentStatus status, int limit);

    /**
     * One page of the consents the participant is a party to, filtered total included.
     *
     * <p>Backs {@code GET /consents}. The caller is scoped <em>in</em> the predicate - a consent
     * naming it as neither provider nor consumer is never read - so a participant can see only
     * consents it is party to and no window exists in which another's row is materialised. Backed
     * by {@code idx_consents_provider_id} and {@code idx_consents_consumer_id}.
     *
     * <p>{@code statuses} is always non-empty: an absent {@code status} filter defaults to {@code
     * GRANTED} in the service, so no empty {@code IN ()} reaches Postgres. {@code userId} and
     * {@code privacyNoticeId} are each optional; a null narrows nothing, guarded with a {@code
     * CAST(... AS uuid) IS NULL} rather than branching into a separate method per filter
     * combination. The page's total is counted under the same predicate as its content (bug 18.3
     * prevention). The table carries the {@code consent_} alias Micronaut appends its dynamic
     * {@code ORDER BY} against, so the {@link Pageable}'s sort resolves on a native query.
     */
    @Query(
            value =
                    "SELECT * FROM consents consent_"
                            + " WHERE (provider_id = :participantId OR consumer_id = :participantId)"
                            + " AND status IN (:statuses)"
                            + " AND (CAST(:userId AS uuid) IS NULL OR user_id = :userId)"
                            + " AND (CAST(:privacyNoticeId AS uuid) IS NULL"
                            + " OR privacy_notice_id = :privacyNoticeId)",
            countQuery =
                    "SELECT COUNT(*) FROM consents"
                            + " WHERE (provider_id = :participantId OR consumer_id = :participantId)"
                            + " AND status IN (:statuses)"
                            + " AND (CAST(:userId AS uuid) IS NULL OR user_id = :userId)"
                            + " AND (CAST(:privacyNoticeId AS uuid) IS NULL"
                            + " OR privacy_notice_id = :privacyNoticeId)")
    Page<Consent> findForParticipant(
            UUID participantId,
            Collection<ConsentStatus> statuses,
            @Nullable UUID userId,
            @Nullable UUID privacyNoticeId,
            Pageable pageable);

    /**
     * The consent in the given status for the pair that the participant is a party to, or empty.
     *
     * <p>Backs {@code GET /consents/check}. Called with {@code GRANTED}, where the partial unique
     * index {@code uq_consents_granted_per_user_notice} admits at most one row per pair, so the
     * single indexed lookup returns at most one. The caller is scoped <em>in</em> the predicate - a
     * grant naming it as neither provider nor consumer is never read - so a participant cannot
     * learn whether another's grant exists, and an unknown subject yields the same empty answer as
     * a known subject with no grant. Backed by {@code idx_consents_privacy_notice_id}.
     */
    @Query(
            "SELECT * FROM consents WHERE user_id = :userId"
                    + " AND privacy_notice_id = :privacyNoticeId"
                    + " AND status = :status"
                    + " AND (provider_id = :participantId OR consumer_id = :participantId)")
    Optional<Consent> findForParticipantByUserAndNoticeAndStatus(
            UUID userId, UUID privacyNoticeId, ConsentStatus status, UUID participantId);

    /**
     * One consent the participant is a party to, or empty when none with that id names it.
     *
     * <p>Party-ship is a predicate rather than a check after the read, so {@code GET /consents/id}
     * cannot tell an unknown id from one the caller is no party to: both resolve to empty and the
     * same {@code 404}, disclosing nothing about consents the caller may not see.
     */
    @Query(
            "SELECT * FROM consents WHERE id = :id"
                    + " AND (provider_id = :participantId OR consumer_id = :participantId)")
    Optional<Consent> findByIdForParticipant(UUID id, UUID participantId);
}
