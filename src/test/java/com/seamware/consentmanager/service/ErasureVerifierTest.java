package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the verifier an erased record keeps: an operator has to be able to recompute it outside this
 * service, so the algorithm and the encoding are a published contract rather than an internal one.
 */
@DisplayName("Erasure verifier")
class ErasureVerifierTest {

    private static final String SECRET = "correct-horse-battery-staple";

    private static final String IDENTIFIER = "urn:test:user:ada";

    /**
     * {@code printf '%s' "$IDENTIFIER" | openssl dgst -sha256 -hmac "$SECRET" -binary | basenc
     * --base64url | tr -d '='} - the command an operator runs to check a candidate identifier.
     */
    private static final String EXPECTED = "eIeQUH6h03VLvyvDML2Ch_oxo_SxFb9JlJSk92N3ypI";

    @Test
    @DisplayName("reproduces the HMAC-SHA256 an operator computes outside the service")
    void matchesTheExternallyComputedValue() {
        assertThat(verifierWith(SECRET).verifierFor(IDENTIFIER)).isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("is stable for one identifier and different for another")
    void confirmsExactlyTheIdentifierItWasComputedFrom() {
        ErasureVerifier verifier = verifierWith(SECRET);

        assertThat(verifier.verifierFor(IDENTIFIER)).isEqualTo(verifier.verifierFor(IDENTIFIER));
        assertThat(verifier.verifierFor(IDENTIFIER + "x")).isNotEqualTo(EXPECTED);
    }

    @ParameterizedTest(name = "secret = {0}")
    @ValueSource(strings = {" ", "\t"})
    @NullAndEmptySource
    @DisplayName("yields nothing at all when the deployment configured no secret")
    void yieldsNothingWithoutASecret(String secret) {
        assertThat(verifierWith(secret).verifierFor(IDENTIFIER)).isNull();
    }

    @Test
    @DisplayName("a rotated secret abandons the verifiers written under the previous one")
    void changesWithTheSecret() {
        assertThat(verifierWith("other-secret").verifierFor(IDENTIFIER)).isNotEqualTo(EXPECTED);
    }

    private static ErasureVerifier verifierWith(String secret) {
        ConsentManagerConfiguration.Erasure erasure = new ConsentManagerConfiguration.Erasure();
        erasure.setVerificationSecret(secret);
        return new ErasureVerifier(erasure);
    }
}
