package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Shutdown drops queued notifications after a bounded grace instead of working through them. */
class NotificationThreadPoolTest {

    private static final int QUEUED_TASKS = 5;
    private static final Duration DRAIN_MARGIN = Duration.ofSeconds(1);
    private static final Duration TASK_START_TIMEOUT = Duration.ofSeconds(5);

    private final Logger poolLogger =
            (Logger) LoggerFactory.getLogger(NotificationThreadPool.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capturePoolLog() {
        appender.start();
        poolLogger.addAppender(appender);
    }

    @AfterEach
    void releasePoolLog() {
        poolLogger.detachAppender(appender);
    }

    @Test
    @DisplayName(
            "drain returns within the grace, runs no queued task and reports the dropped count")
    void dropsQueuedTasksAfterTheGrace() throws InterruptedException {
        NotificationThreadPool pool = new NotificationExecutorFactory().notificationExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean daemon = new AtomicBoolean();
        AtomicInteger queuedRuns = new AtomicInteger();
        for (int i = 0; i < NotificationExecutorFactory.NOTIFICATION_SENDER_THREADS; i++) {
            pool.execute(
                    () -> {
                        daemon.set(Thread.currentThread().isDaemon());
                        started.countDown();
                        awaitUninterruptibly(release);
                    });
        }
        for (int i = 0; i < QUEUED_TASKS; i++) {
            pool.execute(queuedRuns::incrementAndGet);
        }
        assertThat(started.await(TASK_START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        long begin = System.nanoTime();
        pool.drain();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - begin);
        release.countDown();

        assertThat(elapsed)
                .isLessThan(
                        NotificationExecutorFactory.NOTIFICATION_SHUTDOWN_GRACE.plus(DRAIN_MARGIN));
        assertThat(queuedRuns).hasValue(0);
        assertThat(daemon).isTrue();
        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains(QUEUED_TASKS + " queued notifications dropped");
    }

    /**
     * Ignores the interrupt shutdownNow sends, so the task outlives the grace like a stuck socket.
     */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean done = false;
        while (!done) {
            try {
                latch.await();
                done = true;
            } catch (InterruptedException ignored) {
                // A blocked socket read does not react to interrupts either.
            }
        }
    }
}
