package com.seamware.consentmanager.observability;

import com.seamware.consentmanager.domain.ConsentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Counts consent status changes by status left and reached; called only after the write commits.
 */
@Singleton
public class ConsentMetrics {

    /** Counter of committed consent status changes. */
    public static final String TRANSITIONS_METRIC = "consentmanager.consent.transitions";

    /** Tag carrying the status the consent held, or {@link #NO_STATUS} for a creation. */
    public static final String FROM_TAG = "from";

    /** Tag carrying the status the consent reached. */
    public static final String TO_TAG = "to";

    /** Value of {@link #FROM_TAG} for a consent that did not exist before the write. */
    public static final String NO_STATUS = "none";

    private final MeterRegistry registry;

    public ConsentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Counts one move from {@code held}, or one creation when {@code held} is empty. */
    public void transitioned(@NonNull Optional<ConsentStatus> held, @NonNull ConsentStatus now) {
        Counter.builder(TRANSITIONS_METRIC)
                .tag(FROM_TAG, held.map(ConsentStatus::name).orElse(NO_STATUS))
                .tag(TO_TAG, now.name())
                .register(registry)
                .increment();
    }
}
