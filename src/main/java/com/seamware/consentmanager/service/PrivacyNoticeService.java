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
     * The notice held under an id, with the documents it only names filled in when asked for.
     *
     * <p>An archived notice is returned like an active one and reports {@code archivedAt}: a
     * consent given against a notice is read against the notice that was published, not against
     * whatever superseded it - which is also why the resolver leaves its contract unread. Not
     * scoped to the two parties either - a user has to be able to read the notice they are being
     * asked to consent to before any link to it exists.
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
     * The participant row a stored notice points at.
     *
     * <p>Both foreign keys are {@code ON DELETE RESTRICT}, so a notice can only ever point at a row
     * that exists; a missing one is a broken database rather than a request the caller got wrong,
     * and saying so beats reporting it as a notice that is not there.
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
        requireParty(callerIdentifier, providerIdentifier, consumerIdentifier);

        return generate(fetch(contractUri), contractUri, provider, consumer);
    }

    /**
     * Generates the notice for a contract that names its own two parties, reporting whether this
     * call stored it.
     *
     * <p>Only the parties are arrived at differently; everything from there on is {@link
     * #generate(ParticipantPrincipal, String, String, String)}, so the two operations cannot drift
     * over duplicates, archival, the caller's standing or a lost race. A contract that does not
     * narrow each side to one party is {@code 400} from {@link ContractPartyResolver}, which is why
     * the fetch comes first here: there is nothing to look up until the document has been read.
     *
     * <p>The caller is checked against the derived pair <em>before</em> anything else names a
     * party, which the declared form has no reason to do. Here the identifiers come off the
     * contract rather than off the request, so every refusal that quotes one - the {@code 404} for
     * a party this deployment has no registration for, the {@code 400} for a contract naming one
     * participant on both sides - would otherwise report it to a stranger. The resolver's own
     * ambiguity refusal precedes this check and therefore names no participant at all.
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
     * The half both operations share: the contract must support the pair, and then the notice is
     * stored or found already stored.
     *
     * <p>The binding check runs on derived parties too. They pass it by construction - the resolver
     * reads the sides off the evidence the check tests against - and running it anyway is what
     * keeps that true: were the two readings ever to diverge, this refuses rather than publishing a
     * notice whose parties the contract does not support.
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
        return UniqueViolation.rejected(e);
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
