package com.seamware.consentmanager.support;

import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Polls a condition an asynchronous component reaches, against a deadline. Fails with the
 * condition's description rather than hanging or guessing a sleep.
 */
public final class Await {

    /** How often the condition is re-evaluated. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private Await() {}

    /** Blocks until the condition holds, failing the test with {@code description} on timeout. */
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

    /** Fails the test if the condition becomes true at any point within the window. */
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
