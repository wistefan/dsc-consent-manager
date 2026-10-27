package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins how a configured {@code API_PREFIX} is normalised before it prefixes a controller route. */
@DisplayName("Origin-root URI naming strategy")
class OriginRootUriNamingStrategyTest {

    /** Route the base strategy derives from {@link StatusController}'s name. */
    private static final String STATUS_ROUTE = "/status";

    /** Route the base strategy derives from {@link KeysController}'s name. */
    private static final String KEYS_ROUTE = "/keys";

    /** Plain controller, routed under the prefix. */
    private static final class StatusController {}

    /** Origin-root controller, never prefixed. */
    @OriginRoot
    private static final class KeysController {}

    private static Stream<Arguments> prefixes() {
        return Stream.of(
                Arguments.of(null, STATUS_ROUTE),
                Arguments.of("", STATUS_ROUTE),
                Arguments.of("/", STATUS_ROUTE),
                Arguments.of("v1", "/v1" + STATUS_ROUTE),
                Arguments.of("/v1", "/v1" + STATUS_ROUTE),
                Arguments.of("/v1/", "/v1" + STATUS_ROUTE),
                Arguments.of("v1/", "/v1" + STATUS_ROUTE),
                Arguments.of("/api/v1/", "/api/v1" + STATUS_ROUTE));
    }

    @ParameterizedTest(name = "api-prefix \"{0}\" routes a plain controller at {1}")
    @MethodSource("prefixes")
    void prefixesPlainControllersOnce(String prefix, String expected) {
        assertThat(new OriginRootUriNamingStrategy(prefix).resolveUri(StatusController.class))
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "api-prefix \"{0}\" leaves an @OriginRoot controller at the root")
    @MethodSource("prefixes")
    void neverPrefixesOriginRootControllers(String prefix, String ignored) {
        assertThat(new OriginRootUriNamingStrategy(prefix).resolveUri(KeysController.class))
                .isEqualTo(KEYS_ROUTE);
    }
}
