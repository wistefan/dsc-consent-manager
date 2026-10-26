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
 * The one table of consent lifecycle rules: which status a consent may be created in, and which
 * status it may move to from the one it holds.
 *
 * <p>A table rather than branches scattered over the services, so a transition nobody thought about
 * is refused by default: a pair absent from {@link #TRANSITIONS} is not permitted, and a terminal
 * status is an empty entry rather than a missing one.
 */
@Singleton
public class ConsentStateMachine {

    /**
     * The statuses a consent may be created in.
     *
     * <p>{@link ConsentStatus#DRAFT} is deliberately absent, although the ticket's state table
     * lists it as a creation: nothing in this service prepares a draft, so admitting it would ship
     * a state no endpoint can produce and none can answer. The status survives here only as a
     * transition source, and the ticket that adds draft preparation adds it to this table and to
     * the factory's event table together.
     */
    private static final Set<ConsentStatus> CREATABLE =
            Collections.unmodifiableSet(
                    EnumSet.of(
                            ConsentStatus.GRANTED, ConsentStatus.REFUSED, ConsentStatus.PENDING));

    /**
     * Where an unanswered offer may go: answered either way, or terminated.
     *
     * <p>{@link ConsentStatus#TERMINATED} is not decoration - an erasure closes the offers its
     * subject can no longer answer, and a deregistration closes the ones naming the departing
     * participant, so omitting the edge would make the table declare invalid a move the service
     * performs.
     */
    private static final Set<ConsentStatus> UNANSWERED_MOVES =
            Collections.unmodifiableSet(
                    EnumSet.of(
                            ConsentStatus.GRANTED,
                            ConsentStatus.REFUSED,
                            ConsentStatus.TERMINATED));

    /**
     * The statuses a data subject's decision may be recorded in.
     *
     * <p>Narrower than {@link #CREATABLE}, which also admits the {@code PENDING} a participant
     * opens: a subject either consents or does not, so without this table a decision carried as a
     * status would let a subject request consent from themselves or terminate an open request as a
     * deliberate act of their own. The generated request enum narrows the HTTP surface the same
     * way, but the service contract is enforced here rather than left to it.
     */
    private static final Set<ConsentStatus> DECISIONS =
            Collections.unmodifiableSet(EnumSet.of(ConsentStatus.GRANTED, ConsentStatus.REFUSED));

    /**
     * The statuses in which a consent still stands between the parties: one in force, and one
     * nobody has answered yet.
     *
     * <p>Here rather than on either reader, so the writer's filter over the rows it locked and the
     * duplicate check that refuses a second request for the same pair cannot drift apart. Not
     * derived from {@link #TRANSITIONS}: {@link ConsentStatus#DRAFT} is non-terminal there and
     * still binds nobody.
     */
    public static final Set<ConsentStatus> OPEN_STATUSES =
            Collections.unmodifiableSet(EnumSet.of(ConsentStatus.GRANTED, ConsentStatus.PENDING));

    /**
     * The statuses an affirmation may be recorded against: a consent in force, and nothing else.
     *
     * <p>Re-confirmation moves no status, so it cannot be expressed as an entry in {@link
     * #TRANSITIONS} and cannot go through {@link #requireTransition}. The rule still belongs in
     * this table rather than as a branch in a service, for the same reason every other one does.
     */
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
