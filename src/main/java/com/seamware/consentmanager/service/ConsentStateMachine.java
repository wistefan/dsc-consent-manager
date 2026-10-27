package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.error.BadRequestException;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The one table of consent lifecycle rules: valid creation statuses and permitted moves. A pair
 * absent from {@link #TRANSITIONS} is refused; a terminal status is an empty entry.
 */
@Singleton
public class ConsentStateMachine {

    /** Statuses a consent may be created in; {@code DRAFT} is absent, as nothing prepares one. */
    private static final Set<ConsentStatus> CREATABLE =
            Collections.unmodifiableSet(
                    EnumSet.of(
                            ConsentStatus.GRANTED, ConsentStatus.REFUSED, ConsentStatus.PENDING));

    /**
     * Where an unanswered offer may go: answered either way, or terminated. {@code TERMINATED} is
     * the move erasure and deregistration make on open offers.
     */
    private static final Set<ConsentStatus> UNANSWERED_MOVES =
            Collections.unmodifiableSet(
                    EnumSet.of(
                            ConsentStatus.GRANTED,
                            ConsentStatus.REFUSED,
                            ConsentStatus.TERMINATED));

    /**
     * The statuses a data subject's decision may be recorded in; narrower than {@link #CREATABLE}.
     * Enforced here, not left to the generated request enum.
     */
    private static final Set<ConsentStatus> DECISIONS =
            Collections.unmodifiableSet(EnumSet.of(ConsentStatus.GRANTED, ConsentStatus.REFUSED));

    /**
     * Statuses in which a consent still stands between the parties: in force, or unanswered. Not
     * derived from {@link #TRANSITIONS}, where {@code DRAFT} is non-terminal yet binds nobody.
     */
    public static final Set<ConsentStatus> OPEN_STATUSES =
            Collections.unmodifiableSet(EnumSet.of(ConsentStatus.GRANTED, ConsentStatus.PENDING));

    /** Statuses an affirmation may be recorded against; it moves no status, so not a transition. */
    private static final Set<ConsentStatus> AFFIRMABLE =
            Collections.unmodifiableSet(EnumSet.of(ConsentStatus.GRANTED));

    /** Every permitted move, by the status held. An empty entry is a terminal status. */
    private static final Map<ConsentStatus, Set<ConsentStatus>> TRANSITIONS = transitions();

    private static final String NOT_CREATABLE = "a consent cannot be created in state %s";

    private static final String NOT_PERMITTED = "a consent in state %s cannot move to state %s";

    private static final String NOT_A_DECISION =
            "state %s is not a decision a data subject can take; expected GRANTED or REFUSED";

    private static final String NOT_AFFIRMABLE =
            "a consent in state %s cannot be re-confirmed; re-confirmation requires one of %s";

    /** Whether a consent holding {@code from} may move to {@code to}. */
    public boolean permits(@NonNull ConsentStatus from, @NonNull ConsentStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** Whether a consent may be created holding {@code status}. */
    public boolean permitsCreation(@NonNull ConsentStatus status) {
        return CREATABLE.contains(status);
    }

    /** Refuses a creation in a status this service does not create, naming that status. */
    public void requireCreatable(@NonNull ConsentStatus target) {
        if (!permitsCreation(target)) {
            throw new BadRequestException(NOT_CREATABLE.formatted(target));
        }
    }

    /** Refuses a status that is not a subject's decision, naming it. */
    public void requireDecision(@NonNull ConsentStatus target) {
        if (!DECISIONS.contains(target)) {
            throw new BadRequestException(NOT_A_DECISION.formatted(target));
        }
    }

    /** Refuses an affirmation of a consent not in force, naming both it and what is required. */
    public void requireAffirmable(@NonNull ConsentStatus held) {
        if (!AFFIRMABLE.contains(held)) {
            throw new BadRequestException(NOT_AFFIRMABLE.formatted(held, AFFIRMABLE));
        }
    }

    /** Refuses a move the table does not permit, naming both the held and the attempted status. */
    public void requireTransition(@NonNull ConsentStatus from, @NonNull ConsentStatus to) {
        if (!permits(from, to)) {
            throw new BadRequestException(NOT_PERMITTED.formatted(from, to));
        }
    }

    private static Map<ConsentStatus, Set<ConsentStatus>> transitions() {
        Map<ConsentStatus, Set<ConsentStatus>> table = new EnumMap<>(ConsentStatus.class);
        table.put(ConsentStatus.PENDING, UNANSWERED_MOVES);
        table.put(ConsentStatus.DRAFT, UNANSWERED_MOVES);
        table.put(
                ConsentStatus.GRANTED,
                EnumSet.of(ConsentStatus.REVOKED, ConsentStatus.TERMINATED, ConsentStatus.EXPIRED));
        table.put(ConsentStatus.REVOKED, EnumSet.noneOf(ConsentStatus.class));
        table.put(ConsentStatus.REFUSED, EnumSet.noneOf(ConsentStatus.class));
        table.put(ConsentStatus.TERMINATED, EnumSet.noneOf(ConsentStatus.class));
        table.put(ConsentStatus.EXPIRED, EnumSet.noneOf(ConsentStatus.class));
        return Collections.unmodifiableMap(table);
    }
}
