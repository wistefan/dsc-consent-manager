package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds the participant API to storing and returning no credential of any kind.
 *
 * <p>The service is a resource server: it accepts tokens an identity provider issued and never
 * issues, stores or echoes one. That is a property of the published models rather than of any
 * handler, so it is asserted against the models the generator actually emitted - a property added
 * to a schema reaches a wire format here whether or not a handler ever reads it.
 *
 * <p>The models are reached by walking {@link ParticipantController}'s signatures rather than by
 * matching class names, so a model introduced later is covered by having been wired into the API at
 * all, and a nested one is covered through the property that holds it.
 */
@DisplayName("Participant API models")
class ParticipantCredentialAbsenceTest {

    /** Package the generator writes its models into; the walk stops at its boundary. */
    private static final String MODEL_PACKAGE = "com.seamware.consentmanager.api.generated.model";

    /** Prefix of the generated constant that holds a property's name as it appears on the wire. */
    private static final String WIRE_NAME_PREFIX = "JSON_PROPERTY_";

    /**
     * Fragments a property name may not contain, matched case-insensitively and without a word
     * boundary so that one entry catches every compound built on it - {@code secret} catches {@code
     * clientSecret}, {@code key} catches {@code apiKey}, {@code token} catches {@code
     * refreshToken}.
     */
    private static final List<String> CREDENTIAL_VOCABULARY =
            List.of("clientid", "secret", "password", "token", "key", "credential");

    /**
     * Models the walk has to reach, so that a walk broken by a refactoring fails here rather than
     * passing with nothing to assert.
     */
    private static final Set<String> REACHED_MODELS =
            Set.of(
                    "Participant",
                    "ParticipantRegistration",
                    "ParticipantUpdate",
                    "ParticipantPage",
                    "ParticipantEndpoints",
                    "ParticipantLegalPerson",
                    "DeregistrationSummary");

    @ParameterizedTest(name = "{0} publishes no credential")
    @MethodSource("participantModels")
    @DisplayName("name no property that reads as a credential")
    void nameNoPropertyThatReadsAsACredential(String name, Class<?> model) {
        List<String> properties = propertyNames(model);

        assertThat(properties).as("%s publishes no property at all", name).isNotEmpty();
        properties.forEach(
                property ->
                        assertThat(CREDENTIAL_VOCABULARY)
                                .as(
                                        "%s.%s reads as a credential, and this service holds none:"
                                                + " it validates tokens another issuer minted",
                                        name, property)
                                .noneMatch(
                                        fragment ->
                                                property.toLowerCase(Locale.ROOT)
                                                        .contains(fragment)));
    }

    @Test
    @DisplayName("are all reached from the controller's signatures")
    void areAllReachedFromTheControllersSignatures() {
        assertThat(participantModels().map(model -> model.get()[0])).containsAll(REACHED_MODELS);
    }

    /** Every generated model the participant API accepts, returns, or nests inside one of those. */
    static Stream<Arguments> participantModels() {
        Deque<Type> pending = new ArrayDeque<>();
        for (Method method : ParticipantController.class.getDeclaredMethods()) {
            pending.add(method.getGenericReturnType());
            pending.addAll(List.of(method.getGenericParameterTypes()));
        }
        Set<Class<?>> models = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            Type type = pending.poll();
            if (type instanceof ParameterizedType parameterized) {
                pending.add(parameterized.getRawType());
                pending.addAll(List.of(parameterized.getActualTypeArguments()));
            } else if (type instanceof Class<?> candidate
                    && MODEL_PACKAGE.equals(candidate.getPackageName())
                    && models.add(candidate)) {
                Stream.of(candidate.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .forEach(field -> pending.add(field.getGenericType()));
            }
        }
        return models.stream().map(model -> Arguments.of(model.getSimpleName(), model));
    }

    /** A model's property names as they appear on the wire, read off the generated constants. */
    private static List<String> propertyNames(Class<?> model) {
        return Stream.of(model.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getName().startsWith(WIRE_NAME_PREFIX))
                .map(ParticipantCredentialAbsenceTest::wireName)
                .toList();
    }

    private static String wireName(Field constant) {
        try {
            return String.valueOf(constant.get(null));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to read " + constant, e);
        }
    }
}
