package com.seamware.consentmanager.signing;

import io.micronaut.core.annotation.NonNull;

/**
 * A receipt beside its detached JWS and the RFC 8785 text the JWS covers. Store {@code
 * canonicalJson}: it verifies without re-serialising.
 */
public record SignedReceipt<R>(
        @NonNull R receipt,
        @NonNull String jws,
        @NonNull String keyId,
        @NonNull String canonicalJson) {}
