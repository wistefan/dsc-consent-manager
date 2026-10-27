package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;
import java.util.Map;

/**
 * A replacement self-description for {@link ParticipantService#update}; carries no identifier.
 * {@code PUT} semantics: a {@code null} optional field clears its column.
 */
public record ParticipantUpdate(
        String legalName,
        @Nullable String selfDescriptionUri,
        @Nullable String email,
        Map<String, Object> endpoints,
        @Nullable Map<String, Object> legalPerson) {}
