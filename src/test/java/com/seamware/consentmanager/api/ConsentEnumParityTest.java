package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.seamware.consentmanager.api.generated.model.Consent;
import com.seamware.consentmanager.api.generated.model.ConsentResponse;
import com.seamware.consentmanager.api.generated.model.ConsentValidity;
import com.seamware.consentmanager.api.generated.model.GrantConsent.DecisionEnum;
import com.seamware.consentmanager.domain.ConsentStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps every published consent enum and {@link ConsentStatus} naming the same states.
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

    /** Every generated status enum a response renders a stored {@link ConsentStatus} into. */
    static Stream<Arguments> servedStatusEnums() {
        return Stream.of(
                served("Consent", Consent.StatusEnum.values(), Consent.StatusEnum::fromValue),
                served(
                        "ConsentResponse",
                        ConsentResponse.StatusEnum.values(),
                        ConsentResponse.StatusEnum::fromValue),
                served(
                        "ConsentValidity",
                        ConsentValidity.StatusEnum.values(),
                        ConsentValidity.StatusEnum::fromValue));
    }

    private static Arguments served(String model, Object[] values, Function<String, ?> fromValue) {
        return Arguments.of(model, Arrays.stream(values).map(String::valueOf).toList(), fromValue);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servedStatusEnums")
    @DisplayName("the served status enum names exactly the states the domain holds")
    void theServedStatusEnumNamesExactlyTheDomainStates(
            String model, List<String> values, Function<String, ?> fromValue) {
        assertThat(values)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(ConsentStatus.values()).map(Enum::name).toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servedStatusEnums")
    @DisplayName("every stored status renders rather than throwing")
    void everyStoredStatusRenders(
            String model, List<String> values, Function<String, ?> fromValue) {
        assertThat(ConsentStatus.values())
                .allSatisfy(
                        status ->
                                assertThatCode(() -> fromValue.apply(status.name()))
                                        .doesNotThrowAnyException());
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
