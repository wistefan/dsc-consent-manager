package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import io.micronaut.core.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * Outcome of {@code GET /consents/check}: whether a subject's consent is in force, and its grant. A
 * null {@code grant} is the same for an unknown subject and one holding no consent, so neither is
 * disclosed; {@code reason} is present exactly when {@code valid} is false.
 */
public record ConsentValidityResult(
        boolean valid,
        @Nullable Consent grant,
        @Nullable String reason,
        OffsetDateTime checkedAt) {}
