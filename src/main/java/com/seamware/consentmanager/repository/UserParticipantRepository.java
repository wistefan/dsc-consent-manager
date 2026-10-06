package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.domain.UserParticipant.UserParticipantId;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link UserParticipant} link entities, backed by the {@code user_participants}
 * database table.
 *
 * <p>This repository manages the many-to-many association between users and participants. The
 * entity uses a composite primary key ({@code user_id}, {@code participant_id}) represented by the
 * {@link UserParticipantId} embeddable class, which allows standard {@link CrudRepository}
 * operations to work with the composite key.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserParticipantRepository
        extends CrudRepository<UserParticipant, UserParticipantId> {

    /**
     * Finds all user–participant associations for the given user.
     *
     * @param userId the UUID of the user (from the embedded composite key)
     * @return a list of associations for the user, or an empty list if none exist
     */
    List<UserParticipant> findByIdUserId(UUID userId);

    /**
     * Finds all user–participant associations for the given participant.
     *
     * @param participantId the UUID of the participant (from the embedded composite key)
     * @return a list of associations for the participant, or an empty list if none exist
     */
    List<UserParticipant> findByIdParticipantId(UUID participantId);

    /**
     * Checks whether an association between the given user and participant exists.
     *
     * @param userId the UUID of the user
     * @param participantId the UUID of the participant
     * @return {@code true} if the association exists, {@code false} otherwise
     */
    boolean existsByIdUserIdAndIdParticipantId(UUID userId, UUID participantId);

    /**
     * Deletes the association between the given user and participant.
     *
     * @param userId the UUID of the user
     * @param participantId the UUID of the participant
     */
    void deleteByIdUserIdAndIdParticipantId(UUID userId, UUID participantId);

    /**
     * Removes every association the given user has, in one statement.
     *
     * <p>The erasure path uses this: it ends all affiliations at once and needs the count it
     * removed to report back to the data subject.
     *
     * @param userId the UUID of the user
     * @return how many associations were removed
     */
    long deleteByIdUserId(UUID userId);
}
