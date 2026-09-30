package com.seamware.consentmanager.domain;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Represents the lifecycle status of a consent record.
 *
 * <p>These seven states model the complete consent lifecycle from initial request through
 * termination. The values correspond exactly to the CHECK constraint {@code chk_consents_status} in
 * the {@code consents} database table.
 *
 * <p>The {@code consented} boolean on a consent entity is {@code true} only when the status is
 * {@link #GRANTED}; for all other statuses it is {@code false}.
 */
@Serdeable
public enum ConsentStatus {

    /** Consent has been created but not yet presented to the user. */
    PENDING,

    /** Consent is in draft state, awaiting finalization. */
    DRAFT,

    /** Consent has been actively granted by the user. */
    GRANTED,

    /** Consent was previously granted but has been revoked by the user. */
    REVOKED,

    /** Consent has expired according to its retention period. */
    EXPIRED,

    /** Consent has been terminated by the system or an administrator. */
    TERMINATED,

    /** Consent request was explicitly refused by the user. */
    REFUSED;

    /**
     * Returns {@code true} if this status represents an actively granted consent.
     *
     * <p>Only {@link #GRANTED} returns {@code true}; all other statuses return {@code false}. This
     * mirrors the business rule that the {@code consented} flag on a consent entity is derived
     * solely from the status value.
     *
     * @return {@code true} if this status is {@link #GRANTED}, {@code false} otherwise
     */
    public boolean isConsented() {
        return this == GRANTED;
    }
}
