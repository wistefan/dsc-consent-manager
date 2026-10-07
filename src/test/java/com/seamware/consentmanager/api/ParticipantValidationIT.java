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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The validation matrix over the two routes that accept a participant self-description, run end to
 * end against a real Keycloak and a real PostgreSQL.
 *
 * <p>{@code ParticipantRegistration} and {@code ParticipantUpdate} are separate schema files that
 * {@link ParticipantSchemaParityTest} keeps constrained identically, so every payload here is sent
 * to both routes: a bound tightened on one and forgotten on the other fails on whichever route
 * still admits the value. What is asserted is the whole published contract of a refusal — the
 * status, the problem media type, and that the detail names the field the caller has to fix — plus
 * that the refusal left the database as it found it.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant self-description validation")
class ParticipantValidationIT extends KeycloakAndPostgresTestResource {

    private static final String PATH_REGISTER = "/participants";

    private static final String PATH_UPDATE = PATH_REGISTER + "/me";

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** Legal name of the row the update route is given to work on, and the one a refusal keeps. */
    private static final String SEEDED_LEGAL_NAME = "Seeded Clinic Ltd";

    private static final String LEGAL_NAME = "Validation Clinic Ltd";

    private static final String SELF_DESCRIPTION_URI = "https://clinic.example.org/sd.json";

    private static final String EMAIL = "data-protection@clinic.example.org";

    private static final String CONSENT_NOTIFICATION = "https://clinic.example.org/notify";

    private static final String REGISTRATION_NUMBER = "DE123456789";

    private static final String HEADQUARTERS_ADDRESS = "Hauptstrasse 1, 10115 Berlin, DE";

    private static final String PARENT_ORGANIZATION = "urn:example:participant:holding";

    /** Prefix every generated URL is padded out from, so a length-bound case stays a valid URL. */
    private static final String URI_PREFIX = "https://clinic.example.org/";

    private static final int MAX_LEGAL_NAME_LENGTH = 255;

    private static final int MAX_URI_LENGTH = 2048;

    private static final int MAX_REGISTRATION_NUMBER_LENGTH = 255;

    private static final int MAX_ADDRESS_LENGTH = 1024;

    private static final int MAX_ORGANIZATION_LENGTH = 2048;

    /** Media type every refusal carries, per RFC 7807. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** The problem member that names what was rejected. */
    private static final String DETAIL_MEMBER = "detail";

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject ParticipantRepository participants;

    /**
     * The two routes a self-description is submitted to, each with the status it answers when the
     * payload is valid.
     *
     * <p>They differ in the state they need — registration refuses a caller that already has a row,
     * the update refuses one that has none — so each case prepares the fixture its route wants
     * rather than relying on a shared {@code @BeforeEach}.
     */
    enum Route {
        REGISTER(HttpStatus.CREATED),
        UPDATE(HttpStatus.OK);

        private final HttpStatus accepted;

        Route(HttpStatus accepted) {
            this.accepted = accepted;
        }

        /** The status the route answers with when it applies the payload. */
        HttpStatus accepted() {
            return accepted;
        }

        MutableHttpRequest<?> request(Map<String, Object> body) {
            return this == REGISTER
                    ? HttpRequest.POST(PATH_REGISTER, body)
                    : HttpRequest.PUT(PATH_UPDATE, body);
        }
    }

    /** A payload the schema must refuse, with the property whose bound it breaks. */
    record Rejected(String description, String field, Map<String, Object> body) {}

    @BeforeEach
    void awaitTheRealm() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        removeCommittedRows();
    }

    /** Rows committed by these tests outlive the transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        participants.findByIdentifier(CALLER_PARTICIPANT).ifPresent(participants::delete);
    }

    @ParameterizedTest(name = "{0} refuses {1}")
    @MethodSource("rejectedPayloads")
    @DisplayName("an invalid self-description is refused, naming the field, writing nothing")
    void anInvalidSelfDescriptionIsRefused(Route route, String description, Rejected rejected)
            throws IOException {
        prepare(route);

        HttpResponse<String> response = exchange(route.request(rejected.body()));

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(response.getContentType().orElseThrow().toString()).isEqualTo(PROBLEM_JSON);
        assertThat(detail(response))
                .as("a caller cannot fix a field the problem does not name")
                .contains(rejected.field());
        assertNothingWritten(route);
    }

    @ParameterizedTest(name = "{0} accepts {1}")
    @MethodSource("acceptedPayloads")
    @DisplayName("a self-description within every bound is applied")
    void aValidSelfDescriptionIsApplied(Route route, String description, Map<String, Object> body) {
        prepare(route);

        HttpResponse<String> response = exchange(route.request(body));

        assertThat(response.code()).isEqualTo(route.accepted().getCode());
        assertThat(participants.findByIdentifier(CALLER_PARTICIPANT).orElseThrow().getLegalName())
                .isEqualTo(body.get("legalName"));
    }

    static Stream<Arguments> rejectedPayloads() {
        return crossProduct(
                rejectedBodies().map(rejected -> Arguments.of(rejected.description(), rejected)));
    }

    static Stream<Arguments> acceptedPayloads() {
        return crossProduct(acceptedBodies());
    }

    /**
     * Every payload the schemas must refuse.
     *
     * <p>The three URL properties are checked against the same four failures — plaintext, relative,
     * whitespace and over-long — because they carry the same pattern and the same bound, and a
     * property that silently lost one of them would otherwise still pass the others.
     */
    static Stream<Rejected> rejectedBodies() {
        return Stream.of(
                new Rejected("no legal name at all", "legalName", Map.of("email", EMAIL)),
                new Rejected("an empty legal name", "legalName", Map.of("legalName", "")),
                new Rejected("a blank legal name", "legalName", Map.of("legalName", "   ")),
                new Rejected(
                        "an over-long legal name",
                        "legalName",
                        Map.of("legalName", "x".repeat(MAX_LEGAL_NAME_LENGTH + 1))),
                rejectedUrl("selfDescriptionUri", "plaintext", "http://clinic.example.org/sd.json"),
                rejectedUrl("selfDescriptionUri", "relative", "/sd.json"),
                rejectedUrl(
                        "selfDescriptionUri", "malformed", "https://clinic example.org/sd.json"),
                rejectedUrl("selfDescriptionUri", "over-long", uriOfLength(MAX_URI_LENGTH + 1)),
                new Rejected(
                        "a malformed email", "email", valid(Map.of("email", "not-an-address"))),
                rejectedEndpoint("plaintext", "http://clinic.example.org/notify"),
                rejectedEndpoint("relative", "/notify"),
                rejectedEndpoint("malformed", "https://clinic example.org/notify"),
                rejectedEndpoint("over-long", uriOfLength(MAX_URI_LENGTH + 1)),
                rejectedLegalPerson("registrationNumber", MAX_REGISTRATION_NUMBER_LENGTH),
                rejectedLegalPerson("headquartersAddress", MAX_ADDRESS_LENGTH),
                rejectedLegalPerson("legalAddress", MAX_ADDRESS_LENGTH),
                rejectedLegalPerson("parentOrganization", MAX_ORGANIZATION_LENGTH),
                rejectedLegalPerson("subOrganization", MAX_ORGANIZATION_LENGTH));
    }

    /** Every payload the schemas must accept, including each bound at its last admissible value. */
    static Stream<Arguments> acceptedBodies() {
        return Stream.of(
                Arguments.of("only the required legal name", Map.of("legalName", LEGAL_NAME)),
                Arguments.of(
                        "every property the schema accepts",
                        valid(
                                Map.of(
                                        "selfDescriptionUri",
                                        SELF_DESCRIPTION_URI,
                                        "email",
                                        EMAIL,
                                        "endpoints",
                                        Map.of("consentNotification", CONSENT_NOTIFICATION),
                                        "legalPerson",
                                        Map.of(
                                                "registrationNumber", REGISTRATION_NUMBER,
                                                "headquartersAddress", HEADQUARTERS_ADDRESS,
                                                "legalAddress", HEADQUARTERS_ADDRESS,
                                                "parentOrganization", PARENT_ORGANIZATION,
                                                "subOrganization", PARENT_ORGANIZATION)))),
                Arguments.of(
                        "a legal name at its length bound",
                        Map.of("legalName", "x".repeat(MAX_LEGAL_NAME_LENGTH))),
                Arguments.of(
                        "a self-description URL at its length bound",
                        valid(Map.of("selfDescriptionUri", uriOfLength(MAX_URI_LENGTH)))),
                Arguments.of(
                        "a notification endpoint at its length bound",
                        valid(
                                Map.of(
                                        "endpoints",
                                        Map.of(
                                                "consentNotification",
                                                uriOfLength(MAX_URI_LENGTH))))),
                Arguments.of(
                        "legal-person fields at their length bounds",
                        valid(
                                Map.of(
                                        "legalPerson",
                                        Map.of(
                                                "registrationNumber",
                                                        "x".repeat(MAX_REGISTRATION_NUMBER_LENGTH),
                                                "headquartersAddress",
                                                        "x".repeat(MAX_ADDRESS_LENGTH),
                                                "legalAddress", "x".repeat(MAX_ADDRESS_LENGTH),
                                                "parentOrganization",
                                                        "x".repeat(MAX_ORGANIZATION_LENGTH),
                                                "subOrganization",
                                                        "x".repeat(MAX_ORGANIZATION_LENGTH))))));
    }

    /** Runs each case against both routes, so neither schema drifts away from the other. */
    private static Stream<Arguments> crossProduct(Stream<Arguments> cases) {
        return cases.toList().stream()
                .flatMap(
                        testCase ->
                                Stream.of(Route.values())
                                        .map(
                                                route ->
                                                        Arguments.of(
                                                                route,
                                                                testCase.get()[0],
                                                                testCase.get()[1])));
    }

    private static Rejected rejectedUrl(String property, String flaw, String value) {
        return new Rejected("a " + flaw + " " + property, property, valid(Map.of(property, value)));
    }

    private static Rejected rejectedEndpoint(String flaw, String value) {
        return new Rejected(
                "a " + flaw + " notification endpoint",
                "consentNotification",
                valid(Map.of("endpoints", Map.of("consentNotification", value))));
    }

    private static Rejected rejectedLegalPerson(String property, int maxLength) {
        return new Rejected(
                "an over-long " + property,
                property,
                valid(Map.of("legalPerson", Map.of(property, "x".repeat(maxLength + 1)))));
    }

    /** The given properties on top of a body that is otherwise valid, so one flaw is under test. */
    private static Map<String, Object> valid(Map<String, Object> properties) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("legalName", LEGAL_NAME));
        body.putAll(properties);
        return body;
    }

    /** An {@code https} URL of exactly {@code length} characters. */
    private static String uriOfLength(int length) {
        return URI_PREFIX + "x".repeat(length - URI_PREFIX.length());
    }

    /**
     * Puts the caller in the state the route needs: unregistered to register, registered to update.
     */
    private void prepare(Route route) {
        removeCommittedRows();
        if (route == Route.UPDATE) {
            participants.save(
                    new Participant(
                            CALLER_PARTICIPANT, SEEDED_LEGAL_NAME, null, null, Map.of(), null));
        }
    }

    /** A refused payload leaves the caller as it was: still unregistered, or still as seeded. */
    private void assertNothingWritten(Route route) {
        Optional<Participant> stored = participants.findByIdentifier(CALLER_PARTICIPANT);
        switch (route) {
            case REGISTER -> assertThat(stored).isEmpty();
            case UPDATE ->
                    assertThat(stored.orElseThrow().getLegalName()).isEqualTo(SEEDED_LEGAL_NAME);
        }
    }

    private String detail(HttpResponse<String> response) throws IOException {
        Object detail =
                json.readValue(response.body(), Argument.mapOf(String.class, Object.class))
                        .get(DETAIL_MEMBER);
        return String.valueOf(detail);
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
