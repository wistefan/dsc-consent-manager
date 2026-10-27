package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * No generated participant API model, reached by walking controller signatures, carries a
 * credential-shaped property; guards the resource-server rule of never storing or echoing one.
 */
@DisplayName("Participant API models")
class ParticipantCredentialAbsenceTest {

    /** Controllers serving a participant path; every model they publish is covered. */
    private static final List<Class<?>> PARTICIPANT_CONTROLLERS =
            List.of(ParticipantController.class, ParticipantUserController.class);

    /** Package the generator writes its models into; the walk stops at its boundary. */
    private static final String MODEL_PACKAGE = "com.seamware.consentmanager.api.generated.model";

    /** Prefix of the generated constant that holds a property's name as it appears on the wire. */
    private static final String WIRE_NAME_PREFIX = "JSON_PROPERTY_";

    /** Forbidden name fragments, matched case-insensitively anywhere, so compounds are caught. */
    private static final List<String> CREDENTIAL_VOCABULARY =
            List.of("clientid", "secret", "password", "token", "key", "credential");

    /** Models the walk must reach, so a broken walk fails rather than asserting nothing. */
    private static final Set<String> REACHED_MODELS =
            Set.of(
                    "Participant",
                    "ParticipantRegistration",
                    "ParticipantUpdate",
                    "ParticipantPage",
                    "ParticipantEndpoints",
                    "ParticipantLegalPerson",
                    "DeregistrationSummary",
                    "ParticipantUserLink",
                    "ParticipantUserLinkUpdate",
                    "User",
                    "UserRegistration",
                    "UserRegistrationResult",
                    "BulkUserRegistration",
                    "BulkUserRegistrationEntry",
                    "BulkRegistrationResult",
                    "BulkRegistrationEntryResult",
                    "BulkRegistrationSummary");

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
    @DisplayName("are all reached from the controllers' signatures")
    void areAllReachedFromTheControllersSignatures() {
        assertThat(participantModels().map(model -> model.get()[0])).containsAll(REACHED_MODELS);
    }

    /** Every generated model the participant API accepts, returns or nests; enums are skipped. */
    static Stream<Arguments> participantModels() {
        Deque<Type> pending =
                PARTICIPANT_CONTROLLERS.stream()
                        .flatMap(controller -> Stream.of(controller.getDeclaredMethods()))
                        .flatMap(
                                method ->
                                        Stream.concat(
                                                Stream.of(method.getGenericReturnType()),
                                                Stream.of(method.getGenericParameterTypes())))
                        .collect(Collectors.toCollection(ArrayDeque::new));
        Set<Class<?>> models = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            Type type = pending.poll();
            if (type instanceof ParameterizedType parameterized) {
                pending.add(parameterized.getRawType());
                pending.addAll(List.of(parameterized.getActualTypeArguments()));
            } else if (type instanceof Class<?> candidate
                    && MODEL_PACKAGE.equals(candidate.getPackageName())
                    && !candidate.isEnum()
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
