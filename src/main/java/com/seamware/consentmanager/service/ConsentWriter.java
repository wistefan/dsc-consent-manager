package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * The transactional half of the grant flow, on its own bean because Micronaut AOP does not apply to
 * self-invocation, so {@link ConsentService} calling these methods on itself would run them with no
 * transaction at all - and a decision writes a consent and its event, and may close a prior consent
 * first, which only holds together as one unit. Mirrors {@link PrivacyNoticeWriter}.
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

        UUID superseded = revokeOpenGrant(open, actor);
        List<Consent> unanswered = allHeld(open, ConsentStatus.PENDING);
        if (!unanswered.isEmpty()) {
            closeRedundant(unanswered.subList(1, unanswered.size()), actor);
            return answer(unanswered.getFirst(), decision, snapshot, superseded, actor);
        }
        return create(subject, notice, decision, snapshot, parentOf(held), actor);
    }

    /**
     * Revokes the consent in force, if there is one, and reports its id so the decision can link
     * what it superseded.
     */
    @Nullable
    private UUID revokeOpenGrant(List<Consent> open, String actor) {
        Consent inForce = held(open, ConsentStatus.GRANTED);
        if (inForce == null) {
            return null;
        }
        saveUpdated(factory.transition(inForce, ConsentStatus.REVOKED, actor, SUBJECT_TRIGGER));
        return inForce.getId();
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
     * cannot block a concurrent insert and no index covers {@code PENDING}, so two requests racing
     * for a pair that held no row can both land - and answering only the newest would leave the
     * other open forever, permanently refusing every later request for the pair as a duplicate.
     * Closing them here settles that without a second guard the duplicate check could drift from.
     */
    private void closeRedundant(List<Consent> redundant, String actor) {
        redundant.forEach(
                consent ->
                        saveUpdated(
                                factory.transition(
                                        consent,
                                        ConsentStatus.TERMINATED,
                                        actor,
                                        CLEANUP_TRIGGER)));
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
