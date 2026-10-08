package com.seamware.consentmanager.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.support.ContractFixtures;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins what the one contract model makes of the two documents the Contract Service serves.
 *
 * <p>Identity and shape are asserted on every document because everything downstream branches on
 * them: the shape decides which extraction rules apply, and the identifier is what a notice is
 * traced back to.
 */
@DisplayName("Contract deserialization")
class ContractResponseTest {

    private static ApplicationContext context;

    private static ObjectMapper json;

    @BeforeAll
    static void startContext() {
        // No datasource and no Flyway: this suite decodes documents and nothing else, and a
        // database would make a unit test wait on Testcontainers.
        context =
                ApplicationContext.builder(Environment.TEST)
                        .properties(
                                Map.of(
                                        "datasources.default.enabled", false,
                                        "flyway.enabled", false))
                        .start();
        json = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stopContext() {
        context.close();
    }

    @ParameterizedTest(name = "{0} is {2} contract {1}")
    @CsvSource({
        "ecosystem-contract.json, 66d0e2f7a1b2c3d4e5f60001, ECOSYSTEM",
        "bilateral-contract.json, 66d0e2f7a1b2c3d4e5f60002, BILATERAL",
        "ecosystem-contract-mongo-id.json, 66d0e2f7a1b2c3d4e5f60003, ECOSYSTEM",
        "bilateral-contract-unknown-property.json, 66d0e2f7a1b2c3d4e5f60004, BILATERAL"
    })
    @DisplayName("every served document decodes to an identified, shaped contract")
    void decodesIdentityAndShape(String fixture, String expectedId, ContractResponse.Shape shape)
            throws IOException {
        ContractResponse contract = decode(fixture);

        assertThat(contract.contractId())
                .as("the identifier is read from whichever of uid and _id the document carries")
                .isEqualTo(expectedId);
        assertThat(contract.shape())
                .as("the shape is read off the document, not off the endpoint that served it")
                .isEqualTo(shape);
    }

    @Test
    @DisplayName("an unknown top-level property does not fail the fetch")
    void toleratesAnUnknownProperty() throws IOException {
        ContractResponse contract = decode(ContractFixtures.BILATERAL_UNKNOWN_PROPERTY);

        assertThat(contract.dataConsumer())
                .as("an upstream addition leaves the members this service does read intact")
                .isEqualTo("https://catalog.example.org/v1/catalog/participants/consumer");
    }

    @Test
    @DisplayName("an ecosystem contract keeps its offerings, policies, purposes and members")
    void decodesTheEcosystemMembers() throws IOException {
        ContractResponse contract = decode(ContractFixtures.ECOSYSTEM);

        assertThat(contract.serviceOfferings()).hasSize(1);
        assertThat(contract.serviceOfferings().getFirst().policies())
                .extracting(ContractResponse.Policy::ruleId)
                .as("the policies of an offering survive the nesting")
                .containsExactly("rule-access", "rule-count");
        assertThat(contract.purpose())
                .extracting(ContractResponse.Purpose::purposeCategory)
                .containsExactly("Research and statistics");
        assertThat(contract.members())
                .extracting(ContractResponse.Member::role)
                .as("both parties of an ecosystem contract are named through its members")
                .containsExactly("Data provider", "Data consumer");
        assertThat(contract.termsAndConditions())
                .as("an ecosystem contract carries no terms and conditions")
                .isNull();
    }

    @Test
    @DisplayName("a bilateral contract keeps its parties, terms and validity")
    void decodesTheBilateralMembers() throws IOException {
        ContractResponse contract = decode(ContractFixtures.BILATERAL);

        assertThat(contract.dataProvider())
                .isEqualTo("https://catalog.example.org/v1/catalog/participants/provider");
        assertThat(contract.policy())
                .extracting(ContractResponse.Policy::ruleId)
                .containsExactly("rule-access");
        assertThat(contract.purpose().getFirst().thirdPartyName())
                .isEqualTo("Example Grid Operator");
        assertThat(contract.terminationAndValidity().terminationPeriod()).isEqualTo("P30D");
        assertThat(contract.termsAndConditions())
                .as("the only free text a bilateral contract states the principal's rights in")
                .contains("rectification");
        assertThat(contract.members())
                .as("a bilateral contract names its parties directly, never through members")
                .isNull();
    }

    private static ContractResponse decode(String fixture) throws IOException {
        return json.readValue(ContractFixtures.body(fixture), ContractResponse.class);
    }
}
