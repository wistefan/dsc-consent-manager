package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Global exception handler that catches all unhandled exceptions and returns
 * an RFC 7807 Problem Details response using the generated {@link ProblemDetail} model.
 *
 * <p>This is a stub implementation that maps all exceptions to a generic
 * HTTP 500 Internal Server Error. Per-exception-type handling (e.g.,
 * {@link BadRequestException} to 400, {@link NotFoundException} to 404,
 * constraint violation mapping, production detail suppression) is deferred
 * to TICKET-015.
 *
 * @see ProblemDetail
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7807">RFC 7807</a>
 */
@Singleton
@Produces("application/problem+json")
public class GlobalExceptionHandler implements ExceptionHandler<Exception, HttpResponse<ProblemDetail>> {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** RFC 7807 media type for Problem Details responses. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** Default problem type URI used when no specific type is applicable. */
    private static final URI DEFAULT_PROBLEM_TYPE = URI.create("about:blank");

    /** Generic error title for unhandled internal exceptions. */
    private static final String INTERNAL_ERROR_TITLE = "Internal Server Error";

    /** Generic error detail message to avoid leaking internal information. */
    private static final String INTERNAL_ERROR_DETAIL =
            "An unexpected error occurred. Please try again later.";

    /** HTTP status code for Internal Server Error. */
    private static final int STATUS_INTERNAL_SERVER_ERROR = 500;

    /**
     * Handles an exception by logging it and returning a generic 500 response
     * with a {@link ProblemDetail} body.
     *
     * @param request   the HTTP request that triggered the exception
     * @param exception the unhandled exception
     * @return an HTTP 500 response containing a {@link ProblemDetail} body
     */
    @Override
    public HttpResponse<ProblemDetail> handle(HttpRequest request, Exception exception) {
        LOG.error("Unhandled exception for {} {}: {}",
                request.getMethod(), request.getUri(), exception.getMessage(), exception);

        ProblemDetail error = new ProblemDetail(
                DEFAULT_PROBLEM_TYPE,
                INTERNAL_ERROR_TITLE,
                STATUS_INTERNAL_SERVER_ERROR)
                .detail(INTERNAL_ERROR_DETAIL);

        return HttpResponse.<ProblemDetail>status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error)
                .contentType(MediaType.of(PROBLEM_JSON));
    }
}
