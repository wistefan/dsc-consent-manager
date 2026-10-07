package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import io.micronaut.data.exceptions.DataAccessException;
import jakarta.inject.Singleton;

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
}
