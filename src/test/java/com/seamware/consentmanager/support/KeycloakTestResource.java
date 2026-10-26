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
 * Testcontainers Keycloak fixture issuing real tokens, and the trust-list properties naming it. A
 * JVM-wide singleton; {@link #rotateRealmSigningKey(String)} only adds a key, so held tokens stay
 * valid. Tests reach it through {@link KeycloakAndPostgresTestResource}.
 */
public final class KeycloakTestResource {

    /** Pinned image, so a library upgrade cannot silently change the IDP under test. */
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.1";

    private static final String REALM_IMPORT_FILE = "/keycloak/consent-manager-realm.json";

    public static final String REALM = "consent-manager-test";

    /**
     * Audience this realm's tokens carry, which the trust list entry must demand. Must match the
     * realm's audience mapper, or every Keycloak-issued token gets a bare 401.
     */
    public static final String AUDIENCE = "consent-manager";

    /**
     * Identifier in {@link RealmPrincipal#PARTICIPANT}'s {@code participant_id} claim. Tests using
     * that client must commit a {@code participants} row with it, or get 403.
     */
    public static final String PARTICIPANT_IDENTIFIER = "urn:test:participant:keycloak";

    /** Confidential client in the imported realm with the password grant enabled. */
    private static final String TEST_CLIENT_ID = "consent-manager-test-client";

    private static final String TEST_CLIENT_SECRET = "test-client-secret";

    /** Password shared by every {@link RealmPrincipal}. */
    private static final String TEST_PASSWORD = "test-password";

    /** Trust-list entry this fixture overrides; the name is fixed by {@code application.yml}. */
    private static final String PROVIDER_NAME = "primary";

    private static final String REALMS_PATH = "/realms/";

    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    private static final String TOKEN_PATH = "/protocol/openid-connect/token";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static final int HTTP_CREATED = 201;

    /** Lowest HTTP status counting as a failure. */
    private static final int HTTP_BAD_REQUEST = 400;

    private static final int HTTP_NOT_FOUND = 404;

    /**
     * Priority of a rotated-in key: above the built-in key's 100, so it signs without removing the
     * old key.
     */
    private static final String ROTATED_KEY_PRIORITY = "200";

    /** Shared container, started once per JVM; Ryuk stops it when the JVM exits. */
    @SuppressWarnings("resource")
    private static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer(KEYCLOAK_IMAGE).withRealmImportFile(REALM_IMPORT_FILE);

    static {
        KEYCLOAK.start();
    }

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    /**
     * Realm users, one per role, each holding exactly one realm role. Password-grant users, since
     * {@code serviceAccountsEnabled} breaks Keycloak's realm import.
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

    private static final String RAW_PARTICIPANT_ROLE = "dataspace-participant";

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
     * Trust-list properties pointing the {@code primary} provider at this realm. Overrides the role
     * mapping ({@code dataspace-*}) and audience; claim paths are inherited.
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

    /** A token's {@code sub} claim, also the identifier a user is provisioned under. */
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
     * Adds an RSA signing key above the built-in one, so new tokens are signed by it; the old key
     * stays published. Pass a run-unique name and delete the returned component id after.
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

    /** Removes a realm component; tolerates one already gone, so cleanup never masks a failure. */
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

    /** The created component id: the last segment of Keycloak's {@code Location} header. */
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
     * The realm's internal UUID, needed as a key provider's {@code parentId}. Keycloak accepts a
     * wrong {@code parentId} with 201 and attaches the key to nothing.
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

    /** Bootstrap admin's access token, scoped to the master realm. */
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

    /** Sends {@code request}, rethrowing checked failures unchecked. */
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
