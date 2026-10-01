package com.seamware.consentmanager.security;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWK;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the key-resolution half of the trust chain: what reaches the identity provider's JWK Set
 * endpoint, and what does not.
 *
 * <p>Every assertion here is ultimately a request count against a WireMock provider, because the
 * properties under test are invisible from the return value. Resolving a key twice and resolving it
 * once look identical to the caller; what separates them is whether the second lookup cost an
 * outbound HTTP request. The same goes for the rate limit, where the whole point is that a flood of
 * lookups for keys that do not exist produces almost no traffic, and for the single-flight refresh,
 * where sixteen simultaneous cold lookups must still produce one fetch rather than sixteen.
 *
 * <p>Each test owns its context and its stub, so one test's cache cannot answer another's lookup.
 */
@DisplayName("JWKS caching, key selection and rate-limited refetch")
class JwksKeySourceIT {

    /** How long a test waits for the asynchronous discovery to make the provider usable. */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(30);

    /** Name of the single trust-list entry these tests configure. */
    private static final String PRIMARY = "primary";

    /** Prefix of a trust-list entry's configuration properties. */
    private static final String PROVIDER_PREFIX = "consent-manager.identity-providers.";

    /** Audience the stubbed provider's entry declares. */
    private static final String STUB_AUDIENCE = "consent-manager";

    /** Claim the stubbed provider's entry reads roles from. */
    private static final String STUB_ROLES_CLAIM = "realm_access.roles";

    /** Claim the stubbed provider's entry reads the participant identifier from. */
    private static final String STUB_PARTICIPANT_CLAIM = "participant_id";

    /** Provider role the stubbed provider's entry maps to {@link Role#USER}. */
    private static final String STUB_USER_ROLE = "consent-user";

    /** A key identifier no provider in these tests ever publishes. */
    private static final String UNKNOWN_KEY_ID = "a-kid-no-provider-ever-published";

    /** Key identifier a rotation introduces. */
    private static final String ROTATED_KEY_ID = "rotated-key";

    /** An issuer that is not on the trust list. */
    private static final String UNTRUSTED_ISSUER = "https://issuer.invalid/realms/not-configured";

    /** How many threads race for the same cold key in the single-flight test. */
    private static final int CONCURRENT_LOOKUPS = 16;

    /** How long a racing thread may take before the single-flight test is declared broken. */
    private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(30);

    /** Status the key-set endpoint answers with when a test takes it down. */
    private static final int SERVICE_UNAVAILABLE = 503;

    @Test
    @DisplayName("a key resolved once is served from cache, without a second fetch")
    void cachedKeyIsServedWithoutASecondFetch() {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            JwksKeySource keys = resolvedKeySource(context);

            Optional<JWK> first = keys.selectKey(stub.issuer(), stub.initialKeyId());
            Optional<JWK> second = keys.selectKey(stub.issuer(), stub.initialKeyId());

            assertThat(keyIdOf(first)).contains(stub.initialKeyId());
            assertThat(keyIdOf(second)).contains(stub.initialKeyId());
            assertThat(stub.jwksRequestCount())
                    .as("the second lookup is answered from the cache, within the cache lifetime")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a rotated key is picked up by exactly one refetch, without a restart")
    void rotatedKeyIsPickedUpWithExactlyOneRefetch() {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            JwksKeySource keys = resolvedKeySource(context);
            assertThat(keyIdOf(keys.selectKey(stub.issuer(), stub.initialKeyId())))
                    .as("the pre-rotation key resolves and warms the cache")
                    .contains(stub.initialKeyId());

            stub.rotateKeysTo(ROTATED_KEY_ID);

            assertThat(keyIdOf(keys.selectKey(stub.issuer(), ROTATED_KEY_ID)))
                    .as("the key minted after startup resolves without restarting this service")
                    .contains(ROTATED_KEY_ID);
            assertThat(stub.jwksRequestCount())
                    .as("the unknown kid cost exactly one refetch on top of the initial load")
                    .isEqualTo(2);
            assertThat(keyIdOf(keys.selectKey(stub.issuer(), ROTATED_KEY_ID)))
                    .as("the refetched key set is itself cached")
                    .contains(ROTATED_KEY_ID);
            assertThat(stub.jwksRequestCount()).isEqualTo(2);
        }
    }

    @DisplayName("repeated lookups of an unknown kid cost one refetch per cooldown window")
    @ParameterizedTest(name = "{0} lookups of an unknown kid still cost one refetch")
    @ValueSource(ints = {2, 3, 10, 50})
    void unknownKeyIdCostsAtMostOneRefetchPerCooldown(int lookups) {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            JwksKeySource keys = resolvedKeySource(context);
            keys.selectKey(stub.issuer(), stub.initialKeyId());

            for (int i = 0; i < lookups; i++) {
                assertThat(keys.selectKey(stub.issuer(), UNKNOWN_KEY_ID))
                        .as("a kid the provider does not publish never resolves to a key")
                        .isEmpty();
            }

            assertThat(stub.jwksRequestCount())
                    .as(
                            "a flood of forged kids must not be an amplifier pointed at the"
                                    + " identity provider: one refetch for the window, whatever"
                                    + " the volume")
                    .isEqualTo(2);
            assertThat(suppressedRefetches(context))
                    .as("every lookup past the one refetch is counted as suppressed")
                    .isEqualTo(lookups - 1);
        }
    }

    @Test
    @DisplayName("concurrent first lookups of the same key issue a single fetch")
    void concurrentFirstLookupsIssueASingleFetch() throws Exception {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            JwksKeySource keys = resolvedKeySource(context);
            CyclicBarrier startLine = new CyclicBarrier(CONCURRENT_LOOKUPS);
            List<Callable<Optional<JWK>>> lookups = new ArrayList<>(CONCURRENT_LOOKUPS);
            for (int i = 0; i < CONCURRENT_LOOKUPS; i++) {
                lookups.add(
                        () -> {
                            startLine.await(CONCURRENCY_TIMEOUT.toSeconds(), SECONDS);
                            return keys.selectKey(stub.issuer(), stub.initialKeyId());
                        });
            }

            List<Future<Optional<JWK>>> results;
            try (ExecutorService threads = Executors.newFixedThreadPool(CONCURRENT_LOOKUPS)) {
                results = threads.invokeAll(lookups, CONCURRENCY_TIMEOUT.toSeconds(), SECONDS);
            }

            for (Future<Optional<JWK>> result : results) {
                assertThat(keyIdOf(result.get()))
                        .as("every racing thread gets the key, not just the one that fetched it")
                        .contains(stub.initialKeyId());
            }
            assertThat(stub.jwksRequestCount())
                    .as("one thread fetches and the rest wait for its result")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an issuer that is not on the trust list is never looked up")
    void untrustedIssuerIsNeverLookedUp() {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            JwksKeySource keys = resolvedKeySource(context);

            assertThat(keys.selectKey(UNTRUSTED_ISSUER, stub.initialKeyId())).isEmpty();
            assertThat(keys.selectKey(null, stub.initialKeyId())).isEmpty();

            assertThat(stub.jwksRequestCount())
                    .as("a token claiming an unconfigured issuer costs no outbound request at all")
                    .isZero();
        }
    }

    @DisplayName("a token with no usable kid is rejected without reaching the provider")
    @ParameterizedTest(name = "kid [{0}] resolves to no key")
    @ValueSource(strings = {"", " ", "\t"})
    void blankKeyIdResolvesToNoKey(String kid) {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            JwksKeySource keys = resolvedKeySource(context);

            assertThat(keys.selectKey(stub.issuer(), kid))
                    .as("selecting on a blank kid would match every key, which is key confusion")
                    .isEmpty();
            assertThat(stub.jwksRequestCount()).isZero();
        }
    }

    @Test
    @DisplayName("a key set that cannot be fetched yields no key rather than an exception")
    void unreachableKeySetYieldsNoKey() {
        try (OidcDiscoveryStub stub = new OidcDiscoveryStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            JwksKeySource keys = resolvedKeySource(context);
            stub.failJwksRequests(SERVICE_UNAVAILABLE);

            assertThat(keys.selectKey(stub.issuer(), stub.initialKeyId()))
                    .as("the caller is a token validator; an unreadable key set means reject")
                    .isEmpty();
            assertThat(fetchCount(context))
                    .as("the attempt, and its retry, are counted so the outage is visible")
                    .isPositive();
        }
    }

    /**
     * Returns the key source of a context whose provider has finished discovery.
     *
     * @param context the started context
     * @return the key source, ready to be asked for keys
     */
    private static JwksKeySource resolvedKeySource(ApplicationContext context) {
        IdentityProviderRegistry registry = context.getBean(IdentityProviderRegistry.class);
        Await.until(
                "the configured provider to resolve its jwks_uri",
                RESOLUTION_TIMEOUT,
                registry::isFullyResolved);
        return context.getBean(JwksKeySource.class);
    }

    /**
     * Reads the published count of JWK Set requests this process made for the primary provider.
     *
     * @param context the started context
     * @return the counter's value
     */
    private static double fetchCount(ApplicationContext context) {
        return counterValue(context, JwksKeySource.JWKS_FETCH_METRIC);
    }

    /**
     * Reads the published count of refetches the rate limiter refused for the primary provider.
     *
     * @param context the started context
     * @return the counter's value
     */
    private static double suppressedRefetches(ApplicationContext context) {
        return counterValue(context, JwksKeySource.JWKS_REFETCH_SUPPRESSED_METRIC);
    }

    /**
     * Reads one of the key source's counters for the primary provider.
     *
     * @param context the started context
     * @param name the metric name
     * @return the counter's value
     */
    private static double counterValue(ApplicationContext context, String name) {
        MeterRegistry meters = context.getBean(MeterRegistry.class);
        Counter counter = meters.find(name).tag(JwksKeySource.PROVIDER_TAG, PRIMARY).counter();
        assertThat(counter)
                .as("counter '%s' is published for provider '%s'", name, PRIMARY)
                .isNotNull();
        return counter.count();
    }

    /**
     * Extracts the key identifier of a resolved key, for assertions that do not care about the key.
     *
     * @param key the lookup result
     * @return the key's {@code kid}, or empty if no key was resolved
     */
    private static Optional<String> keyIdOf(Optional<JWK> key) {
        return key.map(JWK::getKeyID);
    }

    /**
     * Starts a context whose single configured provider is the given stub.
     *
     * <p>The datasource, Flyway and the HTTP server are left out: this suite asks a bean for keys
     * directly and never issues an HTTP request of its own, so a PostgreSQL container would only
     * slow it down. Metrics are switched on explicitly because two of the assertions read the key
     * source's counters, and a suite that silently stopped asserting them would be worse than one
     * that fails.
     *
     * @param stub the provider the trust list points at
     * @param overrides extra properties layered on top
     * @return the started context
     */
    private static ApplicationContext startContext(
            OidcDiscoveryStub stub, Map<String, Object> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.put("micronaut.server.port", -1);
        properties.put("micronaut.metrics.enabled", true);
        properties.putAll(providerProperties(PRIMARY, stub));
        properties.putAll(overrides);
        return ApplicationContext.builder().environments("test").properties(properties).start();
    }

    /**
     * Renders one trust-list entry pointing at a stub.
     *
     * @param name the entry name
     * @param stub the provider the entry points at
     * @return the entry's properties
     */
    private static Map<String, Object> providerProperties(String name, OidcDiscoveryStub stub) {
        String prefix = PROVIDER_PREFIX + name + ".";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(prefix + "issuer", stub.issuer());
        properties.put(prefix + "discovery-url", stub.discoveryUrl());
        properties.put(prefix + "audience", STUB_AUDIENCE);
        properties.put(prefix + "allow-insecure-transport", true);
        properties.put(prefix + "claims.participant-identifier", STUB_PARTICIPANT_CLAIM);
        properties.put(prefix + "claims.roles", STUB_ROLES_CLAIM);
        properties.put(prefix + "role-mapping.user", STUB_USER_ROLE);
        return properties;
    }
}
