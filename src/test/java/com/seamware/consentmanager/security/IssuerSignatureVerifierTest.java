package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.security.token.jwt.nimbus.ReactiveJwksSignature;
import io.micronaut.security.token.jwt.signature.ReactiveSignatureConfiguration;
import io.micronaut.security.token.jwt.signature.jwks.JwkSetFetcher;
import io.micronaut.security.token.jwt.signature.jwks.JwkValidator;
import io.micronaut.security.token.jwt.signature.jwks.JwksSignatureConfiguration;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

/**
 * Tests that a token is checked only against its claimed issuer's keys, and how often this service
 * fetches them, asserted as request counts against the stub. Fetching and caching are delegated;
 * see ADR-0004.
 */
@DisplayName("Signature verification against a trusted issuer's keys")
class IssuerSignatureVerifierTest {

    /** Longest a test waits for discovery to resolve the stub provider. */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(30);

    /** Longest a test waits for an expired key-set cache to be refilled. */
    private static final Duration ROTATION_TIMEOUT = Duration.ofSeconds(30);

    /** Entry name of the provider under test. */
    private static final String PRIMARY = "primary";

    /** Entry name of the second, deliberately unreachable provider. */
    private static final String UNREACHABLE = "unreachable";

    /** Prefix of a trust-list entry's configuration properties. */
    private static final String PROVIDER_PREFIX = "consent-manager.identity-providers.";

    /**
     * Re-enables {@code micronaut-security}, which {@code application-test.yml} switches off, so
     * verification resolves through the bean graph a deployment uses.
     */
    private static final String SECURITY_ENABLED_PROPERTY = "micronaut.security.enabled";

    /** Property holding the lifetime of the module's shared JWK Set cache. */
    private static final String JWKS_CACHE_TTL_PROPERTY =
            "micronaut.caches.jwks.expire-after-write";

    /**
     * Cache lifetime for the rotation test: short enough not to sit out the production default,
     * long enough that "fetched exactly once" assertions do not race it.
     */
    private static final String SHORT_JWKS_CACHE_TTL = "2s";

    /** Cache lifetime for request-counting tests; pinned so no entry expires mid-test. */
    private static final String LONG_JWKS_CACHE_TTL = "10m";

    /** Audience the stub entries declare; signature verification never looks at it. */
    private static final String STUB_AUDIENCE = "consent-manager";

    /** Roles claim path the stub entries declare. */
    private static final List<String> STUB_ROLES_CLAIM = List.of("realm_access", "roles");

    /** Participant identifier claim path the stub entries declare. */
    private static final String STUB_PARTICIPANT_CLAIM = "participant_id";

    /** Raw role string the stub entries map {@link Role#USER} to. */
    private static final String STUB_USER_ROLE = "consent-user";

    /** Subject every test token carries; no claim is inspected on this path. */
    private static final String SUBJECT = "subject-under-test";

    /** Issuer no trust-list entry names. */
    private static final String UNREGISTERED_ISSUER = "https://not-on-the-trust-list.example";

    /** Discovery URL of the provider that is configured but can never resolve. */
    private static final String UNREACHABLE_DISCOVERY_URL =
            "http://localhost:1/realms/down/.well-known/openid-configuration";

    /** Issuer of the provider that is configured but can never resolve. */
    private static final String UNREACHABLE_ISSUER = "http://localhost:1/realms/down";

    /** Key identifier no provider in these tests publishes. */
    private static final String UNPUBLISHED_KEY_ID = "never-published";

    /** Key identifier the rotation test moves the provider to. */
    private static final String ROTATED_KEY_ID = "rotated-key";

    /** HTTP status the key endpoint answers with while it is modelled as down. */
    private static final int KEY_ENDPOINT_DOWN = 503;

    /** Longest a test waits for a key set cached before an outage to fall out of the cache. */
    private static final Duration OUTAGE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Tokens presented while the key endpoint is down and the cache entry gone; fired well inside
     * {@link IssuerSignatureVerifier#KEY_SET_UNAVAILABLE_COOLDOWN}.
     */
    private static final int VERIFICATIONS_DURING_OUTAGE = 25;

    /**
     * Verifications fired simultaneously against a never-loaded key set; enough that several
     * overlap the first load, few enough for the stub's capacity.
     */
    private static final int CONCURRENT_VERIFICATIONS = 8;

    /** Longest a test waits for one member of a concurrent burst to finish. */
    private static final Duration BURST_TIMEOUT = Duration.ofSeconds(30);

    @Test
    @DisplayName("a token signed by a key the issuer publishes verifies")
    void verifiesATokenSignedByAPublishedKey() {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context = startContext(provider, Map.of())) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);

            SignedJWT token =
                    provider.signToken(provider.initialKeyId(), claims(provider.issuer()));

            assertThat(verify(verifier, provider.issuer(), token))
                    .as("the provider's own key, presented against the provider's own issuer")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("verifies without a meter registry, as when metrics are switched off")
    void verifiesWithoutAMeterRegistry() {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context = startContext(provider, Map.of())) {
            awaitResolved(context, provider);
            IssuerSignatureVerifier unmetered = verifierWith(context, null);

            assertThat(
                            verify(
                                    unmetered,
                                    provider.issuer(),
                                    provider.signToken(
                                            provider.initialKeyId(), claims(provider.issuer()))))
                    .as("the outcome must not depend on whether a meter registry is bound")
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "{0} verifications")
    @ValueSource(ints = {2, 5, 20})
    @DisplayName("the key set is fetched once and served from cache within its lifetime")
    void fetchesTheKeySetOncePerCacheLifetime(int verifications) {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context =
                        startContext(
                                provider, Map.of(JWKS_CACHE_TTL_PROPERTY, LONG_JWKS_CACHE_TTL))) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);
            SignedJWT token =
                    provider.signToken(provider.initialKeyId(), claims(provider.issuer()));

            for (int i = 0; i < verifications; i++) {
                assertThat(verify(verifier, provider.issuer(), token)).isTrue();
            }

            assertThat(provider.jwksRequestCount())
                    .as(
                            "request volume must not be able to drive request volume at the"
                                    + " identity provider")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a key valid at another issuer does not verify a token claiming this one")
    void rejectsAKeyThatBelongsToAnotherIssuer() {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                OidcDiscoveryStub other = new OidcDiscoveryStub();
                ApplicationContext context = startContext(provider, Map.of())) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);

            // Both stubs publish the same kid, so only the routing can tell these tokens apart.
            SignedJWT forged = other.signToken(other.initialKeyId(), claims(provider.issuer()));

            assertThat(verify(verifier, provider.issuer(), forged))
                    .as(
                            "a token is offered to the keys of the issuer it claims and to no"
                                    + " others, so one trusted provider cannot mint tokens for"
                                    + " another")
                    .isFalse();
        }
    }

    @ParameterizedTest(name = "issuer: {0}")
    @NullSource
    @ValueSource(strings = {UNREGISTERED_ISSUER, UNREACHABLE_ISSUER})
    @DisplayName("an absent or unusable issuer costs no outbound request")
    void makesNoRequestForAnUnusableIssuer(String issuer) {
        Map<String, Object> unresolvable = new LinkedHashMap<>();
        unresolvable.put(PROVIDER_PREFIX + UNREACHABLE + ".issuer", UNREACHABLE_ISSUER);
        unresolvable.put(
                PROVIDER_PREFIX + UNREACHABLE + ".discovery-url", UNREACHABLE_DISCOVERY_URL);
        unresolvable.put(PROVIDER_PREFIX + UNREACHABLE + ".audience", STUB_AUDIENCE);
        unresolvable.put(PROVIDER_PREFIX + UNREACHABLE + ".allow-insecure-transport", true);
        unresolvable.put(
                PROVIDER_PREFIX + UNREACHABLE + ".claims.participant-identifier",
                STUB_PARTICIPANT_CLAIM);
        unresolvable.put(PROVIDER_PREFIX + UNREACHABLE + ".claims.roles", STUB_ROLES_CLAIM);
        unresolvable.put(PROVIDER_PREFIX + UNREACHABLE + ".role-mapping.user", STUB_USER_ROLE);

        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context = startContext(provider, unresolvable)) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);
            int fetchesBefore = provider.jwksRequestCount();

            SignedJWT token = provider.signToken(provider.initialKeyId(), claims(issuer));

            assertThat(verify(verifier, issuer, token))
                    .as(
                            "an unregistered issuer and a configured-but-unresolved one are the"
                                    + " same answer, so a caller cannot probe the trust list")
                    .isFalse();
            assertThat(provider.jwksRequestCount())
                    .as("the trust list is consulted before anything leaves this process")
                    .isEqualTo(fetchesBefore);
        }
    }

    @ParameterizedTest(name = "{0} tokens")
    @ValueSource(ints = {1, 5, 25})
    @DisplayName("tokens bearing an unpublished key identifier trigger no refetch")
    void unknownKeyIdentifiersTriggerNoRefetch(int tokens) {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                OidcDiscoveryStub forger = new OidcDiscoveryStub();
                ApplicationContext context =
                        startContext(
                                provider, Map.of(JWKS_CACHE_TTL_PROPERTY, LONG_JWKS_CACHE_TTL))) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);
            // Warm the cache with a legitimate verification, so what follows can only add fetches.
            verify(
                    verifier,
                    provider.issuer(),
                    provider.signToken(provider.initialKeyId(), claims(provider.issuer())));
            int fetchesAfterWarmUp = provider.jwksRequestCount();

            forger.rotateKeysTo(UNPUBLISHED_KEY_ID);
            SignedJWT forged = forger.signToken(UNPUBLISHED_KEY_ID, claims(provider.issuer()));
            for (int i = 0; i < tokens; i++) {
                assertThat(verify(verifier, provider.issuer(), forged)).isFalse();
            }

            assertThat(provider.jwksRequestCount())
                    .as(
                            "a flood of forged key identifiers must not become an amplifier"
                                    + " pointed at the identity provider")
                    .isEqualTo(fetchesAfterWarmUp);
        }
    }

    @Test
    @DisplayName("a cached key verifies through an outage, and stops once the cache expires")
    void verifiesFromCacheDuringAnOutageAndRejectsOnceItExpires() {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context =
                        startContext(
                                provider, Map.of(JWKS_CACHE_TTL_PROPERTY, SHORT_JWKS_CACHE_TTL))) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);
            SignedJWT token =
                    provider.signToken(provider.initialKeyId(), claims(provider.issuer()));
            assertThat(verify(verifier, provider.issuer(), token)).isTrue();

            provider.failJwksRequests(KEY_ENDPOINT_DOWN);

            assertThat(verify(verifier, provider.issuer(), token))
                    .as(
                            "an identity provider outage must not take authentication down with"
                                    + " it for as long as the keys are still cached")
                    .isTrue();

            // A failed fetch is never cached, so once the entry expires this provider's tokens are
            // refused until it answers again: the cache lifetime is the outage grace period.
            Await.until(
                    "the cached key set to expire and the outage to start refusing tokens",
                    OUTAGE_TIMEOUT,
                    () -> !verify(verifier, provider.issuer(), token));
        }
    }

    @Test
    @DisplayName("an outage does not turn inbound traffic into requests at the provider")
    void doesNotRelayTrafficOntoAProviderWhoseKeyEndpointIsDown() {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context =
                        startContext(
                                provider, Map.of(JWKS_CACHE_TTL_PROPERTY, SHORT_JWKS_CACHE_TTL))) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);
            SignedJWT token =
                    provider.signToken(provider.initialKeyId(), claims(provider.issuer()));
            assertThat(verify(verifier, provider.issuer(), token)).isTrue();

            provider.failJwksRequests(KEY_ENDPOINT_DOWN);
            Await.until(
                    "the cached key set to expire while the endpoint is down",
                    OUTAGE_TIMEOUT,
                    () -> !verify(verifier, provider.issuer(), token));
            int requestsWhenTheOutageWasNoticed = provider.jwksRequestCount();

            for (int i = 0; i < VERIFICATIONS_DURING_OUTAGE; i++) {
                assertThat(verify(verifier, provider.issuer(), token)).isFalse();
            }

            assertThat(provider.jwksRequestCount())
                    .as(
                            "a failed fetch is never cached, so without the verifier's cooldown"
                                    + " each of these would be its own request aimed at a provider"
                                    + " that is already unhealthy")
                    .isEqualTo(requestsWhenTheOutageWasNoticed);
        }
    }

    @Test
    @DisplayName("no JWKS-backed signature configuration is registered as a global verifier")
    void registersNoIssuerBlindJwksVerifier() {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context = startContext(provider, Map.of())) {
            awaitResolved(context, provider);

            assertThat(context.getBeansOfType(JwksSignatureConfiguration.class))
                    .as(
                            "ReactiveJwksSignature is @EachBean(JwksSignatureConfiguration.class),"
                                    + " so annotating JwksSignatureConfigurationAdapter as a bean"
                                    + " would silently mint one issuer-blind global verifier per"
                                    + " trust-list entry")
                    .isEmpty();
            assertThat(context.getBeansOfType(ReactiveSignatureConfiguration.class))
                    .as(
                            "a JWKS-backed verifier in this collection is tried against every"
                                    + " token regardless of its issuer, which is exactly the"
                                    + " cross-issuer key confusion IssuerSignatureVerifier exists"
                                    + " to prevent")
                    .noneMatch(ReactiveJwksSignature.class::isInstance);
        }
    }

    @Test
    @DisplayName("a rotated key is honoured once the cache expires, with no restart")
    void honoursARotatedKeyOnceTheCacheExpires() {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context =
                        startContext(
                                provider, Map.of(JWKS_CACHE_TTL_PROPERTY, SHORT_JWKS_CACHE_TTL))) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);
            SignedJWT beforeRotation =
                    provider.signToken(provider.initialKeyId(), claims(provider.issuer()));
            assertThat(verify(verifier, provider.issuer(), beforeRotation)).isTrue();

            provider.rotateKeysTo(ROTATED_KEY_ID);
            SignedJWT afterRotation = provider.signToken(ROTATED_KEY_ID, claims(provider.issuer()));

            Await.until(
                    "the rotated key to be honoured",
                    ROTATION_TIMEOUT,
                    () -> verify(verifier, provider.issuer(), afterRotation));
            assertThat(verify(verifier, provider.issuer(), beforeRotation))
                    .as("the retired key stops verifying once the new set is in hand")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a token is not checked against the keys of an issuer it does not claim")
    void rejectsATokenRoutedToAnIssuerItDoesNotClaim() {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context = startContext(provider, Map.of())) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);
            // Signed by a key this provider really publishes, but claiming somebody else's issuer.
            SignedJWT token =
                    provider.signToken(provider.initialKeyId(), claims(UNREGISTERED_ISSUER));

            assertThat(verify(verifier, provider.issuer(), token))
                    .as(
                            "routing is the whole point of this class, so a caller that passes an"
                                    + " issuer the token does not claim must not get a pass")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a key rotated while the key endpoint was down is honoured once it recovers")
    void honoursAKeyRotatedDuringAnOutage() {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context =
                        startContext(
                                provider, Map.of(JWKS_CACHE_TTL_PROPERTY, SHORT_JWKS_CACHE_TTL))) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);
            SignedJWT beforeOutage =
                    provider.signToken(provider.initialKeyId(), claims(provider.issuer()));
            assertThat(verify(verifier, provider.issuer(), beforeOutage)).isTrue();

            provider.failJwksRequests(KEY_ENDPOINT_DOWN);
            provider.rotateKeysTo(ROTATED_KEY_ID);
            SignedJWT afterRotation = provider.signToken(ROTATED_KEY_ID, claims(provider.issuer()));

            assertThat(verify(verifier, provider.issuer(), afterRotation))
                    .as(
                            "a key published while the endpoint was unreachable cannot have been"
                                    + " learned, so the token must not be accepted on trust")
                    .isFalse();

            provider.serveJwksNormally();

            Await.until(
                    "the key rotated during the outage to be honoured after recovery",
                    ROTATION_TIMEOUT,
                    () -> verify(verifier, provider.issuer(), afterRotation));
        }
    }

    @Test
    @DisplayName("concurrent first-time verifications converge on one cached key set")
    void concurrentFirstTimeVerificationsConvergeOnTheCache() throws Exception {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context =
                        startContext(
                                provider, Map.of(JWKS_CACHE_TTL_PROPERTY, LONG_JWKS_CACHE_TTL))) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);
            SignedJWT token =
                    provider.signToken(provider.initialKeyId(), claims(provider.issuer()));
            ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_VERIFICATIONS);
            CountDownLatch release = new CountDownLatch(1);
            try {
                List<Future<Boolean>> burst =
                        IntStream.range(0, CONCURRENT_VERIFICATIONS)
                                .mapToObj(
                                        i ->
                                                executor.submit(
                                                        () -> {
                                                            release.await();
                                                            return verify(
                                                                    verifier,
                                                                    provider.issuer(),
                                                                    token);
                                                        }))
                                .toList();
                release.countDown();
                for (Future<Boolean> result : burst) {
                    assertThat(result.get(BURST_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                            .as("every token in the burst is signed by a published key")
                            .isTrue();
                }

                int fetchesDuringBurst = provider.jwksRequestCount();
                assertThat(fetchesDuringBurst)
                        .as(
                                "a cold-start burst is bounded by concurrency at that instant, not"
                                        + " by traffic")
                        .isBetween(1, CONCURRENT_VERIFICATIONS);

                for (int i = 0; i < CONCURRENT_VERIFICATIONS; i++) {
                    assertThat(verify(verifier, provider.issuer(), token)).isTrue();
                }
                assertThat(provider.jwksRequestCount())
                        .as(
                                "once the burst has settled the cache serves everything, which is"
                                        + " the steady-state bound the documentation promises")
                        .isEqualTo(fetchesDuringBurst);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("counts a verified and a rejected signature under their own outcomes")
    void countsVerifiedAndRejectedOutcomes() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                OidcDiscoveryStub other = new OidcDiscoveryStub();
                ApplicationContext context = startContext(provider, Map.of())) {
            awaitResolved(context, provider);
            IssuerSignatureVerifier verifier = meteredVerifier(context, metrics);

            assertThat(
                            verify(
                                    verifier,
                                    provider.issuer(),
                                    provider.signToken(
                                            provider.initialKeyId(), claims(provider.issuer()))))
                    .isTrue();
            assertThat(outcomes(metrics, IssuerSignatureVerifier.OUTCOME_VERIFIED))
                    .as("a signature that held must count as verified")
                    .isEqualTo(1);

            assertThat(
                            verify(
                                    verifier,
                                    provider.issuer(),
                                    other.signToken(
                                            other.initialKeyId(), claims(provider.issuer()))))
                    .isFalse();
            assertThat(outcomes(metrics, IssuerSignatureVerifier.OUTCOME_REJECTED))
                    .as("a forged signature must count as rejected, not as unavailable")
                    .isEqualTo(1);

            assertThat(
                            verify(
                                    verifier,
                                    UNREGISTERED_ISSUER,
                                    provider.signToken(
                                            provider.initialKeyId(), claims(UNREGISTERED_ISSUER))))
                    .isFalse();
            assertThat(totalOutcomes(metrics))
                    .as(
                            "an issuer off the trust list reaches no provider, so it must leave the"
                                    + " per-provider counter alone")
                    .isEqualTo(2);
        } finally {
            metrics.close();
        }
    }

    @Test
    @DisplayName("counts an unreadable key set apart from a rejected signature")
    void countsAnUnreadableKeySetAsUnavailable() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context = startContext(provider, Map.of())) {
            awaitResolved(context, provider);
            IssuerSignatureVerifier verifier = meteredVerifier(context, metrics);
            provider.failJwksRequests(KEY_ENDPOINT_DOWN);

            assertThat(
                            verify(
                                    verifier,
                                    provider.issuer(),
                                    provider.signToken(
                                            provider.initialKeyId(), claims(provider.issuer()))))
                    .isFalse();

            assertThat(outcomes(metrics, IssuerSignatureVerifier.OUTCOME_UNAVAILABLE))
                    .as(
                            "a provider outage and a flood of forged tokens look identical in the"
                                    + " response, so only this tag tells them apart")
                    .isEqualTo(1);
            assertThat(outcomes(metrics, IssuerSignatureVerifier.OUTCOME_REJECTED)).isZero();
        } finally {
            metrics.close();
        }
    }

    /**
     * Builds a verifier on the context's real collaborators, publishing to a private meter registry
     * so the assertions do not depend on a configured metrics backend.
     */
    private static IssuerSignatureVerifier meteredVerifier(
            ApplicationContext context, SimpleMeterRegistry metrics) {
        return verifierWith(context, metrics);
    }

    /** Builds a verifier on the context's real collaborators; {@code null} binds no registry. */
    private static IssuerSignatureVerifier verifierWith(
            ApplicationContext context, @Nullable MeterRegistry metrics) {
        return new IssuerSignatureVerifier(
                context.getBean(IdentityProviderRegistry.class),
                context.getBean(JwkValidator.class),
                context.getBean(Argument.of(JwkSetFetcher.class, JWKSet.class)),
                metrics);
    }

    /** Count for one {@code outcome} tag of the primary provider; zero if not yet registered. */
    private static double outcomes(SimpleMeterRegistry metrics, String outcome) {
        Counter counter =
                metrics.find(IssuerSignatureVerifier.SIGNATURE_VERIFICATION_METRIC)
                        .tag(IssuerSignatureVerifier.PROVIDER_TAG, PRIMARY)
                        .tag(IssuerSignatureVerifier.OUTCOME_TAG, outcome)
                        .counter();
        return counter == null ? 0 : counter.count();
    }

    /** Sums the counts across every outcome tag, pinning that a check is counted exactly once. */
    private static double totalOutcomes(SimpleMeterRegistry metrics) {
        return metrics
                .find(IssuerSignatureVerifier.SIGNATURE_VERIFICATION_METRIC)
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    /** Blocks on one signature check against the claimed issuer's keys. */
    private static boolean verify(
            IssuerSignatureVerifier verifier, String issuer, SignedJWT token) {
        return Boolean.TRUE.equals(Mono.from(verifier.verify(issuer, token)).block());
    }

    /** Builds a test token's claim set; only {@code iss} matters on the signature path. */
    private static JWTClaimsSet claims(String issuer) {
        return new JWTClaimsSet.Builder().issuer(issuer).subject(SUBJECT).build();
    }

    /** Waits for the stub provider to resolve and returns the verifier bound to that context. */
    private static IssuerSignatureVerifier awaitResolved(
            ApplicationContext context, OidcDiscoveryStub provider) {
        IdentityProviderRegistry registry = context.getBean(IdentityProviderRegistry.class);
        Await.until(
                "the stub provider to resolve",
                RESOLUTION_TIMEOUT,
                () -> registry.findByIssuer(provider.issuer()).isPresent());
        return context.getBean(IssuerSignatureVerifier.class);
    }

    /** Starts a context trusting the given stub under {@link #PRIMARY}; the caller closes it. */
    private static ApplicationContext startContext(
            OidcDiscoveryStub provider, Map<String, Object> overrides) {
        String prefix = PROVIDER_PREFIX + PRIMARY + ".";
        Map<String, Object> properties = new LinkedHashMap<>();
        // The verifier is built from io.micronaut.security JWKS beans, which application-test.yml
        // switches off, so this context turns security back on.
        properties.put(SECURITY_ENABLED_PROPERTY, true);
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.put("micronaut.server.port", -1);
        properties.put(prefix + "issuer", provider.issuer());
        properties.put(prefix + "discovery-url", provider.discoveryUrl());
        properties.put(prefix + "audience", STUB_AUDIENCE);
        // WireMock serves plain http, so the entry has to opt out of the https requirement.
        properties.put(prefix + "allow-insecure-transport", true);
        properties.put(prefix + "claims.participant-identifier", List.of(STUB_PARTICIPANT_CLAIM));
        properties.put(prefix + "claims.roles", STUB_ROLES_CLAIM);
        properties.put(prefix + "role-mapping.user", STUB_USER_ROLE);
        properties.putAll(overrides);
        return ApplicationContext.builder().environments("test").properties(properties).start();
    }
}
