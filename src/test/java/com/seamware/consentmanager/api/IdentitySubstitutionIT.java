package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.SpecSecurityConsistencyTest.SpecOperation;
import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.client.SelfDescriptionClient;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.ConsentFixtures;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.ContractServiceStub;
import com.seamware.consentmanager.support.DatabaseReset;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import com.seamware.consentmanager.support.RawSql;
import io.micronaut.cache.CacheManager;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Identity substitution over every secured operation: a foreign user or participant named in the
 * query string or as an extra body member must be refused, or change nothing — the answer and the
 * stored rows equal those of the same request without it, so the outcome is the token's alone.
 */
@MicronautTest(transactional = false)
@DisplayName("Identity substitution over the secured operations")
class IdentitySubstitutionIT extends KeycloakAndPostgresTestResource {

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** The participant a request tries to act as. */
    private static final String FOREIGN_PARTICIPANT = "urn:test:participant:substitution-foreign";

    /** The other side of every seeded notice, never a caller; the served contract's consumer. */
    private static final String COUNTERPARTY = "did:web:consumer.example.org";

    /** The served contract's provider, which the caller stands in for so it is a party. */
    private static final String FIXTURE_PROVIDER = "did:web:provider.example.org";

    /** A user the caller participant registers, so the registration routes create a link. */
    private static final String NEW_USER = "urn:test:user:substitution-new";

    private static final String LEGAL_NAME = "Identity Substitution Ltd";

    private static final String LOCAL_IDENTIFIER = "substitution-relabelled";

    private static final String CONTRACT_URI_PREFIX = "https://contract.example.org/v1/contracts/";

    /** The contract every seeded notice is held under. */
    private static final String SEEDED_CONTRACT = "substitution-seeded";

    /** A contract with no notice yet, so a generation creates one. */
    private static final String GENERATED_CONTRACT = "substitution-generated";

    private static final String CONTRACT_URI = CONTRACT_URI_PREFIX + SEEDED_CONTRACT;

    private static final String GENERATED_CONTRACT_URI = CONTRACT_URI_PREFIX + GENERATED_CONTRACT;

    private static final String NOTICE_TITLE = "Privacy notice identity substitution addresses";

    private static final String GRANTED_DECISION = "GRANTED";

    private static final String PROVIDER_ROLE = "provider";

    private static final String SINGLE_RETRY = "1";

    private static final String RETRY_DELAY = "10ms";

    private static final Set<String> METHODS_WITH_BODY = Set.of("post", "put", "patch");

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    /** Every stored fact that names a principal, by natural key so a reseed compares equal. */
    private static final String STORED_STATE =
            """
            SELECT 'user ' || identifier FROM users
            UNION ALL SELECT 'participant ' || identifier || ' ' || legal_name
                || ' active=' || (deregistered_at IS NULL) FROM participants
            UNION ALL SELECT 'link ' || u.identifier || ' ' || p.identifier || ' '
                || coalesce(l.local_identifier, '-')
              FROM user_participants l
              JOIN users u ON u.id = l.user_id JOIN participants p ON p.id = l.participant_id
            UNION ALL SELECT 'notice ' || n.contract_uri || ' ' || p.identifier
                || ' active=' || (n.archived_at IS NULL)
              FROM privacy_notices n JOIN participants p ON p.id = n.provider_id
            UNION ALL SELECT 'consent ' || u.identifier || ' ' || p.identifier || ' ' || c.status
              FROM consents c
              JOIN users u ON u.id = c.user_id JOIN participants p ON p.id = c.provider_id
            UNION ALL SELECT 'event ' || u.identifier || ' ' || e.event_state
                || ' ' || coalesce(e.actor, '-')
              FROM consent_events e
              JOIN consents c ON c.id = e.consent_id JOIN users u ON u.id = c.user_id
            ORDER BY 1
            """;

    /** Erasure's tombstone embeds the generated row id, which no two seeds share. */
    private static final Pattern ERASED_IDENTIFIER =
            Pattern.compile("urn:consent-manager:erased:[0-9a-f-]+");

    private static final String ERASED_MASK = "urn:consent-manager:erased:<id>";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject UserParticipantRepository links;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    @Inject CacheManager<?> caches;

    /** Serves both contracts, so the generation and discovery rows reach their success path. */
    private final ContractServiceStub contracts = new ContractServiceStub();

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put("consent-manager.contract-service.url", contracts.url());
        properties.put("consent-manager.contract-service.retry-attempts", SINGLE_RETRY);
        properties.put("consent-manager.contract-service.retry-delay", RETRY_DELAY);
        return properties;
    }

    /** The user a request tries to act for; created once per JVM, as realm users persist. */
    private static final class Foreign {
        static final Supplier<String> USER =
                KeycloakTestResource.createPrincipal(RealmPrincipal.USER, null);
    }

    /** Where the foreign identifier is placed. */
    enum Location {
        QUERY,
        BODY
    }

    /** The identity-bearing names a client might hope a route honours. */
    enum Injected {
        USER_ID("userId"),
        USER_IDENTIFIER("userIdentifier"),
        SUBJECT("subject"),
        PARTICIPANT_ID("participantId"),
        IDENTIFIER("identifier");

        private final String member;

        Injected(String member) {
            this.member = member;
        }

        /** The foreign value; a bare {@code identifier} names whatever kind the caller is. */
        String value(Seeded seeded, RealmPrincipal caller) {
            return switch (this) {
                case USER_ID -> seeded.foreignUserId();
                case USER_IDENTIFIER, SUBJECT -> seeded.foreignUser();
                case PARTICIPANT_ID -> FOREIGN_PARTICIPANT;
                case IDENTIFIER ->
                        caller == RealmPrincipal.PARTICIPANT
                                ? FOREIGN_PARTICIPANT
                                : seeded.foreignUser();
            };
        }
    }

    /** The values a base request addresses: the caller's own records. */
    record Seeded(
            String callerUser,
            String noticeId,
            String consentId,
            String foreignUser,
            String foreignUserId) {

        /** Strings that name the foreign principals, which no substituted answer may gain. */
        List<String> foreignValues() {
            return List.of(foreignUser, foreignUserId, FOREIGN_PARTICIPANT);
        }
    }

    /** A request as parts, so the foreign identifier can be added to either. */
    record Call(HttpMethod method, String path, Map<String, Object> body) {

        static Call of(HttpMethod method, String path) {
            return new Call(method, path, Map.of());
        }

        MutableHttpRequest<?> build(Map<String, String> query, Map<String, Object> extraBody) {
            UriBuilder uri = UriBuilder.of(path);
            query.forEach(uri::queryParam);
            Map<String, Object> merged = new LinkedHashMap<>(body);
            merged.putAll(extraBody);
            MutableHttpRequest<Object> request = HttpRequest.create(method, uri.toString());
            return METHODS_WITH_BODY.contains(method.name().toLowerCase(Locale.ROOT))
                    ? request.body(merged)
                    : request;
        }
    }

    /**
     * A valid request per secured operation, aimed at the caller's own records, with the status it
     * must answer unsubstituted, the status its consent is seeded in ({@code null} when none is)
     * and whether the caller is registered.
     */
    enum Endpoint {
        REGISTER_USER(
                "post /users/register",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.POST, "/users/register")),
        CURRENT_USER("get /users/me", HttpStatus.OK, s -> Call.of(HttpMethod.GET, "/users/me")),
        ERASE_USER("delete /users/me", HttpStatus.OK, s -> Call.of(HttpMethod.DELETE, "/users/me")),
        SEARCH_USERS(
                "post /users/search",
                HttpStatus.OK,
                s ->
                        new Call(
                                HttpMethod.POST,
                                "/users/search",
                                Map.of("identifier", s.callerUser()))),
        LOOKUP_USER(
                "get /users/{identifier}",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.GET, "/users/" + s.callerUser())),
        LIST_PARTICIPANTS(
                "get /participants", HttpStatus.OK, s -> Call.of(HttpMethod.GET, "/participants")),
        SELF_REGISTER_PARTICIPANT(
                "post /participants",
                HttpStatus.CREATED,
                null,
                false,
                s -> new Call(HttpMethod.POST, "/participants", Map.of("legalName", LEGAL_NAME))),
        CURRENT_PARTICIPANT(
                "get /participants/me",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.GET, "/participants/me")),
        UPDATE_CURRENT_PARTICIPANT(
                "put /participants/me",
                HttpStatus.OK,
                s -> new Call(HttpMethod.PUT, "/participants/me", Map.of("legalName", LEGAL_NAME))),
        DEREGISTER_CURRENT_PARTICIPANT(
                "delete /participants/me",
                HttpStatus.OK,
                ConsentStatus.REVOKED,
                true,
                s -> Call.of(HttpMethod.DELETE, "/participants/me")),
        BULK_REGISTER_PARTICIPANT_USERS(
                "post /participants/me/users/bulk",
                HttpStatus.MULTI_STATUS,
                s ->
                        new Call(
                                HttpMethod.POST,
                                "/participants/me/users/bulk",
                                Map.of("users", List.of(Map.of("identifier", NEW_USER))))),
        REGISTER_PARTICIPANT_USER(
                "post /participants/me/users",
                HttpStatus.CREATED,
                s ->
                        new Call(
                                HttpMethod.POST,
                                "/participants/me/users",
                                Map.of("identifier", NEW_USER))),
        UPDATE_LINK(
                "patch /participants/me/users/{identifier}",
                HttpStatus.OK,
                s ->
                        new Call(
                                HttpMethod.PATCH,
                                "/participants/me/users/" + s.callerUser(),
                                Map.of("localIdentifier", LOCAL_IDENTIFIER))),
        UNLINK(
                "delete /participants/me/users/{identifier}",
                HttpStatus.NO_CONTENT,
                ConsentStatus.REVOKED,
                true,
                s -> Call.of(HttpMethod.DELETE, "/participants/me/users/" + s.callerUser())),
        LOOKUP_PARTICIPANT(
                "get /participants/{identifier}",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.GET, "/participants/" + CALLER_PARTICIPANT)),
        GENERATE_PRIVACY_NOTICE(
                "post /privacy-notices",
                HttpStatus.CREATED,
                s ->
                        new Call(
                                HttpMethod.POST,
                                "/privacy-notices",
                                Map.of(
                                        "contractUri", GENERATED_CONTRACT_URI,
                                        "dataProvider", CALLER_PARTICIPANT,
                                        "dataConsumer", COUNTERPARTY))),
        GENERATE_PRIVACY_NOTICE_BY_CONTRACT(
                "post /privacy-notices/by-contract",
                HttpStatus.CREATED,
                s ->
                        new Call(
                                HttpMethod.POST,
                                "/privacy-notices/by-contract",
                                Map.of("contractUri", GENERATED_CONTRACT_URI))),
        RETRIEVE_PRIVACY_NOTICE(
                "get /privacy-notices/{id}",
                HttpStatus.OK,
                s ->
                        Call.of(
                                HttpMethod.GET,
                                "/privacy-notices/" + s.noticeId() + "?resolve=false")),
        GRANT_CONSENT(
                "post /consents",
                HttpStatus.CREATED,
                null,
                true,
                s ->
                        new Call(
                                HttpMethod.POST,
                                "/consents",
                                Map.of(
                                        "privacyNoticeId",
                                        s.noticeId(),
                                        "decision",
                                        GRANTED_DECISION))),
        LIST_CONSENTS("get /consents", HttpStatus.OK, s -> Call.of(HttpMethod.GET, "/consents")),
        REQUEST_CONSENT(
                "post /consents/requests",
                HttpStatus.CREATED,
                null,
                true,
                s ->
                        new Call(
                                HttpMethod.POST,
                                "/consents/requests",
                                Map.of(
                                        "privacyNoticeId", s.noticeId(),
                                        "userIdentifier", s.callerUser()))),
        CHECK_CONSENT(
                "get /consents/check",
                HttpStatus.OK,
                s ->
                        Call.of(
                                HttpMethod.GET,
                                "/consents/check?userIdentifier="
                                        + s.callerUser()
                                        + "&privacyNoticeId="
                                        + s.noticeId())),
        GET_CONSENT(
                "get /consents/{id}",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.GET, "/consents/" + s.consentId())),
        REVOKE_CONSENT(
                "post /consents/{id}/revoke",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.POST, "/consents/" + s.consentId() + "/revoke")),
        REFUSE_CONSENT(
                "post /consents/{id}/refuse",
                HttpStatus.OK,
                ConsentStatus.PENDING,
                true,
                s -> Call.of(HttpMethod.POST, "/consents/" + s.consentId() + "/refuse")),
        TERMINATE_CONSENT(
                "post /consents/{id}/terminate",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.POST, "/consents/" + s.consentId() + "/terminate")),
        RE_CONFIRM_CONSENT(
                "post /consents/{id}/re-confirm",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.POST, "/consents/" + s.consentId() + "/re-confirm")),
        RESUME_CONSENT(
                "post /consents/{id}/resume",
                HttpStatus.OK,
                ConsentStatus.PENDING,
                true,
                s ->
                        new Call(
                                HttpMethod.POST,
                                "/consents/" + s.consentId() + "/resume",
                                Map.of("userIdentifier", s.callerUser()))),
        LIST_MY_CONSENTS(
                "get /consents/me", HttpStatus.OK, s -> Call.of(HttpMethod.GET, "/consents/me")),
        GET_MY_CONSENT(
                "get /consents/me/{id}",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.GET, "/consents/me/" + s.consentId())),
        LIST_MY_AVAILABLE_CONTRACTS(
                "get /contracts/available",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.GET, "/contracts/available")),
        LIST_AVAILABLE_CONTRACTS(
                "get /contracts/available/{role}",
                HttpStatus.OK,
                s -> Call.of(HttpMethod.GET, "/contracts/available/" + PROVIDER_ROLE));

        private final String specification;
        private final HttpStatus expected;
        private final ConsentStatus seeded;
        private final boolean callerRegistered;
        private final Function<Seeded, Call> factory;

        Endpoint(String specification, HttpStatus expected, Function<Seeded, Call> factory) {
            this(specification, expected, ConsentStatus.GRANTED, true, factory);
        }

        Endpoint(
                String specification,
                HttpStatus expected,
                ConsentStatus seeded,
                boolean callerRegistered,
                Function<Seeded, Call> factory) {
            this.specification = specification;
            this.expected = expected;
            this.seeded = seeded;
            this.callerRegistered = callerRegistered;
            this.factory = factory;
        }

        static Endpoint of(SpecOperation operation) {
            String specification = specificationOf(operation);
            return Arrays.stream(values())
                    .filter(endpoint -> endpoint.specification.equals(specification))
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new AssertionError(
                                            specification
                                                    + " is secured but has no request here, so"
                                                    + " identity substitution never reaches it"));
        }

        @Override
        public String toString() {
            return specification;
        }
    }

    /** One case per secured operation, admitted role and location; read from the parsed spec. */
    static Stream<Arguments> substitutions() {
        return SpecSecurityConsistencyTest.specOperations()
                .filter(operation -> !operation.anonymous())
                .flatMap(
                        operation ->
                                operation.roles().stream()
                                        .flatMap(role -> casesOf(operation, role)));
    }

    /** A query case always; a body case only where the method carries one. */
    private static Stream<Arguments> casesOf(SpecOperation operation, String role) {
        return Stream.of(Location.values())
                .filter(
                        location ->
                                location == Location.QUERY
                                        || METHODS_WITH_BODY.contains(operation.httpMethod()))
                .map(location -> Arguments.of(operation, RealmPrincipal.valueOf(role), location));
    }

    @BeforeEach
    void awaitTheRealm() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
    }

    /** The caller stands in as both contracts' provider, so it is a party to each. */
    @BeforeEach
    void serveContracts() {
        String contract =
                ContractFixtures.body(ContractFixtures.ECOSYSTEM)
                        .replace(FIXTURE_PROVIDER, CALLER_PARTICIPANT);
        contracts.serveEcosystemContract(SEEDED_CONTRACT, contract);
        contracts.serveEcosystemContract(GENERATED_CONTRACT, contract);
    }

    /** Forgets the stubs and whatever they fed the caches, so no answer outlives the case. */
    @AfterEach
    void resetContractService() {
        contracts.reset();
        caches.getCache(ContractGateway.CACHE_NAME).invalidateAll();
        caches.getCache(SelfDescriptionClient.CACHE_NAME).invalidateAll();
    }

    @AfterAll
    void stopContractService() {
        contracts.close();
    }

    @ParameterizedTest(name = "{1} on {0}, foreign identity in the {2}")
    @MethodSource("substitutions")
    @DisplayName("a foreign identity in the request is refused or changes nothing")
    void substitutedIdentityIsRefusedOrIgnored(
            SpecOperation operation, RealmPrincipal caller, Location location) {
        Endpoint endpoint = Endpoint.of(operation);

        Seeded baselineSeed = seed(endpoint);
        HttpResponse<String> baseline =
                exchange(endpoint, baselineSeed, caller, Map.of(), Map.of());
        assertThat(baseline.code())
                .as(
                        "%s %s without a substitution, so the comparison is not vacuous: %s",
                        caller, operation, bodyOf(baseline))
                .isEqualTo(endpoint.expected.getCode());
        List<String> baselineState = storedState();

        DatabaseReset.reset();
        Seeded seeded = seed(endpoint);
        List<String> seededState = storedState();
        Map<String, String> injected = injection(operation, location, seeded, caller);
        HttpResponse<String> substituted =
                location == Location.QUERY
                        ? exchange(endpoint, seeded, caller, injected, Map.of())
                        : exchange(endpoint, seeded, caller, Map.of(), Map.copyOf(injected));

        if (substituted.code() == HttpStatus.BAD_REQUEST.getCode()) {
            assertThat(storedState())
                    .as("a refused substitution wrote nothing")
                    .isEqualTo(seededState);
            return;
        }
        assertThat(substituted.code())
                .as("%s %s answered differently with %s", caller, operation, injected)
                .isEqualTo(baseline.code());
        String answered = bodyOf(substituted);
        String expected = bodyOf(baseline);
        for (String foreign : seeded.foreignValues()) {
            if (!expected.contains(foreign)) {
                assertThat(answered)
                        .as(
                                "%s %s answered about the injected identity %s",
                                caller, operation, foreign)
                        .doesNotContain(foreign);
            }
        }
        assertThat(storedState())
                .as(
                        "%s %s stored an outcome other than the token's own with %s",
                        caller, operation, injected)
                .isEqualTo(baselineState);
    }

    @Test
    @DisplayName("every secured operation has a request here")
    void everySecuredOperationHasARequest() {
        List<String> secured =
                SpecSecurityConsistencyTest.specOperations()
                        .filter(operation -> !operation.anonymous())
                        .map(IdentitySubstitutionIT::specificationOf)
                        .toList();
        Set<String> covered =
                Arrays.stream(Endpoint.values())
                        .map(endpoint -> endpoint.specification)
                        .collect(Collectors.toSet());

        assertThat(secured).isNotEmpty();
        assertThat(covered).containsExactlyInAnyOrderElementsOf(secured);
    }

    /** The injected names the operation does not itself declare at that location. */
    private static Map<String, String> injection(
            SpecOperation operation, Location location, Seeded seeded, RealmPrincipal caller) {
        Set<String> declared =
                location == Location.QUERY
                        ? Set.copyOf(operation.parameterNames())
                        : SpecSecurityConsistencyTest.requestBodyMembers(
                                operation.httpMethod(), operation.path());
        Map<String, String> injected = new LinkedHashMap<>();
        for (Injected name : Injected.values()) {
            if (!declared.contains(name.member)) {
                injected.put(name.member, name.value(seeded, caller));
            }
        }
        return injected;
    }

    private static String specificationOf(SpecOperation operation) {
        return operation.httpMethod() + " " + operation.path();
    }

    /**
     * The caller's user, participant, notice and consent, and a foreign user linked to a foreign
     * participant with a consent of its own, so a substitution has something real to reach.
     */
    private Seeded seed(Endpoint endpoint) {
        User subject = provision(KeycloakTestResource.accessToken(RealmPrincipal.USER));
        User foreignSubject = provision(Foreign.USER.get());
        Participant counterparty = ConsentFixtures.participant(participants, COUNTERPARTY);
        Participant foreign = ConsentFixtures.participant(participants, FOREIGN_PARTICIPANT);
        links.save(new UserParticipant(foreignSubject.getId(), foreign.getId(), null));
        consentOf(ConsentStatus.GRANTED, foreignSubject, notice(foreign, counterparty));
        String noticeId = null;
        String consentId = null;
        if (endpoint.callerRegistered) {
            Participant caller = ConsentFixtures.participant(participants, CALLER_PARTICIPANT);
            links.save(new UserParticipant(subject.getId(), caller.getId(), null));
            PrivacyNotice notice = notice(caller, counterparty);
            noticeId = notice.getId().toString();
            consentId = consentOf(endpoint.seeded, subject, notice);
        }
        return new Seeded(
                subject.getIdentifier(),
                noticeId,
                consentId,
                foreignSubject.getIdentifier(),
                foreignSubject.getId().toString());
    }

    /** Provisions the token's {@code users} row through one authenticated call, as JIT does. */
    private User provision(String token) {
        HttpResponse<String> response = send(HttpRequest.GET("/users/me"), token);
        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        return users.findByIdentifier(KeycloakTestResource.subject(token)).orElseThrow();
    }

    private PrivacyNotice notice(Participant provider, Participant consumer) {
        return notices.save(
                new PrivacyNotice(
                        CONTRACT_URI,
                        NOTICE_TITLE,
                        provider.getId(),
                        consumer.getId(),
                        PrivacyNoticeFixtures.emptyPayload()));
    }

    private String consentOf(ConsentStatus status, User subject, PrivacyNotice under) {
        if (status == null) {
            return null;
        }
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                under.getId(),
                                under.getProviderId(),
                                under.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                ConsentSnapshot.of(under, null, null)))
                .getId()
                .toString();
    }

    private static List<String> storedState() {
        return RawSql.query(
                STORED_STATE,
                row -> ERASED_IDENTIFIER.matcher(row.getString(1)).replaceAll(ERASED_MASK));
    }

    private HttpResponse<String> exchange(
            Endpoint endpoint,
            Seeded seeded,
            RealmPrincipal caller,
            Map<String, String> query,
            Map<String, Object> body) {
        return send(
                endpoint.factory.apply(seeded).build(query, body),
                KeycloakTestResource.accessToken(caller));
    }

    /** Issues the request, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> send(MutableHttpRequest<?> request, String token) {
        try {
            return client.toBlocking().exchange(request.bearerAuth(token), String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    private static String bodyOf(HttpResponse<String> response) {
        return response.getBody(String.class).orElse("");
    }
}
