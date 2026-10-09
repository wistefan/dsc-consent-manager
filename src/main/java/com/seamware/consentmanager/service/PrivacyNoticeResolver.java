package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.client.SelfDescriptionClient;
import com.seamware.consentmanager.client.dto.SelfDescription;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import com.seamware.consentmanager.error.UpstreamServiceException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.service.NoticeResolution.State;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fills in what a stored notice only names, and reports per section how far that got.
 *
 * <p>Read-only: it produces a view beside the row and never writes one back, so what is stored
 * stays what generation extracted rather than drifting with read traffic. Resolution also never
 * takes a value away - a section that resolved to nothing keeps the stored text, because a live
 * document that says less than the contract did is no reason to publish less.
 *
 * <p>It is the one place {@link UpstreamServiceException} is caught. The gateway raises it when the
 * Contract Service cannot be reached, and letting it out of a GET would answer the 502 that
 * resolution exists to avoid; {@link SelfDescriptionClient} never throws, so the obligation is
 * one-sided.
 */
@Singleton
public class PrivacyNoticeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(PrivacyNoticeResolver.class);

    private final ContractGateway contracts;
    private final ContractContentExtractor extractor;
    private final SelfDescriptionClient selfDescriptions;
    private final ParticipantRepository participants;

    public PrivacyNoticeResolver(
            ContractGateway contracts,
            ContractContentExtractor extractor,
            SelfDescriptionClient selfDescriptions,
            ParticipantRepository participants) {
        this.contracts = contracts;
        this.extractor = extractor;
        this.selfDescriptions = selfDescriptions;
        this.participants = participants;
    }

    /**
     * The notice as a retrieval serves it, with every external document read when asked for.
     *
     * <p>{@code resolve} false makes no external call at all and reports {@code NOT_REQUESTED}
     * throughout, which is a different claim from everything having been read successfully.
     */
    @NonNull
    public ResolvedNotice resolve(
            @NonNull PrivacyNotice notice,
            @NonNull Participant provider,
            @Nullable Participant consumer,
            boolean resolve) {
        String providerIdentifier = provider.getIdentifier();
        String consumerIdentifier = consumer == null ? null : consumer.getIdentifier();
        PrivacyNoticePayload stored = notice.getPayload();
        if (!resolve) {
            return new ResolvedNotice(
                    notice,
                    providerIdentifier,
                    consumerIdentifier,
                    notice.getTitle(),
                    stored,
                    NoticeResolution.notRequested());
        }

        Optional<NoticeContent> live = live(notice.getContractUri(), provider);
        // The purposes are read off the contract, so they can only be as resolved as it was.
        State contractState = NoticeResolution.stateOf(live.isPresent());
        Section<List<DataReference>> data = data(stored.data());
        Section<List<Recipient>> recipients = recipients(stored.recipients());

        PrivacyNoticePayload payload =
                new PrivacyNoticePayload(
                        firstOf(
                                live.map(content -> content.payload().purposes()),
                                stored.purposes()),
                        stored.categoriesOfData(),
                        data.value(),
                        recipients.value(),
                        stored.controllerDetails(),
                        stored.retentionPeriod(),
                        firstOf(
                                live.map(content -> content.payload().piiPrincipalRights()),
                                stored.piiPrincipalRights()),
                        stored.internationalTransfers(),
                        stored.withdrawalOfConsent());
        return new ResolvedNotice(
                notice,
                providerIdentifier,
                consumerIdentifier,
                firstOf(live.map(NoticeContent::title), notice.getTitle()),
                payload,
                new NoticeResolution(
                        contractState, data.state(), contractState, recipients.state()));
    }

    /** What the contract says now, or empty when it could not be read or is no longer known. */
    private Optional<NoticeContent> live(String contractUri, Participant provider) {
        try {
            return contracts
                    .fetch(contractUri)
                    .map(contract -> extractor.extract(contract, provider));
        } catch (UpstreamServiceException e) {
            LOG.debug(
                    "Serving the stored notice for {}: the contract could not be read",
                    contractUri,
                    e);
            return Optional.empty();
        }
    }

    /**
     * The catalog walk: each stored entry names a service offering, whose own document names the
     * data resources behind it, and both hops are read.
     *
     * <p>The resources are appended as entries of their own, because that is where a resource's
     * title and description belong and the contract carries nothing about them. A failure at either
     * hop leaves the affected entry bare and marks the whole section unavailable: a list that is
     * only partly filled in while claiming to be resolved would misreport itself.
     */
    private Section<List<DataReference>> data(@Nullable List<DataReference> stored) {
        if (stored == null) {
            return new Section<>(null, State.RESOLVED);
        }
        List<DataReference> resolved = new ArrayList<>();
        boolean complete = true;
        for (DataReference entry : stored) {
            if (entry.uri() == null) {
                resolved.add(entry);
                continue;
            }
            Optional<SelfDescription> offering = selfDescriptions.fetch(entry.uri());
            if (offering.isEmpty()) {
                resolved.add(entry);
                complete = false;
                continue;
            }
            resolved.add(described(entry.uri(), offering.get()));
            for (String resourceUrl : offering.get().dataResourceUrls()) {
                Optional<SelfDescription> resource = selfDescriptions.fetch(resourceUrl);
                resolved.add(
                        resource.map(document -> described(resourceUrl, document))
                                .orElseGet(() -> new DataReference(resourceUrl, null, null)));
                complete = complete && resource.isPresent();
            }
        }
        return new Section<>(List.copyOf(resolved), NoticeResolution.stateOf(complete));
    }

    /**
     * Each recipient as this service has it registered, the ones it has no row for left bare.
     *
     * <p>A missing row marks the section unavailable rather than failing: a contract may well name
     * a party this deployment never registered, and the notice still has to be readable.
     */
    private Section<List<Recipient>> recipients(@Nullable List<Recipient> stored) {
        if (stored == null) {
            return new Section<>(null, State.RESOLVED);
        }
        List<Recipient> resolved = new ArrayList<>();
        boolean complete = true;
        for (Recipient recipient : stored) {
            Optional<Participant> row =
                    recipient.identifier() == null
                            ? Optional.empty()
                            : participants.findByIdentifier(recipient.identifier());
            if (row.isEmpty()) {
                resolved.add(recipient);
                complete = false;
                continue;
            }
            resolved.add(named(recipient.identifier(), row.get()));
        }
        return new Section<>(List.copyOf(resolved), NoticeResolution.stateOf(complete));
    }

    /** A catalog document's own name and description against the URI it was read from. */
    private static DataReference described(String uri, SelfDescription document) {
        return new DataReference(uri, document.name(), document.description());
    }

    /** A recipient as the local registration describes it. */
    private static Recipient named(String identifier, Participant participant) {
        return new Recipient(
                identifier,
                participant.getLegalName(),
                participant.getEmail(),
                participant.getSelfDescriptionUri());
    }

    /** The resolved value where there is one, and the stored value where there is not. */
    @Nullable
    private static <T> T firstOf(Optional<T> resolved, @Nullable T stored) {
        return resolved.orElse(stored);
    }

    /** One section's resolved value and how far it got, kept together so neither can be lost. */
    private record Section<T>(@Nullable T value, @NonNull State state) {}
}
