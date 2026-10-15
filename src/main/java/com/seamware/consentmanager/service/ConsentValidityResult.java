package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import io.micronaut.core.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * The outcome of {@code GET /consents/check}: whether a subject's consent is in force, and the
 * grant the answer rests on when one was found.
 *
 * <p>{@code grant} is the {@code GRANTED} consent the caller is a party to, or null when none was
 * found - and a null grant is deliberately the same whether the subject is unknown or merely holds
 * no consent, so the result discloses nothing about which. {@code reason} is present exactly when
 * {@code valid} is false. {@code checkedAt} is stamped when the check was made, so a cached answer
 * carries its own age.
 */
public record ConsentValidityResult(
        boolean valid,
        @Nullable Consent grant,
        @Nullable String reason,
        OffsetDateTime checkedAt) {}
