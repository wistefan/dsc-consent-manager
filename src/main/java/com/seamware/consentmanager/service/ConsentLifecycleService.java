package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentRepository;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * The moves a consent makes after it exists: who is allowed to make each one, and what it reads
 * back as.
 *
 * <p>Authorisation is against the consent itself rather than against the caller's role, so the role
 * a token carries is never on its own enough. Everything transactional lives in {@link
 * ConsentWriter}; this bean resolves the record, checks who is asking, and renders the result
 * through {@link ConsentParties}.
 */
@Singleton
public class ConsentLifecycleService {

    /** Every move served here is asked for by an identified caller, never by an automated sweep. */
    private static final ConsentEventTrigger EXPLICIT_TRIGGER = ConsentEventTrigger.EXPLICIT;

    private static final String UNKNOWN_CONSENT = "No consent exists with identifier '%s'.";

    private static final String NOT_A_PARTY =
            "Participant '%s' is named by consent '%s' as neither provider nor consumer, so it"
                    + " cannot terminate it.";

    private final ConsentRepository consents;
    private final ConsentWriter writer;
    private final ConsentParties parties;

    public ConsentLifecycleService(
            ConsentRepository consents, ConsentWriter writer, ConsentParties parties) {
        this.consents = consents;
        this.writer = writer;
        this.parties = parties;
    }

    /**
     * Withdraws a consent the subject has given.
     *
     * <p>Permitted from {@code GRANTED} only; any other status is {@code 400} naming both it and
     * the move attempted.
     */
    @NonNull
    public ConsentDetails revoke(@NonNull User subject, @NonNull UUID consentId) {
        return move(subject, consentId, ConsentStatus.REVOKED);
    }

    /**
     * Declines an unanswered request on the subject's behalf.
     *
     * <p>Permitted from {@code PENDING} and {@code DRAFT} only; a consent already decided, {@code
     * GRANTED} included, is {@code 400} naming both statuses.
     */
    @NonNull
    public ConsentDetails refuse(@NonNull User subject, @NonNull UUID consentId) {
        return move(subject, consentId, ConsentStatus.REFUSED);
    }

    /**
     * Ends an arrangement from the organisational side, in the name of a participant the consent
     * names as provider or as consumer.
     *
     * <p>A participant that is party to neither side is {@code 403} rather than the {@code 404} a
     * foreign subject gets: it may legitimately know the consent exists through its own records, so
     * there is nothing to conceal, only standing to refuse. Permitted from {@code GRANTED} and
     * equally from the unanswered statuses, because a party may end an arrangement before the
     * subject has answered; a terminal status is {@code 400} naming both it and the move attempted.
     */
    @NonNull
    public ConsentDetails terminate(@NonNull Participant caller, @NonNull UUID consentId) {
        Consent consent =
                consents.findById(consentId)
                        .orElseThrow(
                                () -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));
        requireParty(caller, consent);
        RecordedConsent moved =
                writer.move(
                        consent.getId(),
                        ConsentStatus.TERMINATED,
                        caller.getIdentifier(),
                        EXPLICIT_TRIGGER);
        return parties.details(moved);
    }

    /**
     * Refuses a caller the consent names on neither side.
     *
     * <p>Both parties may end it: the provider holds the terms, and the consumer is where the data
     * would flow, so either has an arrangement to end. Anyone else has none.
     */
    private static void requireParty(Participant caller, Consent consent) {
        if (!caller.getId().equals(consent.getProviderId())
                && !caller.getId().equals(consent.getConsumerId())) {
            throw new ForbiddenException(
                    NOT_A_PARTY.formatted(caller.getIdentifier(), consent.getId()));
        }
    }

    /**
     * Moves the subject's own consent to {@code target}, in the subject's name.
     *
     * <p>A consent that does not exist and one belonging to somebody else are the same {@code 404},
     * so the route answers nothing about which identifiers are real.
     */
    private ConsentDetails move(User subject, UUID consentId, ConsentStatus target) {
        Consent owned =
                consents.findById(consentId)
                        .filter(consent -> consent.getUserId().equals(subject.getId()))
                        .orElseThrow(
                                () -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));
        RecordedConsent moved =
                writer.move(owned.getId(), target, subject.getIdentifier(), EXPLICIT_TRIGGER);
        return parties.details(moved);
    }
}
