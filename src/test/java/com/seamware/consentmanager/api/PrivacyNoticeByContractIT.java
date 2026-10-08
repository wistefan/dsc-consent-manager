package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
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
class PrivacyNoticeByContractIT extends KeycloakAndPostgresTestResource {

    private static final String PATH = "/privacy-notices/by-contract";

    /** Where the same generation is reached with the two parties declared. */
    private static final String DECLARED_PATH = "/privacy-notices";

    /** Prefix every contract URI in this suite carries; the last segment is the upstream id. */
    private static final String CONTRACT_URI_PREFIX = "https://contract.example.org/v1/contracts/";

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** Provider the fixtures name, replaced by {@link #CALLER} wherever the caller is a party. */
    private static final String FIXTURE_PROVIDER = "did:web:provider.example.org";

    /** Consumer the fixtures name, registered here so the derived parties resolve. */
    private static final String CONSUMER = "did:web:consumer.example.org";

    /** A second consumer, for the contract that leaves its consuming side open. */
    private static final String OTHER_CONSUMER = "did:web:other-consumer.example.org";

    /** Identifier no participant is registered under. */
    private static final String UNREGISTERED = "urn:test:participant:never-registered";

    /** Retry budget this suite runs under, so an upstream case costs milliseconds. */
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

    @Test
    @DisplayName("a derived party this deployment has no registration for is a 404")
    void refusesAnUnregisteredDerivedParty() {
        String contractUri = serveBody("unregistered", ecosystem().replace(CONSUMER, UNREGISTERED));

        HttpResponse<String> response = generate(contractUri);

        assertThat(response.code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
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
        return send(HttpRequest.POST(PATH, Map.of("contractUri", contractUri)));
    }

    /** Issues the same generation with both parties declared, for the parity case. */
    private HttpResponse<String> declare(String contractUri) {
        return send(
                HttpRequest.POST(
                        DECLARED_PATH,
                        Map.of(
                                "contractUri", contractUri,
                                "dataProvider", CALLER,
                                "dataConsumer", CONSUMER)));
    }

    private HttpResponse<String> send(MutableHttpRequest<Map<String, String>> request) {
        try {
            return client.toBlocking()
                    .exchange(
                            request.bearerAuth(
                                    KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT)),
                            String.class);
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

    /** The ecosystem fixture with the caller as its provider, so the caller is a party. */
    private static String ecosystem() {
        return ContractFixtures.body(ContractFixtures.ECOSYSTEM).replace(FIXTURE_PROVIDER, CALLER);
    }

    /** The same contract, recording a second consumer, which leaves that side open. */
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

    /** Every row this suite wrote for a contract, archived ones included. */
    private List<PrivacyNotice> allNotices(String contractUri) {
        return StreamSupport.stream(notices.findAll().spliterator(), false)
                .filter(notice -> contractUri.equals(notice.getContractUri()))
                .toList();
    }

    private PrivacyNotice notice(UUID id) {
        return notices.findById(id).orElseThrow();
    }

    private void register(String identifier) {
        Optional<Participant> existing = participants.findByIdentifier(identifier);
        if (existing.isEmpty()) {
            participants.save(new Participant(identifier, identifier, null, null, Map.of(), null));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        try {
            return json.readValue(response.body(), Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the response body", e);
        }
    }
}
