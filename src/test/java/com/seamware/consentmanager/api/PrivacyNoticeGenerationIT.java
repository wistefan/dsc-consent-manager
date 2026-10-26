package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.PrivacyNoticeApiTestSupport;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Notice generation against a stubbed Contract Service: guards the two success codes and the cache
 * staleness window, which an edit must not bypass. One contract per case, as the cache is shared.
 */
@MicronautTest(transactional = false)
@DisplayName("Privacy notice generation over HTTP")
class PrivacyNoticeGenerationIT extends PrivacyNoticeApiTestSupport {

    private static final String PATH = "/privacy-notices";

    /** Status standing in for an outage that outlasts the retry budget. */
    private static final int UPSTREAM_FAILURE = 503;

    @Test
    @DisplayName("a contract between two registered parties yields a stored notice")
    void generatesANoticeFromTheContract() {
        String contractUri = serveEcosystem("generate");

        HttpResponse<String> response = generate(contractUri, CALLER, CONSUMER);

        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
        PrivacyNotice stored = activeNotice(contractUri);
        assertThat(stored.getTitle())
                .as("the title is derived from the contract's subject")
                .isEqualTo("https://catalog.example.org/v1/catalog/ecosystems/mobility-data-space");
        assertThat(stored.getPayload().purposes())
                .containsExactly("Produce aggregated mobility insights for the city of Example.");
        assertThat(stored.getPayload().recipients())
                .extracting(PrivacyNoticePayload.Recipient::identifier)
                .contains(CALLER, CONSUMER);
        assertThat(stored.getPayload().controllerDetails().identifier())
                .as("the controller is the provider as this service has it registered")
                .isEqualTo(CALLER);

        Map<String, Object> body = body(response);
        assertThat(body.get("id")).isEqualTo(stored.getId().toString());
        assertThat(body.get("dataProvider")).isEqualTo(CALLER);
        assertThat(body.get("dataConsumer")).isEqualTo(CONSUMER);
        assertThat(body).doesNotContainKey("archivedAt");
        assertThat(resolution(body).values())
                .as("generation dereferences nothing, so every section is NOT_REQUESTED")
                .containsOnly("NOT_REQUESTED");
    }

    @Test
    @DisplayName("a bilateral contract yields a notice just as an ecosystem one does")
    void generatesANoticeFromABilateralContract() {
        String contractUri = serveBilateral("bilateral");

        HttpResponse<String> response = generate(contractUri, CALLER, CONSUMER);

        assertThat(response.code())
                .as("the ecosystem endpoint does not hold it, so the gateway falls through")
                .isEqualTo(HttpStatus.CREATED.getCode());
        PrivacyNotice stored = activeNotice(contractUri);
        assertThat(stored.getTitle())
                .as("a bilateral contract's subject is the service offering it is about")
                .isEqualTo(
                        "https://catalog.example.org/v1/catalog/serviceofferings/charging-sessions");
        assertThat(stored.getPayload().recipients())
                .extracting(PrivacyNoticePayload.Recipient::identifier)
                .containsExactly(CALLER, CONSUMER);
        assertThat(stored.getPayload().piiPrincipalRights())
                .as("only the bilateral shape states terms, and they fill this heading alone")
                .startsWith("The PII principal may request access to");
        assertThat(stored.getPayload().withdrawalOfConsent()).isNull();
    }

    @Test
    @DisplayName("a consumer a bilateral contract does not bind is refused")
    void refusesAPartyTheBilateralContractDoesNotBind() {
        String contractUri = serveBilateral("bilateral-unbound");

        HttpResponse<String> response = generate(contractUri, CALLER, OTHER_CONSUMER);

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(allNotices(contractUri)).isEmpty();
    }

    @Test
    @DisplayName("one participant may not be named on both sides")
    void refusesAParticipantNamedAsBothParties() {
        String contractUri = serveEcosystem("same-party");

        HttpResponse<String> response = generate(contractUri, CALLER, CALLER);

        assertThat(response.code())
                .as(
                        "an ecosystem contract binds its provider as a member too, so only this"
                                + " refuses a notice saying a participant consumes its own data")
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(allNotices(contractUri)).isEmpty();
    }

    @Test
    @DisplayName("a base64-encoded identifier is not an alias for the identifier it encodes")
    void doesNotAcceptABase64EncodedIdentifier() {
        String contractUri = serveEcosystem("base64");
        String encoded =
                Base64.getEncoder().encodeToString(CONSUMER.getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = generate(contractUri, CALLER, encoded);

        assertThat(response.code())
                .as("nothing on this API is base64-encoded, so an encoded value names nobody")
                .isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(activeNotices(contractUri)).isEmpty();
    }

    @Test
    @DisplayName("a caller that is neither party may not generate the notice")
    void refusesACallerThatIsNeitherParty() {
        String contractUri =
                serveBody("foreign", ContractFixtures.body(ContractFixtures.ECOSYSTEM));

        HttpResponse<String> response = generate(contractUri, FIXTURE_PROVIDER, CONSUMER);

        assertThat(response.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(activeNotices(contractUri)).isEmpty();
    }

    @ParameterizedTest(name = "an unregistered {0} is a 404")
    @CsvSource({"data provider, true", "data consumer, false"})
    @DisplayName("a party no participant is registered under is a 404")
    void refusesAnUnregisteredParty(String role, boolean providerSide) {
        String contractUri = serveEcosystem("unregistered-" + providerSide);

        HttpResponse<String> response =
                providerSide
                        ? generate(contractUri, UNREGISTERED, CONSUMER)
                        : generate(contractUri, CALLER, UNREGISTERED);

        assertThat(response.code()).as(role).isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(activeNotices(contractUri)).isEmpty();
    }

    @Test
    @DisplayName("a deregistered caller may not generate a notice in its organization's name")
    void refusesADeregisteredCaller() {
        String contractUri = serveEcosystem("deregistered");
        deregister(CALLER);
        try {
            HttpResponse<String> response = generate(contractUri, CALLER, CONSUMER);

            assertThat(response.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
            assertThat(activeNotices(contractUri)).isEmpty();
        } finally {
            reactivate(CALLER);
        }
    }

    /** Covers the consumer side only; {@code PrivacyNoticeServiceTest} covers both. */
    @Test
    @DisplayName("a deregistered party is a 404, so no new notice republishes its retained record")
    void refusesADeregisteredParty() {
        String contractUri = serveEcosystem("deregistered-consumer");
        deregister(CONSUMER);
        try {
            HttpResponse<String> response = generate(contractUri, CALLER, CONSUMER);

            assertThat(response.code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
            assertThat(activeNotices(contractUri)).isEmpty();
        } finally {
            reactivate(CONSUMER);
        }
    }

    @Test
    @DisplayName("regenerating from an unchanged contract returns the notice already held")
    void regenerationOfAnUnchangedContractWritesNothing() {
        String contractUri = serveEcosystem("unchanged");

        HttpResponse<String> first = generate(contractUri, CALLER, CONSUMER);
        HttpResponse<String> second = generate(contractUri, CALLER, CONSUMER);

        assertThat(first.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(second.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(second).get("id")).isEqualTo(body(first).get("id"));
        assertThat(allNotices(contractUri))
                .as("a regeneration that changes nothing must not leave a second row behind")
                .hasSize(1);
    }

    @Test
    @DisplayName("a contract edit that leaves the notice identical archives nothing")
    void aPayloadNeutralEditIsNotASupersession() {
        String contractId = "neutral";
        String contractUri = serveEcosystem(contractId);

        HttpResponse<String> first = generate(contractUri, CALLER, CONSUMER);
        serveBody(
                contractId,
                ecosystem().replace("\"status\": \"signed\"", "\"status\": \"revoked\""));
        invalidate(contractUri);
        HttpResponse<String> second = generate(contractUri, CALLER, CONSUMER);

        assertThat(second.code())
                .as("an upstream edit no heading of the notice reflects must not invalidate it")
                .isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(second).get("id")).isEqualTo(body(first).get("id"));
        assertThat(allNotices(contractUri)).hasSize(1);
    }

    @Test
    @DisplayName("a changed contract archives the notice it supersedes")
    void aChangedContractSupersedesTheActiveNotice() {
        String contractId = "changed";
        String contractUri = serveEcosystem(contractId);

        HttpResponse<String> first = generate(contractUri, CALLER, CONSUMER);
        String changed =
                ecosystem()
                        .replace(
                                "Produce aggregated mobility insights for the city of Example.",
                                "Produce aggregated mobility insights for the region of Example.");
        serveBody(contractId, changed);

        HttpResponse<String> stale = generate(contractUri, CALLER, CONSUMER);
        assertThat(stale.code())
                .as("inside the cache lifetime the edit is not visible here yet, by design")
                .isEqualTo(HttpStatus.OK.getCode());
        assertThat(allNotices(contractUri)).hasSize(1);

        invalidate(contractUri);
        HttpResponse<String> second = generate(contractUri, CALLER, CONSUMER);

        assertThat(second.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(body(second).get("id")).isNotEqualTo(body(first).get("id"));
        PrivacyNotice superseded = notice(UUID.fromString((String) body(first).get("id")));
        assertThat(superseded.getArchivedAt())
                .as("the notice consents were given against is kept, archived")
                .isNotNull();
        assertThat(activeNotices(contractUri)).hasSize(1);
    }

    @Test
    @DisplayName("an unreachable Contract Service is the one fatal outage")
    void anUnreachableContractServiceIsABadGateway() {
        String contractId = "unreachable";
        String contractUri = uri(contractId);
        contracts.serveStatus(contractId, UPSTREAM_FAILURE);

        HttpResponse<String> response = generate(contractUri, CALLER, CONSUMER);

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_GATEWAY.getCode());
        assertThat(response.getContentType())
                .hasValueSatisfying(type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
        assertThat(activeNotices(contractUri)).isEmpty();
    }

    @Test
    @DisplayName("a contract the upstream does not hold is a 404, not a bad gateway")
    void anUnknownContractIsNotFound() {
        String contractId = "unknown";
        String contractUri = uri(contractId);
        contracts.serveUnknown(contractId);

        HttpResponse<String> response = generate(contractUri, CALLER, CONSUMER);

        assertThat(response.code())
                .as("a definite 'no such contract' is an answer, not an outage")
                .isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(activeNotices(contractUri)).isEmpty();
    }

    @Test
    @DisplayName("a consumer the contract does not bind is refused")
    void refusesAPartyTheContractDoesNotBind() {
        String contractUri = serveEcosystem("unbound");

        HttpResponse<String> response = generate(contractUri, CALLER, OTHER_CONSUMER);

        assertThat(response.code())
                .as("a published notice must not misname a party the contract never bound")
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(allNotices(contractUri)).isEmpty();
    }

    @Test
    @DisplayName("one provider's second consumer supersedes the first, the index holding one slot")
    void aSecondConsumerSupersedesTheFirst() {
        String contractId = "two-consumers";
        String contractUri = serveBody(contractId, twoConsumerEcosystem());

        HttpResponse<String> first = generate(contractUri, CALLER, CONSUMER);
        HttpResponse<String> second = generate(contractUri, CALLER, OTHER_CONSUMER);

        assertThat(first.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(second.code())
                .as("uq_privacy_notices_active_contract is keyed on contract and provider only")
                .isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(notice(UUID.fromString((String) body(first).get("id"))).getArchivedAt())
                .isNotNull();
        assertThat(activeNotices(contractUri))
                .singleElement()
                .satisfies(
                        active ->
                                assertThat(active.getConsumerId())
                                        .isEqualTo(participant(OTHER_CONSUMER).getId()));
    }

    /** Issues a generation request with both parties declared. */
    private HttpResponse<String> generate(String contractUri, String provider, String consumer) {
        return post(
                PATH,
                Map.of(
                        "contractUri", contractUri,
                        "dataProvider", provider,
                        "dataConsumer", consumer));
    }
}
