package com.seamware.consentmanager.signing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.jwk.AsymmetricJWK;
import com.nimbusds.jose.jwk.JWK;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Optional;

/**
 * Detached compact JWS (RFC 7515 Appendix F) over the RFC 8785 form of a receipt, signed with the
 * {@link SigningKeyStore}'s active key; the header names {@code alg}, {@code kid} and {@code typ}.
 */
@Singleton
public class JwsConsentSigningService implements ConsentSigningService {

    private static final String SIGNING_FAILED = "Could not sign a consent receipt";

    private final SigningKeyStore keys;
    private final CanonicalJsonSerializer canonical;
    private final JWSHeader header;

    public JwsConsentSigningService(SigningKeyStore keys, CanonicalJsonSerializer canonical) {
        this.keys = keys;
        this.canonical = canonical;
        this.header =
                new JWSHeader.Builder(keys.algorithm())
                        .type(JOSEObjectType.JOSE)
                        .keyID(keys.keyId())
                        .build();
    }

    @Override
    @NonNull
    public <R> SignedReceipt<R> sign(@NonNull R receipt) {
        try {
            String text = canonical.toJson(receipt);
            JWSObject jws =
                    new JWSObject(header, new Payload(text.getBytes(StandardCharsets.UTF_8)));
            jws.sign(keys.signer());
            return new SignedReceipt<>(receipt, jws.serialize(true), keys.keyId(), text);
        } catch (JOSEException | RuntimeException e) {
            throw new ReceiptSigningException(SIGNING_FAILED, e);
        }
    }

    @Override
    public boolean verify(@NonNull Object receipt, @NonNull String detachedJws) {
        try {
            return verify(canonical.toBytes(receipt), detachedJws);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean verifyCanonical(@NonNull String canonicalJson, @NonNull String detachedJws) {
        return verify(canonicalJson.getBytes(StandardCharsets.UTF_8), detachedJws);
    }

    /**
     * Re-attaches {@code payload} and checks the signature with the key the header's {@code kid}
     * names, which must publish the header's {@code alg}. An attached payload is refused.
     */
    private boolean verify(byte[] payload, String detachedJws) {
        try {
            JWSObject jws = JWSObject.parse(detachedJws, new Payload(payload));
            Optional<JWK> key = keys.verificationKey(jws.getHeader().getKeyID());
            if (key.isEmpty() || !key.get().getAlgorithm().equals(jws.getHeader().getAlgorithm())) {
                return false;
            }
            return jws.verify(
                    new DefaultJWSVerifierFactory()
                            .createJWSVerifier(
                                    jws.getHeader(), ((AsymmetricJWK) key.get()).toPublicKey()));
        } catch (ParseException | JOSEException | RuntimeException e) {
            return false;
        }
    }
}
