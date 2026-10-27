package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.Embeddable;
import io.micronaut.data.annotation.EmbeddedId;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.Transient;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Link between a {@link User} and a {@link Participant}, keyed by both ids, with an optional
 * identifier the participant uses for the user in its own system.
 */
@Serdeable
@MappedEntity("user_participants")
public class UserParticipant {

    @EmbeddedId private UserParticipantId id;

    /** Optional identifier the participant uses for the user in its own system. */
    @Nullable
    @MappedProperty("local_identifier")
    private String localIdentifier;

    @DateCreated
    @MappedProperty("created_at")
    private Instant createdAt;

    /** For Micronaut Data; application code uses the parameterized constructor. */
    public UserParticipant() {}

    /** Creates a link between a user and a participant. */
    public UserParticipant(
            @NonNull UUID userId, @NonNull UUID participantId, @Nullable String localIdentifier) {
        this.id = new UserParticipantId(userId, participantId);
        this.localIdentifier = localIdentifier;
    }

    /** The composite primary key. */
    @NonNull
    public UserParticipantId getId() {
        return id;
    }

    /** Sets the composite primary key. */
    public void setId(@NonNull UserParticipantId id) {
        this.id = id;
    }

    /** The user id; {@code @Transient} so the embedded {@code user_id} is not inserted twice. */
    @Transient
    @NonNull
    public UUID getUserId() {
        return id.getUserId();
    }

    /** The participant id; {@code @Transient} so {@code participant_id} is not inserted twice. */
    @Transient
    @NonNull
    public UUID getParticipantId() {
        return id.getParticipantId();
    }

    /** The participant-local identifier; {@code null} if unset. */
    @Nullable
    public String getLocalIdentifier() {
        return localIdentifier;
    }

    /** Sets the participant-local identifier, or clears it with {@code null}. */
    public void setLocalIdentifier(@Nullable String localIdentifier) {
        this.localIdentifier = localIdentifier;
    }

    /** When the link was created. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Sets the creation time; normally managed by {@link DateCreated}. */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** Composite key matching {@code pk_user_participants}. */
    @Embeddable
    @Serdeable
    public static class UserParticipantId {

        @MappedProperty("user_id")
        private UUID userId;

        @MappedProperty("participant_id")
        private UUID participantId;

        /** For Micronaut Data; application code uses the parameterized constructor. */
        public UserParticipantId() {}

        /** Creates the key of a user-participant link. */
        public UserParticipantId(@NonNull UUID userId, @NonNull UUID participantId) {
            this.userId = userId;
            this.participantId = participantId;
        }

        /** The user id. */
        @NonNull
        public UUID getUserId() {
            return userId;
        }

        /** Sets the user id. */
        public void setUserId(@NonNull UUID userId) {
            this.userId = userId;
        }

        /** The participant id. */
        @NonNull
        public UUID getParticipantId() {
            return participantId;
        }

        /** Sets the participant id. */
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
