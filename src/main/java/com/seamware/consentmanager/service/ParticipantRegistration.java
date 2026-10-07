package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;
import java.util.Map;

/**
 * What an organization asks {@link ParticipantService#register} to record about itself, kept free
 * of the generated API models.
 *
 * <p>Carries no identifier: the participant being registered is the one the caller's token names,
 * and leaving it out here is what keeps a body-supplied identifier from ever reaching a column.
 * {@code endpoints} and {@code legalPerson} are the JSONB columns as they will be stored.
 */
public record ParticipantRegistration(
        String legalName,
        @Nullable String selfDescriptionUri,
        @Nullable String email,
        Map<String, Object> endpoints,
        @Nullable Map<String, Object> legalPerson) {}
