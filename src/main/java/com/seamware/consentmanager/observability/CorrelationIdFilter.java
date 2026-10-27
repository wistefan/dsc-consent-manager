package com.seamware.consentmanager.observability;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import com.seamware.consentmanager.domain.UuidGenerator;
import io.micronaut.context.propagation.slf4j.MdcPropagationContext;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.propagation.MutablePropagatedContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.filter.ServerFilterPhase;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Gives every request a correlation ID, logged, echoed in {@link #HEADER} and stamped on problem
 * details. Runs first; an inbound ID not matching {@link #ACCEPTED_ID} is replaced.
 */
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
public class CorrelationIdFilter implements Ordered {

    /** Header the ID is read from and echoed in. */
    public static final String HEADER = "X-Request-ID";

    /** MDC key every log line of the request carries the ID under. */
    public static final String MDC_KEY = "correlationId";

    /** Request attribute holding the ID for the duration of the request. */
    public static final String ATTRIBUTE = "consent-manager.correlation-id";

    /** Longest inbound ID adopted; a UUID is 36, so this leaves room for other formats. */
    static final int MAX_LENGTH = 128;

    /** Inbound IDs adopted verbatim: URL-safe characters only, no whitespace or control codes. */
    static final Pattern ACCEPTED_ID = Pattern.compile("[A-Za-z0-9._~-]{1," + MAX_LENGTH + "}");

    /**
     * Assigns the ID and propagates it in the MDC to every thread the request runs on; the entry is
     * set only long enough for the propagated context to capture it.
     */
    @RequestFilter
    public void assign(HttpRequest<?> request, MutablePropagatedContext propagatedContext) {
        String correlationId = accept(request.getHeaders().get(HEADER));
        request.setAttribute(ATTRIBUTE, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            propagatedContext.add(new MdcPropagationContext());
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Echoes the ID in the response header and on a problem-detail body. */
    @ResponseFilter
    public void echo(HttpRequest<?> request, MutableHttpResponse<?> response) {
        correlationIdOf(request)
                .ifPresent(
                        correlationId -> {
                            response.header(HEADER, correlationId);
                            if (response.getBody().orElse(null) instanceof ProblemDetail problem) {
                                problem.setCorrelationId(correlationId);
                            }
                        });
    }

    /** Places this filter ahead of every other, the security filter included. */
    @Override
    public int getOrder() {
        return ServerFilterPhase.FIRST.order();
    }

    /** The ID {@link #assign} gave the request, empty only if it never ran. */
    public static Optional<String> correlationIdOf(HttpRequest<?> request) {
        return request.getAttribute(ATTRIBUTE, String.class);
    }

    /** The inbound ID when it is well-formed, otherwise a freshly generated one. */
    static String accept(@Nullable String inbound) {
        return inbound != null && ACCEPTED_ID.matcher(inbound).matches()
                ? inbound
                : UuidGenerator.uuidV7().toString();
    }
}
