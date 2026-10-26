package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.seamware.consentmanager.support.TestPseudonymizer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.validator.TokenValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests that no bearer token authenticates while the only trusted issuer's keys are unavailable,
 * least of all an unsigned one, despite Micronaut's fail-open {@code noSignatures} latch; {@link
 * #acceptsUnsignedTokenWithoutTheRejector()} is the control proving the rejections meaningful.
 */
@DisplayName("Token signature enforcement")
class TokenSignatureEnforcementTest {

    /**
     * No property file is read, so the receipt signer and the log pseudonymizer, which both fail
     * startup keyless, need these.
     */
    private static final Map<String, Object> STARTUP_KEYS =
            Map.of(
                    "consent-manager.receipts.ephemeral-signing-key",
                    true,
                    "consent-manager.logging.pseudonym-key",
                    TestPseudonymizer.KEY);

    /** Property that switches the probe route below on, so it exists only for this test. */
    private static final String PROBE_ROUTE_ENABLED = "test.token-signature-enforcement.enabled";

    /** Path of the secured probe route. */
    private static final String PROBE_PATH = "/token-signature-probe";

    /** Name of the single trust-list entry this test configures. */
    private static final String PROVIDER = "primary";

    /** Issuer the configured provider is trusted under. Nothing listens on that port. */
    private static final String TRUSTED_ISSUER = "http://localhost:1/realms/dataspace";

    /** Discovery URL of the configured provider; never resolved by this test. */
    private static final String DISCOVERY_URL =
            TRUSTED_ISSUER + "/.well-known/openid-configuration";

    /** Per-entry key allowing the deliberately unreachable cleartext {@link #TRUSTED_ISSUER}. */
    private static final String ALLOW_INSECURE_TRANSPORT_KEY = ".allow-insecure-transport";

    /** Issuer no provider is configured under; its host must never occur in a 401 body. */
    private static final String UNTRUSTED_ISSUER = "https://untrusted.invalid/realms/dataspace";

    /** Host of {@link #UNTRUSTED_ISSUER}, the substring a leaking response would echo. */
    private static final String UNTRUSTED_ISSUER_HOST = "untrusted.invalid";

    /** Audience the configured provider requires. */
    private static final String AUDIENCE = "consent-manager";

    /** Claim path the configured provider carries its participant identifier in. */
    private static final String PARTICIPANT_IDENTIFIER_CLAIM = "participant_id";

    /** Claim path the configured provider carries its roles in. */
    private static final List<String> ROLES_CLAIM = List.of("realm_access", "roles");

    /** Provider role string the {@code USER} role is mapped from. */
    private static final String USER_ROLE_VALUE = "consent-user";

    /** {@code kid} the minted RSA tokens carry. */
    private static final String SIGNING_KEY_ID = "signing-key-1";

    /** Modulus size of the generated RSA keys. */
    private static final int RSA_KEY_SIZE = 2048;

    /** How far in the future a minted token expires, unless a case overrides it. */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(10);

    /** How far in the past an intentionally expired token expired. */
    private static final Duration TOKEN_EXPIRY_AGE = Duration.ofMinutes(10);

    /** How far in the future an intentionally not-yet-valid token becomes valid. */
    private static final Duration TOKEN_NOT_BEFORE_DELAY = Duration.ofMinutes(10);

    /** HMAC secret for the symmetric forgery; 256 bits, the minimum {@code HS256} accepts. */
    private static final String SYMMETRIC_SECRET = "symmetric-secret-of-at-least-32-chars";

    /** Subject every minted token carries. */
    private static final String SUBJECT = "subject-under-test";

    /**
     * RFC 9728 challenge parameter that must never appear: it names a document this service does
     * not serve and leaks its base URL to an unauthenticated caller.
     */
    private static final String RESOURCE_METADATA_CHALLENGE_PARAMETER = "resource_metadata";

    private static RSAKey signingKey;

    private static ApplicationContext context;
    private static EmbeddedServer server;
    private static HttpClient client;

    private static ApplicationContext shippedContext;
    private static EmbeddedServer shippedServer;
    private static HttpClient shippedClient;

    /** Generates the key material and starts the application context and its server. */
    @BeforeAll
    static void startFixtures() throws JOSEException {
        signingKey =
                new RSAKeyGenerator(RSA_KEY_SIZE)
                        .keyID(SIGNING_KEY_ID)
                        .algorithm(JWSAlgorithm.RS256)
                        .generate();

        context = startContext(Map.of());
        server = context.getBean(EmbeddedServer.class).start();
        client = HttpClient.create(server.getURL());

        shippedContext = startShippedContext();
        shippedServer = shippedContext.getBean(EmbeddedServer.class).start();
        shippedClient = HttpClient.create(shippedServer.getURL());
    }

    /** Shuts the client, server and context down. */
    @AfterAll
    static void stopFixtures() {
        closeQuietly(shippedClient);
        closeQuietly(shippedServer);
        closeQuietly(shippedContext);
        closeQuietly(client);
        closeQuietly(server);
        closeQuietly(context);
    }

    /** Closes a possibly {@code null} fixture, ignoring failures so one cannot mask the rest. */
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Teardown failures are not test results.
        }
    }

    /**
     * Starts a context with one valid, unreachable trust-list entry and no default property
     * sources; the {@code test} environment lets the control case disable {@link
     * UnsignedTokenRejector}.
     */
    private static ApplicationContext startContext(Map<String, Object> overrides) {
        String settings = IdentityProviderConfiguration.PREFIX + "." + PROVIDER;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PROBE_ROUTE_ENABLED, true);
        properties.put("micronaut.security.enabled", true);
        properties.put("micronaut.server.port", -1);
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.put("endpoints.all.enabled", false);
        properties.put(settings + ".issuer", TRUSTED_ISSUER);
        properties.put(settings + ".discovery-url", DISCOVERY_URL);
        properties.put(settings + ".audience", AUDIENCE);
        properties.put(
                settings + ".claims.participant-identifier", List.of(PARTICIPANT_IDENTIFIER_CLAIM));
        properties.put(settings + ".claims.roles", ROLES_CLAIM);
        properties.put(settings + ".role-mapping.user", USER_ROLE_VALUE);
        properties.put(settings + ALLOW_INSECURE_TRANSPORT_KEY, true);
        properties.putAll(overrides);

        return ApplicationContext.builder(Environment.TEST)
                .enableDefaultPropertySources(false)
                .properties(STARTUP_KEYS)
                .properties(properties)
                .build()
                .start();
    }

    /**
     * Starts a context under the shipped configuration, default property sources loaded. Route
     * probes need it: only {@code application.yml} keeps RFC 9728 resource metadata switched off.
     */
    private static ApplicationContext startShippedContext() {
        return ApplicationContext.builder(Environment.TEST)
                .properties(
                        Map.of(
                                PROBE_ROUTE_ENABLED,
                                true,
                                "micronaut.security.enabled",
                                true,
                                "micronaut.server.port",
                                -1,
                                "datasources.default.enabled",
                                false,
                                "flyway.enabled",
                                false,
                                "endpoints.all.enabled",
                                false))
                .build()
                .start();
    }

    /** Builds the claims of a well-formed token for the trusted issuer and audience, valid now. */
    private static JWTClaimsSet claims() {
        return claims(TRUSTED_ISSUER, AUDIENCE, Instant.now().plus(TOKEN_LIFETIME), null);
    }

    /**
     * Builds a complete claim set granting {@link Role#USER}, so a refusal is attributable to the
     * one field a case varies; a {@code null} {@code notBefore} omits {@code nbf}.
     */
    private static JWTClaimsSet claims(
            String issuer, String audience, Instant expiresAt, Instant notBefore) {
        JWTClaimsSet.Builder builder =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(audience)
                        .subject(SUBJECT)
                        .claim(
                                ROLES_CLAIM.getFirst(),
                                Map.of(ROLES_CLAIM.get(1), List.of(USER_ROLE_VALUE)))
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(expiresAt));
        if (notBefore != null) {
            builder.notBeforeTime(Date.from(notBefore));
        }
        return builder.build();
    }

    /** Mints an unsigned {@code alg: none} token. */
    private static String unsignedToken() {
        return new PlainJWT(claims()).serialize();
    }

    /** Mints a token signed with the generated RSA key. */
    private static String rsaToken(JWTClaimsSet claims) {
        try {
            SignedJWT token =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .keyID(signingKey.getKeyID())
                                    .build(),
                            claims);
            token.sign(new RSASSASigner(signingKey));
            return token.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to sign the test token", e);
        }
    }

    /** Mints a token signed with an HMAC secret rather than an asymmetric key. */
    private static String symmetricToken() {
        try {
            SignedJWT token =
                    new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims());
            token.sign(new MACSigner(SYMMETRIC_SECRET));
            return token.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to sign the test token", e);
        }
    }

    /** Calls the probe route, anonymously for a {@code null} token, and returns the status. */
    private static int statusFor(String token) {
        return statusFor(client, token);
    }

    /** As {@link #statusFor(String)}, through the given client. */
    private static int statusFor(HttpClient httpClient, String token) {
        return responseFor(httpClient, token).getStatus().getCode();
    }

    /** Calls the probe route through the given client, returning the response even on refusal. */
    private static HttpResponse<?> responseFor(HttpClient httpClient, String token) {
        MutableHttpRequest<Object> request = HttpRequest.GET(PROBE_PATH);
        if (token != null) {
            request = request.bearerAuth(token);
        }
        try {
            return httpClient.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            return e.getResponse();
        }
    }

    /**
     * {@code (description, token)} rows that must all be refused: three cryptographic forgeries,
     * then well-formed tokens refused because no signing key is registered at all. The time-based
     * rows also guard the {@code exp} and {@code nbf} checks.
     */
    static Stream<Arguments> refusedTokens() {
        return Stream.of(
                Arguments.of(
                        "an unsigned alg:none token",
                        (Supplier<String>) TokenSignatureEnforcementTest::unsignedToken),
                Arguments.of(
                        "a symmetrically signed HS256 token",
                        (Supplier<String>) TokenSignatureEnforcementTest::symmetricToken),
                Arguments.of(
                        "a token naming an unregistered issuer",
                        (Supplier<String>)
                                () ->
                                        rsaToken(
                                                claims(
                                                        UNTRUSTED_ISSUER,
                                                        AUDIENCE,
                                                        Instant.now().plus(TOKEN_LIFETIME),
                                                        null))),
                Arguments.of(
                        "a token naming the wrong audience",
                        (Supplier<String>)
                                () ->
                                        rsaToken(
                                                claims(
                                                        TRUSTED_ISSUER,
                                                        "another-service",
                                                        Instant.now().plus(TOKEN_LIFETIME),
                                                        null))),
                Arguments.of(
                        "an expired token",
                        (Supplier<String>)
                                () ->
                                        rsaToken(
                                                claims(
                                                        TRUSTED_ISSUER,
                                                        AUDIENCE,
                                                        Instant.now().minus(TOKEN_EXPIRY_AGE),
                                                        null))),
                Arguments.of(
                        "a token whose nbf is in the future",
                        (Supplier<String>)
                                () ->
                                        rsaToken(
                                                claims(
                                                        TRUSTED_ISSUER,
                                                        AUDIENCE,
                                                        Instant.now().plus(TOKEN_LIFETIME),
                                                        Instant.now()
                                                                .plus(TOKEN_NOT_BEFORE_DELAY)))),
                Arguments.of(
                        "a well-formed token signed by an unregistered key",
                        (Supplier<String>) () -> rsaToken(claims())));
    }

    @ParameterizedTest(name = "{0} is refused")
    @MethodSource("refusedTokens")
    @DisplayName("refuses every bearer token while no provider has contributed a signing key")
    void refusesEveryToken(String description, Supplier<String> token) {
        assertThat(statusFor(token.get()))
                .as(
                        "%s must not authenticate: no signing key is registered in this context, so"
                                + " the only safe answer is 401",
                        description)
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("refuses a request that carries no token")
    void refusesAnonymousRequest() {
        assertThat(statusFor(null))
                .as("the probe route is @Secured(IS_AUTHENTICATED)")
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("does not echo the submitted issuer back to the caller")
    void doesNotEchoTheSubmittedIssuer() {
        HttpResponse<?> response =
                responseFor(
                        client,
                        rsaToken(
                                claims(
                                        UNTRUSTED_ISSUER,
                                        AUDIENCE,
                                        Instant.now().plus(TOKEN_LIFETIME),
                                        null)));

        assertThat(response.getBody(String.class).orElse(""))
                .as(
                        "US-ID-008: the rejection must not tell the caller which issuers are"
                                + " registered, not even by repeating the one it submitted")
                .doesNotContain(UNTRUSTED_ISSUER_HOST);
        for (String name : response.getHeaders().names()) {
            assertThat(response.getHeaders().getAll(name))
                    .as("header %s must not echo the submitted issuer", name)
                    .allSatisfy(value -> assertThat(value).doesNotContain(UNTRUSTED_ISSUER_HOST));
        }
    }

    @Test
    @DisplayName("the service's own validator is the only TokenValidator a deployment registers")
    void serviceOwnValidatorIsTheOnlyTokenValidator() {
        assertThat(shippedContext.getBeansOfType(TokenValidator.class))
                .as(
                        "TokenAuthenticationFetcher takes the first Authentication ANY registered"
                                + " TokenValidator returns, so a second one beside"
                                + " ConsentManagerTokenValidator would be a parallel authentication"
                                + " path validating against the declarative signature configuration"
                                + " this service deliberately does not have. The"
                                + " micronaut.security.token.jwt.nimbus.* switches in application.yml"
                                + " are what keep this list a singleton")
                .singleElement()
                .isInstanceOf(ConsentManagerTokenValidator.class);
    }

    @Test
    @DisplayName("control: the unsigned token authenticates once the rejector is switched off")
    void acceptsUnsignedTokenWithoutTheRejector() {
        try (ApplicationContext bypassed =
                        startContext(Map.of(UnsignedTokenRejector.ENABLED_PROPERTY, false));
                EmbeddedServer bypassedServer = bypassed.getBean(EmbeddedServer.class).start();
                HttpClient bypassedClient = HttpClient.create(bypassedServer.getURL())) {

            assertThat(statusFor(bypassedClient, unsignedToken()))
                    .as(
                            "without UnsignedTokenRejector the empty signature-configuration"
                                    + " collection makes Micronaut treat a PlainJWT as validly"
                                    + " signed, so the request authenticates and is served - which"
                                    + " is the bypass this bean exists to close. The probe route"
                                    + " declares no principal parameter, so PrincipalResolutionFilter"
                                    + " never runs and cannot mask the bypass behind a 403. If this"
                                    + " ever returns 401 the bypass is closed by something else and"
                                    + " the rejector's own assertions have stopped proving anything")
                    .isEqualTo(HttpStatus.OK.getCode());
        }
    }

    @Test
    @DisplayName("challenges without advertising a protected-resource metadata document")
    void challengeCarriesNoResourceMetadataParameter() {
        HttpResponse<?> response = responseFor(shippedClient, null);

        assertThat(response.getStatus().getCode())
                .as("the probe route is @Secured(IS_AUTHENTICATED) and the call carries no token")
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
        assertThat(response.getHeaders().getAll(HttpHeaders.WWW_AUTHENTICATE))
                .as(
                        "micronaut-security-oauth2's RFC 9728 challenge provider is gated on"
                                + " micronaut.security.oauth2.protected-resource-metadata"
                                + ".www-authenticate ALONE - not on .enabled - so losing that one"
                                + " line (a duplicate `oauth2` key in application.yml is enough,"
                                + " YAML keeps the last) puts a %s parameter naming this service's"
                                + " base URL into every 401, pointing at a route that is"
                                + " deliberately not served",
                        RESOURCE_METADATA_CHALLENGE_PARAMETER)
                .allSatisfy(
                        value ->
                                assertThat(value)
                                        .doesNotContain(RESOURCE_METADATA_CHALLENGE_PARAMETER));
    }

    @ParameterizedTest(name = "{0} is not served")
    @ValueSource(
            strings = {
                "/.well-known/oauth-protected-resource",
                "/.well-known/openid-configuration",
                "/oauth/login/" + PROVIDER,
                "/oauth/callback/" + PROVIDER,
                "/oauth/logout"
            })
    @DisplayName("exposes no OAuth2 client or metadata routes")
    void exposesNoOAuth2Routes(String path) {
        int status;
        try {
            status =
                    shippedClient
                            .toBlocking()
                            .exchange(HttpRequest.GET(path), String.class)
                            .getStatus()
                            .getCode();
        } catch (HttpClientResponseException e) {
            status = e.getStatus().getCode();
        }

        assertThat(status)
                .as(
                        "%s is a micronaut-security-oauth2 login or metadata route. That module is"
                                + " on the classpath for its discovery components only (ADR 0003);"
                                + " this service runs no OAuth2 client and issues no tokens, so it"
                                + " must serve none of them. The login, callback and logout routes"
                                + " stay unregistered on their own because no"
                                + " micronaut.security.oauth2.clients.* property is set, but the"
                                + " RFC 9728 /.well-known/oauth-protected-resource controller"
                                + " defaults to ENABLED and is held off only by the"
                                + " micronaut.security.oauth2.protected-resource-metadata block in"
                                + " application.yml. Serving it would advertise this service's base"
                                + " URL anonymously and, once authorization servers are listed,"
                                + " the very issuers the trust list does not disclose (US-ID-008)",
                        path)
                .isNotEqualTo(HttpStatus.OK.getCode());
    }

    /**
     * A secured route that exists only while {@link #PROBE_ROUTE_ENABLED} is set, giving the test
     * something real to authenticate against.
     */
    @Requires(property = PROBE_ROUTE_ENABLED, value = "true")
    @Controller(PROBE_PATH)
    static class ProbeController {

        /** Answers any authenticated caller with a fixed body. */
        @Get
        @Secured(SecurityRule.IS_AUTHENTICATED)
        String get() {
            return "authenticated";
        }
    }
}
