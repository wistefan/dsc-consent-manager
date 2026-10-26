package com.seamware.consentmanager.observability;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * The part of a failure safe to log above DEBUG: route template, exception class chain and the top
 * frame of the root cause, never a message, a path value or a query string.
 *
 * <p>Exception messages are free text from this code, drivers and libraries; a unique violation
 * reads {@code Key (identifier)=(alice@example.com)}. So the message and throwable go to DEBUG
 * only, under the same {@code correlationId} the MDC carries for both lines.
 */
public final class FailureSummary {

    /** Stands in for the route template when no route matched the request. */
    public static final String UNMATCHED_ROUTE = "<unmatched>";

    /** Stands in for the top frame when the root cause carries no stack trace. */
    public static final String NO_FRAME = "<no frame>";

    private static final String CHAIN_SEPARATOR = " <- ";

    private static final String FRAME_SEPARATOR = " at ";

    private static final String SERVER_FAILURE = "{} {} failed with {}: {}";

    private FailureSummary() {}

    /**
     * Logs a server-side request failure: the summary at ERROR, message and throwable at DEBUG.
     * {@code outcome} names the problem type or status the response carries.
     */
    public static void logRequestFailure(
            Logger log, HttpRequest<?> request, Object outcome, @Nullable Throwable failure) {
        log.error(SERVER_FAILURE, request.getMethod(), routeOf(request), outcome, of(failure));
        if (log.isDebugEnabled()) {
            log.debug(
                    SERVER_FAILURE,
                    request.getMethod(),
                    request.getUri(),
                    outcome,
                    failure == null ? null : failure.getMessage(),
                    failure);
        }
    }

    /** The matched route template, such as {@code /consents/{id}}, never the concrete path. */
    public static String routeOf(HttpRequest<?> request) {
        return BasicHttpAttributes.getUriTemplate(request).orElse(UNMATCHED_ROUTE);
    }

    /**
     * Class names from the outermost exception to the root cause, then the root cause's top frame
     * as {@code class.method:line}; an empty string for no failure.
     */
    public static String of(@Nullable Throwable failure) {
        if (failure == null) {
            return "";
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        // takeWhile on first sight stops a cause cycle, which Throwable permits.
        List<Throwable> causes =
                Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
                        .takeWhile(seen::add)
                        .toList();
        String chain =
                causes.stream()
                        .map(throwable -> throwable.getClass().getName())
                        .collect(Collectors.joining(CHAIN_SEPARATOR));
        return chain + FRAME_SEPARATOR + topFrame(causes.getLast());
    }

    private static String topFrame(Throwable root) {
        StackTraceElement[] frames = root.getStackTrace();
        if (frames.length == 0) {
            return NO_FRAME;
        }
        StackTraceElement top = frames[0];
        return top.getClassName() + "." + top.getMethodName() + ":" + top.getLineNumber();
    }
}
