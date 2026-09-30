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
import io.micronaut.http.HttpRequest;
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
 * SignatureConfiguration} whose keys it caches and re-fetches. This test stands a WireMock OpenID
 * provider in front of that machinery and drives real HTTP requests through a secured route.
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

        reachableContext = startContext(reachableIssuer);
        reachableServer = reachableContext.getBean(EmbeddedServer.class).start();
        reachableClient = HttpClient.create(reachableServer.getURL());

        unreachableContext = startContext(UNREACHABLE_ISSUER);
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
     * Starts an isolated context whose only trusted provider is the given issuer.
     *
     * @param issuer the issuer to trust
     * @return the started context
     */
    private static ApplicationContext startContext(String issuer) {
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
                issuer);
        properties.put(settings + ".audience", AUDIENCE);
        properties.put(settings + ".claims.participant-identifier", "participant_id");
        properties.put(settings + ".claims.roles", "realm_access.roles");
        properties.put(settings + ".role-mapping.user", "consent-user");

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
        return new JWTClaimsSet.Builder()
                .issuer(reachableIssuer)
                .audience(AUDIENCE)
                .subject("a-subject")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plus(TOKEN_LIFETIME)))
                .build();
    }

    /**
     * Mints an unsigned {@code alg: none} token.
     *
     * @return the serialized token
     */
    private static String unsignedToken() {
        return new PlainJWT(claims()).serialize();
    }

    /**
     * Mints an RS256 token signed with the given key.
     *
     * @param key the signing key
     * @return the serialized token
     */
    private static String rsaToken(RSAKey key) {
        try {
            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                            claims());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to mint an RS256 token", e);
        }
    }

    /**
     * Mints a token signed with a key the provider never published.
     *
     * @return the serialized token
     */
    private static String foreignlySignedToken() {
        return rsaToken(foreignKey);
    }

    /**
     * Mints an HS256 token bearing the published key identifier, the classic key-confusion shape.
     *
     * @return the serialized token
     */
    private static String symmetricToken() {
        try {
            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(SIGNING_KEY_ID).build(),
                            claims());
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
        HttpRequest<?> request =
                token == null
                        ? HttpRequest.GET(PROBE_PATH)
                        : HttpRequest.GET(PROBE_PATH).bearerAuth(token);
        try {
            return client.toBlocking().exchange(request).getStatus().getCode();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    /**
     * Supplies the token shapes that must never authenticate.
     *
     * @return case name and a supplier that mints the token when the case runs
     */
    static Stream<Arguments> forgedTokens() {
        return Stream.of(
                Arguments.of("no Authorization header", (Supplier<String>) () -> null),
                Arguments.of(
                        "an unsigned alg:none token",
                        (Supplier<String>) TokenSignatureEnforcementIT::unsignedToken),
                Arguments.of(
                        "an RS256 token signed with a key the provider does not publish",
                        (Supplier<String>) TokenSignatureEnforcementIT::foreignlySignedToken),
                Arguments.of(
                        "an HS256 token claiming the published key id",
                        (Supplier<String>) TokenSignatureEnforcementIT::symmetricToken),
                Arguments.of(
                        "a syntactically invalid token", (Supplier<String>) () -> "not-a-jwt"));
    }

    @ParameterizedTest(name = "{0} is refused")
    @MethodSource("forgedTokens")
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
    @MethodSource("forgedTokens")
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
        assertThat(statusFor(unreachableClient, rsaToken(publishedKey)))
                .as("with no usable signing key the service must fail closed")
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
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
