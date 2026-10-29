package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * What a deregistration did: when the record was marked, and what was closed, unlinked, archived.
 * {@code deregisteredAt} is null when the row was deleted outright; {@code moves} and {@code
 * notificationsAbandoned} are reported by the caller after commit. See ADR-0008.
 */
public record DeregistrationResult(
        @Nullable Instant deregisteredAt,
        List<ConsentMove> moves,
        int consentsRetained,
        int noticesArchived,
        int linksRemoved,
        int notificationsAbandoned) {

    public DeregistrationResult {
        moves = List.copyOf(moves);
    }

    /** How many unanswered consents the deregistration terminated. */
    public int consentsTerminated() {
        return moves.size();
    }
}
