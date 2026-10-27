package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the authorization model end to end: which requests are refused with which status, and the
 * principal the rest reach a handler with. Not transactional, because the server reads the seeded
 * participant on its own connection.
 */
@MicronautTest(transactional = false)
@DisplayName("Principal resolution and the 401/403 boundary")
class PrincipalResolutionIT extends PostgresTestResource {

    /** Enables the probe controller, so it exists only for this test. */
    private static final String PROBE_ENABLED = "test.principal-resolution.enabled";

    /** Prefix of every probe route. */
    private static final String PROBE_PATH = "/principal-probe";

    /** Probe accepting any authenticated caller, whatever role it holds. */
    private static final String ANY_ROUTE = PROBE_PATH + "/any";

    /** Probe declaring a {@link UserPrincipal} parameter, so only a user may reach its body. */
    private static final String USER_ROUTE = PROBE_PATH + "/user";

    /** Probe rendering every optional profile claim a {@link UserPrincipal} carries. */
    private static final String PROFILE_ROUTE = PROBE_PATH + "/profile";

    /** Probe that only a {@link Role#USER} may reach. */
    private static final String USER_SCOPED_ROUTE = PROBE_PATH + "/user-scoped";

    /** Probe that only a {@link Role#PARTICIPANT} may reach. */
    private static final String PARTICIPANT_SCOPED_ROUTE = PROBE_PATH + "/participant-scoped";

    /** Probe carrying no {@code @Secured} annotation at all. */
    private static final String UNSECURED_ROUTE = PROBE_PATH + "/unsecured";

    /** A path no route is mapped to. */
    private static final String UNMAPPED_ROUTE = "/no-such-route";

    /** The public reachability probe, which must stay reachable without a token. */
    private static final String ANONYMOUS_ROUTE = "/api-status";

    /** Audience the trust list expects, matching {@code application-test.yml}. */
    private static final String AUDIENCE = "consent-manager";

    /** Subject of every minted token; also the user identifier, which defaults to {@code sub}. */
    private static final String SUBJECT = "subject-under-test";

    /** Claim holding the participant identifier, matching the configured claim name. */
    private static final String PARTICIPANT_IDENTIFIER_CLAIM = "participant_id";

    /** Outer segment of the configured roles claim path. */
    private static final String REALM_ACCESS_CLAIM = "realm_access";

    /** Inner segment of the configured roles claim path. */
    private static final String ROLES_SEGMENT = "roles";

    /** Raw role string the trust list maps to {@link Role#USER}. */
    private static final String USER_ROLE = "consent-user";

    /** Raw role string the trust list maps to {@link Role#PARTICIPANT}. */
    private static final String PARTICIPANT_ROLE = "consent-participant";

    /** Raw role string the trust list maps to {@link Role#CATALOG}. */
    private static final String CATALOG_ROLE = "consent-catalog";

    /** A role the provider might emit that this service deliberately does not map. */
    private static final String UNMAPPED_ROLE = "offline_access";

    /** Standard OpenID Connect profile claims a user token may carry, in the order probed. */
    private static final List<String> PROFILE_CLAIMS =
            List.of("email", "email_verified", "name", "given_name", "family_name");

    /** Separator the profile probe joins its fields with. */
    private static final String PROFILE_SEPARATOR = "|";

    /** Value of the {@code email} claim on the profile token. */
    private static final String PROFILE_EMAIL = "user@participant.example";

    /** Value of the {@code name} claim on the profile token. */
    private static final String PROFILE_NAME = "Ada Lovelace";

    /** Value of the {@code given_name} claim on the profile token. */
    private static final String PROFILE_GIVEN_NAME = "Ada";

    /** Value of the {@code family_name} claim on the profile token. */
    private static final String PROFILE_FAMILY_NAME = "Lovelace";

    /** Participant identifier registered in the database before each test. */
    private static final String REGISTERED_PARTICIPANT = "did:example:registered";

    /** Participant identifier deliberately absent from the database. */
    private static final String UNREGISTERED_PARTICIPANT = "did:example:unregistered";

    /** Legal name of the registered participant, asserted on to prove the row was resolved. */
    private static final String REGISTERED_LEGAL_NAME = "Registered Participant Ltd";

    /** Rendering the probe gives a participant principal that carries no registered row. */
    private static final String UNREGISTERED_MARKER = "<unregistered>";

    /** Lifetime given to every minted token. */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);

    /** How long discovery of the stub provider is given before a test gives up. */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(30);

    /** RFC 7807 media type every refusal must be rendered in. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** Header a 401 must carry so a client knows how to authenticate. */
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    /** Authentication scheme named in that header. */
    private static final String BEARER = "Bearer";

    /** Host of the stub, which no refusal body or header may disclose. */
    private static final String ISSUER_HOST = "localhost";

    private static final OidcDiscoveryStub IDENTITY_PROVIDER = new OidcDiscoveryStub();

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject ParticipantRepository participants;

    /** Points the trust list at the stub provider and switches security on. */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put("micronaut.security.enabled", "true");
        properties.put(PROBE_ENABLED, "true");
        properties.put(
                IdentityProviderConfiguration.PREFIX + ".primary.issuer",
                IDENTITY_PROVIDER.issuer());
        properties.put(
                IdentityProviderConfiguration.PREFIX + ".primary.discovery-url",
                IDENTITY_PROVIDER.discoveryUrl());
        return properties;
    }

    @AfterEach
    void resetIdentityProvider() {
        IDENTITY_PROVIDER.reset();
    }

    /** Stops the stub provider once every test has run. */
    @AfterAll
    static void stopIdentityProvider() {
        IDENTITY_PROVIDER.close();
    }

    /** Waits for discovery to resolve and makes sure the registered participant exists. */
    @BeforeEach
    void prepare() {
        Await.until(
                "the stub identity provider to resolve",
                RESOLUTION_TIMEOUT,
                () -> registry.findByIssuer(IDENTITY_PROVIDER.issuer()).isPresent());
        if (!participants.existsByIdentifier(REGISTERED_PARTICIPANT)) {
            participants.save(
                    new Participant(
                            REGISTERED_PARTICIPANT,
                            REGISTERED_LEGAL_NAME,
                            null,
                            null,
                            Map.of(),
                            null));
        }
    }

    @ParameterizedTest(name = "{0} resolves to {1}")
    @MethodSource("mappedRoles")
    @DisplayName("each mapped role resolves to its own typed principal")
    void mappedRoleResolvesToItsPrincipal(String rawRole, String expectedDescription) {
        HttpResponse<String> response =
                get(ANY_ROUTE, token(claims(rawRole, REGISTERED_PARTICIPANT)));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getBody(String.class)).hasValue(expectedDescription);
    }

    /** The mapped roles and the principal each must produce. */
    static Stream<Arguments> mappedRoles() {
        return Stream.of(
                Arguments.of(USER_ROLE, "UserPrincipal USER " + SUBJECT),
                Arguments.of(
                        PARTICIPANT_ROLE,
                        "ParticipantPrincipal PARTICIPANT "
                                + REGISTERED_PARTICIPANT
                                + " "
                                + REGISTERED_LEGAL_NAME),
                Arguments.of(CATALOG_ROLE, "CatalogPrincipal CATALOG " + SUBJECT));
    }

    /** Authorization still reads every granted role; this pins only the identity acted as. */
    @ParameterizedTest(name = "{0} resolves to {2}")
    @MethodSource("multiRoleTokens")
    @DisplayName("a token granting several roles acts as the highest-precedence one")
    void multiRoleTokenActsAsItsHighestPrecedenceRole(
            String[] rawRoles, String participantIdentifier, String expectedDescription) {
        HttpResponse<String> response =
                get(ANY_ROUTE, token(claims(rawRoles, participantIdentifier)));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getBody(String.class)).hasValue(expectedDescription);
    }

    /**
     * Role combinations and the identity each settles on; the last is the catalog service account,
     * participant plus catalog with no participant identifier, which must authenticate.
     */
    static Stream<Arguments> multiRoleTokens() {
        String catalog = "CatalogPrincipal CATALOG " + SUBJECT;
        String participant =
                "ParticipantPrincipal PARTICIPANT "
                        + REGISTERED_PARTICIPANT
                        + " "
                        + REGISTERED_LEGAL_NAME;
        return Stream.of(
                Arguments.of(
                        new String[] {PARTICIPANT_ROLE, CATALOG_ROLE},
                        REGISTERED_PARTICIPANT,
                        catalog),
                Arguments.of(
                        new String[] {USER_ROLE, CATALOG_ROLE}, REGISTERED_PARTICIPANT, catalog),
                Arguments.of(
                        new String[] {USER_ROLE, PARTICIPANT_ROLE},
                        REGISTERED_PARTICIPANT,
                        participant),
                Arguments.of(
                        new String[] {USER_ROLE, PARTICIPANT_ROLE, CATALOG_ROLE},
                        REGISTERED_PARTICIPANT,
                        catalog),
                Arguments.of(
                        new String[] {UNMAPPED_ROLE, USER_ROLE, CATALOG_ROLE},
                        REGISTERED_PARTICIPANT,
                        catalog),
                Arguments.of(new String[] {PARTICIPANT_ROLE, CATALOG_ROLE}, null, catalog));
    }

    /** A user-scoped route must not let a USER+PARTICIPANT caller act as its organisation. */
    @ParameterizedTest(name = "{0} hands a USER+PARTICIPANT token {1}")
    @MethodSource("roleScopedRoutes")
    @DisplayName("a role-scoped operation hands the caller the role it accepts")
    void roleScopedRouteResolvesTheRoleItAccepts(String route, String expectedDescription) {
        HttpResponse<String> response =
                get(
                        route,
                        token(
                                claims(
                                        new String[] {USER_ROLE, PARTICIPANT_ROLE},
                                        REGISTERED_PARTICIPANT)));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getBody(String.class)).hasValue(expectedDescription);
    }

    /** The role-scoped probes and the identity each gives the same multi-role token. */
    static Stream<Arguments> roleScopedRoutes() {
        return Stream.of(
                Arguments.of(USER_SCOPED_ROUTE, "UserPrincipal USER " + SUBJECT),
                Arguments.of(
                        PARTICIPANT_SCOPED_ROUTE,
                        "ParticipantPrincipal PARTICIPANT "
                                + REGISTERED_PARTICIPANT
                                + " "
                                + REGISTERED_LEGAL_NAME));
    }

    @Test
    @DisplayName("a user token's profile claims land on its principal")
    void profileClaimsLandOnThePrincipal() {
        HttpResponse<String> response = get(PROFILE_ROUTE, token(profileClaims()));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getBody(String.class))
                .hasValue(
                        String.join(
                                PROFILE_SEPARATOR,
                                PROFILE_EMAIL,
                                "true",
                                PROFILE_NAME,
                                PROFILE_GIVEN_NAME,
                                PROFILE_FAMILY_NAME));
    }

    @Test
    @DisplayName("a user token without profile claims resolves with none of them set")
    void absentProfileClaimsLeaveThePrincipalEmpty() {
        HttpResponse<String> response = get(PROFILE_ROUTE, token(claims(USER_ROLE, null)));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getBody(String.class)).hasValue("null|false|null|null|null");
    }

    /** 403, not 401: the signature and issuer were good, so the caller is authenticated. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("authenticatedButUnauthorized")
    @DisplayName("a valid token that names no usable caller is refused with 403")
    void validTokenWithoutAUsableRoleIsForbidden(String description, JWTClaimsSet claims) {
        HttpResponse<String> response = get(ANY_ROUTE, token(claims));

        assertThatIsProblem(response, HttpStatus.FORBIDDEN);
    }

    /** The ways a correctly signed token can still fail to name a caller. */
    static Stream<Arguments> authenticatedButUnauthorized() {
        return Stream.of(
                Arguments.of("the roles claim is absent", base().build()),
                Arguments.of("the roles claim is empty", claims(new String[0], null)),
                Arguments.of("the only role is unmappable", claims(UNMAPPED_ROLE, null)),
                Arguments.of(
                        "every role is unmappable",
                        claims(new String[] {UNMAPPED_ROLE, "uma_authorization"}, null)));
    }

    /** Self-registration needs an unknown participant identifier to resolve rather than 403. */
    @Test
    @DisplayName("an unregistered participant identifier resolves a principal without its row")
    void unregisteredParticipantResolvesWithoutItsRow() {
        HttpResponse<String> response =
                get(ANY_ROUTE, token(claims(PARTICIPANT_ROLE, UNREGISTERED_PARTICIPANT)));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getBody(String.class))
                .hasValue(
                        "ParticipantPrincipal PARTICIPANT "
                                + UNREGISTERED_PARTICIPANT
                                + " "
                                + UNREGISTERED_MARKER);
    }

    @ParameterizedTest(name = "a {0} token on a UserPrincipal route")
    @ValueSource(strings = {PARTICIPANT_ROLE, CATALOG_ROLE})
    @DisplayName("a declared principal type the caller does not match is refused with 403")
    void wrongPrincipalTypeIsForbidden(String rawRole) {
        HttpResponse<String> response =
                get(USER_ROUTE, token(claims(rawRole, REGISTERED_PARTICIPANT)));

        assertThatIsProblem(response, HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unauthenticated")
    @DisplayName("a missing or unusable token is refused with 401 and a Bearer challenge")
    void missingOrInvalidTokenIsUnauthorized(String description, String token) {
        HttpResponse<String> response = get(ANY_ROUTE, token);

        assertThatIsProblem(response, HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getAll(WWW_AUTHENTICATE))
                .as("a 401 tells the client how to authenticate")
                .anySatisfy(value -> assertThat(value).startsWith(BEARER));
    }

    /**
     * The ways a request can fail to authenticate; the last, a {@code PARTICIPANT} token naming no
     * participant, is rejected during validation rather than as an authorization failure.
     */
    static Stream<Arguments> unauthenticated() {
        return Stream.of(
                Arguments.of("no token at all", null),
                Arguments.of("a token that is not a JWT", "not-a-jwt"),
                Arguments.of(
                        "a token from an issuer that is not on the trust list",
                        token(
                                base().issuer("https://issuer.invalid/realms/other")
                                        .claim(
                                                REALM_ACCESS_CLAIM,
                                                Map.of(ROLES_SEGMENT, List.of(USER_ROLE)))
                                        .build())),
                Arguments.of("an expired token", token(expired())),
                Arguments.of(
                        "a participant token carrying no participant identifier",
                        token(claims(PARTICIPANT_ROLE, null))));
    }

    /** 401 anonymously and 403 with a token, never 200: access is explicit or it is not access. */
    @Test
    @DisplayName("a route with no @Secured annotation is not reachable")
    void routeWithoutSecuredAnnotationIsRefused() {
        assertThatIsProblem(get(UNSECURED_ROUTE, null), HttpStatus.UNAUTHORIZED);
        assertThatIsProblem(
                get(UNSECURED_ROUTE, token(claims(USER_ROLE, null))), HttpStatus.FORBIDDEN);
    }

    /** Denying by default must not deny what was allowed. */
    @Test
    @DisplayName("an explicitly anonymous route is still reachable without a token")
    void anonymousRouteRemainsReachable() {
        assertThat(get(ANONYMOUS_ROUTE, null).code()).isEqualTo(HttpStatus.OK.getCode());
    }

    /** Principal resolution runs only where a route declares a principal parameter. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensNamingNoCaller")
    @DisplayName("an anonymous route is reachable whatever a token carries")
    void anonymousRouteIgnoresAnUnusableToken(String description, JWTClaimsSet claims) {
        assertThat(get(ANONYMOUS_ROUTE, token(claims)).code())
                .as("%s", description)
                .isEqualTo(HttpStatus.OK.getCode());
    }

    /** Tokens naming no usable caller, which the anonymous route must never inspect. */
    static Stream<Arguments> tokensNamingNoCaller() {
        return Stream.of(
                Arguments.of("no mapped role", claims(UNMAPPED_ROLE, null)),
                Arguments.of(
                        "an unregistered participant identifier",
                        claims(PARTICIPANT_ROLE, UNREGISTERED_PARTICIPANT)));
    }

    /** Refused rather than 404, so an anonymous caller cannot map the URL space. */
    @Test
    @DisplayName("an unmapped path is refused without disclosing that it is unmapped")
    void unmappedPathIsNotDisclosed() {
        assertThatIsProblem(get(UNMAPPED_ROUTE, null), HttpStatus.UNAUTHORIZED);
    }

    /** Asserts a refusal: expected status, RFC 7807 body, and nothing said about the issuer. */
    private static void assertThatIsProblem(HttpResponse<String> response, HttpStatus expected) {
        assertThat(response.code()).isEqualTo(expected.getCode());
        assertThat(response.getContentType())
                .hasValueSatisfying(type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
        String body = response.getBody(String.class).orElse("");
        assertThat(body).contains("\"status\":" + expected.getCode());
        if (expected == HttpStatus.FORBIDDEN) {
            assertThat(response.getHeaders().getAll(WWW_AUTHENTICATE))
                    .as("no credential fixes a 403, so it must not invite the client to retry")
                    .isEmpty();
        }
        assertThat(body)
                .as("a refusal says nothing about the trust list")
                .doesNotContain(ISSUER_HOST);
        for (String name : response.getHeaders().names()) {
            assertThat(response.getHeaders().getAll(name))
                    .allSatisfy(value -> assertThat(value).doesNotContain(ISSUER_HOST));
        }
    }

    /** Performs a GET with an optional bearer token, returning error responses too. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> get(String path, String token) {
        MutableHttpRequest<Object> request = HttpRequest.GET(path);
        if (token != null) {
            request = request.bearerAuth(token);
        }
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    /** Signs a claim set with the stub provider's current key. */
    private static String token(JWTClaimsSet claims) {
        return IDENTITY_PROVIDER.signToken(IDENTITY_PROVIDER.initialKeyId(), claims).serialize();
    }

    /** A claim set granting one role; a {@code null} participant identifier omits the claim. */
    private static JWTClaimsSet claims(String rawRole, String participantIdentifier) {
        return claims(new String[] {rawRole}, participantIdentifier);
    }

    /** A claim set granting the given roles, possibly none; a {@code null} identifier omits it. */
    private static JWTClaimsSet claims(String[] rawRoles, String participantIdentifier) {
        JWTClaimsSet.Builder builder =
                base().claim(REALM_ACCESS_CLAIM, Map.of(ROLES_SEGMENT, List.of(rawRoles)));
        if (participantIdentifier != null) {
            builder.claim(PARTICIPANT_IDENTIFIER_CLAIM, participantIdentifier);
        }
        return builder.build();
    }

    /** A user claim set carrying every optional profile claim. */
    private static JWTClaimsSet profileClaims() {
        return new JWTClaimsSet.Builder(claims(USER_ROLE, null))
                .claim(PROFILE_CLAIMS.get(0), PROFILE_EMAIL)
                .claim(PROFILE_CLAIMS.get(1), Boolean.TRUE)
                .claim(PROFILE_CLAIMS.get(2), PROFILE_NAME)
                .claim(PROFILE_CLAIMS.get(3), PROFILE_GIVEN_NAME)
                .claim(PROFILE_CLAIMS.get(4), PROFILE_FAMILY_NAME)
                .build();
    }

    /** A claim set valid in every respect except that it expired an hour ago. */
    private static JWTClaimsSet expired() {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        return base().claim(REALM_ACCESS_CLAIM, Map.of(ROLES_SEGMENT, List.of(USER_ROLE)))
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(1)))
                .build();
    }

    /** The claims every token carries, before roles and identifiers are added. */
    private static JWTClaimsSet.Builder base() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(IDENTITY_PROVIDER.issuer())
                .subject(SUBJECT)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(TOKEN_LIFETIME)));
    }

    /** Test-only routes taking their caller from the token alone, the shape of a real endpoint. */
    @Requires(property = PROBE_ENABLED, value = "true")
    @Controller(PROBE_PATH)
    static class PrincipalProbeController {

        /** Describes whichever principal the caller resolved to. */
        @Get("/any")
        @Secured(SecurityRule.IS_AUTHENTICATED)
        String any(ConsentManagerPrincipal principal) {
            return describe(principal);
        }

        /** Serves users only, naming the role rather than the principal type. */
        @Get("/user-scoped")
        @Secured("USER")
        String userScoped(ConsentManagerPrincipal principal) {
            return describe(principal);
        }

        /** Serves participants only, naming the role rather than the principal type. */
        @Get("/participant-scoped")
        @Secured("PARTICIPANT")
        String participantScoped(ConsentManagerPrincipal principal) {
            return describe(principal);
        }

        /** Renders a principal's type, role and identity for the assertions to match. */
        private static String describe(ConsentManagerPrincipal principal) {
            return switch (principal) {
                case UserPrincipal user -> "UserPrincipal " + user.role() + " " + user.identifier();
                case ParticipantPrincipal participant ->
                        "ParticipantPrincipal "
                                + participant.role()
                                + " "
                                + participant.identifier()
                                + " "
                                + (participant.registered()
                                        ? participant.participant().getLegalName()
                                        : UNREGISTERED_MARKER);
                case CatalogPrincipal catalog ->
                        "CatalogPrincipal " + catalog.role() + " " + catalog.subject();
            };
        }

        /** Serves users only, by declaring the principal type it will act for. */
        @Get("/user")
        @Secured(SecurityRule.IS_AUTHENTICATED)
        String user(UserPrincipal user) {
            return user.identifier();
        }

        /** Renders the optional profile claims, joined by {@link #PROFILE_SEPARATOR}. */
        @Get("/profile")
        @Secured(SecurityRule.IS_AUTHENTICATED)
        String profile(UserPrincipal user) {
            return String.join(
                    PROFILE_SEPARATOR,
                    String.valueOf(user.email()),
                    String.valueOf(user.emailVerified()),
                    String.valueOf(user.name()),
                    String.valueOf(user.givenName()),
                    String.valueOf(user.familyName()));
        }

        /** Carries no access rule on purpose, to assert that forgetting one is a refusal. */
        @Get("/unsecured")
        String unsecured() {
            return "reachable";
        }
    }
}
