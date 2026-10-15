package com.seamware.consentmanager.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.factories.DefaultJWSSignerFactory;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKParameterNames;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.erdtman.jcs.JsonCanonicalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signs consent receipts with this deployment's receipt key and holds the public half it publishes.
 *
 * <p>A signature is a detached compact JWS (RFC 7515 Appendix F) over the RFC 8785 canonical form
 * of the receipt without its {@value #SIGNATURE_MEMBER} member. A {@link Context} bean, so a
 * missing or unusable key fails startup rather than the first receipt read.
 */
@Context
public class ReceiptSigner {

    /** Receipt member carrying the signature, which is never part of what it signs. */
    public static final String SIGNATURE_MEMBER = "signature";

    /** Curve of the key generated when none is configured and an ephemeral one is allowed. */
    static final Curve EPHEMERAL_CURVE = Curve.P_256;

    /** The JWS algorithm each supported EC curve signs with. */
    private static final Map<Curve, JWSAlgorithm> EC_ALGORITHMS =
            Map.of(
                    Curve.P_256, JWSAlgorithm.ES256,
                    Curve.P_384, JWSAlgorithm.ES384,
                    Curve.P_521, JWSAlgorithm.ES512);

    /** The JWS algorithm an RSA key signs with when its JWK names none. */
    private static final JWSAlgorithm RSA_ALGORITHM = JWSAlgorithm.RS256;

    private static final String MISSING_KEY =
            "No receipt signing key is configured: set RECEIPTS_SIGNING_KEY, or allow a throwaway"
                    + " key with RECEIPTS_EPHEMERAL_SIGNING_KEY=true.";

    private static final String MALFORMED_KEY = "RECEIPTS_SIGNING_KEY is not a JSON Web Key.";

    private static final String PUBLIC_KEY_ONLY =
            "RECEIPTS_SIGNING_KEY holds no private key, so nothing can be signed with it.";

    private static final String UNSUPPORTED_KEY =
            "RECEIPTS_SIGNING_KEY must be an RSA key or an EC key on P-256, P-384 or P-521.";

    private static final String UNUSABLE_KEY = "RECEIPTS_SIGNING_KEY cannot sign with %s.";

    private static final String EPHEMERAL_KEY =
            "Signing receipts with a key generated at startup (kid {}): every receipt it signs"
                    + " stops verifying when this instance restarts. Set RECEIPTS_SIGNING_KEY.";

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptSigner.class);

    private final JsonMapper json;
    private final JWSHeader header;
    private final JWSSigner signer;
    private final JWK publicKey;

    public ReceiptSigner(ConsentManagerConfiguration.Receipts settings, JsonMapper json) {
        this.json = json;
        JWK key = load(settings);
        JWSAlgorithm algorithm = algorithmFor(key);
        String keyId = keyIdOf(key);
        try {
            this.signer = new DefaultJWSSignerFactory().createJWSSigner(key, algorithm);
            this.publicKey = publish(key, algorithm, keyId);
        } catch (JOSEException | ParseException e) {
            throw new IllegalStateException(UNUSABLE_KEY.formatted(algorithm), e);
        }
        this.header = new JWSHeader.Builder(algorithm).keyID(keyId).build();
    }

    /**
     * The detached JWS of {@code receipt}; a {@value #SIGNATURE_MEMBER} it already carries is
     * ignored.
     */
    @NonNull
    public String sign(@NonNull Object receipt) {
        JWSObject jws = new JWSObject(header, new Payload(canonicalForm(receipt)));
        try {
            jws.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a consent receipt", e);
        }
        return jws.serialize(true);
    }

    /** The public half of the signing key, naming the {@code kid}, {@code use} and {@code alg}. */
    @NonNull
    public JWK publicKey() {
        return publicKey;
    }

    private byte[] canonicalForm(Object receipt) {
        try {
            Map<String, JsonNode> members = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> member : json.writeValueToTree(receipt).entries()) {
                if (!SIGNATURE_MEMBER.equals(member.getKey())) {
                    members.put(member.getKey(), member.getValue());
                }
            }
            return new JsonCanonicalizer(json.writeValueAsBytes(JsonNode.createObjectNode(members)))
                    .getEncodedUTF8();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not canonicalize a consent receipt", e);
        }
    }

    private static JWK load(ConsentManagerConfiguration.Receipts settings) {
        String configured = settings.getSigningKey();
        if (configured == null || configured.isBlank()) {
            if (!settings.isEphemeralSigningKey()) {
                throw new IllegalStateException(MISSING_KEY);
            }
            return ephemeral();
        }
        JWK key;
        try {
            key = JWK.parse(configured);
        } catch (ParseException e) {
            // The cause is dropped: a parse error may quote the secret it failed on.
            throw new IllegalStateException(MALFORMED_KEY);
        }
        if (!key.isPrivate()) {
            throw new IllegalStateException(PUBLIC_KEY_ONLY);
        }
        return key;
    }

    private static JWK ephemeral() {
        try {
            JWK key = new ECKeyGenerator(EPHEMERAL_CURVE).generate();
            LOG.warn(EPHEMERAL_KEY, keyIdOf(key));
            return key;
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate a receipt signing key", e);
        }
    }

    /** The JWK's own {@code alg}, else the default for its type; only EC and RSA are published. */
    private static JWSAlgorithm algorithmFor(JWK key) {
        JWSAlgorithm algorithm =
                switch (key) {
                    case ECKey ec -> EC_ALGORITHMS.get(ec.getCurve());
                    case RSAKey ignored -> RSA_ALGORITHM;
                    default -> null;
                };
        if (algorithm == null) {
            throw new IllegalStateException(UNSUPPORTED_KEY);
        }
        return key.getAlgorithm() == null
                ? algorithm
                : JWSAlgorithm.parse(key.getAlgorithm().getName());
    }

    /** The configured {@code kid}, else the RFC 7638 thumbprint, which is stable per key. */
    private static String keyIdOf(JWK key) {
        if (key.getKeyID() != null) {
            return key.getKeyID();
        }
        try {
            return key.computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not derive a receipt signing key id", e);
        }
    }

    private static JWK publish(JWK key, JWSAlgorithm algorithm, String keyId)
            throws ParseException {
        Map<String, Object> members = new LinkedHashMap<>(key.toPublicJWK().toJSONObject());
        members.put(JWKParameterNames.PUBLIC_KEY_USE, KeyUse.SIGNATURE.identifier());
        members.put(JWKParameterNames.ALGORITHM, algorithm.getName());
        members.put(JWKParameterNames.KEY_ID, keyId);
        return JWK.parse(members);
    }
}
