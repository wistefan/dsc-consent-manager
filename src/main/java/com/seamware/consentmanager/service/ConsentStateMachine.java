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
     * <p>{@link ConsentStatus#DRAFT} is deliberately absent - nothing in this service creates a
     * draft yet, and the status survives only as a transition source for the ticket that adds one.
     */
    private static final Set<ConsentStatus> CREATABLE =
            Collections.unmodifiableSet(
                    EnumSet.of(
                            ConsentStatus.GRANTED, ConsentStatus.REFUSED, ConsentStatus.PENDING));

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
        table.put(ConsentStatus.PENDING, EnumSet.of(ConsentStatus.GRANTED, ConsentStatus.REFUSED));
        table.put(ConsentStatus.DRAFT, EnumSet.of(ConsentStatus.GRANTED, ConsentStatus.REFUSED));
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
