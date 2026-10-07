package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;
import java.util.Map;

/**
 * A replacement self-description for {@link ParticipantService#update}, kept free of the generated
 * API models.
 *
 * <p>Carries no identifier, so no update can rename a participant and orphan the consents keyed on
 * the old name. A {@code null} optional field clears the column rather than leaving it: the body
 * this mirrors is a {@code PUT}, not a patch. {@code endpoints} and {@code legalPerson} are the
 * JSONB columns as they will be stored.
 */
public record ParticipantUpdate(
        String legalName,
        @Nullable String selfDescriptionUri,
        @Nullable String email,
        Map<String, Object> endpoints,
        @Nullable Map<String, Object> legalPerson) {}
