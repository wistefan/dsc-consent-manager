package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import io.micronaut.context.ApplicationContext;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The webhook client resolves from the real context, with the allow-list bound through the
 * application.yml placeholder, so a wiring mistake fails here rather than in the dispatcher.
 */
class ParticipantWebhookClientBindingTest {

    private static final String ALLOWED_HOSTS_VARIABLE = "PARTICIPANT_NOTIFICATIONS_ALLOWED_HOSTS";

    private static final String LISTED_HOSTS = "a.example, ,B.example";

    private static final String UNLISTED_ENDPOINT = "https://other.example/consent";

    static Stream<Arguments> allowListBindings() {
        return Stream.of(
                Arguments.of("unset", Map.of(), List.of()),
                Arguments.of("empty", Map.of(ALLOWED_HOSTS_VARIABLE, ""), List.of()),
                Arguments.of(
                        "listed with a blank entry",
                        Map.of(ALLOWED_HOSTS_VARIABLE, LISTED_HOSTS),
                        List.of("a.example", "B.example")));
    }

    @ParameterizedTest(name = "allowed hosts {0}")
    @MethodSource("allowListBindings")
    @DisplayName("the client resolves and the allow-list binds without blank entries")
    void bindsTheAllowList(String label, Map<String, Object> properties, List<String> expected) {
        try (ApplicationContext context = NotificationContexts.start(properties)) {
            assertThat(context.getBean(ParticipantWebhookClient.class)).isNotNull();
            assertThat(context.getBean(HostResolver.class))
                    .isInstanceOf(HostResolver.SystemHostResolver.class);
            assertThat(context.getBean(ParticipantNotifications.class).getAllowedHosts())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("a bound allow-list refuses an unlisted host before resolving it")
    void boundAllowListRefusesUnlistedHosts() {
        try (ApplicationContext context =
                NotificationContexts.start(Map.of(ALLOWED_HOSTS_VARIABLE, LISTED_HOSTS))) {
            DeliveryOutcome outcome =
                    context.getBean(ParticipantWebhookClient.class)
                            .send(UNLISTED_ENDPOINT, ParticipantWebhookClientTest.payload());

            assertThat(outcome)
                    .isEqualTo(
                            DeliveryOutcome.abandon(
                                    ParticipantWebhookClient.ENDPOINT_NOT_PERMITTED));
        }
    }
}
