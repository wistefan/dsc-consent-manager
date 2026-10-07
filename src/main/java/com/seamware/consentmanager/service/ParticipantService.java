package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

/**
 * The participant directory: what an organization may record about itself and what others may read
 * of it.
 *
 * <p>Every operation here is scoped by an identifier the caller's token asserted, never by one a
 * request body or path carried, so a participant can only ever act on itself.
 */
@Singleton
public class ParticipantService {

    /**
     * Why a second registration of the same identifier is refused; published as the problem detail.
     * Unlike a user, a participant is not registered on first sight of a token, so the caller is
     * told plainly which operation does what it was evidently trying to do.
     */
    private static final String ALREADY_REGISTERED_DETAIL =
            "Participant '%s' is already registered. Update its registration with"
                    + " PUT /participants/me.";

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

    private final ConsentManagerConfiguration.Participants paging;

    public ParticipantService(
            ParticipantRepository participants, ConsentManagerConfiguration.Participants paging) {
        this.participants = participants;
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
     * <p>Deliberately not transactional, for the reason {@link UserService}'s create paths are not:
     * {@code uq_participants_identifier} is what settles concurrent first requests, and reading the
     * table again after the failed insert is impossible inside the aborted transaction.
     *
     * @throws ConflictException {@code 409} when the identifier is already registered
     */
    public Participant register(String identifier, ParticipantRegistration registration) {
        if (participants.existsByIdentifier(identifier)) {
            throw new ConflictException(ALREADY_REGISTERED_DETAIL.formatted(identifier));
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
            if (participants.existsByIdentifier(identifier)) {
                throw new ConflictException(ALREADY_REGISTERED_DETAIL.formatted(identifier));
            }
            throw e;
        }
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

    private static ConflictException conflict(String detail, String identifier) {
        return new ConflictException(detail.formatted(identifier));
    }
}
