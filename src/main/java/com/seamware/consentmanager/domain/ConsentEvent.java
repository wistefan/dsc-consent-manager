package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable audit entry recording one lifecycle transition of a {@link Consent}; deleted with its
 * consent by cascade.
 */
@Serdeable
@MappedEntity("consent_events")
public class ConsentEvent {

    /** Event type used when none is given. */
    public static final String DEFAULT_EVENT_TYPE = "explicit";

    /** Application-generated UUIDv7 primary key. */
    @Id private UUID id;

    @NonNull
    @MappedProperty("consent_id")
    private UUID consentId;

    @NonNull
    @MappedProperty("event_state")
    private ConsentEventState eventState;

    /** What triggered the event, e.g. {@code explicit} (user) or {@code system}. */
    @NonNull
    @MappedProperty("event_type")
    private String eventType;

    /** Optional identifier of the triggering actor, e.g. a user id or system component. */
    @Nullable private String actor;

    @NonNull
    @MappedProperty("occurred_at")
    private Instant occurredAt;

    /** Optional event-specific metadata, stored as JSONB. */
    @Nullable
    @TypeDef(type = DataType.JSON)
    private Map<String, Object> details;

    /** For Micronaut Data; application code uses a parameterized constructor. */
    public ConsentEvent() {}

    /** Creates a {@value #DEFAULT_EVENT_TYPE} event occurring now, with a fresh UUIDv7 id. */
    public ConsentEvent(
            @NonNull UUID consentId,
            @NonNull ConsentEventState eventState,
            @Nullable String actor,
            @Nullable Map<String, Object> details) {
        this.id = UuidGenerator.uuidV7();
        this.consentId = consentId;
        this.eventState = eventState;
        this.eventType = DEFAULT_EVENT_TYPE;
        this.actor = actor;
        this.occurredAt = Instant.now();
        this.details = details;
    }

    /** Creates an event with an explicit type and occurrence time, with a fresh UUIDv7 id. */
    public ConsentEvent(
            @NonNull UUID consentId,
            @NonNull ConsentEventState eventState,
            @NonNull String eventType,
            @Nullable String actor,
            @NonNull Instant occurredAt,
            @Nullable Map<String, Object> details) {
        this.id = UuidGenerator.uuidV7();
        this.consentId = consentId;
        this.eventState = eventState;
        this.eventType = eventType;
        this.actor = actor;
        this.occurredAt = occurredAt;
        this.details = details;
    }

    /** Primary key; {@code null} until assigned. */
    @Nullable
    public UUID getId() {
        return id;
    }

    /** Sets the primary key. */
    public void setId(@Nullable UUID id) {
        this.id = id;
    }

    /** The consent this event belongs to. */
    @NonNull
    public UUID getConsentId() {
        return consentId;
    }

    /** Sets the consent this event belongs to. */
    public void setConsentId(@NonNull UUID consentId) {
        this.consentId = consentId;
    }

    /** The recorded transition. */
    @NonNull
    public ConsentEventState getEventState() {
        return eventState;
    }

    /** Sets the recorded transition. */
    public void setEventState(@NonNull ConsentEventState eventState) {
        this.eventState = eventState;
    }

    /** What triggered the event; defaults to {@value #DEFAULT_EVENT_TYPE}. */
    @NonNull
    public String getEventType() {
        return eventType;
    }

    /** Sets what triggered the event. */
    public void setEventType(@NonNull String eventType) {
        this.eventType = eventType;
    }

    /** The triggering actor; {@code null} if not recorded. */
    @Nullable
    public String getActor() {
        return actor;
    }

    /** Sets the triggering actor, or clears it with {@code null}. */
    public void setActor(@Nullable String actor) {
        this.actor = actor;
    }

    /** When the event occurred. */
    @NonNull
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** Sets when the event occurred. */
    public void setOccurredAt(@NonNull Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    /** Event metadata; {@code null} if not recorded. */
    @Nullable
    public Map<String, Object> getDetails() {
        return details;
    }

    /** Sets the event metadata, or clears it with {@code null}. */
    public void setDetails(@Nullable Map<String, Object> details) {
        this.details = details;
    }
}
