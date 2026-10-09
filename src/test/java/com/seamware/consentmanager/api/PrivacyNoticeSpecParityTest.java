package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.generated.model.PrivacyNotice;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeDataInner;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeRecipientsInner;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps the served privacy notice, the stored payload and the two generation bodies saying the same
 * thing.
 *
 * <p>A privacy notice is published as a legal document, so a heading the spec serves but the
 * payload cannot carry is a hole a caller only discovers in a rendered notice. Three drifts are
 * cheap to introduce and invisible at runtime, and each costs a build here instead: a spec property
 * with no payload component behind it, a positional shift in the payload record — whose six
 * fixtures pass nine nulls and so compile through any reordering — and an inline {@code items}
 * schema degrading to {@code List<Object>} in the generator.
 */
@DisplayName("Privacy notice schema parity")
class PrivacyNoticeSpecParityTest {

    private static final String NOTICE_RESOURCE = "static/components/schemas/PrivacyNotice.yaml";

    private static final String RESOLUTION_RESOURCE =
            "static/components/schemas/PrivacyNoticeResolution.yaml";

    private static final String GENERATE_RESOURCE =
            "static/components/schemas/GeneratePrivacyNotice.yaml";

    private static final String BY_CONTRACT_RESOURCE =
            "static/components/schemas/GenerateByContract.yaml";

    /** The one property both generation bodies carry, and the key the notice is held against. */
    private static final String CONTRACT_URI = "contractUri";

    /**
     * Prose, not validation: the two bodies explain the same constraint in the terms their own flow
     * needs.
     */
    private static final Set<String> DIVERGENT_KEYWORDS = Set.of("description", "example");

    /**
     * What the notice serves from its own row rather than from the stored payload: the identity and
     * the parties assigned at generation, and the three timestamps.
     */
    private static final Set<String> ENTITY_PROPERTIES =
            Set.of(
                    "id",
                    "contractUri",
                    "title",
                    "dataProvider",
                    "dataConsumer",
                    "createdAt",
                    "updatedAt",
                    "archivedAt");

    /** The block reporting how far a resolved retrieval got, which no payload component backs. */
    private static final String RESOLUTION_PROPERTY = "resolution";

    /**
     * Every payload component in declaration order, with the type it must keep.
     *
     * <p>Order is asserted and not only membership: the fixtures construct the record positionally,
     * so two components of the same type swapping places would compile and silently publish one
     * heading's text under the other.
     */
    private static final List<Map.Entry<String, String>> PAYLOAD_COMPONENTS =
            List.of(
                    component("purposes", List.class, String.class),
                    component("categoriesOfData", List.class, String.class),
                    component("data", List.class, DataReference.class),
                    component("recipients", List.class, Recipient.class),
                    component("controllerDetails", ControllerDetails.class),
                    component("retentionPeriod", String.class),
                    component("piiPrincipalRights", String.class),
                    component("internationalTransfers", List.class, String.class),
                    component("withdrawalOfConsent", String.class));

    @Test
    @DisplayName("the two generation bodies constrain contractUri identically")
    void theTwoGenerationBodiesConstrainContractUriIdentically() {
        assertThat(validation(BY_CONTRACT_RESOURCE, CONTRACT_URI))
                .isEqualTo(validation(GENERATE_RESOURCE, CONTRACT_URI));
    }

    @ParameterizedTest(name = "{0} requires contractUri")
    @MethodSource("generationBodies")
    @DisplayName("both generation bodies require contractUri")
    void bothGenerationBodiesRequireContractUri(String resource) {
        assertThat(required(resource)).contains(CONTRACT_URI);
    }

    @Test
    @DisplayName("the notice serves exactly the payload, the row and the resolution block")
    void theNoticeServesExactlyThePayloadTheRowAndTheResolutionBlock() {
        List<String> expected = new ArrayList<>(ENTITY_PROPERTIES);
        PAYLOAD_COMPONENTS.forEach(payloadComponent -> expected.add(payloadComponent.getKey()));
        expected.add(RESOLUTION_PROPERTY);

        assertThat(properties(NOTICE_RESOURCE).keySet())
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("the payload keeps its component names, types and order")
    void thePayloadKeepsItsComponentNamesTypesAndOrder() {
        assertThat(
                        Stream.of(PrivacyNoticePayload.class.getRecordComponents())
                                .map(PrivacyNoticeSpecParityTest::declaration)
                                .toList())
                .containsExactlyElementsOf(PAYLOAD_COMPONENTS);
    }

    @ParameterizedTest(name = "nothing under {0} is nullable")
    @MethodSource("servedSchemas")
    @DisplayName("no served property is nullable")
    void noServedPropertyIsNullable(String resource) {
        assertThat(nullableProperties(load(resource), "")).isEmpty();
    }

    @ParameterizedTest(name = "{0} is served as a list of its own element type")
    @MethodSource("inlineItemProperties")
    @DisplayName("the inline item schemas keep their element type")
    void theInlineItemSchemasKeepTheirElementType(String accessor, Class<?> element)
            throws NoSuchMethodException {
        Type returned = PrivacyNotice.class.getMethod(accessor).getGenericReturnType();

        assertThat(returned).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) returned).getActualTypeArguments())
                .as("a cross-file or degraded `items` schema yields List<Object>")
                .containsExactly(element);
    }

    static Stream<String> generationBodies() {
        return Stream.of(GENERATE_RESOURCE, BY_CONTRACT_RESOURCE);
    }

    static Stream<String> servedSchemas() {
        return Stream.of(NOTICE_RESOURCE, RESOLUTION_RESOURCE);
    }

    static Stream<Arguments> inlineItemProperties() {
        return Stream.of(
                Arguments.of("getData", PrivacyNoticeDataInner.class),
                Arguments.of("getRecipients", PrivacyNoticeRecipientsInner.class));
    }

    /** Every path under {@code schema} whose subschema declares {@code nullable: true}. */
    private static List<String> nullableProperties(Map<String, Object> schema, String path) {
        List<String> nullable = new ArrayList<>();
        if (Boolean.TRUE.equals(schema.get("nullable"))) {
            nullable.add(path);
        }
        Stream.of("properties", "items")
                .map(schema::get)
                .filter(Map.class::isInstance)
                .map(PrivacyNoticeSpecParityTest::asMap)
                .forEach(
                        nested ->
                                nested.forEach(
                                        (name, value) -> {
                                            if (value instanceof Map) {
                                                nullable.addAll(
                                                        nullableProperties(
                                                                asMap(value), path + "/" + name));
                                            }
                                        }));
        return nullable;
    }

    /** A record component as {@code name -> fully qualified generic type}. */
    private static Map.Entry<String, String> declaration(RecordComponent recordComponent) {
        return Map.entry(
                recordComponent.getName(), recordComponent.getGenericType().getTypeName());
    }

    /** The same encoding as {@link #declaration}, spelled out from the types it must match. */
    private static Map.Entry<String, String> component(
            String name, Class<?> raw, Class<?>... arguments) {
        String type =
                arguments.length == 0
                        ? raw.getTypeName()
                        : Stream.of(arguments)
                                .map(Class::getTypeName)
                                .collect(Collectors.joining(", ", raw.getTypeName() + "<", ">"));
        return Map.entry(name, type);
    }

    /** A property's schema with the prose stripped out, leaving only what constrains a value. */
    private static Map<String, Object> validation(String resource, String property) {
        Map<String, Object> schema = new LinkedHashMap<>(asMap(properties(resource).get(property)));
        schema.keySet().removeAll(DIVERGENT_KEYWORDS);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(String resource) {
        Object declared = load(resource).get("required");
        assertThat(declared).isInstanceOf(List.class);
        return (List<String>) declared;
    }

    private static Map<String, Object> properties(String resource) {
        return asMap(load(resource).get("properties"));
    }

    private static Map<String, Object> load(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            assertThat(stream).as("`%s` is on the classpath", resource).isNotNull();
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
