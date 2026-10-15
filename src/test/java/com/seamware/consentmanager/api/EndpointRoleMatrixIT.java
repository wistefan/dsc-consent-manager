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
 * The authorization matrix over the operations this service actually publishes: for every specified
 * route, the status each kind of caller receives, with tokens issued by a real Keycloak.
 *
 * <p>{@code KeycloakRoleMatrixIT} proves the same thing about synthetic probe routes, which answers
 * whether the mechanism works but not whether it was wired onto the published routes. This suite is
 * the one that fails when an operation is left on {@code IS_AUTHENTICATED}, served to the wrong
 * role, or reachable without a token, and {@link #everySpecifiedOperationIsCovered()} makes
 * forgetting to add a route here a failure rather than a silence.
 *
 * <p>The cell for the role a route serves carries that route's own success status rather than a
 * blanket "not refused": a route reached by the right caller has to answer what it specifies, and
 * the fixture is reseeded per case so the status is the same whichever case runs first.
 */
@MicronautTest(transactional = false)
@DisplayName("Role matrix over the published endpoints")
class EndpointRoleMatrixIT extends KeycloakAndPostgresTestResource {

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String PARTICIPANT_LEGAL_NAME = "Role Matrix Participant Ltd";

    /**
     * A second participant, present only so that every case has a record it is entitled to none of.
     */
    private static final String OTHER_PARTICIPANT = "urn:test:participant:role-matrix-bystander";

    private static final String OTHER_LEGAL_NAME = "Role Matrix Bystander Ltd";

    /**
     * User seeded and linked to the caller participant, so every read route has something to
     * answer.
     */
    private static final String LINKED_USER = "urn:test:user:role-matrix";

    /**
     * The caller participant's own label for its link, present so the {@code PATCH} updates one.
     */
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

    /**
     * A notice id no case stores, so every admitted caller stops at the same {@code 404}.
     *
     * <p>Retrieval is deliberately unscoped, so an absent notice is the only way to tell an
     * admitted caller from a refused one by status alone.
     */
    private static final String ABSENT_NOTICE = "0199a4f2-0000-7000-8000-000000000000";

    /**
     * A consent id no case stores. Every lifecycle route answers an unknown consent and one
     * belonging to somebody else with the same {@code 404}, so this is what an admitted caller
     * stops at.
     */
    private static final String ABSENT_CONSENT = "0199a4f2-0001-7000-8000-000000000000";

    /**
     * A contract identifier no case serves. Generation resolves its two named participants before
     * it fetches anything, so paired with {@link #UNREGISTERED_PARTY} it never leaves this process.
     */
    private static final String UNSERVED_CONTRACT =
            "https://contracts.example.org/contracts/absent";

    /**
     * An identifier this suite never registers, which is what keeps the generation row
     * deterministic here: the route stops at the participant directory rather than calling a
     * Contract Service this suite does not stand up.
     */
    private static final String UNREGISTERED_PARTY = "urn:test:participant:role-matrix-unknown";

    /**
     * The one {@code PARTICIPANT} operation an unregistered identifier may reach, named as {@link
     * Endpoint#toString()} names it. It is what creates the row every other route demands, so it is
     * excluded from {@link #participantScopedEndpoints()}.
     */
    private static final String SELF_REGISTRATION = "post " + PATH_PARTICIPANTS;

    /** HTTP methods that change a record, spelled as the specification spells them. */
    private static final Set<String> MUTATING_METHODS = Set.of("post", "put", "patch", "delete");

    /**
     * Marks a path segment the caller fills in, which is what a self-scoped route must not have.
     */
    private static final String PATH_TEMPLATE_MARKER = "{";

    /**
     * Closes a path template, so a parameter can be matched against the segment that declares it.
     */
    private static final String PATH_TEMPLATE_END = "}";

    /** Collection segment a participant-identifying template would directly follow. */
    private static final String PARTICIPANTS_SEGMENT = "participants";

    /** Legal name a foreign write would leave behind, so a write that lands is unmistakable. */
    private static final String HIJACKED_LEGAL_NAME = "Hijacked Ltd";

    /** Last status code that still reports success; a route that serves nothing stays above it. */
    private static final int HIGHEST_SUCCESS_CODE = 299;

    /** Body of a request to an operation that specifies none; the route ignores it. */
    private static final String NO_BODY = "";

    /**
     * Contract Service URL for a suite that stands none up: a port in the reserved range, where a
     * connection is refused rather than left to time out.
     */
    private static final String UNREACHABLE_CONTRACT_SERVICE = "http://localhost:1";

    /**
     * Retries after a failed fetch, counted after the initial attempt, so this is two upstream
     * requests and not one: a refused connection is not going to answer differently on a second
     * try, and the default budget would spend seconds finding that out. One is the floor -
     * {@code @Retryable} refuses a budget below it - so a single retry is as close to none as this
     * suite can configure.
     */
    private static final String SINGLE_RETRY = "1";

    /** Pause before the one retry, which this suite has no reason to wait out. */
    private static final String RETRY_DELAY = "10ms";

    /** Media type every refusal carries, per RFC 7807. */
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

    /**
     * The {@code USER} caller's global identifier, resolved once because reading it costs a token.
     */
    private String userIdentifier;

    /**
     * Points the Contract Service at a port nothing listens on, with the smallest retry budget
     * {@code @Retryable} accepts.
     *
     * <p>The by-contract row needs an outcome that is upstream's rather than this service's, and
     * needs it in milliseconds: a refused connection is the fastest unambiguous one, and the
     * default retry budget would spend seconds on it.
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
     * The callers in the matrix: one per role the realm grants, plus the two the service refuses.
     *
     * <p>{@code OUTSIDER} holds a realm role the trust list maps to nothing, which is the
     * authenticated-but-unauthorized case; {@code ANONYMOUS} sends no token at all.
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
     * Every operation of the specification, named as the specification names it and paired with a
     * request that is valid for the role the operation serves.
     *
     * <p>The name is {@code <method> <path>} with the path template intact, which is what {@link
     * #everySpecifiedOperationIsCovered()} matches against the specification. A parameterized test
     * may not take the request factory directly - Micronaut's JUnit extension claims a {@code
     * Supplier} parameter as a bean to inject - so the factory travels inside this enum.
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
     * The ways a {@code PARTICIPANT} token could reach a record that is not its own: the
     * self-scoped writes, which take their target from the token, and the directly addressed ones,
     * for which the specification publishes no route at all.
     *
     * <p>Both are run as the caller participant against a seeded bystander, because the two ways
     * fail differently - the self-scoped write succeeds but on the wrong row, the addressed one is
     * refused by routing - and only the bystander's own state tells them apart.
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

        /** Whether the specification publishes this request, which decides what status it earns. */
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
     * The matrix itself: one row per operation, one column per caller.
     *
     * <p>A caller holding no role of this service is {@code 403} rather than {@code 401}: its
     * signature was good and its issuer is trusted, so asking it to authenticate again would be a
     * lie. {@code /api-status} and the receipt JWKS stay {@code 200} throughout, including for a
     * caller that names nobody: neither a reachability check nor verifying a receipt may depend on
     * a credential.
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
                        // The directory is readable by every role of this service: discovering who
                        // may be transacted with is not a privilege of any one of them.
                        row(Endpoint.LIST_PARTICIPANTS, ok, ok, ok, denied, challenged),
                        row(Endpoint.LOOKUP_PARTICIPANT, ok, ok, ok, denied, challenged),
                        // The fixture registers the caller before every case, so the one caller
                        // this route admits lands on the conflict rather than on a second
                        // registration. That it gets there at all is what the matrix asserts.
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
                        // The fixture's participant has neither a consent nor a notice naming it,
                        // so the caller leaves on the branch that deletes the row outright; the
                        // next case re-seeds it.
                        row(
                                Endpoint.DEREGISTER_CURRENT_PARTICIPANT,
                                denied,
                                ok,
                                denied,
                                denied,
                                challenged),
                        // Generation needs a Contract Service, which this suite does not stand up,
                        // so the one caller this route admits is sent at an unregistered consumer
                        // and stops at the directory. That it gets past authorization to reach that
                        // refusal is what the matrix asserts; PrivacyNoticeGenerationIT owns the
                        // rest of the flow.
                        row(
                                Endpoint.GENERATE_PRIVACY_NOTICE,
                                denied,
                                unknownParty,
                                denied,
                                denied,
                                challenged),
                        // This form reads the parties off the contract, so it cannot stop at the
                        // directory the way the row above does: it reaches the Contract Service
                        // this suite deliberately points at a closed port, and the 502 that comes
                        // back is precisely the evidence that authorization let the caller
                        // through. PrivacyNoticeByContractIT owns the rest of the flow.
                        row(
                                Endpoint.GENERATE_PRIVACY_NOTICE_BY_CONTRACT,
                                denied,
                                upstreamDown,
                                denied,
                                denied,
                                challenged),
                        // Retrieval is the one route open to all three roles, and it is not scoped
                        // to the notice's parties, so an id nothing stored is what tells an
                        // admitted caller from a refused one.
                        row(
                                Endpoint.RETRIEVE_PRIVACY_NOTICE,
                                unknownNotice,
                                unknownNotice,
                                unknownNotice,
                                denied,
                                challenged),
                        // The body is complete, so the one caller this route admits gets past bean
                        // validation and stops where the fixture leaves it: at a notice nothing
                        // stored. An incomplete body would answer 400 for every caller and the
                        // sweep would stop meaning anything. ConsentGrantIT owns the success path.
                        row(
                                Endpoint.GRANT_CONSENT,
                                unknownNotice,
                                denied,
                                denied,
                                denied,
                                challenged),
                        // Same shape as the row above, mirrored: the body names a user the fixture
                        // links to the caller, so the one caller this route admits gets past
                        // validation and the affiliation check and stops at the same absent
                        // notice. ConsentRequestSurfaceIT owns the 201.
                        row(
                                Endpoint.REQUEST_CONSENT,
                                denied,
                                unknownNotice,
                                denied,
                                denied,
                                challenged),
                        // The lifecycle routes authorise against the consent, so the subject the
                        // fixture seeds is admitted and stops at an id nothing stored - the same
                        // 404 a consent owned by somebody else answers. ConsentLifecycleSurfaceIT
                        // owns the moves that land.
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
                        // Re-confirmation moves nothing, but it is the subject's act like the
                        // other two, so it authorises the same way and stops at the same absent id.
                        row(
                                Endpoint.RE_CONFIRM_CONSENT,
                                unknownConsent,
                                denied,
                                denied,
                                denied,
                                challenged),
                        // Termination is the participant's counterpart, authorised against the
                        // consent in the same way, so the registered caller the fixture seeds is
                        // admitted and stops at the same absent id.
                        row(
                                Endpoint.TERMINATE_CONSENT,
                                denied,
                                unknownConsent,
                                denied,
                                denied,
                                challenged),
                        // Resuming is the one lifecycle route two roles reach, and both authorise
                        // against the consent, so both stop at the same absent id. The body names
                        // a user because a catalog must; the subject's own call has to ignore it
                        // rather than refuse the request for carrying it.
                        row(
                                Endpoint.RESUME_CONSENT,
                                unknownConsent,
                                denied,
                                unknownConsent,
                                denied,
                                challenged),
                        // The query side is the subject's alone: the seeded USER has no consent
                        // yet, so the listing answers an empty page and the id lookup an absent
                        // consent - the same 404 a consent owned by somebody else earns. Every
                        // other role is refused outright. ConsentQueryIT owns the populated page.
                        row(Endpoint.LIST_MY_CONSENTS, ok, denied, denied, denied, challenged),
                        row(
                                Endpoint.GET_MY_CONSENT,
                                unknownConsent,
                                denied,
                                denied,
                                denied,
                                challenged),
                        // The participant query side is the participant's alone: the seeded caller
                        // is party to no consent yet, so the listing answers an empty page and the
                        // id lookup an absent consent - the same 404 a consent it is no party to
                        // earns. Every other role is refused outright. ConsentParticipantQueryIT
                        // owns the populated page and the scoping.
                        row(Endpoint.LIST_CONSENTS, denied, ok, denied, denied, challenged),
                        row(
                                Endpoint.GET_CONSENT,
                                denied,
                                unknownConsent,
                                denied,
                                denied,
                                challenged),
                        // The validity check is the participant's alone. The seeded subject exists
                        // but holds no grant the caller is party to, so the one caller this route
                        // admits is answered 200 with valid:false - the same shape an unknown
                        // subject earns - while every other role is refused. ConsentValidityIT owns
                        // the true answer and the no-disclosure proof.
                        row(Endpoint.CHECK_CONSENT, denied, ok, denied, denied, challenged),
                        // Contract discovery reads its candidates from the notices held here, and
                        // the fixture seeds none for either caller, so the one role each route
                        // admits is answered an empty list without the Contract Service being
                        // asked. ContractDiscoveryIT owns the populated list and the 502.
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
     * The published routes a {@code PARTICIPANT} may only reach once its identifier is registered:
     * every operation admitting the role, less the registration that creates the row and less the
     * operations that also admit {@code USER}.
     *
     * <p>A route serving {@code USER} cannot demand a participant row, because a user never has
     * one; demanding it would make the route's contract depend on who is calling. The participant
     * directory is that kind of route, and it is excluded by the rule rather than by name.
     *
     * <p>Derived from the specification rather than listed, for the same reason {@link
     * #everySpecifiedOperationIsCovered()} exists - a participant route added later and forgotten
     * here would silently skip the very check this case makes.
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

    /**
     * The matrix row for a specified operation, which {@code everySpecifiedOperationIsCovered}
     * keeps total.
     */
    private static Endpoint rowFor(Map<String, Endpoint> rows, String specification) {
        Endpoint endpoint = rows.get(specification);
        assertThat(endpoint).as("no matrix row for %s", specification).isNotNull();
        return endpoint;
    }

    /**
     * An unregistered identifier now resolves a principal instead of being refused by the filter,
     * so each of these routes has to do the refusing itself.
     *
     * <p>The assertion is on the status rather than on an empty result, so a later change that
     * answers an unregistered participant with an empty page - which would still be the scope
     * widening this guards against, merely with nothing to disclose yet - fails here.
     */
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
     * AC 9 read off the specification: a participant may modify its own record and no other.
     *
     * <p>The guarantee is positional rather than procedural - no published write ever puts a
     * participant in a slot the caller fills in, so there is no handler that could compare an
     * addressed participant against the token and no comparison that could be forgotten. Two things
     * make that true and both are asserted: no write templates the segment that names a
     * participant, and no write declares a parameter that is not one of its own path segments,
     * which rules out a query parameter steering it. This case inspects parameters only; the body
     * half of the guarantee - that neither write body carries an {@code identifier} property - is
     * pinned by {@link ParticipantSchemaParityTest#neitherAcceptsAnIdentifier()}, which matters
     * because {@code POST /users/search} is a write steered entirely by its body.
     *
     * <p>The converse is asserted too: every templated segment resolves back to a declared
     * parameter. A parameter list the specification parser cannot see would otherwise leave the
     * check above matching over nothing and passing in silence rather than failing.
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

    /**
     * A participant token sent at another participant's record, both ways round: the self-scoped
     * write, which resolves to the caller, and the addressed one, which no route serves.
     */
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

    /**
     * Whether a path names a participant positionally: a template directly under the collection.
     */
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

    /** The path segment that would supply a parameter of the given name. */
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
