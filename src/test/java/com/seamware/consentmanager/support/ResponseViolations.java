package com.seamware.consentmanager.support;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-context record of responses that broke the spec, filled by {@link
 * OpenApiResponseValidationFilter} and asserted empty after every {@link PostgresTestResource}
 * test.
 */
@Singleton
@Requires(env = Environment.TEST)
public final class ResponseViolations {

    private final Queue<String> violations = new ConcurrentLinkedQueue<>();

    /** Records one violation; safe to call from any event-loop thread. */
    void record(String violation) {
        violations.add(violation);
    }

    /** Returns every violation recorded since the last drain and forgets them. */
    public List<String> drain() {
        List<String> drained = new ArrayList<>();
        for (String violation; (violation = violations.poll()) != null; ) {
            drained.add(violation);
        }
        return drained;
    }
}
