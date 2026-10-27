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
import com.seamware.consentmanager.notification.UserNotificationService;
import com.seamware.consentmanager.observability.FailureSummary;
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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The moves a consent makes after it exists, and who may make each one.
 *
 * <p>Authorisation is against the consent itself, so a token's role is never enough on its own. Not
 * transactional: writes go through {@link ConsentWriter} and races are caught here as 409.
 */
@Singleton
public class ConsentLifecycleService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentLifecycleService.class);

    private static final String NOTIFICATION_FAILED =
            "Consent {} was terminated, but notifying its subject failed: {}";

    private static final String SUBJECT_MISSING =
            "Consent {} was terminated, but its subject {} no longer exists to be notified";

    /** Every move served here is asked for by an identified caller, never by an automated sweep. */
    private static final ConsentEventTrigger EXPLICIT_TRIGGER = ConsentEventTrigger.EXPLICIT;

    private static final String UNKNOWN_CONSENT = "No consent exists with identifier '%s'.";

    private static final String NOT_A_PARTY =
            "Participant '%s' is named by consent '%s' as neither provider nor consumer, so it"
                    + " cannot terminate it.";

    /** A relaying catalog's audit actor; includes the issuer, as subject is unique per issuer. */
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
    private final ConsentReporter reporter;
    private final UserNotificationService notifications;

    public ConsentLifecycleService(
            ConsentRepository consents,
            UserRepository users,
            ConsentStateMachine states,
            ConsentNoticeGuard noticeGuard,
            ConsentWriter writer,
            ConsentReporter reporter,
            UserNotificationService notifications) {
        this.consents = consents;
        this.users = users;
        this.states = states;
        this.noticeGuard = noticeGuard;
        this.writer = writer;
        this.reporter = reporter;
        this.notifications = notifications;
    }

    /** Withdraws a consent the subject has given; from {@code GRANTED} only, else {@code 400}. */
    @NonNull
    public ConsentDetails revoke(@NonNull User subject, @NonNull UUID consentId) {
        return move(subject, consentId, ConsentStatus.REVOKED);
    }

    /** Declines an unanswered request; from PENDING or DRAFT only, else {@code 400}. */
    @NonNull
    public ConsentDetails refuse(@NonNull User subject, @NonNull UUID consentId) {
        return move(subject, consentId, ConsentStatus.REFUSED);
    }

    /**
     * Records that the subject still stands by a {@code GRANTED} consent, else {@code 400}. The
     * record is unchanged; the affirmation shows only in the audit trail.
     */
    @NonNull
    public ConsentDetails reConfirm(@NonNull User subject, @NonNull UUID consentId) {
        Consent owned = ownedBy(subject, consentId);
        return reporter.report(writer.affirm(owned.getId(), subject.getIdentifier()));
    }

    /**
     * Ends a granted or unanswered consent in the name of a participant it names as a party.
     *
     * <p>A non-party is {@code 403}, not {@code 404}: it may know the consent exists, so there is
     * nothing to conceal. A terminal status is {@code 400}. The subject is notified after commit,
     * and nothing after the commit can fail the call. See ADR-0015.
     */
    @NonNull
    public ConsentDetails terminate(@NonNull Participant caller, @NonNull UUID consentId) {
        Consent consent =
                consents.findById(consentId)
                        .orElseThrow(
                                () -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));
        requireParty(caller, consent);
        ConsentDetails details =
                reporter.report(
                        writer.move(
                                consent.getId(),
                                ConsentStatus.TERMINATED,
                                caller.getIdentifier(),
                                EXPLICIT_TRIGGER));
        notifyTerminated(caller, details.consent());
        return details;
    }

    /** Tells the subject of a committed termination about it; never throws. */
    private void notifyTerminated(Participant caller, Consent terminated) {
        try {
            Optional<User> subject = users.findById(terminated.getUserId());
            if (subject.isEmpty()) {
                LOG.error(SUBJECT_MISSING, terminated.getId(), terminated.getUserId());
                return;
            }
            notifications.consentTerminated(subject.get(), terminated, caller);
        } catch (RuntimeException e) {
            LOG.error(NOTIFICATION_FAILED, terminated.getId(), FailureSummary.of(e));
            LOG.debug(NOTIFICATION_FAILED, terminated.getId(), e.getMessage(), e);
        }
    }

    /**
     * Grants an unanswered request, for the subject or a catalog relaying their decision.
     *
     * <p>A catalog must name the subject, which is checked against the consent, never used to find
     * it (ADR-0010); a participant is refused. The status check here only picks the refusal; the
     * one under the row lock in {@link ConsentWriter#resume} settles races. The snapshot is kept.
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
        ResumedConsent written = resumed(consent, resumable.actor());
        written.collateral().forEach(reporter::report);
        return reporter.report(written.answered());
    }

    /** A consent a resume may move, and the actor its event records. */
    private record Resumable(Consent consent, String actor) {}

    /**
     * The consent a relayed decision addresses, if the named subject owns it. Unknown and foreign
     * consents are the same {@code 404}, so ownership is never disclosed.
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

    /** The resume's writes, with a race on the one-grant-per-notice index answered as 409. */
    private ResumedConsent resumed(Consent consent, String actor) {
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

    /** Refuses a caller the consent names on neither side. */
    private static void requireParty(Participant caller, Consent consent) {
        if (!caller.getId().equals(consent.getProviderId())
                && !caller.getId().equals(consent.getConsumerId())) {
            throw new ForbiddenException(
                    NOT_A_PARTY.formatted(caller.getIdentifier(), consent.getId()));
        }
    }

    /** A consent the subject owns; unknown and foreign are the same {@code 404}. */
    private Consent ownedBy(User subject, UUID consentId) {
        return consents.findById(consentId)
                .filter(consent -> consent.getUserId().equals(subject.getId()))
                .orElseThrow(() -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));
    }

    /** Moves the subject's own consent to {@code target}, in the subject's name. */
    private ConsentDetails move(User subject, UUID consentId, ConsentStatus target) {
        Consent owned = ownedBy(subject, consentId);
        return reporter.report(
                writer.move(owned.getId(), target, subject.getIdentifier(), EXPLICIT_TRIGGER));
    }
}
