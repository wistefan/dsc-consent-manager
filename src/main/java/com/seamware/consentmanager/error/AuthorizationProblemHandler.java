package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.authentication.DefaultAuthorizationExceptionHandler;
import io.micronaut.security.authentication.WwwAuthenticateChallengeProvider;
import io.micronaut.security.config.RedirectConfiguration;
import io.micronaut.security.config.RedirectService;
import io.micronaut.security.errors.PriorToLoginPersistence;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Renders the framework's 401 and 403 as RFC 7807 problem details, and drops the {@code
 * WWW-Authenticate} challenge from a 403, where RFC 7235 does not define it.
 *
 * <p>Neither body names an issuer, algorithm, claim or role: the trust list is not published.
 */
@Singleton
@Produces(ProblemType.MEDIA_TYPE)
@Replaces(DefaultAuthorizationExceptionHandler.class)
public class AuthorizationProblemHandler extends DefaultAuthorizationExceptionHandler {

    /** Says that the token was unusable without saying which check it failed. */
    private static final String UNAUTHORIZED_DETAIL =
            "The request did not carry a valid bearer token.";

    /**
     * Says the caller is known but not permitted, without naming which of several checks refused.
     */
    private static final String FORBIDDEN_DETAIL =
            "The authenticated caller is not permitted to perform this operation.";

    /** Creates the handler with the collaborators the superclass needs. */
    public AuthorizationProblemHandler(
            ErrorResponseProcessor<?> errorResponseProcessor,
            RedirectConfiguration redirectConfiguration,
            RedirectService redirectService,
            List<WwwAuthenticateChallengeProvider<HttpRequest<?>>>
                    wwwAuthenticateChallengeProviders,
            @Nullable PriorToLoginPersistence priorToLoginPersistence) {
        super(
                errorResponseProcessor,
                redirectConfiguration,
                redirectService,
                wwwAuthenticateChallengeProviders,
                priorToLoginPersistence);
    }

    /** Keeps the superclass's status and headers, minus a 403's challenge, with a problem body. */
    @Override
    protected MutableHttpResponse<?> httpResponseWithStatus(
            HttpRequest<?> request, AuthorizationException exception) {
        MutableHttpResponse<?> response = super.httpResponseWithStatus(request, exception);
        boolean forbidden = exception.isForbidden();
        if (forbidden) {
            response.getHeaders().remove(HttpHeaders.WWW_AUTHENTICATE);
        }
        return response.body(problemOf(request, forbidden))
                .contentType(MediaType.of(ProblemType.MEDIA_TYPE));
    }

    private static ProblemDetail problemOf(HttpRequest<?> request, boolean forbidden) {
        return forbidden
                ? ProblemType.FORBIDDEN.toProblemDetail(FORBIDDEN_DETAIL, request.getPath())
                : ProblemType.UNAUTHORIZED.toProblemDetail(UNAUTHORIZED_DETAIL, request.getPath());
    }
}
