package com.seamware.consentmanager.support;

import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Waits for a condition that an asynchronous component is expected to reach.
 *
 * <p>OpenID Connect discovery deliberately runs off the startup thread, so the states a test cares
 * about - a provider becoming resolved, readiness flipping - are reached some time after the
 * context is up. Polling with a deadline keeps those assertions honest: the test fails with the
 * condition's description rather than hanging, and it does not encode a guess about how long the
 * work takes.
 */
public final class Await {

    /** How often the condition is re-evaluated. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    /** Not instantiable. */
    private Await() {}

    /**
     * Blocks until the condition holds, or fails the test when the timeout expires.
     *
     * @param description what the test is waiting for, used as the failure message
     * @param timeout how long to wait before giving up
     * @param condition the condition to poll
     */
    public static void until(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        if (!condition.getAsBoolean()) {
            fail("Timed out after %s waiting for: %s".formatted(timeout, description));
        }
    }

    /**
     * Asserts that a condition stays false for a whole window.
     *
     * <p>The counterpart to {@link #until}: some guarantees - "a permanently failed provider is
     * never retried" - are about something that must <em>not</em> happen, and can only be checked
     * by watching for long enough that it would have happened.
     *
     * @param description what must not happen, used as the failure message
     * @param window how long the condition must stay false
     * @param condition the condition that must not become true
     */
    public static void never(String description, Duration window, BooleanSupplier condition) {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                fail("Expected this never to happen within %s: %s".formatted(window, description));
            }
            sleep();
        }
    }

    /** Sleeps one poll interval, restoring the interrupt flag if interrupted. */
    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", interrupted);
        }
    }
}
