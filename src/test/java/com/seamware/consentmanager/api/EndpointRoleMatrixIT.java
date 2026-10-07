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
                () -> HttpRequest.DELETE(PATH_CURRENT_PARTICIPANT));

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
     * lie. {@code /api-status} stays {@code 200} throughout, including for a caller that names
     * nobody, because a reachability check must not depend on a credential.
     */
    static Stream<Arguments> roleMatrix() {
        HttpStatus ok = HttpStatus.OK;
        HttpStatus created = HttpStatus.CREATED;
        HttpStatus perEntry = HttpStatus.MULTI_STATUS;
        HttpStatus emptied = HttpStatus.NO_CONTENT;
        HttpStatus alreadyRegistered = HttpStatus.CONFLICT;
        HttpStatus denied = HttpStatus.FORBIDDEN;
        HttpStatus challenged = HttpStatus.UNAUTHORIZED;
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
                                challenged))
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
