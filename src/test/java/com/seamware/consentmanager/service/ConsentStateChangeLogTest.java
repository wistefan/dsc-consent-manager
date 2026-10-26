package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.observability.ConsentMetrics;
import com.seamware.consentmanager.support.LogCapture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

@DisplayName("Consent state-change log")
class ConsentStateChangeLogTest {

    private static final String HELD_STATUS_FIELD = "\"heldStatus\"";

    private static final String PROVIDER = "urn:test:participant:log-provider";

    private static final String CONSUMER = "urn:test:participant:log-consumer";

    /** How each seam is invoked, the line's expected transition text, and its counter's tags. */
    static Stream<Arguments> writes() {
        BiConsumer<ConsentStateChangeLog, ConsentDetails> created =
                (log, details) -> log.created(details, event(details));
        BiConsumer<ConsentStateChangeLog, ConsentDetails> moved =
                (log, details) -> log.recorded(ConsentStatus.PENDING, details, event(details));
        return Stream.of(
                Arguments.of("a creation", created, false, "none -> GRANTED", "none"),
                Arguments.of("a move", moved, true, "PENDING -> GRANTED", "PENDING"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writes")
    @DisplayName("names the held status as a JSON field only for a move, and counts the write")
    void namesTheHeldStatusOnlyForAMove(
            String name,
            BiConsumer<ConsentStateChangeLog, ConsentDetails> write,
            boolean carriesHeldStatus,
            String transition,
            String from) {
        MeterRegistry registry = new SimpleMeterRegistry();
        ConsentStateChangeLog log = new ConsentStateChangeLog(new ConsentMetrics(registry));
        ConsentDetails details = granted();

        String line;
        try (LogCapture capture = LogCapture.attach()) {
            write.accept(log, details);
            line = encode(lineFor(capture, details.consent()));
        }

        assertThat(line.contains(HELD_STATUS_FIELD)).isEqualTo(carriesHeldStatus);
        assertThat(line).contains(transition);
        assertThat(
                        registry.get(ConsentMetrics.TRANSITIONS_METRIC)
                                .tag(ConsentMetrics.FROM_TAG, from)
                                .tag(ConsentMetrics.TO_TAG, ConsentStatus.GRANTED.name())
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    private static ConsentDetails granted() {
        Consent consent =
                Consent.withStatus(
                        ConsentStatus.GRANTED,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        noTerms());
        return new ConsentDetails(consent, PROVIDER, CONSUMER, true);
    }

    private static ConsentEvent event(ConsentDetails details) {
        return new ConsentEvent(
                details.consent().getId(), ConsentEventState.CONSENT_GIVEN, null, null);
    }

    private static ILoggingEvent lineFor(LogCapture capture, Consent consent) {
        return capture.events().stream()
                .filter(
                        event ->
                                event.getLoggerName().equals(ConsentStateChangeLog.class.getName()))
                .filter(event -> event.getFormattedMessage().contains(consent.getId().toString()))
                .findFirst()
                .orElseThrow();
    }

    /** The JSON line the production encoder writes for the event. */
    private static String encode(ILoggingEvent event) {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(
                ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).getLoggerContext());
        encoder.start();
        try {
            return new String(encoder.encode(event), StandardCharsets.UTF_8);
        } finally {
            encoder.stop();
        }
    }
}
