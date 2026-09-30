package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.DateUpdated;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a user in the consent management system.
 *
 * <p>A user is the data subject (PII principal) who grants or revokes consent. Each user is
 * uniquely identified by their {@code identifier} (e.g. a Keycloak subject ID) and may optionally
 * have an email address and name details.
 *
 * <p>Users are associated with one or more {@link Participant participants} via the {@link
 * UserParticipant} link entity. This entity maps to the {@code users} database table.
 */
@Serdeable
@MappedEntity("users")
public class User {

    /** Unique surrogate primary key. */
    @Id
    @GeneratedValue(GeneratedValue.Type.UUID)
    private UUID id;

    /** External unique identifier for the user (e.g. Keycloak subject ID). */
    @NonNull private String identifier;

    /** Optional email address of the user. */
    @Nullable private String email;

    /** Optional first name of the user. */
    @Nullable
    @MappedProperty("first_name")
    private String firstName;

    /** Optional last name of the user. */
    @Nullable
    @MappedProperty("last_name")
    private String lastName;

    /** Timestamp when this user record was created; set automatically on insert. */
    @DateCreated
    @MappedProperty("created_at")
    private Instant createdAt;

    /** Timestamp when this user record was last updated; set automatically on update. */
    @DateUpdated
    @MappedProperty("updated_at")
    private Instant updatedAt;

    /**
     * Default no-argument constructor required by Micronaut Data.
     *
     * <p>Use the parameterized constructor for application-level entity creation.
     */
    public User() {}

    /**
     * Creates a new user with the required identifier and optional profile fields.
     *
     * @param identifier the external unique identifier for the user (must not be {@code null})
     * @param email the optional email address
     * @param firstName the optional first name
     * @param lastName the optional last name
     */
    public User(
            @NonNull String identifier,
            @Nullable String email,
            @Nullable String firstName,
            @Nullable String lastName) {
        this.identifier = identifier;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    /**
     * Returns the surrogate primary key.
     *
     * @return the UUID primary key, or {@code null} if not yet persisted
     */
    @Nullable
    public UUID getId() {
        return id;
    }

    /**
     * Sets the surrogate primary key.
     *
     * @param id the UUID primary key
     */
    public void setId(@Nullable UUID id) {
        this.id = id;
    }

    /**
     * Returns the external unique identifier for the user.
     *
     * @return the identifier string (never {@code null} once persisted)
     */
    @NonNull
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Sets the external unique identifier for the user.
     *
     * @param identifier the identifier string (must not be {@code null})
     */
    public void setIdentifier(@NonNull String identifier) {
        this.identifier = identifier;
    }

    /**
     * Returns the optional email address of the user.
     *
     * @return the email address, or {@code null} if not set
     */
    @Nullable
    public String getEmail() {
        return email;
    }

    /**
     * Sets the email address of the user.
     *
     * @param email the email address, or {@code null} to clear
     */
    public void setEmail(@Nullable String email) {
        this.email = email;
    }

    /**
     * Returns the optional first name of the user.
     *
     * @return the first name, or {@code null} if not set
     */
    @Nullable
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the first name of the user.
     *
     * @param firstName the first name, or {@code null} to clear
     */
    public void setFirstName(@Nullable String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the optional last name of the user.
     *
     * @return the last name, or {@code null} if not set
     */
    @Nullable
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the last name of the user.
     *
     * @param lastName the last name, or {@code null} to clear
     */
    public void setLastName(@Nullable String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the timestamp when this user record was created.
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
     * Returns the timestamp when this user record was last updated.
     *
     * @return the last-updated timestamp
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Sets the last-updated timestamp. Normally managed by {@link DateUpdated}.
     *
     * @param updatedAt the last-updated timestamp
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
