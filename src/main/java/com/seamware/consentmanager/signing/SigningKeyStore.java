package com.seamware.consentmanager.signing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSSigner;
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
import io.micronaut.core.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receipt signing key and the public keys a receipt verifies against. Eager, so a bad key fails
 * startup; a message names the variable or mounted file at fault, never key material. See ADR-0014.
 */
@Context
public class SigningKeyStore {

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

    private static final String SIGNING_KEY_FILE_VARIABLE = "RECEIPTS_SIGNING_KEY_FILE";

    private static final String RETIRED_KEYS_VARIABLE = "RECEIPTS_RETIRED_VERIFICATION_KEYS";

    private static final String RETIRED_KEYS_FILE_VARIABLE =
            "RECEIPTS_RETIRED_VERIFICATION_KEYS_FILE";

    /** How a message names a file source: its variable, then the path it holds. */
    private static final String FILE_SOURCE = "%s (%s)";

    private static final String MISSING_KEY =
            "No receipt signing key is configured: set RECEIPTS_SIGNING_KEY_FILE, or"
                    + " RECEIPTS_SIGNING_KEY for local use, or allow a throwaway key with"
                    + " RECEIPTS_EPHEMERAL_SIGNING_KEY=true.";

    private static final String CONFLICTING_SOURCES = "Set either %s or %s, not both.";

    private static final String UNREADABLE_FILE = "%s cannot be read.";

    private static final String EMPTY_FILE = "%s is empty.";

    private static final String MALFORMED_KEY = "%s is not a JSON Web Key.";

    private static final String PUBLIC_KEY_ONLY =
            "%s holds no private key, so nothing can be signed with it.";

    private static final String UNSUPPORTED_KEY =
            "%s may hold only RSA keys and EC keys on P-256, P-384 or P-521.";

    private static final String MISMATCHED_ALGORITHM = "%s names alg %s, which its key cannot use.";

    private static final String UNUSABLE_KEY = "%s cannot sign with %s.";

    private static final String MALFORMED_RETIRED_KEYS = "%s is not a JSON Web Key Set.";

    private static final String PRIVATE_RETIRED_KEY =
            "%s must hold public keys only, since it is published.";

    private static final String DUPLICATE_KEY_ID =
            "%s publishes receipt verification key id %s more than once.";

    private static final String EPHEMERAL_KEY =
            "Signing receipts with a key generated at startup (kid {}): every receipt it signs"
                    + " stops verifying when this instance restarts. Set RECEIPTS_SIGNING_KEY_FILE,"
                    + " or RECEIPTS_SIGNING_KEY for local use.";

    private static final Logger LOG = LoggerFactory.getLogger(SigningKeyStore.class);

    private final JWSSigner signer;
    private final JWSAlgorithm algorithm;
    private final String keyId;
    private final List<JWK> verificationKeys;

    public SigningKeyStore(ConsentManagerConfiguration.Receipts settings) {
        Source signingKey =
                Source.resolve(
                        SIGNING_KEY_VARIABLE,
                        settings.getSigningKey(),
                        SIGNING_KEY_FILE_VARIABLE,
                        settings.getSigningKeyFile());
        JWK key = load(signingKey, settings.isEphemeralSigningKey());
        this.algorithm = algorithmFor(key, signingKey.name());
        this.keyId = keyIdOf(key);
        JWK publicKey;
        try {
            this.signer = new DefaultJWSSignerFactory().createJWSSigner(key, algorithm);
            publicKey = publish(key, algorithm, keyId);
        } catch (JOSEException | ParseException | IllegalArgumentException e) {
            // Nimbus refuses a short RSA key with IllegalArgumentException. The cause is dropped,
            // since a JOSE error may quote the key it rejected.
            throw new IllegalStateException(UNUSABLE_KEY.formatted(signingKey.name(), algorithm));
        }
        this.verificationKeys =
                verificationKeys(
                        publicKey,
                        Source.resolve(
                                RETIRED_KEYS_VARIABLE,
                                settings.getRetiredVerificationKeys(),
                                RETIRED_KEYS_FILE_VARIABLE,
                                settings.getRetiredVerificationKeysFile()));
    }

    /** Signs with the active private key, which never leaves this bean. */
    @NonNull
    public JWSSigner signer() {
        return signer;
    }

    /** The algorithm the active key signs with, as its published JWK names it. */
    @NonNull
    public JWSAlgorithm algorithm() {
        return algorithm;
    }

    /** The active key's {@code kid}: configured, else its RFC 7638 thumbprint. */
    @NonNull
    public String keyId() {
        return keyId;
    }

    /**
     * Every public key a receipt may verify against, naming {@code kid}, {@code use} and {@code
     * alg}: the active key first, then the retired ones.
     */
    @NonNull
    public List<JWK> verificationKeys() {
        return verificationKeys;
    }

    /** The published key whose {@code kid} is {@code keyId}; empty for an unknown or null one. */
    @NonNull
    public Optional<JWK> verificationKey(@Nullable String keyId) {
        return verificationKeys.stream().filter(key -> key.getKeyID().equals(keyId)).findFirst();
    }

    private static JWK load(Source configured, boolean ephemeralAllowed) {
        if (configured.value() == null) {
            if (!ephemeralAllowed) {
                throw new IllegalStateException(MISSING_KEY);
            }
            return ephemeral();
        }
        JWK key;
        try {
            key = JWK.parse(configured.value());
        } catch (ParseException e) {
            // The cause is dropped: a parse error may quote the secret it failed on.
            throw new IllegalStateException(MALFORMED_KEY.formatted(configured.name()));
        }
        if (!key.isPrivate()) {
            throw new IllegalStateException(PUBLIC_KEY_ONLY.formatted(configured.name()));
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
    private static List<JWK> verificationKeys(JWK active, Source retired) {
        List<JWK> keys = new ArrayList<>(List.of(active));
        Set<String> keyIds = new HashSet<>(Set.of(active.getKeyID()));
        for (JWK key : parseRetired(retired)) {
            // Checked before anything else touches the key, so a secret is never published.
            if (key.isPrivate()) {
                throw new IllegalStateException(PRIVATE_RETIRED_KEY.formatted(retired.name()));
            }
            String keyId = keyIdOf(key);
            if (!keyIds.add(keyId)) {
                throw new IllegalStateException(DUPLICATE_KEY_ID.formatted(retired.name(), keyId));
            }
            try {
                keys.add(publish(key, algorithmFor(key, retired.name()), keyId));
            } catch (ParseException e) {
                throw new IllegalStateException(MALFORMED_RETIRED_KEYS.formatted(retired.name()));
            }
        }
        return List.copyOf(keys);
    }

    private static List<JWK> parseRetired(Source retired) {
        if (retired.value() == null) {
            return List.of();
        }
        try {
            return JWKSet.parse(retired.value()).getKeys();
        } catch (ParseException e) {
            // The cause is dropped: a parse error may quote a private member it failed on.
            throw new IllegalStateException(MALFORMED_RETIRED_KEYS.formatted(retired.name()));
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

    /**
     * A configured key value and the name a message blames for it; {@code value} is null when
     * nothing is configured, never for a mounted file, so an empty mount cannot pass as unset.
     */
    private record Source(String name, @Nullable String value) {

        static Source resolve(
                String variable,
                @Nullable String inline,
                String fileVariable,
                @Nullable String path) {
            boolean hasInline = inline != null && !inline.isBlank();
            if (path == null || path.isBlank()) {
                return new Source(variable, hasInline ? inline : null);
            }
            if (hasInline) {
                throw new IllegalStateException(
                        CONFLICTING_SOURCES.formatted(fileVariable, variable));
            }
            String name = FILE_SOURCE.formatted(fileVariable, path);
            String content;
            try {
                content = Files.readString(Path.of(path), StandardCharsets.UTF_8).strip();
            } catch (IOException | InvalidPathException e) {
                // The cause is dropped, as for a parse error: a decoding error may quote content.
                throw new IllegalStateException(UNREADABLE_FILE.formatted(name));
            }
            if (content.isEmpty()) {
                throw new IllegalStateException(EMPTY_FILE.formatted(name));
            }
            return new Source(name, content);
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
