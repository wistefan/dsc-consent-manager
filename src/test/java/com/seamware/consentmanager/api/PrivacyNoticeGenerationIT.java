package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.ContractServiceStub;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.cache.CacheManager;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Exercises {@code POST /privacy-notices} end to end against a stubbed Contract Service.
 *
 * <p>What only an end-to-end run can show: that the two success codes report what the operation
 * decided rather than what the route returns, and that a contract edit reaches generation through
 * the cache and not around it. The staleness window the cache opens is asserted as deliberate - the
 * same upstream edit is shown to be invisible until its key is invalidated - so a later change to
 * {@code cache-ttl} cannot quietly turn that into a surprise.
 *
 * <p>Each case uses a contract identifier of its own. The context, and with it the {@code
 * contracts} cache, is shared by the whole class, so a shared identifier would let one case serve
 * another's contract.
 */
@MicronautTest(transactional = false)
@DisplayName("Privacy notice generation over HTTP")
class PrivacyNoticeGenerationIT extends KeycloakAndPostgresTestResource {

    private static final String PATH = "/privacy-notices";

    /** Content type every refusal in this suite renders. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** Prefix every contract URI in this suite carries; the last segment is the upstream id. */
    private static final String CONTRACT_URI_PREFIX = "https://contract.example.org/v1/contracts/";

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** Provider the fixtures name, replaced by {@link #CALLER} wherever the caller is a party. */
    private static final String FIXTURE_PROVIDER = "did:web:provider.example.org";

    /** Consumer the fixtures name, registered here so the parties resolve. */
    private static final String CONSUMER = "did:web:consumer.example.org";

    /** A second consumer, for the ecosystem contract that admits two against one provider. */
    private static final String OTHER_CONSUMER = "did:web:other-consumer.example.org";

    /** Identifier no participant is registered under. */
    private static final String UNREGISTERED = "urn:test:participant:never-registered";

    /** Status standing in for an outage that outlasts the retry budget. */
    private static final int UPSTREAM_FAILURE = 503;

    /** Retry budget this suite runs under, so a 502 case costs milliseconds rather than seconds. */
    private static final String RETRY_ATTEMPTS = "1";

    private static final String RETRY_DELAY = "10ms";

    /**
     * The stubbed Contract Service. Static because {@link #getProperties()} must name its URL
     * before the context that reads it starts.
     */
    private static final ContractServiceStub CONTRACTS = new ContractServiceStub();

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject CacheManager<?> caches;

    @Inject ObjectMapper json;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put("consent-manager.contract-service.url", CONTRACTS.url());
        properties.put("consent-manager.contract-service.retry-attempts", RETRY_ATTEMPTS);
        properties.put("consent-manager.contract-service.retry-delay", RETRY_DELAY);
        return properties;
    }

    @AfterAll
    static void stopContractService() {
        CONTRACTS.close();
    }

    @BeforeEach
    void registerParties() {
        register(CALLER);
        register(FIXTURE_PROVIDER);
        register(CONSUMER);
        register(OTHER_CONSUMER);
    }

    /**
     * Rows these cases commit outlive the transaction, and a notice holds its parties against
     * {@code ON DELETE RESTRICT}, so a leftover would make every later suite's participant cleanup
     * fail.
     */
    @AfterEach
    void removeCommittedRows() {
        notices.deleteAll();
        Stream.of(CALLER, FIXTURE_PROVIDER, CONSUMER, OTHER_CONSUMER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

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
        String contractUri = CONTRACT_URI_PREFIX + contractId;
        CONTRACTS.serveStatus(contractId, UPSTREAM_FAILURE);

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
        String contractUri = CONTRACT_URI_PREFIX + contractId;
        CONTRACTS.serveUnknown(contractId);

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

    /** Issues a generation request with the realm's participant token. */
    private HttpResponse<String> generate(String contractUri, String provider, String consumer) {
        MutableHttpRequest<Map<String, String>> request =
                HttpRequest.POST(
                                PATH,
                                Map.of(
                                        "contractUri", contractUri,
                                        "dataProvider", provider,
                                        "dataConsumer", consumer))
                        .bearerAuth(KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> refusal = (HttpResponse<String>) e.getResponse();
            return refusal;
        }
    }

    /** Serves the ecosystem fixture, the caller standing in as its provider. */
    private String serveEcosystem(String contractId) {
        return serveBody(contractId, ecosystem());
    }

    private String serveBody(String contractId, String document) {
        CONTRACTS.serveEcosystemContract(contractId, document);
        return CONTRACT_URI_PREFIX + contractId;
    }

    /** Serves the bilateral fixture on the bilateral endpoint alone, the caller its provider. */
    private String serveBilateral(String contractId) {
        CONTRACTS.serveBilateralContract(
                contractId,
                ContractFixtures.body(ContractFixtures.BILATERAL)
                        .replace(FIXTURE_PROVIDER, CALLER));
        return CONTRACT_URI_PREFIX + contractId;
    }

    /** The ecosystem fixture with the caller as its provider, so the caller is a party. */
    private static String ecosystem() {
        return ContractFixtures.body(ContractFixtures.ECOSYSTEM).replace(FIXTURE_PROVIDER, CALLER);
    }

    /** The same contract, admitting a second consumer against the one provider. */
    private static String twoConsumerEcosystem() {
        return ecosystem()
                .replace(
                        "\"members\": [",
                        "\"members\": [{\"participant\": \""
                                + OTHER_CONSUMER
                                + "\", \"role\": \"Data consumer\","
                                + " \"signature\": \"hasSigned\","
                                + " \"date\": \"2026-01-16T09:00:00.000Z\"},");
    }

    /** Drops the cached contract, which is TTL expiry without waiting one out. */
    private void invalidate(String contractUri) {
        caches.getCache(ContractGateway.CACHE_NAME).invalidate(contractUri);
    }

    private PrivacyNotice activeNotice(String contractUri) {
        return activeNotices(contractUri).stream().findFirst().orElseThrow();
    }

    private List<PrivacyNotice> activeNotices(String contractUri) {
        return allNotices(contractUri).stream()
                .filter(notice -> notice.getArchivedAt() == null)
                .toList();
    }

    /**
     * Every row this suite wrote for a contract, archived ones included.
     *
     * <p>Filtered here rather than queried: nothing in {@code src/main} reads a contract's whole
     * notice history, so the repository carries no method for it.
     */
    private List<PrivacyNotice> allNotices(String contractUri) {
        return StreamSupport.stream(notices.findAll().spliterator(), false)
                .filter(notice -> contractUri.equals(notice.getContractUri()))
                .toList();
    }

    private PrivacyNotice notice(UUID id) {
        return notices.findById(id).orElseThrow();
    }

    private Participant participant(String identifier) {
        return participants.findByIdentifier(identifier).orElseThrow();
    }

    private void register(String identifier) {
        Optional<Participant> existing = participants.findByIdentifier(identifier);
        if (existing.isEmpty()) {
            participants.save(new Participant(identifier, identifier, null, null, Map.of(), null));
        }
    }

    private void deregister(String identifier) {
        Participant participant = participant(identifier);
        participant.setDeregisteredAt(java.time.Instant.now());
        participants.update(participant);
    }

    private void reactivate(String identifier) {
        Participant participant = participant(identifier);
        participant.setDeregisteredAt(null);
        participants.update(participant);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        try {
            return json.readValue(response.body(), Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the response body", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolution(Map<String, Object> body) {
        return (Map<String, Object>) body.get("resolution");
    }
}
