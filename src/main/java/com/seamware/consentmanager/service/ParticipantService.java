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
     * Why an update that wrote nothing is a conflict rather than a {@code 500}; published as the
     * problem detail. The row is read back after the write, so a vanished row surfaces here, and
     * deregistration is the only thing that removes one.
     */
    private static final String DEREGISTERED_CONCURRENTLY_DETAIL =
            "Participant '%s' was deregistered while its registration was being updated. The update"
                    + " was not applied.";

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
     * <p>A {@code PUT}, so an optional field the body omits is cleared rather than kept. It is also
     * last-writer-wins: {@code current} is the row as it stood when the request was authenticated,
     * and two concurrent updates do not see each other. The row is read back inside the transaction
     * instead of the written entity being returned, so the response is the stored row by
     * construction and a column the database rather than the application owns is never reported
     * stale.
     *
     * @throws ConflictException {@code 409} when the row vanished under the update, which only a
     *     concurrent deregistration does
     */
    @Transactional
    public Participant update(Participant current, ParticipantUpdate update) {
        current.setLegalName(update.legalName());
        current.setSelfDescriptionUri(update.selfDescriptionUri());
        current.setEmail(update.email());
        current.setEndpoints(update.endpoints());
        current.setLegalPerson(update.legalPerson());
        participants.update(current);
        return participants
                .findById(current.getId())
                .orElseThrow(
                        () ->
                                new ConflictException(
                                        DEREGISTERED_CONCURRENTLY_DETAIL.formatted(
                                                current.getIdentifier())));
    }
}
