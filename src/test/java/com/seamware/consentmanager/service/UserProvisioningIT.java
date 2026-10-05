package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderConfiguration;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.security.annotation.Secured;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * End-to-end check of just-in-time user provisioning: that a {@code USER} token reaches a handler
 * with a row behind it, that the row is created once and only once however many requests race for
 * it, and that its display fields track the token without being rewritten on every request.
 *
 * <p>Runs against a full context - real PostgreSQL, because the unique constraint is what makes the
 * concurrent case correct and an in-memory stand-in would not enforce it, and a WireMock identity
 * provider whose keys actually sign the tokens, because provisioning only happens for a token that
 * got through validation.
 *
 * <p>Not transactional: the server answers on its own connection, so a row written inside a test
 * transaction would be invisible to it and the rows it writes have to be cleaned up explicitly.
 */
@MicronautTest(transactional = false)
@DisplayName("Just-in-time user provisioning")
class UserProvisioningIT extends PostgresTestResource {

    /** Enables the probe controller, so it exists only for this test. */
    private static final String PROBE_ENABLED = "test.user-provisioning.enabled";

    /** Route a user reaches to have themselves provisioned. */
    private static final String PROBE_PATH = "/provisioning-probe";

    /** Audience the trust list expects, matching {@code application-test.yml}. */
    private static final String AUDIENCE = "consent-manager";

    /** Outer segment of the configured roles claim path. */
    private static final String REALM_ACCESS_CLAIM = "realm_access";

    /** Inner segment of the configured roles claim path. */
    private static final String ROLES_SEGMENT = "roles";

    /** Raw role string the trust list maps to the user role. */
    private static final String USER_ROLE = "consent-user";

    /** Standard OpenID Connect claim the {@code email} column is copied from. */
    private static final String EMAIL_CLAIM = "email";

    /** Standard OpenID Connect claim the {@code first_name} column is copied from. */
    private static final String GIVEN_NAME_CLAIM = "given_name";

    /** Standard OpenID Connect claim the {@code last_name} column is copied from. */
    private static final String FAMILY_NAME_CLAIM = "family_name";

    /** Email claim of the token that first provisions a user. */
    private static final String INITIAL_EMAIL = "ada@participant.example";

    /** Given-name claim of the token that first provisions a user. */
    private static final String INITIAL_GIVEN_NAME = "Ada";

    /** Family-name claim of the token that first provisions a user. */
    private static final String INITIAL_FAMILY_NAME = "Lovelace";

    /** Lifetime given to every minted token. */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);

    /** How long discovery of the stub provider is given before a test gives up. */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(30);

    /** How many requests race for the same brand-new identifier in the concurrency test. */
    private static final int CONCURRENT_FIRST_REQUESTS = 16;

    /** How long those racing requests are given to all come back. */
    private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(60);

    /** Counts the rows a single identifier has, which the schema constrains to at most one. */
    private static final String COUNT_BY_IDENTIFIER =
            "SELECT count(*) FROM users WHERE identifier = ?";

    private static final OidcDiscoveryStub IDENTITY_PROVIDER = new OidcDiscoveryStub();

    /** Identifiers provisioned during the run, removed once it ends. */
    private final List<String> provisioned = new ArrayList<>();

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject DataSource dataSource;

    @Inject DataSourceResolver dataSources;

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

    /** Removes the rows this test committed, which outlive it without this. */
    @AfterAll
    void removeProvisionedUsers() {
        provisioned.forEach(
                identifier -> users.findByIdentifier(identifier).ifPresent(users::delete));
    }

    /** Waits for discovery to resolve, without which no token would get as far as provisioning. */
    @BeforeEach
    void awaitDiscovery() {
        Await.until(
                "the stub identity provider to resolve",
                RESOLUTION_TIMEOUT,
                () -> registry.findByIssuer(IDENTITY_PROVIDER.issuer()).isPresent());
    }

    /** A first request for an unknown identifier leaves exactly one row, built from the claims. */
    @Test
    @DisplayName("a first request provisions one row from the token's claims")
    void firstRequestProvisionsTheUser() {
        String identifier = unknownIdentifier();

        HttpResponse<String> response = get(token(claims(identifier)));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(countRowsFor(identifier)).isOne();
        User row = row(identifier);
        assertThat(row.getIdentifier()).isEqualTo(identifier);
        assertThat(row.getEmail()).isEqualTo(INITIAL_EMAIL);
        assertThat(row.getFirstName()).isEqualTo(INITIAL_GIVEN_NAME);
        assertThat(row.getLastName()).isEqualTo(INITIAL_FAMILY_NAME);
        assertThat(response.body()).isEqualTo(row.getId().toString());
    }

    /** A token carrying only the identifier still provisions; the profile claims are optional. */
    @Test
    @DisplayName("a token carrying no profile claims provisions a row with empty display fields")
    void profileClaimsAreOptional() {
        String identifier = unknownIdentifier();

        HttpResponse<String> response = get(token(bareClaims(identifier)));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        User row = row(identifier);
        assertThat(row.getEmail()).isNull();
        assertThat(row.getFirstName()).isNull();
        assertThat(row.getLastName()).isNull();
    }

    /** The second request adopts the first request's row rather than adding another. */
    @Test
    @DisplayName("a second request reuses the row the first one created")
    void secondRequestReusesTheRow() {
        String identifier = unknownIdentifier();
        HttpResponse<String> first = get(token(claims(identifier)));
        Instant createdAt = row(identifier).getCreatedAt();

        HttpResponse<String> second = get(token(claims(identifier)));

        assertThat(second.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(countRowsFor(identifier)).isOne();
        assertThat(row(identifier).getCreatedAt()).isEqualTo(createdAt);
    }

    /**
     * A display claim that changed is written back, and the row keeps its identity while doing so.
     *
     * @param description what the token changed, for the test name
     * @param email the {@code email} claim the second token carries
     * @param givenName the {@code given_name} claim the second token carries
     * @param familyName the {@code family_name} claim the second token carries
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("changedProfileClaims")
    @DisplayName("a changed display claim is written back to the existing row")
    void changedClaimsRefreshTheRow(
            String description, String email, String givenName, String familyName) {
        String identifier = unknownIdentifier();
        HttpResponse<String> first = get(token(claims(identifier)));
        Instant updatedAt = row(identifier).getUpdatedAt();

        HttpResponse<String> second = get(token(claims(identifier, email, givenName, familyName)));

        assertThat(second.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(countRowsFor(identifier)).isOne();
        User row = row(identifier);
        assertThat(row.getEmail()).isEqualTo(email);
        assertThat(row.getFirstName()).isEqualTo(givenName);
        assertThat(row.getLastName()).isEqualTo(familyName);
        assertThat(row.getUpdatedAt()).isAfter(updatedAt);
    }

    /** One changed claim per case, plus the case where the provider stops sending one. */
    static Stream<Arguments> changedProfileClaims() {
        return Stream.of(
                Arguments.of(
                        "the email changed",
                        "ada.lovelace@participant.example",
                        INITIAL_GIVEN_NAME,
                        INITIAL_FAMILY_NAME),
                Arguments.of(
                        "the given name changed", INITIAL_EMAIL, "Augusta", INITIAL_FAMILY_NAME),
                Arguments.of("the family name changed", INITIAL_EMAIL, INITIAL_GIVEN_NAME, "King"),
                Arguments.of("every display claim was dropped", null, null, null));
    }

    /** An unchanged claim set must not rewrite the row, which every request would otherwise do. */
    @Test
    @DisplayName("an unchanged claim set leaves updated_at untouched")
    void unchangedClaimsDoNotTouchTheRow() {
        String identifier = unknownIdentifier();
        get(token(claims(identifier)));
        Instant updatedAt = row(identifier).getUpdatedAt();

        HttpResponse<String> repeated = get(token(claims(identifier)));

        assertThat(repeated.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(row(identifier).getUpdatedAt()).isEqualTo(updatedAt);
    }

    /**
     * Requests racing for one brand-new identifier all succeed and all land on the same row.
     *
     * <p>This is the case the unique constraint exists for: without it the losers would either
     * insert a duplicate or fail the request, and with a pre-check alone they would do so only
     * occasionally, which is worse.
     */
    @Test
    @DisplayName("concurrent first requests for one identifier leave exactly one row")
    void concurrentFirstRequestsProvisionOnce() throws Exception {
        String identifier = unknownIdentifier();
        String token = token(claims(identifier));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> responses = new ArrayList<>();

        ExecutorService racers = Executors.newFixedThreadPool(CONCURRENT_FIRST_REQUESTS);
        try {
            for (int i = 0; i < CONCURRENT_FIRST_REQUESTS; i++) {
                responses.add(
                        racers.submit(
                                () -> {
                                    start.await();
                                    return get(token);
                                }));
            }
            start.countDown();
            racers.shutdown();
            assertThat(racers.awaitTermination(CONCURRENCY_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .as("every racing request to come back")
                    .isTrue();
        } finally {
            racers.shutdownNow();
        }

        List<String> bodies = new ArrayList<>();
        for (Future<HttpResponse<String>> pending : responses) {
            HttpResponse<String> response = pending.get();
            assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
            bodies.add(response.body());
        }
        assertThat(bodies)
                .hasSize(CONCURRENT_FIRST_REQUESTS)
                .containsOnly(row(identifier).getId().toString());
        assertThat(countRowsFor(identifier)).isOne();
    }

    /**
     * An identifier no row exists for, remembered so the row it provisions is cleaned up.
     *
     * @return the identifier
     */
    private String unknownIdentifier() {
        String identifier = "urn:test:user:" + UUID.randomUUID();
        provisioned.add(identifier);
        return identifier;
    }

    /**
     * Reads the row an identifier provisioned, failing the test when there is none.
     *
     * @param identifier the user identifier
     * @return the row
     */
    private User row(String identifier) {
        Optional<User> row = users.findByIdentifier(identifier);
        assertThat(row).as("the row for %s", identifier).isPresent();
        return row.get();
    }

    /**
     * Counts the rows an identifier has, straight from the database rather than through the
     * repository, so a duplicate the schema somehow allowed would be visible rather than throwing.
     *
     * @param identifier the user identifier
     * @return how many rows carry it
     */
    private long countRowsFor(String identifier) {
        // The injected DataSource is Micronaut Data's contextual wrapper, which hands out a
        // connection only inside a transaction this test deliberately does not have; resolving it
        // gets at the plain pool underneath.
        DataSource pool = dataSources.resolve(dataSource);
        try (Connection connection = pool.getConnection();
                PreparedStatement statement = connection.prepareStatement(COUNT_BY_IDENTIFIER)) {
            statement.setString(1, identifier);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the rows for " + identifier, e);
        }
    }

    /**
     * Calls the probe with a bearer token.
     *
     * @param token the bearer token
     * @return the response, refusals included
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> get(String token) {
        try {
            return client.toBlocking()
                    .exchange(HttpRequest.GET(PROBE_PATH).bearerAuth(token), String.class);
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
     * A user claim set carrying the initial profile claims.
     *
     * @param identifier the user identifier, which is the {@code sub} the trust list reads
     * @return the claims
     */
    private static JWTClaimsSet claims(String identifier) {
        return claims(identifier, INITIAL_EMAIL, INITIAL_GIVEN_NAME, INITIAL_FAMILY_NAME);
    }

    /**
     * A user claim set carrying the given profile claims, omitting each one that is {@code null}.
     *
     * @param identifier the user identifier
     * @param email the {@code email} claim, or {@code null} to omit it
     * @param givenName the {@code given_name} claim, or {@code null} to omit it
     * @param familyName the {@code family_name} claim, or {@code null} to omit it
     * @return the claims
     */
    private static JWTClaimsSet claims(
            String identifier, String email, String givenName, String familyName) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder(bareClaims(identifier));
        if (email != null) {
            builder.claim(EMAIL_CLAIM, email);
        }
        if (givenName != null) {
            builder.claim(GIVEN_NAME_CLAIM, givenName);
        }
        if (familyName != null) {
            builder.claim(FAMILY_NAME_CLAIM, familyName);
        }
        return builder.build();
    }

    /**
     * A user claim set carrying nothing beyond what validation requires.
     *
     * @param identifier the user identifier
     * @return the claims
     */
    private static JWTClaimsSet bareClaims(String identifier) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(IDENTITY_PROVIDER.issuer())
                .subject(identifier)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(TOKEN_LIFETIME)))
                .claim(REALM_ACCESS_CLAIM, Map.of(ROLES_SEGMENT, List.of(USER_ROLE)))
                .build();
    }

    /**
     * The route that exists only for this test, taking its caller from the token and nothing else.
     */
    @Requires(property = PROBE_ENABLED, value = "true")
    @Controller(PROBE_PATH)
    static class ProvisioningProbeController {

        /**
         * Renders the identifier of the row the caller was provisioned to, which is the whole point
         * of the step: a handler sees a persisted user, not just claims.
         *
         * @param principal the resolved caller
         * @return the row's primary key
         */
        @Get
        @Secured("USER")
        String provisionedUser(UserPrincipal principal) {
            return principal.user().getId().toString();
        }
    }
}
