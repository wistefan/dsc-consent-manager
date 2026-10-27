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

/** Repository over {@code consents}; every page's total counts only the filtered rows. */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ConsentRepository extends PageableRepository<Consent, UUID> {

    /** One page of the user's consents. */
    Page<Consent> findByUserId(UUID userId, Pageable pageable);

    /** One page of the user's consents in the statuses; never called with an empty set. */
    Page<Consent> findByUserIdAndStatusIn(
            UUID userId, Collection<ConsentStatus> statuses, Pageable pageable);

    /** One page of the user's consents under the notice. */
    Page<Consent> findByUserIdAndPrivacyNoticeId(
            UUID userId, UUID privacyNoticeId, Pageable pageable);

    /** One page of the user's consents in the (non-empty) statuses under the notice. */
    Page<Consent> findByUserIdAndStatusInAndPrivacyNoticeId(
            UUID userId,
            Collection<ConsentStatus> statuses,
            UUID privacyNoticeId,
            Pageable pageable);

    /** The consent if the user owns it; an unknown and a foreign id both read empty. */
    Optional<Consent> findByIdAndUserId(UUID id, UUID userId);

    /** Every consent of the user in the status. */
    List<Consent> findByUserIdAndStatus(UUID userId, ConsentStatus status);

    /** Every consent of the user in any of the statuses. */
    List<Consent> findByUserIdAndStatusIn(UUID userId, Collection<ConsentStatus> statuses);

    /** The user's consents in the statuses, newest first, row-locked; erasure's read. */
    @Query(
            "SELECT * FROM consents WHERE user_id = :userId AND status IN (:statuses)"
                    + " ORDER BY created_at DESC, id DESC FOR UPDATE")
    List<Consent> findByUserIdAndStatusInForUpdate(UUID userId, Collection<ConsentStatus> statuses);

    /** Whether any consent names the user, which forces the user row to survive erasure. */
    boolean existsByUserId(UUID userId);

    /** One page of consents with the participant as provider. */
    Page<Consent> findByProviderId(UUID providerId, Pageable pageable);

    /** One page of consents with the participant as consumer. */
    Page<Consent> findByConsumerId(UUID consumerId, Pageable pageable);

    /** Every consent under the notice. */
    List<Consent> findByPrivacyNoticeId(UUID privacyNoticeId);

    /** Every consent whose parent is the given consent. */
    List<Consent> findByParentConsentId(UUID parentConsentId);

    /** Consents in the statuses naming the participant, newest first, row-locked. */
    @Query(
            "SELECT * FROM consents"
                    + " WHERE (provider_id = :participantId OR consumer_id = :participantId)"
                    + " AND status IN (:statuses) ORDER BY created_at DESC, id DESC FOR UPDATE")
    List<Consent> findNamingParticipantForUpdate(
            UUID participantId, Collection<ConsentStatus> statuses);

    /** Every consent for the pair, any status, newest first (id breaks ties), row-locked. */
    @Query(
            "SELECT * FROM consents WHERE user_id = :userId"
                    + " AND privacy_notice_id = :privacyNoticeId"
                    + " ORDER BY created_at DESC, id DESC FOR UPDATE")
    List<Consent> findByUserIdAndPrivacyNoticeIdForUpdate(UUID userId, UUID privacyNoticeId);

    /** One consent, row-locked; lifecycle writes guard their transition on this read. */
    @Query("SELECT * FROM consents WHERE id = :id FOR UPDATE")
    Optional<Consent> findByIdForUpdate(UUID id);

    /** Consents naming the participant on either side, a self-dealing one counted once. */
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

    /** First {@code limit} ids in the status naming the participant, in id order. */
    @Query(
            "SELECT id FROM consents"
                    + " WHERE (provider_id = :participantId OR consumer_id = :participantId)"
                    + " AND status = :status ORDER BY id LIMIT :limit")
    List<UUID> findIdsByParticipantAndStatus(UUID participantId, ConsentStatus status, int limit);

    /** One page of consents the participant is party to; a null filter narrows nothing. */
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

    /** The consent in the status for subject and notice, if the participant is a party. */
    @Query(
            "SELECT c.* FROM consents c JOIN users u ON u.id = c.user_id"
                    + " WHERE u.identifier = :userIdentifier"
                    + " AND c.privacy_notice_id = :privacyNoticeId"
                    + " AND c.status = :status"
                    + " AND (c.provider_id = :participantId OR c.consumer_id = :participantId)")
    Optional<Consent> findForParticipantBySubjectAndNoticeAndStatus(
            String userIdentifier, UUID privacyNoticeId, ConsentStatus status, UUID participantId);

    /** The consent if the participant is a party; an unknown and a foreign id read empty. */
    @Query(
            "SELECT * FROM consents WHERE id = :id"
                    + " AND (provider_id = :participantId OR consumer_id = :participantId)")
    Optional<Consent> findByIdForParticipant(UUID id, UUID participantId);

    /** Ancestors of {@code startId} the user owns, nearest first; stops at depth or cycle. */
    @Query(
            "WITH RECURSIVE chain(id, parent_consent_id, depth, path) AS ("
                    + " SELECT c.id, c.parent_consent_id, 1, ARRAY[CAST(:startId AS uuid), c.id]"
                    + " FROM consents c WHERE c.id = :parentId AND c.user_id = :userId"
                    + " AND c.id <> :startId AND :maxDepth > 0"
                    + " UNION ALL"
                    + " SELECT p.id, p.parent_consent_id, chain.depth + 1, chain.path || p.id"
                    + " FROM chain JOIN consents p ON p.id = chain.parent_consent_id"
                    + " WHERE p.user_id = :userId AND chain.depth < :maxDepth"
                    + " AND p.id <> ALL(chain.path))"
                    + " SELECT c.* FROM chain JOIN consents c ON c.id = chain.id"
                    + " ORDER BY chain.depth")
    List<Consent> findLineageForUser(UUID startId, UUID parentId, UUID userId, int maxDepth);

    /** Ancestors naming the participant, nearest first; stops as {@link #findLineageForUser}. */
    @Query(
            "WITH RECURSIVE chain(id, parent_consent_id, depth, path) AS ("
                    + " SELECT c.id, c.parent_consent_id, 1, ARRAY[CAST(:startId AS uuid), c.id]"
                    + " FROM consents c WHERE c.id = :parentId"
                    + " AND (c.provider_id = :participantId OR c.consumer_id = :participantId)"
                    + " AND c.id <> :startId AND :maxDepth > 0"
                    + " UNION ALL"
                    + " SELECT p.id, p.parent_consent_id, chain.depth + 1, chain.path || p.id"
                    + " FROM chain JOIN consents p ON p.id = chain.parent_consent_id"
                    + " WHERE (p.provider_id = :participantId OR p.consumer_id = :participantId)"
                    + " AND chain.depth < :maxDepth AND p.id <> ALL(chain.path))"
                    + " SELECT c.* FROM chain JOIN consents c ON c.id = chain.id"
                    + " ORDER BY chain.depth")
    List<Consent> findLineageForParticipant(
            UUID startId, UUID parentId, UUID participantId, int maxDepth);
}
