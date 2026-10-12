package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The only place a consent is created or re-statused, and the only place its audit event is built.
 *
 * <p>Funnelling both through here is what makes the records agree by construction: {@code
 * consented} is derived from the target status by {@link Consent#withStatus}, the event state is
 * derived from the same status by {@link #EVENT_STATES}, and the acting identity and the {@link
 * ConsentEventTrigger} are required arguments, so no path records a refusal as a grant, leaves a
 * binding decision anonymous, or files an automated change as a deliberate one.
 *
 * <p>The exclusivity is enforced rather than asserted: {@code ConsentStatusInvariantTest} fails the
 * build when any other source under {@code src/main/java} calls {@code withStatus} or {@code
 * updateStatus}, so a transition cannot reach the entity without passing {@link
 * ConsentStateMachine} first.
 */
@Singleton
public class ConsentFactory {

    /** The event each target status is recorded as. {@link ConsentStatus#DRAFT} records nothing. */
    private static final Map<ConsentStatus, ConsentEventState> EVENT_STATES = eventStates();

    private static final String UNMAPPED_STATUS = "no consent event is defined for state %s";

    private final ConsentStateMachine lifecycle;

    public ConsentFactory(ConsentStateMachine lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Creates a consent in {@code target} together with the event recording it.
     *
     * <p>Refuses with {@link com.seamware.consentmanager.error.BadRequestException} a status this
     * service does not create. Takes no trigger because every creatable status is by definition a
     * deliberate act of {@code actor}; only {@link #transition} is reachable from a job. Neither
     * record is persisted here.
     */
    @NonNull
    public RecordedConsent create(
            @NonNull ConsentStatus target,
            @NonNull UUID userId,
            @NonNull UUID privacyNoticeId,
            @NonNull UUID providerId,
            @NonNull UUID consumerId,
            @Nullable UUID parentConsentId,
            @Nullable String contractUri,
            @NonNull ConsentSnapshot snapshot,
            @NonNull String actor) {
        lifecycle.requireCreatable(target);
        Consent consent =
                Consent.withStatus(
                        target,
                        userId,
                        privacyNoticeId,
                        providerId,
                        consumerId,
                        parentConsentId,
                        contractUri,
                        snapshot);
        return new RecordedConsent(
                consent, event(consent, actor, ConsentEventTrigger.EXPLICIT, null), true);
    }

    /**
     * Moves an existing consent to {@code target} and returns it with the event recording the move.
     *
     * <p>Refuses with {@link com.seamware.consentmanager.error.BadRequestException} a move the
     * lifecycle does not permit; the consent is left untouched in that case. {@code trigger}
     * separates a person's revocation from a job's expiry, which share both the state table and
     * this method.
     */
    @NonNull
    public RecordedConsent transition(
            @NonNull Consent consent,
            @NonNull ConsentStatus target,
            @NonNull String actor,
            @NonNull ConsentEventTrigger trigger) {
        return transition(consent, target, actor, trigger, null);
    }

    /**
     * The same move, with {@code details} recorded on the event.
     *
     * <p>Used where the move needs a reason the state alone does not carry, such as the erasure or
     * deregistration that closes a consent nobody acted on.
     */
    @NonNull
    public RecordedConsent transition(
            @NonNull Consent consent,
            @NonNull ConsentStatus target,
            @NonNull String actor,
            @NonNull ConsentEventTrigger trigger,
            @Nullable Map<String, Object> details) {
        lifecycle.requireTransition(consent.getStatus(), target);
        consent.updateStatus(target);
        return new RecordedConsent(consent, event(consent, actor, trigger, details), false);
    }

    private static ConsentEvent event(
            Consent consent,
            String actor,
            ConsentEventTrigger trigger,
            Map<String, Object> details) {
        ConsentEventState state = EVENT_STATES.get(consent.getStatus());
        if (state == null) {
            throw new IllegalStateException(UNMAPPED_STATUS.formatted(consent.getStatus()));
        }
        return new ConsentEvent(
                consent.getId(),
                state,
                trigger.eventType(),
                Objects.requireNonNull(actor, "an event records who acted"),
                Instant.now(),
                details);
    }

    private static Map<ConsentStatus, ConsentEventState> eventStates() {
        Map<ConsentStatus, ConsentEventState> states = new EnumMap<>(ConsentStatus.class);
        states.put(ConsentStatus.GRANTED, ConsentEventState.CONSENT_GIVEN);
        states.put(ConsentStatus.REFUSED, ConsentEventState.CONSENT_REFUSED);
        states.put(ConsentStatus.PENDING, ConsentEventState.CONSENT_REQUESTED);
        states.put(ConsentStatus.REVOKED, ConsentEventState.CONSENT_REVOKED);
        states.put(ConsentStatus.TERMINATED, ConsentEventState.CONSENT_TERMINATED);
        states.put(ConsentStatus.EXPIRED, ConsentEventState.CONSENT_EXPIRED);
        return Collections.unmodifiableMap(states);
    }
}
