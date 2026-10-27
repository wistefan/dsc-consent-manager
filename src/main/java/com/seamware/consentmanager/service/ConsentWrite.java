package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;
import java.util.List;
import java.util.Optional;

/**
 * Everything one decision or request wrote: its primary consent, the status it held under the lock
 * (empty when inserted), and the collateral moves in write order.
 */
public record ConsentWrite(
        RecordedConsent primary, Optional<ConsentStatus> held, List<ConsentMove> collateral) {

    private static final String HELD_MISMATCH =
            "a consent write holds a prior status exactly when it did not create its row";

    public ConsentWrite {
        if (held.isEmpty() != primary.created()) {
            throw new IllegalArgumentException(HELD_MISMATCH);
        }
        collateral = List.copyOf(collateral);
    }

    /** A write that inserted its primary row. */
    static ConsentWrite created(RecordedConsent primary, List<ConsentMove> collateral) {
        return new ConsentWrite(primary, Optional.empty(), collateral);
    }

    /** A write that moved a stored row as its primary. */
    static ConsentWrite moved(ConsentMove primary, List<ConsentMove> collateral) {
        return new ConsentWrite(primary.recorded(), Optional.of(primary.held()), collateral);
    }
}
