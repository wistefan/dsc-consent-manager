package com.seamware.consentmanager.support;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.cache.CacheManager;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Harness every privacy-notice API suite runs on: a stubbed Contract Service, the participants a
 * contract fixture names, and the reads that look behind the response at the stored rows.
 *
 * <p>Shared rather than copied because the parts that would drift are the ones no suite asserts. A
 * cleanup that stops removing a row one suite still writes, or a retry budget raised in one copy
 * only, fails in whichever suite was not updated and is diagnosed there.
 *
 * <p>The stub is an instance field, so each concrete suite owns one:
 * {@code @TestInstance(PER_CLASS)} is inherited from {@link PostgresTestResource}, which is also
 * what lets the {@code @AfterAll} that closes it run per suite rather than once for the first.
 */
public abstract class PrivacyNoticeApiTestSupport extends KeycloakAndPostgresTestResource {

    /** Prefix every contract URI carries; the last segment is the upstream contract id. */
    protected static final String CONTRACT_URI_PREFIX =
            "https://contract.example.org/v1/contracts/";

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    protected static final String CALLER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** Provider the fixtures name, replaced by {@link #CALLER} wherever the caller is a party. */
    protected static final String FIXTURE_PROVIDER = "did:web:provider.example.org";

    /** Consumer the fixtures name, registered here so the parties resolve. */
    protected static final String CONSUMER = "did:web:consumer.example.org";

    /** A second consumer, for the ecosystem contract that admits two against one provider. */
    protected static final String OTHER_CONSUMER = "did:web:other-consumer.example.org";

    /** Identifier no participant is registered under. */
    protected static final String UNREGISTERED = "urn:test:participant:never-registered";

    /** Content type every refusal renders. */
    protected static final String PROBLEM_JSON = "application/problem+json";

    /** Retry budget these suites run under, so an upstream case costs milliseconds. */
    private static final String RETRY_ATTEMPTS = "1";

    private static final String RETRY_DELAY = "10ms";

    /**
     * Participants registered before each case and removed after it.
     *
     * <p>A notice holds its parties against {@code ON DELETE RESTRICT} and these rows outlive the
     * case's transaction, so a leftover would make every later suite's participant cleanup fail.
     */
    private static final List<String> PARTIES =
            List.of(CALLER, FIXTURE_PROVIDER, CONSUMER, OTHER_CONSUMER);

    /**
     * The stubbed Contract Service, named by {@link #getProperties()} before the context starts.
     */
    protected final ContractServiceStub contracts = new ContractServiceStub();

    @Inject
    @Client("/")
    protected HttpClient client;

    @Inject protected ParticipantRepository participants;

    @Inject protected PrivacyNoticeRepository notices;

    @Inject protected CacheManager<?> caches;

    @Inject protected ObjectMapper json;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put("consent-manager.contract-service.url", contracts.url());
        properties.put("consent-manager.contract-service.retry-attempts", RETRY_ATTEMPTS);
        properties.put("consent-manager.contract-service.retry-delay", RETRY_DELAY);
        return properties;
    }

    @AfterAll
    void stopContractService() {
        contracts.close();
    }

    @BeforeEach
    void registerParties() {
        PARTIES.forEach(this::register);
    }

    @AfterEach
    void removeCommittedRows() {
        notices.deleteAll();
        PARTIES.forEach(
                identifier ->
                        participants.findByIdentifier(identifier).ifPresent(participants::delete));
    }

    /** Issues a request with the realm's participant token, returning a refusal as a response. */
    protected HttpResponse<String> post(String path, Map<String, String> payload) {
        HttpRequest<Map<String, String>> request =
                HttpRequest.POST(path, payload)
                        .bearerAuth(KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> refusal = (HttpResponse<String>) e.getResponse();
            return refusal;
        }
    }

    /** Reads a path with the realm's participant token, returning a refusal as a response. */
    protected HttpResponse<String> get(String path) {
        HttpRequest<?> request =
                HttpRequest.GET(path)
                        .bearerAuth(KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> refusal = (HttpResponse<String>) e.getResponse();
            return refusal;
        }
    }

    /**
     * Writes a notice straight to the table, bypassing generation.
     *
     * <p>What a resolution case needs: generating through the API would fetch the contract and
     * leave it cached, so the retrieval under test would be served from the cache and never reach
     * the stub it is about.
     */
    protected PrivacyNotice seed(
            String contractUri,
            String title,
            String provider,
            String consumer,
            PrivacyNoticePayload payload) {
        return notices.save(
                new PrivacyNotice(
                        contractUri,
                        title,
                        participant(provider).getId(),
                        consumer == null ? null : participant(consumer).getId(),
                        payload));
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> body(HttpResponse<String> response) {
        try {
            return json.readValue(response.body(), Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the response body", e);
        }
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> resolution(Map<String, Object> body) {
        return (Map<String, Object>) body.get("resolution");
    }

    /** The URI a contract id is served under, whether or not anything is stubbed for it. */
    protected static String uri(String contractId) {
        return CONTRACT_URI_PREFIX + contractId;
    }

    /** Serves the ecosystem fixture, the caller standing in as its provider. */
    protected String serveEcosystem(String contractId) {
        return serveBody(contractId, ecosystem());
    }

    /** Serves the fixture stating both purposes and terms, the caller standing in as provider. */
    protected String serveResolvable(String contractId) {
        return serveBody(
                contractId,
                ContractFixtures.body(ContractFixtures.ECOSYSTEM_RESOLVABLE)
                        .replace(FIXTURE_PROVIDER, CALLER));
    }

    /** Serves a document on the ecosystem endpoint, returning the URI it is held under. */
    protected String serveBody(String contractId, String document) {
        contracts.serveEcosystemContract(contractId, document);
        return uri(contractId);
    }

    /** Serves the bilateral fixture on the bilateral endpoint alone, the caller its provider. */
    protected String serveBilateral(String contractId) {
        contracts.serveBilateralContract(
                contractId,
                ContractFixtures.body(ContractFixtures.BILATERAL)
                        .replace(FIXTURE_PROVIDER, CALLER));
        return uri(contractId);
    }

    /** The ecosystem fixture with the caller as its provider, so the caller is a party. */
    protected static String ecosystem() {
        return ContractFixtures.body(ContractFixtures.ECOSYSTEM).replace(FIXTURE_PROVIDER, CALLER);
    }

    /** The same contract, recording a second consumer against the one provider. */
    protected static String twoConsumerEcosystem() {
        return withSecondConsumer(ecosystem());
    }

    /**
     * An ecosystem document with one more consumer than it had, which is what leaves its consuming
     * side open. Takes the document so a case can make a contract ambiguous for a caller that is no
     * party to it.
     */
    protected static String withSecondConsumer(String document) {
        return document.replace(
                "\"members\": [",
                "\"members\": [{\"participant\": \""
                        + OTHER_CONSUMER
                        + "\", \"role\": \"Data consumer\","
                        + " \"signature\": \"hasSigned\","
                        + " \"date\": \"2026-01-16T09:00:00.000Z\"},");
    }

    /** Drops the cached contract, which is TTL expiry without waiting one out. */
    protected void invalidate(String contractUri) {
        caches.getCache(ContractGateway.CACHE_NAME).invalidate(contractUri);
    }

    protected PrivacyNotice activeNotice(String contractUri) {
        return activeNotices(contractUri).stream().findFirst().orElseThrow();
    }

    protected List<PrivacyNotice> activeNotices(String contractUri) {
        return allNotices(contractUri).stream()
                .filter(notice -> notice.getArchivedAt() == null)
                .toList();
    }

    /**
     * Every row written for a contract, archived ones included.
     *
     * <p>Filtered here rather than queried: nothing in {@code src/main} reads a contract's whole
     * notice history, so the repository carries no method for it.
     */
    protected List<PrivacyNotice> allNotices(String contractUri) {
        return StreamSupport.stream(notices.findAll().spliterator(), false)
                .filter(notice -> contractUri.equals(notice.getContractUri()))
                .toList();
    }

    protected PrivacyNotice notice(UUID id) {
        return notices.findById(id).orElseThrow();
    }

    protected Participant participant(String identifier) {
        return participants.findByIdentifier(identifier).orElseThrow();
    }

    protected void register(String identifier) {
        if (participants.findByIdentifier(identifier).isEmpty()) {
            participants.save(new Participant(identifier, identifier, null, null, Map.of(), null));
        }
    }

    protected void deregister(String identifier) {
        Participant participant = participant(identifier);
        participant.setDeregisteredAt(Instant.now());
        participants.update(participant);
    }

    protected void reactivate(String identifier) {
        Participant participant = participant(identifier);
        participant.setDeregisteredAt(null);
        participants.update(participant);
    }
}
