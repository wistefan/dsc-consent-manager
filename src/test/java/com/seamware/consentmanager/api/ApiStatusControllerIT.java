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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration test for {@link ApiStatusController} and for the split OpenAPI specification it is
 * generated from.
 *
 * <p>Two things are verified here:
 *
 * <ul>
 *   <li>{@code GET /api-status} is actually <em>routed</em>. The OpenAPI generator emits only an
 *       abstract controller, which is not a bean, so without the hand-written concrete subclass the
 *       endpoint would return 404 and any test written against it would pass vacuously.
 *   <li>Every file of the referenced specification layout is published under {@code /static}, which
 *       is what makes the relative {@code $ref}s in {@code openapi.yaml} resolve when Swagger UI
 *       loads the spec from the running service.
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
                .isEqualTo(ApiStatusController.STATUS_OK);
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

    @ParameterizedTest(name = "{0} documents \"{1}\"")
    @CsvSource({
        "components/security.yaml, bearerAuth",
        "components/security.yaml, User Access Token",
        "components/security.yaml, Participant Access Token",
        "components/security.yaml, Catalog Token",
        "components/security.yaml, CATALOG",
        "openapi.yaml, bearerAuth"
    })
    @DisplayName("The published token contract is visible in the served specification")
    void tokenContractIsDocumented(String relativePath, String expectedText) {
        String body = new String(fetchSpecFile(relativePath).body(), StandardCharsets.UTF_8);

        assertThat(body).contains(expectedText);
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
