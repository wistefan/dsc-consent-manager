package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.error.UpstreamServiceException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Lists the contracts a data subject's participants, or one participant on one side, take part in.
 *
 * <p>Candidates are the contract URIs of active privacy notices, each fetched and kept only for the
 * sides {@link ContractPartyResolver} confirms. Any upstream failure or the configured deadline
 * aborts the whole listing with {@code 502}, never a truncated list.
 */
@Singleton
public class ContractDiscoveryService {

    /** Floor on the configured concurrency; zero would never read. */
    private static final int MIN_CONCURRENCY = 1;

    private final PrivacyNoticeRepository notices;
    private final UserParticipantRepository links;
    private final ParticipantRepository participants;
    private final ContractGateway contracts;
    private final ContractPartyResolver parties;
    private final ExecutorService executor;
    private final int maxConcurrency;
    private final Duration maxDuration;

    /** Creates the service; contracts are read on the blocking executor, bounded by config. */
    public ContractDiscoveryService(
            PrivacyNoticeRepository notices,
            UserParticipantRepository links,
            ParticipantRepository participants,
            ContractGateway contracts,
            ContractPartyResolver parties,
            @Named(TaskExecutors.BLOCKING) ExecutorService executor,
            ConsentManagerConfiguration.ContractDiscovery configuration) {
        this.notices = notices;
        this.links = links;
        this.participants = participants;
        this.contracts = contracts;
        this.parties = parties;
        this.executor = executor;
        this.maxConcurrency = Math.max(MIN_CONCURRENCY, configuration.getMaxConcurrency());
        this.maxDuration = configuration.getMaxDuration();
    }

    /** Contracts of the subject's linked participants, once each with all their sides, by URI. */
    public List<DiscoveredContract> forUser(@NonNull User subject) {
        List<UUID> linked =
                links.findByIdUserId(subject.getId()).stream()
                        .map(UserParticipant::getParticipantId)
                        .toList();
        if (linked.isEmpty()) {
            return List.of();
        }
        SortedMap<String, List<Claim>> candidates = new TreeMap<>();
        for (Participant participant : participants.findByIdIn(linked)) {
            for (ContractSide side : ContractSide.values()) {
                collect(candidates, participant, side);
            }
        }
        return hydrate(candidates);
    }

    /** The contracts the participant takes part in on the given side, ordered by contract URI. */
    public List<DiscoveredContract> forParticipant(
            @NonNull Participant participant, @NonNull ContractSide side) {
        SortedMap<String, List<Claim>> candidates = new TreeMap<>();
        collect(candidates, participant, side);
        return hydrate(candidates);
    }

    /** Adds the participant's contract URIs on one side as claims to verify. */
    private void collect(
            Map<String, List<Claim>> candidates, Participant participant, ContractSide side) {
        UUID participantId = participant.getId();
        Collection<String> uris =
                switch (side) {
                    case PROVIDER -> notices.findActiveContractUrisByProvider(participantId);
                    case CONSUMER -> notices.findActiveContractUrisByConsumer(participantId);
                };
        Claim claim = new Claim(participant.getIdentifier(), side);
        uris.forEach(
                uri -> candidates.computeIfAbsent(uri, ignored -> new ArrayList<>()).add(claim));
    }

    /** Fetches all candidates concurrently; all or nothing, never a prefix. */
    private List<DiscoveredContract> hydrate(SortedMap<String, List<Claim>> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Semaphore permits = new Semaphore(maxConcurrency);
        List<Future<Optional<DiscoveredContract>>> pending = new ArrayList<>(candidates.size());
        try {
            candidates.forEach(
                    (uri, claims) ->
                            pending.add(
                                    executor.submit(
                                            () -> {
                                                permits.acquire();
                                                try {
                                                    return discover(uri, claims);
                                                } finally {
                                                    permits.release();
                                                }
                                            })));
            long deadline = System.nanoTime() + maxDuration.toNanos();
            return pending.stream()
                    .map(fetch -> awaitBy(fetch, deadline))
                    .flatMap(Optional::stream)
                    .toList();
        } finally {
            pending.forEach(fetch -> fetch.cancel(true));
        }
    }

    /** The contract with the sides its document still confirms; empty if it confirms none. */
    private Optional<DiscoveredContract> discover(String uri, List<Claim> claims) {
        return contracts
                .fetch(uri)
                .flatMap(
                        contract -> {
                            Set<ContractSide> sides = confirmedSides(contract, claims);
                            return sides.isEmpty()
                                    ? Optional.empty()
                                    : Optional.of(
                                            new DiscoveredContract(
                                                    uri,
                                                    contract,
                                                    Collections.unmodifiableSet(sides)));
                        });
    }

    private Set<ContractSide> confirmedSides(ContractResponse contract, List<Claim> claims) {
        return claims.stream()
                .filter(claim -> parties.binds(contract, claim.participant(), claim.side()))
                .map(Claim::side)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ContractSide.class)));
    }

    /** Awaits one fetch until the listing's deadline; other failures surface as {@code 502}. */
    private Optional<DiscoveredContract> awaitBy(
            Future<Optional<DiscoveredContract>> fetch, long deadline) {
        try {
            return fetch.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new UpstreamServiceException(
                    "The Contract Service did not answer for every contract within " + maxDuration,
                    e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new UpstreamServiceException("Reading a contract failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamServiceException("Interrupted while reading contracts", e);
        }
    }

    /** A notice's claim that the participant takes this side in its contract. */
    private record Claim(String participant, ContractSide side) {}
}
