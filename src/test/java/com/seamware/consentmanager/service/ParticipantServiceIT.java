package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises {@link ParticipantService#update} against a real PostgreSQL.
 *
 * <p>The point of the real database here is that the two things the update contract turns on - that
 * the identifier survives a replacement and that what is returned is the stored row rather than the
 * object that was written - are both invisible against a mock.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant update")
class ParticipantServiceIT extends PostgresTestResource {

    private static final String IDENTIFIER = "urn:test:participant:service-update";

    private static final String ORIGINAL_LEGAL_NAME = "Original Clinic Ltd";

    private static final String ORIGINAL_SELF_DESCRIPTION_URI =
            "https://clinic.example.org/sd.json";

    private static final String ORIGINAL_EMAIL = "original@clinic.example.org";

    private static final Map<String, Object> ORIGINAL_ENDPOINTS =
            Map.of("consentNotification", "https://clinic.example.org/notify");

    private static final Map<String, Object> ORIGINAL_LEGAL_PERSON =
            Map.of("registrationNumber", "DE123456789");

    private static final String REPLACED_LEGAL_NAME = "Replaced Clinic Ltd";

    private static final String REPLACED_SELF_DESCRIPTION_URI =
            "https://clinic.example.org/sd-v2.json";

    private static final String REPLACED_EMAIL = "replaced@clinic.example.org";

    private static final Map<String, Object> REPLACED_ENDPOINTS =
            Map.of("consentNotification", "https://clinic.example.org/notify/v2");

    private static final Map<String, Object> REPLACED_LEGAL_PERSON =
            Map.of("registrationNumber", "DE987654321");

    /** Every mutable field at once, so a replacement that drops one shows up. */
    private static final ParticipantUpdate EVERY_FIELD =
            new ParticipantUpdate(
                    REPLACED_LEGAL_NAME,
                    REPLACED_SELF_DESCRIPTION_URI,
                    REPLACED_EMAIL,
                    REPLACED_ENDPOINTS,
                    REPLACED_LEGAL_PERSON);

    @Inject ParticipantService participants;

    @Inject ParticipantRepository repository;

    @AfterEach
    void removeCommittedRows() {
        repository.findByIdentifier(IDENTIFIER).ifPresent(repository::delete);
    }

    @Test
    @DisplayName("writes every mutable field and leaves the identifier alone")
    void writesEveryMutableField() {
        Participant registered = register();

        Participant updated = participants.update(registered, EVERY_FIELD);

        assertThat(updated.getIdentifier()).isEqualTo(IDENTIFIER);
        assertThat(updated.getLegalName()).isEqualTo(REPLACED_LEGAL_NAME);
        assertThat(updated.getSelfDescriptionUri()).isEqualTo(REPLACED_SELF_DESCRIPTION_URI);
        assertThat(updated.getEmail()).isEqualTo(REPLACED_EMAIL);
        assertThat(updated.getEndpoints()).isEqualTo(REPLACED_ENDPOINTS);
        assertThat(updated.getLegalPerson()).isEqualTo(REPLACED_LEGAL_PERSON);
    }

    @Test
    @DisplayName("returns the stored row, timestamp included, rather than the object written")
    void returnsTheStoredRow() {
        Participant registered = register();

        Participant updated = participants.update(registered, EVERY_FIELD);
        Participant reread = repository.findById(updated.getId()).orElseThrow();

        assertThat(updated.getUpdatedAt())
                .as("a stale updatedAt is the symptom of returning the written object")
                .isEqualTo(reread.getUpdatedAt())
                .isAfterOrEqualTo(registered.getCreatedAt());
        assertThat(updated.getLegalName()).isEqualTo(reread.getLegalName());
        assertThat(updated.getSelfDescriptionUri()).isEqualTo(reread.getSelfDescriptionUri());
        assertThat(updated.getEmail()).isEqualTo(reread.getEmail());
        assertThat(updated.getEndpoints()).isEqualTo(reread.getEndpoints());
        assertThat(updated.getLegalPerson()).isEqualTo(reread.getLegalPerson());
        assertThat(updated.getCreatedAt()).isEqualTo(reread.getCreatedAt());
    }

    /** A {@code PUT} replaces: an optional field the body omits is cleared, not kept. */
    @ParameterizedTest(name = "omitting {0} clears it")
    @MethodSource("clearableFields")
    void omittingAnOptionalFieldClearsIt(
            String field, ParticipantUpdate update, Consumer<Participant> assertion) {
        Participant registered = register();

        participants.update(registered, update);

        assertion.accept(repository.findByIdentifier(IDENTIFIER).orElseThrow());
    }

    static Stream<Arguments> clearableFields() {
        return Stream.of(
                Arguments.of(
                        "selfDescriptionUri",
                        new ParticipantUpdate(
                                REPLACED_LEGAL_NAME,
                                null,
                                REPLACED_EMAIL,
                                REPLACED_ENDPOINTS,
                                REPLACED_LEGAL_PERSON),
                        (Consumer<Participant>)
                                stored -> assertThat(stored.getSelfDescriptionUri()).isNull()),
                Arguments.of(
                        "email",
                        new ParticipantUpdate(
                                REPLACED_LEGAL_NAME,
                                REPLACED_SELF_DESCRIPTION_URI,
                                null,
                                REPLACED_ENDPOINTS,
                                REPLACED_LEGAL_PERSON),
                        (Consumer<Participant>) stored -> assertThat(stored.getEmail()).isNull()),
                Arguments.of(
                        "legalPerson",
                        new ParticipantUpdate(
                                REPLACED_LEGAL_NAME,
                                REPLACED_SELF_DESCRIPTION_URI,
                                REPLACED_EMAIL,
                                REPLACED_ENDPOINTS,
                                null),
                        (Consumer<Participant>)
                                stored -> assertThat(stored.getLegalPerson()).isNull()),
                Arguments.of(
                        "endpoints",
                        new ParticipantUpdate(
                                REPLACED_LEGAL_NAME,
                                REPLACED_SELF_DESCRIPTION_URI,
                                REPLACED_EMAIL,
                                Map.of(),
                                REPLACED_LEGAL_PERSON),
                        (Consumer<Participant>)
                                stored -> assertThat(stored.getEndpoints()).isEmpty()));
    }

    @Test
    @DisplayName("leaves no second row behind")
    void leavesNoSecondRowBehind() {
        participants.update(register(), EVERY_FIELD);

        assertThat(repository.findAll())
                .filteredOn(stored -> IDENTIFIER.equals(stored.getIdentifier()))
                .hasSize(1);
    }

    private Participant register() {
        return participants.register(
                IDENTIFIER,
                new ParticipantRegistration(
                        ORIGINAL_LEGAL_NAME,
                        ORIGINAL_SELF_DESCRIPTION_URI,
                        ORIGINAL_EMAIL,
                        ORIGINAL_ENDPOINTS,
                        ORIGINAL_LEGAL_PERSON));
    }
}
