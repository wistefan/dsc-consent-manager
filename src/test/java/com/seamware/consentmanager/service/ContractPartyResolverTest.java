package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.support.ContractFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins which contracts name their own two parties and which only look as if they do.
 *
 * <p>The refusals matter as much as the derivations: an ecosystem contract names N members, so
 * every case that resolves has to show what evidence narrowed each side to one party, and every
 * case that does not has to show the caller how many were found.
 */
@DisplayName("Contract party resolution")
class ContractPartyResolverTest {

    /** Provider every fixture in this suite names. */
    private static final String PROVIDER = "did:web:provider.example.org";

    /** Consumer every fixture that resolves names. */
    private static final String CONSUMER = "did:web:consumer.example.org";

    /** A participant no fixture names. */
    private static final String STRANGER = "did:web:stranger.example.org";

    /** The member {@link ContractFixtures#ECOSYSTEM_NO_PURPOSE} records as revoked. */
    private static final String DEPARTED = "did:web:departed.example.org";

    private final ContractPartyResolver resolver = new ContractPartyResolver();

    /** Contracts that narrow each side to exactly one party, and what narrowed it. */
    static List<Arguments> resolvingContracts() {
        return List.of(
                Arguments.of(
                        "a bilateral contract states both parties outright",
                        ContractFixtures.BILATERAL),
                Arguments.of(
                        "a bilateral contract naming its parties and nothing else still states"
                                + " them",
                        ContractFixtures.BILATERAL_MINIMAL),
                Arguments.of(
                        "an ecosystem contract recording a role per member is read off the roles",
                        ContractFixtures.ECOSYSTEM),
                Arguments.of(
                        "a revoked member is on neither side, so the two that remain resolve",
                        ContractFixtures.ECOSYSTEM_NO_PURPOSE),
                Arguments.of(
                        "with no roles recorded, the member offering the service is the provider",
                        ContractFixtures.ECOSYSTEM_OFFERING_EVIDENCE));
    }

    /** Contracts that leave a side open, and the count the refusal has to report. */
    static List<Arguments> ambiguousContracts() {
        return List.of(
                Arguments.of(
                        "a bilateral contract missing its consumer names no second party",
                        ContractFixtures.BILATERAL_NO_CONSUMER,
                        "names no data consumer"),
                Arguments.of(
                        "two members offering a service put two on the providing side",
                        ContractFixtures.ECOSYSTEM_TWO_OFFERINGS,
                        "names 2 participants on its data provider side"),
                Arguments.of(
                        "a third member with no role joins the consuming side",
                        ContractFixtures.ECOSYSTEM_THREE_MEMBERS,
                        "names 2 participants on its data consumer side"),
                Arguments.of(
                        "a sole member leaves the consuming side empty",
                        ContractFixtures.ECOSYSTEM_UNTITLED,
                        "names 0 participants on its data consumer side"),
                Arguments.of(
                        "neither roles nor offerings evidence any side at all",
                        ContractFixtures.ECOSYSTEM_ROLELESS,
                        "names 0 participants on its data provider side"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resolvingContracts")
    @DisplayName("a contract that names its own two parties yields them")
    void resolvesTheTwoParties(String description, String fixture) {
        ContractParties parties = resolver.resolve(ContractFixtures.contract(fixture));

        assertThat(parties.dataProvider()).as(description).isEqualTo(PROVIDER);
        assertThat(parties.dataConsumer()).as(description).isEqualTo(CONSUMER);
    }

    /**
     * The invariant that lets both generation operations run one binding check: whatever this
     * derives, the check a declared pair faces has to accept.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("resolvingContracts")
    @DisplayName("a derived pair passes the check a declared pair faces")
    void derivedPartiesAreBound(String description, String fixture) {
        ContractResponse contract = ContractFixtures.contract(fixture);
        ContractParties parties = resolver.resolve(contract);

        assertThatCode(
                        () ->
                                resolver.requireBound(
                                        contract, parties.dataProvider(), parties.dataConsumer()))
                .as(description)
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ambiguousContracts")
    @DisplayName("a contract that does not narrow a side to one party is refused, not guessed at")
    void refusesAnAmbiguousContract(String description, String fixture, String reported) {
        assertThatThrownBy(() -> resolver.resolve(ContractFixtures.contract(fixture)))
                .as(description)
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(reported)
                .hasMessageContaining("POST /privacy-notices");
    }

    /** Whether a fixture still binds a participant on a side, and the evidence that decides it. */
    static List<Arguments> bindings() {
        return List.of(
                Arguments.of(
                        "bilateral provider",
                        ContractFixtures.BILATERAL,
                        PROVIDER,
                        ContractSide.PROVIDER,
                        true),
                Arguments.of(
                        "bilateral consumer",
                        ContractFixtures.BILATERAL,
                        CONSUMER,
                        ContractSide.CONSUMER,
                        true),
                Arguments.of(
                        "bilateral provider on the consuming side",
                        ContractFixtures.BILATERAL,
                        PROVIDER,
                        ContractSide.CONSUMER,
                        false),
                Arguments.of(
                        "a party the bilateral no longer names",
                        ContractFixtures.BILATERAL,
                        STRANGER,
                        ContractSide.CONSUMER,
                        false),
                Arguments.of(
                        "ecosystem member in its recorded role",
                        ContractFixtures.ECOSYSTEM,
                        CONSUMER,
                        ContractSide.CONSUMER,
                        true),
                Arguments.of(
                        "ecosystem member against its recorded role",
                        ContractFixtures.ECOSYSTEM,
                        CONSUMER,
                        ContractSide.PROVIDER,
                        false),
                Arguments.of(
                        "a participant the ecosystem does not name",
                        ContractFixtures.ECOSYSTEM,
                        STRANGER,
                        ContractSide.PROVIDER,
                        false),
                Arguments.of(
                        "a revoked ecosystem member",
                        ContractFixtures.ECOSYSTEM_NO_PURPOSE,
                        DEPARTED,
                        ContractSide.CONSUMER,
                        false),
                Arguments.of(
                        "a roleless member offering a service, as consumer",
                        ContractFixtures.ECOSYSTEM_OFFERING_EVIDENCE,
                        PROVIDER,
                        ContractSide.CONSUMER,
                        false),
                Arguments.of(
                        "a roleless member offering a service, as provider",
                        ContractFixtures.ECOSYSTEM_OFFERING_EVIDENCE,
                        PROVIDER,
                        ContractSide.PROVIDER,
                        true),
                Arguments.of(
                        "no evidence of direction leaves either side standing",
                        ContractFixtures.ECOSYSTEM_ROLELESS,
                        CONSUMER,
                        ContractSide.PROVIDER,
                        true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindings")
    @DisplayName("a contract binds a participant on a side only while its document says so")
    void bindsOnlyWhatTheDocumentConfirms(
            String description,
            String fixture,
            String participant,
            ContractSide side,
            boolean bound) {
        assertThat(resolver.binds(ContractFixtures.contract(fixture), participant, side))
                .as(description)
                .isEqualTo(bound);
    }

    /** The per-side check and the pair check read one body of evidence, so they never disagree. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("resolvingContracts")
    @DisplayName("a derived pair is bound on each side by the per-side check too")
    void derivedPartiesBindTheirSides(String description, String fixture) {
        ContractResponse contract = ContractFixtures.contract(fixture);
        ContractParties parties = resolver.resolve(contract);

        assertThat(resolver.binds(contract, parties.dataProvider(), ContractSide.PROVIDER))
                .as(description)
                .isTrue();
        assertThat(resolver.binds(contract, parties.dataConsumer(), ContractSide.CONSUMER))
                .as(description)
                .isTrue();
    }
}
