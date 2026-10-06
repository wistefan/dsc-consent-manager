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
 * The salt is equally load-bearing - without it the column would correlate two erasures of the same
 * person for anyone who can read the table.
 */
@DisplayName("Erasure verifier")
class ErasureVerifierTest {

    private static final String SECRET = "correct-horse-battery-staple";

    private static final String IDENTIFIER = "urn:test:user:ada";

    /**
     * A verifier built outside this service: {@code printf '%s' "${VERIFIER%%.*}$IDENTIFIER" |
     * openssl dgst -sha256 -hmac "$SECRET" -binary | basenc --base64url | tr -d '='} over the salt
     * in its first field - the command an operator runs to check a candidate identifier.
     */
    private static final String EXTERNALLY_COMPUTED =
            "Zm9vLXNhbHQtdmVjdG9y.-AKxUbuy9ngGKIkarmkrZK7xG1dkXIamulCi_8PnhPw";

    @Test
    @DisplayName("accepts the identifier behind an externally computed verifier")
    void matchesTheExternallyComputedValue() {
        assertThat(verifierWith(SECRET).matches(EXTERNALLY_COMPUTED, IDENTIFIER)).isTrue();
    }

    @Test
    @DisplayName("confirms exactly the identifier it was computed from")
    void confirmsExactlyTheIdentifierItWasComputedFrom() {
        ErasureVerifier verifier = verifierWith(SECRET);

        String value = verifier.verifierFor(IDENTIFIER);

        assertThat(verifier.matches(value, IDENTIFIER)).isTrue();
        assertThat(verifier.matches(value, IDENTIFIER + "x")).isFalse();
    }

    @Test
    @DisplayName("is salted, so two erasures of one person cannot be linked by the column")
    void saltsEveryValue() {
        ErasureVerifier verifier = verifierWith(SECRET);

        String first = verifier.verifierFor(IDENTIFIER);
        String second = verifier.verifierFor(IDENTIFIER);

        assertThat(first).isNotEqualTo(second);
        assertThat(verifier.matches(first, IDENTIFIER)).isTrue();
        assertThat(verifier.matches(second, IDENTIFIER)).isTrue();
    }

    @ParameterizedTest(name = "verifier = {0}")
    @ValueSource(strings = {"not-a-verifier", ".", "no-mac.", ".no-salt", "Zm9v.YmFy"})
    @NullAndEmptySource
    @DisplayName("refuses a missing or malformed verifier instead of throwing")
    void refusesMalformedValues(String verifier) {
        assertThat(verifierWith(SECRET).matches(verifier, IDENTIFIER)).isFalse();
    }

    @ParameterizedTest(name = "secret = {0}")
    @ValueSource(strings = {" ", "\t"})
    @NullAndEmptySource
    @DisplayName("yields nothing at all when the deployment configured no secret")
    void yieldsNothingWithoutASecret(String secret) {
        ErasureVerifier verifier = verifierWith(secret);

        assertThat(verifier.verifierFor(IDENTIFIER)).isNull();
        assertThat(verifier.matches(EXTERNALLY_COMPUTED, IDENTIFIER)).isFalse();
    }

    @Test
    @DisplayName("a rotated secret abandons the verifiers written under the previous one")
    void changesWithTheSecret() {
        assertThat(verifierWith("other-secret").matches(EXTERNALLY_COMPUTED, IDENTIFIER)).isFalse();
    }

    private static ErasureVerifier verifierWith(String secret) {
        ConsentManagerConfiguration.Erasure erasure = new ConsentManagerConfiguration.Erasure();
        erasure.setVerificationSecret(secret);
        return new ErasureVerifier(erasure);
    }
}
