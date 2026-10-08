package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.client.dto.ContractResponse.Member;
import com.seamware.consentmanager.client.dto.ContractResponse.ServiceOffering;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Generates the privacy notice a contract yields, and decides whether it supersedes the one already
 * held for that contract and provider.
 *
 * <p>Deliberately not transactional at the entry point. The only write is {@link
 * PrivacyNoticeWriter#archiveAndCreate}, and the race it can lose is settled by re-reading the
 * winner - which Postgres forbids inside the transaction that just failed, the same reason {@code
 * UserService}'s create-or-link path stays outside one.
 */
@Singleton
public class PrivacyNoticeService {

    private final ParticipantRepository participants;
    private final PrivacyNoticeRepository notices;
    private final ContractGateway contracts;
    private final ContractContentExtractor extractor;
    private final PrivacyNoticeWriter writer;

    public PrivacyNoticeService(
            ParticipantRepository participants,
            PrivacyNoticeRepository notices,
            ContractGateway contracts,
            ContractContentExtractor extractor,
            PrivacyNoticeWriter writer) {
        this.participants = participants;
        this.notices = notices;
        this.contracts = contracts;
        this.extractor = extractor;
        this.writer = writer;
    }

    /**
     * Generates the notice for a contract and the two participants it binds, reporting whether this
     * call stored it.
     *
     * <p>The caller must be one of the two parties and must be active, because generating a notice
     * acts in the dataspace in the participant's name. Its own standing is checked before anything
     * the body names, so a token carrying no active registration is refused whatever it asks for.
     * Both identifiers must then be registered here and both must be bound by the fetched contract.
     * An unreachable Contract Service surfaces as {@link
     * com.seamware.consentmanager.error.UpstreamServiceException} from the gateway - the one place
     * in this API where that outage is fatal rather than degraded.
     */
    @NonNull
    public GeneratedNotice generate(
            @NonNull ParticipantPrincipal caller,
            @NonNull String contractUri,
            @NonNull String providerIdentifier,
            @NonNull String consumerIdentifier) {
        String callerIdentifier = caller.requireActive().getIdentifier();
        Participant provider = require(providerIdentifier);
        Participant consumer = require(consumerIdentifier);
        requireParty(callerIdentifier, provider, consumer);

        ContractResponse contract =
                contracts
                        .fetch(contractUri)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "No contract is held under '"
                                                        + contractUri
                                                        + "'."));
        requireBound(contract, providerIdentifier, consumerIdentifier);

        return store(contractUri, provider, consumer, extractor.extract(contract, provider));
    }

    /** The registered participant an identifier names; {@code 404} when it names none. */
    @NonNull
    private Participant require(String identifier) {
        return participants
                .findByIdentifier(identifier)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "No participant is registered under '"
                                                + identifier
                                                + "'."));
    }

    /** Refuses a caller that is neither of the two parties. */
    private static void requireParty(
            String identifier, Participant provider, Participant consumer) {
        if (!identifier.equals(provider.getIdentifier())
                && !identifier.equals(consumer.getIdentifier())) {
            throw new ForbiddenException(
                    "Participant '"
                            + identifier
                            + "' is neither the data provider nor the data consumer of this"
                            + " notice, so it may not generate it.");
        }
    }

    /**
     * Refuses a request naming a party the fetched contract does not bind.
     *
     * <p>Without it the body could name any registered participant as consumer and the published
     * notice would misname a party; with it, a party that differs from the stored notice's can only
     * mean the contract itself changed, which is what makes the comparison in {@link #store} say
     * what it is meant to say.
     */
    private static void requireBound(ContractResponse contract, String provider, String consumer) {
        switch (contract.shape()) {
            case BILATERAL -> {
                requireNamed(provider.equals(contract.dataProvider()), provider, "data provider");
                requireNamed(consumer.equals(contract.dataConsumer()), consumer, "data consumer");
            }
            case ECOSYSTEM -> {
                Set<String> members = activeMembers(contract);
                requireNamed(
                        members.contains(provider)
                                && offeringParticipants(contract).contains(provider),
                        provider,
                        "data provider");
                requireNamed(members.contains(consumer), consumer, "data consumer");
            }
        }
    }

    private static void requireNamed(boolean bound, String identifier, String role) {
        if (!bound) {
            throw new BadRequestException(
                    "The contract does not bind '" + identifier + "' as its " + role + ".");
        }
    }

    /** Participants an ecosystem contract still binds: its members, less the revoked ones. */
    private static Set<String> activeMembers(ContractResponse contract) {
        Set<String> revoked = participantsOf(contract.revokedMembers());
        return participantsOf(contract.members()).stream()
                .filter(member -> !revoked.contains(member))
                .collect(Collectors.toSet());
    }

    private static Set<String> participantsOf(@Nullable List<Member> members) {
        return members == null
                ? Set.of()
                : members.stream()
                        .map(Member::participant)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
    }

    /**
     * Participants an ecosystem contract names as offering a service, which is the provider side.
     */
    private static Set<String> offeringParticipants(ContractResponse contract) {
        List<ServiceOffering> offerings = contract.serviceOfferings();
        return offerings == null
                ? Set.of()
                : offerings.stream()
                        .map(ServiceOffering::participant)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
    }

    /**
     * Returns the active notice unchanged, or supersedes it with what the contract yields now.
     *
     * <p>The comparison is against the extracted content rather than against the contract document,
     * so an upstream edit that leaves the notice identical - a signature added, a timestamp bumped
     * - does not archive a notice that consents already point at.
     */
    @NonNull
    private GeneratedNotice store(
            String contractUri, Participant provider, Participant consumer, NoticeContent content) {
        Optional<PrivacyNotice> active =
                notices.findByContractUriAndProviderIdAndArchivedAtIsNull(
                        contractUri, provider.getId());
        if (active.isPresent() && unchanged(active.get(), consumer, content)) {
            return render(active.get(), provider, consumer, false);
        }

        PrivacyNotice replacement =
                new PrivacyNotice(
                        contractUri,
                        content.title(),
                        provider.getId(),
                        consumer.getId(),
                        content.payload());
        try {
            return render(
                    writer.archiveAndCreate(active.orElse(null), replacement),
                    provider,
                    consumer,
                    true);
        } catch (DataAccessException e) {
            // A concurrent generation holds the active slot; serve its notice rather than an error.
            return notices.findByContractUriAndProviderIdAndArchivedAtIsNull(
                            contractUri, provider.getId())
                    .map(raced -> render(raced, provider, consumer, false))
                    .orElseThrow(() -> e);
        }
    }

    /** Whether the stored notice already says exactly what this contract yields now. */
    private static boolean unchanged(
            PrivacyNotice stored, Participant consumer, NoticeContent content) {
        return Objects.equals(stored.getTitle(), content.title())
                && Objects.equals(stored.getConsumerId(), consumer.getId())
                && Objects.equals(stored.getPayload(), content.payload());
    }

    /**
     * Names the notice's parties by identifier.
     *
     * <p>The resolved consumer answers for every notice this call assembled; only a race-loser can
     * be serving a row naming someone else, and that one costs the lookup.
     */
    @NonNull
    private GeneratedNotice render(
            PrivacyNotice notice, Participant provider, Participant consumer, boolean created) {
        UUID storedConsumer = notice.getConsumerId();
        String consumerIdentifier =
                Objects.equals(storedConsumer, consumer.getId())
                        ? consumer.getIdentifier()
                        : consumerIdentifier(storedConsumer);
        return new GeneratedNotice(notice, provider.getIdentifier(), consumerIdentifier, created);
    }

    @Nullable
    private String consumerIdentifier(@Nullable UUID consumerId) {
        return consumerId == null
                ? null
                : participants.findById(consumerId).map(Participant::getIdentifier).orElse(null);
    }
}
