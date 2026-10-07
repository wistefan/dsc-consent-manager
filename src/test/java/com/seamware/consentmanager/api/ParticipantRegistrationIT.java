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
 * Exercises {@code POST /participants} end to end against a real Keycloak and a real PostgreSQL.
 *
 * <p>What is under test is the registration rule as the API publishes it: that an unregistered
 * participant token reaches the route at all, that the identifier is taken from the token however
 * the body tries to name one, that a second registration is refused rather than silently applied,
 * and that the representation carries no credential.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant self-registration")
class ParticipantRegistrationIT extends KeycloakAndPostgresTestResource {

    private static final String PATH = "/participants";

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** Never the caller: smuggled into the body, it must not reach a column or the response. */
    private static final String FOREIGN_PARTICIPANT = "urn:test:participant:registration-foreign";

    private static final String LEGAL_NAME = "Registration Clinic Ltd";

    private static final String SELF_DESCRIPTION_URI = "https://clinic.example.org/sd.json";

    private static final String EMAIL = "data-protection@clinic.example.org";

    private static final String CONSENT_NOTIFICATION = "https://clinic.example.org/notify";

    private static final String REGISTRATION_NUMBER = "DE123456789";

    /** Every property the registration schema accepts, so a transposed argument shows up. */
    private static final Map<String, Object> EVERY_PROPERTY =
            Map.of(
                    "legalName", LEGAL_NAME,
                    "selfDescriptionUri", SELF_DESCRIPTION_URI,
                    "email", EMAIL,
                    "legalPerson", Map.of("registrationNumber", REGISTRATION_NUMBER),
                    "endpoints", Map.of("consentNotification", CONSENT_NOTIFICATION));

    /** Exactly what the representation publishes for a freshly registered participant. */
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

    /** The {@code maxLength} the registration schema puts on a legal name. */
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
    void awaitTheRealmAndEmptyTheDirectory() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        removeCommittedRows();
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
    @DisplayName("records the organization under the identifier its token asserts")
    void recordsTheOrganizationUnderTheTokenIdentifier() throws IOException {
        HttpResponse<String> response = post(EVERY_PROPERTY);

        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(body(response))
                .containsEntry("identifier", CALLER_PARTICIPANT)
                .containsEntry("legalName", LEGAL_NAME)
                .containsEntry("selfDescriptionUri", SELF_DESCRIPTION_URI)
                .containsEntry("email", EMAIL)
                .containsEntry("legalPerson", Map.of("registrationNumber", REGISTRATION_NUMBER))
                .containsEntry("endpoints", Map.of("consentNotification", CONSENT_NOTIFICATION));

        Participant stored = participants.findByIdentifier(CALLER_PARTICIPANT).orElseThrow();
        assertThat(stored.getLegalName()).isEqualTo(LEGAL_NAME);
        assertThat(stored.getSelfDescriptionUri()).isEqualTo(SELF_DESCRIPTION_URI);
        assertThat(stored.getEmail()).isEqualTo(EMAIL);
        assertThat(stored.getEndpoints())
                .containsEntry("consentNotification", CONSENT_NOTIFICATION);
        assertThat(stored.getLegalPerson())
                .containsEntry("registrationNumber", REGISTRATION_NUMBER);
    }

    /** AC 2: the property is simply absent from the schema, so it is ignored rather than a 400. */
    @Test
    @DisplayName("an identifier in the body is ignored rather than rejected")
    void anIdentifierInTheBodyIsIgnored() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>(EVERY_PROPERTY);
        body.put("identifier", FOREIGN_PARTICIPANT);

        HttpResponse<String> response = post(body);

        assertThat(response.code())
                .as("an unknown property is ignored, so a 400 here is a rejection of it")
                .isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(body(response))
                .as("the record follows the token, never the body")
                .containsEntry("identifier", CALLER_PARTICIPANT);
        assertThat(participants.findByIdentifier(FOREIGN_PARTICIPANT)).isEmpty();
    }

    @Test
    @DisplayName("a second registration is refused rather than overwriting the first")
    void aSecondRegistrationConflicts() throws IOException {
        assertThat(post(EVERY_PROPERTY).code()).isEqualTo(HttpStatus.CREATED.getCode());

        HttpResponse<String> response = post(Map.of("legalName", "Renamed Clinic Ltd"));

        assertThat(response.code()).isEqualTo(HttpStatus.CONFLICT.getCode());
        assertThat(participants.findByIdentifier(CALLER_PARTICIPANT).orElseThrow().getLegalName())
                .isEqualTo(LEGAL_NAME);
    }

    /** AC 14 for this surface, plus the rule that an active participant has no deregistration. */
    @Test
    @DisplayName("publishes the self-description and nothing else")
    void publishesTheSelfDescriptionAndNothingElse() throws IOException {
        assertThat(body(post(EVERY_PROPERTY))).containsOnlyKeys(PUBLISHED_PROPERTIES);
    }

    @Test
    @DisplayName("a participant that supplies only its legal name is registered")
    void aMinimalRegistrationIsAccepted() throws IOException {
        HttpResponse<String> response = post(Map.of("legalName", LEGAL_NAME));

        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(body(response))
                .containsEntry("identifier", CALLER_PARTICIPANT)
                .doesNotContainKeys("selfDescriptionUri", "email", "legalPerson", "endpoints");
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("no legal name at all", Map.of("email", EMAIL)),
                Arguments.of("an empty legal name", Map.of("legalName", "")),
                Arguments.of("a blank legal name", Map.of("legalName", "   ")),
                Arguments.of(
                        "an over-long legal name",
                        Map.of("legalName", "x".repeat(MAX_LEGAL_NAME_LENGTH + 1))),
                Arguments.of(
                        "a plaintext self-description URL",
                        Map.of(
                                "legalName",
                                LEGAL_NAME,
                                "selfDescriptionUri",
                                "http://clinic.example.org/sd.json")),
                Arguments.of(
                        "a relative self-description URL",
                        Map.of("legalName", LEGAL_NAME, "selfDescriptionUri", "/sd.json")),
                Arguments.of(
                        "a plaintext notification endpoint",
                        Map.of(
                                "legalName",
                                LEGAL_NAME,
                                "endpoints",
                                Map.of(
                                        "consentNotification",
                                        "http://clinic.example.org/notify"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("an invalid registration is refused before anything is written")
    void anInvalidRegistrationIsRefused(String name, Map<String, Object> body) {
        assertThat(post(body).code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(participants.findByIdentifier(CALLER_PARTICIPANT)).isEmpty();
    }

    @ParameterizedTest(name = "{0} gets {1}")
    @MethodSource("callers")
    @DisplayName("only a participant may register a participant")
    void onlyAParticipantMayRegister(RealmPrincipal caller, HttpStatus expected) {
        HttpResponse<String> response =
                exchange(
                        HttpRequest.POST(PATH, Map.of("legalName", LEGAL_NAME)),
                        caller == null ? null : KeycloakTestResource.accessToken(caller));

        assertThat(response.code()).isEqualTo(expected.getCode());
    }

    /** Who may reach the route at all, before the registration rule gets a say. */
    static Stream<Arguments> callers() {
        return Stream.of(
                Arguments.of(RealmPrincipal.USER, HttpStatus.FORBIDDEN),
                Arguments.of(RealmPrincipal.CATALOG, HttpStatus.FORBIDDEN),
                Arguments.of(RealmPrincipal.OUTSIDER, HttpStatus.FORBIDDEN),
                Arguments.of(null, HttpStatus.UNAUTHORIZED));
    }

    private HttpResponse<String> post(Map<String, Object> body) {
        return exchange(
                HttpRequest.POST(PATH, body),
                KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
    }

    private Map<String, Object> body(HttpResponse<String> response) throws IOException {
        return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
    }

    /** Issues the request, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request, String token) {
        MutableHttpRequest<?> sent = token == null ? request : request.bearerAuth(token);
        try {
            return client.toBlocking().exchange(sent, String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }
}
