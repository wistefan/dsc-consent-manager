package com.seamware.consentmanager.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Canary for {@link OpenApiResponseValidationFilter}'s wiring: real client exchanges with the
 * embedded server reach it, error bodies included, so a green suite is not a vacuous one.
 */
@MicronautTest(transactional = false)
@Property(name = ResponseValidationWiringIT.CANARY_ENABLED, value = "true")
@DisplayName("Response validation sees real client traffic")
class ResponseValidationWiringIT extends PostgresTestResource {

    static final String CANARY_ENABLED = "response-validation-canary.enabled";

    /** Deliberately absent from both allow-lists, so whatever it answers is judged. */
    private static final String CANARY_PATH = "/response-validation-canary";

    private static final String LOOPBACK_LITERAL = "127.0.0.1";

    private static final String STATUS_PATH = "/api-status";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject EmbeddedServer server;

    @Test
    void judgesAConformingResponseWithoutReportingIt() {
        long before = responseViolations.judgedCount();

        HttpResponse<String> response = client.toBlocking().exchange(STATUS_PATH, String.class);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(responseViolations.judgedCount()).isEqualTo(before + 1);
    }

    @Test
    void reportsASuccessOnARouteTheSpecDoesNotPublish() {
        client.toBlocking().exchange(CANARY_PATH + "/answer", String.class);

        assertThat(responseViolations.drain())
                .singleElement()
                .asString()
                .contains(CANARY_PATH + "/answer -> 200");
    }

    @Test
    void reportsAServerErrorDeliveredAsTheClientsFailure() {
        assertThatThrownBy(() -> client.toBlocking().exchange(CANARY_PATH + "/fail", String.class))
                .isInstanceOf(HttpClientResponseException.class);

        assertThat(responseViolations.drain())
                .singleElement()
                .asString()
                .contains(CANARY_PATH + "/fail -> 500");
    }

    @Test
    void judgesARequestThatNamesTheServerByItsLoopbackAddress() throws Exception {
        URI loopback =
                new URI(
                        server.getScheme(),
                        null,
                        LOOPBACK_LITERAL,
                        server.getPort(),
                        STATUS_PATH,
                        null,
                        null);
        long before = responseViolations.judgedCount();

        // An absolute URI overrides the client's base URL; a client built outside the context
        // would carry none of its filters, so the injected one is used.
        client.toBlocking().exchange(HttpRequest.GET(loopback), String.class);

        assertThat(responseViolations.judgedCount()).isEqualTo(before + 1);
    }

    /** Answers outside the spec, once successfully and once with a defect. */
    @Requires(property = CANARY_ENABLED, value = "true")
    @Controller(CANARY_PATH)
    static class CanaryController {

        /** A success the spec does not describe. */
        @Get("/answer")
        Map<String, String> answer() {
            return Map.of("canary", "answer");
        }

        /** A defect, which the error layer renders as a 500 problem. */
        @Get("/fail")
        String fail() {
            throw new IllegalStateException("canary defect");
        }
    }
}
