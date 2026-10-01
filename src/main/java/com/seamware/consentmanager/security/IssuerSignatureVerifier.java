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
import java.util.Optional;
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
 * behaviour is written here, which also means none of its edge cases are: a provider that publishes
 * no {@code kid} at all, or two keys under one {@code kid}, is handled by the module's matcher,
 * which falls back to offering every published key and accepts the token if the signature verifies
 * against any of them.
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
 * requirement. A key minted after startup is picked up when the cached set next expires, with no
 * restart; and because the cache is never invalidated on demand, a flood of tokens bearing {@code
 * kid} values no provider ever published costs <em>no</em> outbound request at all - the request
 * rate this service can put on an identity provider is bounded by the cache lifetime and nothing
 * else, whatever an attacker sends.
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

    private final IdentityProviderRegistry registry;
    private final JwkValidator jwkValidator;
    private final JwkSetFetcher<JWKSet> jwkSetFetcher;
    private final MeterRegistry meterRegistry;

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
     * able to probe which issuers this deployment trusts.
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
        ResolvedIdentityProvider resolved = provider.get();
        ReactiveJwksSignature signature =
                new ReactiveJwksSignature(
                        new JwksSignatureConfigurationAdapter(resolved),
                        jwkValidator,
                        jwkSetFetcher);
        return Mono.from(signature.verify(jwt))
                .defaultIfEmpty(Boolean.FALSE)
                .doOnNext(verified -> record(resolved, jwt, verified));
    }

    /**
     * Publishes the outcome of one signature check and logs a rejection.
     *
     * @param provider the provider whose keys were consulted
     * @param jwt the token that was checked, read only for the {@code kid} in the log line
     * @param verified whether the signature verified
     */
    private void record(ResolvedIdentityProvider provider, SignedJWT jwt, boolean verified) {
        Counter.builder(SIGNATURE_VERIFICATION_METRIC)
                .tag(PROVIDER_TAG, provider.name())
                .tag(OUTCOME_TAG, verified ? OUTCOME_VERIFIED : OUTCOME_REJECTED)
                .register(meterRegistry)
                .increment();
        if (!verified && LOG.isWarnEnabled()) {
            LOG.warn(
                    "No key published by provider '{}' verified a token presenting kid '{}'; "
                            + "the key set is cached, so a rotation this service has not yet "
                            + "refreshed looks the same as a forged token",
                    provider.name(),
                    jwt.getHeader().getKeyID());
        }
    }
}
