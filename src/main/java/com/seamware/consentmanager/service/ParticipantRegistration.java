package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;
import java.util.Map;

/**
 * What an organization asks {@link ParticipantService#register} to record about itself. Carries no
 * identifier, so only the token's identifier can ever reach the column.
 */
public record ParticipantRegistration(
        String legalName,
        @Nullable String selfDescriptionUri,
        @Nullable String email,
        Map<String, Object> endpoints,
        @Nullable Map<String, Object> legalPerson) {}
