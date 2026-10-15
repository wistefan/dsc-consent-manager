package com.seamware.consentmanager.signing;

import static com.seamware.consentmanager.signing.SigningFixtures.canonical;
import static com.seamware.consentmanager.signing.SigningFixtures.signing;
import static com.seamware.consentmanager.signing.SigningFixtures.store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.seamware.consentmanager.api.generated.model.ConsentReceipt;
import com.seamware.consentmanager.support.TestJson;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pins that equal JSON canonicalises to equal bytes, whichever library wrote it. */
@DisplayName("Canonical JSON")
class CanonicalJsonSerializerTest {

    /** The canonical form every numerically equal spelling of one reduces to. */
    private static final String CANONICAL_ONE = "{\"n\":1}";

    private static final String NUMBER_DOCUMENT = "{\"n\":%s}";

    private static final OffsetDateTime GRANTED_AT =
            OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final long CONSENT_TIMESTAMP = 1_767_225_600L;

    /** Escaped differently by Micronaut serde and by Nimbus's Gson. */
    private static final String AWKWARD_TEXT = "Ünïcode ✓ <b>&</b> \"quoted\" /  ";

    private final CanonicalJsonSerializer canonical = canonical();

    @Test
    @DisplayName("orders members by code point at every depth, whatever order they were written in")
    void ignoresMemberOrder() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("d", 2);
        inner.put("c", 3);
        Map<String, Object> written = new LinkedHashMap<>();
        written.put("b", inner);
        written.put("a", List.of(1, 2));

        assertThat(canonical.toJson(written)).isEqualTo("{\"a\":[1,2],\"b\":{\"c\":3,\"d\":2}}");
        assertThat(canonical.canonicalize("{\"b\":{\"d\":2,\"c\":3},\"a\":[1,2]}"))
                .isEqualTo(canonical.canonicalize("{\"a\":[1,2],\"b\":{\"c\":3,\"d\":2}}"))
                .isEqualTo(canonical.toJson(written));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1", "1.0", "1e0", "10e-1", "1E+0"})
    @DisplayName("writes numerically equal spellings as one number")
    void normalisesNumbers(String spelling) {
        assertThat(canonical.canonicalize(NUMBER_DOCUMENT.formatted(spelling)))
                .isEqualTo(CANONICAL_ONE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{\"n\":", "not json"})
    @DisplayName("refuses text that is not JSON")
    void refusesMalformedText(String text) {
        assertThatThrownBy(() -> canonical.canonicalize(text))
                .isInstanceOf(UncheckedIOException.class);
    }

    static Stream<Arguments> receipts() {
        Map<String, Object> kantaraLike = new LinkedHashMap<>();
        kantaraLike.put("version", "KI-CR-v1.1.0");
        kantaraLike.put("consentTimestamp", CONSENT_TIMESTAMP);
        kantaraLike.put("sensitive", false);
        kantaraLike.put("purposes", List.of(AWKWARD_TEXT));
        return Stream.of(
                Arguments.of(
                        "ISO/IEC 29184 record",
                        new ConsentReceipt(
                                        UUID.randomUUID(),
                                        ConsentReceipt.StandardEnum.ISO_IEC_29184_2020,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "did:web:subject.example",
                                        ConsentReceipt.StatusEnum.GRANTED,
                                        true,
                                        "did:web:provider.example",
                                        "did:web:consumer.example",
                                        GRANTED_AT,
                                        GRANTED_AT)
                                .title(AWKWARD_TEXT)
                                .purposes(List.of(AWKWARD_TEXT))),
                Arguments.of("Kantara-shaped map with a timestamp", kantaraLike));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("receipts")
    @DisplayName("verifies a receipt re-serialised by a different JSON library")
    void survivesAnotherJsonLibrary(String name, Object receipt) throws Exception {
        JwsConsentSigningService signing = signing(store(null));
        String jws = signing.sign(receipt).jws();
        String served = TestJson.mapper().writeValueAsString(receipt);

        String rewritten = JSONObjectUtils.toJSONString(JSONObjectUtils.parse(served));

        assertThat(rewritten).isNotEqualTo(served);
        assertThat(signing.verifyCanonical(canonical.canonicalize(rewritten), jws)).isTrue();
    }
}
