package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The participant directory: what an organization may record about itself and what others may read
 * of it.
 *
 * <p>Every operation here is scoped by an identifier the caller's token asserted, never by one a
 * request body or path carried, so a participant can only ever act on itself.
 */
@Singleton
public class ParticipantService {

    private static final Logger LOG = LoggerFactory.getLogger(ParticipantService.class);

    /**
     * Why a second registration of the same identifier is refused; published as the problem detail.
     * Unlike a user, a participant is not registered on first sight of a token, so the caller is
     * told plainly which operation does what it was evidently trying to do.
     */
    private static final String ALREADY_REGISTERED_DETAIL =
            "Participant '%s' is already registered. Update its registration with"
                    + " PUT /participants/me.";

    /**
     * Why a deregistration is refused while a user's permission still stands; published as the
     * problem detail. It names the blocking consents because the remedy is not the participant's:
     * only the users who granted them can revoke them.
     */
    private static final String GRANTED_CONSENTS_DETAIL =
            "Participant '%s' cannot be deregistered while consents naming it are still GRANTED:"
                    + " %s. Only the users who granted them can revoke them.";

    /**
     * How many blocking consent ids the refusal names before summarising the rest, so a long-lived
     * participant's refusal stays a readable problem detail rather than a dump.
     */
    private static final int MAX_REPORTED_BLOCKING_CONSENTS = 20;

    /** Appended to the blocking-consent list when it was truncated. */
    private static final String MORE_BLOCKING_CONSENTS = "and %d more";

    /** Separator between the blocking consent ids in the refusal. */
    private static final String BLOCKING_CONSENT_SEPARATOR = ", ";

    /** Consent statuses that are an unanswered offer rather than a decision either way. */
    private static final Set<ConsentStatus> UNANSWERED_STATUSES =
            Set.of(ConsentStatus.PENDING, ConsentStatus.DRAFT);

    /** Actor recorded on the events deregistration appends; no person triggered them. */
    private static final String DEREGISTRATION_ACTOR = "consent-manager";

    /**
     * Event type recorded on the events deregistration appends.
     *
     * <p>Not {@link ConsentEvent#DEFAULT_EVENT_TYPE}: the counterparty left, so the consent was not
     * closed by anyone acting on it.
     */
    private static final String DEREGISTRATION_EVENT_TYPE = "system";

    /** Key of the reason carried in a deregistration event's details. */
    private static final String DEREGISTRATION_DETAIL_KEY = "reason";

    /** Why a deregistration event closed the consent, recorded in its details. */
    private static final String DEREGISTRATION_DETAIL_REASON = "participant-deregistered";

    /**
     * Why an update whose row is gone is a conflict rather than a {@code 500}; published as the
     * problem detail. The row is read back before the write, and deregistration is the only thing
     * that removes one.
     */
    private static final String DEREGISTERED_CONCURRENTLY_DETAIL =
            "Participant '%s' was deregistered while its registration was being updated. The update"
                    + " was not applied.";

    /**
     * Why an update to a retained but deregistered row is refused; published as the problem detail.
     * Such a row is kept only so the consents and privacy notices naming it stay legible, so it may
     * not be edited back into a live registration.
     */
    private static final String DEREGISTERED_DETAIL =
            "Participant '%s' is deregistered. Its registration can no longer be updated.";

    /** Why an identifier naming nobody is a {@code 404}; published as the problem detail. */
    private static final String UNKNOWN_DETAIL = "No participant is registered as '%s'.";

    /**
     * Column the directory is ordered by. Unique, so a record keeps its place across page requests
     * while the directory itself does not change - an order on a non-unique column could not
     * promise even that. Concurrent registrations still move records across boundaries; the listing
     * is not served from a snapshot.
     */
    private static final String DIRECTORY_ORDER_PROPERTY = "identifier";

    /** First page, applied when the caller names none. */
    private static final int FIRST_PAGE = 0;

    private final ParticipantRepository participants;

    private final ConsentRepository consents;

    private final ConsentEventRepository consentEvents;

    private final PrivacyNoticeRepository notices;

    private final UserParticipantRepository links;

    private final ConsentManagerConfiguration.Participants paging;

    public ParticipantService(
            ParticipantRepository participants,
            ConsentRepository consents,
            ConsentEventRepository consentEvents,
            PrivacyNoticeRepository notices,
            UserParticipantRepository links,
            ConsentManagerConfiguration.Participants paging) {
        this.participants = participants;
        this.consents = consents;
        this.consentEvents = consentEvents;
        this.notices = notices;
        this.links = links;
        this.paging = paging;
    }

    /**
     * One page of the directory, ordered by identifier and excluding deregistered participants.
     *
     * <p>{@code size} is clamped to the configured ceiling rather than refused, so a caller asking
     * for more than a deployment serves gets a smaller page instead of an error; the returned
     * page's size reports what was applied. An {@code identifier} narrows the page to that exact,
     * case-sensitive value and then resolves a deregistered participant too - it is the dependable
     * way to reach one whose identifier contains a {@code /}.
     */
    public Page<Participant> list(
            @Nullable Integer page, @Nullable Integer size, @Nullable String identifier) {
        Pageable pageable =
                Pageable.from(
                        page == null ? FIRST_PAGE : page,
                        applicableSize(size),
                        Sort.of(Sort.Order.asc(DIRECTORY_ORDER_PROPERTY)));
        return identifier == null
                ? participants.findByDeregisteredAtIsNull(pageable)
                : participants.findByIdentifier(identifier, pageable);
    }

    /**
     * The participant an identifier names, deregistered or not - a retained record still has to
     * resolve, or a consent naming it would name nobody a reader could identify.
     *
     * @throws NotFoundException {@code 404} when no participant carries the identifier
     */
    public Participant find(String identifier) {
        return participants
                .findByIdentifier(identifier)
                .orElseThrow(() -> new NotFoundException(UNKNOWN_DETAIL.formatted(identifier)));
    }

    /**
     * The requested size bounded by the configured ceiling, or the configured default when none.
     */
    private int applicableSize(@Nullable Integer size) {
        return Math.min(size == null ? paging.getPageDefaultSize() : size, paging.getPageMaxSize());
    }

    /**
     * Records an organization's self-description under the identifier its token asserts.
     *
     * <p>An identifier whose record was deregistered is <em>reactivated</em> rather than refused:
     * {@code uq_participants_identifier} makes a second row impossible, so refusing would bar the
     * organization from the dataspace permanently on the strength of one {@code DELETE}. Only an
     * active record is a conflict. See ADR 0008.
     *
     * <p>Deliberately not transactional, for the reason {@link UserService}'s create paths are not:
     * {@code uq_participants_identifier} is what settles concurrent first requests, and reading the
     * table again after the failed insert is impossible inside the aborted transaction.
     *
     * @throws ConflictException {@code 409} when the identifier is already registered and active
     */
    public Participant register(String identifier, ParticipantRegistration registration) {
        Optional<Participant> existing = participants.findByIdentifier(identifier);
        if (existing.isPresent()) {
            return reactivate(existing.get(), registration);
        }
        try {
            return participants.save(
                    new Participant(
                            identifier,
                            registration.legalName(),
                            registration.selfDescriptionUri(),
                            registration.email(),
                            registration.endpoints(),
                            registration.legalPerson()));
        } catch (DataAccessException e) {
            // Another request registered first; no row means the insert failed for another reason.
            return participants
                    .findByIdentifier(identifier)
                    .map(stored -> reactivate(stored, registration))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Brings a deregistered record back under the body it was re-registered with, mutable fields
     * and all.
     *
     * @throws ConflictException {@code 409} when the record is active, which is a plain re-register
     */
    private Participant reactivate(Participant stored, ParticipantRegistration registration) {
        if (stored.getDeregisteredAt() == null) {
            throw new ConflictException(
                    ALREADY_REGISTERED_DETAIL.formatted(stored.getIdentifier()));
        }
        stored.setDeregisteredAt(null);
        stored.setLegalName(registration.legalName());
        stored.setSelfDescriptionUri(registration.selfDescriptionUri());
        stored.setEmail(registration.email());
        stored.setEndpoints(registration.endpoints());
        stored.setLegalPerson(registration.legalPerson());
        participants.update(stored);
        return stored;
    }

    /**
     * Replaces a registered participant's self-description, leaving its identifier alone.
     *
     * <p>A {@code PUT}, so an optional field the body omits is cleared rather than kept. The row is
     * re-read and locked inside the transaction rather than written back from {@code current}: that
     * snapshot is the row as it stood when the request was authenticated, and persisting it whole
     * would write every column it carries - {@code deregistered_at} among them - from a stale read.
     * Concurrent writers therefore serialize on the row, the later update wins outright, and one
     * racing a deregistration is refused instead of resurrecting the row. The row is read once more
     * after the write, so the response is the stored row by construction and a column the database
     * rather than the application owns is never reported stale.
     *
     * @throws ConflictException {@code 409} when the row was deregistered or removed under the
     *     update, which leaves it unapplied
     */
    @Transactional
    public Participant update(Participant current, ParticipantUpdate update) {
        Participant stored =
                participants
                        .findByIdForUpdate(current.getId())
                        .orElseThrow(
                                () ->
                                        conflict(
                                                DEREGISTERED_CONCURRENTLY_DETAIL,
                                                current.getIdentifier()));
        if (stored.getDeregisteredAt() != null) {
            throw conflict(DEREGISTERED_DETAIL, stored.getIdentifier());
        }
        stored.setLegalName(update.legalName());
        stored.setSelfDescriptionUri(update.selfDescriptionUri());
        stored.setEmail(update.email());
        stored.setEndpoints(update.endpoints());
        stored.setLegalPerson(update.legalPerson());
        participants.update(stored);
        return participants
                .findById(stored.getId())
                .orElseThrow(
                        () -> conflict(DEREGISTERED_CONCURRENTLY_DETAIL, stored.getIdentifier()));
    }

    /**
     * Removes the organization from the dataspace and reports what that entailed, in one
     * all-or-nothing transaction.
     *
     * <p>The cascade is ADR 0008 in code: a consent still {@code GRANTED} refuses the whole
     * operation, because that permission is the user's to withdraw and not the participant's; the
     * unanswered {@code PENDING} and {@code DRAFT} ones are terminated so no later lifecycle
     * operation can advance an offer whose counterparty has left; every affiliation goes and every
     * live privacy notice is archived. The {@code participants} row is then deleted when nothing
     * refers to it and otherwise retained, marked and mostly intact - its identity fields are kept
     * deliberately, so the consents still naming it stay legible, and only the contact {@code
     * email} and the {@code endpoints} are cleared.
     *
     * <p>{@code @Transactional} where the create paths are not: a half-deregistered participant -
     * unlinked but still advertising endpoints, or archived but still answerable - is worse than an
     * undeparted one, and nothing here inserts-and-catches.
     *
     * <p>The {@code participant} argument is mutated in place on the retain branch, so a caller
     * holding the pre-deregistration row sees the marked state afterwards.
     *
     * @throws ConflictException {@code 409} while any consent naming the participant is granted
     */
    @Transactional
    public DeregistrationResult deregister(Participant participant) {
        UUID participantId = participant.getId();
        refuseWhileGranted(participant, participantId);

        int consentsTerminated = terminateUnanswered(participantId);
        long linksRemoved = links.deleteByIdParticipantId(participantId);
        int noticesArchived = archiveNotices(participantId);
        long consentsRetained = consents.countByParticipant(participantId);
        Instant deregisteredAt = deleteOrRetain(participant, consentsRetained);

        LOG.info(
                "Deregistered participant {}: terminated {}, retained {}, archived {}, unlinked {},"
                        + " record {}",
                participant.getIdentifier(),
                consentsTerminated,
                consentsRetained,
                noticesArchived,
                linksRemoved,
                deregisteredAt == null ? "deleted" : "retained");
        return new DeregistrationResult(
                deregisteredAt,
                consentsTerminated,
                consentsRetained,
                noticesArchived,
                linksRemoved);
    }

    /** Refuses the deregistration, naming the consents, while any of them is still granted. */
    private void refuseWhileGranted(Participant participant, UUID participantId) {
        List<Consent> granted = naming(participantId, Set.of(ConsentStatus.GRANTED));
        if (!granted.isEmpty()) {
            throw new ConflictException(
                    GRANTED_CONSENTS_DETAIL.formatted(
                            participant.getIdentifier(), blockingConsents(granted)));
        }
    }

    /**
     * Every consent in one of the given statuses that names the participant on either side.
     *
     * <p>The two sides are read separately so the derived queries can bind the status enums; a
     * self-dealing consent comes back from both and is de-duplicated by id here.
     */
    private List<Consent> naming(UUID participantId, Set<ConsentStatus> statuses) {
        return distinctById(
                Stream.concat(
                        consents.findByProviderIdAndStatusIn(participantId, statuses).stream(),
                        consents.findByConsumerIdAndStatusIn(participantId, statuses).stream()),
                Consent::getId);
    }

    /** The items in encounter order, keeping the first of each id. */
    private static <T> List<T> distinctById(Stream<T> items, Function<T, UUID> id) {
        Map<UUID, T> byId = new LinkedHashMap<>();
        items.forEach(item -> byId.putIfAbsent(id.apply(item), item));
        return List.copyOf(byId.values());
    }

    /** The blocking consent ids, in a bounded, readable list. */
    private static String blockingConsents(List<Consent> granted) {
        String listed =
                granted.stream()
                        .map(Consent::getId)
                        .sorted(Comparator.comparing(UUID::toString))
                        .limit(MAX_REPORTED_BLOCKING_CONSENTS)
                        .map(UUID::toString)
                        .collect(Collectors.joining(BLOCKING_CONSENT_SEPARATOR));
        int omitted = granted.size() - MAX_REPORTED_BLOCKING_CONSENTS;
        return omitted <= 0
                ? listed
                : listed + BLOCKING_CONSENT_SEPARATOR + MORE_BLOCKING_CONSENTS.formatted(omitted);
    }

    /**
     * Moves every unanswered consent naming the participant to {@code TERMINATED}, appending a
     * {@code CONSENT_TERMINATED} event to each trail, and reports how many moved.
     */
    private int terminateUnanswered(UUID participantId) {
        List<Consent> closed =
                naming(participantId, UNANSWERED_STATUSES).stream()
                        .map(ParticipantService::terminated)
                        .toList();
        if (closed.isEmpty()) {
            return 0;
        }
        consents.updateAll(closed);
        consentEvents.saveAll(closed.stream().map(ParticipantService::terminationEvent).toList());
        return closed.size();
    }

    /** The same consent, moved to {@code TERMINATED}. */
    private static Consent terminated(Consent consent) {
        consent.updateStatus(ConsentStatus.TERMINATED);
        return consent;
    }

    /**
     * The trail entry saying a deregistration closed this consent, naming the service rather than a
     * person.
     */
    private static ConsentEvent terminationEvent(Consent consent) {
        return new ConsentEvent(
                consent.getId(),
                ConsentEventState.CONSENT_TERMINATED,
                DEREGISTRATION_EVENT_TYPE,
                DEREGISTRATION_ACTOR,
                Instant.now(),
                Map.of(DEREGISTRATION_DETAIL_KEY, DEREGISTRATION_DETAIL_REASON));
    }

    /**
     * Archives every live privacy notice naming the participant on either side and reports how
     * many. Already-archived notices keep their original timestamp.
     */
    private int archiveNotices(UUID participantId) {
        List<PrivacyNotice> live =
                distinctById(
                        Stream.concat(
                                notices.findByProviderIdAndArchivedAtIsNull(participantId).stream(),
                                notices
                                        .findByConsumerIdAndArchivedAtIsNull(participantId)
                                        .stream()),
                        PrivacyNotice::getId);
        if (live.isEmpty()) {
            return 0;
        }
        Instant archivedAt = now();
        live.forEach(notice -> notice.setArchivedAt(archivedAt));
        notices.updateAll(live);
        return live.size();
    }

    /**
     * Deletes the record when nothing refers to it, otherwise marks it deregistered and returns the
     * instant it was marked at.
     *
     * <p>Deleting is only possible for a participant that never transacted: both participant
     * foreign keys on {@code consents} and both on {@code privacy_notices} are {@code ON DELETE
     * RESTRICT}, and archiving a notice does not release its reference. The retained record keeps
     * its identifier, legal name, self-description and legal person - an organization has no
     * erasure right and a scrubbed one could be recovered by nobody - and loses the contact {@code
     * email}, which is a natural person's, and the {@code endpoints}, because a departed
     * participant must not keep advertising a callback.
     */
    @Nullable
    private Instant deleteOrRetain(Participant participant, long consentsRetained) {
        UUID participantId = participant.getId();
        if (consentsRetained == 0 && notices.countByParticipant(participantId) == 0) {
            participants.delete(participant);
            return null;
        }
        Instant deregisteredAt = now();
        participant.setDeregisteredAt(deregisteredAt);
        participant.setEmail(null);
        participant.setEndpoints(Map.of());
        participants.update(participant);
        return deregisteredAt;
    }

    /**
     * The clock at the resolution PostgreSQL keeps, so a stamp reported back matches the stored row
     * digit for digit - the same reason {@code MicrosecondDateTimeProvider} exists.
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static ConflictException conflict(String detail, String identifier) {
        return new ConflictException(detail.formatted(identifier));
    }
}
