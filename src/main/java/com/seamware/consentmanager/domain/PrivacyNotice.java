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
import java.util.UUID;

/**
 * Contract-derived terms under which a provider, and optionally a consumer, process personal data.
 * At most one unarchived notice exists per {@code (contract_uri, provider_id)}.
 */
@Serdeable
@MappedEntity("privacy_notices")
public class PrivacyNotice {

    /** Application-generated UUIDv7 primary key. */
    @Id private UUID id;

    /** URI of the contract this notice is derived from. */
    @NonNull
    @MappedProperty("contract_uri")
    private String contractUri;

    @Nullable private String title;

    @NonNull
    @MappedProperty("provider_id")
    private UUID providerId;

    @Nullable
    @MappedProperty("consumer_id")
    private UUID consumerId;

    @NonNull
    @TypeDef(type = DataType.JSON)
    private PrivacyNoticePayload payload;

    /** When archived, or {@code null} while active; archiving frees the contract/provider slot. */
    @Nullable
    @MappedProperty("archived_at")
    private Instant archivedAt;

    @DateCreated
    @MappedProperty("created_at")
    private Instant createdAt;

    @DateUpdated
    @MappedProperty("updated_at")
    private Instant updatedAt;

    /** For Micronaut Data; application code uses the parameterized constructor. */
    public PrivacyNotice() {}

    /** Creates a notice with a fresh UUIDv7 id. */
    public PrivacyNotice(
            @NonNull String contractUri,
            @Nullable String title,
            @NonNull UUID providerId,
            @Nullable UUID consumerId,
            @NonNull PrivacyNoticePayload payload) {
        this.id = UuidGenerator.uuidV7();
        this.contractUri = contractUri;
        this.title = title;
        this.providerId = providerId;
        this.consumerId = consumerId;
        this.payload = payload;
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

    /** The contract URI this notice is derived from. */
    @NonNull
    public String getContractUri() {
        return contractUri;
    }

    /** Sets the contract URI. */
    public void setContractUri(@NonNull String contractUri) {
        this.contractUri = contractUri;
    }

    /** The human-readable title; {@code null} if unset. */
    @Nullable
    public String getTitle() {
        return title;
    }

    /** Sets the title, or clears it with {@code null}. */
    public void setTitle(@Nullable String title) {
        this.title = title;
    }

    /** The data provider participant. */
    @NonNull
    public UUID getProviderId() {
        return providerId;
    }

    /** Sets the data provider participant. */
    public void setProviderId(@NonNull UUID providerId) {
        this.providerId = providerId;
    }

    /** The data consumer participant; {@code null} if unset. */
    @Nullable
    public UUID getConsumerId() {
        return consumerId;
    }

    /** Sets the data consumer participant, or clears it with {@code null}. */
    public void setConsumerId(@Nullable UUID consumerId) {
        this.consumerId = consumerId;
    }

    /** The contract-derived notice content. */
    @NonNull
    public PrivacyNoticePayload getPayload() {
        return payload;
    }

    /** Sets the contract-derived notice content. */
    public void setPayload(@NonNull PrivacyNoticePayload payload) {
        this.payload = payload;
    }

    /** When the notice was archived; {@code null} while active. */
    @Nullable
    public Instant getArchivedAt() {
        return archivedAt;
    }

    /** Sets the archive time; {@code null} marks the notice active. */
    public void setArchivedAt(@Nullable Instant archivedAt) {
        this.archivedAt = archivedAt;
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
}
