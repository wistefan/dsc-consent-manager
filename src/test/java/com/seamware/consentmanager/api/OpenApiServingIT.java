package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@code GET /openapi.yaml} serves the build's bundle unchanged, anonymously, as YAML.
 */
@MicronautTest(transactional = false)
@DisplayName("Serving the bundled OpenAPI specification")
class OpenApiServingIT extends KeycloakAndPostgresTestResource {

    /** Where the bundle is served, at the origin root outside the API prefix. */
    private static final String SPEC_PATH = "/openapi.yaml";

    /**
     * System property Failsafe sets from the pom's {@code openapi.bundle.file}, the bundle's path.
     */
    private static final String BUNDLE_FILE_PROPERTY = "openapi.bundle.file";

    /** OpenAPI version the authored specification declares. */
    private static final String OPENAPI_VERSION = "3.0.3";

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Test
    @DisplayName("the served bytes are the build's bundle, byte for byte")
    void servesTheBundleUnchanged() throws IOException {
        Path bundleFile =
                Path.of(
                        Objects.requireNonNull(
                                System.getProperty(BUNDLE_FILE_PROPERTY),
                                BUNDLE_FILE_PROPERTY + " is unset; run under Failsafe"));

        assertThat(fetch().body()).isEqualTo(Files.readAllBytes(bundleFile));
    }

    @Test
    @DisplayName("the specification is served to a request carrying no token, as YAML")
    void servesAnonymouslyAsYaml() {
        HttpResponse<byte[]> response = fetch();

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getHeaders().get(HttpHeaders.CONTENT_TYPE))
                .startsWith(MediaType.APPLICATION_YAML);
    }

    @Test
    @DisplayName("the served bundle parses as OpenAPI without resolving anything outside it")
    void servedBundleParses() {
        SwaggerParseResult result =
                new OpenAPIV3Parser()
                        .readContents(new String(fetch().body(), StandardCharsets.UTF_8));

        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI().getOpenapi()).isEqualTo(OPENAPI_VERSION);
        assertThat(result.getOpenAPI().getComponents().getSecuritySchemes())
                .containsKey("bearerAuth");
    }

    private HttpResponse<byte[]> fetch() {
        return httpClient
                .toBlocking()
                .exchange(HttpRequest.GET(SPEC_PATH).accept(MediaType.ALL), byte[].class);
    }
}
