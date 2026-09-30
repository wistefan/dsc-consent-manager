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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Integration test for {@link ApiStatusController} and for the split OpenAPI specification it is
 * generated from.
 *
 * <p>Three things are verified here:
 *
 * <ul>
 *   <li>{@code GET /api-status} is actually <em>routed</em>. The OpenAPI generator emits only an
 *       abstract controller, which is not a bean, so without the hand-written concrete subclass the
 *       endpoint would return 404 and any test written against it would pass vacuously.
 *   <li>Every file of the referenced specification layout is published under {@code /static}, which
 *       is what makes the relative {@code $ref}s in {@code openapi.yaml} resolve when Swagger UI
 *       loads the spec from the running service.
 *   <li>The token contract documented on the {@code bearerAuth} scheme is <em>rendered</em> as the
 *       markdown it is written as. The contract is a deliverable that integrators configure their
 *       identity provider against, so the served specification is parsed and the resolved
 *       description asserted line by line: substring matching on the raw YAML would still pass if a
 *       folded block scalar collapsed every table into a single run-on line.
 * </ul>
 *
 * <p>The {@code test} environment clears {@code micronaut.server.context-path}, so endpoints are
 * addressed without the {@code /v1} prefix that the {@code servers} block declares.
 */
@MicronautTest
@DisplayName("API status endpoint and published OpenAPI specification")
class ApiStatusControllerIT extends PostgresTestResource {

    /** Path of the status endpoint, without the context path the test environment clears. */
    private static final String API_STATUS_PATH = "/api-status";

    /** Root of the published specification tree. */
    private static final String STATIC_SPEC_ROOT = "/static/";

    /** Root document of the published specification tree. */
    private static final String ROOT_SPEC_FILE = "openapi.yaml";

    /**
     * Status value the specification declares as the {@code example} for {@code ApiStatus.status}.
     * Asserted as a literal rather than against the controller's own constant, so that changing the
     * constant breaks the test instead of silently redefining the documented contract.
     */
    private static final String EXPECTED_STATUS_VALUE = "ok";

    /** JSON Reference key used by OpenAPI to point at an external component. */
    private static final String REFERENCE_KEY = "$ref";

    /** Separator between the file part and the fragment part of a JSON Reference. */
    private static final String REFERENCE_FRAGMENT_SEPARATOR = "#";

    /** Prefix used by the specification's relative references. */
    private static final String RELATIVE_PATH_PREFIX = "./";

    /** HTTP client targeting the embedded test server. */
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

    @ParameterizedTest(name = "{0} is published under /static")
    @ValueSource(
            strings = {
                "openapi.yaml",
                "components/security.yaml",
                "components/schemas/ApiStatus.yaml",
                "components/schemas/ProblemDetail.yaml",
                "components/responses/Unauthorized.yaml",
                "components/responses/Forbidden.yaml"
            })
    @DisplayName("Every specification file is served, so relative $refs resolve for Swagger UI")
    void specificationFilesAreServed(String relativePath) {
        HttpResponse<byte[]> response = fetchSpecFile(relativePath);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.body())
                .as("Served specification file should not be empty")
                .isNotEmpty();
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
        String description = stringAt(parseSpecFile(ROOT_SPEC_FILE), "info", "description");

        assertThat(description.lines())
                .as(
                        "The info description must contain a bullet starting with \"%s\"",
                        expectedLinePrefix)
                .anyMatch(line -> line.startsWith(expectedLinePrefix));
    }

    /**
     * Resolves the {@code bearerAuth} security scheme description by following the {@code $ref}
     * from the served root document into the served component file, exactly as a specification
     * consumer such as Swagger UI does.
     *
     * @return the description split into rendered lines
     */
    private List<String> bearerAuthDescriptionLines() {
        Map<String, Object> root = parseSpecFile(ROOT_SPEC_FILE);
        String reference =
                stringAt(root, "components", "securitySchemes", "bearerAuth", REFERENCE_KEY);

        String referencedFile =
                reference.substring(0, reference.indexOf(REFERENCE_FRAGMENT_SEPARATOR));
        assertThat(referencedFile)
                .as("bearerAuth must be defined in a referenced component file")
                .isNotBlank();

        Map<String, Object> component =
                parseSpecFile(referencedFile.replace(RELATIVE_PATH_PREFIX, ""));
        String description = stringAt(component, "securitySchemes", "bearerAuth", "description");

        return description.lines().toList();
    }

    /**
     * Fetches a specification file from the published {@code /static} tree and parses it as YAML.
     *
     * @param relativePath path of the file relative to the specification root
     * @return the parsed document
     */
    private Map<String, Object> parseSpecFile(String relativePath) {
        String body = new String(fetchSpecFile(relativePath).body(), StandardCharsets.UTF_8);
        return new Yaml().load(body);
    }

    /**
     * Reads a nested string value from a parsed YAML document, failing the test with the traversed
     * path when any segment is missing.
     *
     * @param document the parsed document
     * @param path the sequence of mapping keys to follow
     * @return the string value found at that path
     */
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

    /**
     * Fetches a specification file from the published {@code /static} tree.
     *
     * @param relativePath path of the file relative to the specification root
     * @return the raw HTTP response
     */
    private HttpResponse<byte[]> fetchSpecFile(String relativePath) {
        return httpClient
                .toBlocking()
                .exchange(
                        HttpRequest.GET(STATIC_SPEC_ROOT + relativePath).accept(MediaType.ALL),
                        byte[].class);
    }
}
