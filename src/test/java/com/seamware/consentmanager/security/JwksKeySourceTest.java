package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the two arithmetic rules that keep a configured JWKS cache lifetime usable.
 *
 * <p>Nimbus refuses to build a source whose refetch cooldown is not strictly shorter than its cache
 * lifetime, and it is right to: a cooldown outliving the cache would block the very refresh the
 * expiry asks for. That constraint is enforced at build time, which is the first token validation
 * of the process - far too late to learn that {@code jwks-cache-ttl: 5s} is unworkable. These rules
 * move the problem to startup arithmetic, so they are worth pinning on their own rather than only
 * through the behaviour they produce in {@link JwksKeySourceIT}.
 */
@DisplayName("JWKS cache lifetime and refetch cooldown arithmetic")
class JwksKeySourceTest {

    /** Provider name used in the warnings the methods under test emit. */
    private static final String PROVIDER_NAME = "primary";

    @DisplayName("a cache lifetime shorter than the floor is raised to it, longer ones are kept")
    @ParameterizedTest(name = "jwks-cache-ttl {0}s is used as {1}s")
    @CsvSource({"0, 10", "1, 10", "9, 10", "10, 10", "11, 11", "60, 60", "3600, 3600"})
    void cacheTtlIsFlooredButNeverShortened(long configuredSeconds, long expectedSeconds) {
        Duration effective =
                JwksKeySource.effectiveCacheTtl(
                        Duration.ofSeconds(configuredSeconds), PROVIDER_NAME);

        assertThat(effective).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @DisplayName("the cooldown is the nominal one, or half the cache lifetime when that is shorter")
    @ParameterizedTest(name = "a {0}s cache yields a {1}s cooldown")
    @CsvSource({"10, 5", "20, 10", "40, 20", "60, 30", "120, 30", "3600, 30"})
    void cooldownShrinksToFitAShortCache(long cacheSeconds, long expectedCooldownSeconds) {
        Duration cooldown =
                JwksKeySource.effectiveRefetchCooldown(Duration.ofSeconds(cacheSeconds));

        assertThat(cooldown).isEqualTo(Duration.ofSeconds(expectedCooldownSeconds));
    }

    @DisplayName("the derived cooldown is always strictly shorter than the cache lifetime")
    @ParameterizedTest(name = "a configured {0}s cache satisfies Nimbus's constraint")
    @ValueSource(longs = {0, 1, 9, 10, 11, 31, 59, 60, 61, 3600, 86_400})
    void cooldownAlwaysFitsInsideTheCache(long configuredSeconds) {
        Duration cacheTtl =
                JwksKeySource.effectiveCacheTtl(
                        Duration.ofSeconds(configuredSeconds), PROVIDER_NAME);

        assertThat(JwksKeySource.effectiveRefetchCooldown(cacheTtl))
                .as("Nimbus rejects a source whose cooldown is not shorter than its cache lifetime")
                .isLessThan(cacheTtl);
    }
}
