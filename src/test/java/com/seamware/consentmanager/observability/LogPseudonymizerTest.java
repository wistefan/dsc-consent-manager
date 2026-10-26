package com.seamware.consentmanager.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.support.TestPseudonymizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Log pseudonymizer")
class LogPseudonymizerTest {

    private static final String OTHER_KEY = "another-log-pseudonym-key-of-sufficient-length";

    private static final String HEX_DIGITS = "[0-9a-f]+";

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.com", "did:web:example.com:alice", "x"})
    @DisplayName("the same key and input give the same pseudonym, across instances")
    void isStable(String identifier) {
        assertThat(TestPseudonymizer.create().pseudonym(identifier))
                .isEqualTo(TestPseudonymizer.create().pseudonym(identifier));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.com", "did:web:example.com:alice", "x"})
    @DisplayName("a different key gives a different pseudonym")
    void dependsOnTheKey(String identifier) {
        assertThat(TestPseudonymizer.withKey(OTHER_KEY).pseudonym(identifier))
                .isNotEqualTo(TestPseudonymizer.create().pseudonym(identifier));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.com", "did:web:example.com:alice", "x", ""})
    @DisplayName("the pseudonym is hex of the named length and does not quote its input")
    void hasTheNamedShape(String identifier) {
        String pseudonym = TestPseudonymizer.create().pseudonym(identifier);

        assertThat(pseudonym).hasSize(LogPseudonymizer.PSEUDONYM_LENGTH).matches(HEX_DIGITS);
        if (!identifier.isEmpty()) {
            assertThat(pseudonym).doesNotContain(identifier);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "a-key-of-thirty-one-bytes-only!"})
    @DisplayName("a missing or short key fails construction without quoting the key")
    void refusesAnUnusableKey(String key) {
        assertThatThrownBy(() -> TestPseudonymizer.withKey(key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOG_PSEUDONYM_KEY")
                .satisfies(
                        failure -> {
                            if (key != null && !key.isBlank()) {
                                assertThat(failure.getMessage()).doesNotContain(key);
                            }
                        });
    }
}
