package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.client.dto.ContractResponse;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates the privacy notice a contract yields, superseding the one held for that contract and
 * provider when its content changed.
 *
 * <p>Not transactional: the only write is {@link PrivacyNoticeWriter#archiveAndCreate}, and a race
 * lost on {@code uq_privacy_notices_active_contract} is settled by re-reading the winner, which
 * Postgres forbids inside the failed transaction.
 */
@Singleton
public class PrivacyNoticeService {

    private final ParticipantRepository participants;
    private final PrivacyNoticeRepository notices;
    private final ContractGateway contracts;
    private final ContractContentExtractor extractor;
    private final PrivacyNoticeWriter writer;
    private final ContractPartyResolver parties;
    private final PrivacyNoticeResolver resolver;

    public PrivacyNoticeService(
            ParticipantRepository participants,
            PrivacyNoticeRepository notices,
            ContractGateway contracts,
            ContractContentExtractor extractor,
            PrivacyNoticeWriter writer,
            ContractPartyResolver parties,
            PrivacyNoticeResolver resolver) {
        this.participants = participants;
        this.notices = notices;
        this.contracts = contracts;
        this.extractor = extractor;
        this.writer = writer;
        this.parties = parties;
        this.resolver = resolver;
    }

    /**
     * The notice held under an id, its named documents resolved when asked for. Archived notices
     * are returned too, and the read is not scoped to the parties.
     */
    @NonNull
    public ResolvedNotice get(@NonNull UUID id, boolean resolve) {
        PrivacyNotice notice =
                notices.findById(id)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "No privacy notice is held under '" + id + "'."));
        return resolver.resolve(
                notice, party(notice.getProviderId()), consumer(notice.getConsumerId()), resolve);
    }

    /** The consumer a notice names, or null when it names none. */
    @Nullable
    private Participant consumer(@Nullable UUID consumerId) {
        return consumerId == null ? null : party(consumerId);
    }

    /**
     * The participant row a stored notice points at; a missing one is a broken database ({@code ON
     * DELETE RESTRICT}), so {@link IllegalStateException} rather than a 404.
     */
    @NonNull
    private Participant party(UUID participantId) {
        return participants
                .findById(participantId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Privacy notice names participant "
                                                + participantId
                                                + ", which no longer has a row."));
    }

    /**
     * Generates the notice for a contract and two declared parties, reporting whether this call
     * stored it.
     *
     * <p>The caller must be active (checked first) and one of the parties; the parties must differ,
     * be active here and be bound by the contract. An unreachable Contract Service is fatal here:
     * {@link com.seamware.consentmanager.error.UpstreamServiceException}.
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
        requireParty(callerIdentifier, providerIdentifier, consumerIdentifier);

        return generate(fetch(contractUri), contractUri, provider, consumer);
    }

    /**
     * Generates the notice for a contract that names its own two parties, otherwise as {@link
     * #generate(ParticipantPrincipal, String, String, String)}.
     *
     * <p>The caller is checked against the derived pair before any refusal quotes a party, so a
     * stranger learns no identifier off the contract.
     */
    @NonNull
    public GeneratedNotice generateByContract(
            @NonNull ParticipantPrincipal caller, @NonNull String contractUri) {
        String callerIdentifier = caller.requireActive().getIdentifier();
        ContractResponse contract = fetch(contractUri);
        ContractParties derived = parties.resolve(contract);

        requireParty(callerIdentifier, derived.dataProvider(), derived.dataConsumer());
        requireDistinctParties(derived.dataProvider(), derived.dataConsumer());
        Participant provider = require(derived.dataProvider());
        Participant consumer = require(derived.dataConsumer());

        return generate(contract, contractUri, provider, consumer);
    }

    /**
     * Shared tail: requires the contract to bind the pair (derived parties too, as a guard against
     * drift), then stores the notice or finds it stored.
     */
    @NonNull
    private GeneratedNotice generate(
            ContractResponse contract,
            String contractUri,
            Participant provider,
            Participant consumer) {
        parties.requireBound(contract, provider.getIdentifier(), consumer.getIdentifier());
        return store(contractUri, provider, consumer, extractor.extract(contract, provider));
    }

    /** The contract held under a URI; {@code 404} when the Contract Service holds none. */
    @NonNull
    private ContractResponse fetch(String contractUri) {
        return contracts
                .fetch(contractUri)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        "No contract is held under '" + contractUri + "'."));
    }

    /** Refuses one participant on both sides, which an ecosystem contract would otherwise pass. */
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
     * The active participant an identifier names; {@code 404} when none, a departed one included.
     * See ADR-0008.
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
    private static void requireParty(String identifier, String provider, String consumer) {
        if (!identifier.equals(provider) && !identifier.equals(consumer)) {
            throw new ForbiddenException(
                    "Participant '"
                            + identifier
                            + "' is neither the data provider nor the data consumer of this"
                            + " notice, so it may not generate it.");
        }
    }

    /**
     * Returns the active notice unchanged, or supersedes it; compares extracted content, not the
     * contract document, so a cosmetic upstream edit archives nothing.
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
     * Settles a race lost on {@code uq_privacy_notices_active_contract} as a sequential run would:
     * serve an identical winner, else supersede it once; a second loss is {@code 409}.
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

    /** Whether a write failed on the active-notice unique constraint, not on any other error. */
    private static boolean lostTheActiveSlot(DataAccessException e) {
        return UniqueViolation.rejected(e);
    }

    /** Whether the stored notice already says exactly what this contract yields now. */
    private static boolean unchanged(
            PrivacyNotice stored, Participant consumer, NoticeContent content) {
        return Objects.equals(stored.getTitle(), content.title())
                && Objects.equals(stored.getConsumerId(), consumer.getId())
                && Objects.equals(stored.getPayload(), content.payload());
    }

    /** Names the notice's parties by identifier; every caller passes the consumer the row names. */
    @NonNull
    private GeneratedNotice render(
            PrivacyNotice notice, Participant provider, Participant consumer, boolean created) {
        return new GeneratedNotice(
                notice, provider.getIdentifier(), consumer.getIdentifier(), created);
    }
}
