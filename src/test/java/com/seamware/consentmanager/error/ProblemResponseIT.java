package com.seamware.consentmanager.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.seamware.consentmanager.observability.CorrelationIdFilter;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Drives every problem-detail rendering over a real server, through probe routes that exist only
 * for this test, catching an unselected handler or a body rendered as {@code application/json}.
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

    /** RFC 7807's type for statuses this service mints no type for. */
    private static final String UNTYPED = "about:blank";

    /** A body that is not JSON at all, so the request fails before any model is bound. */
    private static final String MALFORMED_BODY = "{\"identifier\":";

    /** Message of the deliberate defect below; asserted absent from the 500 it produces. */
    private static final String DEFECT_MESSAGE = "jdbc:postgresql://db:5432 credentials rejected";

    /** A caller-supplied correlation ID in the accepted shape. */
    private static final String INBOUND_ID = "client-trace-0001";

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

    /** One case per refusal the framework produces before any handler of this service runs. */
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

    /** Serialization is {@code non-absent}: a null stays absent, empty values are published. */
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

    /** Every failure route of the probe, one per site that renders a problem detail. */
    static Stream<Arguments> everyFailure() {
        return Stream.of(
                Arguments.of(
                        "bad request", HttpRequest.GET(PROBE_PATH + "/refuse/bad-request"), 400),
                Arguments.of("forbidden", HttpRequest.GET(PROBE_PATH + "/refuse/forbidden"), 403),
                Arguments.of("not found", HttpRequest.GET(PROBE_PATH + "/refuse/not-found"), 404),
                Arguments.of("conflict", HttpRequest.GET(PROBE_PATH + "/refuse/conflict"), 409),
                Arguments.of("upstream", HttpRequest.GET(PROBE_PATH + "/refuse/upstream"), 502),
                Arguments.of(
                        "bean validation",
                        HttpRequest.POST(PROBE_PATH + "/validate", Map.of("identifier", " ")),
                        400),
                Arguments.of("unroutable", HttpRequest.GET(PROBE_PATH + "/no-such-route"), 404),
                Arguments.of("defect", HttpRequest.GET(PROBE_PATH + "/defect"), 500));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("everyFailure")
    @DisplayName("every problem carries the correlation ID of the response header")
    void stampsTheGeneratedCorrelationId(String name, HttpRequest<?> request, int status)
            throws IOException {
        HttpResponse<String> response = exchange(request);

        String header = response.header(CorrelationIdFilter.HEADER);
        assertThat(response.status().getCode()).isEqualTo(status);
        assertThat(header).isNotBlank();
        assertThat(bodyOf(response)).containsEntry("correlationId", header);
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("everyFailure")
    @DisplayName("a well-formed inbound X-Request-ID is echoed in header and body")
    void echoesTheInboundCorrelationId(String name, MutableHttpRequest<?> request, int status)
            throws IOException {
        HttpResponse<String> response =
                exchange(request.header(CorrelationIdFilter.HEADER, INBOUND_ID));

        assertThat(response.status().getCode()).isEqualTo(status);
        assertThat(response.header(CorrelationIdFilter.HEADER)).isEqualTo(INBOUND_ID);
        assertThat(bodyOf(response)).containsEntry("correlationId", INBOUND_ID);
    }

    @Test
    @DisplayName("a success carries the correlation ID in its header too")
    void echoesTheCorrelationIdOnSuccess() {
        HttpResponse<String> response =
                exchange(
                        HttpRequest.POST(PROBE_PATH + "/validate", Map.of("identifier", "x"))
                                .header(CorrelationIdFilter.HEADER, INBOUND_ID));

        assertThat(response.status().getCode()).isEqualTo(200);
        assertThat(response.header(CorrelationIdFilter.HEADER)).isEqualTo(INBOUND_ID);
    }

    @Test
    @DisplayName("a request failing two constraints lists both fields in errors")
    void listsEveryRejectedField() throws IOException {
        HttpResponse<String> response =
                exchange(
                        HttpRequest.POST(
                                PROBE_PATH + "/validate-pair",
                                Map.of("identifier", " ", "email", " ")));

        assertThat(response.status().getCode()).isEqualTo(400);
        assertThat(bodyOf(response))
                .containsEntry(
                        "errors",
                        List.of(
                                Map.of("field", "email", "message", "must not be blank"),
                                Map.of("field", "identifier", "message", "must not be blank")))
                .containsEntry("detail", "email: must not be blank; identifier: must not be blank");
    }

    @Test
    @DisplayName("the log line of a failure carries the correlation ID of its response")
    void logsUnderTheResponseCorrelationId() {
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logger.addAppender(captured);
        try {
            HttpResponse<String> response = get(PROBE_PATH + "/defect");

            assertThat(captured.list)
                    .extracting(event -> event.getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY))
                    .containsExactly(response.header(CorrelationIdFilter.HEADER));
        } finally {
            logger.detachAppender(captured);
        }
    }

    /** Parses the body, which arrives as text because no codec claims {@code problem+json}. */
    private Map<String, Object> bodyOf(HttpResponse<String> response) throws IOException {
        return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
    }

    /** Performs a GET, returning even a refusal as a response. */
    private HttpResponse<String> get(String path) {
        return exchange(HttpRequest.GET(path));
    }

    /** Exchanges a request, turning the error response back into an ordinary one. */
    private HttpResponse<String> exchange(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            HttpResponse<?> response = e.getResponse();
            String body = response.getBody(String.class).orElse("");
            MutableHttpResponse<String> copy =
                    HttpResponse.<String>status(response.status()).body(body);
            response.getHeaders()
                    .forEach((name, values) -> values.forEach(value -> copy.header(name, value)));
            return copy;
        }
    }

    /** A body with one constraint, enough to make the validator refuse the request. */
    @Serdeable
    record Payload(@NotBlank String identifier) {}

    /** A body with two constraints, so one request can fail both. */
    @Serdeable
    record PairPayload(@NotBlank String identifier, @NotBlank String email) {}

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
                case "upstream" -> new UpstreamServiceException(DEFECT_MESSAGE);
                default -> new IllegalArgumentException(kind);
            };
        }

        /** Exists for the constraint the request fails. */
        @Post("/validate")
        String validate(@Body @Valid Payload payload) {
            return payload.identifier();
        }

        /** Exists for the two constraints the request fails. */
        @Post("/validate-pair")
        String validatePair(@Body @Valid PairPayload payload) {
            return payload.identifier();
        }

        /** Fails the way a defect does: an exception no handler was written for. */
        @Get("/defect")
        String defect() {
            throw new IllegalStateException(DEFECT_MESSAGE);
        }
    }
}
