package com.seamware.consentmanager.domain;

import io.micronaut.serde.annotation.Serdeable;

/** Lifecycle status of a consent; mirrors the {@code chk_consents_status} constraint. */
@Serdeable
public enum ConsentStatus {

    /** Consent has been created but not yet presented to the user. */
    PENDING,

    /** Consent is in draft state, awaiting finalization. */
    DRAFT,

    /** Consent has been actively granted by the user. */
    GRANTED,

    /** Consent was granted and then revoked by the user. */
    REVOKED,

    /** Consent has expired according to its retention period. */
    EXPIRED,

    /** Consent has been terminated by the system or an administrator. */
    TERMINATED,

    /** Consent request was explicitly refused by the user. */
    REFUSED;

    /** Whether this is {@link #GRANTED}, the sole source of a consent's {@code consented} flag. */
    public boolean isConsented() {
        return this == GRANTED;
    }
}
