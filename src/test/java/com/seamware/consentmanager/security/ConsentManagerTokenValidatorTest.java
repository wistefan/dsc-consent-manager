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
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import com.seamware.consentmanager.support.TokenTampering;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

/**
 * Tests which tokens the validation pipeline turns into an {@link Authentication} and which into
 * nothing, against two WireMock providers so cross-issuer key confusion can be expressed. The 401
 * body itself is asserted in {@link TokenSignatureEnforcementTest}.
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

    /** User identifier claim; not the {@code sub} default, so a token can lack an identifier. */
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
     * Offset outside the validity window in the clock-skew cases; within the inherited {@link
     * IdentityProviderConfiguration#DEFAULT_CLOCK_SKEW}, so such a token is still accepted.
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

    /** A JWS payload that is well-formed base64url but not a JSON claim set. */
    private static final String NON_CLAIM_SET_PAYLOAD = "not a claim set";

    /** A subject claim present in form but naming nobody. */
    private static final String BLANK_SUBJECT = "   ";

    /** Separates the header, payload and signature segments of a compact JWS. */
    private static final String SEGMENT_SEPARATOR = ".";

    /** Index of the payload among a compact JWS's segments. */
    private static final int PAYLOAD_SEGMENT = 1;

    /** Where a forged {@code x5u} header points; {@code .invalid} so nothing could answer. */
    private static final URI ATTACKER_CERTIFICATE_URL =
            URI.create("https://attacker.invalid/certificate.pem");

    /** A {@code crit} member no implementation of this service understands. */
    private static final String UNRECOGNISED_CRITICAL_PARAMETER = "urn:test:unrecognised";

    private static OidcDiscoveryStub primary;
    private static OidcDiscoveryStub secondary;
    private static ApplicationContext context;
    private static ConsentManagerTokenValidator validator;

    /** Registry {@link #meteredValidator} publishes to, holding only this suite's refusals. */
    private static SimpleMeterRegistry claimMetrics;

    /** The validator built from the context's real beans but wired to {@link #claimMetrics}. */
    private static ConsentManagerTokenValidator meteredValidator;

    /** A locally generated key no provider publishes, used for the forgery cases. */
    private static RSAKey foreignKey;

    /** Starts both stub providers and a context trusting them, and waits for discovery. */
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
        claimMetrics = new SimpleMeterRegistry();
        meteredValidator =
                new ConsentManagerTokenValidator(
                        registry,
                        context.getBean(IssuerSignatureVerifier.class),
                        context.getBean(ClaimMapper.class),
                        claimMetrics);
    }

    @AfterEach
    void resetProviders() {
        primary.reset();
        secondary.reset();
    }

    /** Shuts the context and both stubs down. */
    @AfterAll
    static void stopFixtures() {
        closeQuietly(context);
        closeQuietly(primary);
        closeQuietly(secondary);
        if (claimMetrics != null) {
            claimMetrics.close();
        }
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
     * Every token that must not authenticate, minted lazily so time-dependent cases are relative to
     * when the test runs.
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
                        "no kid, signed by a key the issuer does not publish",
                        token(() -> signWithForeignKey(null, claims(primary.issuer())))),
                Arguments.of(
                        "an embedded jwk header carrying the key that signed it",
                        token(
                                () ->
                                        TokenTampering.resigned(
                                                sign(primary, claims(primary.issuer())),
                                                foreignKey,
                                                new JWSHeader.Builder(JWSAlgorithm.RS256)
                                                        .keyID(primary.initialKeyId())
                                                        .jwk(foreignKey.toPublicJWK())))),
                Arguments.of(
                        "a jku header naming another trusted issuer's key set, signed by its key",
                        token(
                                () ->
                                        secondary
                                                .signToken(
                                                        secondary.initialKeyId(),
                                                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                                                .keyID(secondary.initialKeyId())
                                                                .jwkURL(
                                                                        URI.create(
                                                                                secondary
                                                                                        .jwksUri())),
                                                        claims(primary.issuer()))
                                                .serialize())),
                Arguments.of(
                        "an x5u header naming a certificate the attacker serves",
                        token(
                                () ->
                                        TokenTampering.resigned(
                                                sign(primary, claims(primary.issuer())),
                                                foreignKey,
                                                new JWSHeader.Builder(JWSAlgorithm.RS256)
                                                        .keyID(UNPUBLISHED_KEY_ID)
                                                        .x509CertURL(ATTACKER_CERTIFICATE_URL)))),
                Arguments.of(
                        "an RS256 header over the issuer's EC key and its genuine ES256 signature",
                        token(
                                () ->
                                        TokenTampering.withHeader(
                                                primary.signToken(
                                                                primary.initialEcKeyId(),
                                                                claims(primary.issuer()))
                                                        .serialize(),
                                                header ->
                                                        header.put(
                                                                TokenTampering.ALG,
                                                                JWSAlgorithm.RS256.getName())))),
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
                        "a blank subject",
                        token(
                                () ->
                                        sign(
                                                primary,
                                                builder(primary.issuer())
                                                        .subject(BLANK_SUBJECT)
                                                        .build()))),
                Arguments.of(
                        "a genuine header over a payload that is not a claim set",
                        token(
                                () ->
                                        withPayload(
                                                sign(primary, claims(primary.issuer())),
                                                NON_CLAIM_SET_PAYLOAD))),
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

    /** Tokens whose header alone disqualifies them, whichever key signed them. */
    private static Stream<Arguments> refusedHeaders() {
        return Stream.of(
                Arguments.of(
                        "a genuine signature under a crit parameter nobody understands",
                        token(
                                () ->
                                        primary.signToken(
                                                        primary.initialKeyId(),
                                                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                                                .keyID(primary.initialKeyId())
                                                                .criticalParams(
                                                                        Set.of(
                                                                                UNRECOGNISED_CRITICAL_PARAMETER))
                                                                .customParam(
                                                                        UNRECOGNISED_CRITICAL_PARAMETER,
                                                                        Boolean.TRUE),
                                                        claims(primary.issuer()))
                                                .serialize())),
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
                Arguments.of("an empty token", token(() -> "")),
                Arguments.of("no token at all", token(() -> null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedHeaders")
    @DisplayName("refuses a token its header alone disqualifies")
    void refusesDisqualifyingHeaders(String description, Supplier<String> token) {
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

    /** Each raw role string and the mapped role it grants. */
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

    /** Pins that the no-kid refusal above is about the foreign key, not the absent kid. */
    @Test
    @DisplayName("authenticates a genuinely signed token whose header names no kid")
    void authenticatesGenuineTokenWithoutKeyId() {
        String token =
                primary.signToken(
                                primary.initialKeyId(),
                                new JWSHeader.Builder(JWSAlgorithm.RS256),
                                roles(builder(primary.issuer()), USER_ROLE_VALUE).build())
                        .serialize();

        assertThat(authenticate(token))
                .as("RFC 7515 makes kid optional, so every published key of the issuer is tried")
                .isPresent();
    }

    /** Roles claims that grant nothing, each of which must still authenticate. */
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
                        "%s is a valid token held by somebody without the role. That is a"
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
     * One refused token per {@link ConsentManagerTokenValidator#REASON_TAG} value; every refusal is
     * a bare 401, so this counter is an operator's only way to tell the causes apart.
     */
    private static Stream<Arguments> countedClaimRejections() {
        return Stream.of(
                Arguments.of(
                        "an expired token",
                        ConsentManagerTokenValidator.REASON_TIME,
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
                        "an audience belonging to another service",
                        ConsentManagerTokenValidator.REASON_AUDIENCE,
                        token(
                                () ->
                                        sign(
                                                primary,
                                                builder(primary.issuer())
                                                        .audience(FOREIGN_AUDIENCE)
                                                        .build()))),
                Arguments.of(
                        "no subject",
                        ConsentManagerTokenValidator.REASON_SUBJECT,
                        token(
                                () ->
                                        sign(
                                                primary,
                                                builder(primary.issuer()).subject(null).build()))),
                Arguments.of(
                        "a USER token with no user identifier claim",
                        ConsentManagerTokenValidator.REASON_IDENTIFIER,
                        token(
                                () ->
                                        sign(
                                                primary,
                                                roles(builder(primary.issuer()), USER_ROLE_VALUE)
                                                        .claim(USER_IDENTIFIER_CLAIM, null)
                                                        .build()))));
    }

    @ParameterizedTest(name = "{0} counts as reason={1}")
    @MethodSource("countedClaimRejections")
    @DisplayName("counts a claim-level refusal under its own reason and under no other")
    void countsClaimRejectionsByReason(String description, String reason, Supplier<String> token) {
        double taggedBefore = claimRejections(reason);
        double totalBefore = allClaimRejections();

        assertThat(meteredAuthenticate(token.get()))
                .as("%s must authenticate nobody", description)
                .isEmpty();

        assertThat(claimRejections(reason))
                .as("%s must be counted under reason=%s", description, reason)
                .isEqualTo(taggedBefore + 1);
        assertThat(allClaimRejections())
                .as("%s must be counted exactly once, under no other reason", description)
                .isEqualTo(totalBefore + 1);
    }

    @Test
    @DisplayName("refuses and authenticates without a meter registry, as when metrics are off")
    void validatesWithoutAMeterRegistry() {
        ConsentManagerTokenValidator unmetered =
                new ConsentManagerTokenValidator(
                        context.getBean(IdentityProviderRegistry.class),
                        context.getBean(IssuerSignatureVerifier.class),
                        context.getBean(ClaimMapper.class),
                        null);
        String subjectless = sign(primary, builder(primary.issuer()).subject(null).build());
        String valid = sign(primary, roles(builder(primary.issuer()), USER_ROLE_VALUE).build());

        assertThat(
                        Mono.from(unmetered.validateToken(subjectless, null))
                                .blockOptional(VALIDATION_TIMEOUT))
                .as("a counted refusal must not need a bound meter registry")
                .isEmpty();
        assertThat(
                        Mono.from(unmetered.validateToken(valid, null))
                                .blockOptional(VALIDATION_TIMEOUT))
                .isPresent();
    }

    @Test
    @DisplayName("counts no claim refusal for a token that authenticates")
    void countsNothingForAValidToken() {
        double totalBefore = allClaimRejections();
        String valid = sign(primary, roles(builder(primary.issuer()), USER_ROLE_VALUE).build());

        assertThat(meteredAuthenticate(valid)).isPresent();

        assertThat(allClaimRejections())
                .as("a counter that also moved on success would be unreadable as an alert")
                .isEqualTo(totalBefore);
    }

    /** Runs a token through the validator wired to {@link #claimMetrics}. */
    private static Optional<Authentication> meteredAuthenticate(String token) {
        return Mono.from(meteredValidator.validateToken(token, null))
                .blockOptional(VALIDATION_TIMEOUT);
    }

    /** Refusal count for one {@code reason} tag of the primary provider; zero if unregistered. */
    private static double claimRejections(String reason) {
        Counter counter =
                claimMetrics
                        .find(ConsentManagerTokenValidator.CLAIM_REJECTION_METRIC)
                        .tag(IssuerSignatureVerifier.PROVIDER_TAG, PRIMARY)
                        .tag(ConsentManagerTokenValidator.REASON_TAG, reason)
                        .counter();
        return counter == null ? 0 : counter.count();
    }

    /** Sums refusal counts across every tag, pinning that a refusal is counted exactly once. */
    private static double allClaimRejections() {
        return claimMetrics
                .find(ConsentManagerTokenValidator.CLAIM_REJECTION_METRIC)
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    /** Runs a raw compact token through the validator under test. */
    private static Optional<Authentication> authenticate(String token) {
        return Mono.from(validator.validateToken(token, null)).blockOptional(VALIDATION_TIMEOUT);
    }

    /** Replaces a compact token's payload segment, keeping its header and signature. */
    private static String withPayload(String token, String payload) {
        String[] segments = token.split(Pattern.quote(SEGMENT_SEPARATOR));
        segments[PAYLOAD_SEGMENT] = Base64URL.encode(payload).toString();
        return String.join(SEGMENT_SEPARATOR, segments);
    }

    /** Types a token-minting lambda so a case needs no cast. */
    private static Supplier<String> token(Supplier<String> supplier) {
        return supplier;
    }

    /** Signs a claim set with a stub's current key and serialises it. */
    private static String sign(OidcDiscoveryStub provider, JWTClaimsSet claims) {
        return provider.signToken(provider.initialKeyId(), claims).serialize();
    }

    /** Signs a claim set with a local key no provider publishes, advertising any {@code kid}. */
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
     * Algorithm-confusion forgery: signs with {@code HS256} keyed by the issuer's public RSA
     * modulus, which verifies only if the token's header may choose the algorithm.
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

    /** Reads the modulus of a stub's first published RSA key off its JWK Set endpoint. */
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

    /** Builds a well-formed claim set for the issuer; {@code null} omits {@code iss}. */
    private static JWTClaimsSet claims(String issuer) {
        return builder(issuer).build();
    }

    /** Builds a claim set with explicit time claims; a {@code null} argument omits its claim. */
    private static JWTClaimsSet timed(
            String issuer, Instant expiry, Instant issuedAt, Instant notBefore) {
        return builder(issuer)
                .expirationTime(toDate(expiry))
                .issueTime(toDate(issuedAt))
                .notBeforeTime(toDate(notBefore))
                .build();
    }

    /** Starts a claim set that passes every check, so a case need only state what it breaks. */
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

    /** Adds a Keycloak-shaped nested roles claim. */
    private static JWTClaimsSet.Builder roles(JWTClaimsSet.Builder builder, String... rawRoles) {
        return builder.claim(REALM_ACCESS_CLAIM, Map.of(ROLES_SEGMENT, List.of(rawRoles)));
    }

    /** Converts an instant to a Nimbus time-claim date, passing {@code null} through. */
    private static Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    /**
     * Starts a context trusting both stubs plus one that never resolves, with security re-enabled
     * because the validator is built from {@code io.micronaut.security} beans.
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

    /** Builds the properties of one trust-list entry. */
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
