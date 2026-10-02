package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.security.authentication.Authentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

/**
 * Pins the token validation pipeline: which tokens become an {@link Authentication} and which
 * become nothing at all.
 *
 * <p>Emitting nothing is the whole failure vocabulary of a {@link
 * io.micronaut.security.token.validator.TokenValidator}; the security filter turns it into {@code
 * 401}. So every rejection case below asserts the same thing - an empty result - and the value of
 * the suite is in the <em>breadth</em> of the matrix rather than in any single assertion. A check
 * that silently stopped running would show up here as a token that authenticates, not as a
 * different error.
 *
 * <p>The providers are WireMock stubs rather than containers: the pipeline needs an issuer whose
 * discovery resolves and whose key set is published, and nothing about those two documents needs a
 * real Keycloak. The end-to-end run against a real one is Step 8's.
 *
 * <p><strong>Two stubs, deliberately.</strong> Cross-issuer key confusion - a {@code kid} that is
 * genuinely valid at provider A, presented in a token claiming provider B's issuer - cannot be
 * expressed with a single trusted provider, and is the one rejection an implementation that
 * verified against "any trusted key" would still get wrong.
 *
 * <p>What is <em>not</em> asserted here is the shape of the HTTP response, including the rule that
 * a rejection must not echo the submitted issuer (US-ID-008): a validator produces no response and
 * no message, so that assertion belongs to {@link TokenSignatureEnforcementTest}, which makes it
 * against a real 401 body and its headers.
 */
@DisplayName("Bearer token validation")
class ConsentManagerTokenValidatorTest {

    /** Longest a test waits for the stub providers' discovery to resolve. */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(30);

    /** Prefix of a trust-list entry's configuration properties. */
    private static final String PROVIDER_PREFIX = IdentityProviderConfiguration.PREFIX + ".";

    /** Entry name of the provider most tokens are minted for. */
    private static final String PRIMARY = "primary";

    /** Entry name of the second trusted provider, used for key-confusion cases. */
    private static final String SECONDARY = "secondary";

    /** Entry name of the provider that is configured but whose discovery can never succeed. */
    private static final String UNRESOLVABLE = "unresolvable";

    /** Issuer of {@link #UNRESOLVABLE}. Nothing listens on that port, by construction. */
    private static final String UNRESOLVABLE_ISSUER = "http://localhost:1/realms/down";

    /** Discovery URL of {@link #UNRESOLVABLE}. */
    private static final String UNRESOLVABLE_DISCOVERY_URL =
            UNRESOLVABLE_ISSUER + "/.well-known/openid-configuration";

    /** Issuer no trust-list entry names. */
    private static final String UNREGISTERED_ISSUER = "https://untrusted.invalid/realms/dataspace";

    /** Audience both trusted entries require. */
    private static final String AUDIENCE = "consent-manager";

    /** Audience belonging to a different service in the same dataspace. */
    private static final String FOREIGN_AUDIENCE = "another-service";

    /** First segment of {@link #ROLES_CLAIM}. */
    private static final String REALM_ACCESS_CLAIM = "realm_access";

    /** Second segment of {@link #ROLES_CLAIM}. */
    private static final String ROLES_SEGMENT = "roles";

    /** Nested path the entries carry their roles under, as Keycloak publishes them. */
    private static final List<String> ROLES_CLAIM = List.of(REALM_ACCESS_CLAIM, ROLES_SEGMENT);

    /**
     * Claim the entries carry the user identifier under.
     *
     * <p>Deliberately <em>not</em> {@link
     * IdentityProviderConfiguration#DEFAULT_USER_IDENTIFIER_CLAIM}: a token always carries {@code
     * sub}, so with the default in place the "a {@code USER} token with no user identifier" case
     * could not be written at all.
     */
    private static final String USER_IDENTIFIER_CLAIM = "user_id";

    /** Claim the entries carry the participant identifier under. */
    private static final String PARTICIPANT_IDENTIFIER_CLAIM = "participant_id";

    /** Raw role string both entries map {@link Role#USER} to. */
    private static final String USER_ROLE_VALUE = "consent-user";

    /** Raw role string both entries map {@link Role#PARTICIPANT} to. */
    private static final String PARTICIPANT_ROLE_VALUE = "consent-participant";

    /** Raw role string both entries map {@link Role#CATALOG} to. */
    private static final String CATALOG_ROLE_VALUE = "consent-catalog";

    /** A role string the providers emit that this service's mapping does not cover. */
    private static final String FOREIGN_ROLE_VALUE = "offline_access";

    /** Subject every minted token carries, unless a case drops it. */
    private static final String SUBJECT = "subject-under-test";

    /** Value of the user identifier claim on tokens that carry one. */
    private static final String USER_ID = "user-42";

    /** Value of the participant identifier claim on tokens that carry one. */
    private static final String PARTICIPANT_ID = "did:web:participant.example";

    /** How far in the future a minted token expires, unless a case overrides it. */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(10);

    /** How far outside its validity window an intentionally out-of-window token sits. */
    private static final Duration OUTSIDE_WINDOW = Duration.ofMinutes(10);

    /**
     * How far outside its validity window a token sits in the clock-skew cases.
     *
     * <p>Shorter than {@link IdentityProviderConfiguration#DEFAULT_CLOCK_SKEW}, which is the
     * tolerance both entries inherit, so such a token must still be accepted.
     */
    private static final Duration INSIDE_CLOCK_SKEW = Duration.ofSeconds(10);

    /** Modulus size of the locally generated forgery keys. */
    private static final int RSA_KEY_SIZE = 2048;

    /** {@code kid} no provider in this test publishes. */
    private static final String UNPUBLISHED_KEY_ID = "never-published";

    /** Longest a key-set fetch may take while a test token is being validated. */
    private static final Duration VALIDATION_TIMEOUT = Duration.ofSeconds(30);

    /** Longest the JWK Set fetch over the stub is given before the test fails. */
    private static final Duration JWKS_FETCH_TIMEOUT = Duration.ofSeconds(10);

    /** A string that is a bearer token in position only. */
    private static final String MALFORMED_TOKEN = "not.a.jwt";

    private static OidcDiscoveryStub primary;
    private static OidcDiscoveryStub secondary;
    private static ApplicationContext context;
    private static ConsentManagerTokenValidator validator;

    /** A locally generated key no provider publishes, used for the forgery cases. */
    private static RSAKey foreignKey;

    /**
     * Starts both stub providers and a context trusting them, and waits for discovery.
     *
     * @throws JOSEException if the forgery key pair cannot be generated
     */
    @BeforeAll
    static void startFixtures() throws JOSEException {
        foreignKey =
                new RSAKeyGenerator(RSA_KEY_SIZE)
                        .keyID(UNPUBLISHED_KEY_ID)
                        .algorithm(JWSAlgorithm.RS256)
                        .generate();
        primary = new OidcDiscoveryStub();
        secondary = new OidcDiscoveryStub();
        context = startContext();
        IdentityProviderRegistry registry = context.getBean(IdentityProviderRegistry.class);
        Await.until(
                "both stub providers to resolve",
                RESOLUTION_TIMEOUT,
                () ->
                        registry.findByIssuer(primary.issuer()).isPresent()
                                && registry.findByIssuer(secondary.issuer()).isPresent());
        validator = context.getBean(ConsentManagerTokenValidator.class);
    }

    /** Shuts the context and both stubs down. */
    @AfterAll
    static void stopFixtures() {
        closeQuietly(context);
        closeQuietly(primary);
        closeQuietly(secondary);
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
     * Every token that must not authenticate.
     *
     * <p>Tokens are minted lazily through a {@link Supplier} so that the time-dependent cases are
     * built relative to the moment the test runs rather than to argument-resolution time.
     *
     * @return description and token supplier per case
     */
    private static Stream<Arguments> refusedTokens() {
        return Stream.of(
                Arguments.of(
                        "an issuer on no trust list",
                        token(() -> sign(primary, claims(UNREGISTERED_ISSUER)))),
                Arguments.of(
                        "an issuer that is configured but has not resolved",
                        token(() -> sign(primary, claims(UNRESOLVABLE_ISSUER)))),
                Arguments.of("no issuer claim at all", token(() -> sign(primary, claims(null)))),
                Arguments.of(
                        "a kid the issuer publishes, signed by a key it does not",
                        token(
                                () ->
                                        signWithForeignKey(
                                                primary.initialKeyId(), claims(primary.issuer())))),
                Arguments.of(
                        "a kid no issuer publishes",
                        token(
                                () ->
                                        signWithForeignKey(
                                                UNPUBLISHED_KEY_ID, claims(primary.issuer())))),
                Arguments.of(
                        "a key genuinely valid at another trusted issuer",
                        token(
                                () ->
                                        secondary
                                                .signToken(
                                                        secondary.initialKeyId(),
                                                        claims(primary.issuer()))
                                                .serialize())),
                Arguments.of(
                        "an expired token",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                timed(
                                                        primary.issuer(),
                                                        Instant.now().minus(OUTSIDE_WINDOW),
                                                        Instant.now().minus(OUTSIDE_WINDOW),
                                                        null)))),
                Arguments.of(
                        "a token whose nbf is in the future",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                timed(
                                                        primary.issuer(),
                                                        Instant.now().plus(TOKEN_LIFETIME),
                                                        Instant.now(),
                                                        Instant.now().plus(OUTSIDE_WINDOW))))),
                Arguments.of(
                        "a token issued in the future",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                timed(
                                                        primary.issuer(),
                                                        Instant.now().plus(TOKEN_LIFETIME),
                                                        Instant.now().plus(OUTSIDE_WINDOW),
                                                        null)))),
                Arguments.of(
                        "a token that never expires",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                timed(
                                                        primary.issuer(),
                                                        null,
                                                        Instant.now(),
                                                        null)))),
                Arguments.of(
                        "an audience belonging to another service",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                builder(primary.issuer())
                                                        .audience(FOREIGN_AUDIENCE)
                                                        .build()))),
                Arguments.of(
                        "no audience at all",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                builder(primary.issuer())
                                                        .audience((String) null)
                                                        .build()))),
                Arguments.of(
                        "no subject",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                builder(primary.issuer()).subject(null).build()))),
                Arguments.of(
                        "a USER token with no user identifier claim",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                roles(builder(primary.issuer()), USER_ROLE_VALUE)
                                                        .claim(USER_IDENTIFIER_CLAIM, null)
                                                        .build()))),
                Arguments.of(
                        "a PARTICIPANT token with no participant identifier claim",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                roles(
                                                                builder(primary.issuer()),
                                                                PARTICIPANT_ROLE_VALUE)
                                                        .claim(PARTICIPANT_IDENTIFIER_CLAIM, null)
                                                        .build()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedTokens")
    @DisplayName("refuses a token that fails any step of the pipeline")
    void refusesInvalidTokens(String description, Supplier<String> token) {
        assertThat(authenticate(token.get()))
                .as("%s must authenticate nobody", description)
                .isEmpty();
    }

    /**
     * Tokens whose <em>header</em> alone disqualifies them, before any key is looked up.
     *
     * @return description and token supplier per case
     */
    private static Stream<Arguments> refusedHeaders() {
        return Stream.of(
                Arguments.of(
                        "alg: none over otherwise impeccable claims",
                        token(() -> new PlainJWT(claims(primary.issuer())).serialize())),
                Arguments.of(
                        "HS256 keyed with the issuer's own published RSA modulus",
                        token(
                                () ->
                                        symmetricallySigned(
                                                primary.initialKeyId(), claims(primary.issuer())))),
                Arguments.of("a string that is not a JWT", token(() -> MALFORMED_TOKEN)),
                Arguments.of("an empty token", token(() -> "")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedHeaders")
    @DisplayName("refuses a token the signature-algorithm allow-list excludes")
    void refusesDisallowedAlgorithms(String description, Supplier<String> token) {
        assertThat(authenticate(token.get()))
                .as(
                        "%s must be refused for what its header says, not for which key material"
                                + " happens to be configured",
                        description)
                .isEmpty();
    }

    @ParameterizedTest(name = "{0} is not accepted")
    @CsvSource({"none", "HS256", "HS384", "HS512"})
    @DisplayName("names no unsigned or symmetric algorithm in the allow-list")
    void excludesSymmetricAlgorithms(String algorithm) {
        assertThat(ConsentManagerTokenValidator.PERMITTED_SIGNATURE_ALGORITHMS)
                .as("an attacker picks the algorithm, so the allow-list is the control")
                .doesNotContain(JWSAlgorithm.parse(algorithm));
    }

    @ParameterizedTest(name = "{0} is accepted")
    @CsvSource({"RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512"})
    @DisplayName("names every asymmetric algorithm an OpenID provider may sign with")
    void includesAsymmetricAlgorithms(String algorithm) {
        assertThat(ConsentManagerTokenValidator.PERMITTED_SIGNATURE_ALGORITHMS)
                .as("refusing an algorithm a trusted provider legitimately uses locks users out")
                .contains(JWSAlgorithm.parse(algorithm));
    }

    /**
     * The role strings each mapped role is reached through.
     *
     * @return raw role string and the role it grants
     */
    private static Stream<Arguments> mappedRoles() {
        return Stream.of(
                Arguments.of(USER_ROLE_VALUE, Role.USER),
                Arguments.of(PARTICIPANT_ROLE_VALUE, Role.PARTICIPANT),
                Arguments.of(CATALOG_ROLE_VALUE, Role.CATALOG));
    }

    @ParameterizedTest(name = "{0} grants {1}")
    @MethodSource("mappedRoles")
    @DisplayName("authenticates a well-formed token and carries its mapped role as an authority")
    void authenticatesAndMapsRoles(String rawRole, Role expected) {
        String token = sign(primary, roles(builder(primary.issuer()), rawRole).build());

        Optional<Authentication> authentication = authenticate(token);

        assertThat(authentication).isPresent();
        assertThat(authentication.get().getRoles())
                .as("the nested %s claim is read through the configured path", ROLES_CLAIM)
                .containsExactly(expected.name());
        assertThat(authentication.get().getName()).isEqualTo(SUBJECT);
    }

    @Test
    @DisplayName("authenticates a token signed with the provider's elliptic-curve key")
    void authenticatesEllipticCurveSignedToken() {
        String token =
                primary.signToken(
                                primary.initialEcKeyId(),
                                roles(builder(primary.issuer()), USER_ROLE_VALUE).build())
                        .serialize();

        Optional<Authentication> authentication = authenticate(token);

        assertThat(authentication)
                .as(
                        "ES256 is in the allow-list, so a provider signing with an EC key must get"
                                + " through the key-selection layer as well as past the header check")
                .isPresent();
        assertThat(authentication.get().getRoles()).containsExactly(Role.USER.name());
    }

    /**
     * Roles claims that grant nothing, each of which must still authenticate.
     *
     * @return description and token supplier per case
     */
    private static Stream<Arguments> tokensWithoutAuthorities() {
        return Stream.of(
                Arguments.of(
                        "no roles claim at all",
                        token(() -> sign(primary, builder(primary.issuer()).build()))),
                Arguments.of(
                        "a roles claim holding only strings this provider does not map",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                roles(builder(primary.issuer()), FOREIGN_ROLE_VALUE)
                                                        .build()))),
                Arguments.of(
                        "an empty roles claim",
                        token(() -> sign(primary, roles(builder(primary.issuer())).build()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensWithoutAuthorities")
    @DisplayName("authenticates a token that maps to no role, with no authorities")
    void authenticatesWithoutAuthorities(String description, Supplier<String> token) {
        Optional<Authentication> authentication = authenticate(token.get());

        assertThat(authentication)
                .as(
                        "AC 9: %s is a valid token held by somebody without the role. That is a"
                                + " 403 at the @Secured check, never a 401 here",
                        description)
                .isPresent();
        assertThat(authentication.get().getRoles()).isEmpty();
    }

    @Test
    @DisplayName("accepts a token that is outside its window by less than the clock skew")
    void toleratesConfiguredClockSkew() {
        String justExpired =
                sign(
                        primary,
                        timed(
                                primary.issuer(),
                                Instant.now().minus(INSIDE_CLOCK_SKEW),
                                Instant.now().minus(OUTSIDE_WINDOW),
                                Instant.now().plus(INSIDE_CLOCK_SKEW)));

        assertThat(authenticate(justExpired))
                .as(
                        "the clocks that may disagree are this service's and the provider's, so the"
                                + " entry's %s tolerance applies to exp and nbf alike",
                        IdentityProviderConfiguration.DEFAULT_CLOCK_SKEW)
                .isPresent();
    }

    @Test
    @DisplayName("accepts a token that carries no iat, which RFC 7519 makes optional")
    void acceptsTokenWithoutIssuedAt() {
        String withoutIssuedAt =
                sign(
                        primary,
                        roles(
                                        new JWTClaimsSet.Builder(
                                                timed(
                                                        primary.issuer(),
                                                        Instant.now().plus(TOKEN_LIFETIME),
                                                        null,
                                                        null)),
                                        USER_ROLE_VALUE)
                                .build());

        assertThat(authenticate(withoutIssuedAt))
                .as(
                        "exp bounds the validity window on its own, so a conforming provider that"
                                + " omits the optional iat claim is not refused")
                .isPresent();
    }

    @Test
    @DisplayName("carries the raw claims and the issuing provider's name as attributes")
    void exposesClaimsAsAttributes() {
        String token = sign(primary, roles(builder(primary.issuer()), USER_ROLE_VALUE).build());

        Optional<Authentication> authentication = authenticate(token);

        assertThat(authentication).isPresent();
        assertThat(authentication.get().getAttributes())
                .containsEntry(USER_IDENTIFIER_CLAIM, USER_ID)
                .containsEntry(PARTICIPANT_IDENTIFIER_CLAIM, PARTICIPANT_ID)
                .containsEntry(
                        ConsentManagerTokenValidator.IDENTITY_PROVIDER_ISSUER_ATTRIBUTE,
                        primary.issuer());
    }

    @Test
    @DisplayName("authenticates a token from either trusted provider")
    void authenticatesTokensFromEveryTrustedProvider() {
        String fromSecondary =
                secondary
                        .signToken(
                                secondary.initialKeyId(),
                                roles(builder(secondary.issuer()), USER_ROLE_VALUE).build())
                        .serialize();

        Optional<Authentication> authentication = authenticate(fromSecondary);

        assertThat(authentication).isPresent();
        assertThat(authentication.get().getAttributes())
                .containsEntry(
                        ConsentManagerTokenValidator.IDENTITY_PROVIDER_ISSUER_ATTRIBUTE,
                        secondary.issuer());
    }

    /**
     * Runs a token through the validator under test.
     *
     * @param token the raw compact serialisation, as the {@code Authorization} header would carry
     * @return the authentication it produced, or empty if it produced none
     */
    private static Optional<Authentication> authenticate(String token) {
        return Mono.from(validator.validateToken(token, null)).blockOptional(VALIDATION_TIMEOUT);
    }

    /**
     * Wraps a token-minting lambda so a case can be declared without a cast at every use.
     *
     * @param supplier mints the token when the case runs
     * @return the same supplier, typed
     */
    private static Supplier<String> token(Supplier<String> supplier) {
        return supplier;
    }

    /**
     * Signs a claim set with a stub's current key and serialises it.
     *
     * @param provider the stub whose key signs
     * @param claims the claims to sign
     * @return the compact serialisation
     */
    private static String sign(OidcDiscoveryStub provider, JWTClaimsSet claims) {
        return provider.signToken(provider.initialKeyId(), claims).serialize();
    }

    /**
     * Signs a claim set with a locally generated key no provider publishes.
     *
     * @param keyId the {@code kid} to advertise, which may well be one the provider does publish
     * @param claims the claims to sign
     * @return the compact serialisation
     */
    private static String signWithForeignKey(String keyId, JWTClaimsSet claims) {
        SignedJWT token =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
        try {
            token.sign(new RSASSASigner(foreignKey));
        } catch (JOSEException failure) {
            throw new IllegalStateException("Could not sign a forged test token", failure);
        }
        return token.serialize();
    }

    /**
     * Signs a claim set with {@code HS256}, keyed with the issuer's published RSA modulus.
     *
     * <p>This is the classic algorithm-confusion forgery: the modulus is public, so anybody can
     * compute this signature. It verifies if - and only if - a service lets the token's header
     * choose the algorithm and then hands the HMAC verifier whatever key material the {@code kid}
     * resolves to.
     *
     * @param keyId the {@code kid} to advertise
     * @param claims the claims to sign
     * @return the compact serialisation
     */
    private static String symmetricallySigned(String keyId, JWTClaimsSet claims) {
        SignedJWT token =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keyId).build(), claims);
        try {
            token.sign(new MACSigner(publishedModulus(primary)));
        } catch (JOSEException failure) {
            throw new IllegalStateException("Could not sign a symmetric test token", failure);
        }
        return token.serialize();
    }

    /**
     * Reads a stub's published RSA modulus straight off its JWK Set endpoint.
     *
     * @param provider the stub to read from
     * @return the modulus bytes of its first published key
     */
    private static byte[] publishedModulus(OidcDiscoveryStub provider) {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response =
                    http.send(
                            HttpRequest.newBuilder(URI.create(provider.jwksUri()))
                                    .timeout(JWKS_FETCH_TIMEOUT)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            return JWKSet.parse(response.body()).getKeys().stream()
                    .filter(RSAKey.class::isInstance)
                    .map(RSAKey.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("The stub publishes no RSA key"))
                    .getModulus()
                    .decode();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading a stub's key set");
        } catch (Exception failure) {
            throw new IllegalStateException("Could not read a stub's key set", failure);
        }
    }

    /**
     * Builds the claim set a well-formed token for the given issuer carries.
     *
     * @param issuer the issuer to claim; {@code null} omits the claim entirely
     * @return the claims
     */
    private static JWTClaimsSet claims(String issuer) {
        return builder(issuer).build();
    }

    /**
     * Builds a claim set whose time claims are stated explicitly.
     *
     * @param issuer the issuer to claim
     * @param expiry the {@code exp} claim; {@code null} omits it
     * @param issuedAt the {@code iat} claim; {@code null} omits it
     * @param notBefore the {@code nbf} claim; {@code null} omits it
     * @return the claims
     */
    private static JWTClaimsSet timed(
            String issuer, Instant expiry, Instant issuedAt, Instant notBefore) {
        return builder(issuer)
                .expirationTime(toDate(expiry))
                .issueTime(toDate(issuedAt))
                .notBeforeTime(toDate(notBefore))
                .build();
    }

    /**
     * Starts a claim set that passes every check, so a case need only state what it breaks.
     *
     * @param issuer the issuer to claim; {@code null} omits the claim entirely
     * @return a builder carrying a valid subject, audience, window and identifier claims
     */
    private static JWTClaimsSet.Builder builder(String issuer) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(SUBJECT)
                .audience(AUDIENCE)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plus(TOKEN_LIFETIME)))
                .claim(USER_IDENTIFIER_CLAIM, USER_ID)
                .claim(PARTICIPANT_IDENTIFIER_CLAIM, PARTICIPANT_ID);
    }

    /**
     * Adds a Keycloak-shaped nested roles claim.
     *
     * @param builder the claim set under construction
     * @param rawRoles the raw role strings the provider emits
     * @return the same builder
     */
    private static JWTClaimsSet.Builder roles(JWTClaimsSet.Builder builder, String... rawRoles) {
        return builder.claim(REALM_ACCESS_CLAIM, Map.of(ROLES_SEGMENT, List.of(rawRoles)));
    }

    /**
     * Converts an instant to the date type Nimbus's time claims take.
     *
     * @param instant the instant, or {@code null} to omit the claim
     * @return the date, or {@code null}
     */
    private static Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    /**
     * Starts a context trusting both stubs plus one provider that can never resolve.
     *
     * <p>{@code micronaut.security.enabled} is turned back on because {@code application-test.yml}
     * switches it off for the tests that predate this ticket, and the validator is built from beans
     * of the {@code io.micronaut.security} package.
     *
     * @return the started context, which {@link #stopFixtures()} closes
     */
    private static ApplicationContext startContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("micronaut.security.enabled", true);
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.put("micronaut.server.port", -1);
        properties.putAll(entry(PRIMARY, primary.issuer(), primary.discoveryUrl()));
        properties.putAll(entry(SECONDARY, secondary.issuer(), secondary.discoveryUrl()));
        properties.putAll(entry(UNRESOLVABLE, UNRESOLVABLE_ISSUER, UNRESOLVABLE_DISCOVERY_URL));
        return ApplicationContext.builder(Environment.TEST).properties(properties).start();
    }

    /**
     * Builds the properties of one trust-list entry.
     *
     * @param name the entry name
     * @param issuer the issuer it is trusted under
     * @param discoveryUrl where its metadata is published
     * @return the entry's properties
     */
    private static Map<String, Object> entry(String name, String issuer, String discoveryUrl) {
        String prefix = PROVIDER_PREFIX + name + ".";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(prefix + "issuer", issuer);
        properties.put(prefix + "discovery-url", discoveryUrl);
        properties.put(prefix + "audience", AUDIENCE);
        // WireMock and the unreachable entry both serve plain http.
        properties.put(prefix + "allow-insecure-transport", true);
        properties.put(prefix + "claims.user-identifier", List.of(USER_IDENTIFIER_CLAIM));
        properties.put(
                prefix + "claims.participant-identifier", List.of(PARTICIPANT_IDENTIFIER_CLAIM));
        properties.put(prefix + "claims.roles", ROLES_CLAIM);
        properties.put(prefix + "role-mapping.user", USER_ROLE_VALUE);
        properties.put(prefix + "role-mapping.participant", PARTICIPANT_ROLE_VALUE);
        properties.put(prefix + "role-mapping.catalog", CATALOG_ROLE_VALUE);
        return properties;
    }
}
