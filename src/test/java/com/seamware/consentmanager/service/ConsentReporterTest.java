package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.observability.ConsentMetrics;
import com.seamware.consentmanager.support.LogCapture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Consent reporter")
class ConsentReporterTest {

    /** Stands in for a transient database failure while naming the parties. */
    private static final String LOOKUP_FAILURE = "participant lookup failed";

    @Test
    @DisplayName("swallows and logs a failure reporting bulk moves, counting nothing")
    void reportingBulkMovesNeverThrows() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ConsentParties failing =
                new ConsentParties(null) {
                    @Override
                    public List<ConsentDetails> details(List<RecordedConsent> recorded) {
                        throw new IllegalStateException(LOOKUP_FAILURE);
                    }
                };
        ConsentReporter reporter =
                new ConsentReporter(
                        failing, new ConsentStateChangeLog(new ConsentMetrics(registry)));

        try (LogCapture capture = LogCapture.attach()) {
            assertThatCode(() -> reporter.report(List.of(terminatedMove())))
                    .doesNotThrowAnyException();

            assertThat(capture.events())
                    .anySatisfy(
                            event -> {
                                assertThat(event.getLoggerName())
                                        .isEqualTo(ConsentReporter.class.getName());
                                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                                assertThat(event.getFormattedMessage())
                                        .contains(IllegalStateException.class.getName())
                                        .doesNotContain(LOOKUP_FAILURE);
                            });
        }
        assertThat(registry.find(ConsentMetrics.TRANSITIONS_METRIC).counters()).isEmpty();
    }

    private static ConsentMove terminatedMove() {
        Consent consent =
                Consent.withStatus(
                        ConsentStatus.TERMINATED,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        noTerms());
        ConsentEvent event =
                new ConsentEvent(consent.getId(), ConsentEventState.CONSENT_TERMINATED, null, null);
        return new ConsentMove(ConsentStatus.PENDING, new RecordedConsent(consent, event, false));
    }
}
