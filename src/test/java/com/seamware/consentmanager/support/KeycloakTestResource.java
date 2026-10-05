package com.seamware.consentmanager.support;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import com.seamware.consentmanager.security.IdentityProviderConfiguration;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Testcontainers Keycloak fixture issuing real tokens from the realm imported by {@value
 * #REALM_IMPORT_FILE}, and the trust-list properties pointing this service at it.
 *
 * <p>The container is a JVM-wide singleton, as in {@link PostgresTestResource}: Keycloak takes
 * several seconds to boot, and the realm is read-only apart from the signing-key rotation in {@link
 * #rotateRealmSigningKey(String)}, which only ever <em>adds</em> a key - and so cannot invalidate
 * tokens another test class already holds - and is undone through {@link
 * #deleteRealmComponent(String)}.
 *
 * <p>This is a static fixture, not a base class. Tests reach it through {@link
 * KeycloakAndPostgresTestResource}, which merges {@link #keycloakProperties()} into the
 * Testcontainers datasource properties and puts back the {@code micronaut.security.enabled} that
 * {@code src/test/resources/application-test.yml} switches off.
 *
 * <p>Transport is plain http, which the trust list refuses unless {@code allow-insecure-transport}
 * is set; {@code application-test.yml} already sets it for the {@code primary} entry this fixture
 * overrides.
 */
public final class KeycloakTestResource {

    /** Pinned Keycloak image, so a library upgrade cannot silently change the IDP under test. */
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.1";

    /** Classpath location of the imported realm definition. */
    private static final String REALM_IMPORT_FILE = "/keycloak/consent-manager-realm.json";

    /** Realm name declared in the import file. */
    public static final String REALM = "consent-manager-test";

    /**
     * Audience this realm's tokens carry, which the trust list entry must demand.
     *
     * <p>Must equal the {@code included.custom.audience} of the audience mapper in {@value
     * #REALM_IMPORT_FILE}; a mismatch refuses every Keycloak-issued token with a bare 401.
     */
    public static final String AUDIENCE = "consent-manager";

    /**
     * Participant identifier hard-coded into {@link RealmPrincipal#PARTICIPANT}'s {@code
     * participant_id} claim. A test exercising that client must have committed a {@code
     * participants} row with this identifier, or the token resolves to no principal and the request
     * is refused with 403.
     */
    public static final String PARTICIPANT_IDENTIFIER = "urn:test:participant:keycloak";

    /** Confidential client in the imported realm with the password grant enabled. */
    private static final String TEST_CLIENT_ID = "consent-manager-test-client";

    /** Secret of {@link #TEST_CLIENT_ID}, as declared in the realm import file. */
    private static final String TEST_CLIENT_SECRET = "test-client-secret";

    /** Password shared by every {@link RealmPrincipal}, as declared in the realm import file. */
    private static final String TEST_PASSWORD = "test-password";

    /** Trust-list entry this fixture overrides; the name is fixed by {@code application.yml}. */
    private static final String PROVIDER_NAME = "primary";

    /** Realm path segment preceding every realm-scoped Keycloak endpoint. */
    private static final String REALMS_PATH = "/realms/";

    /** Path of the OpenID Connect discovery document, relative to the issuer. */
    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    /** Path of the token endpoint, relative to the issuer. */
    private static final String TOKEN_PATH = "/protocol/openid-connect/token";

    /** Timeout for the token and admin calls this fixture makes against the container. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    /** HTTP status Keycloak returns when a component is created. */
    private static final int HTTP_CREATED = 201;

    /** Lowest HTTP status counting as a failure. */
    private static final int HTTP_BAD_REQUEST = 400;

    /** HTTP status Keycloak returns for a component that no longer exists. */
    private static final int HTTP_NOT_FOUND = 404;

    /**
     * Priority of the signing key added by {@link #rotateRealmSigningKey(String)}. Keycloak signs
     * with the active key of highest priority, and the realm's built-in generated key sits at 100,
     * so anything above that takes over without removing the old key.
     */
    private static final String ROTATED_KEY_PRIORITY = "200";

    /** Shared container, started once per JVM; Ryuk stops it when the JVM exits. */
    @SuppressWarnings("resource")
    private static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer(KEYCLOAK_IMAGE).withRealmImportFile(REALM_IMPORT_FILE);

    static {
        KEYCLOAK.start();
    }

    /** Client used for the token and admin calls. */
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    /**
     * Realm users in the imported realm, one per role the matrix needs.
     *
     * <p>Each holds exactly one realm role and authenticates with the resource-owner password grant
     * against {@link #TEST_CLIENT_ID}, so the role a token carries is decided by which constant
     * minted it. The realm uses password-grant users rather than service accounts because a client
     * with {@code serviceAccountsEnabled} crashes Keycloak's bootstrap realm import with "Session
     * not bound to a realm"; the tokens are equally real either way.
     */
    public enum RealmPrincipal {
        /** Holds {@code dataspace-user}, mapped to this service's {@code USER} role. */
        USER("user-principal"),

        /** Holds {@code dataspace-participant}, and carries a {@code participant_id} claim. */
        PARTICIPANT("participant-principal"),

        /** Holds {@code dataspace-catalog}, mapped to this service's {@code CATALOG} role. */
        CATALOG("catalog-principal"),

        /** Holds {@code dataspace-outsider}, which the trust list maps to no role at all. */
        OUTSIDER("outsider-principal");

        private final String username;

        RealmPrincipal(String username) {
            this.username = username;
        }

        /** The {@code username} as declared in the realm import file. */
        public String username() {
            return username;
        }
    }

    /** Raw provider role mapped to {@code USER}, as declared in the realm import file. */
    private static final String RAW_USER_ROLE = "dataspace-user";

    /** Raw provider role mapped to {@code PARTICIPANT}. */
    private static final String RAW_PARTICIPANT_ROLE = "dataspace-participant";

    /** Raw provider role mapped to {@code CATALOG}. */
    private static final String RAW_CATALOG_ROLE = "dataspace-catalog";

    /** The realm's issuer identifier, which is also what its tokens carry in {@code iss}. */
    public static String issuer() {
        String authServerUrl = KEYCLOAK.getAuthServerUrl();
        String base =
                authServerUrl.endsWith("/")
                        ? authServerUrl.substring(0, authServerUrl.length() - 1)
                        : authServerUrl;
        return base + REALMS_PATH + REALM;
    }

    /** URL of the realm's OpenID Connect discovery document. */
    public static String discoveryUrl() {
        return issuer() + DISCOVERY_PATH;
    }

    /**
     * Trust-list properties pointing the {@code primary} provider at this realm.
     *
     * <p>The role mapping is overridden because the realm names its roles {@code dataspace-*} while
     * {@code application.yml} defaults to {@code consent-*}, and the audience is pinned here
     * because this fixture owns the realm that mints it. Claim paths and clock skew are inherited:
     * their defaults were chosen for Keycloak's own token shape, which is worth exercising.
     */
    public static Map<String, String> keycloakProperties() {
        String prefix = IdentityProviderConfiguration.PREFIX + "." + PROVIDER_NAME + ".";
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(prefix + "issuer", issuer());
        properties.put(prefix + "discovery-url", discoveryUrl());
        properties.put(prefix + "audience", AUDIENCE);
        properties.put(prefix + "role-mapping.user", RAW_USER_ROLE);
        properties.put(prefix + "role-mapping.participant", RAW_PARTICIPANT_ROLE);
        properties.put(prefix + "role-mapping.catalog", RAW_CATALOG_ROLE);
        return properties;
    }

    /** A freshly issued access token for {@code principal}, signed by the realm's active key. */
    public static String accessToken(RealmPrincipal principal) {
        Map<String, String> form =
                Map.of(
                        "grant_type", "password",
                        "client_id", TEST_CLIENT_ID,
                        "client_secret", TEST_CLIENT_SECRET,
                        "username", principal.username,
                        "password", TEST_PASSWORD);
        Map<String, Object> response = postForm(URI.create(issuer() + TOKEN_PATH), form);
        Object token = response.get("access_token");
        if (!(token instanceof String accessToken)) {
            throw new IllegalStateException(
                    "Keycloak returned no access_token for " + principal.username);
        }
        return accessToken;
    }

    /**
     * The {@code sub} claim of a token, which is also the identifier a user is provisioned under.
     */
    public static String subject(String accessToken) {
        try {
            return SignedJWT.parse(accessToken).getJWTClaimsSet().getSubject();
        } catch (ParseException e) {
            throw new IllegalArgumentException("Not a parseable signed JWT", e);
        }
    }

    /** The {@code kid} from a token's JOSE header, identifying the key that signed it. */
    public static String signingKeyId(String accessToken) {
        try {
            return SignedJWT.parse(accessToken).getHeader().getKeyID();
        } catch (ParseException e) {
            throw new IllegalArgumentException("Not a parseable signed JWT", e);
        }
    }

    /**
     * Adds a new RSA signing key to the realm at a priority above the built-in one, so tokens
     * issued from now on are signed by it. The previous key stays published in the JWK Set, which
     * is what keeps already-issued tokens verifiable.
     *
     * <p>Keycloak does not enforce unique component names, and a second provider at the same
     * priority leaves which key signs undecided, so the caller must pass a name unique to the run
     * and hand the returned id to {@link #deleteRealmComponent(String)} when done.
     *
     * @param componentName name for the new key provider
     * @return the id Keycloak assigned to the created key provider
     */
    public static String rotateRealmSigningKey(String componentName) {
        String body =
                """
                {"name":"%s","providerId":"rsa-generated",\
                "providerType":"org.keycloak.keys.KeyProvider","parentId":"%s",\
                "config":{"priority":["%s"],"algorithm":["RS256"],\
                "enabled":["true"],"active":["true"]}}"""
                        .formatted(componentName, realmId(), ROTATED_KEY_PRIORITY);
        URI components = URI.create(adminBaseUrl() + "/admin/realms/" + REALM + "/components");
        HttpRequest request =
                HttpRequest.newBuilder(components)
                        .timeout(HTTP_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + adminToken())
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != HTTP_CREATED) {
            throw new IllegalStateException(
                    "Creating key provider "
                            + componentName
                            + " failed with "
                            + response.statusCode()
                            + ": "
                            + response.body());
        }
        return createdComponentId(response);
    }

    /**
     * Removes a realm component, restoring the realm to its imported state.
     *
     * <p>Tolerates a component that is already gone, so cleanup never masks the failure that left
     * it behind.
     *
     * @param componentId id returned by {@link #rotateRealmSigningKey(String)}
     */
    public static void deleteRealmComponent(String componentId) {
        URI component =
                URI.create(
                        adminBaseUrl() + "/admin/realms/" + REALM + "/components/" + componentId);
        HttpRequest request =
                HttpRequest.newBuilder(component)
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", "Bearer " + adminToken())
                        .DELETE()
                        .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= HTTP_BAD_REQUEST && response.statusCode() != HTTP_NOT_FOUND) {
            throw new IllegalStateException(
                    "Deleting component "
                            + componentId
                            + " failed with "
                            + response.statusCode()
                            + ": "
                            + response.body());
        }
    }

    /**
     * The component id from a creation response, which Keycloak returns only as the last segment of
     * the {@code Location} header.
     */
    private static String createdComponentId(HttpResponse<String> response) {
        String location =
                response.headers()
                        .firstValue("Location")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Keycloak created a component without a Location"
                                                        + " header"));
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /**
     * The realm's internal identifier, which a key provider component must name as its {@code
     * parentId}. Keycloak assigns a UUID on import rather than reusing {@link #REALM}, and accepts
     * a component whose {@code parentId} matches neither — returning 201 while attaching the key to
     * nothing — so the identifier has to be read back rather than assumed.
     */
    private static String realmId() {
        URI realm = URI.create(adminBaseUrl() + "/admin/realms/" + REALM);
        HttpRequest request =
                HttpRequest.newBuilder(realm)
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", "Bearer " + adminToken())
                        .GET()
                        .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= HTTP_BAD_REQUEST) {
            throw new IllegalStateException(
                    "Reading realm " + REALM + " failed with " + response.statusCode());
        }
        try {
            Object id = JSONObjectUtils.parse(response.body()).get("id");
            if (!(id instanceof String realmId)) {
                throw new IllegalStateException("Keycloak returned no realm id for " + REALM);
            }
            return realmId;
        } catch (ParseException e) {
            throw new IllegalStateException("Keycloak returned a non-JSON realm", e);
        }
    }

    /** Container base URL without a trailing slash. */
    private static String adminBaseUrl() {
        String url = KEYCLOAK.getAuthServerUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Access token for the container's bootstrap admin, scoped to the master realm. */
    private static String adminToken() {
        Map<String, String> form =
                Map.of(
                        "grant_type",
                        "password",
                        "client_id",
                        "admin-cli",
                        "username",
                        KEYCLOAK.getAdminUsername(),
                        "password",
                        KEYCLOAK.getAdminPassword());
        URI tokenEndpoint = URI.create(adminBaseUrl() + REALMS_PATH + "master" + TOKEN_PATH);
        Object token = postForm(tokenEndpoint, form).get("access_token");
        if (!(token instanceof String adminToken)) {
            throw new IllegalStateException("Keycloak returned no admin access_token");
        }
        return adminToken;
    }

    /** POSTs {@code form} as {@code application/x-www-form-urlencoded} and parses the JSON body. */
    private static Map<String, Object> postForm(URI endpoint, Map<String, String> form) {
        StringBuilder encoded = new StringBuilder();
        form.forEach(
                (key, value) -> {
                    if (!encoded.isEmpty()) {
                        encoded.append('&');
                    }
                    encoded.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                            .append('=')
                            .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                });
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(endpoint)
                        .timeout(HTTP_TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        encoded.toString(), StandardCharsets.UTF_8));
        HttpResponse<String> response = send(builder.build());
        if (response.statusCode() >= HTTP_BAD_REQUEST) {
            throw new IllegalStateException(
                    "POST "
                            + endpoint
                            + " failed with "
                            + response.statusCode()
                            + ": "
                            + response.body());
        }
        try {
            return JSONObjectUtils.parse(response.body());
        } catch (ParseException e) {
            throw new IllegalStateException("Keycloak returned a non-JSON body", e);
        }
    }

    /** Sends {@code request}, translating the checked failures into unchecked ones. */
    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Request to Keycloak failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Keycloak", e);
        }
    }

    private KeycloakTestResource() {}
}
