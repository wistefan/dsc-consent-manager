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
 * Computes and checks the keyed verifier an erased record carries in place of the identifier it
 * lost.
 *
 * <p>The stored value is {@code <salt>.<mac>}, both base64url without padding, where {@code salt}
 * is 16 fresh random bytes per record and {@code mac} is {@code HMAC-SHA256(secret, salt ||
 * identifier)} over the salt's own base64url text followed by the identifier. The salt is what
 * keeps the value from being a stable correlator: two erasures of the same person produce different
 * verifiers, so nobody can link the retained rows by reading the column. An operator holding the
 * secret reproduces the value outside this service - {@code printf '%s'
 * "${VERIFIER%%.*}$IDENTIFIER" | openssl dgst -sha256 -hmac "$SECRET" -binary | basenc --base64url
 * | tr -d '='} - and compares it with {@code ${VERIFIER#*.}}. That answers "did this named person
 * consent?" for an identifier the asker already holds; it yields no identifier on its own, and
 * without the secret it is not computable at all. The secret therefore lives with the operator and
 * never in the database.
 *
 * <p>Yields nothing when no secret is configured, which is the default: erasure then stores no
 * verifier and nothing can ever be confirmed against that record.
 */
@Singleton
public class ErasureVerifier {

    /** MAC the verifier is computed with; part of the published contract, so not a free choice. */
    private static final String MAC_ALGORITHM = "HmacSHA256";

    /** Separates salt from MAC; outside the base64url alphabet, so the split is unambiguous. */
    private static final String FIELD_SEPARATOR = ".";

    /** Salt width, well past the point where two records could collide. */
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
     * A fresh verifier for an identifier, or {@code null} when this deployment configured no
     * secret. Salted, so two calls for the same identifier never agree - compare with {@link
     * #matches}.
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
     * Whether a stored verifier was computed from this identifier - the operator-facing check.
     * False for an unset secret, an absent verifier and a malformed one alike.
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

    /** The MAC half of the stored value, over the salt text followed by the identifier. */
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
