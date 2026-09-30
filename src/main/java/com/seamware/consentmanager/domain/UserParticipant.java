package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.Embeddable;
import io.micronaut.data.annotation.EmbeddedId;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents the many-to-many link between a {@link User} and a {@link Participant}.
 *
 * <p>This entity records which users are associated with which participants, with an optional
 * locally-scoped identifier that the participant may use to reference the user within their own
 * system. The composite primary key is ({@code userId}, {@code participantId}).
 *
 * <p>This entity maps to the {@code user_participants} database table.
 */
@Serdeable
@MappedEntity("user_participants")
public class UserParticipant {

    /** Composite primary key consisting of user ID and participant ID. */
    @EmbeddedId private UserParticipantId id;

    /**
     * Optional local identifier that the participant uses to reference the user in their own
     * system.
     */
    @Nullable
    @MappedProperty("local_identifier")
    private String localIdentifier;

    /** Timestamp when this association was created; set automatically on insert. */
    @DateCreated
    @MappedProperty("created_at")
    private Instant createdAt;

    /**
     * Default no-argument constructor required by Micronaut Data.
     *
     * <p>Use the parameterized constructor for application-level entity creation.
     */
    public UserParticipant() {}

    /**
     * Creates a new user–participant association.
     *
     * @param userId the UUID of the user (must not be {@code null})
     * @param participantId the UUID of the participant (must not be {@code null})
     * @param localIdentifier the optional local identifier used by the participant
     */
    public UserParticipant(
            @NonNull UUID userId, @NonNull UUID participantId, @Nullable String localIdentifier) {
        this.id = new UserParticipantId(userId, participantId);
        this.localIdentifier = localIdentifier;
    }

    /**
     * Returns the composite primary key.
     *
     * @return the composite key containing user ID and participant ID
     */
    @NonNull
    public UserParticipantId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * @param id the composite key (must not be {@code null})
     */
    public void setId(@NonNull UserParticipantId id) {
        this.id = id;
    }

    /**
     * Returns the user ID from the composite key.
     *
     * <p>Convenience accessor that delegates to {@link UserParticipantId#getUserId()}.
     *
     * @return the UUID of the associated user
     */
    @NonNull
    public UUID getUserId() {
        return id.getUserId();
    }

    /**
     * Returns the participant ID from the composite key.
     *
     * <p>Convenience accessor that delegates to {@link UserParticipantId#getParticipantId()}.
     *
     * @return the UUID of the associated participant
     */
    @NonNull
    public UUID getParticipantId() {
        return id.getParticipantId();
    }

    /**
     * Returns the optional local identifier that the participant uses to reference the user.
     *
     * @return the local identifier, or {@code null} if not set
     */
    @Nullable
    public String getLocalIdentifier() {
        return localIdentifier;
    }

    /**
     * Sets the local identifier that the participant uses to reference the user.
     *
     * @param localIdentifier the local identifier, or {@code null} to clear
     */
    public void setLocalIdentifier(@Nullable String localIdentifier) {
        this.localIdentifier = localIdentifier;
    }

    /**
     * Returns the timestamp when this association was created.
     *
     * @return the creation timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets the creation timestamp. Normally managed by {@link DateCreated}.
     *
     * @param createdAt the creation timestamp
     */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Embeddable composite primary key for the {@link UserParticipant} entity.
     *
     * <p>Consists of the user's UUID and the participant's UUID, matching the composite primary key
     * {@code pk_user_participants} in the {@code user_participants} database table.
     */
    @Embeddable
    @Serdeable
    public static class UserParticipantId {

        /** UUID of the associated user. */
        @MappedProperty("user_id")
        private UUID userId;

        /** UUID of the associated participant. */
        @MappedProperty("participant_id")
        private UUID participantId;

        /**
         * Default no-argument constructor required by Micronaut Data.
         *
         * <p>Use the parameterized constructor for application-level ID creation.
         */
        public UserParticipantId() {}

        /**
         * Creates a composite key for a user–participant association.
         *
         * @param userId the UUID of the user (must not be {@code null})
         * @param participantId the UUID of the participant (must not be {@code null})
         */
        public UserParticipantId(@NonNull UUID userId, @NonNull UUID participantId) {
            this.userId = userId;
            this.participantId = participantId;
        }

        /**
         * Returns the UUID of the associated user.
         *
         * @return the user UUID
         */
        @NonNull
        public UUID getUserId() {
            return userId;
        }

        /**
         * Sets the UUID of the associated user.
         *
         * @param userId the user UUID
         */
        public void setUserId(@NonNull UUID userId) {
            this.userId = userId;
        }

        /**
         * Returns the UUID of the associated participant.
         *
         * @return the participant UUID
         */
        @NonNull
        public UUID getParticipantId() {
            return participantId;
        }

        /**
         * Sets the UUID of the associated participant.
         *
         * @param participantId the participant UUID
         */
        public void setParticipantId(@NonNull UUID participantId) {
            this.participantId = participantId;
        }

        /** {@inheritDoc} */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            UserParticipantId that = (UserParticipantId) o;
            return Objects.equals(userId, that.userId)
                    && Objects.equals(participantId, that.participantId);
        }

        /** {@inheritDoc} */
        @Override
        public int hashCode() {
            return Objects.hash(userId, participantId);
        }
    }
}
