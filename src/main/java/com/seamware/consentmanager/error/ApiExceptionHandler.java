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
 * Renders any {@link ApiException} as the RFC 7807 problem detail its {@link ProblemType} declares;
 * being narrower, it wins over {@link GlobalExceptionHandler}.
 */
@Singleton
@Produces(ProblemType.MEDIA_TYPE)
public class ApiExceptionHandler
        implements ExceptionHandler<ApiException, HttpResponse<ProblemDetail>> {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ServerErrorDetail serverErrorDetail;

    /** Creates the handler. */
    public ApiExceptionHandler(ServerErrorDetail serverErrorDetail) {
        this.serverErrorDetail = serverErrorDetail;
    }

    /** Maps to the declared status; a server error's message is published as configured. */
    @Override
    public HttpResponse<ProblemDetail> handle(HttpRequest request, ApiException exception) {
        ProblemType problemType = exception.problemType();
        boolean serverError =
                problemType.status().getCode() >= ProblemType.LOWEST_SERVER_ERROR_STATUS;
        if (serverError) {
            FailureSummary.logRequestFailure(LOG, request, problemType, exception);
        } else if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "{} {} refused with {}: {}",
                    request.getMethod(),
                    request.getPath(),
                    problemType,
                    exception.getMessage());
        }

        String detail =
                serverError ? serverErrorDetail.detailFor(exception) : exception.getMessage();
        ProblemDetail problem = problemType.toProblemDetail(detail, request.getPath());
        return HttpResponse.<ProblemDetail>status(problemType.status())
                .body(problem)
                .contentType(MediaType.of(ProblemType.MEDIA_TYPE));
    }
}
