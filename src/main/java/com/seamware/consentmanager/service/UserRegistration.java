package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;

/**
 * What a participant asks {@link UserService#registerForParticipant} to register. Only {@code
 * identifier} is matched; attributes are written only on insert, while {@code localIdentifier}
 * lives on the link and is refreshed whenever supplied.
 */
public record UserRegistration(
        String identifier,
        @Nullable String localIdentifier,
        @Nullable String email,
        @Nullable String firstName,
        @Nullable String lastName) {}
