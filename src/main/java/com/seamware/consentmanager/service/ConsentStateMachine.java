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

    /** Every permitted move, by the status held. An empty entry is a terminal status. */
    private static final Map<ConsentStatus, Set<ConsentStatus>> TRANSITIONS = transitions();

    private static final String NOT_CREATABLE = "a consent cannot be created in state %s";

    private static final String NOT_PERMITTED = "a consent in state %s cannot move to state %s";

    /** Whether a consent holding {@code from} may move to {@code to}. */
    public boolean permits(@NonNull ConsentStatus from, @NonNull ConsentStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** Refuses a creation in a status this service does not create, naming that status. */
    public void requireCreatable(@NonNull ConsentStatus target) {
        if (!CREATABLE.contains(target)) {
            throw new BadRequestException(NOT_CREATABLE.formatted(target));
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
