package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seamware.consentmanager.client.ContractServiceClient;
import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.client.dto.ContractResponse.Shape;
import com.seamware.consentmanager.support.ContractFixtures;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Get;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the compose stack's WireMock stub ({@code docker/wiremock}) to the routes {@link
 * ContractServiceClient} calls and to documents this service decodes into a provider and a
 * consumer.
 */
@DisplayName("Local Contract Service stub")
class DevContractStubTest {

    /** The stub's root, as compose mounts it. */
    private static final Path STUB_ROOT = Path.of("docker", "wiremock");

    /** Request matchers; each contract's mapping shares its body file's name. */
    private static final Path MAPPINGS = STUB_ROOT.resolve("mappings");

    /** Response bodies the mappings serve. */
    private static final Path STUB_FILES = STUB_ROOT.resolve("__files");

    /** The mapping answering every contract the stub does not know. */
    private static final String UNKNOWN_CONTRACT_MAPPING = "unknown-contract.json";

    /** The route parameter in the client's {@code @Get} templates. */
    private static final String CONTRACT_ID_PARAMETER = "{contractId}";

    /** A contract id no mapping names. */
    private static final String UNKNOWN_CONTRACT_ID = "000000000000000000000000";

    /** WireMock's priority for a mapping that declares none; a lower number wins. */
    private static final int WIREMOCK_DEFAULT_PRIORITY = 5;

    /** The local Keycloak realm's {@code test-participant}, provider in every stubbed contract. */
    private static final String PROVIDER = "did:example:participant-1";

    /** The local Keycloak realm's {@code test-participant-2}, consumer in every stub. */
    private static final String CONSUMER = "did:example:participant-2";

    private static final ObjectMapper JSON = new ObjectMapper();

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

    @ParameterizedTest(name = "{0} answers {1}")
    @CsvSource({
        "ecosystem-contract.json, fetchContract, 66d0e2f7a1b2c3d4e5f60001",
        "bilateral-contract.json, fetchBilateralContract, 66d0e2f7a1b2c3d4e5f60002"
    })
    @DisplayName("a contract mapping serves its body on the client's route, ahead of the 404")
    void contractMappingMatchesTheClientRoute(String file, String clientMethod, String contractId)
            throws Exception {
        JsonNode mapping = mapping(file);

        assertThat(mapping.at("/request/method").asText()).isEqualTo(HttpMethod.GET.name());
        assertThat(mapping.at("/request/url").asText())
                .isEqualTo(route(clientMethod).replace(CONTRACT_ID_PARAMETER, contractId));
        assertThat(mapping.at("/response/bodyFileName").asText()).isEqualTo(file);
        assertThat(priority(mapping)).isLessThan(priority(mapping(UNKNOWN_CONTRACT_MAPPING)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fetchContract", "fetchBilateralContract"})
    @DisplayName(
            "an unknown contract answers 404 on either client route, so the gateway falls back")
    void unknownContractAnswersNotFound(String clientMethod) throws Exception {
        JsonNode mapping = mapping(UNKNOWN_CONTRACT_MAPPING);
        Pattern pattern = Pattern.compile(mapping.at("/request/urlPathPattern").asText());

        assertThat(route(clientMethod).replace(CONTRACT_ID_PARAMETER, UNKNOWN_CONTRACT_ID))
                .matches(pattern);
        assertThat(mapping.at("/response/status").asInt())
                .isEqualTo(HttpStatus.NOT_FOUND.getCode());
    }

    private static JsonNode mapping(String file) throws IOException {
        return JSON.readTree(MAPPINGS.resolve(file).toFile());
    }

    private static int priority(JsonNode mapping) {
        return mapping.path("priority").asInt(WIREMOCK_DEFAULT_PRIORITY);
    }

    /** The route template the client declares for {@code clientMethod}. */
    private static String route(String clientMethod) throws NoSuchMethodException {
        return ContractServiceClient.class
                .getMethod(clientMethod, String.class)
                .getAnnotation(Get.class)
                .value();
    }
}
