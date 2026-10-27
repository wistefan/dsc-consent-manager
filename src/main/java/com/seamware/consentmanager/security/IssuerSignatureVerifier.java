package com.seamware.consentmanager.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.async.annotation.SingleResult;
import io.micronaut.security.token.jwt.nimbus.ReactiveJwksSignature;
import io.micronaut.security.token.jwt.signature.jwks.JwkSetFetcher;
import io.micronaut.security.token.jwt.signature.jwks.JwkValidator;
import jakarta.inject.Singleton;
import java.text.ParseException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Checks a token's signature against the JWK Set of the issuer it claims; makes no claim checks.
 *
 * <p>Security invariant: the issuer is resolved through the trust list first and the token is
 * offered only to that provider's keys, so a key valid at provider B never satisfies a token
 * claiming A. Fetching, caching ({@code micronaut.caches.jwks.expire-after-write}), {@code kid}
 * matching and verification are the module's {@link ReactiveJwksSignature}; see ADR-0004. A failed
 * fetch is never cached, so a provider whose key set is unreadable is skipped for {@link
 * #KEY_SET_UNAVAILABLE_COOLDOWN} rather than hit once per inbound token.
 */
@Singleton
public class IssuerSignatureVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(IssuerSignatureVerifier.class);

    /** Counter of signature checks that reached a provider's key set, tagged by provider. */
    public static final String SIGNATURE_VERIFICATION_METRIC =
            "consentmanager.token.signature.verifications";

    /** Tag carrying the configured provider name. */
    public static final String PROVIDER_TAG = "provider";

    /** Tag separating accepted signatures from rejected ones. */
    public static final String OUTCOME_TAG = "outcome";

    /** Value of {@link #OUTCOME_TAG} for a token whose signature verified. */
    public static final String OUTCOME_VERIFIED = "verified";

    /**
     * Value of {@link #OUTCOME_TAG} for a token the provider's keys did not verify. Worth alerting
     * on: an uncaught key rotation or forged tokens; the WARN line's {@code kid} tells them apart.
     */
    public static final String OUTCOME_REJECTED = "rejected";

    /**
     * Value of {@link #OUTCOME_TAG} for an unreadable key set; separate from {@link
     * #OUTCOME_REJECTED} so an outage does not look like forgery. The caller sees {@code false}.
     */
    public static final String OUTCOME_UNAVAILABLE = "unavailable";

    /**
     * How long a provider with an unreadable key set is left alone; its tokens are rejected
     * meanwhile. Bounds outbound requests to a failing JWK Set endpoint.
     */
    static final Duration KEY_SET_UNAVAILABLE_COOLDOWN = Duration.ofSeconds(10);

    private final IdentityProviderRegistry registry;
    private final JwkValidator jwkValidator;
    private final JwkSetFetcher<JWKSet> jwkSetFetcher;
    private final MeterRegistry meterRegistry;

    /** Per-provider state by entry name; bounded by the fixed trust list, not by traffic. */
    private final Map<String, ProviderState> stateByProvider = new ConcurrentHashMap<>();

    /** Creates the verifier; a {@code null} meter registry is replaced by a throwaway one. */
    public IssuerSignatureVerifier(
            IdentityProviderRegistry registry,
            JwkValidator jwkValidator,
            JwkSetFetcher<JWKSet> jwkSetFetcher,
            @Nullable MeterRegistry meterRegistry) {
        this.registry = registry;
        this.jwkValidator = jwkValidator;
        this.jwkSetFetcher = jwkSetFetcher;
        this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
    }

    /**
     * Emits {@code true} only if a key the issuer currently publishes signed the token.
     * Non-blocking; compose, never await. An unknown, unresolved or cooling-down issuer emits
     * {@code false} with no outbound request, indistinguishable from a bad signature.
     */
    @SingleResult
    public Publisher<Boolean> verify(@Nullable String issuer, SignedJWT jwt) {
        Optional<ResolvedIdentityProvider> provider =
                issuer == null ? Optional.empty() : registry.findByIssuer(issuer);
        if (provider.isEmpty()) {
            LOG.debug("No resolved identity provider is registered for the token's issuer");
            return Mono.just(Boolean.FALSE);
        }
        if (!issuer.equals(claimedIssuer(jwt))) {
            LOG.warn(
                    "A signature check was routed to provider '{}' for a token that claims a"
                            + " different issuer; refusing to check it against those keys",
                    provider.get().name());
            return Mono.just(Boolean.FALSE);
        }
        ResolvedIdentityProvider resolved = provider.get();
        ProviderState state = stateOf(resolved);
        if (state.isUnavailable()) {
            return Mono.just(record(resolved, jwt, OUTCOME_UNAVAILABLE));
        }
        ReactiveJwksSignature signature =
                new ReactiveJwksSignature(
                        new JwksSignatureConfigurationAdapter(resolved),
                        jwkValidator,
                        jwkSetFetcher);
        return Mono.from(signature.verify(jwt))
                .map(verified -> verified ? OUTCOME_VERIFIED : OUTCOME_REJECTED)
                .onErrorResume(error -> Mono.just(unreadable(resolved, error)))
                .defaultIfEmpty(OUTCOME_UNAVAILABLE)
                .map(outcome -> record(resolved, jwt, outcome));
    }

    /** The provider's state, created on first use. */
    private ProviderState stateOf(ResolvedIdentityProvider provider) {
        return stateByProvider.computeIfAbsent(provider.name(), name -> new ProviderState());
    }

    /** The untrusted {@code iss} claim, or {@code null} if absent or unreadable. */
    @Nullable
    private static String claimedIssuer(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            LOG.debug("A token presented a claim set that cannot be parsed", e);
            return null;
        }
    }

    /**
     * Maps a key-set lookup failure to {@link #OUTCOME_UNAVAILABLE}, never an error signal, which
     * would surface as a 500 that tells an outage apart from a bad signature.
     */
    private static String unreadable(ResolvedIdentityProvider provider, Throwable error) {
        LOG.debug("Consulting the key set of provider '{}' threw", provider.name(), error);
        return OUTCOME_UNAVAILABLE;
    }

    /**
     * Counts one check's outcome, updates state, and returns whether the token is signed. WARNs
     * only when a provider's outcome worsens, so forged-token floods cannot fill the logs.
     */
    private Boolean record(ResolvedIdentityProvider provider, SignedJWT jwt, String outcome) {
        Counter.builder(SIGNATURE_VERIFICATION_METRIC)
                .tag(PROVIDER_TAG, provider.name())
                .tag(OUTCOME_TAG, outcome)
                .register(meterRegistry)
                .increment();
        ProviderState state = stateOf(provider);
        if (OUTCOME_UNAVAILABLE.equals(outcome)) {
            if (state.markUnavailable() && LOG.isWarnEnabled()) {
                LOG.warn(
                        "The key set of provider '{}' could not be read; its tokens are rejected"
                                + " and it will not be asked again for {}",
                        provider.name(),
                        KEY_SET_UNAVAILABLE_COOLDOWN);
            }
            return Boolean.FALSE;
        }
        boolean verified = OUTCOME_VERIFIED.equals(outcome);
        boolean changed = state.markChecked(verified);
        // A kid is a public key-set index, not key material, so it is safe above DEBUG.
        if (!verified && changed && LOG.isWarnEnabled()) {
            LOG.warn(
                    "No key published by provider '{}' verified a token presenting kid '{}'; "
                            + "the key set is cached, so a rotation this service has not yet "
                            + "refreshed looks the same as a forged token. Further rejections "
                            + "are counted under the '{}' outcome rather than logged",
                    provider.name(),
                    jwt.getHeader().getKeyID(),
                    OUTCOME_REJECTED);
        } else if (!verified && LOG.isDebugEnabled()) {
            LOG.debug(
                    "No key published by provider '{}' verified a token presenting kid '{}'",
                    provider.name(),
                    jwt.getHeader().getKeyID());
        }
        return verified;
    }

    /**
     * Advisory per-provider state: outage cooldown and last conclusive outcome. Volatile, not
     * locked: a lost update costs one request or log line, never a token decision.
     */
    private static final class ProviderState {

        /** {@link System#nanoTime()} before which the key set is assumed unreadable. */
        private volatile long unavailableUntilNanos = System.nanoTime();

        /** Whether the last check that did reach a key set verified. */
        private volatile boolean lastCheckVerified = true;

        /** Whether this provider is inside its outage cooldown; subtraction survives wraparound. */
        private boolean isUnavailable() {
            return System.nanoTime() - unavailableUntilNanos < 0L;
        }

        /**
         * Opens the cooldown unless open; never extends it, so traffic cannot hold it open forever.
         * Returns {@code true} on the available-to-unavailable transition.
         */
        private boolean markUnavailable() {
            if (isUnavailable()) {
                return false;
            }
            unavailableUntilNanos = System.nanoTime() + KEY_SET_UNAVAILABLE_COOLDOWN.toNanos();
            return true;
        }

        /** Records a conclusive check, ending any cooldown; {@code true} if the outcome changed. */
        private boolean markChecked(boolean verified) {
            unavailableUntilNanos = System.nanoTime();
            boolean changed = verified != lastCheckVerified;
            lastCheckVerified = verified;
            return changed;
        }
    }
}
