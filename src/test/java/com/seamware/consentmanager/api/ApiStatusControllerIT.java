package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.generated.model.ApiStatus;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link ApiStatusController} is actually routed, and the served {@code bearerAuth} token contract
 * renders line by line rather than collapsing into one folded line.
 */
@MicronautTest
@DisplayName("API status endpoint and published OpenAPI specification")
class ApiStatusControllerIT extends PostgresTestResource {

    /** Path of the status endpoint, without the API prefix the test environment clears. */
    private static final String API_STATUS_PATH = "/api-status";

    /** Where the bundled specification is served. */
    private static final String SPEC_PATH = "/openapi.yaml";

    /** The spec's example status, as a literal so changing the controller constant fails here. */
    private static final String EXPECTED_STATUS_VALUE = "ok";

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Test
    @DisplayName("GET /api-status is routed and returns the ApiStatus body")
    void apiStatusReturnsOk() {
        HttpResponse<ApiStatus> response =
                httpClient.toBlocking().exchange(HttpRequest.GET(API_STATUS_PATH), ApiStatus.class);

        assertThat(response.code())
                .as("The status endpoint must be routed, not 404")
                .isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.body())
                .as("Response body should be an ApiStatus")
                .isNotNull()
                .extracting(ApiStatus::getStatus)
                .isEqualTo(EXPECTED_STATUS_VALUE);
    }

    @ParameterizedTest(name = "the token contract renders a markdown line starting \"{0}\"")
    @ValueSource(
            strings = {
                "| Claim | Required | Description |",
                "| --- | --- | --- |",
                "| `iss` | yes |",
                "| `aud` | yes |",
                "| *user identifier* | yes |",
                "| *participant identifier* | yes |",
                "| *participant identifier* | no |",
                "| Role | Granted to | Scope |",
                "| `CATALOG` |",
                "### User Access Token",
                "### Participant Access Token",
                "### Catalog Token"
            })
    @DisplayName("The resolved token contract renders as markdown, not as folded run-on text")
    void tokenContractRendersAsMarkdown(String expectedLinePrefix) {
        assertThat(bearerAuthDescriptionLines())
                .as(
                        "The bearerAuth description must contain a line starting with \"%s\"."
                                + " A folded YAML block scalar (>) joins consecutive lines with a"
                                + " space and collapses the markdown tables into run-on text.",
                        expectedLinePrefix)
                .anyMatch(line -> line.startsWith(expectedLinePrefix));
    }

    @ParameterizedTest(name = "the API description renders a markdown line starting \"{0}\"")
    @ValueSource(strings = {"- `USER` \u2014", "- `PARTICIPANT` \u2014", "- `CATALOG` \u2014"})
    @DisplayName("The role list in the API description renders as a markdown bullet list")
    void roleListRendersAsMarkdown(String expectedLinePrefix) {
        String description = stringAt(parseSpec(), "info", "description");

        assertThat(description.lines())
                .as(
                        "The info description must contain a bullet starting with \"%s\"",
                        expectedLinePrefix)
                .anyMatch(line -> line.startsWith(expectedLinePrefix));
    }

    /** The {@code bearerAuth} description as served, split into rendered lines. */
    private List<String> bearerAuthDescriptionLines() {
        return stringAt(parseSpec(), "components", "securitySchemes", "bearerAuth", "description")
                .lines()
                .toList();
    }

    /** Fetches the served bundle and parses it as YAML. */
    private Map<String, Object> parseSpec() {
        String body =
                httpClient
                        .toBlocking()
                        .retrieve(HttpRequest.GET(SPEC_PATH).accept(MediaType.ALL), String.class);
        return new Yaml().load(body);
    }

    /** Reads a nested string from parsed YAML, failing with the path when a segment is missing. */
    @SuppressWarnings("unchecked")
    private static String stringAt(Map<String, Object> document, String... path) {
        Object current = document;
        for (String key : path) {
            assertThat(current)
                    .as("Specification path %s should resolve to a mapping", String.join(".", path))
                    .isInstanceOf(Map.class);
            current = ((Map<String, Object>) current).get(key);
            assertThat(current)
                    .as("Specification should define %s", String.join(".", path))
                    .isNotNull();
        }
        return current.toString();
    }
}
