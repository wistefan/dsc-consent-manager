package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.PrivacyNoticeApiTestSupport;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@code POST /privacy-notices/by-contract} end to end against a stubbed Contract
 * Service.
 *
 * <p>Two things only an end-to-end run shows. That the derived form and the declared form produce
 * the same notice from the same contract - asserted on the stored rows rather than on the status,
 * because the point is that one code path serves both. And that the rules the declared form is held
 * to still hold here: a caller that is no party, a party this deployment has no registration for,
 * the duplicate that writes nothing and the contract edit that archives.
 *
 * <p>Each case uses a contract identifier of its own, the class-wide {@code contracts} cache being
 * shared.
 */
@MicronautTest(transactional = false)
@DisplayName("Privacy notice generation from a contract alone")
class PrivacyNoticeByContractIT extends PrivacyNoticeApiTestSupport {

    private static final String PATH = "/privacy-notices/by-contract";

    /** Where the same generation is reached with the two parties declared. */
    private static final String DECLARED_PATH = "/privacy-notices";

    @Test
    @DisplayName("the contract's own parties yield the notice the declared form would have")
    void derivesThePartiesAndStoresTheSameNotice() {
        String derivedUri = serveEcosystem("derived");
        String declaredUri = serveEcosystem("declared");

        HttpResponse<String> response = generate(derivedUri);
        HttpResponse<String> declared = declare(declaredUri);

        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(declared.code()).isEqualTo(HttpStatus.CREATED.getCode());
        Map<String, Object> body = body(response);
        assertThat(body.get("dataProvider"))
                .as("the provider is the member the contract has offering the service")
                .isEqualTo(CALLER);
        assertThat(body.get("dataConsumer")).isEqualTo(CONSUMER);

        PrivacyNotice derivedNotice = activeNotice(derivedUri);
        PrivacyNotice declaredNotice = activeNotice(declaredUri);
        assertThat(derivedNotice.getProviderId()).isEqualTo(declaredNotice.getProviderId());
        assertThat(derivedNotice.getConsumerId()).isEqualTo(declaredNotice.getConsumerId());
        assertThat(derivedNotice.getTitle()).isEqualTo(declaredNotice.getTitle());
        assertThat(derivedNotice.getPayload())
                .as("only the parties are arrived at differently, so the notice is the same")
                .isEqualTo(declaredNotice.getPayload());
    }

    @Test
    @DisplayName("a bilateral contract names its two parties outright and generates from them")
    void derivesThePartiesOfABilateralContract() {
        String contractUri = serveBilateral("bilateral-derived");

        HttpResponse<String> response = generate(contractUri);

        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
        Map<String, Object> body = body(response);
        assertThat(body.get("dataProvider")).isEqualTo(CALLER);
        assertThat(body.get("dataConsumer"))
                .as("the bilateral shape declares both sides, so neither is inferred")
                .isEqualTo(CONSUMER);
        assertThat(activeNotices(contractUri)).hasSize(1);
    }

    @Test
    @DisplayName("a caller that is neither derived party may not generate the notice")
    void refusesACallerThatIsNeitherParty() {
        String contractUri =
                serveBody("foreign", ContractFixtures.body(ContractFixtures.ECOSYSTEM));

        HttpResponse<String> response = generate(contractUri);

        assertThat(response.code())
                .as("deriving the parties does not make the caller one of them")
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(allNotices(contractUri)).isEmpty();
    }

    /**
     * The identifiers are read off the contract rather than off the request here, so the order of
     * the two refusals is what keeps them from leaking: a stranger must not learn a party's
     * identifier from the {@code 404} that reports it unregistered.
     */
    @Test
    @DisplayName("a caller that is neither party is refused before either party is looked up")
    void refusesANonPartyBeforeReportingAnUnregisteredParty() {
        String contractUri =
                serveBody(
                        "foreign-unregistered",
                        ContractFixtures.body(ContractFixtures.ECOSYSTEM)
                                .replace(CONSUMER, UNREGISTERED));

        HttpResponse<String> response = generate(contractUri);

        assertThat(response.code())
                .as("the 404 would name a party the caller was never entitled to learn")
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(allNotices(contractUri)).isEmpty();
    }

    @Test
    @DisplayName("a derived party this deployment has no registration for is a 404")
    void refusesAnUnregisteredDerivedParty() {
        String contractUri = serveBody("unregistered", ecosystem().replace(CONSUMER, UNREGISTERED));

        HttpResponse<String> response = generate(contractUri);

        assertThat(response.code())
                .as("the caller is the provider here, so it is a party and sees the real reason")
                .isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(allNotices(contractUri)).isEmpty();
    }

    @Test
    @DisplayName("a contract that leaves a side open is refused rather than guessed at")
    void refusesAnAmbiguousContract() {
        String contractUri = serveBody("ambiguous", twoConsumerEcosystem());

        HttpResponse<String> response = generate(contractUri);

        assertThat(response.code())
                .as("two consumers against one provider name no single pair")
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(allNotices(contractUri)).isEmpty();
    }

    @Test
    @DisplayName("regenerating from an unchanged contract returns the notice already held")
    void regenerationOfAnUnchangedContractWritesNothing() {
        String contractUri = serveEcosystem("unchanged");

        HttpResponse<String> first = generate(contractUri);
        HttpResponse<String> second = generate(contractUri);

        assertThat(first.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(second.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(second).get("id")).isEqualTo(body(first).get("id"));
        assertThat(allNotices(contractUri)).hasSize(1);
    }

    @Test
    @DisplayName("a changed contract archives the notice it supersedes here too")
    void aChangedContractSupersedesTheActiveNotice() {
        String contractId = "changed";
        String contractUri = serveEcosystem(contractId);

        HttpResponse<String> first = generate(contractUri);
        serveBody(
                contractId,
                ecosystem()
                        .replace(
                                "Produce aggregated mobility insights for the city of Example.",
                                "Produce aggregated mobility insights for the region of Example."));
        invalidate(contractUri);
        HttpResponse<String> second = generate(contractUri);

        assertThat(second.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(body(second).get("id")).isNotEqualTo(body(first).get("id"));
        assertThat(notice(UUID.fromString((String) body(first).get("id"))).getArchivedAt())
                .as("the notice consents were given against is kept, archived")
                .isNotNull();
        assertThat(activeNotices(contractUri)).hasSize(1);
    }

    /** Issues a by-contract generation with the realm's participant token. */
    private HttpResponse<String> generate(String contractUri) {
        return post(PATH, Map.of("contractUri", contractUri));
    }

    /** Issues the same generation with both parties declared, for the parity case. */
    private HttpResponse<String> declare(String contractUri) {
        return post(
                DECLARED_PATH,
                Map.of(
                        "contractUri", contractUri,
                        "dataProvider", CALLER,
                        "dataConsumer", CONSUMER));
    }
}
