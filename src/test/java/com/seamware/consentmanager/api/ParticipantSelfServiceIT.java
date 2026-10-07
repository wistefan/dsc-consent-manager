package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises {@code GET /participants/me} and {@code PUT /participants/me} end to end against a real
 * Keycloak and a real PostgreSQL.
 *
 * <p>Both routes name their subject from the token alone, so what is under test is that the record
 * reached is the caller's whatever the body says, that a replacement really replaces, and that the
 * response is the row as stored. Who may reach the routes at all, and what an unregistered
 * participant gets, are asserted exhaustively by {@link EndpointRoleMatrixIT}.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant self-service")
class ParticipantSelfServiceIT extends KeycloakAndPostgresTestResource {

    private static final String PATH = "/participants/me";

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** Never the caller: smuggled into the body, it must not reach a column or the response. */
    private static final String FOREIGN_PARTICIPANT = "urn:test:participant:self-service-foreign";

    private static final String REGISTERED_LEGAL_NAME = "Registered Clinic Ltd";

    private static final String REGISTERED_SELF_DESCRIPTION_URI =
            "https://clinic.example.org/sd.json";

    private static final String REGISTERED_EMAIL = "registered@clinic.example.org";

    private static final Map<String, Object> REGISTERED_ENDPOINTS =
            Map.of("consentNotification", "https://clinic.example.org/notify");

    private static final Map<String, Object> REGISTERED_LEGAL_PERSON =
            Map.of("registrationNumber", "DE123456789");

    private static final String REPLACED_LEGAL_NAME = "Replaced Clinic Ltd";

    private static final String REPLACED_SELF_DESCRIPTION_URI =
            "https://clinic.example.org/sd-v2.json";

    private static final String REPLACED_EMAIL = "replaced@clinic.example.org";

    private static final Map<String, Object> REPLACED_ENDPOINTS =
            Map.of("consentNotification", "https://clinic.example.org/notify/v2");

    private static final Map<String, Object> REPLACED_LEGAL_PERSON =
            Map.of("registrationNumber", "DE987654321");

    /** Every property the update schema accepts, so a transposed argument shows up. */
    private static final Map<String, Object> EVERY_PROPERTY =
            Map.of(
                    "legalName", REPLACED_LEGAL_NAME,
                    "selfDescriptionUri", REPLACED_SELF_DESCRIPTION_URI,
                    "email", REPLACED_EMAIL,
                    "legalPerson", REPLACED_LEGAL_PERSON,
                    "endpoints", REPLACED_ENDPOINTS);

    /** Exactly what the representation publishes for an active participant. */
    private static final List<String> PUBLISHED_PROPERTIES =
            List.of(
                    "identifier",
                    "legalName",
                    "selfDescriptionUri",
                    "email",
                    "legalPerson",
                    "endpoints",
                    "createdAt",
                    "updatedAt");

    /** The {@code maxLength} the update schema puts on a legal name. */
    private static final int MAX_LEGAL_NAME_LENGTH = 255;

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject ParticipantRepository participants;

    @BeforeEach
    void awaitTheRealmAndRegisterTheCaller() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        removeCommittedRows();
        participants.save(
                new Participant(
                        CALLER_PARTICIPANT,
                        REGISTERED_LEGAL_NAME,
                        REGISTERED_SELF_DESCRIPTION_URI,
                        REGISTERED_EMAIL,
                        REGISTERED_ENDPOINTS,
                        REGISTERED_LEGAL_PERSON));
    }

    /** Rows committed by these tests outlive the transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        List.of(CALLER_PARTICIPANT, FOREIGN_PARTICIPANT)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @Test
    @DisplayName("reads back the record the caller's token names, and nothing beyond it")
    void readsBackTheCallersRecord() throws IOException {
        HttpResponse<String> response = exchange(HttpRequest.GET(PATH));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .containsOnlyKeys(PUBLISHED_PROPERTIES)
                .containsEntry("identifier", CALLER_PARTICIPANT)
                .containsEntry("legalName", REGISTERED_LEGAL_NAME)
                .containsEntry("selfDescriptionUri", REGISTERED_SELF_DESCRIPTION_URI)
                .containsEntry("email", REGISTERED_EMAIL)
                .containsEntry("legalPerson", REGISTERED_LEGAL_PERSON)
                .containsEntry("endpoints", REGISTERED_ENDPOINTS);
    }

    @Test
    @DisplayName("an update replaces every mutable field and answers with the stored row")
    void anUpdateReplacesEveryMutableField() throws IOException {
        HttpResponse<String> response = exchange(HttpRequest.PUT(PATH, EVERY_PROPERTY));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .containsEntry("identifier", CALLER_PARTICIPANT)
                .containsEntry("legalName", REPLACED_LEGAL_NAME)
                .containsEntry("selfDescriptionUri", REPLACED_SELF_DESCRIPTION_URI)
                .containsEntry("email", REPLACED_EMAIL)
                .containsEntry("legalPerson", REPLACED_LEGAL_PERSON)
                .containsEntry("endpoints", REPLACED_ENDPOINTS);

        Participant stored = participants.findByIdentifier(CALLER_PARTICIPANT).orElseThrow();
        assertThat(stored.getLegalName()).isEqualTo(REPLACED_LEGAL_NAME);
        assertThat(stored.getSelfDescriptionUri()).isEqualTo(REPLACED_SELF_DESCRIPTION_URI);
        assertThat(stored.getEmail()).isEqualTo(REPLACED_EMAIL);
        assertThat(stored.getEndpoints()).isEqualTo(REPLACED_ENDPOINTS);
        assertThat(stored.getLegalPerson()).isEqualTo(REPLACED_LEGAL_PERSON);
    }

    /** AC 2: the property is simply absent from the schema, so it is ignored rather than a 400. */
    @Test
    @DisplayName("an identifier in the update body is ignored rather than rejected")
    void anIdentifierInTheBodyIsIgnored() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>(EVERY_PROPERTY);
        body.put("identifier", FOREIGN_PARTICIPANT);

        HttpResponse<String> response = exchange(HttpRequest.PUT(PATH, body));

        assertThat(response.code())
                .as("an unknown property is ignored, so a 400 here is a rejection of it")
                .isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .as("the record follows the token, never the body")
                .containsEntry("identifier", CALLER_PARTICIPANT);
        assertThat(participants.findByIdentifier(FOREIGN_PARTICIPANT)).isEmpty();
    }

    @Test
    @DisplayName("an update is a replacement, so an omitted optional property is cleared")
    void anOmittedOptionalPropertyIsCleared() throws IOException {
        HttpResponse<String> response =
                exchange(HttpRequest.PUT(PATH, Map.of("legalName", REPLACED_LEGAL_NAME)));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .containsEntry("legalName", REPLACED_LEGAL_NAME)
                .doesNotContainKeys("selfDescriptionUri", "email", "legalPerson", "endpoints");
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("no legal name at all", Map.of("email", REPLACED_EMAIL)),
                Arguments.of("an empty legal name", Map.of("legalName", "")),
                Arguments.of("a blank legal name", Map.of("legalName", "   ")),
                Arguments.of(
                        "an over-long legal name",
                        Map.of("legalName", "x".repeat(MAX_LEGAL_NAME_LENGTH + 1))),
                Arguments.of(
                        "a plaintext self-description URL",
                        Map.of(
                                "legalName",
                                REPLACED_LEGAL_NAME,
                                "selfDescriptionUri",
                                "http://clinic.example.org/sd.json")),
                Arguments.of(
                        "a malformed email",
                        Map.of("legalName", REPLACED_LEGAL_NAME, "email", "not-an-address")));
    }

    @ParameterizedTest(name = "{0} is refused")
    @MethodSource("invalidBodies")
    @DisplayName("an invalid update is refused and leaves the registration as it was")
    void anInvalidUpdateIsRefused(String description, Map<String, Object> body) {
        HttpResponse<String> response = exchange(HttpRequest.PUT(PATH, body));

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(participants.findByIdentifier(CALLER_PARTICIPANT).orElseThrow().getLegalName())
                .isEqualTo(REGISTERED_LEGAL_NAME);
    }

    private Map<String, Object> body(HttpResponse<String> response) throws IOException {
        return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
    }

    /** Issues the request as the realm's participant, returning a refusal rather than throwing. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request) {
        try {
            return client.toBlocking()
                    .exchange(
                            request.bearerAuth(
                                    KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT)),
                            String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }
}
