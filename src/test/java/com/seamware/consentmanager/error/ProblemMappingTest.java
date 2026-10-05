package com.seamware.consentmanager.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.net.URI;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins every exception this service refuses a request with to the problem detail it is rendered as.
 *
 * <p>The {@code type} URIs asserted here are literals on purpose: they are a published part of the
 * API contract that clients branch on, so a renamed slug has to fail here rather than silently in a
 * client.
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

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

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

    /**
     * Netty hands the raw request target through, so the path reaching a handler is not guaranteed
     * to be a URI. Rendering must never fail on one.
     */
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
                            "identifier: must not be blank"),
                    Arguments.of(
                            "several violations, listed in a stable order",
                            violationException(
                                    violation("register.body.lastName", "size must be <= 255"),
                                    violation("register.body.identifier", "must not be blank")),
                            "identifier: must not be blank; lastName: size must be <= 255"),
                    Arguments.of(
                            "a duplicate violation, reported once",
                            violationException(
                                    violation("register.body.identifier", "must not be blank"),
                                    violation("bulk.body.identifier", "must not be blank")),
                            "identifier: must not be blank"),
                    Arguments.of(
                            "no violation at all",
                            new ConstraintViolationException(Set.of()),
                            "The request failed validation."));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("violations")
        void rendersAsBadRequest(
                String description, ConstraintViolationException exception, String detail) {
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

    /**
     * Bundles violations in the order given; the handler is responsible for ordering the output.
     */
    private static ConstraintViolationException violationException(
            ConstraintViolation<?>... violations) {
        return new ConstraintViolationException(new LinkedHashSet<>(Arrays.asList(violations)));
    }

    /**
     * A violation of {@code propertyPath} with {@code message}.
     *
     * <p>Hand-built rather than produced by a validator: the project has no mocking framework, and
     * driving a real validator would pull a bean context into a plain unit test for the two members
     * the handler reads.
     */
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
