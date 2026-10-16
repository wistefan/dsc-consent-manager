package com.seamware.consentmanager.notification;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded pool the notification sends run on. {@link #drain()} drops what is still queued at
 * shutdown instead of working through it against a possibly unreachable server.
 */
class NotificationThreadPool extends ThreadPoolExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationThreadPool.class);

    private final Duration shutdownGrace;

    NotificationThreadPool(
            int threads, int queueCapacity, Duration shutdownGrace, ThreadFactory threadFactory) {
        super(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new AbortPolicy());
        this.shutdownGrace = shutdownGrace;
    }

    /** Stops accepting work, waits at most the grace, then drops the rest and reports how many. */
    void drain() {
        shutdown();
        try {
            if (awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int dropped = shutdownNow().size();
        LOG.warn(
                "Notification sender stopped after {} ms; {} queued notifications dropped",
                shutdownGrace.toMillis(),
                dropped);
    }
}
