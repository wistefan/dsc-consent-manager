package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import java.time.Duration;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the default retry schedule: each attempt's jitter window, then exhaustion. */
@DisplayName("Notification backoff")
class NotificationBackoffTest {

    private static final double JITTER = ParticipantNotifications.DEFAULT_JITTER;

    private static final NotificationBackoff BACKOFF =
            new NotificationBackoff(ParticipantNotifications.DEFAULT_BACKOFF, JITTER);

    /** The largest double below 1, the top of {@link RandomGenerator#nextDouble()}. */
    private static final double HIGHEST_DRAW = Math.nextDown(1.0);

    private static final int SAMPLES = 200;

    static Stream<Arguments> schedule() {
        return Stream.of(
                Arguments.of(1, Duration.ofMinutes(1)),
                Arguments.of(2, Duration.ofMinutes(5)),
                Arguments.of(3, Duration.ofMinutes(15)),
                Arguments.of(4, Duration.ofHours(1)),
                Arguments.of(5, Duration.ofHours(6)),
                Arguments.of(6, Duration.ofHours(24)));
    }

    @ParameterizedTest(name = "after {0} failed attempts the next waits {1} ± jitter")
    @MethodSource("schedule")
    void delayStaysInsideTheJitterWindow(int attemptsMade, Duration base) {
        Duration lowest = scaled(base, 1 - JITTER);
        Duration highest = scaled(base, 1 + JITTER);

        assertThat(BACKOFF.delay(attemptsMade, fixed(0.0))).contains(lowest);
        assertThat(BACKOFF.delay(attemptsMade, fixed(0.5))).contains(base);
        assertThat(BACKOFF.delay(attemptsMade, fixed(HIGHEST_DRAW)).orElseThrow())
                .isBetween(base, highest);
        RandomGenerator random = RandomGenerator.getDefault();
        for (int i = 0; i < SAMPLES; i++) {
            assertThat(BACKOFF.delay(attemptsMade, random).orElseThrow())
                    .isBetween(lowest, highest);
        }
    }

    @ParameterizedTest(name = "after {0} attempts the schedule is exhausted")
    @ValueSource(ints = {7, 8, 0})
    void exhaustsAfterTheLastRetry(int attemptsMade) {
        assertThat(BACKOFF.delay(attemptsMade, RandomGenerator.getDefault())).isEmpty();
    }

    private static Duration scaled(Duration base, double factor) {
        return Duration.ofNanos(Math.round(base.toNanos() * factor));
    }

    /** A generator whose every draw is {@code value}. */
    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }
}
