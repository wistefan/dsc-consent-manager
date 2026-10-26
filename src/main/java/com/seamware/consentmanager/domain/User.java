package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.DateUpdated;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.UUID;

/**
 * The data subject (PII principal) who grants or revokes consent, unique by {@code identifier};
 * linked to participants through {@link UserParticipant}.
 */
@Serdeable
@MappedEntity("users")
public class User {

    /** Application-generated UUIDv7 primary key. */
    @Id private UUID id;

    /** External unique identifier, e.g. the Keycloak subject. */
    @NonNull private String identifier;

    @Nullable private String email;

    @Nullable
    @MappedProperty("first_name")
    private String firstName;

    @Nullable
    @MappedProperty("last_name")
    private String lastName;

    /**
     * Salted, keyed HMAC of the identifier held before erasure; {@code null} while live. Never read
     * by this service nor a lookup key; see {@code ErasureVerifier} and ADR-0007.
     */
    @Nullable
    @MappedProperty("erasure_verifier")
    private String erasureVerifier;

    @DateCreated
    @MappedProperty("created_at")
    private Instant createdAt;

    @DateUpdated
    @MappedProperty("updated_at")
    private Instant updatedAt;

    /** For Micronaut Data; application code uses the parameterized constructor. */
    public User() {}

    /** Creates a user with a fresh UUIDv7 id. */
    public User(
            @NonNull String identifier,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName) {
        this.id = UuidGenerator.uuidV7();
        this.identifier = identifier;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
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

    /** Keyed verifier of the pre-erasure identifier; {@code null} while live. */
    @Nullable
    public String getErasureVerifier() {
        return erasureVerifier;
    }

    /** Sets the erasure verifier, or clears it with {@code null}. */
    public void setErasureVerifier(@Nullable String erasureVerifier) {
        this.erasureVerifier = erasureVerifier;
    }

    /** The email address; {@code null} if unset. */
    @Nullable
    public String getEmail() {
        return email;
    }

    /** Sets the email address, or clears it with {@code null}. */
    public void setEmail(@Nullable String email) {
        this.email = email;
    }

    /** The first name; {@code null} if unset. */
    @Nullable
    public String getFirstName() {
        return firstName;
    }

    /** Sets the first name, or clears it with {@code null}. */
    public void setFirstName(@Nullable String firstName) {
        this.firstName = firstName;
    }

    /** The last name; {@code null} if unset. */
    @Nullable
    public String getLastName() {
        return lastName;
    }

    /** Sets the last name, or clears it with {@code null}. */
    public void setLastName(@Nullable String lastName) {
        this.lastName = lastName;
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
