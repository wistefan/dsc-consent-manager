package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.DateUpdated;
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
 * An organization that provides or consumes data under a privacy notice, unique by {@code
 * identifier}; endpoints and legal-person metadata are free-form JSONB.
 */
@Serdeable
@MappedEntity("participants")
public class Participant {

    /** Key of the consent-notification webhook URL in {@link #getEndpoints()}. */
    public static final String CONSENT_NOTIFICATION_ENDPOINT = "consentNotification";

    /** Application-generated UUIDv7 primary key. */
    @Id private UUID id;

    /** External unique identifier. */
    @NonNull private String identifier;

    @NonNull
    @MappedProperty("legal_name")
    private String legalName;

    @Nullable
    @MappedProperty("self_description_uri")
    private String selfDescriptionUri;

    @Nullable private String email;

    /** Service endpoints; structure varies by contract. */
    @NonNull
    @TypeDef(type = DataType.JSON)
    private Map<String, Object> endpoints;

    /** Optional legal-entity metadata, e.g. Gaia-X self-description attributes. */
    @Nullable
    @TypeDef(type = DataType.JSON)
    @MappedProperty("legal_person")
    private Map<String, Object> legalPerson;

    @DateCreated
    @MappedProperty("created_at")
    private Instant createdAt;

    @DateUpdated
    @MappedProperty("updated_at")
    private Instant updatedAt;

    /** Set only on a deregistered row kept because a consent or notice references it. */
    @Nullable
    @MappedProperty("deregistered_at")
    private Instant deregisteredAt;

    /** For Micronaut Data; application code uses the parameterized constructor. */
    public Participant() {}

    /** Creates a participant with a fresh UUIDv7 id. */
    public Participant(
            @NonNull String identifier,
            @NonNull String legalName,
            @Nullable String selfDescriptionUri,
            @Nullable String email,
            @NonNull Map<String, Object> endpoints,
            @Nullable Map<String, Object> legalPerson) {
        this.id = UuidGenerator.uuidV7();
        this.identifier = identifier;
        this.legalName = legalName;
        this.selfDescriptionUri = selfDescriptionUri;
        this.email = email;
        this.endpoints = endpoints;
        this.legalPerson = legalPerson;
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

    /** The external unique identifier. */
    @NonNull
    public String getIdentifier() {
        return identifier;
    }

    /** Sets the external unique identifier. */
    public void setIdentifier(@NonNull String identifier) {
        this.identifier = identifier;
    }

    /** The official legal name. */
    @NonNull
    public String getLegalName() {
        return legalName;
    }

    /** Sets the official legal name. */
    public void setLegalName(@NonNull String legalName) {
        this.legalName = legalName;
    }

    /** The self-description URI; {@code null} if unset. */
    @Nullable
    public String getSelfDescriptionUri() {
        return selfDescriptionUri;
    }

    /** Sets the self-description URI, or clears it with {@code null}. */
    public void setSelfDescriptionUri(@Nullable String selfDescriptionUri) {
        this.selfDescriptionUri = selfDescriptionUri;
    }

    /** The contact email; {@code null} if unset. */
    @Nullable
    public String getEmail() {
        return email;
    }

    /** Sets the contact email, or clears it with {@code null}. */
    public void setEmail(@Nullable String email) {
        this.email = email;
    }

    /** The service endpoints. */
    @NonNull
    public Map<String, Object> getEndpoints() {
        return endpoints;
    }

    /** Sets the service endpoints. */
    public void setEndpoints(@NonNull Map<String, Object> endpoints) {
        this.endpoints = endpoints;
    }

    /** The legal-entity metadata; {@code null} if unset. */
    @Nullable
    public Map<String, Object> getLegalPerson() {
        return legalPerson;
    }

    /** Sets the legal-entity metadata, or clears it with {@code null}. */
    public void setLegalPerson(@Nullable Map<String, Object> legalPerson) {
        this.legalPerson = legalPerson;
    }

    /** When the record was created. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Sets the creation time; normally managed by {@link DateCreated}. */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** When the record was last updated. */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Sets the last-updated time; normally managed by {@link DateUpdated}. */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** When the participant deregistered; {@code null} while active. */
    @Nullable
    public Instant getDeregisteredAt() {
        return deregisteredAt;
    }

    /** Sets the deregistration time; {@code null} reactivates the participant. */
    public void setDeregisteredAt(@Nullable Instant deregisteredAt) {
        this.deregisteredAt = deregisteredAt;
    }
}
