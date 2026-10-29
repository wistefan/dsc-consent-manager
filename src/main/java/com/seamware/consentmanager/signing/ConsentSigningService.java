package com.seamware.consentmanager.signing;

import io.micronaut.core.annotation.NonNull;

/**
 * Signs consent documents with a detached JWS and checks one against the published keys. The
 * untyped methods mean {@link SignatureType#RECEIPT}.
 */
public interface ConsentSigningService {

    /**
     * Signs the RFC 8785 form of the whole {@code document} as {@code type}. Throws {@link
     * ReceiptSigningException} rather than ever returning an unsigned result.
     */
    @NonNull
    <R> SignedReceipt<R> sign(@NonNull R document, @NonNull SignatureType type);

    /** Signs a receipt; see {@link #sign(Object, SignatureType)}. */
    @NonNull
    default <R> SignedReceipt<R> sign(@NonNull R receipt) {
        return sign(receipt, SignatureType.RECEIPT);
    }

    /** Whether {@code detachedJws} verifies over the canonical {@code receipt}; never throws. */
    boolean verify(@NonNull Object receipt, @NonNull String detachedJws);

    /**
     * Whether {@code detachedJws} verifies over exactly {@code canonicalJson} and is typed {@code
     * type}; never throws.
     */
    boolean verifyCanonical(
            @NonNull String canonicalJson,
            @NonNull String detachedJws,
            @NonNull SignatureType type);

    /** Whether a receipt signature verifies over exactly {@code canonicalJson}; never throws. */
    default boolean verifyCanonical(@NonNull String canonicalJson, @NonNull String detachedJws) {
        return verifyCanonical(canonicalJson, detachedJws, SignatureType.RECEIPT);
    }
}
