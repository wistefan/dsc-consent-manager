package com.seamware.consentmanager.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Proves the correlation ID is assigned ahead of the security filter, so its refusals carry it. */
@MicronautTest(transactional = false)
@DisplayName("security refusals carry the correlation ID")
class CorrelationIdSecurityIT extends KeycloakAndPostgresTestResource {

    /** A route every caller without the PARTICIPANT role is refused on. */
    private static final String SECURED_PATH = "/participants/me";

    /** A caller-supplied correlation ID in the accepted shape. */
    private static final String INBOUND_ID = "security-trace-0001";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject JsonMapper json;

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("anonymous", null, 401),
                Arguments.of("outsider", RealmPrincipal.OUTSIDER, 403));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("refusals")
    void echoesTheIdOnARefusal(String name, RealmPrincipal principal, int status)
            throws IOException {
        MutableHttpRequest<?> request =
                HttpRequest.GET(SECURED_PATH).header(CorrelationIdFilter.HEADER, INBOUND_ID);
        if (principal != null) {
            request.bearerAuth(KeycloakTestResource.accessToken(principal));
        }

        HttpResponse<?> response =
                catchThrowableOfType(
                                HttpClientResponseException.class,
                                () -> client.toBlocking().exchange(request, String.class))
                        .getResponse();

        assertThat(response.status().getCode()).isEqualTo(status);
        assertThat(response.header(CorrelationIdFilter.HEADER)).isEqualTo(INBOUND_ID);
        Map<String, Object> body =
                json.readValue(
                        response.getBody(String.class).orElseThrow(),
                        Argument.mapOf(String.class, Object.class));
        assertThat(body).containsEntry("correlationId", INBOUND_ID);
    }
}
