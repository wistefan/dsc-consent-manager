package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import io.micronaut.context.ApplicationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

/**
 * Pins the signature half of the trust chain: whose keys a token is checked against, and how often
 * this service is willing to ask a provider for them.
 *
 * <p>The fetching, caching, {@code kid} matching and signature verification themselves belong to
 * {@code micronaut-security-jwt} (see {@code
 * docs/adr/0004-delegate-jwks-retrieval-and-caching-to-micronaut-security.md}) and are not retested
 * here. What is tested is everything that decision leaves to this service or makes observable from
 * outside it: that a token is only ever offered to the keys of the issuer it claims, that an issuer
 * which is not on the trust list costs no outbound request at all, and that the one cache lifetime
 * bounds both how fast a rotated key is honoured and how much traffic an attacker can aim at a
 * provider through this service.
 *
 * <p>Every assertion that matters is a <em>request count</em> against the stub rather than a return
 * value, because a cache that silently stopped caching would still return the right answers.
 *
 * <p>Each test owns its context and its stubs so that one test's cached key set cannot satisfy
 * another's fetch-count assertion.
 */
@DisplayName("Signature verification against a trusted issuer's keys")
class IssuerSignatureVerifierIT {

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
     * Property that re-enables {@code micronaut-security}.
     *
     * <p>{@code src/test/resources/application-test.yml} sets this to {@code false} so that the
     * tests written before this ticket are not asked for a token. Every bean this test needs is
     * conditional on it, so each context started here sets it back to {@code true}.
     */
    private static final String SECURITY_ENABLED_PROPERTY = "micronaut.security.enabled";

    /** Property holding the lifetime of the module's shared JWK Set cache. */
    private static final String JWKS_CACHE_TTL_PROPERTY =
            "micronaut.caches.jwks.expire-after-write";

    /**
     * Cache lifetime used by the rotation test.
     *
     * <p>Short enough that a test need not sit out the production default, long enough that the
     * tests which assert "fetched exactly once" are not racing it.
     */
    private static final String SHORT_JWKS_CACHE_TTL = "2s";

    /** Audience the stub entries declare; signature verification never looks at it. */
    private static final String STUB_AUDIENCE = "consent-manager";

    /** Roles claim path the stub entries declare. */
    private static final String STUB_ROLES_CLAIM = "realm_access.roles";

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

    /**
     * Verifications fired simultaneously against a provider whose key set has never been loaded.
     *
     * <p>Large enough that several threads are genuinely inside the first load at once, small
     * enough to stay well inside the stub's capacity.
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

    @ParameterizedTest(name = "{0} verifications")
    @ValueSource(ints = {2, 5, 20})
    @DisplayName("the key set is fetched once and served from cache within its lifetime")
    void fetchesTheKeySetOncePerCacheLifetime(int verifications) {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context = startContext(provider, Map.of())) {
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
    @ValueSource(strings = {UNREGISTERED_ISSUER, UNREACHABLE_ISSUER})
    @DisplayName("an issuer that is not usable costs no outbound request")
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
                ApplicationContext context = startContext(provider, Map.of())) {
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
    @DisplayName("a cached key keeps verifying while the key endpoint is down")
    void keepsVerifyingWhileTheKeyEndpointIsDown() {
        try (OidcDiscoveryStub provider = new OidcDiscoveryStub();
                ApplicationContext context = startContext(provider, Map.of())) {
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
                ApplicationContext context = startContext(provider, Map.of())) {
            IssuerSignatureVerifier verifier = awaitResolved(context, provider);
            SignedJWT token =
                    provider.signToken(provider.initialKeyId(), claims(provider.issuer()));
            ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_VERIFICATIONS);
            CountDownLatch release = new CountDownLatch(1);
            try {
                List<Future<Boolean>> burst = new ArrayList<>();
                for (int i = 0; i < CONCURRENT_VERIFICATIONS; i++) {
                    burst.add(
                            executor.submit(
                                    () -> {
                                        release.await();
                                        return verify(verifier, provider.issuer(), token);
                                    }));
                }
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

    /**
     * Blocks on one signature check.
     *
     * @param verifier the verifier under test
     * @param issuer the issuer the token claims
     * @param token the token to check
     * @return whether the signature verified against that issuer's keys
     */
    private static boolean verify(
            IssuerSignatureVerifier verifier, String issuer, SignedJWT token) {
        return Boolean.TRUE.equals(Mono.from(verifier.verify(issuer, token)).block());
    }

    /**
     * Builds the claim set of a test token.
     *
     * <p>Only {@code iss} carries meaning on the signature path; it is set so that a token read
     * back in a failure message says which issuer it claimed.
     *
     * @param issuer the issuer to claim
     * @return the claim set
     */
    private static JWTClaimsSet claims(String issuer) {
        return new JWTClaimsSet.Builder().issuer(issuer).subject(SUBJECT).build();
    }

    /**
     * Waits for the stub provider to resolve and returns the verifier bound to that context.
     *
     * @param context the started application context
     * @param provider the stub whose discovery must have succeeded
     * @return the verifier under test
     */
    private static IssuerSignatureVerifier awaitResolved(
            ApplicationContext context, OidcDiscoveryStub provider) {
        IdentityProviderRegistry registry = context.getBean(IdentityProviderRegistry.class);
        Await.until(
                "the stub provider to resolve",
                RESOLUTION_TIMEOUT,
                () -> registry.findByIssuer(provider.issuer()).isPresent());
        return context.getBean(IssuerSignatureVerifier.class);
    }

    /**
     * Starts a context trusting the given stub, plus any extra properties the test needs.
     *
     * @param provider the stub to put on the trust list under {@link #PRIMARY}
     * @param overrides extra configuration, applied last
     * @return the started context, which the caller closes
     */
    private static ApplicationContext startContext(
            OidcDiscoveryStub provider, Map<String, Object> overrides) {
        String prefix = PROVIDER_PREFIX + PRIMARY + ".";
        Map<String, Object> properties = new LinkedHashMap<>();
        // The module's JWKS components - JwkValidator, JwkSetFetcher - are beans of the
        // io.micronaut.security package, which application-test.yml switches off wholesale for
        // the tests that predate this ticket. The verifier under test is built from them, so this
        // context has to turn security back on.
        properties.put(SECURITY_ENABLED_PROPERTY, true);
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.put("micronaut.server.port", -1);
        properties.put(prefix + "issuer", provider.issuer());
        properties.put(prefix + "discovery-url", provider.discoveryUrl());
        properties.put(prefix + "audience", STUB_AUDIENCE);
        // WireMock serves plain http, so the entry has to opt out of the https requirement.
        properties.put(prefix + "allow-insecure-transport", true);
        properties.put(prefix + "claims.participant-identifier", STUB_PARTICIPANT_CLAIM);
        properties.put(prefix + "claims.roles", STUB_ROLES_CLAIM);
        properties.put(prefix + "role-mapping.user", STUB_USER_ROLE);
        properties.putAll(overrides);
        return ApplicationContext.builder().environments("test").properties(properties).start();
    }
}
