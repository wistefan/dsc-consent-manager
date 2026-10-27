package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.ContractServiceStub;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that concurrent generation for one contract and provider settles on one active notice and
 * serves the losers the winner rather than an error. The row count is asserted at the database.
 */
@MicronautTest(transactional = false)
@DisplayName("Concurrent privacy notice generation")
class PrivacyNoticeConcurrencyIT extends PostgresTestResource {

    /** Callers racing for the one active slot. */
    private static final int RACERS = 8;

    private static final String CONTRACT_ID = "concurrent";

    private static final String CONTRACT_URI =
            "https://contract.example.org/v1/contracts/" + CONTRACT_ID;

    /** The provider the ecosystem fixture names, which is also the caller here. */
    private static final String PROVIDER = "did:web:provider.example.org";

    /** The consumer the ecosystem fixture names. */
    private static final String CONSUMER = "did:web:consumer.example.org";

    private static final String ISSUER = "https://idp.example.org/realms/test";

    private static final String SUBJECT = "concurrency-it-subject";

    private static final ContractServiceStub CONTRACTS = new ContractServiceStub();

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject PrivacyNoticeService service;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put("consent-manager.contract-service.url", CONTRACTS.url());
        return properties;
    }

    @AfterAll
    static void stopContractService() {
        CONTRACTS.close();
    }

    @Test
    @DisplayName("every racing caller succeeds, naming the one notice that survived")
    void concurrentGenerationYieldsExactlyOneActiveNotice() throws Exception {
        CONTRACTS.serveEcosystemContract(
                CONTRACT_ID, ContractFixtures.body(ContractFixtures.ECOSYSTEM));
        Participant provider = register(PROVIDER);
        register(CONSUMER);
        ParticipantPrincipal caller = new ParticipantPrincipal(ISSUER, SUBJECT, PROVIDER, provider);

        List<UUID> served = raceToGenerate(caller);

        List<PrivacyNotice> active =
                StreamSupport.stream(notices.findAll().spliterator(), false)
                        .filter(notice -> CONTRACT_URI.equals(notice.getContractUri()))
                        .filter(notice -> notice.getArchivedAt() == null)
                        .toList();
        assertThat(active)
                .as("the partial unique index admits one active notice per contract and provider")
                .hasSize(1);
        assertThat(served)
                .as("every caller succeeded, and all of them were served the surviving notice")
                .hasSize(RACERS)
                .containsOnly(active.getFirst().getId());
    }

    /** Runs {@link #RACERS} generations that start together, returning the id each was served. */
    private List<UUID> raceToGenerate(ParticipantPrincipal caller) throws Exception {
        CyclicBarrier start = new CyclicBarrier(RACERS);
        Callable<UUID> generate =
                () -> {
                    start.await();
                    return service.generate(caller, CONTRACT_URI, PROVIDER, CONSUMER)
                            .notice()
                            .getId();
                };
        try (ExecutorService pool = Executors.newFixedThreadPool(RACERS)) {
            List<Future<UUID>> outcomes = pool.invokeAll(Collections.nCopies(RACERS, generate));
            List<UUID> served = new ArrayList<>(RACERS);
            for (Future<UUID> outcome : outcomes) {
                served.add(outcome.get());
            }
            return served;
        }
    }

    private Participant register(String identifier) {
        Optional<Participant> existing = participants.findByIdentifier(identifier);
        return existing.orElseGet(
                () ->
                        participants.save(
                                new Participant(
                                        identifier, identifier, null, null, Map.of(), null)));
    }
}
