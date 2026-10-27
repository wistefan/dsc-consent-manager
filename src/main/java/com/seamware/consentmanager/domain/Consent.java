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
 * A user's decision on one privacy notice, between a provider and a consumer, with frozen terms.
 * Invariant: {@code consented == (status == GRANTED)}; change status only via {@link
 * #updateStatus(ConsentStatus)} or {@link #withStatus}, never the hydration setters.
 */
@Serdeable
@MappedEntity("consents")
public class Consent {

    /** Application-generated UUIDv7 primary key. */
    @Id private UUID id;

    /** The {@link User} who granted, or is asked for, this consent. */
    @NonNull
    @MappedProperty("user_id")
    private UUID userId;

    @NonNull
    @MappedProperty("privacy_notice_id")
    private UUID privacyNoticeId;

    @NonNull
    @MappedProperty("provider_id")
    private UUID providerId;

    @NonNull
    @MappedProperty("consumer_id")
    private UUID consumerId;

    /** Optional parent consent in a chain; {@code ON DELETE SET NULL}, never itself. */
    @Nullable
    @MappedProperty("parent_consent_id")
    private UUID parentConsentId;

    /** Lifecycle status; change only via {@link #updateStatus(ConsentStatus)}. */
    @NonNull private ConsentStatus status;

    /** Derived from {@code status}: {@code true} only when {@link ConsentStatus#GRANTED}. */
    private boolean consented;

    @Nullable
    @MappedProperty("contract_uri")
    private String contractUri;

    /** Frozen copy of the notice terms at consent time, unaffected by later notice changes. */
    @NonNull
    @TypeDef(type = DataType.JSON)
    private ConsentSnapshot snapshot;

    @DateCreated
    @MappedProperty("created_at")
    private Instant createdAt;

    @DateUpdated
    @MappedProperty("updated_at")
    private Instant updatedAt;

    /** For Micronaut Data; application code uses {@link #withStatus}. */
    public Consent() {}

    /** Creates a consent with a fresh UUIDv7 id and {@code consented} derived from the status. */
    public static Consent withStatus(
            @NonNull ConsentStatus status,
            @NonNull UUID userId,
            @NonNull UUID privacyNoticeId,
            @NonNull UUID providerId,
            @NonNull UUID consumerId,
            @Nullable UUID parentConsentId,
            @Nullable String contractUri,
            @NonNull ConsentSnapshot snapshot) {
        var consent = new Consent();
        consent.id = UuidGenerator.uuidV7();
        consent.userId = userId;
        consent.privacyNoticeId = privacyNoticeId;
        consent.providerId = providerId;
        consent.consumerId = consumerId;
        consent.parentConsentId = parentConsentId;
        consent.status = status;
        consent.consented = status.isConsented();
        consent.contractUri = contractUri;
        consent.snapshot = snapshot;
        return consent;
    }

    /** The only way to change a stored status; recomputes {@code consented} with it. */
    public void updateStatus(@NonNull ConsentStatus newStatus) {
        this.status = newStatus;
        this.consented = newStatus.isConsented();
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

    /** The user the consent concerns. */
    @NonNull
    public UUID getUserId() {
        return userId;
    }

    /** Sets the user. */
    public void setUserId(@NonNull UUID userId) {
        this.userId = userId;
    }

    /** The privacy notice the consent is given under. */
    @NonNull
    public UUID getPrivacyNoticeId() {
        return privacyNoticeId;
    }

    /** Sets the privacy notice. */
    public void setPrivacyNoticeId(@NonNull UUID privacyNoticeId) {
        this.privacyNoticeId = privacyNoticeId;
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

    /** The data consumer participant. */
    @NonNull
    public UUID getConsumerId() {
        return consumerId;
    }

    /** Sets the data consumer participant. */
    public void setConsumerId(@NonNull UUID consumerId) {
        this.consumerId = consumerId;
    }

    /** The parent consent; {@code null} for a root consent. */
    @Nullable
    public UUID getParentConsentId() {
        return parentConsentId;
    }

    /** Sets the parent consent, or clears it with {@code null}. */
    public void setParentConsentId(@Nullable UUID parentConsentId) {
        this.parentConsentId = parentConsentId;
    }

    /** The lifecycle status. */
    @NonNull
    public ConsentStatus getStatus() {
        return status;
    }

    /** Whether the consent is granted; always {@code status == GRANTED}. */
    public boolean isConsented() {
        return consented;
    }

    /** For Micronaut Data hydration only; see {@link #updateStatus(ConsentStatus)}. */
    public void setConsented(boolean consented) {
        this.consented = consented;
    }

    /** For Micronaut Data hydration only; see {@link #updateStatus(ConsentStatus)}. */
    public void setStatus(@NonNull ConsentStatus status) {
        this.status = status;
    }

    /** The contract URI; {@code null} if unset. */
    @Nullable
    public String getContractUri() {
        return contractUri;
    }

    /** Sets the contract URI, or clears it with {@code null}. */
    public void setContractUri(@Nullable String contractUri) {
        this.contractUri = contractUri;
    }

    /** The frozen notice terms. */
    @NonNull
    public ConsentSnapshot getSnapshot() {
        return snapshot;
    }

    /** Sets the frozen notice terms. */
    public void setSnapshot(@NonNull ConsentSnapshot snapshot) {
        this.snapshot = snapshot;
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
