package com.seamware.consentmanager.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs one callable on N threads released together by a {@link CyclicBarrier}, so they contend for
 * the same row rather than queueing behind each other, and reports how each one ended.
 */
public final class Race {

    /** Longest a whole race may take before the test fails instead of hanging the suite. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    private Race() {}

    /** How one racer ended: exactly one of {@code value} and {@code failure} is meaningful. */
    public record Outcome<T>(T value, Throwable failure) {

        /** Whether the racer returned rather than threw. */
        public boolean succeeded() {
            return failure == null;
        }
    }

    /** Races {@code racers} copies of {@code racer}; outcomes are in submission order. */
    public static <T> List<Outcome<T>> run(int racers, Callable<T> racer) {
        CyclicBarrier start = new CyclicBarrier(racers);
        Callable<T> released =
                () -> {
                    start.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    return racer.call();
                };
        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            List<Future<T>> futures =
                    pool.invokeAll(
                            Collections.nCopies(racers, released),
                            TIMEOUT.toMillis(),
                            TimeUnit.MILLISECONDS);
            List<Outcome<T>> outcomes = new ArrayList<>(racers);
            for (Future<T> future : futures) {
                outcomes.add(outcomeOf(future));
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while racing", e);
        }
    }

    private static <T> Outcome<T> outcomeOf(Future<T> future) throws InterruptedException {
        try {
            return new Outcome<>(future.get(), null);
        } catch (ExecutionException e) {
            return new Outcome<>(null, e.getCause());
        } catch (CancellationException e) {
            throw new IllegalStateException(
                    "A racer did not finish within %s".formatted(TIMEOUT), e);
        }
    }
}
