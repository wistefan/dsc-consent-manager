package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jwt.SignedJWT;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import com.seamware.consentmanager.support.TokenTampering;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The token-failure matrix against a live Keycloak: each case mutates a genuine realm token the way
 * an attacker would, and each must be refused with a {@code 401} problem.
 */
@MicronautTest(transactional = false)
@DisplayName("Tampered Keycloak tokens")
class KeycloakTokenTamperIT extends KeycloakAndPostgresTestResource {

    /** A real route the catalog role may read, so the untampered token answers 200. */
    private static final String ROUTE = "/participants";

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String BEARER = "Bearer ";

    private static final String SUBJECT_CLAIM = "sub";

    /** The subject a tampered payload claims instead of the one Keycloak signed. */
    private static final String FORGED_SUBJECT = "attacker-chosen-subject";

    /** A {@code kid} the realm has never published. */
    private static final String UNPUBLISHED_KEY_ID = "unpublished-key-id";

    /** Where a forged {@code x5u} header points; {@code .invalid} so nothing could answer. */
    private static final URI ATTACKER_CERTIFICATE_URL =
            URI.create("https://attacker.invalid/certificate.pem");

    private static final String PEM_HEADER = "-----BEGIN PUBLIC KEY-----\n";

    private static final String PEM_FOOTER = "\n-----END PUBLIC KEY-----\n";

    private static final int RSA_KEY_SIZE = 2048;

    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(60);

    /** A key no trusted issuer publishes, standing in for the attacker's own. */
    private static RSAKey attackerKey;

    /** An attacker-run key server the {@code jku} case points at; must never be dialled. */
    private static OidcDiscoveryStub attackerKeyServer;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @BeforeAll
    static void startAttacker() throws JOSEException {
        attackerKey = new RSAKeyGenerator(RSA_KEY_SIZE).keyID(UNPUBLISHED_KEY_ID).generate();
        attackerKeyServer = new OidcDiscoveryStub();
    }

    @AfterAll
    static void stopAttacker() {
        attackerKeyServer.close();
    }

    @BeforeEach
    void awaitRealm() {
        Await.until(
                "the Keycloak realm to resolve",
                RESOLUTION_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
    }

    /** Pins that every refusal below is the tampering's doing, not the route's or the token's. */
    @Test
    @DisplayName("the untampered token is accepted")
    void untamperedTokenIsAccepted() {
        assertThat(exchange(KeycloakTestResource.accessToken(RealmPrincipal.CATALOG)).code())
                .isEqualTo(HttpStatus.OK.getCode());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tamperings")
    @DisplayName("a tampered realm token is refused with a 401 problem")
    void tamperedTokenIsUnauthorized(String description, UnaryOperator<String> tamper) {
        String genuine = KeycloakTestResource.accessToken(RealmPrincipal.CATALOG);

        HttpResponse<String> response = exchange(tamper.apply(genuine));

        assertThat(response.code()).isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
        assertThat(response.getContentType())
                .hasValueSatisfying(type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
        assertThat(response.getBody(String.class))
                .hasValueSatisfying(
                        body ->
                                assertThat(body)
                                        .contains(
                                                "\"status\":" + HttpStatus.UNAUTHORIZED.getCode()));
        assertThat(attackerKeyServer.jwksRequestCount())
                .as("a header naming a key location is never followed")
                .isZero();
    }

    /** One mutation per attack on a genuine token; header edits void Keycloak's own signature. */
    static Stream<Arguments> tamperings() {
        return Stream.of(
                Arguments.of(
                        "payload claim changed",
                        tamper(
                                token ->
                                        TokenTampering.withClaims(
                                                token,
                                                claims ->
                                                        claims.put(
                                                                SUBJECT_CLAIM, FORGED_SUBJECT)))),
                Arguments.of(
                        "signature byte flipped", tamper(TokenTampering::withFlippedSignatureBit)),
                Arguments.of("alg rewritten to none", tamper(TokenTampering::unsecured)),
                Arguments.of(
                        "alg rewritten to HS256, keyed by the realm public key's DER bytes",
                        tamper(
                                token ->
                                        TokenTampering.hmacSigned(
                                                token, realmPublicKeyDer(token)))),
                Arguments.of(
                        "alg rewritten to HS256, keyed by the realm public key's PEM text",
                        tamper(
                                token ->
                                        TokenTampering.hmacSigned(
                                                token,
                                                (PEM_HEADER
                                                                + Base64.encode(
                                                                        realmPublicKeyDer(token))
                                                                + PEM_FOOTER)
                                                        .getBytes(StandardCharsets.US_ASCII)))),
                Arguments.of(
                        "kid removed",
                        tamper(
                                token ->
                                        TokenTampering.withHeader(
                                                token,
                                                header -> header.remove(TokenTampering.KID)))),
                Arguments.of(
                        "kid set to an unpublished value",
                        tamper(
                                token ->
                                        TokenTampering.withHeader(
                                                token,
                                                header ->
                                                        header.put(
                                                                TokenTampering.KID,
                                                                UNPUBLISHED_KEY_ID)))),
                Arguments.of(
                        "embedded jwk header carrying the attacker's key",
                        tamper(
                                token ->
                                        TokenTampering.resigned(
                                                token,
                                                attackerKey,
                                                new JWSHeader.Builder(JWSAlgorithm.RS256)
                                                        .keyID(
                                                                KeycloakTestResource.signingKeyId(
                                                                        token))
                                                        .jwk(attackerKey.toPublicJWK())))),
                Arguments.of(
                        "jku header naming the attacker's key server",
                        tamper(
                                token ->
                                        TokenTampering.resigned(
                                                token,
                                                attackerKey,
                                                new JWSHeader.Builder(JWSAlgorithm.RS256)
                                                        .keyID(UNPUBLISHED_KEY_ID)
                                                        .jwkURL(
                                                                URI.create(
                                                                        attackerKeyServer
                                                                                .jwksUri()))))),
                Arguments.of(
                        "x5u header naming the attacker's certificate",
                        tamper(
                                token ->
                                        TokenTampering.resigned(
                                                token,
                                                attackerKey,
                                                new JWSHeader.Builder(JWSAlgorithm.RS256)
                                                        .keyID(UNPUBLISHED_KEY_ID)
                                                        .x509CertURL(ATTACKER_CERTIFICATE_URL)))));
    }

    /** Names the lambda's type, so each case reads as data rather than a cast. */
    private static UnaryOperator<String> tamper(UnaryOperator<String> mutation) {
        return mutation;
    }

    /** The DER-encoded public key the realm published under the token's {@code kid}. */
    private static byte[] realmPublicKeyDer(String token) {
        try {
            String keyId = SignedJWT.parse(token).getHeader().getKeyID();
            return KeycloakTestResource.publishedKeys(KeycloakTestResource.REALM)
                    .getKeyByKeyId(keyId)
                    .toRSAKey()
                    .toPublicKey()
                    .getEncoded();
        } catch (ParseException | JOSEException e) {
            throw new IllegalStateException("Could not read the realm's public key", e);
        }
    }

    /** Presents {@code token} to {@link #ROUTE}; a refusal is returned rather than thrown. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(String token) {
        try {
            return client.toBlocking()
                    .exchange(
                            HttpRequest.GET(ROUTE)
                                    .header(HttpHeaders.AUTHORIZATION, BEARER + token),
                            String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }
}
