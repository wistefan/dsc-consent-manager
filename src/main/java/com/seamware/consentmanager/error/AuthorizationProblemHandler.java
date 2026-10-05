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
 * Renders the framework's 401 and 403 as RFC 7807 problem details instead of the module's default
 * Hateoas error body, so an authorization failure looks like every other error this service emits.
 *
 * <p>The status and the {@code WWW-Authenticate} challenge are otherwise left to {@link
 * DefaultAuthorizationExceptionHandler}, which assembles the challenge from the registered {@code
 * WwwAuthenticateChallengeProvider} beans - that is also what keeps the {@code resource_metadata}
 * parameter off it (see the {@code micronaut.security.oauth2.protected-resource-metadata} block in
 * {@code application.yml}). The one correction made here is that the superclass adds the challenge
 * on a 403 as well, where RFC 7235 defines it for 401 only and a client reading it as "retry with
 * credentials" would be misled: no token fixes a 403.
 *
 * <p>Neither body names an issuer, an algorithm, a claim or a role. Which of the many ways a token
 * can fail actually occurred is a fact about the trust list, and the trust list is not published
 * (US-ID-008); the distinction a caller does get is 401 versus 403, and that one is exact.
 */
@Singleton
@Produces(ProblemType.MEDIA_TYPE)
@Replaces(DefaultAuthorizationExceptionHandler.class)
public class AuthorizationProblemHandler extends DefaultAuthorizationExceptionHandler {

    /** Says that the token was unusable without saying which check it failed. */
    private static final String UNAUTHORIZED_DETAIL =
            "The request did not carry a valid bearer token.";

    /**
     * Says that the caller is known and still not permitted, without naming which check refused it:
     * a role the operation does not permit, no mapped role at all, and a participant identifier
     * this service does not know all land here, so wording that named any one of them would be
     * wrong more often than right.
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

    /**
     * Takes the superclass's status and headers, drops the challenge from a 403, and replaces the
     * body with a {@link ProblemDetail}.
     */
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

    /** Builds the body for one of the two outcomes. */
    private static ProblemDetail problemOf(HttpRequest<?> request, boolean forbidden) {
        return forbidden
                ? ProblemType.FORBIDDEN.toProblemDetail(FORBIDDEN_DETAIL, request.getPath())
                : ProblemType.UNAUTHORIZED.toProblemDetail(UNAUTHORIZED_DETAIL, request.getPath());
    }
}
