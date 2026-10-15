package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.generated.model.AvailableContract;
import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.service.ContractSide;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps {@code AvailableContract}'s published enums naming the same constants as the types {@code
 * ContractDiscoveryController} maps them from by name, so a drift fails the build rather than a
 * discovery request.
 */
@DisplayName("Available contract enum parity")
class AvailableContractEnumParityTest {

    static Stream<Arguments> bridgedEnums() {
        return Stream.of(
                Arguments.of(
                        "shape",
                        ContractResponse.Shape.values(),
                        AvailableContract.ShapeEnum.values()),
                Arguments.of("roles", ContractSide.values(), AvailableContract.RolesEnum.values()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bridgedEnums")
    @DisplayName("the published enum names exactly the constants it is mapped from")
    void thePublishedEnumNamesExactlyTheSourceConstants(
            String property, Enum<?>[] source, Enum<?>[] published) {
        assertThat(Arrays.stream(published).map(Enum::name))
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(source).map(Enum::name).toList());
    }
}
