package com.seamware.consentmanager.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Proves the response check reports drift, skips the allow-lists and ignores foreign hosts. */
class OpenApiResponseValidationFilterTest {

    /** The embedded server reports the machine name, not a loopback one. */
    private static final String OWN_ORIGIN = "http://consent-manager.test:8080";

    private static final String LOCALHOST_ORIGIN = "http://localhost:8080";

    private static final String FOREIGN_ORIGIN = "http://contracts.example:8080";

    private static final String OTHER_PORT_ORIGIN = "http://localhost:9999";

    private static final String IPV4_LOOPBACK_ORIGIN = "http://127.0.0.1:8080";

    private static final String IPV6_LOOPBACK_ORIGIN = "http://[::1]:8080";

    private static final String CONFORMING_STATUS = "{\"status\":\"ok\"}";

    private static final String PROBLEM =
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}";

    private final ResponseViolations violations = new ResponseViolations();

    private final OpenApiResponseValidationFilter filter =
            new OpenApiResponseValidationFilter(
                    OpenApiResponseValidationFilter.bundledSpec(),
                    violations,
                    URI.create(OWN_ORIGIN));

    static Stream<Arguments> exchanges() {
        return Stream.of(
                Arguments.of(
                        "conforming body",
                        OWN_ORIGIN,
                        "/api-status",
                        200,
                        json(CONFORMING_STATUS),
                        false),
                Arguments.of(
                        "non-conforming body", OWN_ORIGIN, "/api-status", 200, json("{}"), true),
                Arguments.of(
                        "undeclared status",
                        OWN_ORIGIN,
                        "/api-status",
                        201,
                        json(CONFORMING_STATUS),
                        true),
                Arguments.of(
                        "declared problem", OWN_ORIGIN, "/users/me", 401, problem(PROBLEM), false),
                Arguments.of("problem as JSON", OWN_ORIGIN, "/users/me", 401, json(PROBLEM), true),
                Arguments.of(
                        "test probe",
                        OWN_ORIGIN,
                        "/role-matrix-probe/user",
                        200,
                        text("USER"),
                        false),
                Arguments.of("management", OWN_ORIGIN, "/health/liveness", 200, json("{}"), false),
                Arguments.of("prometheus", OWN_ORIGIN, "/prometheus", 200, text("up 1"), false),
                Arguments.of(
                        "spec document",
                        OWN_ORIGIN,
                        "/openapi.yaml",
                        200,
                        text("openapi: 3.0.3"),
                        false),
                Arguments.of(
                        "swagger ui",
                        OWN_ORIGIN,
                        "/swagger-ui/index.html",
                        200,
                        text("<html/>"),
                        false),
                Arguments.of("unlisted path", OWN_ORIGIN, "/no-such-route", 200, json("{}"), true),
                Arguments.of(
                        "unlisted path refused",
                        OWN_ORIGIN,
                        "/no-such-route",
                        404,
                        problem(PROBLEM),
                        false),
                Arguments.of(
                        "unlisted path failing",
                        OWN_ORIGIN,
                        "/no-such-route",
                        500,
                        problem(PROBLEM),
                        true),
                Arguments.of(
                        "jwks is validated",
                        OWN_ORIGIN,
                        "/.well-known/jwks.json",
                        200,
                        json("{}"),
                        true),
                Arguments.of(
                        "localhost alias", LOCALHOST_ORIGIN, "/api-status", 200, json("{}"), true),
                Arguments.of(
                        "IPv4 loopback alias",
                        IPV4_LOOPBACK_ORIGIN,
                        "/api-status",
                        200,
                        json("{}"),
                        true),
                Arguments.of(
                        "IPv6 loopback alias",
                        IPV6_LOOPBACK_ORIGIN,
                        "/api-status",
                        200,
                        json("{}"),
                        true),
                Arguments.of("foreign host", FOREIGN_ORIGIN, "/api-status", 200, json("{}"), false),
                Arguments.of(
                        "foreign port", OTHER_PORT_ORIGIN, "/api-status", 200, json("{}"), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exchanges")
    void reportsOnlyOwnResponsesThatBreakTheSpec(
            String scenario,
            String origin,
            String path,
            int status,
            HttpResponse<String> response,
            boolean reported) {
        HttpResponse<String> received =
                HttpResponse.<String>status(HttpStatus.valueOf(status))
                        .body(response.body())
                        .contentType(response.getContentType().orElseThrow());

        filter.validate(HttpRequest.GET(origin + path), received, null);

        assertThat(violations.drain()).as(scenario).hasSize(reported ? 1 : 0);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "own service, " + OWN_ORIGIN + "/api-status, 1",
        "loopback alias, " + IPV4_LOOPBACK_ORIGIN + "/api-status, 1",
        "allow-listed route, " + OWN_ORIGIN + "/health/liveness, 0",
        "foreign host, " + FOREIGN_ORIGIN + "/api-status, 0"
    })
    void countsOnlyTheExchangesItJudges(String scenario, String target, long judged) {
        filter.validate(HttpRequest.GET(target), json(CONFORMING_STATUS), null);

        assertThat(violations.judgedCount()).as(scenario).isEqualTo(judged);
    }

    @Test
    void acceptsARefusalOfAnUnpublishedMethod() {
        HttpResponse<String> refused =
                HttpResponse.<String>status(HttpStatus.METHOD_NOT_ALLOWED)
                        .body(PROBLEM)
                        .contentType(MediaType.APPLICATION_JSON_PROBLEM);

        filter.validate(HttpRequest.PUT(OWN_ORIGIN + "/api-status", "{}"), refused, null);

        assertThat(violations.drain()).isEmpty();
    }

    @Test
    void validatesAnErrorStatusDeliveredAsTheClientsFailure() {
        HttpResponse<String> unauthorized =
                HttpResponse.<String>status(HttpStatus.UNAUTHORIZED)
                        .body("{}")
                        .contentType(MediaType.APPLICATION_JSON_PROBLEM);

        filter.validate(
                HttpRequest.GET(OWN_ORIGIN + "/users/me"),
                null,
                new HttpClientResponseException("Unauthorized", unauthorized));

        assertThat(violations.drain()).singleElement().asString().contains("/users/me -> 401");
    }

    private static HttpResponse<String> json(String body) {
        return HttpResponse.ok(body).contentType(MediaType.APPLICATION_JSON);
    }

    private static HttpResponse<String> problem(String body) {
        return HttpResponse.ok(body).contentType(MediaType.APPLICATION_JSON_PROBLEM);
    }

    private static HttpResponse<String> text(String body) {
        return HttpResponse.ok(body).contentType(MediaType.TEXT_PLAIN);
    }
}
