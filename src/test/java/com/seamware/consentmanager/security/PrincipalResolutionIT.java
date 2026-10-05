package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * End-to-end check of the authorization model: which requests are refused, with which status, and
 * what identity the ones that are not refused reach a handler with.
 *
 * <p>Runs against a full context - real PostgreSQL for the participant lookup and a WireMock
 * identity provider whose keys actually sign the tokens - because the distinctions under test are
 * produced by the security filter, the principal filter and the argument binder acting together,
 * and are not observable from any one of them alone.
 *
 * <p>The routes exercised here are probes declared below rather than real endpoints: no secured
 * endpoint exists yet, and a probe is what lets the rules be tested before the endpoints that will
 * rely on them are written.
 *
 * <p>Not transactional: the participant row has to be committed before the server, which answers on
 * its own connection, can see it.
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

    @Inject UserRepository users;

    /**
     * Points the trust list at the stub provider and switches security on, which {@code
     * application-test.yml} leaves off for every other test.
     *
     * @return the property overrides, on top of the Testcontainers datasource
     */
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

    /** Stops the stub provider once every test has run. */
    @AfterAll
    static void stopIdentityProvider() {
        IDENTITY_PROVIDER.close();
    }

    /**
     * Removes the rows that outlive the test: the participant registered for it, and the user
     * just-in-time provisioning created for every token minted with {@link #SUBJECT}.
     */
    @AfterAll
    void removeCommittedRows() {
        participants.findByIdentifier(REGISTERED_PARTICIPANT).ifPresent(participants::delete);
        users.findByIdentifier(SUBJECT).ifPresent(users::delete);
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

    /**
     * Each mapped role reaches a handler as its own principal type, carrying what the token said.
     *
     * @param rawRole the role string the provider emits
     * @param expectedDescription what the probe renders for the principal it was handed
     */
    @ParameterizedTest(name = "{0} resolves to {1}")
    @MethodSource("mappedRoles")
    @DisplayName("each mapped role resolves to its own typed principal")
    void mappedRoleResolvesToItsPrincipal(String rawRole, String expectedDescription) {
        HttpResponse<String> response =
                get(ANY_ROUTE, token(claims(rawRole, REGISTERED_PARTICIPANT)));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getBody(String.class)).hasValue(expectedDescription);
    }

    /**
     * The mapped roles and the principal each must produce.
     *
     * @return one case per role
     */
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

    /**
     * A token granting several roles acts as exactly one of them, by the published precedence.
     *
     * <p>Authorization still reads the whole set - every mapped role is granted as an authority -
     * so this only pins which identity the caller then acts as. The catalog case is the one the
     * dataspace actually issues: a catalog token is a participant token that additionally bears the
     * catalog role, so a precedence that let {@code PARTICIPANT} win would mean no token could ever
     * act as the catalog.
     *
     * @param rawRoles the role strings the provider emits together
     * @param expectedDescription what the probe renders for the principal it was handed
     */
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
     * The role combinations a provider can grant, and the identity each settles on.
     *
     * <p>The last case is the catalog service account the token contract describes: participant
     * plus catalog, with no participant identifier at all. It must authenticate, because the
     * identifier of one granted role - here the catalog's issuer and subject - suffices.
     *
     * @return one case per combination
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

    /**
     * An operation scoped to one role hands a multi-role caller that role, not whichever role a
     * route-blind precedence would pick.
     *
     * <p>Otherwise identity and authorization diverge: a person who is also a registered
     * participant satisfies a user-scoped {@code @Secured} and would then act as their organisation
     * on it.
     *
     * @param route the role-scoped probe
     * @param expectedDescription what the probe renders for the principal it was handed
     */
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

    /**
     * The role-scoped probes and the identity each gives the same multi-role token.
     *
     * @return one case per scope
     */
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

    /** Every optional profile claim the token carries reaches the handler on its principal. */
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

    /**
     * A user token carrying none of the optional profile claims still resolves, with nothing set.
     */
    @Test
    @DisplayName("a user token without profile claims resolves with none of them set")
    void absentProfileClaimsLeaveThePrincipalEmpty() {
        HttpResponse<String> response = get(PROFILE_ROUTE, token(claims(USER_ROLE, null)));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getBody(String.class)).hasValue("null|false|null|null|null");
    }

    /**
     * A token that authenticates but names no caller this service will act for is a 403.
     *
     * <p>This is the heart of the authorization model: the signature was good and the issuer is
     * trusted, so the caller <em>is</em> authenticated and sending it back to authenticate again
     * would be a lie.
     *
     * @param description what is wrong with the token, used as the case name
     * @param claims the claim set to mint
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("authenticatedButUnauthorized")
    @DisplayName("a valid token that names no usable caller is refused with 403")
    void validTokenWithoutAUsableRoleIsForbidden(String description, JWTClaimsSet claims) {
        HttpResponse<String> response = get(ANY_ROUTE, token(claims));

        assertThatIsProblem(response, HttpStatus.FORBIDDEN);
    }

    /**
     * The ways a correctly signed token can still fail to name a caller.
     *
     * @return one case per way
     */
    static Stream<Arguments> authenticatedButUnauthorized() {
        return Stream.of(
                Arguments.of("the roles claim is absent", base().build()),
                Arguments.of("the roles claim is empty", claims(new String[0], null)),
                Arguments.of("the only role is unmappable", claims(UNMAPPED_ROLE, null)),
                Arguments.of(
                        "every role is unmappable",
                        claims(new String[] {UNMAPPED_ROLE, "uma_authorization"}, null)),
                Arguments.of(
                        "the participant identifier is not registered",
                        claims(PARTICIPANT_ROLE, UNREGISTERED_PARTICIPANT)));
    }

    /**
     * A caller whose role does not match the principal type a handler declares is a 403.
     *
     * @param rawRole a role that is not {@link Role#USER}
     */
    @ParameterizedTest(name = "a {0} token on a UserPrincipal route")
    @ValueSource(strings = {PARTICIPANT_ROLE, CATALOG_ROLE})
    @DisplayName("a declared principal type the caller does not match is refused with 403")
    void wrongPrincipalTypeIsForbidden(String rawRole) {
        HttpResponse<String> response =
                get(USER_ROUTE, token(claims(rawRole, REGISTERED_PARTICIPANT)));

        assertThatIsProblem(response, HttpStatus.FORBIDDEN);
    }

    /**
     * A request that never authenticated is a 401, whatever is wrong with its token.
     *
     * @param description the case name
     * @param token the bearer token to send, or {@code null} to send none
     */
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
     * The ways a request can fail to authenticate.
     *
     * <p>The last case looks like an authorization failure and is not: the token contract requires
     * a {@code PARTICIPANT} token to name its participant, so a token that does not is rejected
     * while it is still being validated and never authenticates at all.
     *
     * @return one case per way
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

    /**
     * A route nobody secured is refused rather than served: access is explicit or it is not access.
     *
     * <p>Anonymously it is a 401 and with a valid token a 403, which is the same boundary every
     * other route observes - the point is that neither is a 200.
     */
    @Test
    @DisplayName("a route with no @Secured annotation is not reachable")
    void routeWithoutSecuredAnnotationIsRefused() {
        assertThatIsProblem(get(UNSECURED_ROUTE, null), HttpStatus.UNAUTHORIZED);
        assertThatIsProblem(
                get(UNSECURED_ROUTE, token(claims(USER_ROLE, null))), HttpStatus.FORBIDDEN);
    }

    /** The public probe stays anonymous: denying by default must not deny what was allowed. */
    @Test
    @DisplayName("an explicitly anonymous route is still reachable without a token")
    void anonymousRouteRemainsReachable() {
        assertThat(get(ANONYMOUS_ROUTE, null).code()).isEqualTo(HttpStatus.OK.getCode());
    }

    /**
     * A reachability probe stays reachable even for a caller whose token names nobody.
     *
     * <p>A browser or a monitoring agent sends whatever token it is holding on every request, and a
     * route the specification declares {@code security: []} must not start answering {@code 403}
     * because of one. Principal resolution therefore runs only for a route that declares a
     * principal parameter, which this one does not.
     *
     * @param description the case name
     * @param claims a claim set that would be refused on a route taking a principal
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensNamingNoCaller")
    @DisplayName("an anonymous route is reachable with a token that names no usable caller")
    void anonymousRouteIgnoresAnUnusableToken(String description, JWTClaimsSet claims) {
        assertThat(get(ANONYMOUS_ROUTE, token(claims)).code())
                .as("%s", description)
                .isEqualTo(HttpStatus.OK.getCode());
    }

    /**
     * Tokens that authenticate and then name no caller, every one of them a 403 on a secured route.
     *
     * @return one case per way
     */
    static Stream<Arguments> tokensNamingNoCaller() {
        return Stream.of(
                Arguments.of("no mapped role", claims(UNMAPPED_ROLE, null)),
                Arguments.of(
                        "an unregistered participant identifier",
                        claims(PARTICIPANT_ROLE, UNREGISTERED_PARTICIPANT)));
    }

    /**
     * An unmapped path is refused rather than answered 404, so an anonymous caller cannot map the
     * URL space by watching which paths come back as missing.
     */
    @Test
    @DisplayName("an unmapped path is refused without disclosing that it is unmapped")
    void unmappedPathIsNotDisclosed() {
        assertThatIsProblem(get(UNMAPPED_ROUTE, null), HttpStatus.UNAUTHORIZED);
    }

    /**
     * Asserts a refusal: the expected status, an RFC 7807 body, and nothing said about the issuer.
     *
     * @param response the refusal
     * @param expected the status it must carry
     */
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

    /**
     * Performs a GET, returning the response even when it is an error.
     *
     * @param path the path to request
     * @param token the bearer token, or {@code null} to send none
     * @return the response
     */
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

    /**
     * Signs a claim set with the stub provider's current key.
     *
     * @param claims the claims to sign
     * @return the serialized bearer token
     */
    private static String token(JWTClaimsSet claims) {
        return IDENTITY_PROVIDER.signToken(IDENTITY_PROVIDER.initialKeyId(), claims).serialize();
    }

    /**
     * A claim set granting one role.
     *
     * @param rawRole the role string the provider emits
     * @param participantIdentifier the participant identifier, or {@code null} to omit the claim
     * @return the claims
     */
    private static JWTClaimsSet claims(String rawRole, String participantIdentifier) {
        return claims(new String[] {rawRole}, participantIdentifier);
    }

    /**
     * A claim set granting the given roles.
     *
     * @param rawRoles the role strings the provider emits, possibly none
     * @param participantIdentifier the participant identifier, or {@code null} to omit the claim
     * @return the claims
     */
    private static JWTClaimsSet claims(String[] rawRoles, String participantIdentifier) {
        JWTClaimsSet.Builder builder =
                base().claim(REALM_ACCESS_CLAIM, Map.of(ROLES_SEGMENT, List.of(rawRoles)));
        if (participantIdentifier != null) {
            builder.claim(PARTICIPANT_IDENTIFIER_CLAIM, participantIdentifier);
        }
        return builder.build();
    }

    /**
     * A user claim set carrying every optional profile claim.
     *
     * @return the claims
     */
    private static JWTClaimsSet profileClaims() {
        return new JWTClaimsSet.Builder(claims(USER_ROLE, null))
                .claim(PROFILE_CLAIMS.get(0), PROFILE_EMAIL)
                .claim(PROFILE_CLAIMS.get(1), Boolean.TRUE)
                .claim(PROFILE_CLAIMS.get(2), PROFILE_NAME)
                .claim(PROFILE_CLAIMS.get(3), PROFILE_GIVEN_NAME)
                .claim(PROFILE_CLAIMS.get(4), PROFILE_FAMILY_NAME)
                .build();
    }

    /**
     * Builds a claim set valid in every respect except that it expired an hour ago.
     *
     * @return the claims
     */
    private static JWTClaimsSet expired() {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        return base().claim(REALM_ACCESS_CLAIM, Map.of(ROLES_SEGMENT, List.of(USER_ROLE)))
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(1)))
                .build();
    }

    /**
     * Builds the claims every token carries, before roles and identifiers are added.
     *
     * @return the builder
     */
    private static JWTClaimsSet.Builder base() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(IDENTITY_PROVIDER.issuer())
                .subject(SUBJECT)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(TOKEN_LIFETIME)));
    }

    /**
     * Routes that exist only for this test, each one taking its caller from the token and nothing
     * else - which is the shape every real endpoint is expected to have.
     */
    @Requires(property = PROBE_ENABLED, value = "true")
    @Controller(PROBE_PATH)
    static class PrincipalProbeController {

        /**
         * Describes whichever principal the caller resolved to.
         *
         * @param principal the resolved caller
         * @return a rendering of the principal's type, role and identity
         */
        @Get("/any")
        @Secured(SecurityRule.IS_AUTHENTICATED)
        String any(ConsentManagerPrincipal principal) {
            return describe(principal);
        }

        /**
         * Serves users only, naming the role rather than the principal type.
         *
         * @param principal the resolved caller
         * @return a rendering of the principal the route was handed
         */
        @Get("/user-scoped")
        @Secured("USER")
        String userScoped(ConsentManagerPrincipal principal) {
            return describe(principal);
        }

        /**
         * Serves participants only, naming the role rather than the principal type.
         *
         * @param principal the resolved caller
         * @return a rendering of the principal the route was handed
         */
        @Get("/participant-scoped")
        @Secured("PARTICIPANT")
        String participantScoped(ConsentManagerPrincipal principal) {
            return describe(principal);
        }

        /**
         * Renders a principal's type, role and identity.
         *
         * @param principal the resolved caller
         * @return the rendering the assertions match on
         */
        private static String describe(ConsentManagerPrincipal principal) {
            return switch (principal) {
                case UserPrincipal user -> "UserPrincipal " + user.role() + " " + user.identifier();
                case ParticipantPrincipal participant ->
                        "ParticipantPrincipal "
                                + participant.role()
                                + " "
                                + participant.identifier()
                                + " "
                                + participant.participant().getLegalName();
                case CatalogPrincipal catalog ->
                        "CatalogPrincipal " + catalog.role() + " " + catalog.subject();
            };
        }

        /**
         * Serves users only, by declaring the principal type it is willing to act for.
         *
         * @param user the resolved caller
         * @return the user's identifier
         */
        @Get("/user")
        @Secured(SecurityRule.IS_AUTHENTICATED)
        String user(UserPrincipal user) {
            return user.identifier();
        }

        /**
         * Renders the optional profile claims the principal carried out of the token.
         *
         * @param user the resolved caller
         * @return the profile fields, separated by {@link #PROFILE_SEPARATOR}
         */
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

        /**
         * Carries no access rule on purpose, so that forgetting one can be asserted to be a refusal
         * rather than a public endpoint.
         *
         * @return a body no caller should ever see
         */
        @Get("/unsecured")
        String unsecured() {
            return "reachable";
        }
    }
}
