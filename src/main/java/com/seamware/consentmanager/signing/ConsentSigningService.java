package com.seamware.consentmanager.signing;

import io.micronaut.core.annotation.NonNull;

/** Signs consent receipts with a detached JWS and checks one against the published keys. */
public interface ConsentSigningService {

    /**
     * Signs the RFC 8785 form of the whole {@code receipt}. Throws {@link ReceiptSigningException}
     * rather than ever returning an unsigned result.
     */
    @NonNull
    <R> SignedReceipt<R> sign(@NonNull R receipt);

    /**
     * Whether {@code detachedJws} verifies over the canonical form of {@code receipt}; never
     * throws.
     */
    boolean verify(@NonNull Object receipt, @NonNull String detachedJws);

    /** Whether {@code detachedJws} verifies over exactly {@code canonicalJson}; never throws. */
    boolean verifyCanonical(@NonNull String canonicalJson, @NonNull String detachedJws);
}
