package com.seamware.consentmanager.security;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * End-to-end checks that only a token signed by a trusted provider's published key authenticates.
 *
 * <p>Signature verification is delegated to Micronaut Security rather than reimplemented: each
 * {@code micronaut.security.oauth2.clients.<name>.openid.issuer} entry makes Micronaut fetch that
 * provider's OpenID discovery document, read its {@code jwks_uri} and register a JWKS-backed {@code
 * SignatureConfiguration} over the keys it publishes. This test stands a WireMock OpenID provider
 * in front of that machinery and drives real HTTP requests through a secured route.
 *
 * <p>What it does <em>not</em> cover is recovery: discovery is attempted once and is not retried,
 * so {@link #refusesGenuineTokenDuringOutage()} pins the fail-closed behaviour of a provider that
 * was unreachable, not an eventual return to service. Retry with backoff, readiness reporting and
 * rate-limited key refetch are still to be built.
 *
 * <p>The decisive case is {@code alg: none}. Micronaut's {@code
 * AbstractJsonWebTokenValidator.validateSignature} treats an <em>empty</em> set of signature
 * configurations as "nothing to check against" and returns {@code true}, so an unsigned token would
 * authenticate whenever no provider has contributed a key — during startup, throughout an identity
 * provider outage, and permanently if an issuer is misconfigured. {@link UnsignedTokenRejector}
 * keeps that set permanently non-empty. Both halves are asserted here: against a reachable provider
 * and against an unreachable one, where no key material exists at all.
 */
@DisplayName("Token signature enforcement")
class TokenSignatureEnforcementIT {

    /** Property that activates the secured probe route, so it exists only for this test. */
    private static final String PROBE_ROUTE_ENABLED = "test.token-signature-enforcement.enabled";

    /** Path of the secured probe route. */
    private static final String PROBE_PATH = "/token-signature-probe";

    /** Name shared by both halves of the trust-list entry under test. */
    private static final String PROVIDER = "primary";

    /** Realm path served by the WireMock provider. */
    private static final String REALM_PATH = "/realms/dataspace";

    /** Audience this service expects, and which the minted tokens carry. */
    private static final String AUDIENCE = "consent-manager";

    /** Key identifier published in the stubbed JWKS document. */
    private static final String SIGNING_KEY_ID = "signing-key-1";

    /** Size of the generated RSA keys, large enough for RS256. */
    private static final int RSA_KEY_SIZE = 2048;

    /** Lifetime given to every minted token, comfortably beyond the test's runtime. */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(10);

    /** An HMAC secret long enough for HS256, used to mint a symmetrically signed token. */
    private static final String SYMMETRIC_SECRET =
            "a-symmetric-secret-that-is-long-enough-for-hs256-abcdefghijklmnop";

    /** Issuer URL that refuses connections instantly, so discovery can never succeed. */
    private static final String UNREACHABLE_ISSUER = "http://localhost:1" + REALM_PATH;

    /**
     * Path micronaut-security-oauth2 serves its protected-resource metadata on by default.
     *
     * <p>{@code ProtectedResourceMetadataController} is annotated
     * {@code @Secured(SecurityRule.IS_ANONYMOUS)} and {@code
     * DefaultProtectedResourceMetadataProvider} renders the injected {@code
     * List<OpenIdClientConfiguration>}, so while the endpoint is enabled any unauthenticated caller
     * can read the configured issuer URLs.
     */
    private static final String PROTECTED_RESOURCE_METADATA_PATH =
            "/.well-known/oauth-protected-resource";

    /** Property {@code application.yml} sets to remove the metadata controller. */
    private static final String PROTECTED_RESOURCE_METADATA_ENABLED =
            "micronaut.security.oauth2.protected-resource-metadata.enabled";

    /**
     * Property {@code application.yml} sets to stop the metadata hint being appended to {@code
     * WWW-Authenticate}. It is separate from {@link #PROTECTED_RESOURCE_METADATA_ENABLED}: the
     * challenge provider has its own {@code @Requires} on this name and ignores the other flag.
     */
    private static final String PROTECTED_RESOURCE_METADATA_WWW_AUTHENTICATE =
            "micronaut.security.oauth2.protected-resource-metadata.www-authenticate";

    /** Parameter the challenge provider appends to {@code WWW-Authenticate} while it is active. */
    private static final String RESOURCE_METADATA_CHALLENGE_PARAMETER = "resource_metadata";

    private static WireMockServer provider;
    private static String reachableIssuer;
    private static RSAKey publishedKey;
    private static RSAKey foreignKey;

    private static ApplicationContext reachableContext;
    private static EmbeddedServer reachableServer;
    private static HttpClient reachableClient;

    private static ApplicationContext unreachableContext;
    private static EmbeddedServer unreachableServer;
    private static HttpClient unreachableClient;

    /**
     * Generates the key material, starts the WireMock OpenID provider and boots the two servers
     * under test: one trusting the reachable provider, one trusting an unreachable one.
     *
     * @throws JOSEException if key generation fails
     */
    @BeforeAll
    static void startFixtures() throws JOSEException {
        publishedKey = new RSAKeyGenerator(RSA_KEY_SIZE).keyID(SIGNING_KEY_ID).generate();
        foreignKey = new RSAKeyGenerator(RSA_KEY_SIZE).keyID(SIGNING_KEY_ID).generate();

        provider = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        provider.start();
        reachableIssuer = "http://localhost:" + provider.port() + REALM_PATH;

        provider.stubFor(
                get(REALM_PATH + "/.well-known/openid-configuration")
                        .willReturn(okJson(discoveryDocument(reachableIssuer))));
        provider.stubFor(
                get(REALM_PATH + "/certs")
                        .willReturn(
                                okJson(
                                        "{\"keys\":["
                                                + publishedKey.toPublicJWK().toJSONString()
                                                + "]}")));

        reachableContext = startContext(reachableIssuer, Map.of());
        reachableServer = reachableContext.getBean(EmbeddedServer.class).start();
        reachableClient = HttpClient.create(reachableServer.getURL());

        // Trusts an issuer that can never be discovered. Its tokens carry that same `iss` and the
        // expected `aud`, so the missing signature configuration is the only possible ground for
        // rejection. See startContext.
        unreachableContext = startContext(UNREACHABLE_ISSUER, Map.of());
        unreachableServer = unreachableContext.getBean(EmbeddedServer.class).start();
        unreachableClient = HttpClient.create(unreachableServer.getURL());
    }

    /** Shuts the servers, contexts and the WireMock provider down. */
    @AfterAll
    static void stopFixtures() {
        closeQuietly(reachableClient);
        closeQuietly(reachableServer);
        closeQuietly(reachableContext);
        closeQuietly(unreachableClient);
        closeQuietly(unreachableServer);
        closeQuietly(unreachableContext);
        if (provider != null) {
            provider.stop();
        }
    }

    /**
     * Closes a fixture, ignoring a {@code null} that an earlier failure left behind.
     *
     * @param closeable the fixture to close, possibly {@code null}
     */
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            throw new IllegalStateException("failed to close a test fixture", e);
        }
    }

    /**
     * Builds a minimal OpenID provider metadata document.
     *
     * @param issuer the issuer the document describes
     * @return the discovery document as JSON
     */
    private static String discoveryDocument(String issuer) {
        return "{\"issuer\":\""
                + issuer
                + "\",\"jwks_uri\":\""
                + issuer
                + "/certs\",\"authorization_endpoint\":\""
                + issuer
                + "/auth\",\"token_endpoint\":\""
                + issuer
                + "/token\",\"response_types_supported\":[\"code\"],"
                + "\"subject_types_supported\":[\"public\"],"
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
    }

    /**
     * Starts an isolated context trusting exactly one issuer.
     *
     * <p>The interim claims validators are pointed at that same issuer and audience, because {@link
     * IdentityProviderRegistryValidator} refuses to start a context in which they diverge from the
     * trust list. The outage context therefore trusts an issuer that can never be discovered
     * <em>and</em> accepts the {@code iss} its tokens carry, which is what leaves the missing
     * signature configuration as the only possible ground for rejection. Were the validator pointed
     * anywhere else, every outage assertion would be satisfied by an issuer mismatch and {@link
     * UnsignedTokenRejector} could be deleted without a single test going red.
     *
     * @param trustedIssuer the issuer registered as an OpenID client, whose discovery document
     *     supplies the signing keys and which minted tokens must name in {@code iss}
     * @param overrides properties layered on top, for control cases that switch a bean off
     * @return the started context
     */
    private static ApplicationContext startContext(
            String trustedIssuer, Map<String, Object> overrides) {
        String settings = IdentityProviderConfiguration.PREFIX + "." + PROVIDER;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PROBE_ROUTE_ENABLED, true);
        properties.put("micronaut.security.enabled", true);
        properties.put("micronaut.server.port", -1);
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.put("endpoints.all.enabled", false);
        properties.put(
                IdentityProviderConfiguration.OIDC_CLIENTS_PREFIX
                        + "."
                        + PROVIDER
                        + ".openid.issuer",
                trustedIssuer);
        properties.put(settings + ".audience", AUDIENCE);
        // Mirrors the interim claims validators configured in application.yml. Default property
        // sources are disabled for this context, so they have to be restated here; without them
        // Micronaut's built-in validator checks neither `iss` nor `aud`.
        properties.put("micronaut.security.token.jwt.claims-validators.issuer", trustedIssuer);
        properties.put("micronaut.security.token.jwt.claims-validators.audience", AUDIENCE);
        properties.put(settings + ".claims.participant-identifier", "participant_id");
        properties.put(settings + ".claims.roles", "realm_access.roles");
        properties.put(settings + ".role-mapping.user", "consent-user");
        properties.putAll(overrides);

        return ApplicationContext.builder()
                .enableDefaultPropertySources(false)
                .properties(properties)
                .build()
                .start();
    }

    /**
     * Builds the claim set every minted token carries.
     *
     * @return claims with a valid issuer, audience, subject and expiry
     */
    private static JWTClaimsSet claims() {
        return claims(reachableIssuer, AUDIENCE);
    }

    /**
     * Builds a claim set with the given issuer and audience, leaving the rest valid.
     *
     * @param issuer the value of the {@code iss} claim
     * @param audience the value of the {@code aud} claim
     * @return the claim set
     */
    private static JWTClaimsSet claims(String issuer, String audience) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("a-subject")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plus(TOKEN_LIFETIME)))
                .build();
    }

    /**
     * Mints an unsigned {@code alg: none} token naming the given issuer.
     *
     * @param issuer the {@code iss} the token claims
     * @return the serialized token
     */
    private static String unsignedToken(String issuer) {
        return new PlainJWT(claims(issuer, AUDIENCE)).serialize();
    }

    /**
     * Mints an RS256 token signed with the given key.
     *
     * @param key the signing key
     * @return the serialized token
     */
    private static String rsaToken(RSAKey key) {
        return rsaToken(key, claims());
    }

    /**
     * Mints an RS256 token signed with the given key and naming the given issuer.
     *
     * @param key the signing key
     * @param issuer the {@code iss} the token claims
     * @return the serialized token
     */
    private static String rsaToken(RSAKey key, String issuer) {
        return rsaToken(key, claims(issuer, AUDIENCE));
    }

    /**
     * Mints an RS256 token signed with the given key and carrying the given claims.
     *
     * @param key the signing key
     * @param claims the claim set to sign
     * @return the serialized token
     */
    private static String rsaToken(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                            claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to mint an RS256 token", e);
        }
    }

    /**
     * Mints a token signed with a key the provider never published.
     *
     * @param issuer the {@code iss} the token claims
     * @return the serialized token
     */
    private static String foreignlySignedToken(String issuer) {
        return rsaToken(foreignKey, issuer);
    }

    /**
     * Mints a correctly signed token whose {@code aud} names a different service.
     *
     * <p>The signature is valid, so only the audience check can refuse it. Until the per-provider
     * validator lands, that check is the interim global {@code claims-validators.audience} setting
     * this service configures; this case is what keeps it from being dropped unnoticed.
     *
     * @param issuer the {@code iss} the token claims
     * @return the serialized token
     */
    private static String foreignAudienceToken(String issuer) {
        return rsaToken(publishedKey, claims(issuer, "some-other-service"));
    }

    /**
     * Mints a correctly signed token whose {@code iss} names a different provider.
     *
     * <p>As above, the signature is valid: the token is refused only because the issuer does not
     * match the one this service trusts.
     *
     * @return the serialized token
     */
    private static String foreignIssuerToken() {
        return rsaToken(publishedKey, claims("https://not-the-trusted-issuer.example", AUDIENCE));
    }

    /**
     * Mints an HS256 token bearing the published key identifier, the classic key-confusion shape.
     *
     * @param issuer the {@code iss} the token claims
     * @return the serialized token
     */
    private static String symmetricToken(String issuer) {
        try {
            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(SIGNING_KEY_ID).build(),
                            claims(issuer, AUDIENCE));
            jwt.sign(new MACSigner(SYMMETRIC_SECRET));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to mint an HS256 token", e);
        }
    }

    /**
     * Calls the secured probe route and reports the status it answered with.
     *
     * @param client a client bound to one of the servers under test
     * @param token the bearer token, or {@code null} to send no {@code Authorization} header
     * @return the HTTP status code; {@link HttpStatus} itself is a {@link CharSequence}, which
     *     makes an AssertJ assertion on it ambiguous, so the numeric code is returned instead
     */
    private static int statusFor(HttpClient client, String token) {
        return statusFor(client, PROBE_PATH, token);
    }

    /**
     * Issues a GET against an arbitrary path and reports the status code.
     *
     * @param client the client bound to the server under test
     * @param path the path to request
     * @param token the bearer token to present, or {@code null} to call anonymously
     * @return the HTTP status code, including error statuses
     */
    private static int statusFor(HttpClient client, String path, String token) {
        HttpRequest<?> request =
                token == null ? HttpRequest.GET(path) : HttpRequest.GET(path).bearerAuth(token);
        try {
            return client.toBlocking().exchange(request).getStatus().getCode();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    /**
     * Supplies the token shapes that must never authenticate against the given issuer.
     *
     * <p>The issuer is a parameter because each context under test trusts a different one and
     * refuses any {@code iss} but its own. A forged token whose issuer does not match the context
     * it is presented to would be refused for that reason alone, which would mask the forgery the
     * case exists to pin.
     *
     * @param issuer the {@code iss} the context under test trusts
     * @return case name and a supplier that mints the token when the case runs
     */
    private static Stream<Arguments> forgedTokens(String issuer) {
        return Stream.of(
                Arguments.of("no Authorization header", (Supplier<String>) () -> null),
                Arguments.of(
                        "an unsigned alg:none token",
                        (Supplier<String>) () -> unsignedToken(issuer)),
                Arguments.of(
                        "an RS256 token signed with a key the provider does not publish",
                        (Supplier<String>) () -> foreignlySignedToken(issuer)),
                Arguments.of(
                        "an HS256 token claiming the published key id",
                        (Supplier<String>) () -> symmetricToken(issuer)),
                Arguments.of("a syntactically invalid token", (Supplier<String>) () -> "not-a-jwt"),
                Arguments.of(
                        "a genuinely signed token minted for another audience",
                        (Supplier<String>) () -> foreignAudienceToken(issuer)),
                Arguments.of(
                        "a genuinely signed token claiming another issuer",
                        (Supplier<String>) TokenSignatureEnforcementIT::foreignIssuerToken));
    }

    /**
     * Supplies the forged shapes aimed at the reachable provider.
     *
     * @return case name and token supplier
     */
    static Stream<Arguments> forgedTokensForReachableProvider() {
        return forgedTokens(reachableIssuer);
    }

    /**
     * Supplies the forged shapes aimed at the unreachable provider.
     *
     * @return case name and token supplier
     */
    static Stream<Arguments> forgedTokensForUnreachableProvider() {
        return forgedTokens(UNREACHABLE_ISSUER);
    }

    @ParameterizedTest(name = "{0} is refused")
    @MethodSource("forgedTokensForReachableProvider")
    @DisplayName("refuses every token the trusted provider did not sign")
    void refusesForgedTokens(String description, Supplier<String> token) {
        assertThat(statusFor(reachableClient, token.get()))
                .as("%s must not authenticate", description)
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("accepts a token signed with the key the provider publishes")
    void acceptsGenuineToken() {
        assertThat(statusFor(reachableClient, rsaToken(publishedKey)))
                .as("a token signed with the published JWKS key must authenticate")
                .isEqualTo(HttpStatus.OK.getCode());
    }

    @ParameterizedTest(name = "{0} is refused while the provider is unreachable")
    @MethodSource("forgedTokensForUnreachableProvider")
    @DisplayName("refuses every token while no provider has contributed a signing key")
    void refusesForgedTokensDuringOutage(String description, Supplier<String> token) {
        assertThat(statusFor(unreachableClient, token.get()))
                .as(
                        "%s must not authenticate when discovery has failed and the set of"
                                + " signature configurations would otherwise be empty",
                        description)
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("refuses a genuinely signed token while the provider is unreachable")
    void refusesGenuineTokenDuringOutage() {
        assertThat(statusFor(unreachableClient, rsaToken(publishedKey, UNREACHABLE_ISSUER)))
                .as("with no usable signing key the service must fail closed")
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
    }

    /**
     * Control case proving {@link UnsignedTokenRejector} is what refuses the unsigned token.
     *
     * <p>Every other assertion about that bean is an assertion that a token is <em>rejected</em>,
     * and a token is rejected for many reasons, so all of them would stay green if the bean were
     * deleted as apparently dead code. Here the bean is switched off against the same unreachable
     * provider, leaving the set of signature configurations genuinely empty, and the same {@code
     * alg: none} token is required to be <em>accepted</em>. If this case ever stops returning 200,
     * either the bypass has gone away in the framework or the context no longer reproduces it, and
     * {@link #refusesForgedTokensDuringOutage} has stopped proving anything.
     *
     * <p>The context is built fresh rather than shared, so the disabling property cannot leak into
     * any other case.
     */
    @Test
    @DisplayName("control: the unsigned token authenticates once the rejector is switched off")
    void acceptsUnsignedTokenWithoutTheRejector() {
        try (ApplicationContext context =
                startContext(
                        UNREACHABLE_ISSUER,
                        Map.of(UnsignedTokenRejector.ENABLED_PROPERTY, false))) {
            EmbeddedServer server = context.getBean(EmbeddedServer.class).start();
            try (HttpClient client = HttpClient.create(server.getURL())) {
                assertThat(context.findBean(UnsignedTokenRejector.class))
                        .as("the control case must actually remove the bean")
                        .isEmpty();
                assertThat(statusFor(client, unsignedToken(UNREACHABLE_ISSUER)))
                        .as(
                                "without the rejector an empty set of signature configurations"
                                        + " makes Micronaut treat an unsigned token as verified, which"
                                        + " is the bypass UnsignedTokenRejector exists to close")
                        .isEqualTo(HttpStatus.OK.getCode());
            }
        }
    }

    /**
     * Asserts that adding micronaut-security-oauth2 has not silently exposed the trust list.
     *
     * <p>The module enables {@code ProtectedResourceMetadataController} by default, and that
     * controller answers anonymous callers with the configured issuer URLs. Its sibling {@code
     * ResourceMetadataWwwAuthenticateChallengeProvider} points at the same document from every 401.
     * This service deliberately discloses nothing about which issuers it trusts - a rejected token
     * gets a generic 401 that never echoes the issuer back - so {@code application.yml} switches
     * both off.
     *
     * <p>Unlike every other case here, these contexts are built from the <strong>real</strong>
     * property sources rather than an inline map. Restating the disabling flags in a map would only
     * prove that the flags work, which is Micronaut's concern; the claim worth pinning is that
     * {@code application.yml} actually sets them. Security has to be forced on because {@code
     * application-test.yml} disables it, and with it disabled the endpoint would be absent for the
     * wrong reason and the assertion would hold vacuously.
     *
     * <p>The removed route answers 401, not 404, because the security filter rejects an anonymous
     * request to any path carrying no explicit anonymous grant before routing decides whether a
     * route exists - which is exactly what stops the absence from being observable. A 401 on its
     * own would therefore be no evidence at all, so {@link #publishesProtectedResourceMetadata()}
     * is the control: with the flags overridden the same request is served.
     */
    @Test
    @DisplayName("does not publish the trust list at the OAuth2 protected-resource metadata path")
    void doesNotPublishProtectedResourceMetadata() {
        HttpResponse<?> response = probeProtectedResourceMetadata(Map.of());

        assertThat(response.getStatus().getCode())
                .as("an unauthenticated caller must not be able to read the trust list")
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
        assertThat(response.getHeaders().getAll(HttpHeaders.WWW_AUTHENTICATE))
                .as("the rejection must not point the caller at the metadata document either")
                .noneMatch(challenge -> challenge.contains(RESOURCE_METADATA_CHALLENGE_PARAMETER));
    }

    /**
     * Control for {@link #doesNotPublishProtectedResourceMetadata()}: with both flags overridden
     * back to their Micronaut defaults the endpoint is served to an anonymous caller, so the
     * rejection asserted there is the doing of {@code application.yml} rather than a status this
     * probe would have produced for any path at all.
     */
    @Test
    @DisplayName("control: the metadata endpoint is anonymous once the flags are put back")
    void publishesProtectedResourceMetadata() {
        HttpResponse<?> response =
                probeProtectedResourceMetadata(
                        Map.of(
                                PROTECTED_RESOURCE_METADATA_ENABLED, true,
                                PROTECTED_RESOURCE_METADATA_WWW_AUTHENTICATE, true));

        assertThat(response.getStatus().getCode())
                .as("with the flags removed the module serves the document without a token")
                .isEqualTo(HttpStatus.OK.getCode());
    }

    /**
     * Starts a context from the real property sources and requests the protected-resource metadata
     * path without a token.
     *
     * @param overrides properties layered on top of {@code application.yml} for this probe
     * @return the response, whether the server accepted or rejected the request
     */
    private static HttpResponse<?> probeProtectedResourceMetadata(Map<String, Object> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("micronaut.security.enabled", true);
        properties.put("micronaut.server.port", -1);
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.putAll(overrides);

        try (ApplicationContext context =
                ApplicationContext.builder(Environment.TEST).properties(properties).build()) {
            context.start();
            EmbeddedServer server = context.getBean(EmbeddedServer.class).start();
            try (HttpClient client = HttpClient.create(server.getURL())) {
                try {
                    return client.toBlocking()
                            .exchange(HttpRequest.GET(PROTECTED_RESOURCE_METADATA_PATH));
                } catch (HttpClientResponseException e) {
                    return e.getResponse();
                }
            }
        }
    }

    /**
     * Supplies {@code iss} values that are <em>not</em> the trusted issuer but that the interim
     * global validator nevertheless accepts.
     *
     * @return case name and the {@code iss} value to mint a token with
     */
    static Stream<Arguments> looselyMatchedIssuers() {
        return Stream.of(
                Arguments.of("a bare trailing path segment", (Supplier<String>) () -> "dataspace"),
                Arguments.of(
                        "the same URL with the scheme swapped",
                        (Supplier<String>)
                                () -> "https" + reachableIssuer.substring("http".length())),
                Arguments.of(
                        "the path without the authority",
                        (Supplier<String>) () -> REALM_PATH.substring(1)));
    }

    /**
     * Pins the known looseness of the interim issuer check, so step 5 cannot inherit the assumption
     * that the issuer is already matched exactly.
     *
     * <p>This test asserts that these tokens are <strong>accepted</strong>, which is the opposite
     * of what the finished service must do. That is deliberate. Micronaut's {@code
     * IssuerJwtClaimsValidator} does not compare issuers for equality: it strips the scheme and one
     * trailing slash from both sides and evaluates {@code expected.endsWith(actual)}, so a token's
     * {@code iss} only has to be a suffix of the configured issuer. {@link #foreignIssuerToken()}
     * uses a wholly unrelated issuer and so cannot detect this — a suffix match rejects that one
     * too.
     *
     * <p>Nothing here is exploitable today: every one of these tokens still carries a genuine
     * signature from the trusted provider's JWKS, so an attacker able to mint one already holds the
     * provider's signing key. What the looseness would permit is a scheme downgrade and confusion
     * between issuers sharing a URL suffix.
     *
     * <p><strong>When the per-provider byte-for-byte validator lands in step 5, this test must go
     * red.</strong> The fix at that point is not to relax it but to delete it and move these cases
     * into {@link #forgedTokens()}, where they belong.
     *
     * @param description the case name, for the test report
     * @param issuer supplies the {@code iss} value to mint the token with, deferred because the
     *     reachable issuer's port is only known once the WireMock provider has started
     */
    @ParameterizedTest(name = "{0} is (for now) wrongly accepted as the trusted issuer")
    @MethodSource("looselyMatchedIssuers")
    @DisplayName("documents that the interim issuer check is a suffix match, not an equality test")
    void documentsInterimIssuerSuffixMatch(String description, Supplier<String> issuer) {
        assertThat(
                        statusFor(
                                reachableClient,
                                rsaToken(publishedKey, claims(issuer.get(), AUDIENCE))))
                .as(
                        "the interim global issuer validator suffix-matches; step 5 must tighten "
                                + "this to a byte-for-byte comparison and move the case into "
                                + "forgedTokens()")
                .isEqualTo(HttpStatus.OK.getCode());
    }

    /**
     * A secured route that exists only while {@link #PROBE_ROUTE_ENABLED} is set, giving the test
     * something real to authenticate against.
     */
    @Requires(property = PROBE_ROUTE_ENABLED, value = "true")
    @Controller(PROBE_PATH)
    static class ProbeController {

        /**
         * Answers any authenticated caller.
         *
         * @return a fixed body
         */
        @Get
        @Secured(SecurityRule.IS_AUTHENTICATED)
        String get() {
            return "authenticated";
        }
    }
}
