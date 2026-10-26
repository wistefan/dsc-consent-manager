package com.seamware.consentmanager.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins which inbound {@code X-Request-ID} values are adopted and which are replaced. */
@DisplayName("an inbound X-Request-ID is adopted only when well-formed")
class CorrelationIdFilterTest {

    /** A caller-supplied ID in the accepted shape. */
    private static final String VALID_ID = "req-42_a.b~c";

    static Stream<Arguments> inbound() {
        return Stream.of(
                Arguments.of("absent", null, false),
                Arguments.of("valid", VALID_ID, true),
                Arguments.of(
                        "valid, at the length limit",
                        "a".repeat(CorrelationIdFilter.MAX_LENGTH),
                        true),
                Arguments.of("empty", "", false),
                Arguments.of("too long", "a".repeat(CorrelationIdFilter.MAX_LENGTH + 1), false),
                Arguments.of("forged log line", "abc\n{\"level\":\"ERROR\"}", false),
                Arguments.of("whitespace", "abc def", false),
                Arguments.of("non-ASCII", "abcé", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inbound")
    void adoptsOrReplaces(String description, String header, boolean adopted) {
        String correlationId = CorrelationIdFilter.accept(header);

        if (adopted) {
            assertThat(correlationId).isEqualTo(header);
        } else {
            assertThat(UUID.fromString(correlationId).version())
                    .as("a generated ID is a UUIDv7")
                    .isEqualTo(7);
        }
    }
}
