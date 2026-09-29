package com.seamware.consentmanager.config;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * HTTP server filter that manages a correlation ID for request tracing.
 *
 * <p>On each incoming request, this filter:
 *
 * <ol>
 *   <li>Reads the {@value #CORRELATION_ID_HEADER} header if present, or generates a new UUID if
 *       absent.
 *   <li>Stores the correlation ID in the SLF4J {@link MDC} under the key {@value #MDC_KEY} so that
 *       all log statements within the request include the correlation ID in the structured JSON
 *       output.
 *   <li>Propagates the correlation ID back in the response header.
 *   <li>Clears the MDC entry after the response is sent.
 * </ol>
 *
 * <p>This filter applies to all requests ({@code "/**"}).
 *
 * @see org.slf4j.MDC
 */
@ServerFilter("/**")
public class CorrelationIdFilter {

    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);

    /** HTTP header name used to pass correlation IDs between services. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    /** MDC key under which the correlation ID is stored for structured logging. */
    public static final String MDC_KEY = "correlationId";

    /**
     * Intercepts incoming requests to extract or generate a correlation ID and place it into the
     * SLF4J MDC.
     *
     * @param request the incoming HTTP request
     */
    @RequestFilter
    public void onRequest(HttpRequest<?> request) {
        String correlationId = request.getHeaders().get(CORRELATION_ID_HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
            LOG.debug(
                    "No {} header found; generated correlation ID: {}",
                    CORRELATION_ID_HEADER,
                    correlationId);
        }

        MDC.put(MDC_KEY, correlationId);
    }

    /**
     * Intercepts outgoing responses to add the correlation ID header and clean up the MDC.
     *
     * @param response the outgoing HTTP response
     */
    @ResponseFilter
    public void onResponse(MutableHttpResponse<?> response) {
        String correlationId = MDC.get(MDC_KEY);
        if (correlationId != null) {
            response.header(CORRELATION_ID_HEADER, correlationId);
        }
        MDC.remove(MDC_KEY);
    }
}
