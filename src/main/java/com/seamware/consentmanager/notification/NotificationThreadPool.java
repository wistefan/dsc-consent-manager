package com.seamware.consentmanager.notification;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded pool notification sends run on; {@link #drain()} drops what is queued at shutdown. Not an
 * {@code ExecutorService}, since Micrometer's proxy of one breaks the {@code drain} preDestroy.
 */
class NotificationThreadPool implements Executor {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationThreadPool.class);

    private final ThreadPoolExecutor pool;
    private final Duration shutdownGrace;

    NotificationThreadPool(
            int threads, int queueCapacity, Duration shutdownGrace, ThreadFactory threadFactory) {
        this.pool =
                new ThreadPoolExecutor(
                        threads,
                        threads,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(queueCapacity),
                        threadFactory,
                        new ThreadPoolExecutor.AbortPolicy());
        this.shutdownGrace = shutdownGrace;
    }

    /** Queues a send. Throws {@code RejectedExecutionException} when full or drained. */
    @Override
    public void execute(Runnable task) {
        pool.execute(task);
    }

    boolean isShutdown() {
        return pool.isShutdown();
    }

    /** Stops accepting work, waits at most the grace, then drops the rest and reports how many. */
    void drain() {
        pool.shutdown();
        try {
            if (pool.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int dropped = pool.shutdownNow().size();
        LOG.warn(
                "Notification sender stopped after {} ms; {} queued notifications dropped",
                shutdownGrace.toMillis(),
                dropped);
    }
}
