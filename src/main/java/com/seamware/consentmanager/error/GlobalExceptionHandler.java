package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Last-resort handler: renders anything no narrower handler claimed as a generic 500 problem
 * detail.
 *
 * <p>Deliberate refusals carry their own status through {@link ApiException}, which {@link
 * ApiExceptionHandler} serves; reaching this handler therefore means a genuine defect, which is why
 * it logs at ERROR and publishes nothing about the cause.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7807">RFC 7807</a>
 */
@Singleton
@Produces(ProblemType.MEDIA_TYPE)
public class GlobalExceptionHandler
        implements ExceptionHandler<Exception, HttpResponse<ProblemDetail>> {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Default problem type URI used when no specific type is applicable. */
    private static final URI DEFAULT_PROBLEM_TYPE = URI.create("about:blank");

    /** Generic error title for unhandled internal exceptions. */
    private static final String INTERNAL_ERROR_TITLE = "Internal Server Error";

    /** Generic error detail message to avoid leaking internal information. */
    private static final String INTERNAL_ERROR_DETAIL =
            "An unexpected error occurred. Please try again later.";

    /** HTTP status code for Internal Server Error. */
    private static final int STATUS_INTERNAL_SERVER_ERROR = 500;

    /** Logs the failure in full and answers with a 500 that reveals none of it. */
    @Override
    public HttpResponse<ProblemDetail> handle(HttpRequest request, Exception exception) {
        LOG.error(
                "Unhandled exception for {} {}: {}",
                request.getMethod(),
                request.getUri(),
                exception.getMessage(),
                exception);

        ProblemDetail error =
                new ProblemDetail(
                                DEFAULT_PROBLEM_TYPE,
                                INTERNAL_ERROR_TITLE,
                                STATUS_INTERNAL_SERVER_ERROR)
                        .detail(INTERNAL_ERROR_DETAIL);

        return HttpResponse.<ProblemDetail>status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error)
                .contentType(MediaType.of(ProblemType.MEDIA_TYPE));
    }
}
