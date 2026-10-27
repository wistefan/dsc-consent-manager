package com.seamware.consentmanager.support;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-context record of responses that broke the spec, filled by {@link
 * OpenApiResponseValidationFilter} and asserted empty after every {@link PostgresTestResource}
 * test. Also counts the exchanges the filter judged, so a canary can prove it sees real traffic.
 */
@Singleton
@Requires(env = Environment.TEST)
public final class ResponseViolations {

    private final Queue<String> violations = new ConcurrentLinkedQueue<>();

    private final AtomicLong judged = new AtomicLong();

    /** Counts one exchange the filter judged against the spec, conforming or not. */
    void recordJudged() {
        judged.incrementAndGet();
    }

    /** Exchanges judged since the context started; never reset by {@link #drain()}. */
    public long judgedCount() {
        return judged.get();
    }

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
