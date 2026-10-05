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

    /** Logs the failure in full and answers with a 500 that reveals none of it. */
    @Override
    public HttpResponse<ProblemDetail> handle(HttpRequest request, Exception exception) {
        LOG.error(
                "Unhandled exception for {} {}: {}",
                request.getMethod(),
                request.getUri(),
                exception.getMessage(),
                exception);

        ProblemDetail problem =
                ProblemType.INTERNAL_SERVER_ERROR.toProblemDetail(
                        ProblemType.SERVER_ERROR_DETAIL, request.getPath());

        return HttpResponse.<ProblemDetail>status(ProblemType.INTERNAL_SERVER_ERROR.status())
                .body(problem)
                .contentType(MediaType.of(ProblemType.MEDIA_TYPE));
    }
}
