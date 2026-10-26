package com.seamware.consentmanager.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;

/**
 * Captures every event reaching the root logger, surviving the reset Logback performs when a
 * context starts, so leak tests can scan everything an encoder could have written.
 */
public final class LogCapture implements AutoCloseable {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private final ReattachOnReset reattach = new ReattachOnReset(appender);

    private LogCapture() {
        appender.start();
        rootLogger().addAppender(appender);
        rootLogger().getLoggerContext().addListener(reattach);
    }

    /** Starts capturing; attach before the context starts to include startup logging. */
    public static LogCapture attach() {
        return new LogCapture();
    }

    /** A snapshot taken under the appender's lock, since request threads append concurrently. */
    public List<ILoggingEvent> events() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    /** Stops capturing. */
    @Override
    public void close() {
        rootLogger().getLoggerContext().removeListener(reattach);
        rootLogger().detachAppender(appender);
    }

    /** Everything a log encoder could write: message, arguments, MDC and the throwable chain. */
    public static String render(ILoggingEvent event) {
        List<String> parts = new ArrayList<>();
        parts.add(event.getFormattedMessage());
        parts.add(event.getMessage());
        if (event.getArgumentArray() != null) {
            Stream.of(event.getArgumentArray()).map(String::valueOf).forEach(parts::add);
        }
        parts.addAll(event.getMDCPropertyMap().values());
        if (event.getThrowableProxy() != null) {
            parts.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        return String.join("\n", parts.stream().filter(Objects::nonNull).toList());
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    /**
     * Logback drops every appender when the context reconfigures it at startup; this restores ours.
     */
    private record ReattachOnReset(ListAppender<ILoggingEvent> appender)
            implements LoggerContextListener {

        @Override
        public boolean isResetResistant() {
            return true;
        }

        @Override
        public void onReset(LoggerContext context) {
            // The reset stopped it as well as detaching it, and a stopped appender drops events.
            appender.start();
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        }

        @Override
        public void onStart(LoggerContext context) {}

        @Override
        public void onStop(LoggerContext context) {}

        @Override
        public void onLevelChange(Logger logger, Level level) {}
    }
}
