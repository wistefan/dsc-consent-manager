package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.client.dto.ContractResponse.Member;
import com.seamware.consentmanager.client.dto.ContractResponse.ServiceOffering;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import jakarta.inject.Singleton;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /**
     * SQLState Postgres raises when a unique index rejects a write.
     *
     * <p>Here it means one thing only: {@code uq_privacy_notices_active_contract} already holds an
     * active notice for this contract and provider, which is a lost race rather than a defect.
     */
    private static final String UNIQUE_VIOLATION = "23505";

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
     * The two parties must differ, and both must be registered here, still active, and bound by the
     * fetched contract. An unreachable Contract Service surfaces as {@link
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
        requireDistinctParties(providerIdentifier, consumerIdentifier);
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

    /**
     * Refuses a request naming one participant on both sides.
     *
     * <p>An ecosystem contract binds its provider as a member too, so without this the two sides
     * would both pass and the published notice would say a participant consumes its own data.
     */
    private static void requireDistinctParties(String provider, String consumer) {
        if (provider.equals(consumer)) {
            throw new BadRequestException(
                    "The data provider and the data consumer must be different participants, but"
                            + " both are '"
                            + provider
                            + "'.");
        }
    }

    /**
     * The active participant an identifier names; {@code 404} when it names none.
     *
     * <p>A departed participant is refused like an unknown one. Its record is retained only so the
     * notices already naming it stay legible, and minting a new one would republish the legal name
     * and contact deregistration archived - the very re-creation {@link
     * ParticipantPrincipal#requireActive()} refuses the caller, reached through the body instead of
     * the token.
     */
    @NonNull
    private Participant require(String identifier) {
        return participants
                .findByIdentifier(identifier)
                .filter(participant -> participant.getDeregisteredAt() == null)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "No active participant is registered under '"
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
        Optional<PrivacyNotice> active = activeNotice(contractUri, provider);
        if (active.isPresent() && unchanged(active.get(), consumer, content)) {
            return render(active.get(), provider, consumer, false);
        }
        try {
            return write(active.orElse(null), contractUri, provider, consumer, content);
        } catch (DataAccessException e) {
            if (!lostTheActiveSlot(e)) {
                throw e;
            }
            return settleRace(contractUri, provider, consumer, content, e);
        }
    }

    /**
     * Settles a race this call lost on {@code uq_privacy_notices_active_contract}.
     *
     * <p>The winner decides the outcome: a notice saying exactly what this call computed is served
     * as it stands, and one saying anything else is superseded - which is what the two generations
     * run in sequence would have done, so a concurrent pair cannot answer differently from a
     * sequential one. The retry is single: a second rejection means the slot is being contended
     * faster than this call can claim it, and {@code 409} says so rather than reporting a dropped
     * change as success.
     */
    @NonNull
    private GeneratedNotice settleRace(
            String contractUri,
            Participant provider,
            Participant consumer,
            NoticeContent content,
            DataAccessException lost) {
        Optional<PrivacyNotice> winner = activeNotice(contractUri, provider);
        if (winner.isPresent() && unchanged(winner.get(), consumer, content)) {
            return render(winner.get(), provider, consumer, false);
        }
        try {
            return write(winner.orElse(null), contractUri, provider, consumer, content);
        } catch (DataAccessException retried) {
            if (!lostTheActiveSlot(retried)) {
                throw retried;
            }
            throw new ConflictException(
                    "Another generation is holding the active notice for contract '"
                            + contractUri
                            + "'. Retry the request.",
                    lost);
        }
    }

    /** The notice holding this contract's active slot for this provider, when one does. */
    private Optional<PrivacyNotice> activeNotice(String contractUri, Participant provider) {
        return notices.findByContractUriAndProviderIdAndArchivedAtIsNull(
                contractUri, provider.getId());
    }

    /** Archives the notice handed in, when there is one, and stores the contract's content. */
    @NonNull
    private GeneratedNotice write(
            @Nullable PrivacyNotice superseded,
            String contractUri,
            Participant provider,
            Participant consumer,
            NoticeContent content) {
        PrivacyNotice replacement =
                new PrivacyNotice(
                        contractUri,
                        content.title(),
                        provider.getId(),
                        consumer.getId(),
                        content.payload());
        return render(writer.archiveAndCreate(superseded, replacement), provider, consumer, true);
    }

    /**
     * Whether a write failed because the active-notice index already holds a row.
     *
     * <p>Told apart on the constraint rather than on what the surviving row says: a race whose
     * winner computed different content is still a race, and a deadlock or a severed connection is
     * not one however closely the row left behind resembles this call's.
     */
    private static boolean lostTheActiveSlot(DataAccessException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
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
     * <p>Every path hands in the consumer the row names: the two that report a notice already held
     * are behind {@link #unchanged}, which compares it, and the two that report one written pass
     * the row they just built from it.
     */
    @NonNull
    private GeneratedNotice render(
            PrivacyNotice notice, Participant provider, Participant consumer, boolean created) {
        return new GeneratedNotice(
                notice, provider.getIdentifier(), consumer.getIdentifier(), created);
    }
}
