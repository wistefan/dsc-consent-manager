package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import com.seamware.consentmanager.observability.FailureSummary;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Last-resort handler rendering anything unclaimed as a 500 problem detail. Reaching it means a
 * defect, so it logs at ERROR and publishes the cause only as {@link ServerErrorDetail} allows.
 */
@Singleton
@Produces(ProblemType.MEDIA_TYPE)
public class GlobalExceptionHandler
        implements ExceptionHandler<Exception, HttpResponse<ProblemDetail>> {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ServerErrorDetail serverErrorDetail;

    /** Creates the handler. */
    public GlobalExceptionHandler(ServerErrorDetail serverErrorDetail) {
        this.serverErrorDetail = serverErrorDetail;
    }

    /** Logs a {@link FailureSummary} and answers with a 500 that reveals at most its message. */
    @Override
    public HttpResponse<ProblemDetail> handle(HttpRequest request, Exception exception) {
        FailureSummary.logRequestFailure(
                LOG, request, ProblemType.INTERNAL_SERVER_ERROR, exception);

        ProblemDetail problem =
                ProblemType.INTERNAL_SERVER_ERROR.toProblemDetail(
                        serverErrorDetail.detailFor(exception), request.getPath());

        return HttpResponse.<ProblemDetail>status(ProblemType.INTERNAL_SERVER_ERROR.status())
                .body(problem)
                .contentType(MediaType.of(ProblemType.MEDIA_TYPE));
    }
}
