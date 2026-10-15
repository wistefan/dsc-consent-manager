package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.jwk.AsymmetricJWK;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.signing.CanonicalJsonSerializer;
import com.seamware.consentmanager.signing.ConsentSigningService;
import com.seamware.consentmanager.signing.JwsConsentSigningService;
import com.seamware.consentmanager.signing.SignedReceipt;
import com.seamware.consentmanager.signing.SigningKeyStore;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Proves the published JWKS, with security on and an active plus a retired key configured, is
 * anonymous, cacheable, public-only, and enough for plain Nimbus to verify a receipt.
 */
@MicronautTest(transactional = false)
@DisplayName("Receipt signing JWKS")
class JwksIT extends KeycloakAndPostgresTestResource {

    private static final String PATH_JWKS = "/.well-known/jwks.json";

    private static final String EXPECTED_CACHE_CONTROL = "public, max-age=3600";

    private static final String SIGNING_KEY_PROPERTY = "consent-manager.receipts.signing-key";

    private static final String RETIRED_KEYS_PROPERTY =
            "consent-manager.receipts.retired-verification-keys";

    private static final String ACTIVE_KEY_ID = "jwks-it-active";

    private static final String RETIRED_KEY_ID = "jwks-it-retired";

    private static final int RSA_KEY_SIZE = 2048;

    private static final String KEYS_MEMBER = "keys";

    /** The JWK members (RFC 7518 Section 6) that hold private or symmetric key material. */
    private static final Set<String> PRIVATE_MEMBERS = Set.of("d", "p", "q", "dp", "dq", "qi", "k");

    /** The members every published key must name. */
    private static final Set<String> REQUIRED_MEMBERS = Set.of("kid", "alg", "use");

    private static final Map<String, Object> RECEIPT =
            Map.of("consentReceiptID", "jwks-it", "piiPrincipalId", "urn:test:subject");

    /** RSA, so the published set covers both projections and every RSA private member. */
    private static final RSAKey ACTIVE = rsaKey();

    /** Its private half signed receipts before the rotation; only the public half is configured. */
    private static final ECKey RETIRED = ecKey();

    /** Which key a receipt under test was signed with. */
    enum SigningKey {
        ACTIVE,
        RETIRED
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ConsentSigningService signing;

    @Inject CanonicalJsonSerializer canonical;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put(SIGNING_KEY_PROPERTY, ACTIVE.toJSONString());
        properties.put(RETIRED_KEYS_PROPERTY, new JWKSet(RETIRED.toPublicJWK()).toString(true));
        return properties;
    }

    @Test
    @DisplayName("is served anonymously and may be cached for an hour")
    void anonymousAndCacheable() {
        HttpResponse<String> response = fetch();

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getHeaders().get(HttpHeaders.CACHE_CONTROL))
                .isEqualTo(EXPECTED_CACHE_CONTROL)
                .isEqualTo(ReceiptSigningController.JWKS_CACHE_CONTROL);
    }

    @Test
    @DisplayName("names kid, alg and use=sig on every key and no private member on any")
    void everyKeyIsPublicAndDescribed() throws ParseException {
        List<Object> keys = JSONObjectUtils.getJSONArray(rawBody(), KEYS_MEMBER);

        assertThat(keys)
                .isNotEmpty()
                .allSatisfy(
                        key -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> members = (Map<String, Object>) key;
                            assertThat(members.keySet())
                                    .containsAll(REQUIRED_MEMBERS)
                                    .doesNotContainAnyElementsOf(PRIVATE_MEMBERS);
                        });
    }

    @Test
    @DisplayName("parses as a JWK Set holding the active key first, then every retired key")
    void activeKeyFirstThenRetired() throws ParseException {
        JWKSet published = JWKSet.parse(fetch().body());

        assertThat(published.getKeys())
                .extracting(JWK::getKeyID)
                .containsExactly(ACTIVE_KEY_ID, RETIRED_KEY_ID);
        assertThat(published.getKeys())
                .allSatisfy(
                        key -> {
                            assertThat(key.isPrivate()).isFalse();
                            assertThat(key.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
                        });
        assertThat(published.getKeyByKeyId(ACTIVE_KEY_ID).toRSAKey().getModulus())
                .isEqualTo(ACTIVE.getModulus());
        assertThat(published.getKeyByKeyId(RETIRED_KEY_ID).toECKey().getX())
                .isEqualTo(RETIRED.getX());
    }

    @ParameterizedTest
    @EnumSource(SigningKey.class)
    @DisplayName("verifies a receipt with plain Nimbus and the served set alone")
    void receiptVerifiesAgainstTheServedSet(SigningKey signedWith)
            throws ParseException, JOSEException {
        SignedReceipt<Map<String, Object>> signed = sign(signedWith);
        JWKSet published = JWKSet.parse(fetch().body());

        JWSObject jws = JWSObject.parse(signed.jws(), new Payload(signed.canonicalJson()));
        JWK key = published.getKeyByKeyId(jws.getHeader().getKeyID());
        assertThat(key).as("the key the JWS header names").isNotNull();
        JWSVerifier verifier =
                new DefaultJWSVerifierFactory()
                        .createJWSVerifier(jws.getHeader(), ((AsymmetricJWK) key).toPublicKey());

        assertThat(jws.verify(verifier)).isTrue();
    }

    /** The active key signs through the context's bean; the retired one as it did pre-rotation. */
    private SignedReceipt<Map<String, Object>> sign(SigningKey signedWith) {
        return switch (signedWith) {
            case ACTIVE -> signing.sign(RECEIPT);
            case RETIRED -> beforeRotation().sign(RECEIPT);
        };
    }

    private ConsentSigningService beforeRotation() {
        ConsentManagerConfiguration.Receipts settings = new ConsentManagerConfiguration.Receipts();
        settings.setSigningKey(RETIRED.toJSONString());
        return new JwsConsentSigningService(new SigningKeyStore(settings), canonical);
    }

    private Map<String, Object> rawBody() throws ParseException {
        return JSONObjectUtils.parse(fetch().body());
    }

    private HttpResponse<String> fetch() {
        return client.toBlocking().exchange(HttpRequest.GET(PATH_JWKS), String.class);
    }

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(RSA_KEY_SIZE).keyID(ACTIVE_KEY_ID).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ECKey ecKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(RETIRED_KEY_ID).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
