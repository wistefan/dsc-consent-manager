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
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.erdtman.jcs.JsonCanonicalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signs consent receipts with this deployment's receipt key and holds the public keys it publishes.
 *
 * <p>A signature is a detached compact JWS (RFC 7515 Appendix F) over the RFC 8785 canonical form
 * of the whole receipt, carried beside it. A {@link Context} bean, so a missing or unusable key
 * fails startup rather than the first receipt read.
 */
@Context
public class ReceiptSigner {

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

    private static final String SIGNING_KEY_VARIABLE = "RECEIPTS_SIGNING_KEY";

    private static final String RETIRED_KEYS_VARIABLE = "RECEIPTS_RETIRED_VERIFICATION_KEYS";

    private static final String MISSING_KEY =
            "No receipt signing key is configured: set RECEIPTS_SIGNING_KEY, or allow a throwaway"
                    + " key with RECEIPTS_EPHEMERAL_SIGNING_KEY=true.";

    private static final String MALFORMED_KEY = "RECEIPTS_SIGNING_KEY is not a JSON Web Key.";

    private static final String PUBLIC_KEY_ONLY =
            "RECEIPTS_SIGNING_KEY holds no private key, so nothing can be signed with it.";

    private static final String UNSUPPORTED_KEY =
            "%s may hold only RSA keys and EC keys on P-256, P-384 or P-521.";

    private static final String MISMATCHED_ALGORITHM = "%s names alg %s, which its key cannot use.";

    private static final String UNUSABLE_KEY = "RECEIPTS_SIGNING_KEY cannot sign with %s.";

    private static final String MALFORMED_RETIRED_KEYS =
            "RECEIPTS_RETIRED_VERIFICATION_KEYS is not a JSON Web Key Set.";

    private static final String PRIVATE_RETIRED_KEY =
            "RECEIPTS_RETIRED_VERIFICATION_KEYS must hold public keys only, since it is published.";

    private static final String DUPLICATE_KEY_ID =
            "Receipt verification key id %s is published more than once.";

    private static final String EPHEMERAL_KEY =
            "Signing receipts with a key generated at startup (kid {}): every receipt it signs"
                    + " stops verifying when this instance restarts. Set RECEIPTS_SIGNING_KEY.";

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptSigner.class);

    private final JsonMapper json;
    private final JWSHeader header;
    private final JWSSigner signer;
    private final JWK publicKey;
    private final List<JWK> verificationKeys;

    public ReceiptSigner(ConsentManagerConfiguration.Receipts settings, JsonMapper json) {
        this.json = json;
        JWK key = load(settings);
        JWSAlgorithm algorithm = algorithmFor(key, SIGNING_KEY_VARIABLE);
        String keyId = keyIdOf(key);
        try {
            this.signer = new DefaultJWSSignerFactory().createJWSSigner(key, algorithm);
            this.publicKey = publish(key, algorithm, keyId);
        } catch (JOSEException | ParseException e) {
            throw new IllegalStateException(UNUSABLE_KEY.formatted(algorithm), e);
        }
        this.header = new JWSHeader.Builder(algorithm).keyID(keyId).build();
        this.verificationKeys = verificationKeys(publicKey, settings.getRetiredVerificationKeys());
    }

    /** The detached JWS over the RFC 8785 form of the whole {@code receipt}. */
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

    /**
     * Every key a receipt may verify against: {@link #publicKey()} first, then the retired ones.
     */
    @NonNull
    public List<JWK> verificationKeys() {
        return verificationKeys;
    }

    private byte[] canonicalForm(Object receipt) {
        try {
            return new JsonCanonicalizer(json.writeValueAsBytes(receipt)).getEncodedUTF8();
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

    /**
     * The active key followed by the retired ones, so receipts signed before a rotation keep
     * verifying. A {@code kid} is how a verifier selects its key, so none may repeat.
     */
    private static List<JWK> verificationKeys(JWK active, String retired) {
        List<JWK> keys = new ArrayList<>(List.of(active));
        Set<String> keyIds = new HashSet<>(Set.of(active.getKeyID()));
        for (JWK key : parseRetired(retired)) {
            // Checked before anything else touches the key, so a secret is never published.
            if (key.isPrivate()) {
                throw new IllegalStateException(PRIVATE_RETIRED_KEY);
            }
            String keyId = keyIdOf(key);
            if (!keyIds.add(keyId)) {
                throw new IllegalStateException(DUPLICATE_KEY_ID.formatted(keyId));
            }
            try {
                keys.add(publish(key, algorithmFor(key, RETIRED_KEYS_VARIABLE), keyId));
            } catch (ParseException e) {
                throw new IllegalStateException(MALFORMED_RETIRED_KEYS, e);
            }
        }
        return List.copyOf(keys);
    }

    private static List<JWK> parseRetired(String retired) {
        if (retired == null || retired.isBlank()) {
            return List.of();
        }
        try {
            return JWKSet.parse(retired).getKeys();
        } catch (ParseException e) {
            // The cause is dropped: a parse error may quote a private member it failed on.
            throw new IllegalStateException(MALFORMED_RETIRED_KEYS);
        }
    }

    /**
     * The JWK's own {@code alg}, else the default for its type; only EC and RSA are published, and
     * an EC key signs only with its curve's algorithm.
     */
    private static JWSAlgorithm algorithmFor(JWK key, String source) {
        JWSAlgorithm algorithm =
                switch (key) {
                    case ECKey ec -> EC_ALGORITHMS.get(ec.getCurve());
                    case RSAKey ignored -> RSA_ALGORITHM;
                    default -> null;
                };
        if (algorithm == null) {
            throw new IllegalStateException(UNSUPPORTED_KEY.formatted(source));
        }
        if (key.getAlgorithm() == null) {
            return algorithm;
        }
        JWSAlgorithm named = JWSAlgorithm.parse(key.getAlgorithm().getName());
        boolean usable =
                key instanceof RSAKey
                        ? JWSAlgorithm.Family.RSA.contains(named)
                        : named.equals(algorithm);
        if (!usable) {
            throw new IllegalStateException(MISMATCHED_ALGORITHM.formatted(source, named));
        }
        return named;
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
