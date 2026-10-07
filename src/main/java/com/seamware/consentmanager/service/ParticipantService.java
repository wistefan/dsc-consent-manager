package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import io.micronaut.data.exceptions.DataAccessException;
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

    private final ParticipantRepository participants;

    public ParticipantService(ParticipantRepository participants) {
        this.participants = participants;
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
