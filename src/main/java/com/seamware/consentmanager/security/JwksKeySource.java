package com.seamware.consentmanager.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a token's signing key from the JWK Set of the issuer that minted it.
 *
 * <p>This is the second half of the trust chain. {@link IdentityProviderRegistry} answers <em>is
 * this issuer on the trust list, and where does it publish its keys?</em>; this type answers
 * <em>which key, of the ones that issuer publishes right now, is {@code kid}?</em> Nothing here
 * decides whether a token is valid - it hands back a key, and the validator built on top of it in a
 * later step decides what to do with it.
 *
 * <h2>Why Nimbus rather than a cache of our own</h2>
 *
 * <p>The requirements are a per-provider cache lifetime, a refetch when a token presents an unknown
 * {@code kid} so that a rotated key is picked up without a restart, and a rate limit on that
 * refetch so a flood of forged tokens carrying random {@code kid} values cannot be turned into a
 * denial-of-service attack on the identity provider. {@code nimbus-jose-jwt} - which {@code
 * micronaut-security-jwt} already brings, see {@code
 * docs/adr/0002-own-identity-provider-registry-on-nimbus.md} - implements all three, plus
 * single-flight refresh, in code that is far more exercised than anything written here would be.
 * This is the one place in the trust chain where a concurrency bug is a security bug, so the
 * composition below is deliberately nothing but configuration of {@link JWKSourceBuilder}:
 *
 * <ul>
 *   <li>{@link JWKSourceBuilder#cache(long, long)} with the provider's own {@code jwks-cache-ttl},
 *       so a key that has already been fetched is served from memory;
 *   <li>{@link JWKSourceBuilder#rateLimited(long)} with {@link #JWKS_REFETCH_COOLDOWN}, so at most
 *       one unknown-{@code kid} refetch reaches the provider per cooldown window;
 *   <li>{@link JWKSourceBuilder#retrying(boolean)} and {@link
 *       JWKSourceBuilder#outageTolerant(long)}, so a single dropped connection does not fail a
 *       request and a provider outage does not take this service down with it while the cached keys
 *       are still good.
 * </ul>
 *
 * <p>The unknown-{@code kid} behaviour itself is Nimbus's {@code JWKSetBasedJWKSource}: it reads
 * the cache, applies the selector, and <em>only if nothing matched</em> asks the chain once more
 * with a reference-comparison refresh evaluator. One refetch, never a loop, and if another thread
 * has already refreshed in the meantime its result is reused instead of fetching again.
 *
 * <h2>The map is a memo, not a trust list</h2>
 *
 * <p>Sources are built lazily and memoized per issuer because a provider's {@code jwks_uri} only
 * becomes known when the registry's asynchronous discovery resolves it. An entry is only ever
 * created for an issuer that {@link IdentityProviderRegistry#findByIssuer(String)} already returns
 * as usable, so this map can never widen the set of trusted issuers - it is a cache of work, not a
 * second registry. If discovery later re-points a provider at a different {@code jwks_uri}, the
 * memoized source for that issuer is discarded and rebuilt.
 */
@Singleton
public class JwksKeySource implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JwksKeySource.class);

    /**
     * Shortest interval between two JWK Set requests to the same provider.
     *
     * <p>This is the rate limit behind AC 14. Every token bearing a {@code kid} the cache does not
     * know is a refetch candidate, and an attacker can mint unlimited unsigned tokens carrying
     * random {@code kid} values for free; without this window each one would become an outbound
     * HTTP request and this service would be an amplifier pointed at its own identity provider.
     * With it, the first such token in a window costs one request and every later one in that
     * window costs nothing, while a genuine key rotation is still picked up within the window
     * rather than at the next restart.
     */
    public static final Duration JWKS_REFETCH_COOLDOWN = Duration.ofSeconds(30);

    /**
     * Floor applied to a provider's configured {@code jwks-cache-ttl}.
     *
     * <p>Two reasons, one practical and one structural. A cache lifetime at or below {@link
     * #JWKS_REFETCH_COOLDOWN} makes the rate limiter unreachable - every request would find the
     * cache expired and go for the key set directly - and Nimbus rejects that combination outright
     * when the source is built. Rather than let a careless {@code jwks-cache-ttl: 0} fail the first
     * token validation at runtime, a too-short lifetime is raised to this floor and logged.
     */
    public static final Duration MIN_JWKS_CACHE_TTL = Duration.ofSeconds(10);

    /**
     * How long a thread waits for another thread's in-flight JWK Set refresh before giving up.
     *
     * <p>Only one thread fetches; the rest wait for its result instead of issuing their own
     * request. This bound is what keeps a slow identity provider from pinning every request thread
     * in the server for as long as it feels like taking.
     */
    public static final Duration JWKS_REFRESH_TIMEOUT = Duration.ofSeconds(15);

    /** How long to wait for the TCP connection to a provider's JWK Set endpoint. */
    public static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** How long to wait for a provider's JWK Set response once connected. */
    public static final Duration JWKS_READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Largest JWK Set response that will be read, in bytes.
     *
     * <p>A real key set holds a handful of keys and is a few kilobytes at most. The limit stops a
     * compromised or misbehaving provider from exhausting this service's heap with an endless
     * response body.
     */
    public static final int JWKS_MAX_RESPONSE_BYTES = 64 * 1024;

    /**
     * Multiple of the cache lifetime for which keys stay usable while the provider is unreachable.
     *
     * <p>Signing keys outlive their publication by design, so continuing to accept tokens signed by
     * an already-fetched key during a provider outage is both safe and the difference between a
     * degraded identity provider and a total authentication outage here.
     */
    public static final int OUTAGE_TOLERANCE_MULTIPLIER = 10;

    /** Counter of JWK Set requests that actually left this process, tagged by provider. */
    public static final String JWKS_FETCH_METRIC = "consentmanager.jwks.fetches";

    /**
     * Counter of refetches the rate limiter refused, tagged by provider.
     *
     * <p>Published so the rate limit is observable rather than merely present: a rising count is
     * either a key rotation this service has not caught up with or an attempt to use unknown {@code
     * kid} values as an amplification lever, and both are worth an alert.
     */
    public static final String JWKS_REFETCH_SUPPRESSED_METRIC =
            "consentmanager.jwks.refetches.suppressed";

    /** Tag carrying the configured provider name on both counters. */
    public static final String PROVIDER_TAG = "provider";

    private final IdentityProviderRegistry registry;
    private final MeterRegistry meterRegistry;
    private final ResourceRetriever resourceRetriever;
    private final Map<String, IssuerKeySource> sourcesByIssuer = new ConcurrentHashMap<>();

    private volatile boolean closed;

    /**
     * Creates the key source.
     *
     * @param registry the trust list, consulted on every lookup so that only an issuer that is both
     *     configured and discovered can ever get a JWK Set source
     * @param meterRegistry where the fetch counters are published; {@code null} when metrics are
     *     switched off, in which case the counters are kept on a throwaway registry so the
     *     instrumentation needs no null checks and no second code path
     */
    public JwksKeySource(IdentityProviderRegistry registry, @Nullable MeterRegistry meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.resourceRetriever =
                new DefaultResourceRetriever(
                        (int) JWKS_CONNECT_TIMEOUT.toMillis(),
                        (int) JWKS_READ_TIMEOUT.toMillis(),
                        JWKS_MAX_RESPONSE_BYTES);
    }

    /**
     * Finds the key an issuer currently publishes under a given key identifier.
     *
     * <p>Returns empty - never an exception, never a partial result - for every way this can fail
     * to produce a key: an issuer that is not on the trust list or whose discovery has not
     * succeeded, a {@code kid} that is still unknown after the one permitted refetch, a refetch the
     * rate limiter refused, and an identity provider that is unreachable for longer than its keys
     * stay usable. The caller is a token validator, and all of those mean the same thing to it:
     * this token cannot be shown to be genuine, so reject it.
     *
     * <p>A blank {@code kid} also yields empty. Selecting on a blank identifier would match every
     * key in the set, which would turn "which key signed this?" into "try any key", and that is how
     * key-confusion bugs start. Every identity provider this service is built for publishes a
     * {@code kid} on each key and sets it in the JWS header.
     *
     * @param issuer the token's {@code iss} claim; may be {@code null}
     * @param kid the token's JWS {@code kid} header; may be {@code null}
     * @return the matching key, or {@link Optional#empty()} if none can be resolved
     */
    public Optional<JWK> selectKey(String issuer, String kid) {
        if (closed || kid == null || kid.isBlank()) {
            return Optional.empty();
        }
        Optional<ResolvedIdentityProvider> provider = registry.findByIssuer(issuer);
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        String providerName = provider.get().configuration().getName();
        Optional<JWKSource<SecurityContext>> source = sourceFor(provider.get());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        JWKSelector selector = new JWKSelector(new JWKMatcher.Builder().keyID(kid).build());
        try {
            List<JWK> matches = source.get().get(selector, null);
            if (matches.isEmpty()) {
                LOG.debug(
                        "No key with kid '{}' published by provider '{}' after a refetch",
                        kid,
                        providerName);
                return Optional.empty();
            }
            return Optional.of(matches.getFirst());
        } catch (RateLimitReachedException rateLimited) {
            // Expected under load and under attack, so it is counted rather than logged loudly.
            counter(JWKS_REFETCH_SUPPRESSED_METRIC, providerName).increment();
            LOG.debug(
                    "Refetch for unknown kid '{}' of provider '{}' suppressed by the {} cooldown",
                    kid,
                    providerName,
                    JWKS_REFETCH_COOLDOWN);
            return Optional.empty();
        } catch (KeySourceException unavailable) {
            LOG.warn(
                    "Could not read the JWK Set of provider '{}': {}",
                    providerName,
                    unavailable.toString());
            return Optional.empty();
        }
    }

    /**
     * Returns the memoized key source for a provider, building it on first use.
     *
     * @param provider a resolved trust-list entry, so its {@code jwks_uri} is known
     * @return the key source, or {@link Optional#empty()} if the discovered URL cannot be used
     */
    private Optional<JWKSource<SecurityContext>> sourceFor(ResolvedIdentityProvider provider) {
        String jwksUri = provider.jwksUri();
        URL jwksUrl;
        try {
            jwksUrl = new URI(jwksUri).toURL();
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException malformed) {
            // The registry scheme-checks jwks_uri before it resolves an entry, so reaching this is
            // a defect rather than a misconfiguration - but not one worth failing a request thread
            // with, since the outcome for the caller is the same either way.
            LOG.error(
                    "Provider '{}' resolved to an unusable jwks_uri '{}'",
                    provider.configuration().getName(),
                    jwksUri,
                    malformed);
            return Optional.empty();
        }
        IssuerKeySource memoized =
                sourcesByIssuer.compute(
                        provider.configuration().getIssuer(),
                        (issuer, existing) -> rebuildIfStale(existing, provider, jwksUrl));
        return Optional.of(memoized.source());
    }

    /**
     * Keeps an existing memoized source, or replaces one built for a different JWK Set URL.
     *
     * <p>Runs inside {@link ConcurrentHashMap#compute}, so exactly one thread builds the source for
     * an issuer however many arrive at once.
     *
     * @param existing the memoized entry, or {@code null} on first use of this issuer
     * @param provider the resolved trust-list entry being looked up
     * @param jwksUrl the URL discovery most recently published for it
     * @return the entry to memoize
     */
    private IssuerKeySource rebuildIfStale(
            @Nullable IssuerKeySource existing, ResolvedIdentityProvider provider, URL jwksUrl) {
        String jwksUri = jwksUrl.toString();
        if (existing != null && existing.jwksUri().equals(jwksUri)) {
            return existing;
        }
        if (existing != null) {
            LOG.info(
                    "Provider '{}' moved its JWK Set from {} to {}; rebuilding the key source",
                    provider.configuration().getName(),
                    existing.jwksUri(),
                    jwksUri);
            closeQuietly(existing.source());
        }
        return new IssuerKeySource(jwksUri, build(provider, jwksUrl));
    }

    /**
     * Assembles the Nimbus source chain for one provider.
     *
     * @param provider the resolved trust-list entry, supplying the cache lifetime and the name the
     *     counters are tagged with
     * @param jwksUrl the provider's JWK Set URL
     * @return a caching, rate-limited, retrying, outage-tolerant key source
     */
    private JWKSource<SecurityContext> build(ResolvedIdentityProvider provider, URL jwksUrl) {
        String providerName = provider.configuration().getName();
        Duration cacheTtl =
                effectiveCacheTtl(provider.configuration().getJwksCacheTtl(), providerName);
        Duration cooldown = effectiveRefetchCooldown(cacheTtl);
        JWKSetSource<SecurityContext> counted =
                new CountingJWKSetSource(
                        new URLBasedJWKSetSource<>(jwksUrl, resourceRetriever),
                        counter(JWKS_FETCH_METRIC, providerName));
        LOG.debug(
                "Built the JWK Set source for provider '{}' at {} (cache {}, refetch cooldown {})",
                providerName,
                jwksUrl,
                cacheTtl,
                cooldown);
        return JWKSourceBuilder.create(counted)
                .cache(cacheTtl.toMillis(), JWKS_REFRESH_TIMEOUT.toMillis())
                .rateLimited(cooldown.toMillis())
                .retrying(true)
                .outageTolerant(cacheTtl.multipliedBy(OUTAGE_TOLERANCE_MULTIPLIER).toMillis())
                .build();
    }

    /**
     * Raises a configured cache lifetime to {@link #MIN_JWKS_CACHE_TTL} if it is shorter.
     *
     * @param configured the provider's {@code jwks-cache-ttl}
     * @param providerName the provider's configured name, for the warning
     * @return the lifetime to build the cache with, never shorter than the floor
     */
    static Duration effectiveCacheTtl(Duration configured, String providerName) {
        if (configured.compareTo(MIN_JWKS_CACHE_TTL) >= 0) {
            return configured;
        }
        LOG.warn(
                "jwks-cache-ttl of provider '{}' is {}, which would leave the refetch rate limit"
                        + " unreachable; using {} instead",
                providerName,
                configured,
                MIN_JWKS_CACHE_TTL);
        return MIN_JWKS_CACHE_TTL;
    }

    /**
     * Derives the refetch cooldown that fits inside a given cache lifetime.
     *
     * <p>Nimbus requires the cooldown to be strictly shorter than the cache lifetime, because a
     * cooldown that outlives the cache would block the very refresh the expiry asks for. {@link
     * #JWKS_REFETCH_COOLDOWN} is the cooldown this service wants; for a provider configured with a
     * shorter cache lifetime than that, half of the lifetime is the longest cooldown that still
     * leaves room for the cache to refresh on expiry.
     *
     * @param cacheTtl the cache lifetime the source will be built with, already floored
     * @return the cooldown to rate-limit refetches with, always shorter than {@code cacheTtl}
     */
    static Duration effectiveRefetchCooldown(Duration cacheTtl) {
        Duration halfOfCache = cacheTtl.dividedBy(2);
        return JWKS_REFETCH_COOLDOWN.compareTo(halfOfCache) <= 0
                ? JWKS_REFETCH_COOLDOWN
                : halfOfCache;
    }

    /**
     * Returns the counter of the given name for one provider, registering it on first use.
     *
     * @param name the metric name
     * @param providerName the value of the {@link #PROVIDER_TAG} tag
     * @return the counter
     */
    private Counter counter(String name, String providerName) {
        return meterRegistry.counter(name, PROVIDER_TAG, providerName);
    }

    /** Releases every memoized key source on shutdown. */
    @Override
    @PreDestroy
    public void close() {
        closed = true;
        sourcesByIssuer.values().forEach(entry -> closeQuietly(entry.source()));
        sourcesByIssuer.clear();
    }

    /**
     * Closes a key source, logging rather than propagating a failure.
     *
     * <p>Called from shutdown and from the rebuild path, neither of which has anything useful to do
     * about a source that will not close.
     *
     * @param source the source to close
     */
    private static void closeQuietly(JWKSource<SecurityContext> source) {
        if (source instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception failure) {
                LOG.debug("Closing a JWK Set source failed", failure);
            }
        }
    }

    /**
     * One issuer's memoized key source, together with the URL it was built for.
     *
     * <p>The URL is kept so a provider that re-publishes its keys elsewhere is noticed rather than
     * served from a source pointing at the old location forever.
     *
     * @param jwksUri the JWK Set URL this source fetches from
     * @param source the Nimbus source chain
     */
    private record IssuerKeySource(String jwksUri, JWKSource<SecurityContext> source) {}

    /**
     * Counts the JWK Set requests that actually reach the network.
     *
     * <p>Sits at the bottom of the Nimbus chain, below the cache and the rate limiter, so it counts
     * outbound HTTP requests rather than lookups - which is what makes the cache hit rate and the
     * rate limit visible in production and assertable in tests.
     */
    private static final class CountingJWKSetSource implements JWKSetSource<SecurityContext> {

        private final JWKSetSource<SecurityContext> delegate;
        private final Counter fetches;

        /**
         * Wraps a source with a fetch counter.
         *
         * @param delegate the source that performs the request
         * @param fetches the counter to increment per request
         */
        private CountingJWKSetSource(JWKSetSource<SecurityContext> delegate, Counter fetches) {
            this.delegate = delegate;
            this.fetches = fetches;
        }

        @Override
        public JWKSet getJWKSet(
                JWKSetCacheRefreshEvaluator refreshEvaluator,
                long currentTime,
                SecurityContext context)
                throws KeySourceException {
            fetches.increment();
            return delegate.getJWKSet(refreshEvaluator, currentTime, context);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
