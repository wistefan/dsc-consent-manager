package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.generated.model.ParticipantNotification;
import com.seamware.consentmanager.notification.NotificationEventType;
import com.seamware.consentmanager.notification.NotificationOutboxStatus;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Keeps the history route's enums naming exactly the values an outbox row can hold. */
@DisplayName("Participant notification enum parity")
class ParticipantNotificationEnumParityTest {

    static Stream<Arguments> servedEnums() {
        return Stream.of(
                Arguments.of(
                        "eventType",
                        names(ParticipantNotification.EventTypeEnum.values()),
                        names(NotificationEventType.values())),
                Arguments.of(
                        "status",
                        names(ParticipantNotification.StatusEnum.values()),
                        names(NotificationOutboxStatus.values())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servedEnums")
    @DisplayName("the served enum names exactly the domain's values")
    void servedEnumMatchesDomain(String property, List<String> served, List<String> domain) {
        assertThat(served).containsExactlyInAnyOrderElementsOf(domain);
    }

    private static List<String> names(Object[] values) {
        return Arrays.stream(values).map(String::valueOf).toList();
    }
}
