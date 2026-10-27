package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import com.seamware.consentmanager.observability.FailureSummary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.exceptions.response.Error;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import jakarta.inject.Singleton;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders framework-produced errors (malformed body, missing parameter, 405, 415, unroutable 404)
 * as RFC 7807 problem details, replacing Micronaut's Hateoas body.
 */
@Singleton
@Replaces(ErrorResponseProcessor.class)
public class ProblemErrorResponseProcessor implements ErrorResponseProcessor<ProblemDetail> {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorResponseProcessor.class);

    /** Separator between the individual messages listed in one detail string. */
    private static final String MESSAGE_SEPARATOR = "; ";

    private final ServerErrorDetail serverErrorDetail;

    /** Creates the processor. */
    public ProblemErrorResponseProcessor(ServerErrorDetail serverErrorDetail) {
        this.serverErrorDetail = serverErrorDetail;
    }

    /** Renders the error as a problem, withholding the cause of a server-side fault. */
    @Override
    public MutableHttpResponse<ProblemDetail> processResponse(
            ErrorContext errorContext, MutableHttpResponse<?> baseResponse) {
        HttpRequest<?> request = errorContext.getRequest();
        int status = baseResponse.code();
        String reason = baseResponse.reason();
        boolean serverError = status >= ProblemType.LOWEST_SERVER_ERROR_STATUS;

        if (serverError) {
            FailureSummary.logRequestFailure(
                    LOG, request, status, errorContext.getRootCause().orElse(null));
        }

        String detail =
                serverError
                        ? serverErrorDetail.detailFor(errorContext.getRootCause().orElse(null))
                        : describe(errorContext, reason);
        return baseResponse
                .body(ProblemType.problemFor(status, reason, detail, request.getPath()))
                .contentType(MediaType.of(ProblemType.MEDIA_TYPE));
    }

    /** Joins what the framework objected to, falling back to the status's own reason phrase. */
    private static String describe(ErrorContext errorContext, String reason) {
        String messages =
                errorContext.getErrors().stream()
                        .map(Error::getMessage)
                        .filter(message -> message != null && !message.isBlank())
                        .distinct()
                        .collect(Collectors.joining(MESSAGE_SEPARATOR));
        return messages.isEmpty() ? reason : messages;
    }
}
