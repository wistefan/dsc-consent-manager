package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes the keyed verifier an erased record carries in place of the identifier it lost.
 *
 * <p>The value is {@code base64url(HMAC-SHA256(secret, identifier))} without padding, which an
 * operator can reproduce outside this service - {@code printf '%s' "$IDENTIFIER" | openssl dgst
 * -sha256 -hmac "$SECRET" -binary | basenc --base64url | tr -d '='} - and compare against {@code
 * users.erasure_verifier}. That answers "did this named person consent?" for an identifier the
 * asker already holds; it yields no identifier on its own, and without the secret it is not
 * computable at all. The secret therefore lives with the operator and never in the database.
 *
 * <p>Returns {@code null} when no secret is configured, which is the default: erasure then stores
 * no verifier and nothing can ever be confirmed against that record.
 */
@Singleton
public class ErasureVerifier {

    /** MAC the verifier is computed with; part of the published contract, so not a free choice. */
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private static final String NO_SECRET_WARNING =
            "No consent-manager.erasure.verification-secret is configured: erased records will "
                    + "carry no verifier, so no consent they retain can ever be confirmed against "
                    + "a named person.";

    private static final Logger LOG = LoggerFactory.getLogger(ErasureVerifier.class);

    @Nullable private final SecretKeySpec key;

    public ErasureVerifier(ConsentManagerConfiguration.Erasure configuration) {
        String secret = configuration.getVerificationSecret();
        this.key =
                secret == null || secret.isBlank()
                        ? null
                        : new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM);
        if (this.key == null) {
            LOG.warn(NO_SECRET_WARNING);
        }
    }

    /**
     * The verifier for an identifier, or {@code null} when this deployment configured no secret.
     */
    @Nullable
    public String verifierFor(String identifier) {
        if (key == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(key);
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(identifier.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The erasure verifier could not be computed", e);
        }
    }
}
