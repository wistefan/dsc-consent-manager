package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.generated.model.ParticipantUserLinkUpdate;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The tri-state the PATCH body rests on: omitted, explicitly null, and set are three distinct
 * decodings of the same property.
 */
@DisplayName("JsonNullable binding on a PATCH body")
class JsonNullableSerdeTest {

    private static ApplicationContext context;

    private static ObjectMapper json;

    @BeforeAll
    static void startContext() {
        // No datasource and no Flyway: this suite exercises serialisation only, and a
        // database would make a unit test wait on Testcontainers.
        context =
                ApplicationContext.builder(Environment.TEST)
                        .properties(
                                Map.of(
                                        "datasources.default.enabled", false,
                                        "flyway.enabled", false))
                        .start();
        json = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stopContext() {
        context.close();
    }

    static Stream<Arguments> bodies() {
        return Stream.of(
                Arguments.of("{}", false, null),
                Arguments.of("{\"localIdentifier\":null}", true, null),
                Arguments.of("{\"localIdentifier\":\"patient-10427\"}", true, "patient-10427"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodies")
    void decodesTheThreeStatesApart(String body, boolean defined, String value) throws IOException {
        var decoded = json.readValue(body, ParticipantUserLinkUpdate.class);

        assertThat(decoded.getLocalIdentifier_JsonNullable().isPresent()).isEqualTo(defined);
        assertThat(decoded.getLocalIdentifier()).isEqualTo(value);
    }

    @Test
    void aSetValueSerialisesWithoutTheWrapper() throws IOException {
        var body = new ParticipantUserLinkUpdate().localIdentifier("patient-10427");

        assertThat(json.writeValueAsString(body))
                .isEqualTo("{\"localIdentifier\":\"patient-10427\"}");
    }
}
