package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
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
import java.net.URI;
import java.util.List;

/**
 * Renders the framework's 401 and 403 as RFC 7807 problem details instead of the module's default
 * Hateoas error body, so an authorization failure looks like every other error this service emits.
 *
 * <p>Only the body is this class's business. The status, and the {@code WWW-Authenticate} challenge
 * that a 401 must carry, are left to {@link DefaultAuthorizationExceptionHandler}: the challenge is
 * assembled from the registered {@code WwwAuthenticateChallengeProvider} beans, which is also what
 * keeps the {@code resource_metadata} parameter off it (see the {@code
 * micronaut.security.oauth2.protected-resource-metadata} block in {@code application.yml}).
 *
 * <p>Neither body names an issuer, an algorithm, a claim or a role. Which of the many ways a token
 * can fail actually occurred is a fact about the trust list, and the trust list is not published
 * (US-ID-008); the distinction a caller does get is 401 versus 403, and that one is exact.
 */
@Singleton
@Produces(AuthorizationProblemHandler.PROBLEM_JSON)
@Replaces(DefaultAuthorizationExceptionHandler.class)
public class AuthorizationProblemHandler extends DefaultAuthorizationExceptionHandler {

    /** RFC 7807 media type, matching the {@code application/problem+json} declared in the spec. */
    public static final String PROBLEM_JSON = "application/problem+json";

    /** Namespace every problem type URI in this service is minted under (TICKET-015). */
    private static final String PROBLEM_TYPE_PREFIX = "https://consent-manager.example/problems/";

    /** Problem type for a request that carried no usable bearer token. */
    private static final URI UNAUTHORIZED_TYPE = URI.create(PROBLEM_TYPE_PREFIX + "unauthorized");

    /** Problem type for a request that authenticated but may not perform the operation. */
    private static final URI FORBIDDEN_TYPE = URI.create(PROBLEM_TYPE_PREFIX + "forbidden");

    private static final String UNAUTHORIZED_TITLE = "Unauthorized";

    private static final String FORBIDDEN_TITLE = "Forbidden";

    private static final int STATUS_UNAUTHORIZED = 401;

    private static final int STATUS_FORBIDDEN = 403;

    /** Says that the token was unusable without saying which check it failed. */
    private static final String UNAUTHORIZED_DETAIL =
            "The request did not carry a valid bearer token.";

    /** Says that the caller is known and still not permitted, without naming the missing role. */
    private static final String FORBIDDEN_DETAIL =
            "The authenticated principal lacks a role permitted to perform this operation.";

    /**
     * Creates the handler with the collaborators the superclass needs.
     *
     * @param errorResponseProcessor the module's processor, whose body this class overwrites
     * @param redirectConfiguration redirect settings; redirects are switched off in configuration
     * @param redirectService resolves redirect targets, unused while redirects are off
     * @param wwwAuthenticateChallengeProviders supply the challenge a 401 carries
     * @param priorToLoginPersistence login-flow support, absent in a resource server
     */
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
     * Takes the superclass's status and headers and replaces the body with a {@link ProblemDetail}.
     *
     * @param request the refused request, supplying the {@code instance} URI
     * @param exception the failure; {@link AuthorizationException#isForbidden()} picks 403 over 401
     * @return the response to send
     */
    @Override
    protected MutableHttpResponse<?> httpResponseWithStatus(
            HttpRequest<?> request, AuthorizationException exception) {
        MutableHttpResponse<?> response = super.httpResponseWithStatus(request, exception);
        return response.body(problemOf(request, exception.isForbidden()))
                .contentType(MediaType.of(PROBLEM_JSON));
    }

    /**
     * Builds the body for one of the two outcomes.
     *
     * @param request the refused request
     * @param forbidden whether the caller authenticated and was refused anyway
     * @return the problem detail
     */
    private static ProblemDetail problemOf(HttpRequest<?> request, boolean forbidden) {
        ProblemDetail problem =
                forbidden
                        ? new ProblemDetail(FORBIDDEN_TYPE, FORBIDDEN_TITLE, STATUS_FORBIDDEN)
                                .detail(FORBIDDEN_DETAIL)
                        : new ProblemDetail(
                                        UNAUTHORIZED_TYPE, UNAUTHORIZED_TITLE, STATUS_UNAUTHORIZED)
                                .detail(UNAUTHORIZED_DETAIL);
        return problem.instance(URI.create(request.getPath()));
    }
}
