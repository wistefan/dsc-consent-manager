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
 * The transactional half of the consent flow: every consent write and its event as one unit. A
 * separate bean from {@link ConsentService} because Micronaut AOP skips self-invocation.
 */
@Singleton
public class ConsentWriter {

    /** A decision taken by the data subject in person is never an automated move. */
    private static final ConsentEventTrigger SUBJECT_TRIGGER = ConsentEventTrigger.EXPLICIT;

    /** Closing requests a decision leaves redundant is housekeeping, not an act of the subject. */
    private static final ConsentEventTrigger CLEANUP_TRIGGER = ConsentEventTrigger.SYSTEM;

    private static final String UNKNOWN_CONSENT = "No consent exists with identifier '%s'.";

    private static final String ALREADY_OPEN =
            "User '%s' has already been asked to decide on privacy notice '%s', or has already"
                    + " decided. Read the consent back rather than requesting it again.";

    private final ConsentRepository consents;
    private final ConsentEventRepository events;
    private final ConsentFactory factory;
    private final ConsentReceiptIssuer issuer;

    public ConsentWriter(
            ConsentRepository consents,
            ConsentEventRepository events,
            ConsentFactory factory,
            ConsentReceiptIssuer issuer) {
        this.consents = consents;
        this.events = events;
        this.factory = factory;
        this.issuer = issuer;
    }

    /**
     * Records {@code decision} under the {@code (user, notice)} lock, returning what it closed as
     * collateral.
     *
     * @throws io.micronaut.data.exceptions.DataAccessException when a concurrent grant holds {@code
     *     uq_consents_granted_per_user_notice}; the caller answers it outside the transaction
     */
    @NonNull
    @Transactional
    public ConsentWrite decide(
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

        List<ConsentMove> collateral = new ArrayList<>();
        ConsentMove revoked = revokeOpenGrant(open, actor);
        UUID superseded = null;
        if (revoked != null) {
            superseded = revoked.recorded().consent().getId();
            collateral.add(revoked);
        }
        List<Consent> unanswered = allHeld(open, ConsentStatus.PENDING);
        if (!unanswered.isEmpty()) {
            collateral.addAll(closeRedundant(unanswered.subList(1, unanswered.size()), actor));
            Consent adopted = unanswered.getFirst();
            // Read before answer(...) overwrites it.
            ConsentStatus adoptedHeld = adopted.getStatus();
            return ConsentWrite.moved(
                    new ConsentMove(
                            adoptedHeld, answer(adopted, decision, snapshot, superseded, actor)),
                    collateral);
        }
        return ConsentWrite.created(
                create(subject, notice, decision, snapshot, parentOf(held), actor), collateral);
    }

    /**
     * Opens an unanswered request for the subject to decide on {@code notice}, in {@code actor}'s
     * name; refuses with {@link ConflictException} a pair already holding an open consent.
     *
     * <p>The lock cannot see a row another transaction inserted under {@code READ COMMITTED}, so
     * two racing asks can both land a {@code PENDING}: one open request is best effort here, and
     * {@link #decide} closes every open request it does not adopt.
     */
    @NonNull
    @Transactional
    public ConsentWrite request(
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
        return ConsentWrite.created(
                create(subject, notice, ConsentStatus.PENDING, snapshot, parentOf(held), actor),
                List.of());
    }

    /**
     * Moves one stored consent to {@code target} and appends the event recording the move.
     *
     * <p>Guards the transition on the row re-read under {@link
     * ConsentRepository#findByIdForUpdate}, not the caller's read, so of two concurrent moves the
     * loser is refused by {@link ConsentStateMachine}; the reported held status is the locked one.
     *
     * @throws NotFoundException when the row vanished between the two reads
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
     * Appends an affirmation of one stored consent, leaving the row (and its {@code updatedAt})
     * alone; checked on the row re-read under lock, so one racing a revocation is refused.
     *
     * @throws NotFoundException when the row vanished between the two reads
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
     * Grants the addressed request, revoking the pair's grant in force and closing redundant open
     * requests, and reports every closing as collateral.
     *
     * <p>Takes the same {@code (user, notice)} lock as {@link #decide}, so the two serialise. The
     * request is re-parented only when this call revoked a grant.
     *
     * @throws NotFoundException when no such consent exists
     * @throws io.micronaut.data.exceptions.DataAccessException when a grant invisible to the lock
     *     holds {@code uq_consents_granted_per_user_notice}; the caller answers it outside the
     *     transaction
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

    /** Revokes the consent in force, if any, reporting the move or null. */
    @Nullable
    private ConsentMove revokeOpenGrant(List<Consent> open, String actor) {
        Consent inForce = held(open, ConsentStatus.GRANTED);
        if (inForce == null) {
            return null;
        }
        return moved(inForce, ConsentStatus.REVOKED, actor, SUBJECT_TRIGGER);
    }

    /**
     * Answers the open request in place, re-freezing its terms; re-parented only onto a consent
     * this decision revoked. Its {@code createdAt} dates the offer, the appended event the answer.
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
     * Terminates open requests the answered one leaves redundant; racing asks can land extras that
     * no index prevents, and a stranded {@code PENDING} would refuse every later request.
     */
    private List<ConsentMove> closeRedundant(List<Consent> redundant, String actor) {
        return redundant.stream()
                .map(consent -> moved(consent, ConsentStatus.TERMINATED, actor, CLEANUP_TRIGGER))
                .toList();
    }

    /** Applies one move, capturing the held status before the transition overwrites it. */
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
        return issueIfGranted(recorded);
    }

    /** Applies a move to a stored consent and appends the event recording it. */
    private RecordedConsent saveUpdated(RecordedConsent recorded) {
        consents.update(recorded.consent());
        events.save(recorded.event());
        return issueIfGranted(recorded);
    }

    /**
     * Issues the receipt of a write recording {@code GRANTED}, so every route into it issues one; a
     * signing failure rolls the whole write back. See ADR-0014.
     */
    private RecordedConsent issueIfGranted(RecordedConsent recorded) {
        if (recorded.consent().getStatus() == ConsentStatus.GRANTED) {
            issuer.issue(recorded);
        }
        return recorded;
    }

    /** Parent of a new consent for the pair: the most recent row, whatever its status. */
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
