package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The one path every committed consent write takes to {@link ConsentStateChangeLog}, and so to the
 * transition counter.
 *
 * <p>Callers invoke it only after the transactional write has returned, so a rolled-back write is
 * never reported. Subject notification deliberately does not live here, because it must skip the
 * collateral moves (ADR-0015). The held status reported is the one read under the row lock.
 */
@Singleton
public class ConsentReporter {

    private final ConsentParties parties;
    private final ConsentStateChangeLog changeLog;

    public ConsentReporter(ConsentParties parties, ConsentStateChangeLog changeLog) {
        this.parties = parties;
        this.changeLog = changeLog;
    }

    /** Reports one committed move and renders it for the caller. */
    @NonNull
    public ConsentDetails report(@NonNull ConsentMove move) {
        ConsentDetails details = parties.details(move.recorded());
        changeLog.recorded(move.held(), details, move.recorded().event());
        return details;
    }

    /**
     * Reports the collateral moves of a decision or request, then its primary, and renders that.
     */
    @NonNull
    public ConsentDetails report(@NonNull ConsentWrite write) {
        write.collateral().forEach(this::report);
        RecordedConsent primary = write.primary();
        ConsentDetails details = parties.details(primary);
        write.held()
                .ifPresentOrElse(
                        held -> changeLog.recorded(held, details, primary.event()),
                        () -> changeLog.created(details, primary.event()));
        return details;
    }

    /**
     * Reports the moves of an erasure or a deregistration, resolving each distinct participant once
     * rather than twice per consent.
     */
    public void report(@NonNull List<ConsentMove> moves) {
        List<ConsentDetails> details =
                parties.details(moves.stream().map(ConsentMove::recorded).toList());
        IntStream.range(0, moves.size())
                .forEach(
                        i ->
                                changeLog.recorded(
                                        moves.get(i).held(),
                                        details.get(i),
                                        moves.get(i).recorded().event()));
    }
}
