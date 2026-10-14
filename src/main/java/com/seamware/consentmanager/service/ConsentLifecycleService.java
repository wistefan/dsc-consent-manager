package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.CatalogPrincipal;
import com.seamware.consentmanager.security.ConsentManagerPrincipal;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.UserPrincipal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
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

    /**
     * How a relaying catalog names itself in the audit trail.
     *
     * <p>Issuer and subject both, because {@link ConsentManagerPrincipal#subject()} is unique per
     * issuer only and the trust list admits more than one catalog. The prefix is what keeps a
     * relayed grant from reading as one the subject made here directly.
     */
    private static final String CATALOG_ACTOR = "catalog:%s:%s";

    private static final String SUBJECT_NOT_NAMED =
            "A relayed decision must name the data subject it was taken by in 'userIdentifier'.";

    private static final String PARTICIPANT_CANNOT_RESUME =
            "A participant cannot record a decision; only the data subject or a catalog relaying"
                    + " the subject's decision can.";

    private static final String CONCURRENT_GRANT =
            "Another decision for privacy notice '%s' was recorded concurrently. Re-read the"
                    + " consent before resuming it again.";

    private final ConsentRepository consents;
    private final UserRepository users;
    private final ConsentStateMachine states;
    private final ConsentNoticeGuard noticeGuard;
    private final ConsentWriter writer;
    private final ConsentParties parties;

    public ConsentLifecycleService(
            ConsentRepository consents,
            UserRepository users,
            ConsentStateMachine states,
            ConsentNoticeGuard noticeGuard,
            ConsentWriter writer,
            ConsentParties parties) {
        this.consents = consents;
        this.users = users;
        this.states = states;
        this.noticeGuard = noticeGuard;
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
     * Records that the subject still stands by a consent in force, without moving it.
     *
     * <p>Permitted from {@code GRANTED} only; any other status is {@code 400} naming it. Nothing
     * about the record changes, so the body read back is the consent as it already was and the
     * affirmation shows only in the audit trail.
     */
    @NonNull
    public ConsentDetails reConfirm(@NonNull User subject, @NonNull UUID consentId) {
        Consent owned = ownedBy(subject, consentId);
        return parties.details(writer.affirm(owned.getId(), subject.getIdentifier()));
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
     * Grants a request nobody answered, for the subject or for a catalog relaying their decision.
     *
     * <p>Permitted from {@code PENDING} and {@code DRAFT} only; a consent already decided is {@code
     * 400} naming both statuses. A {@code USER} caller resumes its own consent and {@code
     * userIdentifier} is ignored; a {@code CATALOG} caller must name the subject, which is checked
     * against the addressed consent rather than used to find one, so it chooses neither the subject
     * nor the notice — see ADR-0010. A participant reaches neither branch under the route's
     * {@code @Secured} rule and is refused outright.
     *
     * <p>The notice must still be one that may be consented to: a request can outlive the notice it
     * was opened against, and granting under archived terms is refused exactly as {@code POST
     * /consents} refuses it. The stored snapshot is kept — a resume records a decision taken
     * elsewhere rather than electing scope here.
     *
     * <p>The record's own status is checked before the notice is, so a consent already decided is
     * refused by naming both statuses rather than by naming something about a notice that was never
     * the reason. {@link ConsentWriter#resume} checks it again under the row lock, which is the
     * check that settles a race; this one only chooses which refusal the caller is told about.
     */
    @NonNull
    public ConsentDetails resume(
            @NonNull ConsentManagerPrincipal caller,
            @NonNull UUID consentId,
            @Nullable String userIdentifier) {
        Resumable resumable =
                switch (caller) {
                    case UserPrincipal subject ->
                            new Resumable(
                                    ownedBy(subject.user(), consentId),
                                    subject.user().getIdentifier());
                    case CatalogPrincipal catalog ->
                            new Resumable(
                                    relayed(consentId, userIdentifier),
                                    CATALOG_ACTOR.formatted(catalog.issuer(), catalog.subject()));
                    case ParticipantPrincipal ignored ->
                            throw new ForbiddenException(PARTICIPANT_CANNOT_RESUME);
                };
        Consent consent = resumable.consent();
        states.requireTransition(consent.getStatus(), ConsentStatus.GRANTED);
        noticeGuard.consentable(consent.getPrivacyNoticeId());
        return parties.details(resumed(consent, resumable.actor()));
    }

    /** A consent a resume may move, and the actor its event records. */
    private record Resumable(Consent consent, String actor) {}

    /**
     * The consent a catalog's relayed decision addresses, once the subject it names is the one the
     * consent names.
     *
     * <p>A consent that does not exist and one belonging to another subject are the same {@code
     * 404}, so a catalog cannot learn whose consent an identifier addresses, let alone grant it.
     */
    private Consent relayed(UUID consentId, @Nullable String userIdentifier) {
        if (userIdentifier == null || userIdentifier.isBlank()) {
            throw new BadRequestException(SUBJECT_NOT_NAMED);
        }
        Consent consent =
                consents.findById(consentId)
                        .orElseThrow(
                                () -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));
        return users.findByIdentifier(userIdentifier)
                .filter(subject -> subject.getId().equals(consent.getUserId()))
                .map(subject -> consent)
                .orElseThrow(() -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));
    }

    /** The resumed record, with a race on the one-grant-per-notice index answered as 409. */
    private RecordedConsent resumed(Consent consent, String actor) {
        try {
            return writer.resume(consent.getId(), actor);
        } catch (DataAccessException e) {
            if (!UniqueViolation.rejected(e)) {
                throw e;
            }
            throw new ConflictException(
                    CONCURRENT_GRANT.formatted(consent.getPrivacyNoticeId()), e);
        }
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
     * Resolves a consent the subject owns.
     *
     * <p>A consent that does not exist and one belonging to somebody else are the same {@code 404},
     * so the subject's routes answer nothing about which identifiers are real.
     */
    private Consent ownedBy(User subject, UUID consentId) {
        return consents.findById(consentId)
                .filter(consent -> consent.getUserId().equals(subject.getId()))
                .orElseThrow(() -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));
    }

    /** Moves the subject's own consent to {@code target}, in the subject's name. */
    private ConsentDetails move(User subject, UUID consentId, ConsentStatus target) {
        Consent owned = ownedBy(subject, consentId);
        RecordedConsent moved =
                writer.move(owned.getId(), target, subject.getIdentifier(), EXPLICIT_TRIGGER);
        return parties.details(moved);
    }
}
