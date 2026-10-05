package com.seamware.consentmanager.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drives every client-error status over a real server, so the mapping is asserted where it actually
 * matters: an exception handler that is never selected, or a body rendered as {@code
 * application/json}, would still satisfy the unit test.
 *
 * <p>The routes exercised here exist only for this test. Real endpoints arrive with the later steps
 * of this ticket and bring their own status assertions.
 */
@MicronautTest
@Property(name = ProblemResponseIT.PROBE_ENABLED, value = "true")
@DisplayName("problem details over HTTP")
class ProblemResponseIT extends PostgresTestResource {

    /** Enables the probe controller, so no other integration test inherits these routes. */
    static final String PROBE_ENABLED = "problem-probe.enabled";

    /** Root of the probe routes. */
    private static final String PROBE_PATH = "/problem-probe";

    /** RFC 7807 media type every failure of this service is rendered as. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** Namespace every problem type URI is minted under. */
    private static final String TYPE_PREFIX = "https://consent-manager.example/problems/";

    /**
     * RFC 7807's "no semantics beyond the status", used for statuses this service mints no type
     * for.
     */
    private static final String UNTYPED = "about:blank";

    /** A body that is not JSON at all, so the request fails before any model is bound. */
    private static final String MALFORMED_BODY = "{\"identifier\":";

    /** Message of the deliberate defect below; asserted absent from the 500 it produces. */
    private static final String DEFECT_MESSAGE = "jdbc:postgresql://db:5432 credentials rejected";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject JsonMapper json;

    /** One case per client error an endpoint of this service can answer with. */
    static Stream<Arguments> clientErrors() {
        return Stream.of(
                Arguments.of("/refuse/bad-request", 400, "bad-request", "Bad Request"),
                Arguments.of("/refuse/forbidden", 403, "forbidden", "Forbidden"),
                Arguments.of("/refuse/not-found", 404, "not-found", "Not Found"),
                Arguments.of("/refuse/conflict", 409, "conflict", "Conflict"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("clientErrors")
    void rendersTheDeclaredProblem(String path, int status, String slug, String title)
            throws IOException {
        HttpResponse<String> response = get(PROBE_PATH + path);

        assertThat(response.status().getCode()).isEqualTo(status);
        assertThat(response.getContentType().orElseThrow().toString()).isEqualTo(PROBLEM_JSON);
        assertThat(bodyOf(response))
                .containsEntry("type", TYPE_PREFIX + slug)
                .containsEntry("title", title)
                .containsEntry("status", status)
                .containsEntry("instance", PROBE_PATH + path);
    }

    @Test
    @DisplayName("a rejected request body is a 400 naming the field, not a framework error")
    void rendersABeanValidationFailureAsAProblem() throws IOException {
        HttpResponse<String> response =
                exchange(HttpRequest.POST(PROBE_PATH + "/validate", Map.of("identifier", " ")));

        assertThat(response.status().getCode()).isEqualTo(400);
        assertThat(response.getContentType().orElseThrow().toString()).isEqualTo(PROBLEM_JSON);
        assertThat(bodyOf(response))
                .containsEntry("type", TYPE_PREFIX + "bad-request")
                .containsEntry("title", "Bad Request")
                .hasEntrySatisfying(
                        "detail", detail -> assertThat(detail.toString()).contains("identifier"))
                .doesNotContainKey("_embedded")
                .doesNotContainKey("_links");
    }

    /**
     * One case per refusal the framework produces before any handler of this service is reached.
     */
    static Stream<Arguments> frameworkErrors() {
        return Stream.of(
                Arguments.of(
                        "unroutable path",
                        HttpRequest.GET(PROBE_PATH + "/no-such-route"),
                        404,
                        TYPE_PREFIX + "not-found"),
                Arguments.of(
                        "method the route does not accept",
                        HttpRequest.POST(PROBE_PATH + "/defect", "{}")
                                .contentType(MediaType.APPLICATION_JSON),
                        405,
                        UNTYPED),
                Arguments.of(
                        "malformed request body",
                        HttpRequest.POST(PROBE_PATH + "/validate", MALFORMED_BODY)
                                .contentType(MediaType.APPLICATION_JSON),
                        400,
                        TYPE_PREFIX + "bad-request"),
                Arguments.of(
                        "unsupported content type",
                        HttpRequest.POST(PROBE_PATH + "/validate", "identifier=x")
                                .contentType(MediaType.TEXT_PLAIN),
                        415,
                        UNTYPED));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("frameworkErrors")
    @DisplayName("a refusal the framework raised is a problem detail too")
    void rendersFrameworkErrorsAsProblems(
            String name, HttpRequest<?> request, int status, String type) throws IOException {
        HttpResponse<String> response = exchange(request);

        assertThat(response.status().getCode()).isEqualTo(status);
        assertThat(response.getContentType().orElseThrow().toString()).isEqualTo(PROBLEM_JSON);
        assertThat(bodyOf(response))
                .containsEntry("type", type)
                .containsEntry("status", status)
                .doesNotContainKey("_embedded")
                .doesNotContainKey("_links");
    }

    /**
     * A refusal carrying no message publishes no {@code detail} at all.
     *
     * <p>Serialization is configured {@code non-absent} rather than the default {@code non-empty},
     * so empty strings and empty collections are published everywhere. This pins the half of that
     * which problem details depend on: a null stays absent, so an unexplained refusal does not grow
     * a {@code "detail": ""} field that reads as an explanation the service failed to produce.
     */
    @Test
    @DisplayName("a refusal with nothing to explain omits detail rather than publishing it empty")
    void omitsAnAbsentDetailRatherThanPublishingItEmpty() throws IOException {
        HttpResponse<String> response = get(PROBE_PATH + "/refuse/silent");

        assertThat(response.status().getCode()).isEqualTo(404);
        assertThat(bodyOf(response))
                .containsEntry("type", TYPE_PREFIX + "not-found")
                .doesNotContainKey("detail");
    }

    @Test
    @DisplayName("an unhandled defect is a 500 that discloses nothing about itself")
    void withholdsEverythingAboutAnUnexpectedFailure() throws IOException {
        HttpResponse<String> response = get(PROBE_PATH + "/defect");

        assertThat(response.status().getCode()).isEqualTo(500);
        assertThat(response.getContentType().orElseThrow().toString()).isEqualTo(PROBLEM_JSON);
        assertThat(bodyOf(response))
                .containsEntry("type", TYPE_PREFIX + "internal-server-error")
                .containsEntry("title", "Internal Server Error");
        assertThat(response.body())
                .doesNotContain(DEFECT_MESSAGE)
                .doesNotContain("IllegalStateException")
                .doesNotContain("\tat ")
                .doesNotContain("com.seamware.consentmanager.error.ProblemResponseIT");
    }

    /** Parses the body, which arrives as text because no codec claims {@code problem+json}. */
    private Map<String, Object> bodyOf(HttpResponse<String> response) throws IOException {
        return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
    }

    /** Performs a GET, returning the response even when it is a refusal. */
    private HttpResponse<String> get(String path) {
        return exchange(HttpRequest.GET(path));
    }

    /** Exchanges a request, turning the error response back into an ordinary one. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            HttpResponse<?> response = e.getResponse();
            String body = response.getBody(String.class).orElse("");
            return (HttpResponse<String>)
                    HttpResponse.status(response.status())
                            .body(body)
                            .contentType(response.getContentType().orElseThrow());
        }
    }

    /** A body with one constraint, enough to make the validator refuse the request. */
    @Serdeable
    record Payload(@NotBlank String identifier) {}

    /** One route per failure the error layer has to render, plus one genuine defect. */
    @Requires(property = PROBE_ENABLED, value = "true")
    @Validated
    @Controller(PROBE_PATH)
    static class ProblemProbeController {

        /** Throws the refusal named by the path segment. */
        @Get("/refuse/{kind}")
        String refuse(String kind) {
            throw switch (kind) {
                case "bad-request" -> new BadRequestException("identifier: must not be blank");
                case "forbidden" -> new ForbiddenException("Only the data subject may do this.");
                case "not-found" -> new NotFoundException("No user is registered under it.");
                case "conflict" -> new ConflictException("Already linked to this participant.");
                case "silent" -> new NotFoundException(null);
                default -> new IllegalArgumentException(kind);
            };
        }

        /** Accepts nothing; the point is the constraint the request fails. */
        @Post("/validate")
        String validate(@Body @Valid Payload payload) {
            return payload.identifier();
        }

        /** Fails the way a defect does: an exception no handler was written for. */
        @Get("/defect")
        String defect() {
            throw new IllegalStateException(DEFECT_MESSAGE);
        }
    }
}
