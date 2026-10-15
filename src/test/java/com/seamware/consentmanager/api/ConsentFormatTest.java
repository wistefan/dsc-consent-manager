package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.error.BadRequestException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pins how a {@code format} query value selects a representation, and what a listing admits. */
class ConsentFormatTest {

    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of(null, ConsentFormat.FULL),
                Arguments.of("full", ConsentFormat.FULL),
                Arguments.of("receipt", ConsentFormat.RECEIPT),
                Arguments.of("receipt-kantara", ConsentFormat.RECEIPT_KANTARA));
    }

    @ParameterizedTest
    @MethodSource("values")
    void selectsTheNamedFormat(String value, ConsentFormat expected) {
        assertThat(ConsentFormat.of(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "FULL", "receipt_kantara", "jws"})
    void refusesAnUnknownValue(String value) {
        assertThatThrownBy(() -> ConsentFormat.of(value)).isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "full")
    void admitsFullOnAListing(String value) {
        assertThatCode(() -> ConsentFormat.requireListable(value)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"receipt", "receipt-kantara", "unknown"})
    void refusesAnythingElseOnAListing(String value) {
        assertThatThrownBy(() -> ConsentFormat.requireListable(value))
                .isInstanceOf(BadRequestException.class);
    }
}
