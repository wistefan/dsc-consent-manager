package com.seamware.consentmanager.notification;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Produces the executor notification sends run on, off the request thread. */
@Factory
@Requires(condition = SmtpConfiguredCondition.class)
public class NotificationExecutorFactory {

    /** Qualifier of the notification executor. */
    public static final String EXECUTOR_NAME = "notifications";

    static final int NOTIFICATION_SENDER_THREADS = 2;
    static final int NOTIFICATION_QUEUE_CAPACITY = 1000;
    static final Duration NOTIFICATION_SHUTDOWN_GRACE = Duration.ofSeconds(2);
    static final String THREAD_NAME_PREFIX = "notification-sender-";

    /** Daemon threads, so a send blocked in a socket after shutdown cannot hold the JVM. */
    @Singleton
    @Named(EXECUTOR_NAME)
    @Bean(preDestroy = "drain")
    NotificationThreadPool notificationExecutor() {
        return new NotificationThreadPool(
                NOTIFICATION_SENDER_THREADS,
                NOTIFICATION_QUEUE_CAPACITY,
                NOTIFICATION_SHUTDOWN_GRACE,
                daemonThreads());
    }

    static ThreadFactory daemonThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
