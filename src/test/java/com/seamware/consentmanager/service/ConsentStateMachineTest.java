package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.error.BadRequestException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the lifecycle table over the whole status square, not only the pairs the flows use.
 *
 * <p>The expectation is written out here rather than read from the production table, so adding a
 * transition to the service fails this suite until the addition is stated twice.
 */
@DisplayName("Consent state machine")
class ConsentStateMachineTest {

    /** Where an unanswered offer may go: answered either way, or terminated with the offer. */
    private static final Set<ConsentStatus> UNANSWERED_MOVES =
            EnumSet.of(ConsentStatus.GRANTED, ConsentStatus.REFUSED, ConsentStatus.TERMINATED);

    /** The transitions this service permits, restated independently of the production table. */
    private static final Map<ConsentStatus, Set<ConsentStatus>> PERMITTED =
            Map.of(
                    ConsentStatus.PENDING, UNANSWERED_MOVES,
                    ConsentStatus.DRAFT, UNANSWERED_MOVES,
                    ConsentStatus.GRANTED,
                            EnumSet.of(
                                    ConsentStatus.REVOKED,
                                    ConsentStatus.TERMINATED,
                                    ConsentStatus.EXPIRED),
                    ConsentStatus.REVOKED, Set.of(),
                    ConsentStatus.REFUSED, Set.of(),
                    ConsentStatus.TERMINATED, Set.of(),
                    ConsentStatus.EXPIRED, Set.of());

    /** The statuses a consent may be created in. {@code DRAFT} is not one of them. */
    private static final Set<ConsentStatus> CREATABLE =
            EnumSet.of(ConsentStatus.GRANTED, ConsentStatus.REFUSED, ConsentStatus.PENDING);

    private final ConsentStateMachine lifecycle = new ConsentStateMachine();

    @ParameterizedTest(name = "{0} -> {1} permitted: {2}")
    @MethodSource("everyPair")
    @DisplayName("every pair of statuses is permitted or refused exactly as the table says")
    void everyPairIsPermittedOrRefusedAsTheTableSays(
            ConsentStatus from, ConsentStatus to, boolean permitted) {
        assertThat(lifecycle.permits(from, to)).isEqualTo(permitted);
    }

    @ParameterizedTest(name = "{0} -> {1} permitted: {2}")
    @MethodSource("everyPair")
    @DisplayName("a refused move names both the held and the attempted status")
    void aRefusedMoveNamesBothStatuses(ConsentStatus from, ConsentStatus to, boolean permitted) {
        if (permitted) {
            assertThatCode(() -> lifecycle.requireTransition(from, to)).doesNotThrowAnyException();
            return;
        }
        assertThatThrownBy(() -> lifecycle.requireTransition(from, to))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }

    @Test
    @DisplayName("every status states its transitions, so a new one cannot slip through untested")
    void everyStatusStatesItsTransitions() {
        assertThat(PERMITTED.keySet()).isEqualTo(EnumSet.allOf(ConsentStatus.class));
    }

    @ParameterizedTest(name = "{0} creatable: expected")
    @EnumSource(ConsentStatus.class)
    @DisplayName("only the three decision statuses may be created")
    void onlyTheThreeDecisionStatusesMayBeCreated(ConsentStatus target) {
        if (CREATABLE.contains(target)) {
            assertThatCode(() -> lifecycle.requireCreatable(target)).doesNotThrowAnyException();
            return;
        }
        assertThatThrownBy(() -> lifecycle.requireCreatable(target))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(target.name());
    }

    static Stream<Arguments> everyPair() {
        return Stream.of(ConsentStatus.values())
                .flatMap(
                        from ->
                                Stream.of(ConsentStatus.values())
                                        .map(to -> Arguments.of(from, to, expected(from, to))));
    }

    private static boolean expected(ConsentStatus from, ConsentStatus to) {
        return PERMITTED.getOrDefault(from, Set.of()).contains(to);
    }
}
