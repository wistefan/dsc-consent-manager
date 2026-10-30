package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.notification.NotificationRecipients.Notification;
import com.seamware.consentmanager.service.ConsentStateMachine;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Participant notification recipients")
class NotificationRecipientsTest {

    private static final UUID PROVIDER = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static final UUID CONSUMER = UUID.fromString("00000000-0000-7000-8000-000000000002");

    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-7000-8000-000000000003");

    private static final Set<UUID> BOTH = Set.of(PROVIDER, CONSUMER);

    private static final ConsentStateMachine STATES = new ConsentStateMachine();

    private static Arguments row(
            ConsentStatus held,
            ConsentStatus target,
            UUID initiator,
            NotificationEventType expected,
            Set<UUID> recipients) {
        return Arguments.of(held, target, initiator, expected, recipients);
    }

    /** Every move {@link ConsentStateMachine} permits, plus every creation, with no initiator. */
    static Stream<Arguments> unattributedMoves() {
        return Stream.of(
                row(null, ConsentStatus.GRANTED, null, NotificationEventType.CONSENT_GRANTED, BOTH),
                row(null, ConsentStatus.REFUSED, null, null, Set.of()),
                row(null, ConsentStatus.PENDING, null, null, Set.of()),
                row(
                        ConsentStatus.PENDING,
                        ConsentStatus.GRANTED,
                        null,
                        NotificationEventType.CONSENT_GRANTED,
                        BOTH),
                row(ConsentStatus.PENDING, ConsentStatus.REFUSED, null, null, Set.of()),
                row(ConsentStatus.PENDING, ConsentStatus.TERMINATED, null, null, Set.of()),
                row(
                        ConsentStatus.DRAFT,
                        ConsentStatus.GRANTED,
                        null,
                        NotificationEventType.CONSENT_GRANTED,
                        BOTH),
                row(ConsentStatus.DRAFT, ConsentStatus.REFUSED, null, null, Set.of()),
                row(ConsentStatus.DRAFT, ConsentStatus.TERMINATED, null, null, Set.of()),
                row(
                        ConsentStatus.GRANTED,
                        ConsentStatus.REVOKED,
                        null,
                        NotificationEventType.CONSENT_REVOKED,
                        BOTH),
                row(
                        ConsentStatus.GRANTED,
                        ConsentStatus.TERMINATED,
                        null,
                        NotificationEventType.CONSENT_TERMINATED,
                        BOTH),
                row(
                        ConsentStatus.GRANTED,
                        ConsentStatus.EXPIRED,
                        null,
                        NotificationEventType.CONSENT_EXPIRED,
                        BOTH));
    }

    /** Terminations by each party, and by a participant the consent does not name. */
    static Stream<Arguments> attributedTerminations() {
        return Stream.of(
                row(
                        ConsentStatus.GRANTED,
                        ConsentStatus.TERMINATED,
                        PROVIDER,
                        NotificationEventType.CONSENT_TERMINATED,
                        Set.of(CONSUMER)),
                row(
                        ConsentStatus.GRANTED,
                        ConsentStatus.TERMINATED,
                        CONSUMER,
                        NotificationEventType.CONSENT_TERMINATED,
                        Set.of(PROVIDER)),
                row(
                        ConsentStatus.GRANTED,
                        ConsentStatus.TERMINATED,
                        OUTSIDER,
                        NotificationEventType.CONSENT_TERMINATED,
                        BOTH),
                row(ConsentStatus.PENDING, ConsentStatus.TERMINATED, PROVIDER, null, Set.of()));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("unattributedMoves")
    @DisplayName("a move no party asked for notifies both parties exactly when it touches a grant")
    void unattributed(
            ConsentStatus held,
            ConsentStatus target,
            UUID initiator,
            NotificationEventType expected,
            Set<UUID> recipients) {
        assertNotification(held, target, initiator, PROVIDER, CONSUMER, expected, recipients);
    }

    @ParameterizedTest(name = "{0} -> {1} by {2}")
    @MethodSource("attributedTerminations")
    @DisplayName("a termination is never notified to the party that asked for it")
    void attributed(
            ConsentStatus held,
            ConsentStatus target,
            UUID initiator,
            NotificationEventType expected,
            Set<UUID> recipients) {
        assertNotification(held, target, initiator, PROVIDER, CONSUMER, expected, recipients);
    }

    @Test
    @DisplayName("the table covers every move the state machine permits")
    void coversEveryPermittedMove() {
        Set<String> covered =
                unattributedMoves()
                        .map(Arguments::get)
                        .filter(arguments -> arguments[0] != null)
                        .map(arguments -> arguments[0] + "->" + arguments[1])
                        .collect(Collectors.toSet());
        for (ConsentStatus from : EnumSet.allOf(ConsentStatus.class)) {
            for (ConsentStatus to : EnumSet.allOf(ConsentStatus.class)) {
                if (STATES.permits(from, to)) {
                    assertThat(covered).contains(from + "->" + to);
                }
            }
        }
    }

    @Test
    @DisplayName("a participant on both sides gets one notification")
    void sameParticipantOnBothSides() {
        assertNotification(
                ConsentStatus.GRANTED,
                ConsentStatus.REVOKED,
                null,
                PROVIDER,
                PROVIDER,
                NotificationEventType.CONSENT_REVOKED,
                Set.of(PROVIDER));
    }

    @Test
    @DisplayName("a participant on both sides that terminates its own consent tells no one")
    void sameParticipantTerminatingItsOwn() {
        assertNotification(
                ConsentStatus.GRANTED,
                ConsentStatus.TERMINATED,
                PROVIDER,
                PROVIDER,
                PROVIDER,
                null,
                Set.of());
    }

    private static void assertNotification(
            ConsentStatus held,
            ConsentStatus target,
            UUID initiator,
            UUID provider,
            UUID consumer,
            NotificationEventType expected,
            Set<UUID> recipients) {
        Optional<Notification> notification =
                NotificationRecipients.of(held, target, provider, consumer, initiator);
        if (expected == null) {
            assertThat(notification).isEmpty();
            return;
        }
        assertThat(notification)
                .hasValueSatisfying(
                        owed -> {
                            assertThat(owed.eventType()).isEqualTo(expected);
                            assertThat(owed.recipients())
                                    .containsExactlyInAnyOrderElementsOf(recipients);
                        });
    }
}
