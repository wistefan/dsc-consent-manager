package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The transactional half of the grant flow, on its own bean because Micronaut AOP does not apply to
 * self-invocation, so {@link ConsentService} calling these methods on itself would run them with no
 * transaction at all - and a decision writes a consent and its event, and may close a prior consent
 * first, which only holds together as one unit. A request is the same unit minus the closing.
 * Mirrors {@link PrivacyNoticeWriter}.
 */
@Singleton
public class ConsentWriter {

    /** A decision taken by the data subject in person is never an automated move. */
    private static final ConsentEventTrigger SUBJECT_TRIGGER = ConsentEventTrigger.EXPLICIT;

    /**
     * Closing the requests a decision leaves redundant is housekeeping, not an act of the subject,
     * who answered one offer rather than terminating the others.
     */
    private static final ConsentEventTrigger CLEANUP_TRIGGER = ConsentEventTrigger.SYSTEM;

    private static final String UNKNOWN_CONSENT = "No consent exists with identifier '%s'.";

    private static final String ALREADY_OPEN =
            "User '%s' has already been asked to decide on privacy notice '%s', or has already"
                    + " decided. Read the consent back rather than requesting it again.";

    private final ConsentRepository consents;
    private final ConsentEventRepository events;
    private final ConsentFactory factory;

    public ConsentWriter(
            ConsentRepository consents, ConsentEventRepository events, ConsentFactory factory) {
        this.consents = consents;
        this.events = events;
        this.factory = factory;
    }

    /**
     * Records {@code decision} for the subject against {@code notice}, closing whatever it
     * supersedes.
     *
     * <p>Throws {@link io.micronaut.data.exceptions.DataAccessException} when a concurrent grant
     * already holds {@code uq_consents_granted_per_user_notice}: this transaction rolls back whole
     * and the caller answers outside it, as {@link PrivacyNoticeWriter#archiveAndCreate} does.
     */
    @NonNull
    @Transactional
    public RecordedConsent decide(
            @NonNull User subject,
            @NonNull PrivacyNotice notice,
            @NonNull ConsentStatus decision,
            @NonNull ConsentSnapshot snapshot) {
        List<Consent> held =
                consents.findByUserIdAndPrivacyNoticeIdForUpdate(subject.getId(), notice.getId());
        List<Consent> open =
                held.stream()
                        .filter(
                                consent ->
                                        ConsentStateMachine.OPEN_STATUSES.contains(
                                                consent.getStatus()))
                        .toList();
        String actor = subject.getIdentifier();

        ConsentMove revoked = revokeOpenGrant(open, actor);
        UUID superseded = revoked == null ? null : revoked.recorded().consent().getId();
        List<Consent> unanswered = allHeld(open, ConsentStatus.PENDING);
        if (!unanswered.isEmpty()) {
            closeRedundant(unanswered.subList(1, unanswered.size()), actor);
            return answer(unanswered.getFirst(), decision, snapshot, superseded, actor);
        }
        return create(subject, notice, decision, snapshot, parentOf(held), actor);
    }

    /**
     * Opens an unanswered request for the subject to decide on {@code notice}, in {@code actor}'s
     * name.
     *
     * <p>Refuses with {@link ConflictException} a pair that already holds an open consent, so a
     * user is neither asked twice nor asked for a decision already given. The duplicate check runs
     * behind the same locking read the decision path takes, which is also what settles the parent,
     * and that lock is decisive only where the row already held is itself open: both racers read it
     * and both refuse. It covers nothing beyond that. Under {@code READ COMMITTED} a locked row is
     * re-read as its holder left it, but a row the holder <em>inserted</em> was never in this
     * statement's snapshot and no lock makes it appear - so two asks for a pair holding only closed
     * rows, and an ask racing a decision that opens one, can both land a {@code PENDING}. At most
     * one open request is therefore a best effort here rather than an invariant, and it is settled
     * where it is answered: {@code decide} closes every open request it does not adopt.
     */
    @NonNull
    @Transactional
    public RecordedConsent request(
            @NonNull User subject,
            @NonNull PrivacyNotice notice,
            @NonNull ConsentSnapshot snapshot,
            @NonNull String actor) {
        List<Consent> held =
                consents.findByUserIdAndPrivacyNoticeIdForUpdate(subject.getId(), notice.getId());
        if (held.stream()
                .anyMatch(
                        consent ->
                                ConsentStateMachine.OPEN_STATUSES.contains(consent.getStatus()))) {
            throw new ConflictException(
                    ALREADY_OPEN.formatted(subject.getIdentifier(), notice.getId()));
        }
        return create(subject, notice, ConsentStatus.PENDING, snapshot, parentOf(held), actor);
    }

    /**
     * Moves one stored consent to {@code target} and appends the event recording the move.
     *
     * <p>Re-reads the row under {@link ConsentRepository#findByIdForUpdate} and guards the
     * transition on <em>that</em> read, not on whatever the caller loaded to authorise itself, so
     * two moves sent at once cannot both be written: the loser sees the status the winner left and
     * {@link ConsentStateMachine} refuses it. Status and event are one unit of work, so neither can
     * be stored without the other, and a refused move throws before anything is written. The status
     * reported back is the one that locked read held, which is what a caller reporting the move
     * names rather than its own earlier read.
     *
     * <p>Throws {@link NotFoundException} when the row vanished between the two reads.
     */
    @NonNull
    @Transactional
    public ConsentMove move(
            @NonNull UUID consentId,
            @NonNull ConsentStatus target,
            @NonNull String actor,
            @NonNull ConsentEventTrigger trigger) {
        Consent locked =
                consents.findByIdForUpdate(consentId)
                        .orElseThrow(
                                () -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));
        return moved(locked, target, actor, trigger);
    }

    /**
     * Appends an affirmation of one stored consent, leaving the record itself alone.
     *
     * <p>Re-reads the row under {@link ConsentRepository#findByIdForUpdate} and checks the status
     * on <em>that</em> read, so an affirmation racing a revocation is refused rather than written
     * against a consent no longer in force. The consent row is deliberately not updated: {@code
     * updatedAt} dates state changes, and a re-confirmation changes no state — it is dated by its
     * own event.
     *
     * <p>Throws {@link NotFoundException} when the row vanished between the two reads.
     */
    @NonNull
    @Transactional
    public ConsentMove affirm(@NonNull UUID consentId, @NonNull String actor) {
        Consent locked =
                consents.findByIdForUpdate(consentId)
                        .orElseThrow(
                                () -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));
        ConsentStatus held = locked.getStatus();
        RecordedConsent affirmed = factory.affirm(locked, actor);
        events.save(affirmed.event());
        return new ConsentMove(held, affirmed);
    }

    /**
     * Grants the addressed request, closing whatever grant the pair already holds.
     *
     * <p>Deliberately symmetric with {@link #decide}: it takes the same {@code (user, notice)} lock
     * before reading, so a resume and a concurrent decision on the same pair serialise instead of
     * interleaving, and it reuses the same supersede — the grant in force is revoked and the
     * resumed row is re-pointed at it, but only where this call revoked something, so a request
     * answered with nothing in force keeps the parent it was opened with. The revocation, the
     * status, the event and the link are one unit of work.
     *
     * <p>Only the addressed row is <em>answered</em>, but the open requests it leaves redundant are
     * closed here as {@code decide} closes them, and for the same reason: a stranded {@code
     * PENDING} refuses every later request for the pair as a duplicate, forever. A relay cannot
     * fall back on {@code decide} to clear one — {@code POST /consents} is {@code USER}-only — so
     * the straggler would outlive the grant that made it redundant. The addressed row itself is
     * excluded from both searches: a consent cannot close itself, and one already {@code GRANTED}
     * is refused by {@link ConsentStateMachine} rather than revoked in favour of itself.
     *
     * <p>Every one of those closings is reported back alongside the answered row, because each is a
     * status change of its own and the caller is the one that reports moves onward. Returning only
     * the addressed consent would leave a superseded grant looking as though it still stood to
     * anything reading those reports.
     *
     * <p>Throws {@link NotFoundException} when no such consent exists, and {@link
     * io.micronaut.data.exceptions.DataAccessException} when a grant inserted outside this
     * statement's snapshot holds {@code uq_consents_granted_per_user_notice} — invisible to the
     * locking read, so the caller answers it outside the transaction as {@code decide}'s does.
     */
    @NonNull
    @Transactional
    public ResumedConsent resume(@NonNull UUID consentId, @NonNull String actor) {
        Consent addressed =
                consents.findById(consentId)
                        .orElseThrow(
                                () -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));
        List<Consent> locked =
                consents.findByUserIdAndPrivacyNoticeIdForUpdate(
                        addressed.getUserId(), addressed.getPrivacyNoticeId());
        Consent target =
                locked.stream()
                        .filter(consent -> consent.getId().equals(consentId))
                        .findFirst()
                        .orElseThrow(
                                () -> new NotFoundException(UNKNOWN_CONSENT.formatted(consentId)));

        List<Consent> open =
                locked.stream()
                        .filter(consent -> !consent.getId().equals(consentId))
                        .filter(
                                consent ->
                                        ConsentStateMachine.OPEN_STATUSES.contains(
                                                consent.getStatus()))
                        .toList();
        List<ConsentMove> collateral = new ArrayList<>();
        ConsentMove superseded = revokeOpenGrant(open, actor);
        if (superseded != null) {
            target.setParentConsentId(superseded.recorded().consent().getId());
            collateral.add(superseded);
        }
        collateral.addAll(closeRedundant(allHeld(open, ConsentStatus.PENDING), actor));

        ConsentStatus held = target.getStatus();
        RecordedConsent answered =
                saveUpdated(
                        factory.transitionRecordedAs(
                                target,
                                ConsentStatus.GRANTED,
                                ConsentEventState.CONSENT_RESUMED,
                                actor,
                                SUBJECT_TRIGGER));
        return new ResumedConsent(new ConsentMove(held, answered), List.copyOf(collateral));
    }

    /**
     * Revokes the consent in force, if there is one, and reports the move so the decision can link
     * what it superseded and a caller can report what it closed.
     */
    @Nullable
    private ConsentMove revokeOpenGrant(List<Consent> open, String actor) {
        Consent inForce = held(open, ConsentStatus.GRANTED);
        if (inForce == null) {
            return null;
        }
        return moved(inForce, ConsentStatus.REVOKED, actor, SUBJECT_TRIGGER);
    }

    /**
     * Answers the open request in place rather than leaving it stranded, re-freezing its terms as
     * the subject elected them now.
     *
     * <p>The row keeps its id and its own parent, so the request a participant opened is the
     * consent that answers it. It is re-pointed only when this same decision revoked a consent,
     * because then that revocation is what it superseded. Keeping the row means its {@code
     * createdAt} dates the offer rather than the answer: when the subject decided is the timestamp
     * of the event this appends, which is where a receipt or a lineage walk must read it from.
     */
    private RecordedConsent answer(
            Consent unanswered,
            ConsentStatus decision,
            ConsentSnapshot snapshot,
            @Nullable UUID superseded,
            String actor) {
        if (superseded != null) {
            unanswered.setParentConsentId(superseded);
        }
        unanswered.setSnapshot(snapshot);
        return saveUpdated(factory.transition(unanswered, decision, actor, SUBJECT_TRIGGER));
    }

    /**
     * Terminates the open requests the answered one leaves redundant, so none is stranded.
     *
     * <p>Normally there are none: a pair holds at most one open request. But the locking read
     * cannot show a row another transaction inserted after this statement's snapshot, and no index
     * covers {@code PENDING}, so two asks racing for a pair holding no open row - or an ask racing
     * this very decision - can both land one. Answering only the newest would leave the other open
     * forever, permanently refusing every later request for the pair as a duplicate. Closing them
     * here settles that without a second guard the duplicate check could drift from.
     *
     * <p>Each termination is reported back; {@link #decide} has nobody to report to and discards
     * them, while {@link #resume} passes them on to the lifecycle seam.
     */
    private List<ConsentMove> closeRedundant(List<Consent> redundant, String actor) {
        return redundant.stream()
                .map(
                        consent ->
                                moved(consent, ConsentStatus.TERMINATED, actor, CLEANUP_TRIGGER))
                .toList();
    }

    /** Applies one move, reading the status it leaves behind before the transition overwrites it. */
    private ConsentMove moved(
            Consent consent, ConsentStatus target, String actor, ConsentEventTrigger trigger) {
        ConsentStatus held = consent.getStatus();
        return new ConsentMove(
                held, saveUpdated(factory.transition(consent, target, actor, trigger)));
    }

    /** Stores the decision as a new consent, linked to whatever the pair last held. */
    private RecordedConsent create(
            User subject,
            PrivacyNotice notice,
            ConsentStatus decision,
            ConsentSnapshot snapshot,
            @Nullable UUID parent,
            String actor) {
        return saveNew(
                factory.create(
                        decision,
                        subject.getId(),
                        notice.getId(),
                        notice.getProviderId(),
                        notice.getConsumerId(),
                        parent,
                        notice.getContractUri(),
                        snapshot,
                        actor));
    }

    /** Inserts the consent with its event; a consent stored without its trail is untraceable. */
    private RecordedConsent saveNew(RecordedConsent recorded) {
        consents.save(recorded.consent());
        events.save(recorded.event());
        return recorded;
    }

    /** Applies a move to a stored consent and appends the event recording it. */
    private RecordedConsent saveUpdated(RecordedConsent recorded) {
        consents.update(recorded.consent());
        events.save(recorded.event());
        return recorded;
    }

    /**
     * What a new consent for the pair descends from: the most recent row whatever its status, so a
     * grant after a refusal or an expiry records what it followed instead of starting a fresh root.
     */
    @Nullable
    private static UUID parentOf(List<Consent> held) {
        return held.isEmpty() ? null : held.getFirst().getId();
    }

    /** The newest of {@code open} in {@code status}, or null when the pair holds none. */
    @Nullable
    private static Consent held(List<Consent> open, ConsentStatus status) {
        return allHeld(open, status).stream().findFirst().orElse(null);
    }

    /** Every row of {@code open} in {@code status}, newest first, as the locking read ordered. */
    private static List<Consent> allHeld(List<Consent> open, ConsentStatus status) {
        return open.stream().filter(consent -> consent.getStatus() == status).toList();
    }
}
