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
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the token behaviour this step leaves behind: <strong>no bearer token authenticates at
 * all</strong>, least of all an unsigned one.
 *
 * <p>Step 2 delivers the trust list; nothing yet turns a trusted issuer into a signing key — that
 * is step 4 (JWKS) and step 5 (the per-issuer validator). The fail-closed direction of that gap is
 * a property worth pinning rather than assuming, because Micronaut's built-in token validator fails
 * <em>open</em> when no key is registered: {@code AbstractJsonWebTokenValidator} latches
 *
 * <pre>{@code
 * this.noSignatures = imperativeSignatureConfigurations.isEmpty()
 *         && reactiveSignatureConfigurations.isEmpty();
 * }</pre>
 *
 * <p>in its constructor and reports a {@code PlainJWT} as validly signed whenever that flag is set.
 * {@link UnsignedTokenRejector} keeps the imperative collection non-empty so the flag is never set.
 * The control case {@link #acceptsUnsignedTokenWithoutTheRejector()} disables that bean and
 * requires the same unsigned token to be <em>accepted</em>, which is what stops every other
 * assertion here from passing for the wrong reason: a token is rejected for many reasons, so a
 * suite of rejection assertions alone would stay green even if the bypass re-opened.
 *
 * <p>The configured provider is deliberately unreachable. Discovery arrives in step 3, so nothing
 * contacts it; the entry exists only to satisfy the trust-list check and to give the forged tokens
 * an issuer worth claiming.
 *
 * @see UnsignedTokenRejector
 * @see IdentityProviderRegistryValidator
 */
@DisplayName("Token signature enforcement")
class TokenSignatureEnforcementIT {

    /** Property that switches the probe route below on, so it exists only for this test. */
    private static final String PROBE_ROUTE_ENABLED = "test.token-signature-enforcement.enabled";

    /** Path of the secured probe route. */
    private static final String PROBE_PATH = "/token-signature-probe";

    /** Name of the single trust-list entry this test configures. */
    private static final String PROVIDER = "primary";

    /** Issuer the configured provider is trusted under. Nothing listens on that port. */
    private static final String TRUSTED_ISSUER = "http://localhost:1/realms/dataspace";

    /** Discovery URL of the configured provider. Resolved from step 3 on, not here. */
    private static final String DISCOVERY_URL =
            TRUSTED_ISSUER + "/.well-known/openid-configuration";

    /**
     * Per-entry key that tolerates a cleartext provider URL.
     *
     * <p>{@link #TRUSTED_ISSUER} is deliberately an unreachable {@code http} address - this class
     * mints its own tokens and never performs discovery - and the trust-list validator refuses
     * cleartext unless the entry says so in writing.
     */
    private static final String ALLOW_INSECURE_TRANSPORT_KEY = ".allow-insecure-transport";

    /**
     * Issuer no provider is configured under.
     *
     * <p>Its host is asserted absent from the rejection response, so it must be a string that
     * cannot occur in a 401 body for any other reason.
     */
    private static final String UNTRUSTED_ISSUER = "https://untrusted.invalid/realms/dataspace";

    /** Host of {@link #UNTRUSTED_ISSUER}, the substring a leaking response would echo. */
    private static final String UNTRUSTED_ISSUER_HOST = "untrusted.invalid";

    /** Audience the configured provider requires. */
    private static final String AUDIENCE = "consent-manager";

    /** Claim path the configured provider carries its participant identifier in. */
    private static final String PARTICIPANT_IDENTIFIER_CLAIM = "participant_id";

    /** Claim path the configured provider carries its roles in. */
    private static final String ROLES_CLAIM = "realm_access.roles";

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

    private static RSAKey signingKey;

    private static ApplicationContext context;
    private static EmbeddedServer server;
    private static HttpClient client;

    private static ApplicationContext shippedContext;
    private static EmbeddedServer shippedServer;
    private static HttpClient shippedClient;

    /**
     * Generates the key material and starts the application context and its server.
     *
     * @throws JOSEException if the RSA key pair cannot be generated
     */
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

    /**
     * Closes a fixture, ignoring any failure so one bad teardown cannot mask the rest.
     *
     * @param closeable the fixture to close; may be {@code null} if its setup never ran
     */
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
     * Starts an application context holding a single, valid, unreachable trust-list entry.
     *
     * <p>Default property sources are disabled so the result does not depend on {@code
     * application.yml} or {@code application-test.yml}; everything the context needs is stated
     * here. The {@code test} environment is activated explicitly because {@link
     * IdentityProviderRegistryValidator#validate()} only tolerates a disabled {@link
     * UnsignedTokenRejector} there, which the control case relies on.
     *
     * @param overrides properties layered on top, for control cases that switch a bean off
     * @return the started context
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
        properties.put(settings + ".claims.participant-identifier", PARTICIPANT_IDENTIFIER_CLAIM);
        properties.put(settings + ".claims.roles", ROLES_CLAIM);
        properties.put(settings + ".role-mapping.user", USER_ROLE_VALUE);
        properties.put(settings + ALLOW_INSECURE_TRANSPORT_KEY, true);
        properties.putAll(overrides);

        return ApplicationContext.builder(Environment.TEST)
                .enableDefaultPropertySources(false)
                .properties(properties)
                .build()
                .start();
    }

    /**
     * Starts a context under the <strong>shipped</strong> configuration, with {@code
     * application.yml} and {@code application-test.yml} loaded as they are in a real run.
     *
     * <p>{@link #startContext(Map)} switches default property sources off on purpose, so a context
     * it builds cannot say anything about what a deployment exposes. The route probes below are
     * exactly a statement about a deployment: {@code micronaut-security-oauth2} is a compile
     * dependency for its discovery components alone (ADR 0003), and its RFC 9728
     * protected-resource-metadata controller is <em>enabled by default</em> — only the {@code
     * micronaut.security.oauth2.protected-resource-metadata} block in {@code application.yml} keeps
     * it off. Probing a context that never read that block would assert the opposite of the
     * intended property and pass the moment the block is deleted.
     *
     * <p>Only the infrastructure a route probe has no use for is switched off, plus {@code
     * micronaut.security.enabled}, which {@code application-test.yml} turns off for the rest of the
     * suite and which the shipped {@code application.yml} turns on.
     *
     * @return the started context
     */
    private static ApplicationContext startShippedContext() {
        return ApplicationContext.builder(Environment.TEST)
                .properties(
                        Map.of(
                                "micronaut.security.enabled", true,
                                "micronaut.server.port", -1,
                                "datasources.default.enabled", false,
                                "flyway.enabled", false,
                                "endpoints.all.enabled", false))
                .build()
                .start();
    }

    /**
     * Builds the claim set a well-formed token for the trusted provider would carry.
     *
     * @return claims naming the trusted issuer and audience, valid now
     */
    private static JWTClaimsSet claims() {
        return claims(TRUSTED_ISSUER, AUDIENCE, Instant.now().plus(TOKEN_LIFETIME), null);
    }

    /**
     * Builds a claim set, leaving every field not named by a parameter valid.
     *
     * @param issuer value of the {@code iss} claim
     * @param audience value of the {@code aud} claim
     * @param expiresAt value of the {@code exp} claim
     * @param notBefore value of the {@code nbf} claim, or {@code null} to omit it
     * @return the assembled claim set
     */
    private static JWTClaimsSet claims(
            String issuer, String audience, Instant expiresAt, Instant notBefore) {
        JWTClaimsSet.Builder builder =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(audience)
                        .subject(SUBJECT)
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(expiresAt));
        if (notBefore != null) {
            builder.notBeforeTime(Date.from(notBefore));
        }
        return builder.build();
    }

    /**
     * Mints an unsigned {@code alg: none} token.
     *
     * @return the serialised token
     */
    private static String unsignedToken() {
        return new PlainJWT(claims()).serialize();
    }

    /**
     * Mints a token signed with the generated RSA key.
     *
     * @param claims the claims to sign
     * @return the serialised token
     */
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

    /**
     * Mints a token signed with an HMAC secret rather than an asymmetric key.
     *
     * @return the serialised token
     */
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

    /**
     * Calls the secured probe route and reports the status.
     *
     * @param token the bearer token to present, or {@code null} to call anonymously
     * @return the HTTP status code
     */
    private static int statusFor(String token) {
        return statusFor(client, token);
    }

    /**
     * Calls the secured probe route through the given client and reports the status.
     *
     * @param httpClient the client to call through
     * @param token the bearer token to present, or {@code null} to call anonymously
     * @return the HTTP status code
     */
    private static int statusFor(HttpClient httpClient, String token) {
        return responseFor(httpClient, token).getStatus().getCode();
    }

    /**
     * Calls the secured probe route and returns the response, successful or not.
     *
     * @param httpClient the client to call through
     * @param token the bearer token to present, or {@code null} to call anonymously
     * @return the response the server produced
     */
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
     * Supplies the forged and well-formed tokens, every one of which must be refused.
     *
     * <p>The first three are the cryptographic forgeries; the remainder are tokens a conforming
     * provider could have issued, and are refused because no signing key is registered at all. The
     * time-based rows additionally guard the {@code exp} and {@code nbf} checks against a
     * configuration or framework change that quietly switches them off once step 5 registers keys.
     *
     * @return {@code (description, token supplier)} rows
     */
    static Stream<Arguments> refusedTokens() {
        return Stream.of(
                Arguments.of(
                        "an unsigned alg:none token",
                        (Supplier<String>) TokenSignatureEnforcementIT::unsignedToken),
                Arguments.of(
                        "a symmetrically signed HS256 token",
                        (Supplier<String>) TokenSignatureEnforcementIT::symmetricToken),
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
                        "%s must not authenticate: no signing key is registered until step 5, so"
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
    @DisplayName("control: the unsigned token authenticates once the rejector is switched off")
    void acceptsUnsignedTokenWithoutTheRejector() {
        try (ApplicationContext bypassed =
                        startContext(Map.of(UnsignedTokenRejector.ENABLED_PROPERTY, false));
                EmbeddedServer bypassedServer = bypassed.getBean(EmbeddedServer.class).start();
                HttpClient bypassedClient = HttpClient.create(bypassedServer.getURL())) {

            assertThat(statusFor(bypassedClient, unsignedToken()))
                    .as(
                            "without UnsignedTokenRejector the empty signature-configuration"
                                    + " collection makes Micronaut treat a PlainJWT as validly signed."
                                    + " If this ever returns 401, the bypass is closed by something"
                                    + " else and the rejector's own assertions have stopped proving"
                                    + " anything")
                    .isEqualTo(HttpStatus.OK.getCode());
        }
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
