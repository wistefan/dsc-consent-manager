package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes and checks the keyed verifier an erased record carries instead of its identifier.
 *
 * <p>Stored as {@code <salt>.<mac>} (base64url, unpadded), {@code mac = HMAC-SHA256(secret,
 * saltText || identifier)}. The per-record salt keeps it from being a correlator; the secret lives
 * with the operator, never in the database. With no secret (the default) no verifier is stored. See
 * ADR-0007 and docs/security.md.
 */
@Singleton
public class ErasureVerifier {

    /** MAC algorithm; part of the published verifier format. */
    private static final String MAC_ALGORITHM = "HmacSHA256";

    /** Salt/MAC separator; outside the base64url alphabet, so the split is unambiguous. */
    private static final String FIELD_SEPARATOR = ".";

    /** Salt width in bytes. */
    private static final int SALT_BYTES = 16;

    private static final String NO_SECRET_MESSAGE =
            "No consent-manager.erasure.verification-secret is configured: erased records will "
                    + "carry no verifier, so no consent they retain can ever be confirmed against "
                    + "a named person.";

    private static final Logger LOG = LoggerFactory.getLogger(ErasureVerifier.class);

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();

    @Nullable private final SecretKeySpec key;

    public ErasureVerifier(ConsentManagerConfiguration.Erasure configuration) {
        String secret = configuration.getVerificationSecret();
        this.key =
                secret == null || secret.isBlank()
                        ? null
                        : new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM);
        if (this.key == null) {
            // The privacy-preserving default, not a misconfiguration.
            LOG.info(NO_SECRET_MESSAGE);
        }
    }

    /**
     * A fresh salted verifier for the identifier, or {@code null} when no secret is configured. Two
     * calls never agree; compare with {@link #matches}.
     */
    @Nullable
    public String verifierFor(String identifier) {
        if (key == null) {
            return null;
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        String encodedSalt = ENCODER.encodeToString(salt);
        return encodedSalt + FIELD_SEPARATOR + mac(encodedSalt, identifier);
    }

    /**
     * Whether a stored verifier was computed from this identifier. False for an unset secret and an
     * absent or malformed verifier.
     */
    public boolean matches(@Nullable String verifier, String identifier) {
        if (key == null || verifier == null) {
            return false;
        }
        int separator = verifier.indexOf(FIELD_SEPARATOR);
        if (separator <= 0 || separator == verifier.length() - 1) {
            return false;
        }
        String encodedSalt = verifier.substring(0, separator);
        return MessageDigest.isEqual(
                verifier.substring(separator + 1).getBytes(StandardCharsets.UTF_8),
                mac(encodedSalt, identifier).getBytes(StandardCharsets.UTF_8));
    }

    /** The MAC half of the stored value. */
    private String mac(String encodedSalt, String identifier) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(key);
            return ENCODER.encodeToString(
                    mac.doFinal((encodedSalt + identifier).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The erasure verifier could not be computed", e);
        }
    }
}
