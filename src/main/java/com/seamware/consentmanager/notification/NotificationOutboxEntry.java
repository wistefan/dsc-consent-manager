package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.domain.UuidGenerator;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;
import java.time.Instant;
import java.util.UUID;

/**
 * One notification owed to one participant. {@code createdAt} and {@code nextAttemptAt} are set by
 * the database on insert, so they read null on a freshly saved instance until it is re-read.
 */
@MappedEntity("notification_outbox")
public class NotificationOutboxEntry {

    /** Application-generated UUIDv7 primary key. */
    @Id private UUID id;

    @NonNull
    @MappedProperty("participant_id")
    private UUID participantId;

    /** The reported {@code consent_events} id; shared by every recipient of one event. */
    @NonNull
    @MappedProperty("event_id")
    private UUID eventId;

    @NonNull
    @MappedProperty("consent_id")
    private UUID consentId;

    @NonNull
    @MappedProperty("event_type")
    private NotificationEventType eventType;

    /** The frozen document to send; null once the row is terminal. */
    @Nullable
    @TypeDef(type = DataType.JSON)
    private NotificationPayload payload;

    @NonNull private NotificationOutboxStatus status;

    /** Dispatch outcomes recorded so far. */
    private int attempts;

    @GeneratedValue
    @MappedProperty("next_attempt_at")
    private Instant nextAttemptAt;

    /** The current claim's fencing token; null when unclaimed or after an outcome. */
    @Nullable
    @MappedProperty("lease_token")
    private UUID leaseToken;

    /** Short classification of the last failure; never a response body. */
    @Nullable
    @MappedProperty("last_error")
    private String lastError;

    @GeneratedValue
    @MappedProperty("created_at")
    private Instant createdAt;

    @Nullable
    @MappedProperty("delivered_at")
    private Instant deliveredAt;

    /** For Micronaut Data; application code uses {@link #pending}. */
    public NotificationOutboxEntry() {}

    /** A new, immediately due row for {@code participantId} reporting {@code payload}. */
    public static NotificationOutboxEntry pending(
            @NonNull UUID participantId, @NonNull NotificationPayload payload) {
        var entry = new NotificationOutboxEntry();
        entry.id = UuidGenerator.uuidV7();
        entry.participantId = participantId;
        entry.eventId = payload.eventId();
        entry.consentId = payload.consentId();
        entry.eventType = payload.eventType();
        entry.payload = payload;
        entry.status = NotificationOutboxStatus.PENDING;
        return entry;
    }

    /** Primary key; {@code null} until assigned. */
    @Nullable
    public UUID getId() {
        return id;
    }

    /** Sets the primary key. */
    public void setId(UUID id) {
        this.id = id;
    }

    /** The recipient participant. */
    @NonNull
    public UUID getParticipantId() {
        return participantId;
    }

    /** Sets the recipient participant. */
    public void setParticipantId(@NonNull UUID participantId) {
        this.participantId = participantId;
    }

    /** The reported consent event. */
    @NonNull
    public UUID getEventId() {
        return eventId;
    }

    /** Sets the reported consent event. */
    public void setEventId(@NonNull UUID eventId) {
        this.eventId = eventId;
    }

    /** The consent the event moved. */
    @NonNull
    public UUID getConsentId() {
        return consentId;
    }

    /** Sets the consent the event moved. */
    public void setConsentId(@NonNull UUID consentId) {
        this.consentId = consentId;
    }

    /** What the notification reports. */
    @NonNull
    public NotificationEventType getEventType() {
        return eventType;
    }

    /** Sets what the notification reports. */
    public void setEventType(@NonNull NotificationEventType eventType) {
        this.eventType = eventType;
    }

    /** The frozen document; null once terminal. */
    @Nullable
    public NotificationPayload getPayload() {
        return payload;
    }

    /** Sets the frozen document. */
    public void setPayload(@Nullable NotificationPayload payload) {
        this.payload = payload;
    }

    /** Delivery state. */
    @NonNull
    public NotificationOutboxStatus getStatus() {
        return status;
    }

    /** Sets the delivery state. */
    public void setStatus(@NonNull NotificationOutboxStatus status) {
        this.status = status;
    }

    /** Dispatch outcomes recorded so far. */
    public int getAttempts() {
        return attempts;
    }

    /** Sets the outcome count. */
    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    /** When the row is next due, or its lease expires. */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    /** Sets when the row is next due. */
    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    /** The current claim's fencing token. */
    @Nullable
    public UUID getLeaseToken() {
        return leaseToken;
    }

    /** Sets the fencing token. */
    public void setLeaseToken(@Nullable UUID leaseToken) {
        this.leaseToken = leaseToken;
    }

    /** Classification of the last failure. */
    @Nullable
    public String getLastError() {
        return lastError;
    }

    /** Sets the failure classification. */
    public void setLastError(@Nullable String lastError) {
        this.lastError = lastError;
    }

    /** When the row was enqueued, on the database clock. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Sets the enqueue time. */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** When delivery succeeded, on the database clock. */
    @Nullable
    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    /** Sets the delivery time. */
    public void setDeliveredAt(@Nullable Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }
}
