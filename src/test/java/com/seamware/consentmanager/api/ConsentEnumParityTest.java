package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.seamware.consentmanager.api.generated.model.Consent.StatusEnum;
import com.seamware.consentmanager.api.generated.model.GrantConsent.DecisionEnum;
import com.seamware.consentmanager.domain.ConsentStatus;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps the published consent enums and {@link ConsentStatus} naming the same states.
 *
 * <p>{@code ConsentMapper} bridges the two by string in both directions, which the compiler cannot
 * check: a status added to the domain but not to {@code Consent.yaml} would throw out of {@code
 * StatusEnum.fromValue} while rendering a stored consent — a 500 on the read path, long after the
 * commit that caused it. The drift costs a build here instead.
 */
@DisplayName("Consent enum parity")
class ConsentEnumParityTest {

    /** The two states a subject's decision may create; every other state is a lifecycle move. */
    private static final Set<ConsentStatus> DECISIONS =
            Set.of(ConsentStatus.GRANTED, ConsentStatus.REFUSED);

    @Test
    @DisplayName("the served status enum names exactly the states the domain holds")
    void theServedStatusEnumNamesExactlyTheDomainStates() {
        assertThat(Arrays.stream(StatusEnum.values()).map(StatusEnum::getValue))
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(ConsentStatus.values()).map(Enum::name).toList());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConsentStatus.class)
    @DisplayName("every stored status renders rather than throwing")
    void everyStoredStatusRenders(ConsentStatus status) {
        assertThatCode(() -> StatusEnum.fromValue(status.name())).doesNotThrowAnyException();
    }

    static Stream<DecisionEnum> decisions() {
        return Arrays.stream(DecisionEnum.values());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decisions")
    @DisplayName("every accepted decision is a status a subject may decide")
    void everyAcceptedDecisionIsADecidableStatus(DecisionEnum decision) {
        assertThat(ConsentStatus.valueOf(decision.getValue()))
                .as("widening the request enum must not let a lifecycle-only state be requested")
                .isIn(DECISIONS);
    }
}
