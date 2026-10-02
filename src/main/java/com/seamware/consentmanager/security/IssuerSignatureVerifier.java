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
 * Checks a token's signature against the JWK Set of the issuer that is supposed to have minted it.
 *
 * <p>This is the second half of the trust chain. {@link IdentityProviderRegistry} answers <em>is
 * this issuer on the trust list, and where does it publish its keys?</em>; this type answers
 * <em>was this token signed by one of the keys that issuer publishes?</em> Nothing here decides
 * whether a token is valid overall - the claim checks live in the validator built on top of it.
 *
 * <h2>What is delegated, and what is not</h2>
 *
 * <p>Fetching the JWK Set, caching it, matching the token's {@code kid} against it and verifying
 * the signature are all done by {@code micronaut-security-jwt}'s {@link ReactiveJwksSignature},
 * constructed per provider the same way {@code IdentityProviderRegistry} constructs the module's
 * discovery fetcher (see {@code
 * docs/adr/0004-delegate-jwks-retrieval-and-caching-to-micronaut-security.md}). None of that
 * behaviour is written here, which also means its {@code kid} rules are the module's, not this
 * service's: the matcher constrains on {@code kid} only when the <em>token</em> carries one, so a
 * token without a {@code kid} is offered every published key, and two keys published under one
 * {@code kid} are both tried with the token accepted if either verifies. A token whose {@code kid}
 * matches nothing in the cached set is rejected outright - the module has no refresh-on-miss path -
 * so a provider that publishes keys <em>without</em> a {@code kid} while minting tokens that carry
 * one cannot be used with this service. No such provider is on the trust list today; supporting one
 * would be a change to the module or a reason to revisit ADR 0004, not something this class can
 * paper over.
 *
 * <p>What is <em>not</em> delegated is the routing. The module's own wiring registers every
 * configured JWKS endpoint as a global verifier and checks a token against each in turn until one
 * succeeds, without ever reading the token's {@code iss}. With two providers configured that lets
 * provider B mint tokens that authenticate as provider A. Here the issuer is resolved through the
 * trust list <em>first</em> and the token is only ever offered to that one provider's keys, so a
 * key that is valid at B can never satisfy a token claiming A.
 *
 * <h2>Caching and key rotation</h2>
 *
 * <p>The key set is cached by Micronaut Cache under the name {@code jwks}; its lifetime is {@code
 * micronaut.caches.jwks.expire-after-write}. That one setting covers both halves of the
 * requirement, and it is the only lever over either.
 *
 * <p>While the provider is reachable, nothing invalidates that entry on a miss, so a flood of
 * tokens bearing {@code kid} values no provider ever published costs <em>no</em> outbound request
 * at all: the request rate this service can put on a healthy identity provider is bounded by the
 * cache lifetime and nothing else, whatever an attacker sends. The bound is a steady-state one - a
 * burst of concurrent <em>first-ever</em> verifications for one provider can each miss before the
 * first load is cached, which is bounded by concurrency at that instant rather than by traffic.
 *
 * <p>The same absence of a refresh-on-miss path is what makes the lifetime expensive in the other
 * direction: from the moment a provider starts signing with a newly published key until the cached
 * set expires, every token it issues is rejected. Recovery needs no restart, but it does take up to
 * one lifetime, which is why the default is measured in seconds.
 *
 * <h2>What the cache does not bound: a failing key endpoint</h2>
 *
 * <p>A <em>failed</em> fetch is never cached. The module's JWKS client resumes an HTTP failure to
 * an empty result, and Micronaut Cache stores nothing for an empty result - it invalidates the key
 * instead. So once the entry has expired while the provider's key endpoint is unreachable or
 * answering 5xx, the cache bounds nothing: every single verification would issue its own request,
 * with no rate limit and no backoff, which is 1:1 amplification aimed at a provider that is already
 * unhealthy.
 *
 * <p>That gap is closed here rather than in the module, because it is a routing decision and not a
 * cache: after a lookup fails to produce a key set, this class stops consulting that provider for
 * {@link #KEY_SET_UNAVAILABLE_COOLDOWN} and rejects its tokens outright. Nothing is remembered
 * across the window - no last-known-good key set is held, because holding one would be a second
 * cache alongside the module's and would keep honouring keys the provider may have withdrawn.
 * Tokens from that provider are rejected during an outage either way; all the cooldown changes is
 * how much traffic this service relays onto a provider that cannot answer.
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
     * Value of {@link #OUTCOME_TAG} for a token the provider's keys did not verify.
     *
     * <p>Worth alerting on. A rising count is either a key rotation the cache has not caught up
     * with yet or an attempt to pass forged tokens, and an operator wants to know which; the
     * matching WARN line carries the {@code kid} that failed so the two can be told apart.
     */
    public static final String OUTCOME_REJECTED = "rejected";

    /**
     * Value of {@link #OUTCOME_TAG} for a token whose issuer's key set could not be read at all.
     *
     * <p>Kept apart from {@link #OUTCOME_REJECTED} so that an identity provider outage does not
     * look like a flood of forged tokens in a dashboard. The caller cannot tell the two apart - it
     * sees {@code false} either way, which is what US-ID-008 requires - but an operator must be
     * able to, because the remedy is entirely different.
     */
    public static final String OUTCOME_UNAVAILABLE = "unavailable";

    /**
     * How long a provider whose key set could not be read is left alone before it is asked again.
     *
     * <p>This is the only thing standing between a failing JWK Set endpoint and one outbound
     * request per inbound token. A failed fetch is never cached (see the class documentation), so
     * without this window the cache stops bounding the request rate exactly when the provider can
     * least afford it. Within the window a token from that provider is rejected without any
     * outbound request, so the cost of the window is at most this much extra delay before a
     * recovered provider starts verifying again - comfortably inside the cache lifetime that
     * already bounds rotation latency.
     */
    static final Duration KEY_SET_UNAVAILABLE_COOLDOWN = Duration.ofSeconds(10);

    private final IdentityProviderRegistry registry;
    private final JwkValidator jwkValidator;
    private final JwkSetFetcher<JWKSet> jwkSetFetcher;
    private final MeterRegistry meterRegistry;

    /**
     * Per-provider outage and logging state, keyed by the trust-list entry name.
     *
     * <p>Bounded by the trust list, which is fixed at startup, so this map cannot grow with
     * traffic.
     */
    private final Map<String, ProviderState> stateByProvider = new ConcurrentHashMap<>();

    /**
     * Creates the verifier.
     *
     * @param registry the trust list, consulted on every check so that only an issuer that is both
     *     configured and discovered can have its key set fetched at all
     * @param jwkValidator the module's signature validator, which picks the verifier matching the
     *     key and the token's algorithm
     * @param jwkSetFetcher the module's key-set fetcher; the cached implementation when a {@code
     *     jwks} cache is configured, which {@code application.yml} always does
     * @param meterRegistry where the outcome counter is published; {@code null} when metrics are
     *     switched off, in which case a throwaway registry keeps the instrumentation from needing a
     *     second code path
     */
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
     * Reports whether the token was signed by a key the named issuer currently publishes.
     *
     * <p>Non-blocking: the key set is fetched reactively, so a stalled identity provider parks no
     * thread. Callers must compose the returned publisher rather than awaiting it on a request
     * thread.
     *
     * <p>An issuer that is not on the trust list, or one whose discovery has not succeeded, emits
     * {@code false} without any outbound request - it is indistinguishable, from the caller's side,
     * from a token whose signature simply did not verify. That is deliberate: a caller must not be
     * able to probe which issuers this deployment trusts. The same holds for an issuer whose key
     * set could not be read within the last {@link #KEY_SET_UNAVAILABLE_COOLDOWN}.
     *
     * @param issuer the {@code iss} claim of the token, read before anything about it is trusted;
     *     may be {@code null} for a token that carries no issuer
     * @param jwt the parsed token
     * @return a single-element publisher emitting {@code true} only if the signature verified
     *     against a key of that issuer
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

    /**
     * Returns the mutable state kept for one provider, creating it on first use.
     *
     * @param provider the provider whose state is wanted
     * @return that provider's state, never {@code null}
     */
    private ProviderState stateOf(ResolvedIdentityProvider provider) {
        return stateByProvider.computeIfAbsent(provider.name(), name -> new ProviderState());
    }

    /**
     * Reads the issuer the token claims, without trusting it.
     *
     * @param jwt the token to read
     * @return the {@code iss} claim, or {@code null} if the token carries none or cannot be read
     */
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
     * Turns a thrown failure of the key-set lookup into the unreadable-key-set outcome.
     *
     * <p>Anything thrown while resolving or calling the provider's key endpoint has to come out of
     * this publisher as {@code false} rather than as an error signal. An error would surface to the
     * caller as a 500 carrying internal detail, which both tells an unauthenticated caller
     * something about this deployment's upstreams and makes an unreachable provider look different
     * from a bad signature - the distinction US-ID-008 exists to deny.
     *
     * @param provider the provider whose keys could not be consulted
     * @param error what went wrong
     * @return {@link #OUTCOME_UNAVAILABLE}, always
     */
    private static String unreadable(ResolvedIdentityProvider provider, Throwable error) {
        LOG.debug("Consulting the key set of provider '{}' threw", provider.name(), error);
        return OUTCOME_UNAVAILABLE;
    }

    /**
     * Publishes the outcome of one signature check, updates the provider's state and logs it.
     *
     * <p>Logging is deliberately not one line per rejected token. A caller chooses how many forged
     * tokens to send and what {@code kid} each carries, so an unconditional WARN per rejection is a
     * log pipeline an unauthenticated caller can fill, and the genuine signal - a key rotation this
     * service has not caught up with - would be buried in it. A WARN is therefore emitted only when
     * the outcome for a provider <em>changes</em> for the worse, which is exactly when a rotation
     * or an outage starts; the steady state is counted, not logged. The counter above carries every
     * outcome and is the thing to alert on.
     *
     * @param provider the provider whose keys were consulted
     * @param jwt the token that was checked, read only for the {@code kid} in the log line
     * @param outcome one of {@link #OUTCOME_VERIFIED}, {@link #OUTCOME_REJECTED} or {@link
     *     #OUTCOME_UNAVAILABLE}
     * @return whether the token may be treated as signed by that provider
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
     * The mutable state this class keeps about one provider.
     *
     * <p>Two things only: whether that provider's key endpoint is currently believed to be
     * unreadable, and whether the last conclusive check against its keys verified. Both are plain
     * volatile fields rather than locks, because both are advisory - a lost update costs at most
     * one extra outbound request or one extra log line, and never changes whether a token is
     * accepted.
     */
    private static final class ProviderState {

        /**
         * {@link System#nanoTime()} reading before which the key set is assumed unreadable.
         *
         * <p>Initialised to "now", so a freshly created state is available. Compared by
         * subtraction, which stays correct across {@code nanoTime} wraparound.
         */
        private volatile long unavailableUntilNanos = System.nanoTime();

        /** Whether the last check that did reach a key set verified. */
        private volatile boolean lastCheckVerified = true;

        /**
         * Reports whether this provider is inside its outage cooldown.
         *
         * @return {@code true} while its key set must not be requested again
         */
        private boolean isUnavailable() {
            return System.nanoTime() - unavailableUntilNanos < 0L;
        }

        /**
         * Opens the outage cooldown if it is not already open.
         *
         * <p>An open window is deliberately <em>not</em> extended. Extending it would let a steady
         * stream of inbound tokens hold the window open indefinitely, so a provider that had
         * recovered would never be asked again for as long as traffic kept arriving. Letting it
         * lapse on its own means one request probes the provider per window, which is the bound
         * this class is here to put on the outage case.
         *
         * @return {@code true} if the provider was considered available until this call, which is
         *     the transition worth logging
         */
        private boolean markUnavailable() {
            if (isUnavailable()) {
                return false;
            }
            unavailableUntilNanos = System.nanoTime() + KEY_SET_UNAVAILABLE_COOLDOWN.toNanos();
            return true;
        }

        /**
         * Records a check that did reach the provider's key set, ending any cooldown.
         *
         * @param verified whether the signature verified
         * @return {@code true} if this outcome differs from the previous one
         */
        private boolean markChecked(boolean verified) {
            unavailableUntilNanos = System.nanoTime();
            boolean changed = verified != lastCheckVerified;
            lastCheckVerified = verified;
            return changed;
        }
    }
}
