package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * What a deregistration did: when the record was marked, and the tally of what was closed, unlinked
 * and archived on the way there.
 *
 * <p>{@code deregisteredAt} is null when the record was deleted outright rather than retained,
 * which happens only for a participant no consent and no privacy notice ever named. The counts are
 * {@code int} throughout because {@code DeregistrationSummary} publishes them as {@code int32}, so
 * narrowing happens at the repository rather than on the way out.
 *
 * <p>{@code moves} carries every consent the deregistration terminated, with the status it was
 * locked in, so the caller can report them once the transaction has committed.
 */
public record DeregistrationResult(
        @Nullable Instant deregisteredAt,
        List<ConsentMove> moves,
        int consentsRetained,
        int noticesArchived,
        int linksRemoved) {

    public DeregistrationResult {
        moves = List.copyOf(moves);
    }

    /** How many unanswered consents the deregistration terminated. */
    public int consentsTerminated() {
        return moves.size();
    }
}
