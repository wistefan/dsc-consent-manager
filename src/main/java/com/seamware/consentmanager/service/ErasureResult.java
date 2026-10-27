package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.Nullable;
import java.util.List;

/**
 * What an erasure did: the pseudonym the record now carries, and what was closed and unlinked.
 * {@code pseudonym} is null when the row was deleted outright, else published exactly once; {@code
 * moves} are reported by the caller after commit. See ADR-0007.
 */
public record ErasureResult(@Nullable String pseudonym, List<ConsentMove> moves, int linksRemoved) {

    public ErasureResult {
        moves = List.copyOf(moves);
    }

    /** How many grants the erasure revoked. */
    public int consentsRevoked() {
        return closedAs(ConsentStatus.REVOKED);
    }

    /** How many unanswered offers the erasure terminated. */
    public int consentsTerminated() {
        return closedAs(ConsentStatus.TERMINATED);
    }

    private int closedAs(ConsentStatus status) {
        return Math.toIntExact(
                moves.stream()
                        .filter(move -> move.recorded().consent().getStatus() == status)
                        .count());
    }
}
