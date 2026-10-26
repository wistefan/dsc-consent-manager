package com.seamware.consentmanager.observability;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.context.annotation.Context;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stands in for a personal identifier in a log line at INFO or above, so lines about one person
 * correlate without naming them.
 *
 * <p>A keyed HMAC rather than a plain hash: without the key, a list of candidate emails or DIDs
 * cannot be hashed and matched against the logs, and an ADR-0007 erasure is not undone by them.
 * Eager, so a missing or short key fails startup; no message quotes the key.
 */
@Context
public class LogPseudonymizer {

    /** Hex characters a pseudonym keeps: 64 bits, ample to tell the people in one log apart. */
    public static final int PSEUDONYM_LENGTH = 16;

    /** Shortest key accepted, in UTF-8 bytes: the 256-bit strength of the MAC itself. */
    public static final int MINIMUM_KEY_BYTES = 32;

    private static final String MAC_ALGORITHM = "HmacSHA256";

    private static final String MISSING_KEY =
            "consent-manager.logging.pseudonym-key (LOG_PSEUDONYM_KEY) is not set; log lines"
                    + " cannot pseudonymise the people they concern";

    private static final String SHORT_KEY =
            "consent-manager.logging.pseudonym-key (LOG_PSEUDONYM_KEY) must be at least %d bytes"
                    .formatted(MINIMUM_KEY_BYTES);

    private final SecretKeySpec key;

    /** Fails with {@link IllegalStateException} when the configured key is unusable. */
    public LogPseudonymizer(ConsentManagerConfiguration.Logging configuration) {
        String secret = configuration.getPseudonymKey();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(MISSING_KEY);
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(SHORT_KEY);
        }
        this.key = new SecretKeySpec(bytes, MAC_ALGORITHM);
    }

    /**
     * The identifier's stable pseudonym under this key, as {@link #PSEUDONYM_LENGTH} hex digits.
     */
    public String pseudonym(String identifier) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(identifier.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, PSEUDONYM_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A log pseudonym could not be computed", e);
        }
    }
}
