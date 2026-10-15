package com.seamware.consentmanager.signing;

import io.micronaut.core.annotation.NonNull;

/**
 * A receipt beside its detached JWS, with the RFC 8785 text the JWS covers.
 *
 * <p>{@code canonicalJson} is what a stored receipt must keep: it verifies without re-serialising.
 */
public record SignedReceipt<R>(
        @NonNull R receipt,
        @NonNull String jws,
        @NonNull String keyId,
        @NonNull String canonicalJson) {}
