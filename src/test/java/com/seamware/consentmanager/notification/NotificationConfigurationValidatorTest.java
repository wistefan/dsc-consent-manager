package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import io.micronaut.context.exceptions.ConfigurationException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests the startup rules on the participant-notification settings, one violation per case. */
@DisplayName("Participant-notification configuration validation")
class NotificationConfigurationValidatorTest {

    @Test
    @DisplayName("the defaults are admitted")
    void admitsTheDefaults() {
        assertThatCode(
                        () ->
                                NotificationConfigurationValidator.check(
                                        new ParticipantNotifications()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the default max age covers the whole retry schedule")
    void defaultScheduleFitsTheMaxAge() {
        ParticipantNotifications defaults = new ParticipantNotifications();

        assertThat(NotificationConfigurationValidator.retrySchedule(defaults))
                .isLessThanOrEqualTo(defaults.getMaxAge());
    }

    static Stream<Arguments> violations() {
        return Stream.of(
                violation(
                        "a lease shorter than a batch of sends plus the margin",
                        s -> s.setLease(Duration.ofSeconds(46)),
                        "PARTICIPANT_NOTIFICATIONS_LEASE"),
                violation(
                        "a slower read timeout the lease no longer covers",
                        s -> s.setReadTimeout(Duration.ofSeconds(10)),
                        "PARTICIPANT_NOTIFICATIONS_LEASE"),
                violation(
                        "a per-participant cap equal to the concurrency",
                        s -> s.setPerParticipantBatchLimit(s.getConcurrency()),
                        "PARTICIPANT_NOTIFICATIONS_PER_PARTICIPANT_BATCH_LIMIT"),
                violation(
                        "no resolver threads",
                        s -> s.setResolverThreads(0),
                        "PARTICIPANT_NOTIFICATIONS_RESOLVER_THREADS"),
                violation(
                        "resolver threads that isolated dead hosts can fill",
                        s -> s.setResolverThreads(3),
                        "PARTICIPANT_NOTIFICATIONS_RESOLVER_THREADS"),
                violation(
                        "an empty backoff",
                        s -> s.setBackoff(List.of()),
                        "PARTICIPANT_NOTIFICATIONS_BACKOFF"),
                violation(
                        "a zero backoff entry",
                        s -> s.setBackoff(List.of(Duration.ofMinutes(1), Duration.ZERO)),
                        "PARTICIPANT_NOTIFICATIONS_BACKOFF"),
                violation(
                        "a negative jitter",
                        s -> s.setJitter(-0.1),
                        "PARTICIPANT_NOTIFICATIONS_JITTER"),
                violation(
                        "a jitter of one",
                        s -> s.setJitter(1.0),
                        "PARTICIPANT_NOTIFICATIONS_JITTER"),
                violation(
                        "a max age shorter than the retry schedule",
                        s -> s.setMaxAge(Duration.ofHours(34)),
                        "PARTICIPANT_NOTIFICATIONS_MAX_AGE"),
                violation(
                        "a max age the full jitter no longer fits",
                        s -> s.setJitter(0.2),
                        "PARTICIPANT_NOTIFICATIONS_MAX_AGE"),
                violation(
                        "a zero batch size",
                        s -> s.setBatchSize(0),
                        "PARTICIPANT_NOTIFICATIONS_BATCH_SIZE"),
                violation(
                        "a zero poll interval",
                        s -> s.setPollInterval(Duration.ZERO),
                        "PARTICIPANT_NOTIFICATIONS_POLL_INTERVAL"),
                violation(
                        "a zero sweep interval",
                        s -> s.setSweepInterval(Duration.ZERO),
                        "PARTICIPANT_NOTIFICATIONS_SWEEP_INTERVAL"),
                violation(
                        "a zero retention",
                        s -> s.setRetention(Duration.ZERO),
                        "PARTICIPANT_NOTIFICATIONS_RETENTION"));
    }

    @ParameterizedTest(name = "{0} is refused")
    @MethodSource("violations")
    void refusesNamingTheSetting(
            String description, Consumer<ParticipantNotifications> change, String named) {
        ParticipantNotifications settings = new ParticipantNotifications();
        change.accept(settings);

        assertThatThrownBy(() -> NotificationConfigurationValidator.check(settings))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(named);
    }

    private static Arguments violation(
            String description, Consumer<ParticipantNotifications> change, String named) {
        return Arguments.of(description, change, named);
    }
}
