package com.seamware.consentmanager.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import com.seamware.consentmanager.api.generated.model.ProblemFieldError;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.support.LogCapture;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins every exception this service refuses a request with to the problem detail it is rendered as.
 * The {@code type} URIs are literals on purpose: clients branch on them.
 */
@DisplayName("domain exceptions render as the problem detail their type declares")
class ProblemMappingTest {

    /** Path every case is raised on, so {@code instance} has something to be asserted against. */
    private static final String REQUEST_PATH = "/v1/users/register";

    /** Namespace every problem type URI is minted under. */
    private static final String TYPE_PREFIX = "https://consent-manager.example/problems/";

    /** Detail a 5xx publishes in place of the message, which names an internal dependency. */
    private static final String SERVER_ERROR_DETAIL =
            "An unexpected error occurred. Please try again later.";

    /** Message of the upstream failure, asserted absent from the rendered body. */
    private static final String UPSTREAM_MESSAGE =
            "contract-service at 10.0.0.7 refused the TCP connection";

    private final ApiExceptionHandler handler = new ApiExceptionHandler(serverErrorDetail(false));

    /** One case per exception, with the status, type and title it is contractually rendered as. */
    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(
                        new BadRequestException("identifier: must not be blank"),
                        400,
                        "bad-request",
                        "Bad Request",
                        "identifier: must not be blank"),
                Arguments.of(
                        new ForbiddenException("Only the data subject may erase this record."),
                        403,
                        "forbidden",
                        "Forbidden",
                        "Only the data subject may erase this record."),
                Arguments.of(
                        new NotFoundException(
                                "No user is registered under the supplied identifier."),
                        404,
                        "not-found",
                        "Not Found",
                        "No user is registered under the supplied identifier."),
                Arguments.of(
                        new ConflictException("The user is already linked to this participant."),
                        409,
                        "conflict",
                        "Conflict",
                        "The user is already linked to this participant."),
                Arguments.of(
                        new UpstreamServiceException(UPSTREAM_MESSAGE),
                        502,
                        "upstream-service",
                        "Bad Gateway",
                        SERVER_ERROR_DETAIL));
    }

    @ParameterizedTest(name = "{2} -> {1}")
    @MethodSource("refusals")
    void rendersDeclaredProblem(
            ApiException exception, int status, String slug, String title, String detail) {
        HttpResponse<ProblemDetail> response =
                handler.handle(HttpRequest.GET(REQUEST_PATH), exception);

        assertThat(response.status().getCode()).isEqualTo(status);
        assertThat(response.getContentType()).hasValue(MediaType.of(ProblemType.MEDIA_TYPE));
        assertThat(response.body())
                .isNotNull()
                .satisfies(
                        problem -> {
                            assertThat(problem.getType()).isEqualTo(URI.create(TYPE_PREFIX + slug));
                            assertThat(problem.getTitle()).isEqualTo(title);
                            assertThat(problem.getStatus()).isEqualTo(status);
                            assertThat(problem.getDetail()).isEqualTo(detail);
                            assertThat(problem.getInstance()).isEqualTo(URI.create(REQUEST_PATH));
                        });
    }

    @Test
    @DisplayName("a server-side failure publishes none of what it was told")
    void withholdsTheMessageOfAServerError() {
        HttpResponse<ProblemDetail> response =
                handler.handle(
                        HttpRequest.GET(REQUEST_PATH),
                        new UpstreamServiceException(UPSTREAM_MESSAGE));

        assertThat(response.body().getDetail())
                .isEqualTo(SERVER_ERROR_DETAIL)
                .doesNotContain("10.0.0.7");
    }

    /** Whether a 5xx publishes its message is one setting, honoured by every site rendering one. */
    @Nested
    @DisplayName("consent-manager.errors.expose-exception-detail")
    class ExposedServerErrorDetail {

        /** A personal identifier that must reach no log line above DEBUG. */
        private static final String PERSONAL_IDENTIFIER = "did:web:example.com:alice";

        /** A personal email that must reach no log line above DEBUG. */
        private static final String PERSONAL_EMAIL = "alice@example.com";

        /** A message a production deployment must never publish. */
        private static final String INTERNAL_MESSAGE = "pool exhausted on db-primary.internal:5432";

        /** One renderer per site that substitutes the detail of a server error. */
        enum Site {
            UNHANDLED_EXCEPTION {
                @Override
                ProblemDetail render(
                        ServerErrorDetail detail, HttpRequest<?> request, Exception failure) {
                    return new GlobalExceptionHandler(detail).handle(request, failure).body();
                }
            },
            UPSTREAM_SERVICE {
                @Override
                ProblemDetail render(
                        ServerErrorDetail detail, HttpRequest<?> request, Exception failure) {
                    return new ApiExceptionHandler(detail)
                            .handle(
                                    request,
                                    new UpstreamServiceException(
                                            failure.getMessage(), failure.getCause()))
                            .body();
                }
            },
            FRAMEWORK_ERROR {
                @Override
                ProblemDetail render(
                        ServerErrorDetail detail, HttpRequest<?> request, Exception failure) {
                    return new ProblemErrorResponseProcessor(detail)
                            .processResponse(
                                    ErrorContext.builder(request).cause(failure).build(),
                                    HttpResponse.serverError())
                            .body();
                }
            };

            abstract ProblemDetail render(
                    ServerErrorDetail detail, HttpRequest<?> request, Exception failure);
        }

        static Stream<Arguments> cases() {
            return Arrays.stream(Site.values())
                    .flatMap(
                            site ->
                                    Stream.of(
                                            Arguments.of(site, false, SERVER_ERROR_DETAIL),
                                            Arguments.of(site, true, INTERNAL_MESSAGE)));
        }

        @ParameterizedTest(name = "{0}, exposed={1}")
        @MethodSource("cases")
        void publishesTheMessageOnlyWhenExposed(Site site, boolean exposed, String expected) {
            ProblemDetail problem =
                    site.render(
                            serverErrorDetail(exposed),
                            HttpRequest.GET(REQUEST_PATH),
                            new IllegalStateException(INTERNAL_MESSAGE));

            assertThat(problem.getDetail()).isEqualTo(expected);
        }

        /** Above DEBUG only the class chain is logged; messages and paths may carry PII. */
        @ParameterizedTest(name = "{0}")
        @EnumSource(Site.class)
        void logsNoMessageOrPathAtError(Site site) {
            HttpRequest<?> request =
                    HttpRequest.GET(
                            "/v1/users/" + PERSONAL_IDENTIFIER + "?email=" + PERSONAL_EMAIL);
            Exception failure =
                    new IllegalStateException(
                            PERSONAL_IDENTIFIER,
                            new SQLException(
                                    "Key (email)=(" + PERSONAL_EMAIL + ") already exists"));

            List<ILoggingEvent> logged;
            try (LogCapture capture = LogCapture.attach()) {
                site.render(serverErrorDetail(false), request, failure);
                logged = capture.events();
            }

            List<String> errors =
                    logged.stream()
                            .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                            .map(LogCapture::render)
                            .toList();
            assertThat(errors)
                    .isNotEmpty()
                    .allSatisfy(
                            line ->
                                    assertThat(line)
                                            .contains(SQLException.class.getName())
                                            .doesNotContain(PERSONAL_IDENTIFIER)
                                            .doesNotContain(PERSONAL_EMAIL));
        }

        @ParameterizedTest(name = "message {0}")
        @MethodSource("blankMessages")
        void fallsBackWhenThereIsNoMessage(String message) {
            assertThat(serverErrorDetail(true).detailFor(new IllegalStateException(message)))
                    .isEqualTo(SERVER_ERROR_DETAIL);
        }

        static Stream<String> blankMessages() {
            return Stream.of(null, "", "  ");
        }

        @Test
        @DisplayName("no failure at all reads as the fixed sentence")
        void fallsBackWithoutAFailure() {
            assertThat(serverErrorDetail(true).detailFor(null)).isEqualTo(SERVER_ERROR_DETAIL);
        }
    }

    @Test
    @DisplayName("errors is generated as a typed list, not a degraded List<Object>")
    void errorsKeepTheirItemType() throws NoSuchFieldException {
        Type itemType =
                ((ParameterizedType)
                                ProblemDetail.class.getDeclaredField("errors").getGenericType())
                        .getActualTypeArguments()[0];

        assertThat(itemType).isEqualTo(ProblemFieldError.class);
    }

    /** Netty passes the raw request target, which need not be a URI; rendering must not fail. */
    @Nested
    @DisplayName("request paths that are not URIs")
    class RequestPaths {

        /** A path already carrying percent-encoding, which must not be encoded a second time. */
        private static final String ENCODED_PATH = "/v1/users/urn%3Aexample%3Auser%3A42";

        static Stream<Arguments> paths() {
            return Stream.of(
                    Arguments.of("plain", REQUEST_PATH, REQUEST_PATH),
                    Arguments.of("already percent-encoded", ENCODED_PATH, ENCODED_PATH),
                    Arguments.of("non-ASCII", "/v1/users/Jos\u00e9", "/v1/users/Jos%C3%A9"),
                    Arguments.of("curly braces", "/v1/users/{id}", "/v1/users/%7Bid%7D"),
                    Arguments.of("pipe and caret", "/v1/users/a|b^c", "/v1/users/a%7Cb%5Ec"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("paths")
        void escapesWhatIsNotAlreadyAUri(String name, String path, String expected) {
            ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail("No such user.", path);

            assertThat(problem.getInstance()).isNotNull();
            assertThat(problem.getInstance().toASCIIString()).isEqualTo(expected);
        }

        @Test
        @DisplayName("a path that is no help at all simply loses the optional member")
        void omitsAnInstanceItCannotRender() {
            ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail("No such user.", "  ");

            assertThat(problem.getInstance()).isNull();
        }
    }

    /** Bean-validation failures are their own path: no {@link ApiException} is ever constructed. */
    @Nested
    @DisplayName("bean-validation failures")
    class ConstraintViolations {

        private final ConstraintViolationProblemHandler violationHandler =
                new ConstraintViolationProblemHandler();

        /** One case per shape the detail string has to cope with. */
        static Stream<Arguments> violations() {
            return Stream.of(
                    Arguments.of(
                            "one violation",
                            violationException(
                                    violation("register.body.identifier", "must not be blank")),
                            "identifier: must not be blank",
                            List.of(new ProblemFieldError("identifier", "must not be blank"))),
                    Arguments.of(
                            "several violations, listed in a stable order",
                            violationException(
                                    violation("register.body.lastName", "size must be <= 255"),
                                    violation("register.body.identifier", "must not be blank")),
                            "identifier: must not be blank; lastName: size must be <= 255",
                            List.of(
                                    new ProblemFieldError("identifier", "must not be blank"),
                                    new ProblemFieldError("lastName", "size must be <= 255"))),
                    Arguments.of(
                            "a duplicate violation, reported once",
                            violationException(
                                    violation("register.body.identifier", "must not be blank"),
                                    violation("bulk.body.identifier", "must not be blank")),
                            "identifier: must not be blank",
                            List.of(new ProblemFieldError("identifier", "must not be blank"))),
                    Arguments.of(
                            "no violation at all",
                            new ConstraintViolationException(Set.of()),
                            "The request failed validation.",
                            null));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("violations")
        void rendersAsBadRequest(
                String description,
                ConstraintViolationException exception,
                String detail,
                List<ProblemFieldError> errors) {
            HttpResponse<?> response =
                    violationHandler.handle(HttpRequest.POST(REQUEST_PATH, ""), exception);

            assertThat(response.status().getCode()).isEqualTo(400);
            assertThat(response.body())
                    .isInstanceOfSatisfying(
                            ProblemDetail.class,
                            problem -> {
                                assertThat(problem.getType())
                                        .isEqualTo(URI.create(TYPE_PREFIX + "bad-request"));
                                assertThat(problem.getTitle()).isEqualTo("Bad Request");
                                assertThat(problem.getStatus()).isEqualTo(400);
                                assertThat(problem.getDetail()).isEqualTo(detail);
                                assertThat(problem.getErrors()).isEqualTo(errors);
                                assertThat(problem.getInstance())
                                        .isEqualTo(URI.create(REQUEST_PATH));
                            });
        }

        @Test
        @DisplayName("the method and parameter names the validator prefixes stay internal")
        void publishesOnlyTheLeafOfThePropertyPath() {
            HttpResponse<?> response =
                    violationHandler.handle(
                            HttpRequest.POST(REQUEST_PATH, ""),
                            violationException(
                                    violation(
                                            "register.userRegistration.identifier",
                                            "must not be blank")));

            assertThat(((ProblemDetail) response.body()).getDetail())
                    .doesNotContain("register")
                    .doesNotContain("userRegistration");
        }
    }

    private static ServerErrorDetail serverErrorDetail(boolean exposed) {
        ConsentManagerConfiguration.Errors errors = new ConsentManagerConfiguration.Errors();
        errors.setExposeExceptionDetail(exposed);
        return new ServerErrorDetail(errors);
    }

    /** Bundles violations in the order given; ordering the output is the handler's job. */
    private static ConstraintViolationException violationException(
            ConstraintViolation<?>... violations) {
        return new ConstraintViolationException(new LinkedHashSet<>(Arrays.asList(violations)));
    }

    /** A hand-built violation, sparing a plain unit test a validator and its bean context. */
    private static ConstraintViolation<?> violation(String propertyPath, String message) {
        return new StubViolation(new StubPath(propertyPath), message);
    }

    /** A {@link Path} over the dot-separated node names it was built from. */
    private record StubPath(String path) implements Path {

        @Override
        public Iterator<Node> iterator() {
            return Stream.of(path.split("\\.")).map(name -> (Node) new StubNode(name)).iterator();
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /** A named {@link Path.Node}; nothing the handler reads needs the iterable or key members. */
    private record StubNode(String name) implements Path.Node {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isInIterable() {
            return false;
        }

        @Override
        public Integer getIndex() {
            return null;
        }

        @Override
        public Object getKey() {
            return null;
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.PROPERTY;
        }

        @Override
        public <T extends Path.Node> T as(Class<T> nodeType) {
            return nodeType.cast(this);
        }
    }

    /** A violation carrying only the path and message; the handler reads nothing else. */
    private record StubViolation(Path propertyPath, String message)
            implements ConstraintViolation<Object> {

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public String getMessageTemplate() {
            return message;
        }

        @Override
        public Object getRootBean() {
            return null;
        }

        @Override
        public Class<Object> getRootBeanClass() {
            return Object.class;
        }

        @Override
        public Object getLeafBean() {
            return null;
        }

        @Override
        public Object[] getExecutableParameters() {
            return new Object[0];
        }

        @Override
        public Object getExecutableReturnValue() {
            return null;
        }

        @Override
        public Path getPropertyPath() {
            return propertyPath;
        }

        @Override
        public Object getInvalidValue() {
            return null;
        }

        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return null;
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            return type.cast(this);
        }
    }
}
