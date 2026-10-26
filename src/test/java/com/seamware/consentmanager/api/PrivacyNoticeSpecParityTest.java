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
import java.nio.file.Files;
import java.nio.file.Path;
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
 *
 * <p>Each comparison runs one level down as well, because every one of those drifts has a nested
 * twin that fails the same way and is quieter: the three records served inside the notice are
 * matched against their subschemas by name, and the nullable scan descends {@code items} and
 * follows the relative {@code $ref}s the resolution block is written as.
 */
@DisplayName("Privacy notice schema parity")
class PrivacyNoticeSpecParityTest {

    /** Root of the authored specification, relative to the module directory. */
    private static final Path SPEC_ROOT = Path.of("api");

    /**
     * Directory of the authored schemas under {@link #SPEC_ROOT}, and the one every {@code $ref} in
     * them is relative to.
     */
    private static final String SCHEMA_DIRECTORY = "components/schemas/";

    private static final String NOTICE_RESOURCE = SCHEMA_DIRECTORY + "PrivacyNotice.yaml";

    private static final String RESOLUTION_RESOURCE =
            SCHEMA_DIRECTORY + "PrivacyNoticeResolution.yaml";

    private static final String GENERATE_RESOURCE = SCHEMA_DIRECTORY + "GeneratePrivacyNotice.yaml";

    private static final String BY_CONTRACT_RESOURCE = SCHEMA_DIRECTORY + "GenerateByContract.yaml";

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

    private static final String PROPERTIES_KEY = "properties";

    private static final String ITEMS_KEY = "items";

    private static final String REFERENCE_KEY = "$ref";

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

    @ParameterizedTest(name = "{0} serves exactly what {1} carries")
    @MethodSource("nestedRecords")
    @DisplayName("each nested schema serves exactly its record's components")
    void eachNestedSchemaServesExactlyItsRecordsComponents(
            String property, Class<?> nested, List<Map.Entry<String, String>> components) {
        assertThat(nestedProperties(property).keySet())
                .as("`%s` is served from %s", property, nested.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(
                        components.stream().map(Map.Entry::getKey).toList());
    }

    @ParameterizedTest(name = "{1} keeps its component names, types and order")
    @MethodSource("nestedRecords")
    @DisplayName("each nested record keeps its component names, types and order")
    void eachNestedRecordKeepsItsComponentNamesTypesAndOrder(
            String property, Class<?> nested, List<Map.Entry<String, String>> components) {
        assertThat(
                        Stream.of(nested.getRecordComponents())
                                .map(PrivacyNoticeSpecParityTest::declaration)
                                .toList())
                .as("%s backs `%s`", nested.getSimpleName(), property)
                .containsExactlyElementsOf(components);
    }

    @ParameterizedTest(name = "nothing under {0} is nullable")
    @MethodSource("servedSchemas")
    @DisplayName("no served property is nullable")
    void noServedPropertyIsNullable(String resource) {
        assertThat(nullableProperties(resource, "", List.of())).isEmpty();
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

    /**
     * The three records served one level inside the notice, with the property each is served as.
     */
    static Stream<Arguments> nestedRecords() {
        return Stream.of(
                Arguments.of(
                        "data",
                        DataReference.class,
                        List.of(
                                component("uri", String.class),
                                component("title", String.class),
                                component("description", String.class),
                                component("partOf", String.class))),
                Arguments.of(
                        "recipients",
                        Recipient.class,
                        List.of(
                                component("identifier", String.class),
                                component("name", String.class),
                                component("contact", String.class),
                                component("selfDescriptionUri", String.class))),
                Arguments.of(
                        "controllerDetails",
                        ControllerDetails.class,
                        List.of(
                                component("name", String.class),
                                component("contact", String.class),
                                component("identifier", String.class),
                                component("selfDescriptionUri", String.class))));
    }

    static Stream<String> servedSchemas() {
        return Stream.of(NOTICE_RESOURCE, RESOLUTION_RESOURCE);
    }

    static Stream<Arguments> inlineItemProperties() {
        return Stream.of(
                Arguments.of("getData", PrivacyNoticeDataInner.class),
                Arguments.of("getRecipients", PrivacyNoticeRecipientsInner.class));
    }

    /**
     * Every path under {@code resource} whose subschema declares {@code nullable: true}.
     *
     * <p>{@code chain} is the resources already open on this path, so a {@code $ref} cycle
     * terminates instead of recursing.
     */
    private static List<String> nullableProperties(
            String resource, String path, List<String> chain) {
        if (chain.contains(resource)) {
            return List.of();
        }
        List<String> reached = new ArrayList<>(chain);
        reached.add(resource);
        return nullableIn(load(resource), path, reached);
    }

    /**
     * The same walk within one document, descending the three ways a notice nests.
     *
     * <p>Each is shaped differently and conflating them hides whole subtrees: under {@code
     * properties} the map is name to subschema, under {@code items} the map <em>is</em> the
     * subschema, and a {@code $ref} continues in another document altogether — which is the only
     * way the resolution block says anything at all.
     */
    private static List<String> nullableIn(
            Map<String, Object> schema, String path, List<String> chain) {
        List<String> nullable = new ArrayList<>();
        if (Boolean.TRUE.equals(schema.get("nullable"))) {
            nullable.add(path);
        }
        if (schema.get(REFERENCE_KEY) instanceof String reference) {
            nullable.addAll(nullableProperties(referenced(reference), path, chain));
        }
        if (schema.get(PROPERTIES_KEY) instanceof Map<?, ?> properties) {
            properties.forEach(
                    (name, subschema) ->
                            nullable.addAll(
                                    nullableIn(asMap(subschema), path + "/" + name, chain)));
        }
        if (schema.get(ITEMS_KEY) instanceof Map<?, ?> items) {
            nullable.addAll(nullableIn(asMap(items), path + "[]", chain));
        }
        return nullable;
    }

    /**
     * A relative {@code $ref} as the authored document it names; all of them are same-directory.
     */
    private static String referenced(String reference) {
        return SCHEMA_DIRECTORY + reference.substring(reference.lastIndexOf('/') + 1);
    }

    /** A record component as {@code name -> fully qualified generic type}. */
    private static Map.Entry<String, String> declaration(RecordComponent recordComponent) {
        return Map.entry(recordComponent.getName(), recordComponent.getGenericType().getTypeName());
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

    /**
     * The properties the notice serves under {@code property}, stepping through {@code items} for
     * an array so an array of objects and a plain object are read the same way.
     */
    private static Map<String, Object> nestedProperties(String property) {
        Map<String, Object> schema = asMap(properties(NOTICE_RESOURCE).get(property));
        Object items = schema.get(ITEMS_KEY);
        return asMap((items instanceof Map ? asMap(items) : schema).get(PROPERTIES_KEY));
    }

    private static Map<String, Object> properties(String resource) {
        return asMap(load(resource).get(PROPERTIES_KEY));
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
