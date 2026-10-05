package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders any {@link ApiException} as the RFC 7807 problem detail its {@link ProblemType} declares.
 *
 * <p>Being typed on {@code ApiException} it wins over {@link GlobalExceptionHandler}, which stays
 * the last-resort 500 for everything a service did not refuse deliberately.
 */
@Singleton
@Produces(ProblemType.MEDIA_TYPE)
public class ApiExceptionHandler
        implements ExceptionHandler<ApiException, HttpResponse<ProblemDetail>> {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Lowest status code that reports a fault on this side of the connection. */
    private static final int LOWEST_SERVER_ERROR_STATUS = 500;

    /**
     * Stands in for the message of a server-side failure, which is logged rather than published.
     */
    private static final String SERVER_ERROR_DETAIL =
            "An unexpected error occurred. Please try again later.";

    /**
     * Maps the exception to its declared status and body, publishing the message only for a client
     * error.
     */
    @Override
    public HttpResponse<ProblemDetail> handle(HttpRequest request, ApiException exception) {
        ProblemType problemType = exception.problemType();
        boolean serverError = problemType.status().getCode() >= LOWEST_SERVER_ERROR_STATUS;
        if (serverError) {
            LOG.error(
                    "{} {} failed with {}: {}",
                    request.getMethod(),
                    request.getUri(),
                    problemType,
                    exception.getMessage(),
                    exception);
        } else if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "{} {} refused with {}: {}",
                    request.getMethod(),
                    request.getUri(),
                    problemType,
                    exception.getMessage());
        }

        String detail = serverError ? SERVER_ERROR_DETAIL : exception.getMessage();
        ProblemDetail problem = problemType.toProblemDetail(detail, request.getPath());
        return HttpResponse.<ProblemDetail>status(problemType.status())
                .body(problem)
                .contentType(MediaType.of(ProblemType.MEDIA_TYPE));
    }
}
