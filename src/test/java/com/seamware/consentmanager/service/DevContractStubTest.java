package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.client.dto.ContractResponse.Shape;
import com.seamware.consentmanager.support.ContractFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Holds the compose stack's WireMock contracts ({@code docker/wiremock}) to documents this service
 * decodes and derives a provider and a consumer from, so the local stub cannot rot unnoticed.
 */
@DisplayName("Local Contract Service stub")
class DevContractStubTest {

    /** Response bodies the WireMock mappings serve. */
    private static final Path STUB_FILES = Path.of("docker", "wiremock", "__files");

    /** The local Keycloak realm's participant, named as provider by every stubbed contract. */
    private static final String PROVIDER = "did:example:participant-1";

    /** Named as consumer by every stubbed contract. */
    private static final String CONSUMER = "did:example:participant-2";

    private final ContractPartyResolver resolver = new ContractPartyResolver();

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "ecosystem-contract.json, 66d0e2f7a1b2c3d4e5f60001, ECOSYSTEM",
        "bilateral-contract.json, 66d0e2f7a1b2c3d4e5f60002, BILATERAL"
    })
    @DisplayName("a stubbed contract decodes and names one provider and one consumer")
    void stubbedContractNamesItsParties(String file, String contractId, Shape shape)
            throws IOException {
        ContractResponse contract =
                ContractFixtures.decode(
                        Files.readString(STUB_FILES.resolve(file), StandardCharsets.UTF_8));

        assertThat(contract.contractId()).isEqualTo(contractId);
        assertThat(contract.shape()).isEqualTo(shape);
        assertThat(resolver.resolve(contract)).isEqualTo(new ContractParties(PROVIDER, CONSUMER));
    }
}
