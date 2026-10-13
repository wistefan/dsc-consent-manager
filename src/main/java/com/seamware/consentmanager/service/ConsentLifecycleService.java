package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentRepository;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * The moves a consent makes after it exists: who is allowed to make each one, and what it reads
 * back as.
 *
 * <p>Authorisation is against the consent itself rather than against the caller's role, so holding
 * {@code USER} is never on its own enough. Everything transactional lives in {@link ConsentWriter};
 * this bean resolves the record, checks who is asking, and renders the result through {@link
 * ConsentParties}.
 */
@Singleton
public class ConsentLifecycleService {

    /**
     * A move made by the person the consent names is a deliberate act, never an automated sweep.
     */
    private static final ConsentEventTrigger SUBJECT_TRIGGER = ConsentEventTrigger.EXPLICIT;

    private static final String UNKNOWN_CONSENT = "No consent exists with identifier '%s'.";

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
                writer.move(owned.getId(), target, subject.getIdentifier(), SUBJECT_TRIGGER);
        return parties.details(moved);
    }
}
