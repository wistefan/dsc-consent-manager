package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.security.Role;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Authorization matrix over every published operation, with real Keycloak tokens: fails when a
 * route is served to the wrong role, reachable without a token, or missing from the matrix.
 */
@MicronautTest(transactional = false)
@DisplayName("Role matrix over the published endpoints")
class EndpointRoleMatrixIT extends KeycloakAndPostgresTestResource {

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String PARTICIPANT_LEGAL_NAME = "Role Matrix Participant Ltd";

    /** A second participant, so every case has a record it is entitled to none of. */
    private static final String OTHER_PARTICIPANT = "urn:test:participant:role-matrix-bystander";

    private static final String OTHER_LEGAL_NAME = "Role Matrix Bystander Ltd";

    /** User linked to the caller participant, so every read route has something to answer. */
    private static final String LINKED_USER = "urn:test:user:role-matrix";

    /** The caller participant's label for its link, so the {@code PATCH} updates one. */
    private static final String LOCAL_IDENTIFIER = "role-matrix-local";

    private static final String PATH_API_STATUS = "/api-status";

    private static final String PATH_USERS = "/users";

    private static final String PATH_ME = PATH_USERS + "/me";

    private static final String PATH_REGISTER = PATH_USERS + "/register";

    private static final String PATH_SEARCH = PATH_USERS + "/search";

    private static final String PATH_PARTICIPANTS = "/participants";

    private static final String PATH_CURRENT_PARTICIPANT = PATH_PARTICIPANTS + "/me";

    private static final String PATH_PARTICIPANT_USERS = PATH_CURRENT_PARTICIPANT + "/users";

    private static final String PATH_BULK = PATH_PARTICIPANT_USERS + "/bulk";

    private static final String PATH_PRIVACY_NOTICES = "/privacy-notices";

    private static final String PATH_PRIVACY_NOTICES_BY_CONTRACT =
            PATH_PRIVACY_NOTICES + "/by-contract";

    private static final String PATH_CONSENTS = "/consents";

    private static final String PATH_CONSENT_REQUESTS = PATH_CONSENTS + "/requests";

    private static final String PATH_CONTRACTS_AVAILABLE = "/contracts/available";

    private static final String PATH_JWKS = "/.well-known/jwks.json";

    /** A side contract discovery accepts, so the role row is decided by role and not by input. */
    private static final String PROVIDER_ROLE = "provider";

    /** A decision the grant row states, so the body passes validation and the route is reached. */
    private static final String GRANTED_DECISION = "GRANTED";

    /** A notice id no case stores, so every admitted caller stops at the same {@code 404}. */
    private static final String ABSENT_NOTICE = "0199a4f2-0000-7000-8000-000000000000";

    /** A consent id no case stores, so an admitted lifecycle caller stops at {@code 404}. */
    private static final String ABSENT_CONSENT = "0199a4f2-0001-7000-8000-000000000000";

    /** A contract no case serves; paired with {@link #UNREGISTERED_PARTY} it is never fetched. */
    private static final String UNSERVED_CONTRACT =
            "https://contracts.example.org/contracts/absent";

    /** Never registered, so generation stops at the directory before any Contract Service call. */
    private static final String UNREGISTERED_PARTY = "urn:test:participant:role-matrix-unknown";

    /**
     * The one {@code PARTICIPANT} operation an unregistered identifier may reach, so it is excluded
     * from {@link #participantScopedEndpoints()}.
     */
    private static final String SELF_REGISTRATION = "post " + PATH_PARTICIPANTS;

    /** HTTP methods that change a record, spelled as the specification spells them. */
    private static final Set<String> MUTATING_METHODS = Set.of("post", "put", "patch", "delete");

    /** Marks a path segment the caller fills in, which a self-scoped route must not have. */
    private static final String PATH_TEMPLATE_MARKER = "{";

    private static final String PATH_TEMPLATE_END = "}";

    private static final String PARTICIPANTS_SEGMENT = "participants";

    /** Legal name a foreign write would leave behind, so a write that lands is unmistakable. */
    private static final String HIJACKED_LEGAL_NAME = "Hijacked Ltd";

    /** Last status code that still reports success; a route that serves nothing stays above it. */
    private static final int HIGHEST_SUCCESS_CODE = 299;

    private static final String NO_BODY = "";

    /** Contract Service URL on a reserved port, where a connection is refused, not timed out. */
    private static final String UNREACHABLE_CONTRACT_SERVICE = "http://localhost:1";

    /** Retries after the initial attempt; one is the floor {@code @Retryable} accepts. */
    private static final String SINGLE_RETRY = "1";

    private static final String RETRY_DELAY = "10ms";

    private static final String PROBLEM_JSON = "application/problem+json";

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject UserParticipantRepository links;

    /** The {@code USER} caller's identifier, resolved once because reading it costs a token. */
    private String userIdentifier;

    /**
     * Points the Contract Service at a closed port with the smallest retry budget, so the
     * by-contract row gets an upstream failure in milliseconds.
     */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put("consent-manager.contract-service.url", UNREACHABLE_CONTRACT_SERVICE);
        properties.put("consent-manager.contract-service.retry-attempts", SINGLE_RETRY);
        properties.put("consent-manager.contract-service.retry-delay", RETRY_DELAY);
        return properties;
    }

    /**
     * One caller per realm role, plus {@code OUTSIDER} (a role mapped to nothing) and {@code
     * ANONYMOUS} (no token).
     */
    enum Caller {
        USER(RealmPrincipal.USER),
        PARTICIPANT(RealmPrincipal.PARTICIPANT),
        CATALOG(RealmPrincipal.CATALOG),
        OUTSIDER(RealmPrincipal.OUTSIDER),
        ANONYMOUS(null);

        private final RealmPrincipal realmPrincipal;

        Caller(RealmPrincipal realmPrincipal) {
            this.realmPrincipal = realmPrincipal;
        }

        /** A freshly issued token for this caller, or {@code null} when it sends none. */
        String token() {
            return realmPrincipal == null ? null : KeycloakTestResource.accessToken(realmPrincipal);
        }
    }

    /**
     * Every specified operation as {@code <method> <path>}, with a request valid for its role. The
     * factory lives here because Micronaut's extension would inject a {@code Supplier}.
     */
    enum Endpoint {
        API_STATUS("get " + PATH_API_STATUS, () -> HttpRequest.GET(PATH_API_STATUS)),
        REGISTER_USER("post " + PATH_REGISTER, () -> HttpRequest.POST(PATH_REGISTER, NO_BODY)),
        CURRENT_USER("get " + PATH_ME, () -> HttpRequest.GET(PATH_ME)),
        ERASE_USER("delete " + PATH_ME, () -> HttpRequest.DELETE(PATH_ME)),
        SEARCH_USERS(
                "post " + PATH_SEARCH,
                () -> HttpRequest.POST(PATH_SEARCH, Map.of("identifier", LINKED_USER))),
        LOOKUP_USER(
                "get " + PATH_USERS + "/{identifier}",
                () -> HttpRequest.GET(PATH_USERS + "/" + LINKED_USER)),
        REGISTER_PARTICIPANT_USER(
                "post " + PATH_PARTICIPANT_USERS,
                () -> HttpRequest.POST(PATH_PARTICIPANT_USERS, Map.of("identifier", LINKED_USER))),
        BULK_REGISTER_PARTICIPANT_USERS(
                "post " + PATH_BULK,
                () ->
                        HttpRequest.POST(
                                PATH_BULK,
                                Map.of("users", List.of(Map.of("identifier", LINKED_USER))))),
        UPDATE_LINK(
                "patch " + PATH_PARTICIPANT_USERS + "/{identifier}",
                () ->
                        HttpRequest.PATCH(
                                PATH_PARTICIPANT_USERS + "/" + LINKED_USER,
                                Map.of("localIdentifier", LOCAL_IDENTIFIER))),
        UNLINK(
                "delete " + PATH_PARTICIPANT_USERS + "/{identifier}",
                () -> HttpRequest.DELETE(PATH_PARTICIPANT_USERS + "/" + LINKED_USER)),
        LIST_PARTICIPANTS("get " + PATH_PARTICIPANTS, () -> HttpRequest.GET(PATH_PARTICIPANTS)),
        LOOKUP_PARTICIPANT(
                "get " + PATH_PARTICIPANTS + "/{identifier}",
                () -> HttpRequest.GET(PATH_PARTICIPANTS + "/" + CALLER_PARTICIPANT)),
        SELF_REGISTER_PARTICIPANT(
                SELF_REGISTRATION,
                () ->
                        HttpRequest.POST(
                                PATH_PARTICIPANTS, Map.of("legalName", PARTICIPANT_LEGAL_NAME))),
        CURRENT_PARTICIPANT(
                "get " + PATH_CURRENT_PARTICIPANT, () -> HttpRequest.GET(PATH_CURRENT_PARTICIPANT)),
        UPDATE_CURRENT_PARTICIPANT(
                "put " + PATH_CURRENT_PARTICIPANT,
                () ->
                        HttpRequest.PUT(
                                PATH_CURRENT_PARTICIPANT,
                                Map.of("legalName", PARTICIPANT_LEGAL_NAME))),
        DEREGISTER_CURRENT_PARTICIPANT(
                "delete " + PATH_CURRENT_PARTICIPANT,
                () -> HttpRequest.DELETE(PATH_CURRENT_PARTICIPANT)),
        GENERATE_PRIVACY_NOTICE(
                "post " + PATH_PRIVACY_NOTICES,
                () ->
                        HttpRequest.POST(
                                PATH_PRIVACY_NOTICES,
                                Map.of(
                                        "contractUri", UNSERVED_CONTRACT,
                                        "dataProvider", CALLER_PARTICIPANT,
                                        "dataConsumer", UNREGISTERED_PARTY))),
        GENERATE_PRIVACY_NOTICE_BY_CONTRACT(
                "post " + PATH_PRIVACY_NOTICES_BY_CONTRACT,
                () ->
                        HttpRequest.POST(
                                PATH_PRIVACY_NOTICES_BY_CONTRACT,
                                Map.of("contractUri", UNSERVED_CONTRACT))),
        RETRIEVE_PRIVACY_NOTICE(
                "get " + PATH_PRIVACY_NOTICES + "/{id}",
                () -> HttpRequest.GET(PATH_PRIVACY_NOTICES + "/" + ABSENT_NOTICE)),
        GRANT_CONSENT(
                "post " + PATH_CONSENTS,
                () ->
                        HttpRequest.POST(
                                PATH_CONSENTS,
                                Map.of(
                                        "privacyNoticeId", ABSENT_NOTICE,
                                        "decision", GRANTED_DECISION))),
        REQUEST_CONSENT(
                "post " + PATH_CONSENT_REQUESTS,
                () ->
                        HttpRequest.POST(
                                PATH_CONSENT_REQUESTS,
                                Map.of(
                                        "privacyNoticeId", ABSENT_NOTICE,
                                        "userIdentifier", LINKED_USER))),
        REVOKE_CONSENT(
                "post " + PATH_CONSENTS + "/{id}/revoke",
                () -> HttpRequest.POST(PATH_CONSENTS + "/" + ABSENT_CONSENT + "/revoke", NO_BODY)),
        REFUSE_CONSENT(
                "post " + PATH_CONSENTS + "/{id}/refuse",
                () -> HttpRequest.POST(PATH_CONSENTS + "/" + ABSENT_CONSENT + "/refuse", NO_BODY)),
        RE_CONFIRM_CONSENT(
                "post " + PATH_CONSENTS + "/{id}/re-confirm",
                () ->
                        HttpRequest.POST(
                                PATH_CONSENTS + "/" + ABSENT_CONSENT + "/re-confirm", NO_BODY)),
        TERMINATE_CONSENT(
                "post " + PATH_CONSENTS + "/{id}/terminate",
                () ->
                        HttpRequest.POST(
                                PATH_CONSENTS + "/" + ABSENT_CONSENT + "/terminate", NO_BODY)),
        RESUME_CONSENT(
                "post " + PATH_CONSENTS + "/{id}/resume",
                () ->
                        HttpRequest.POST(
                                PATH_CONSENTS + "/" + ABSENT_CONSENT + "/resume",
                                Map.of("userIdentifier", LINKED_USER))),
        LIST_MY_CONSENTS(
                "get " + PATH_CONSENTS + "/me", () -> HttpRequest.GET(PATH_CONSENTS + "/me")),
        GET_MY_CONSENT(
                "get " + PATH_CONSENTS + "/me/{id}",
                () -> HttpRequest.GET(PATH_CONSENTS + "/me/" + ABSENT_CONSENT)),
        LIST_CONSENTS("get " + PATH_CONSENTS, () -> HttpRequest.GET(PATH_CONSENTS)),
        GET_CONSENT(
                "get " + PATH_CONSENTS + "/{id}",
                () -> HttpRequest.GET(PATH_CONSENTS + "/" + ABSENT_CONSENT)),
        CHECK_CONSENT(
                "get " + PATH_CONSENTS + "/check",
                () ->
                        HttpRequest.GET(
                                PATH_CONSENTS
                                        + "/check?userIdentifier="
                                        + LINKED_USER
                                        + "&privacyNoticeId="
                                        + ABSENT_NOTICE)),
        LIST_MY_AVAILABLE_CONTRACTS(
                "get " + PATH_CONTRACTS_AVAILABLE, () -> HttpRequest.GET(PATH_CONTRACTS_AVAILABLE)),
        LIST_AVAILABLE_CONTRACTS(
                "get " + PATH_CONTRACTS_AVAILABLE + "/{role}",
                () -> HttpRequest.GET(PATH_CONTRACTS_AVAILABLE + "/" + PROVIDER_ROLE)),
        RECEIPT_SIGNING_KEYS("get " + PATH_JWKS, () -> HttpRequest.GET(PATH_JWKS));

        private final String specification;
        private final Supplier<MutableHttpRequest<?>> factory;

        Endpoint(String specification, Supplier<MutableHttpRequest<?>> factory) {
            this.specification = specification;
            this.factory = factory;
        }

        /** A fresh request, so no case mutates another's. */
        MutableHttpRequest<?> request() {
            return factory.get();
        }

        @Override
        public String toString() {
            return specification;
        }
    }

    /**
     * Ways a {@code PARTICIPANT} token could reach another participant's record: self-scoped writes
     * and directly addressed ones no route serves. Judged by the bystander's own state.
     */
    enum ForeignWrite {
        UPDATE_SELF(
                "put " + PATH_CURRENT_PARTICIPANT,
                true,
                () ->
                        HttpRequest.PUT(
                                PATH_CURRENT_PARTICIPANT,
                                Map.of("legalName", HIJACKED_LEGAL_NAME))),
        DEREGISTER_SELF(
                "delete " + PATH_CURRENT_PARTICIPANT,
                true,
                () -> HttpRequest.DELETE(PATH_CURRENT_PARTICIPANT)),
        UPDATE_OTHER(
                "put " + PATH_PARTICIPANTS + "/" + OTHER_PARTICIPANT,
                false,
                () ->
                        HttpRequest.PUT(
                                PATH_PARTICIPANTS + "/" + OTHER_PARTICIPANT,
                                Map.of("legalName", HIJACKED_LEGAL_NAME))),
        DEREGISTER_OTHER(
                "delete " + PATH_PARTICIPANTS + "/" + OTHER_PARTICIPANT,
                false,
                () -> HttpRequest.DELETE(PATH_PARTICIPANTS + "/" + OTHER_PARTICIPANT));

        private final String specification;
        private final boolean served;
        private final Supplier<MutableHttpRequest<?>> factory;

        ForeignWrite(
                String specification, boolean served, Supplier<MutableHttpRequest<?>> factory) {
            this.specification = specification;
            this.served = served;
            this.factory = factory;
        }

        /** Whether the specification publishes this request. */
        boolean served() {
            return served;
        }

        MutableHttpRequest<?> request() {
            return factory.get();
        }

        @Override
        public String toString() {
            return specification;
        }
    }

    /**
     * One row per operation, one column per caller. A trusted token holding no role is {@code 403},
     * not {@code 401}; {@code /api-status} and the JWKS are {@code 200} for everyone.
     */
    static Stream<Arguments> roleMatrix() {
        HttpStatus ok = HttpStatus.OK;
        HttpStatus created = HttpStatus.CREATED;
        HttpStatus perEntry = HttpStatus.MULTI_STATUS;
        HttpStatus emptied = HttpStatus.NO_CONTENT;
        HttpStatus alreadyRegistered = HttpStatus.CONFLICT;
        HttpStatus unknownParty = HttpStatus.NOT_FOUND;
        HttpStatus denied = HttpStatus.FORBIDDEN;
        HttpStatus challenged = HttpStatus.UNAUTHORIZED;
        HttpStatus upstreamDown = HttpStatus.BAD_GATEWAY;
        HttpStatus unknownNotice = HttpStatus.NOT_FOUND;
        HttpStatus unknownConsent = HttpStatus.NOT_FOUND;
        return Stream.of(
                        row(Endpoint.API_STATUS, ok, ok, ok, ok, ok),
                        row(Endpoint.REGISTER_USER, created, denied, denied, denied, challenged),
                        row(Endpoint.CURRENT_USER, ok, denied, denied, denied, challenged),
                        row(Endpoint.ERASE_USER, ok, denied, denied, denied, challenged),
                        row(Endpoint.SEARCH_USERS, denied, ok, ok, denied, challenged),
                        row(Endpoint.LOOKUP_USER, denied, ok, ok, denied, challenged),
                        row(
                                Endpoint.REGISTER_PARTICIPANT_USER,
                                denied,
                                ok,
                                denied,
                                denied,
                                challenged),
                        row(
                                Endpoint.BULK_REGISTER_PARTICIPANT_USERS,
                                denied,
                                perEntry,
                                denied,
                                denied,
                                challenged),
                        row(Endpoint.UPDATE_LINK, denied, ok, denied, denied, challenged),
                        row(Endpoint.UNLINK, denied, emptied, denied, denied, challenged),
                        // The directory is readable by every role of this service.
                        row(Endpoint.LIST_PARTICIPANTS, ok, ok, ok, denied, challenged),
                        row(Endpoint.LOOKUP_PARTICIPANT, ok, ok, ok, denied, challenged),
                        // The fixture pre-registers the caller, so the admitted caller hits 409.
                        row(
                                Endpoint.SELF_REGISTER_PARTICIPANT,
                                denied,
                                alreadyRegistered,
                                denied,
                                denied,
                                challenged),
                        row(Endpoint.CURRENT_PARTICIPANT, denied, ok, denied, denied, challenged),
                        row(
                                Endpoint.UPDATE_CURRENT_PARTICIPANT,
                                denied,
                                ok,
                                denied,
                                denied,
                                challenged),
                        // Nothing names the participant, so it is deleted outright; re-seeded.
                        row(
                                Endpoint.DEREGISTER_CURRENT_PARTICIPANT,
                                denied,
                                ok,
                                denied,
                                denied,
                                challenged),
                        // Admitted caller stops at the unregistered consumer (404).
                        row(
                                Endpoint.GENERATE_PRIVACY_NOTICE,
                                denied,
                                unknownParty,
                                denied,
                                denied,
                                challenged),
                        // Reaches the closed-port Contract Service, so 502 proves admission.
                        row(
                                Endpoint.GENERATE_PRIVACY_NOTICE_BY_CONTRACT,
                                denied,
                                upstreamDown,
                                denied,
                                denied,
                                challenged),
                        // Unscoped and open to all three roles; an absent id shows admission.
                        row(
                                Endpoint.RETRIEVE_PRIVACY_NOTICE,
                                unknownNotice,
                                unknownNotice,
                                unknownNotice,
                                denied,
                                challenged),
                        // A valid body passes validation and stops at the absent notice.
                        row(
                                Endpoint.GRANT_CONSENT,
                                unknownNotice,
                                denied,
                                denied,
                                denied,
                                challenged),
                        // Names a linked user, so it passes affiliation and stops at the notice.
                        row(
                                Endpoint.REQUEST_CONSENT,
                                denied,
                                unknownNotice,
                                denied,
                                denied,
                                challenged),
                        // Lifecycle routes admit the subject, who stops at the absent consent.
                        row(
                                Endpoint.REVOKE_CONSENT,
                                unknownConsent,
                                denied,
                                denied,
                                denied,
                                challenged),
                        row(
                                Endpoint.REFUSE_CONSENT,
                                unknownConsent,
                                denied,
                                denied,
                                denied,
                                challenged),
                        // Re-confirmation is the subject's act, authorised the same way.
                        row(
                                Endpoint.RE_CONFIRM_CONSENT,
                                unknownConsent,
                                denied,
                                denied,
                                denied,
                                challenged),
                        // Termination is the participant's counterpart, authorised the same way.
                        row(
                                Endpoint.TERMINATE_CONSENT,
                                denied,
                                unknownConsent,
                                denied,
                                denied,
                                challenged),
                        // Two roles reach resume; the subject must ignore the body's user.
                        row(
                                Endpoint.RESUME_CONSENT,
                                unknownConsent,
                                denied,
                                unknownConsent,
                                denied,
                                challenged),
                        // Subject-only query side: an empty page and a 404 for the absent id.
                        row(Endpoint.LIST_MY_CONSENTS, ok, denied, denied, denied, challenged),
                        row(
                                Endpoint.GET_MY_CONSENT,
                                unknownConsent,
                                denied,
                                denied,
                                denied,
                                challenged),
                        // Participant-only query side: an empty page and a 404 for the absent id.
                        row(Endpoint.LIST_CONSENTS, denied, ok, denied, denied, challenged),
                        row(
                                Endpoint.GET_CONSENT,
                                denied,
                                unknownConsent,
                                denied,
                                denied,
                                challenged),
                        // Participant-only; no grant held, so 200 with valid:false.
                        row(Endpoint.CHECK_CONSENT, denied, ok, denied, denied, challenged),
                        // No notices seeded, so an empty list without asking the Contract Service.
                        row(
                                Endpoint.LIST_MY_AVAILABLE_CONTRACTS,
                                ok,
                                denied,
                                denied,
                                denied,
                                challenged),
                        row(
                                Endpoint.LIST_AVAILABLE_CONTRACTS,
                                denied,
                                ok,
                                denied,
                                denied,
                                challenged),
                        row(Endpoint.RECEIPT_SIGNING_KEYS, ok, ok, ok, ok, ok))
                .flatMap(row -> row);
    }

    /** Expands one operation's row into one case per caller. */
    private static Stream<Arguments> row(
            Endpoint endpoint,
            HttpStatus user,
            HttpStatus participant,
            HttpStatus catalog,
            HttpStatus outsider,
            HttpStatus anonymous) {
        return Stream.of(
                Arguments.of(endpoint, Caller.USER, user),
                Arguments.of(endpoint, Caller.PARTICIPANT, participant),
                Arguments.of(endpoint, Caller.CATALOG, catalog),
                Arguments.of(endpoint, Caller.OUTSIDER, outsider),
                Arguments.of(endpoint, Caller.ANONYMOUS, anonymous));
    }

    /** Re-establishes the fixture per case, since several of the routes under test consume it. */
    @BeforeEach
    void seedTheFixture() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        userIdentifier = KeycloakTestResource.subject(Caller.USER.token());
        removeCommittedRows();
        Participant caller =
                participants
                        .findByIdentifier(CALLER_PARTICIPANT)
                        .orElseGet(
                                () ->
                                        participants.save(
                                                new Participant(
                                                        CALLER_PARTICIPANT,
                                                        PARTICIPANT_LEGAL_NAME,
                                                        null,
                                                        null,
                                                        Map.of(),
                                                        null)));
        User linked = users.save(new User(LINKED_USER, null, null, null));
        links.save(new UserParticipant(linked.getId(), caller.getId(), LOCAL_IDENTIFIER));
        participants
                .findByIdentifier(OTHER_PARTICIPANT)
                .orElseGet(
                        () ->
                                participants.save(
                                        new Participant(
                                                OTHER_PARTICIPANT,
                                                OTHER_LEGAL_NAME,
                                                null,
                                                null,
                                                Map.of(),
                                                null)));
    }

    /** Rows committed by these tests outlive the transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        Stream.of(LINKED_USER, userIdentifier)
                .forEach(
                        identifier ->
                                users.findByIdentifier(identifier)
                                        .ifPresent(
                                                user -> {
                                                    links.findByIdUserId(user.getId())
                                                            .forEach(links::delete);
                                                    users.delete(user);
                                                }));
        Stream.of(CALLER_PARTICIPANT, OTHER_PARTICIPANT)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @ParameterizedTest(name = "{1} on {0} is {2}")
    @MethodSource("roleMatrix")
    @DisplayName("each caller reaches exactly the published routes it is entitled to")
    void roleMatrixHolds(Endpoint endpoint, Caller caller, HttpStatus expected) {
        HttpResponse<String> response = exchange(endpoint.request(), caller.token());

        assertThat(response.code()).isEqualTo(expected.getCode());
        if (expected == HttpStatus.FORBIDDEN || expected == HttpStatus.UNAUTHORIZED) {
            assertThat(response.getContentType())
                    .as("a refusal is a problem detail, whatever the route would have returned")
                    .hasValueSatisfying(
                            type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
        }
    }

    /**
     * Routes a {@code PARTICIPANT} may reach only once registered: those admitting the role, less
     * self-registration and those also admitting {@code USER}. Derived from the spec, not listed.
     */
    static Stream<Arguments> participantScopedEndpoints() {
        Map<String, Endpoint> rows =
                Stream.of(Endpoint.values())
                        .collect(Collectors.toMap(Endpoint::toString, endpoint -> endpoint));
        List<Arguments> cases =
                SpecSecurityConsistencyTest.specOperations()
                        .filter(operation -> operation.roles().contains(Role.PARTICIPANT.name()))
                        .filter(operation -> !operation.roles().contains(Role.USER.name()))
                        .map(operation -> operation.httpMethod() + " " + operation.path())
                        .filter(specification -> !SELF_REGISTRATION.equals(specification))
                        .map(specification -> Arguments.of(rowFor(rows, specification)))
                        .toList();

        assertThat(cases)
                .as("the specification admits PARTICIPANT somewhere, or this case tests nothing")
                .isNotEmpty();
        return cases.stream();
    }

    /** The matrix row for a specified operation. */
    private static Endpoint rowFor(Map<String, Endpoint> rows, String specification) {
        Endpoint endpoint = rows.get(specification);
        assertThat(endpoint).as("no matrix row for %s", specification).isNotNull();
        return endpoint;
    }

    /** Asserts the status, so answering an unregistered caller with an empty page also fails. */
    @ParameterizedTest(name = "{0} refuses an unregistered participant")
    @MethodSource("participantScopedEndpoints")
    @DisplayName("an unregistered participant is refused by every route but its own registration")
    void unregisteredParticipantIsRefused(Endpoint endpoint) {
        deregisterTheCallerParticipant();

        HttpResponse<String> response = exchange(endpoint.request(), Caller.PARTICIPANT.token());

        assertThat(response.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
    }

    /** Drops the caller's {@code participants} row, leaving its token valid but unregistered. */
    private void deregisterTheCallerParticipant() {
        users.findByIdentifier(LINKED_USER)
                .ifPresent(user -> links.findByIdUserId(user.getId()).forEach(links::delete));
        participants.findByIdentifier(CALLER_PARTICIPANT).ifPresent(participants::delete);
    }

    @Test
    @DisplayName("every operation the specification declares has a row in the matrix")
    void everySpecifiedOperationIsCovered() {
        List<String> specified =
                SpecSecurityConsistencyTest.specOperations()
                        .map(operation -> operation.httpMethod() + " " + operation.path())
                        .toList();

        assertThat(Stream.of(Endpoint.values()).map(Endpoint::toString).toList())
                .as(
                        "an operation with no row here has no end-to-end authorization assertion at"
                                + " all, which is the failure this suite exists to make impossible")
                .containsExactlyInAnyOrderElementsOf(specified);
    }

    /**
     * No write templates a participant segment or takes a non-path parameter; the body half is in
     * {@link ParticipantSchemaParityTest#neitherAcceptsAnIdentifier()}.
     */
    @Test
    @DisplayName("no route that changes a record lets the caller name which record")
    void noWriteRouteLetsTheCallerNameTheRecord() {
        List<SpecSecurityConsistencyTest.SpecOperation> writes =
                SpecSecurityConsistencyTest.specOperations()
                        .filter(operation -> MUTATING_METHODS.contains(operation.httpMethod()))
                        .toList();

        assertThat(writes)
                .as("the specification publishes writes, or this case tests nothing")
                .isNotEmpty();
        assertThat(writes)
                .as(
                        "a write addressing a participant positionally would let a token act on a"
                                + " record it does not hold")
                .noneMatch(EndpointRoleMatrixIT::addressesAParticipant);
        writes.forEach(
                write -> {
                    assertThat(write.parameterNames())
                            .as(
                                    "%s declares a parameter that is not one of its own path"
                                            + " segments, which could redirect the write away from"
                                            + " the token",
                                    write)
                            .allMatch(name -> write.path().contains(template(name)));
                    assertThat(write.parameterNames())
                            .as(
                                    "%s templates a path segment it declares no parameter for, so"
                                            + " the parameter list read off the specification is"
                                            + " not the one it carries and the check above matched"
                                            + " over less than it claims to",
                                    write)
                            .containsAll(templatedNamesOf(write.path()));
                });
    }

    @ParameterizedTest(name = "{0} leaves another participant untouched")
    @EnumSource(ForeignWrite.class)
    @DisplayName("a participant token never reaches a record other than its own")
    void aParticipantTokenNeverReachesAnotherRecord(ForeignWrite attempt) {
        HttpResponse<String> response = exchange(attempt.request(), Caller.PARTICIPANT.token());

        if (!attempt.served()) {
            assertThat(response.code())
                    .as("%s is not a published route and must not behave like one", attempt)
                    .isGreaterThan(HIGHEST_SUCCESS_CODE);
        }
        Participant bystander =
                participants
                        .findByIdentifier(OTHER_PARTICIPANT)
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                attempt + " removed a record it does not hold"));
        assertThat(bystander.getLegalName()).isEqualTo(OTHER_LEGAL_NAME);
        assertThat(bystander.getDeregisteredAt())
                .as("%s deregistered a record it does not hold", attempt)
                .isNull();
    }

    /** Whether a path names a participant: a template directly under the collection. */
    private static boolean addressesAParticipant(SpecSecurityConsistencyTest.SpecOperation write) {
        List<String> segments = List.of(write.path().split("/"));
        int collection = segments.indexOf(PARTICIPANTS_SEGMENT);
        return collection >= 0
                && collection + 1 < segments.size()
                && segments.get(collection + 1).startsWith(PATH_TEMPLATE_MARKER);
    }

    /** The parameter names a path templates, in path order. */
    private static List<String> templatedNamesOf(String path) {
        return Stream.of(path.split("/"))
                .filter(
                        segment ->
                                segment.startsWith(PATH_TEMPLATE_MARKER)
                                        && segment.endsWith(PATH_TEMPLATE_END))
                .map(
                        segment ->
                                segment.substring(
                                        PATH_TEMPLATE_MARKER.length(),
                                        segment.length() - PATH_TEMPLATE_END.length()))
                .toList();
    }

    private static String template(String parameterName) {
        return PATH_TEMPLATE_MARKER + parameterName + PATH_TEMPLATE_END;
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
