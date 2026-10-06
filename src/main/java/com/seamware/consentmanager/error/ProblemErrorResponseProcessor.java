package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
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
 * Renders every error the framework itself produces as an RFC 7807 problem detail, replacing the
 * Hateoas body Micronaut ships with.
 *
 * <p>The exception handlers in this package only cover what this service throws. A malformed
 * request body, a missing required parameter, a 405, a 415 and an unroutable 404 never reach them:
 * the framework builds those responses through this processor. Without the replacement the same API
 * would answer {@code application/problem+json} for a refusal it chose and {@code application/json}
 * with {@code _embedded.errors} for one the framework chose, and the shared {@code BadRequest}
 * response component would be a promise the service does not keep.
 */
@Singleton
@Replaces(ErrorResponseProcessor.class)
public class ProblemErrorResponseProcessor implements ErrorResponseProcessor<ProblemDetail> {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorResponseProcessor.class);

    /** Separator between the individual messages listed in one detail string. */
    private static final String MESSAGE_SEPARATOR = "; ";

    /**
     * Renders the error as a problem, withholding the cause of anything that is this side's fault.
     */
    @Override
    public MutableHttpResponse<ProblemDetail> processResponse(
            ErrorContext errorContext, MutableHttpResponse<?> baseResponse) {
        HttpRequest<?> request = errorContext.getRequest();
        int status = baseResponse.code();
        String reason = baseResponse.reason();
        boolean serverError = status >= ProblemType.LOWEST_SERVER_ERROR_STATUS;

        if (serverError) {
            LOG.error(
                    "{} {} failed with {}",
                    request.getMethod(),
                    request.getUri(),
                    status,
                    errorContext.getRootCause().orElse(null));
        }

        String detail =
                serverError ? ProblemType.SERVER_ERROR_DETAIL : describe(errorContext, reason);
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
