package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.Nullable;
import java.util.List;

/**
 * What an erasure did: the opaque identifier the record now carries, and the tally of what was
 * closed and unlinked on the way there.
 *
 * <p>{@code pseudonym} is null when the record was deleted outright rather than pseudonymised,
 * which is what happens when no consent referred to it and nothing therefore had to be retained.
 * When it is present it is published to the data subject exactly once, in the response to their own
 * erasure, because it is the only handle left onto the consents that were.
 *
 * <p>{@code moves} carries every consent the erasure closed, with the status it was locked in, so
 * the caller can report them once the transaction has committed; the published counts derive from
 * it.
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
