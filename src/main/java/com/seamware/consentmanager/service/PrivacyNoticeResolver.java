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
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fills in what a stored notice only names, reporting per section how far that got. Read-only;
 * never fails the request (catches {@link UpstreamServiceException}) nor drops a stored value.
 *
 * <p>For an archived notice the live contract is left unread and its sections report {@code
 * NOT_REQUESTED}, since it would describe the superseding contract; catalog and recipients still
 * resolve.
 */
@Singleton
public class PrivacyNoticeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(PrivacyNoticeResolver.class);

    private final ContractGateway contracts;
    private final ContractContentExtractor extractor;
    private final SelfDescriptionClient selfDescriptions;
    private final ParticipantRepository participants;
    private final int maxDocuments;
    private final Duration maxDuration;

    /** Takes the catalog budget from configuration. */
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
        this.maxDuration = configuration.getMaxDuration();
    }

    /**
     * The notice as a retrieval serves it, external documents read when {@code resolve}; false
     * makes no call and reports {@code NOT_REQUESTED} throughout.
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
     * What the contract says now, or empty when unreadable or unknown; catches every runtime
     * failure, extraction's included, so resolution never fails the request.
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
     * Walks offering then data resources, appending resources as {@code partOf} entries. Any failed
     * read or exhausted budget leaves that entry bare and marks the whole section unavailable.
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
     * Each recipient as registered here; one with no row is left bare and marks the section
     * unavailable, and one with no identifier is dropped.
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
     * One retrieval's catalog walk, bounded by a document ceiling and a deadline because the
     * documents are third-party; each URL is read at most once.
     */
    private final class CatalogWalk {

        private final Set<String> read = new LinkedHashSet<>();

        /** When this walk stops reading. */
        private final long deadline = System.nanoTime() + maxDuration.toNanos();

        /** False once any document could not be read, or a budget ran out before it was tried. */
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

        /** Reads one document against the budgets; an exhausted budget reads nothing further. */
        private Optional<SelfDescription> fetch(String url) {
            if (read.size() >= maxDocuments) {
                LOG.debug(
                        "Stopping the catalog walk at {} documents, before {}", maxDocuments, url);
                complete = false;
                return Optional.empty();
            }
            if (System.nanoTime() - deadline >= 0) {
                LOG.debug("Stopping the catalog walk after {}, before {}", maxDuration, url);
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
