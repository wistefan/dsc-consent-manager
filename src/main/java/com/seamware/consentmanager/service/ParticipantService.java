package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.notification.NotificationOutboxRepository;
import com.seamware.consentmanager.notification.ParticipantNotificationService;
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
 * The participant directory: registration, self-service updates, lookup and deregistration. Writes
 * are scoped by the identifier the caller's token asserted, never by one from the request.
 */
@Singleton
public class ParticipantService {

    private static final Logger LOG = LoggerFactory.getLogger(ParticipantService.class);

    /** Problem detail for a second registration; points the caller at the update route. */
    private static final String ALREADY_REGISTERED_DETAIL =
            "Participant '%s' is already registered. Update its registration with"
                    + " PUT /participants/me.";

    /** Problem detail for a deregistration blocked by granted consents, which it names. */
    private static final String GRANTED_CONSENTS_DETAIL =
            "Participant '%s' cannot be deregistered while consents naming it are still GRANTED:"
                    + " %s. Only the users who granted them can revoke them.";

    /** Blocking consent ids the refusal names before summarising the rest. */
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

    /** Trigger on deregistration events; not {@code EXPLICIT}, as nobody acted on the consent. */
    private static final ConsentEventTrigger DEREGISTRATION_TRIGGER = ConsentEventTrigger.SYSTEM;

    /** Key of the reason carried in a deregistration event's details. */
    private static final String DEREGISTRATION_DETAIL_KEY = "reason";

    /** Why a deregistration event closed the consent, recorded in its details. */
    private static final String DEREGISTRATION_DETAIL_REASON = "participant-deregistered";

    /** Problem detail for an update whose row a concurrent deregistration removed. */
    private static final String DEREGISTERED_CONCURRENTLY_DETAIL =
            "Participant '%s' was deregistered while its registration was being updated. The update"
                    + " was not applied.";

    /** Problem detail for an update to a retained, deregistered row. */
    private static final String DEREGISTERED_DETAIL =
            "Participant '%s' is deregistered. Its registration can no longer be updated.";

    /** Problem detail for deregistering a record that is already deregistered or gone. */
    private static final String DEREGISTERED_ALREADY_DETAIL =
            "Participant '%s' is already deregistered. Register it again with POST /participants"
                    + " before deregistering it anew.";

    /** Problem detail for an identifier naming nobody. */
    private static final String UNKNOWN_DETAIL = "No participant is registered as '%s'.";

    /** Directory order; unique, so pages are stable while the directory does not change. */
    private static final String DIRECTORY_ORDER_PROPERTY = "identifier";

    /** First page, applied when the caller names none. */
    private static final int FIRST_PAGE = 0;

    private final ParticipantRepository participants;

    private final ConsentRepository consents;

    private final ConsentEventRepository consentEvents;

    private final ConsentFactory consentFactory;

    private final PrivacyNoticeRepository notices;

    private final UserParticipantRepository links;

    private final ConsentManagerConfiguration.Participants paging;

    private final ParticipantNotificationService participantNotifications;

    private final NotificationOutboxRepository outbox;

    public ParticipantService(
            ParticipantRepository participants,
            ConsentRepository consents,
            ConsentEventRepository consentEvents,
            ConsentFactory consentFactory,
            PrivacyNoticeRepository notices,
            UserParticipantRepository links,
            ConsentManagerConfiguration.Participants paging,
            ParticipantNotificationService participantNotifications,
            NotificationOutboxRepository outbox) {
        this.participants = participants;
        this.consents = consents;
        this.consentEvents = consentEvents;
        this.consentFactory = consentFactory;
        this.notices = notices;
        this.links = links;
        this.paging = paging;
        this.participantNotifications = participantNotifications;
        this.outbox = outbox;
    }

    /**
     * One page of active participants by identifier; {@code size} is clamped to the configured max.
     * An exact {@code identifier} filter also resolves a deregistered participant.
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
     * The participant an identifier names, deregistered or not.
     *
     * @throws NotFoundException when no participant carries the identifier
     */
    public Participant find(String identifier) {
        return participants
                .findByIdentifier(identifier)
                .orElseThrow(() -> new NotFoundException(UNKNOWN_DETAIL.formatted(identifier)));
    }

    /** The requested size (or the default) bounded by the configured ceiling. */
    private int applicableSize(@Nullable Integer size) {
        return Math.min(size == null ? paging.getPageDefaultSize() : size, paging.getPageMaxSize());
    }

    /**
     * Records an organization under the identifier its token asserts, reactivating a deregistered
     * record. See ADR-0008.
     *
     * <p>Not transactional: {@code uq_participants_identifier} settles concurrent first requests,
     * and the re-read after the failed insert is impossible inside an aborted transaction.
     *
     * @throws ConflictException when the identifier is already registered and active
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
            // Lost the race; no row means the insert failed for another reason.
            return participants
                    .findByIdentifier(identifier)
                    .map(stored -> reactivate(stored, registration))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Restores a deregistered record from the re-registration body.
     *
     * <p>Unlocked by design: {@code stored} was just read and every mutable column comes from the
     * body, so a racing write is simply superseded by this full replacement.
     *
     * @throws ConflictException when the record is active
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
     * Replaces a participant's self-description ({@code PUT}: omitted optional fields are cleared).
     *
     * <p>Re-reads and locks the row rather than persisting the stale authenticated {@code current},
     * so concurrent writers serialize and one racing a deregistration is refused, not resurrected.
     * Returns the row as re-read after the write.
     *
     * @throws ConflictException when the row was deregistered or removed under the update
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
     * Deregisters the participant in one all-or-nothing transaction and reports what it entailed.
     * See ADR-0008.
     *
     * <p>Transactional, unlike the create paths: nothing here inserts-and-catches, and a partial
     * deregistration is worse than none. The row is re-read and locked as in {@link #update}, which
     * also serializes concurrent deregistrations, so the repeat refusal is reliable.
     *
     * @throws ConflictException while any consent naming the participant is granted, or when the
     *     record is already deregistered or was removed under the call
     */
    @Transactional
    public DeregistrationResult deregister(Participant current) {
        Participant participant = lockActive(current);
        UUID participantId = participant.getId();
        // Lock before counting grants, so a concurrent grant is seen and refused, not skipped.
        List<Consent> unanswered =
                consents.findNamingParticipantForUpdate(participantId, UNANSWERED_STATUSES);
        refuseWhileGranted(participant, participantId);

        List<ConsentMove> terminated = terminate(unanswered, participantId);
        // Its endpoints are cleared below, so nothing still queued for it can be delivered.
        int notificationsAbandoned = outbox.abandonForParticipant(participantId);
        int linksRemoved = links.deleteByIdParticipantId(participantId);
        int noticesArchived = archiveNotices(participantId);
        int consentsRetained = consents.countByParticipant(participantId);
        Instant deregisteredAt = deleteOrRetain(participant, consentsRetained);

        LOG.info(
                "Deregistered participant {}: terminated {}, retained {}, archived {}, unlinked {},"
                        + " notifications abandoned {}, record {}",
                participant.getIdentifier(),
                terminated.size(),
                consentsRetained,
                noticesArchived,
                linksRemoved,
                notificationsAbandoned,
                deregisteredAt == null ? "deleted" : "retained");
        return new DeregistrationResult(
                deregisteredAt,
                terminated,
                consentsRetained,
                noticesArchived,
                linksRemoved,
                notificationsAbandoned);
    }

    /**
     * The caller's current row, locked for the rest of the transaction.
     *
     * @throws ConflictException when the record is already deregistered or gone
     */
    private Participant lockActive(Participant current) {
        Participant stored =
                participants
                        .findByIdForUpdate(current.getId())
                        .orElseThrow(
                                () ->
                                        conflict(
                                                DEREGISTERED_ALREADY_DETAIL,
                                                current.getIdentifier()));
        if (stored.getDeregisteredAt() != null) {
            throw conflict(DEREGISTERED_ALREADY_DETAIL, stored.getIdentifier());
        }
        return stored;
    }

    /** Refuses the deregistration while any consent is granted, naming at most the capped few. */
    private void refuseWhileGranted(Participant participant, UUID participantId) {
        int blocking = consents.countByParticipantAndStatus(participantId, ConsentStatus.GRANTED);
        if (blocking > 0) {
            List<UUID> listed =
                    consents.findIdsByParticipantAndStatus(
                            participantId, ConsentStatus.GRANTED, MAX_REPORTED_BLOCKING_CONSENTS);
            throw new ConflictException(
                    GRANTED_CONSENTS_DETAIL.formatted(
                            participant.getIdentifier(), blockingConsents(listed, blocking)));
        }
    }

    /** The items in encounter order, keeping the first of each id. */
    private static <T> List<T> distinctById(Stream<T> items, Function<T, UUID> id) {
        Map<UUID, T> byId = new LinkedHashMap<>();
        items.forEach(item -> byId.putIfAbsent(id.apply(item), item));
        return List.copyOf(byId.values());
    }

    /** The listed blocking ids, plus a count of the ones not listed. */
    private static String blockingConsents(List<UUID> listed, int blocking) {
        String names =
                listed.stream()
                        .map(UUID::toString)
                        .collect(Collectors.joining(BLOCKING_CONSENT_SEPARATOR));
        int omitted = blocking - listed.size();
        return omitted <= 0
                ? names
                : names + BLOCKING_CONSENT_SEPARATOR + MORE_BLOCKING_CONSENTS.formatted(omitted);
    }

    /**
     * Terminates the locked unanswered consents with an event each, at {@code participantId}'s
     * request; returns each move and its prior status. Not reported here, as only a committed move
     * may be counted.
     */
    private List<ConsentMove> terminate(List<Consent> unanswered, UUID participantId) {
        List<ConsentMove> closed =
                unanswered.stream()
                        .map(
                                consent -> {
                                    ConsentStatus held = consent.getStatus();
                                    return new ConsentMove(held, terminated(consent));
                                })
                        .toList();
        if (!closed.isEmpty()) {
            consents.updateAll(closed.stream().map(move -> move.recorded().consent()).toList());
            consentEvents.saveAll(closed.stream().map(move -> move.recorded().event()).toList());
            // An unanswered offer grants nothing, so this queues no row; it keeps the hook total.
            closed.forEach(
                    move ->
                            participantNotifications.enqueue(
                                    move.recorded(), move.held(), participantId));
        }
        return closed;
    }

    /** The consent terminated by deregistration, via {@link ConsentFactory} and its table. */
    private RecordedConsent terminated(Consent consent) {
        return consentFactory.transition(
                consent,
                ConsentStatus.TERMINATED,
                DEREGISTRATION_ACTOR,
                DEREGISTRATION_TRIGGER,
                Map.of(DEREGISTRATION_DETAIL_KEY, DEREGISTRATION_DETAIL_REASON));
    }

    /** Archives live notices naming the participant on either side; returns how many. */
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
     * Deletes an unreferenced record (returning null), else marks it deregistered, clears {@code
     * email} and {@code endpoints}, and returns the mark. See ADR-0008.
     */
    @Nullable
    private Instant deleteOrRetain(Participant participant, int consentsRetained) {
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

    /** Now at PostgreSQL's microsecond resolution, so reported stamps match the stored row. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static ConflictException conflict(String detail, String identifier) {
        return new ConflictException(detail.formatted(identifier));
    }
}
