package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps {@code ParticipantRegistration} and {@code ParticipantUpdate} accepting the same values; a
 * deliberate divergence goes in {@link #REGISTRATION_ONLY_PROPERTIES} or {@link
 * #DIVERGENT_KEYWORDS}.
 */
@DisplayName("Participant registration and update schemas")
class ParticipantSchemaParityTest {

    /** Root of the authored specification, relative to the module directory. */
    private static final Path SPEC_ROOT = Path.of("api");

    private static final String REGISTRATION_RESOURCE =
            "components/schemas/ParticipantRegistration.yaml";

    private static final String UPDATE_RESOURCE = "components/schemas/ParticipantUpdate.yaml";

    /** Properties a participant may set at registration but never change afterwards. */
    private static final Set<String> REGISTRATION_ONLY_PROPERTIES = Set.of();

    /** Prose, not validation, so the two schemas may word them differently. */
    private static final Set<String> DIVERGENT_KEYWORDS = Set.of("description", "example");

    /** Absent from both, so the identifier can only come from the token. */
    private static final String IDENTIFIER_PROPERTY = "identifier";

    @Test
    @DisplayName("admit the same properties")
    void admitTheSameProperties() {
        assertThat(properties(UPDATE_RESOURCE).keySet())
                .containsExactlyInAnyOrderElementsOf(
                        properties(REGISTRATION_RESOURCE).keySet().stream()
                                .filter(name -> !REGISTRATION_ONLY_PROPERTIES.contains(name))
                                .toList());
    }

    @Test
    @DisplayName("neither accepts an identifier")
    void neitherAcceptsAnIdentifier() {
        assertThat(properties(REGISTRATION_RESOURCE)).doesNotContainKey(IDENTIFIER_PROPERTY);
        assertThat(properties(UPDATE_RESOURCE)).doesNotContainKey(IDENTIFIER_PROPERTY);
    }

    @Test
    @DisplayName("require the same properties")
    void requireTheSameProperties() {
        assertThat(load(UPDATE_RESOURCE).get("required"))
                .isEqualTo(load(REGISTRATION_RESOURCE).get("required"));
    }

    @ParameterizedTest(name = "{0} validates identically on both")
    @MethodSource("sharedProperties")
    @DisplayName("validate every shared property identically")
    void validateEverySharedPropertyIdentically(String property) {
        assertThat(validation(UPDATE_RESOURCE, property))
                .isEqualTo(validation(REGISTRATION_RESOURCE, property));
    }

    static Stream<String> sharedProperties() {
        return properties(REGISTRATION_RESOURCE).keySet().stream()
                .filter(name -> !REGISTRATION_ONLY_PROPERTIES.contains(name));
    }

    /** A property's schema with the prose stripped out, leaving only what constrains a value. */
    private static Map<String, Object> validation(String resource, String property) {
        Map<String, Object> schema = new LinkedHashMap<>(asMap(properties(resource).get(property)));
        schema.keySet().removeAll(DIVERGENT_KEYWORDS);
        return schema;
    }

    private static Map<String, Object> properties(String resource) {
        return asMap(load(resource).get("properties"));
    }

    private static Map<String, Object> load(String resource) {
        try (InputStream stream = Files.newInputStream(SPEC_ROOT.resolve(resource))) {
            return new Yaml().load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + resource, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
