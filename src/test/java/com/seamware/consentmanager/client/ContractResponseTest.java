package com.seamware.consentmanager.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.support.ContractFixtures;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins what the one contract model makes of the two documents the Contract Service serves.
 *
 * <p>Identity and shape are asserted on every document because everything downstream branches on
 * them: the shape decides which extraction rules apply, and the identifier is what a notice is
 * traced back to.
 */
@DisplayName("Contract deserialization")
class ContractResponseTest {

    /** The served documents, named by the constants so a renamed file breaks the compile. */
    static Stream<Arguments> servedDocuments() {
        return Stream.of(
                Arguments.of(
                        ContractFixtures.ECOSYSTEM,
                        "66d0e2f7a1b2c3d4e5f60001",
                        ContractResponse.Shape.ECOSYSTEM),
                Arguments.of(
                        ContractFixtures.BILATERAL,
                        "66d0e2f7a1b2c3d4e5f60002",
                        ContractResponse.Shape.BILATERAL),
                Arguments.of(
                        ContractFixtures.ECOSYSTEM_MONGO_ID,
                        "66d0e2f7a1b2c3d4e5f60003",
                        ContractResponse.Shape.ECOSYSTEM),
                Arguments.of(
                        ContractFixtures.BILATERAL_UNKNOWN_PROPERTY,
                        "66d0e2f7a1b2c3d4e5f60004",
                        ContractResponse.Shape.BILATERAL));
    }

    @ParameterizedTest(name = "{0} is {2} contract {1}")
    @MethodSource("servedDocuments")
    @DisplayName("every served document decodes to an identified, shaped contract")
    void decodesIdentityAndShape(String fixture, String expectedId, ContractResponse.Shape shape) {
        ContractResponse contract = ContractFixtures.contract(fixture);

        assertThat(contract.contractId())
                .as("the identifier is read from whichever of uid and _id the document carries")
                .isEqualTo(expectedId);
        assertThat(contract.shape())
                .as("the shape is read off the document, not off the endpoint that served it")
                .isEqualTo(shape);
    }

    @Test
    @DisplayName("an unknown top-level property does not fail the fetch")
    void toleratesAnUnknownProperty() {
        ContractResponse contract =
                ContractFixtures.contract(ContractFixtures.BILATERAL_UNKNOWN_PROPERTY);

        assertThat(contract.dataConsumer())
                .as("an upstream addition leaves the members this service does read intact")
                .isEqualTo("did:web:consumer.example.org");
    }

    /** The two ways the upstream declares a purpose's PII categories, and what each must yield. */
    static Stream<Arguments> piiCategoryForms() {
        return Stream.of(
                Arguments.of(ContractFixtures.ECOSYSTEM, List.of("Location data")),
                Arguments.of(
                        ContractFixtures.BILATERAL,
                        List.of("Energy consumption data", "Charging session data")));
    }

    @ParameterizedTest(name = "{0} yields {1}")
    @MethodSource("piiCategoryForms")
    @DisplayName("piiCategory decodes whether the document declares a string or an array")
    void decodesEitherPiiCategoryForm(String fixture, List<String> expected) {
        ContractResponse contract = ContractFixtures.contract(fixture);

        assertThat(contract.purpose().getFirst().piiCategories())
                .as("a shape this service cannot decode would 502 a well-formed contract")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("an ecosystem contract keeps its offerings, policies, purposes and members")
    void decodesTheEcosystemMembers() {
        ContractResponse contract = ContractFixtures.contract(ContractFixtures.ECOSYSTEM);

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
    void decodesTheBilateralMembers() {
        ContractResponse contract = ContractFixtures.contract(ContractFixtures.BILATERAL);

        assertThat(contract.dataProvider()).isEqualTo("did:web:provider.example.org");
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
        assertThat(contract.signatures().getFirst().party())
                .as("a document may also carry a party's catalog record, which is not its identity")
                .isEqualTo("https://catalog.example.org/v1/catalog/participants/provider");
    }
}
