package com.seamware.consentmanager.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.seamware.consentmanager.client.dto.SelfDescription;
import com.seamware.consentmanager.support.TestJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins which catalog documents a resolved retrieval can read.
 *
 * <p>No real catalog document was available to this ticket, so the shape of {@code dataResources}
 * is the one thing here that is not evidence but a decision: both plausible forms are accepted.
 * These cases are what keeps that decision true - a record committed to one form decodes the other
 * into an exception, and a decode failure is silent and total, leaving every notice's data section
 * unavailable with a green build.
 */
@DisplayName("Self-description deserialization")
class SelfDescriptionDocumentTest {

    private static final String FIRST_RESOURCE = "https://catalog.example.org/dataresources/trips";

    private static final String SECOND_RESOURCE = "https://catalog.example.org/dataresources/stops";

    /** The forms a catalog may name the resources behind an offering in. */
    static Stream<Arguments> resourceShapes() {
        return Stream.of(
                Arguments.of(
                        "bare URL strings",
                        """
                        ["%s", "%s"]"""
                                .formatted(FIRST_RESOURCE, SECOND_RESOURCE)),
                Arguments.of(
                        "objects naming the resource under @id",
                        """
                        [{"@id": "%s"}, {"@id": "%s"}]"""
                                .formatted(FIRST_RESOURCE, SECOND_RESOURCE)),
                Arguments.of(
                        "objects naming the resource under _id, with members of their own",
                        """
                        [{"_id": "%s", "name": "Trips"}, {"_id": "%s", "name": "Stops"}]"""
                                .formatted(FIRST_RESOURCE, SECOND_RESOURCE)),
                Arguments.of(
                        "objects naming the resource under url",
                        """
                        [{"url": "%s"}, {"url": "%s"}]"""
                                .formatted(FIRST_RESOURCE, SECOND_RESOURCE)),
                Arguments.of(
                        "the two forms mixed, as a catalog in migration would serve them",
                        """
                        ["%s", {"@id": "%s"}]"""
                                .formatted(FIRST_RESOURCE, SECOND_RESOURCE)));
    }

    @ParameterizedTest(name = "{0} name the resources behind an offering")
    @MethodSource("resourceShapes")
    @DisplayName("every accepted form of a resource list reads back as the URLs it names")
    void readsEveryAcceptedResourceShape(String name, String resources) {
        SelfDescription offering = decode(offeringNaming(resources));

        assertThat(offering.dataResourceUrls())
                .as("the second hop is reachable only if the first hop's list was understood")
                .containsExactly(FIRST_RESOURCE, SECOND_RESOURCE);
    }

    @Test
    @DisplayName("an entry carrying no usable URL is dropped rather than failing the document")
    void dropsAnEntryThatNamesNothing() {
        SelfDescription offering =
                decode(
                        offeringNaming(
                                """
                                ["%s", {"name": "No identifier here"}, "", null, 7]"""
                                        .formatted(FIRST_RESOURCE)));

        assertThat(offering.dataResourceUrls())
                .as("one unreadable entry must not cost the resources beside it")
                .containsExactly(FIRST_RESOURCE);
    }

    @Test
    @DisplayName("a resource document names no resources of its own and decodes all the same")
    void decodesADocumentWithoutResources() {
        SelfDescription resource =
                decode(
                        """
                        {"_id": "trips", "name": "Trip records", "description": "One per trip."}""");

        assertThat(resource.name()).isEqualTo("Trip records");
        assertThat(resource.dataResourceUrls()).isEmpty();
    }

    @Test
    @DisplayName("a member this service does not read does not break the document")
    void ignoresAnUnknownMember() {
        assertThatCode(
                        () ->
                                decode(
                                        """
                                        {"_id": "trips", "licenses": [{"@id": "cc-by"}]}"""))
                .doesNotThrowAnyException();
    }

    /** An offering document naming those resources, which is the only shape under test. */
    private static String offeringNaming(String resources) {
        return """
               {"_id": "trip-history", "name": "Trip history", "dataResources": %s}"""
                .formatted(resources);
    }

    /** Decoded with the application's own mapper, as a fetch decodes it. */
    private static SelfDescription decode(String document) {
        try {
            return TestJson.mapper().readValue(document, SelfDescription.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decode self-description document", e);
        }
    }
}
