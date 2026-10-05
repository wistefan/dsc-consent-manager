package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;

/**
 * What a participant asks {@link UserService#registerForParticipant} to register, kept free of the
 * generated API models so the erasure and bulk paths can reuse the rule.
 *
 * <p>{@code identifier} is the global user identity and the only thing matching ever looks at. The
 * attributes are written solely on the insert that creates the user; {@code localIdentifier} lives
 * on the link and is refreshed whenever a later call supplies one.
 */
public record UserRegistration(
        String identifier,
        @Nullable String localIdentifier,
        @Nullable String email,
        @Nullable String firstName,
        @Nullable String lastName) {}
