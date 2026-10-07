package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.generated.model.ParticipantEndpoints;
import com.seamware.consentmanager.api.generated.model.ParticipantLegalPerson;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers the column and timestamp cases the endpoint tests cannot produce through a real request —
 * a column key this revision does not name, a value that is not a string, and a deregistered row.
 */
@DisplayName("Mapping a stored participant to its representation")
class ParticipantMapperTest {

    private static final String IDENTIFIER = "urn:test:participant:mapper";

    private static final String LEGAL_NAME = "Mapper Clinic Ltd";

    private static final String SELF_DESCRIPTION_URI = "https://clinic.example.org/sd.json";

    private static final String EMAIL = "data-protection@clinic.example.org";

    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private static final Instant DEREGISTERED_AT = Instant.parse("2026-03-04T05:06:07Z");

    /** Every nested property, each with a value no other property could be mistaken for. */
    private static final ParticipantLegalPerson EVERY_LEGAL_PERSON_PROPERTY =
            new ParticipantLegalPerson()
                    .registrationNumber("DE123456789")
                    .headquartersAddress("Hauptstrasse 1, 10115 Berlin, DE")
                    .legalAddress("Nebenstrasse 2, 10117 Berlin, DE")
                    .parentOrganization("urn:test:participant:holding")
                    .subOrganization("urn:test:participant:clinic-north");

    private static final ParticipantEndpoints EVERY_ENDPOINT =
            new ParticipantEndpoints().consentNotification("https://clinic.example.org/notify");

    /** A key no revision of the representation names, which reading must carry past. */
    private static final String UNPUBLISHED_KEY = "futureEndpoint";

    /** The one endpoint key the representation does name, used to plant a non-string value. */
    private static final String CONSENT_NOTIFICATION_KEY = "consentNotification";

    /** A legal-person key the representation does name, used to plant a non-string value. */
    private static final String REGISTRATION_NUMBER_KEY = "registrationNumber";

    private final ParticipantMapper mapper = new ParticipantMapper();

    private static com.seamware.consentmanager.domain.Participant stored(
            Map<String, Object> endpoints, Map<String, Object> legalPerson) {
        var participant =
                new com.seamware.consentmanager.domain.Participant(
                        IDENTIFIER,
                        LEGAL_NAME,
                        SELF_DESCRIPTION_URI,
                        EMAIL,
                        endpoints,
                        legalPerson);
        participant.setCreatedAt(CREATED_AT);
        participant.setUpdatedAt(CREATED_AT);
        return participant;
    }

    @Test
    @DisplayName("carries the stored attributes through verbatim")
    void carriesTheStoredAttributes() {
        var representation = mapper.toRepresentation(stored(Map.of(), null));

        assertThat(representation.getIdentifier()).isEqualTo(IDENTIFIER);
        assertThat(representation.getLegalName()).isEqualTo(LEGAL_NAME);
        assertThat(representation.getSelfDescriptionUri()).isEqualTo(SELF_DESCRIPTION_URI);
        assertThat(representation.getEmail()).isEqualTo(EMAIL);
    }

    static Stream<Arguments> legalPersons() {
        return Stream.of(
                Arguments.of("every property supplied", EVERY_LEGAL_PERSON_PROPERTY),
                Arguments.of(
                        "one property supplied",
                        new ParticipantLegalPerson().registrationNumber("DE123456789")),
                Arguments.of("no object supplied", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legalPersons")
    @DisplayName("a legal person survives the round trip through its JSONB column")
    void legalPersonRoundTrips(String name, ParticipantLegalPerson legalPerson) {
        var representation =
                mapper.toRepresentation(stored(Map.of(), mapper.toLegalPersonColumn(legalPerson)));

        assertThat(representation.getLegalPerson()).isEqualTo(legalPerson);
    }

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("every endpoint supplied", EVERY_ENDPOINT),
                Arguments.of("no object supplied", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("endpoints survive the round trip through their JSONB column")
    void endpointsRoundTrip(String name, ParticipantEndpoints submitted) {
        var representation =
                mapper.toRepresentation(stored(mapper.toEndpointsColumn(submitted), null));

        assertThat(representation.getEndpoints()).isEqualTo(submitted);
    }

    /** The column is {@code NOT NULL}, so nothing submitted still has to become a value. */
    @Test
    @DisplayName("an absent endpoints object becomes an empty column rather than null")
    void absentEndpointsBecomeAnEmptyColumn() {
        assertThat(mapper.toEndpointsColumn(null)).isEmpty();
    }

    /** A legal person whose every property is absent is nothing to store, not an empty object. */
    @Test
    @DisplayName("a legal person with no property set becomes a null column")
    void anEmptyLegalPersonBecomesNull() {
        assertThat(mapper.toLegalPersonColumn(new ParticipantLegalPerson())).isNull();
    }

    static Stream<Arguments> unpublishableEndpointColumns() {
        return Stream.of(
                Arguments.of("a key this revision does not name", Map.of(UNPUBLISHED_KEY, "x")),
                Arguments.of("a value that is not a string", Map.of(CONSENT_NOTIFICATION_KEY, 42)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unpublishableEndpointColumns")
    @DisplayName("an endpoint this revision cannot publish reads back as absent")
    void unpublishableEndpointsReadAsAbsent(String name, Map<String, Object> column) {
        assertThat(mapper.toRepresentation(stored(column, null)).getEndpoints()).isNull();
    }

    /** The non-string case names a legal-person key, so this path's own guard is exercised. */
    static Stream<Arguments> unpublishableLegalPersonColumns() {
        return Stream.of(
                Arguments.of("a key this revision does not name", Map.of(UNPUBLISHED_KEY, "x")),
                Arguments.of("a value that is not a string", Map.of(REGISTRATION_NUMBER_KEY, 42)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unpublishableLegalPersonColumns")
    @DisplayName("a legal person this revision cannot publish reads back as absent")
    void unpublishableLegalPersonPropertiesReadAsAbsent(String name, Map<String, Object> column) {
        assertThat(mapper.toRepresentation(stored(Map.of(), column)).getLegalPerson()).isNull();
    }

    /** A column that names one property this revision knows still publishes that one. */
    @Test
    @DisplayName("publishes the known properties of a column that also carries unknown ones")
    void publishesTheKnownPropertiesOfAMixedColumn() {
        var column = Map.<String, Object>of(UNPUBLISHED_KEY, "x", REGISTRATION_NUMBER_KEY, "DE1");

        assertThat(mapper.toRepresentation(stored(Map.of(), column)).getLegalPerson())
                .isEqualTo(new ParticipantLegalPerson().registrationNumber("DE1"));
    }

    @Test
    @DisplayName("an empty legal-person column reads back as no object at all")
    void anEmptyLegalPersonColumnReadsAsAbsent() {
        assertThat(mapper.toRepresentation(stored(Map.of(), Map.of())).getLegalPerson()).isNull();
    }

    @Test
    @DisplayName("publishes timestamps at UTC rather than at the server's zone")
    void publishesTimestampsAtUtc() {
        var representation = mapper.toRepresentation(stored(Map.of(), null));

        assertThat(representation.getCreatedAt())
                .isEqualTo(OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
        assertThat(representation.getUpdatedAt()).isEqualTo(representation.getCreatedAt());
    }

    /** Absent rather than present-and-null is what tells a reader the participant is active. */
    @Test
    @DisplayName("omits the deregistration timestamp of an active participant")
    void omitsTheDeregistrationTimestampWhileActive() {
        assertThat(mapper.toRepresentation(stored(Map.of(), null)).getDeregisteredAt()).isNull();
    }

    @Test
    @DisplayName("publishes the deregistration timestamp of a retained participant at UTC")
    void publishesTheDeregistrationTimestamp() {
        var participant = stored(Map.of(), null);
        participant.setDeregisteredAt(DEREGISTERED_AT);

        assertThat(mapper.toRepresentation(participant).getDeregisteredAt())
                .isEqualTo(OffsetDateTime.ofInstant(DEREGISTERED_AT, ZoneOffset.UTC));
    }
}
