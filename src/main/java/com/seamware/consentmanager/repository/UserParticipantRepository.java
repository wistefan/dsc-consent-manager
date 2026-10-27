package com.seamware.consentmanager.repository;

import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.domain.UserParticipant.UserParticipantId;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.List;
import java.util.UUID;

/** Repository over {@code user_participants}, keyed by the composite {@link UserParticipantId}. */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserParticipantRepository
        extends CrudRepository<UserParticipant, UserParticipantId> {

    /** Every link of the user. */
    List<UserParticipant> findByIdUserId(UUID userId);

    /** Every link of the participant. */
    List<UserParticipant> findByIdParticipantId(UUID participantId);

    /** Whether the user and participant are linked. */
    boolean existsByIdUserIdAndIdParticipantId(UUID userId, UUID participantId);

    /** Deletes the link between the user and participant. */
    void deleteByIdUserIdAndIdParticipantId(UUID userId, UUID participantId);

    /** Deletes every link of the user in one statement and returns the count; for erasure. */
    long deleteByIdUserId(UUID userId);

    /** Deletes every link of the participant, returning the count; FK cascade skips kept rows. */
    int deleteByIdParticipantId(UUID participantId);
}
