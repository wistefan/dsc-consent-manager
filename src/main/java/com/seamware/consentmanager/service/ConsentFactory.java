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
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The only place a consent is created or re-statused, and the only place its audit event is built.
 *
 * <p>{@code consented} and the event state both derive from the target status, and actor and
 * trigger are required, so the two records agree by construction. {@code
 * ConsentStatusInvariantTest} fails the build if any other source calls {@code withStatus} or
 * {@code updateStatus}, so no transition bypasses {@link ConsentStateMachine}.
 */
@Singleton
public class ConsentFactory {

    /** The event each target status is recorded as. {@link ConsentStatus#DRAFT} records nothing. */
    static final Map<ConsentStatus, ConsentEventState> EVENT_STATES = eventStates();

    /** The event affirming a grant in force; admissible in GRANTED, but dates nothing. */
    static final ConsentEventState AFFIRMATION = ConsentEventState.CONSENT_RE_CONFIRMED;

    /**
     * Every event a status admits at all, total over {@link ConsentStatus}; {@code DRAFT} maps to
     * the empty set. {@link #EVENT_STATES} only names the default event of a move.
     */
    static final Map<ConsentStatus, Set<ConsentEventState>> ADMISSIBLE_EVENTS = admissibleEvents();

    private static final String UNMAPPED_STATUS = "no consent event is defined for state %s";

    private static final String INADMISSIBLE_EVENT =
            "event %s cannot be recorded against a consent in state %s";

    private final ConsentStateMachine lifecycle;

    public ConsentFactory(ConsentStateMachine lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Creates, unpersisted, a consent in {@code target} with its event; {@code 400} for a status
     * this service does not create. Always an explicit act of {@code actor}.
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
     * Moves a consent to {@code target} and returns it with the event recording the move. A move
     * the lifecycle refuses raises {@code 400} and leaves the consent untouched.
     */
    @NonNull
    public RecordedConsent transition(
            @NonNull Consent consent,
            @NonNull ConsentStatus target,
            @NonNull String actor,
            @NonNull ConsentEventTrigger trigger) {
        return transition(consent, target, actor, trigger, null);
    }

    /** The same move, with {@code details} recorded on the event as a reason the state lacks. */
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

    /**
     * The same move, filed as {@code recordedAs} rather than the target status's default event. The
     * event must still be in {@link #ADMISSIBLE_EVENTS} for that status.
     */
    @NonNull
    public RecordedConsent transitionRecordedAs(
            @NonNull Consent consent,
            @NonNull ConsentStatus target,
            @NonNull ConsentEventState recordedAs,
            @NonNull String actor,
            @NonNull ConsentEventTrigger trigger) {
        lifecycle.requireTransition(consent.getStatus(), target);
        consent.updateStatus(target);
        return new RecordedConsent(
                consent, event(consent, recordedAs, actor, trigger, null), false);
    }

    /**
     * Records that {@code actor} affirms a consent in force, returning it untouched. A consent in
     * any other status is refused with {@code 400}.
     */
    @NonNull
    public RecordedConsent affirm(@NonNull Consent consent, @NonNull String actor) {
        lifecycle.requireAffirmable(consent.getStatus());
        return new RecordedConsent(
                consent,
                event(consent, AFFIRMATION, actor, ConsentEventTrigger.EXPLICIT, null),
                false);
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
        return event(consent, state, actor, trigger, details);
    }

    /** Builds the event, refusing one the consent's status does not admit. */
    private static ConsentEvent event(
            Consent consent,
            ConsentEventState state,
            String actor,
            ConsentEventTrigger trigger,
            Map<String, Object> details) {
        if (!ADMISSIBLE_EVENTS.getOrDefault(consent.getStatus(), Set.of()).contains(state)) {
            throw new IllegalStateException(
                    INADMISSIBLE_EVENT.formatted(state, consent.getStatus()));
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

    private static Map<ConsentStatus, Set<ConsentEventState>> admissibleEvents() {
        Map<ConsentStatus, Set<ConsentEventState>> table = new EnumMap<>(ConsentStatus.class);
        EVENT_STATES.forEach((status, state) -> table.put(status, EnumSet.of(state)));
        table.put(
                ConsentStatus.GRANTED,
                EnumSet.of(
                        ConsentEventState.CONSENT_GIVEN,
                        ConsentEventState.CONSENT_RESUMED,
                        AFFIRMATION));
        table.put(ConsentStatus.DRAFT, EnumSet.noneOf(ConsentEventState.class));
        return Collections.unmodifiableMap(table);
    }
}
