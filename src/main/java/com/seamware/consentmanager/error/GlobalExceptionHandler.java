package com.seamware.consentmanager.error;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global exception handler that catches all unhandled exceptions and returns
 * an RFC 7807 Problem Details response.
 *
 * <p>This is a stub implementation that maps all exceptions to a generic
 * HTTP 500 Internal Server Error. Per-exception-type handling (e.g.,
 * {@link BadRequestException} to 400, {@link NotFoundException} to 404,
 * constraint violation mapping, production detail suppression) is deferred
 * to TICKET-015.
 *
 * @see ApiError
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7807">RFC 7807</a>
 */
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public class GlobalExceptionHandler implements ExceptionHandler<Exception, HttpResponse<ApiError>> {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Generic error title for unhandled internal exceptions. */
    private static final String INTERNAL_ERROR_TITLE = "Internal Server Error";

    /** Generic error detail message to avoid leaking internal information. */
    private static final String INTERNAL_ERROR_DETAIL =
            "An unexpected error occurred. Please try again later.";

    /**
     * Handles an exception by logging it and returning a generic 500 response
     * with an {@link ApiError} body.
     *
     * @param request   the HTTP request that triggered the exception
     * @param exception the unhandled exception
     * @return an HTTP 500 response containing an {@link ApiError} body
     */
    @Override
    public HttpResponse<ApiError> handle(HttpRequest request, Exception exception) {
        LOG.error("Unhandled exception for {} {}: {}",
                request.getMethod(), request.getUri(), exception.getMessage(), exception);

        ApiError error = ApiError.of(
                INTERNAL_ERROR_TITLE,
                ApiError.STATUS_INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_DETAIL);

        return HttpResponse.<ApiError>status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
