package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.client.SelfDescriptionClient;
import com.seamware.consentmanager.client.dto.SelfDescription;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
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
 * <p>An archived notice is the one case where the contract is deliberately left unread, and both
 * contract-fed sections report {@code NOT_REQUESTED} for it. A notice is archived precisely because
 * the contract changed, so reading the live one would print the superseding contract's title,
 * purposes and terms under the superseded notice's id - a document that was never published. The
 * catalog and the recipients are still resolved, because those fill in the URIs and parties the
 * archived notice itself names.
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
    private final int maxDocuments;

    /** Takes the catalog budget from configuration; everything else is a collaborator. */
    public PrivacyNoticeResolver(
            ContractGateway contracts,
            ContractContentExtractor extractor,
            SelfDescriptionClient selfDescriptions,
            ParticipantRepository participants,
            ConsentManagerConfiguration.SelfDescription configuration) {
        this.contracts = contracts;
        this.extractor = extractor;
        this.selfDescriptions = selfDescriptions;
        this.participants = participants;
        this.maxDocuments = configuration.getMaxDocuments();
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

        // An archived notice is a published document the contract has since moved past, so the
        // live contract is deliberately not read for it; the class comment says why.
        boolean superseded = notice.getArchivedAt() != null;
        Optional<NoticeContent> live =
                superseded ? Optional.empty() : live(notice.getContractUri(), provider);
        // The purposes are read off the contract, so they can only be as resolved as it was.
        State contractState =
                superseded ? State.NOT_REQUESTED : NoticeResolution.stateOf(live.isPresent());
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

    /**
     * What the contract says now, or empty when it could not be read or is no longer known.
     *
     * <p>Every runtime failure is caught, not only the gateway's {@link UpstreamServiceException}:
     * extraction runs in here too, and an odd-but-decodable contract that tripped it would escape a
     * GET as the 500 this class exists to prevent. The catch is what makes "resolution never fails
     * the request" true by construction rather than by the extractor staying defensive.
     */
    private Optional<NoticeContent> live(String contractUri, Participant provider) {
        try {
            return contracts
                    .fetch(contractUri)
                    .map(contract -> extractor.extract(contract, provider));
        } catch (RuntimeException e) {
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
     * <p>The resources are appended as entries of their own carrying {@code partOf}, because that
     * is where a resource's title and description belong and the contract carries nothing about
     * them. A failure at either hop leaves the affected entry bare and marks the whole section
     * unavailable: a list that is only partly filled in while claiming to be resolved would
     * misreport itself. Running out of budget is reported the same way, and for the same reason.
     */
    private Section<List<DataReference>> data(@Nullable List<DataReference> stored) {
        if (stored == null) {
            return new Section<>(null, State.RESOLVED);
        }
        CatalogWalk walk = new CatalogWalk();
        List<DataReference> resolved = stored.stream().flatMap(walk::expand).toList();
        return new Section<>(resolved, NoticeResolution.stateOf(walk.complete));
    }

    /**
     * Each recipient as this service has it registered, the ones it has no row for left bare.
     *
     * <p>A missing row marks the section unavailable rather than failing: a contract may well name
     * a party this deployment never registered, and the notice still has to be readable. A stored
     * entry carrying no identifier at all is dropped instead, because it names nobody - a blank row
     * in the recipient list of a published notice tells a reader strictly less than the section
     * being marked incomplete does, which dropping it also does.
     */
    private Section<List<Recipient>> recipients(@Nullable List<Recipient> stored) {
        if (stored == null) {
            return new Section<>(null, State.RESOLVED);
        }
        List<Lookup> lookups = stored.stream().map(this::lookUp).toList();
        List<Recipient> resolved =
                lookups.stream()
                        .filter(lookup -> lookup.registered().isPresent() || lookup.named())
                        .map(lookup -> lookup.registered().orElseGet(lookup::stored))
                        .toList();
        boolean complete = lookups.stream().allMatch(lookup -> lookup.registered().isPresent());
        return new Section<>(resolved, NoticeResolution.stateOf(complete));
    }

    /** A stored recipient beside this service's own registration of it, if it has one. */
    private Lookup lookUp(Recipient stored) {
        Optional<Recipient> registered =
                Optional.ofNullable(stored.identifier())
                        .flatMap(participants::findByIdentifier)
                        .map(row -> named(stored.identifier(), row));
        return new Lookup(stored, registered);
    }

    /** A catalog document's own name and description against the URI it was read from. */
    private static DataReference described(
            String uri, SelfDescription document, @Nullable String partOf) {
        return new DataReference(uri, document.name(), document.description(), partOf);
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

    /** A stored recipient and the registration this service holds for it, if any. */
    private record Lookup(@NonNull Recipient stored, @NonNull Optional<Recipient> registered) {

        /** Whether the stored entry names anyone at all, which is what makes it worth serving. */
        boolean named() {
            return stored.identifier() != null;
        }
    }

    /**
     * One retrieval's walk over the catalog, carrying the budget and what has already been read.
     *
     * <p>Both lists it walks come out of documents this service does not author, so without the
     * budget a single authenticated GET fans out as far as whoever publishes them decides. The
     * de-duplication is the same concern from the other side: two offerings naming one resource
     * read it once and publish it once.
     */
    private final class CatalogWalk {

        private final Set<String> read = new LinkedHashSet<>();

        /** False once any document could not be read, or the budget ran out before it was tried. */
        private boolean complete = true;

        /** The entry as the catalog describes it, followed by the resources behind it. */
        private Stream<DataReference> expand(DataReference entry) {
            Optional<SelfDescription> offering =
                    Optional.ofNullable(entry.uri()).flatMap(this::fetch);
            if (offering.isEmpty()) {
                return Stream.of(entry);
            }
            Stream<DataReference> resources =
                    offering.get().dataResourceUrls().stream()
                            .distinct()
                            .filter(url -> !read.contains(url))
                            .map(url -> resource(url, entry.uri()))
                            .toList()
                            .stream();
            return Stream.concat(
                    Stream.of(described(entry.uri(), offering.get(), null)), resources);
        }

        /** A data resource as its own document describes it, or bare when it could not be read. */
        private DataReference resource(String url, String offeringUri) {
            return fetch(url)
                    .map(document -> described(url, document, offeringUri))
                    .orElseGet(() -> new DataReference(url, null, null, offeringUri));
        }

        /** Reads one document against the budget; an exhausted budget reads nothing further. */
        private Optional<SelfDescription> fetch(String url) {
            if (read.size() >= maxDocuments) {
                LOG.debug(
                        "Stopping the catalog walk at {} documents, before {}", maxDocuments, url);
                complete = false;
                return Optional.empty();
            }
            read.add(url);
            Optional<SelfDescription> document = selfDescriptions.fetch(url);
            complete = complete && document.isPresent();
            return document;
        }
    }
}
