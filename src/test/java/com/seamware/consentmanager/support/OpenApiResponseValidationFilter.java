package com.seamware.consentmanager.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.OpenApiInteractionValidator.SpecSource;
import com.atlassian.oai.validator.interaction.ApiOperationResolver;
import com.atlassian.oai.validator.model.ApiOperationMatch;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.util.OpenApiLoader;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.PathMatcher;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.ClientFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Validates every response the embedded server returns to a test client against the bundled spec:
 * the status must be declared and the body and content type must match it. Every operation declares
 * a {@code default} error response, so a status below 400 counts as declared only when the
 * operation names it explicitly. A route or method the spec does not publish may only be refused
 * with a 4xx, which tests probing such routes expect; any other answer there is a violation.
 * Requests are not validated, since many tests send invalid ones on purpose. A violation is
 * recorded in {@link ResponseViolations}, never thrown, because the client would wrap it.
 *
 * <p>Only exchanges with the embedded server are judged: same scheme and port, and either the host
 * the server reports (often the machine name) or a loopback name, since the server binds every
 * interface. Any other target passes through unvalidated.
 */
// Singleton so the startup listener and the filter are one instance.
@Singleton
@ClientFilter
@Requires(env = Environment.TEST)
@Requires(property = OpenApiResponseValidationFilter.ENABLED_PROPERTY, value = StringUtils.TRUE)
public final class OpenApiResponseValidationFilter
        implements ApplicationEventListener<ServerStartupEvent> {

    /** Defaults to true in {@code application-test.yml}; a test may switch it off. */
    public static final String ENABLED_PROPERTY = "test.response-validation.enabled";

    /** System property the pom sets for Surefire and Failsafe from {@code openapi.bundle.file}. */
    static final String BUNDLE_FILE_PROPERTY = "openapi.bundle.file";

    /** The pom's bundle location, for a test run outside Maven (e.g. from an IDE). */
    static final Path DEFAULT_BUNDLE_FILE = Path.of("target", "openapi-bundle", "openapi.yaml");

    private static final String LOCALHOST = "localhost";

    /** Test-only controllers some ITs mount beside the API; the spec describes none of them. */
    static final List<String> TEST_PROBE_ROUTES =
            List.of(
                    "/role-matrix-probe/**",
                    "/problem-probe/**",
                    "/provisioning-probe/**",
                    "/key-rotation-probe/**",
                    "/principal-probe/**",
                    "/token-signature-probe/**");

    /**
     * The service's own origin routes outside the spec: the enabled management endpoints, which
     * tests serve on the API port, and {@code application.yml}'s two static-resource mappings.
     */
    static final List<String> SERVICE_ORIGIN_ROUTES =
            List.of(
                    "/health",
                    "/health/**",
                    "/metrics",
                    "/metrics/**",
                    "/prometheus",
                    "/*.yaml",
                    "/swagger-ui/**");

    /** The spec's server URL is {@code /v1}; tests run with an empty API prefix. */
    private static final String TEST_BASE_PATH = "/";

    private static final String DEFAULT_SCHEME_HTTPS = "https";

    private static final int DEFAULT_HTTP_PORT = 80;

    private static final int DEFAULT_HTTPS_PORT = 443;

    /** Lowest status the spec's {@code default} error response may stand in for. */
    private static final int FIRST_ERROR_STATUS = 400;

    private static final int FIRST_SERVER_ERROR_STATUS = 500;

    private final OpenApiInteractionValidator validator;

    private final ApiOperationResolver operations;

    private final ResponseViolations violations;

    private volatile @Nullable URI origin;

    /** Validates against the bundled spec once the embedded server reports its URL. */
    @Inject
    OpenApiResponseValidationFilter(ResponseViolations violations) {
        this(BundledSpec.API, violations, null);
    }

    OpenApiResponseValidationFilter(
            OpenAPI api, ResponseViolations violations, @Nullable URI origin) {
        this.validator =
                OpenApiInteractionValidator.createFor(api)
                        .withBasePathOverride(TEST_BASE_PATH)
                        .build();
        this.operations = new ApiOperationResolver(api, TEST_BASE_PATH, false);
        this.violations = violations;
        this.origin = origin;
    }

    /** Parses the spec file named by {@link #BUNDLE_FILE_PROPERTY}, else the pom's default path. */
    static OpenAPI bundledSpec() {
        Path bundle =
                Optional.ofNullable(System.getProperty(BUNDLE_FILE_PROPERTY))
                        .map(Path::of)
                        .orElse(DEFAULT_BUNDLE_FILE);
        if (!Files.isRegularFile(bundle)) {
            throw new IllegalStateException(
                    "No bundled spec at "
                            + bundle.toAbsolutePath()
                            + "; run ./mvnw generate-resources first");
        }
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        return new OpenApiLoader()
                .loadApi(SpecSource.specUrl(bundle.toUri().toString()), List.of(), options);
    }

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        URL url = event.getSource().getURL();
        origin = URI.create(url.toString());
    }

    /** Validates one exchange; an error status reaches here as the client's failure. */
    @ResponseFilter
    public void validate(
            HttpRequest<?> request,
            @Nullable HttpResponse<?> response,
            @Nullable Throwable failure) {
        HttpResponse<?> received = response;
        if (received == null && failure instanceof HttpClientResponseException clientError) {
            received = clientError.getResponse();
        }
        if (received == null || !isOwnService(request.getUri())) {
            return;
        }
        String path = request.getUri().getRawPath();
        if (matchesAny(TEST_PROBE_ROUTES, path) || matchesAny(SERVICE_ORIGIN_ROUTES, path)) {
            return;
        }
        violations.recordJudged();
        Request.Method method = Request.Method.valueOf(request.getMethodName());
        ApiOperationMatch match = operations.findApiOperation(path, method);
        if (!publishes(match) && isClientError(received.code())) {
            return;
        }
        List<String> problems = new ArrayList<>();
        ValidationReport report = validator.validateResponse(path, method, adapt(received));
        report.getMessages().stream()
                .filter(message -> message.getLevel() == ValidationReport.Level.ERROR)
                .map(ValidationReport.Message::getMessage)
                .forEach(problems::add);
        if (problems.isEmpty() && !declaresExplicitly(match, received.code())) {
            problems.add("status " + received.code() + " is covered only by the default response");
        }
        if (!problems.isEmpty()) {
            violations.record(
                    request.getMethodName()
                            + " "
                            + path
                            + " -> "
                            + received.code()
                            + ": "
                            + String.join("; ", problems));
        }
    }

    private static boolean publishes(ApiOperationMatch match) {
        return match.isPathFound() && match.isOperationAllowed();
    }

    private static boolean isClientError(int status) {
        return status >= FIRST_ERROR_STATUS && status < FIRST_SERVER_ERROR_STATUS;
    }

    private static boolean declaresExplicitly(ApiOperationMatch match, int status) {
        return status >= FIRST_ERROR_STATUS
                || !publishes(match)
                || match.getApiOperation()
                        .getOperation()
                        .getResponses()
                        .containsKey(String.valueOf(status));
    }

    private boolean isOwnService(URI target) {
        URI own = origin;
        return own != null
                && target.getHost() != null
                && own.getScheme().equalsIgnoreCase(target.getScheme())
                && sameHost(own.getHost(), target.getHost())
                && effectivePort(own) == effectivePort(target);
    }

    private static boolean sameHost(String own, String target) {
        return own.equalsIgnoreCase(target) || isLoopback(target);
    }

    /** Recognises {@code localhost} and loopback literals without a DNS lookup. */
    private static boolean isLoopback(String host) {
        if (LOCALHOST.equalsIgnoreCase(host)) {
            return true;
        }
        String literal =
                host.startsWith("[") && host.endsWith("]")
                        ? host.substring(1, host.length() - 1)
                        : host;
        try {
            return InetAddress.ofLiteral(literal).isLoopbackAddress();
        } catch (IllegalArgumentException notALiteral) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return DEFAULT_SCHEME_HTTPS.equals(uri.getScheme().toLowerCase(Locale.ROOT))
                ? DEFAULT_HTTPS_PORT
                : DEFAULT_HTTP_PORT;
    }

    private static boolean matchesAny(List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> PathMatcher.ANT.matches(pattern, path));
    }

    private static SimpleResponse adapt(HttpResponse<?> response) {
        SimpleResponse.Builder adapted = SimpleResponse.Builder.status(response.code());
        response.getHeaders()
                .forEach((name, values) -> adapted.withHeader(name, List.copyOf(values)));
        response.getContentType().map(MediaType::toString).ifPresent(adapted::withContentType);
        body(response).ifPresent(adapted::withBody);
        return adapted.build();
    }

    private static Optional<String> body(HttpResponse<?> response) {
        return response.getBody(String.class).filter(body -> !body.isEmpty());
    }

    /** Parses the bundle once per JVM; every context validates against the same file. */
    private static final class BundledSpec {
        private static final OpenAPI API = bundledSpec();
    }
}
