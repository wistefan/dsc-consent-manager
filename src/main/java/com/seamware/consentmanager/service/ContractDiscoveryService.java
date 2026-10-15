package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
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

/**
 * Lists the contracts a caller takes part in, for a data subject across their participants or for a
 * participant on one side.
 *
 * <p>The Contract Service cannot enumerate, so the candidates are the contract URIs of the active
 * privacy notices this service holds; each is then hydrated through {@link ContractGateway}. One
 * upstream no longer knows is dropped, while an upstream failure propagates as {@code 502} and
 * aborts the whole listing - a truncated list would read as a complete one.
 */
@Singleton
public class ContractDiscoveryService {

    private final PrivacyNoticeRepository notices;
    private final UserParticipantRepository links;
    private final ContractGateway contracts;

    /** Creates the service over the notice and link repositories and the contract gateway. */
    public ContractDiscoveryService(
            PrivacyNoticeRepository notices,
            UserParticipantRepository links,
            ContractGateway contracts) {
        this.notices = notices;
        this.links = links;
        this.contracts = contracts;
    }

    /**
     * The contracts any participant the subject is linked to takes part in, each listed once with
     * every side those participants take; ordered by contract URI.
     */
    public List<DiscoveredContract> forUser(@NonNull User subject) {
        SortedMap<String, Set<ContractSide>> candidates = new TreeMap<>();
        for (UserParticipant link : links.findByIdUserId(subject.getId())) {
            for (ContractSide side : ContractSide.values()) {
                collect(candidates, link.getParticipantId(), side);
            }
        }
        return hydrate(candidates);
    }

    /** The contracts the participant takes part in on the given side, ordered by contract URI. */
    public List<DiscoveredContract> forParticipant(
            @NonNull Participant participant, @NonNull ContractSide side) {
        SortedMap<String, Set<ContractSide>> candidates = new TreeMap<>();
        collect(candidates, participant.getId(), side);
        return hydrate(candidates);
    }

    /** Adds the participant's contract URIs on one side to the candidates, merging sides. */
    private void collect(
            Map<String, Set<ContractSide>> candidates, UUID participantId, ContractSide side) {
        Collection<String> uris =
                switch (side) {
                    case PROVIDER -> notices.findActiveContractUrisByProvider(participantId);
                    case CONSUMER -> notices.findActiveContractUrisByConsumer(participantId);
                };
        uris.forEach(
                uri ->
                        candidates
                                .computeIfAbsent(uri, ignored -> EnumSet.noneOf(ContractSide.class))
                                .add(side));
    }

    /**
     * Reads every candidate from the Contract Service before returning any, so a failure on the
     * last one still leaves the caller with nothing rather than a prefix.
     */
    private List<DiscoveredContract> hydrate(SortedMap<String, Set<ContractSide>> candidates) {
        return candidates.entrySet().stream()
                .map(
                        candidate ->
                                contracts
                                        .fetch(candidate.getKey())
                                        .map(
                                                contract ->
                                                        new DiscoveredContract(
                                                                candidate.getKey(),
                                                                contract,
                                                                Collections.unmodifiableSet(
                                                                        candidate.getValue()))))
                .flatMap(Optional::stream)
                .toList();
    }
}
