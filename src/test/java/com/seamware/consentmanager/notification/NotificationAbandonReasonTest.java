package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.observability.NotificationMetrics.AbandonReason;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pins the bounded metric reason each abandoning outcome is counted under. */
class NotificationAbandonReasonTest {

    @ParameterizedTest(name = "\"{0}\" is counted as {1}")
    @CsvSource({
        "participant deregistered, PARTICIPANT_DEREGISTERED",
        "no consentNotification endpoint, NO_ENDPOINT",
        "payload missing, PAYLOAD_MISSING",
        "endpoint not permitted, ENDPOINT_NOT_PERMITTED",
        "HTTP 400, REJECTED",
        "HTTP 302, REJECTED"
    })
    void mapsTheOutcomeToABoundedReason(String reason, AbandonReason expected) {
        assertThat(NotificationDispatcher.abandonReason(DeliveryOutcome.abandon(reason)))
                .isEqualTo(expected);
    }
}
