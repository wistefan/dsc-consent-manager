package com.seamware.consentmanager.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64;
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
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

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

    private static final String JWKS_PATH = "/protocol/openid-connect/certs";

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
        USER("user-principal", RAW_USER_ROLE),

        /** Holds {@code dataspace-participant}, and carries a {@code participant_id} claim. */
        PARTICIPANT("participant-principal", RAW_PARTICIPANT_ROLE),

        /** Holds {@code dataspace-catalog}, mapped to this service's {@code CATALOG} role. */
        CATALOG("catalog-principal", RAW_CATALOG_ROLE),

        /** Holds {@code dataspace-outsider}, which the trust list maps to no role at all. */
        OUTSIDER("outsider-principal", RAW_OUTSIDER_ROLE);

        private final String username;
        private final String rawRole;

        RealmPrincipal(String username, String rawRole) {
            this.username = username;
            this.rawRole = rawRole;
        }

        /** The {@code username} as declared in the realm import file. */
        public String username() {
            return username;
        }

        /** The realm role string the token carries, before the trust list maps it. */
        public String rawRole() {
            return rawRole;
        }
    }

    /** Raw provider role mapped to {@code USER}, as declared in the realm import file. */
    private static final String RAW_USER_ROLE = "dataspace-user";

    private static final String RAW_PARTICIPANT_ROLE = "dataspace-participant";

    private static final String RAW_CATALOG_ROLE = "dataspace-catalog";

    private static final String RAW_OUTSIDER_ROLE = "dataspace-outsider";

    /** User attribute the realm's protocol mapper copies into the {@code participant_id} claim. */
    private static final String PARTICIPANT_ATTRIBUTE = "participant_id";

    /** Mail domain reserved by RFC 2606, so a created user's address can never be delivered. */
    private static final String CREATED_EMAIL_DOMAIN = "@test.invalid";

    private static final int HTTP_NO_CONTENT = 204;

    /** The realm's issuer identifier, which is also what its tokens carry in {@code iss}. */
    public static String issuer() {
        return issuer(REALM);
    }

    /** The issuer identifier of any realm on this container. */
    public static String issuer(String realm) {
        return adminBaseUrl() + REALMS_PATH + realm;
    }

    /** URL of any realm's OpenID Connect discovery document. */
    public static String discoveryUrl(String realm) {
        return issuer(realm) + DISCOVERY_PATH;
    }

    /**
     * Creates an empty, enabled realm whose highest-priority RS256 signing key is {@code
     * signingKey}, so the caller holds the private half Keycloak signs with. Keycloak's default
     * generated RSA key stays active and published beside it. Returns the {@code kid} Keycloak
     * publishes for the imported key, which it derives from the key and does not take from the
     * caller.
     */
    public static String createRealmSigningWith(String realm, RSAKey signingKey) {
        sendAdminTo(
                URI.create(adminBaseUrl() + "/admin/realms"),
                "POST",
                "{\"realm\":\"%s\",\"enabled\":true}".formatted(realm),
                "Creating realm " + realm,
                HTTP_CREATED);
        String realmId = realmId(realm);
        String privateKey;
        try {
            privateKey = Base64.encode(signingKey.toPrivateKey().getEncoded()).toString();
        } catch (JOSEException e) {
            throw new IllegalArgumentException("Not an RSA private key", e);
        }
        String body =
                """
                {"name":"imported-rsa-key","providerId":"rsa",\
                "providerType":"org.keycloak.keys.KeyProvider","parentId":"%s",\
                "config":{"priority":["%s"],"algorithm":["RS256"],"keyUse":["sig"],\
                "enabled":["true"],"active":["true"],"privateKey":["%s"]}}"""
                        .formatted(realmId, ROTATED_KEY_PRIORITY, privateKey);
        sendAdminTo(
                URI.create(adminBaseUrl() + "/admin/realms/" + realm + "/components"),
                "POST",
                body,
                "Importing a signing key into realm " + realm,
                HTTP_CREATED);
        return publishedKeyId(realm, signingKey);
    }

    /**
     * Deletes a test-created realm; tolerates one already gone, so cleanup never masks a failure.
     */
    public static void deleteRealm(String realm) {
        sendAdminTo(
                URI.create(adminBaseUrl() + "/admin/realms/" + realm),
                "DELETE",
                null,
                "Deleting realm " + realm,
                HTTP_NO_CONTENT,
                HTTP_NOT_FOUND);
    }

    /** The {@code kid} under which {@code realm}'s public JWK Set carries {@code key}. */
    private static String publishedKeyId(String realm, RSAKey key) {
        return publishedKeys(realm).getKeys().stream()
                .filter(published -> published instanceof RSAKey)
                .map(RSAKey.class::cast)
                .filter(published -> published.getModulus().equals(key.getModulus()))
                .map(RSAKey::getKeyID)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Realm " + realm + " does not publish the imported key"));
    }

    /** URL of {@code realm}'s public JWK Set. */
    public static String jwksUrl(String realm) {
        return issuer(realm) + JWKS_PATH;
    }

    /** {@code realm}'s public JWK Set, as any relying party would fetch it. */
    public static JWKSet publishedKeys(String realm) {
        HttpResponse<String> response =
                sendAdminTo(
                        URI.create(jwksUrl(realm)),
                        "GET",
                        null,
                        "Reading the key set of realm " + realm);
        try {
            return JWKSet.parse(response.body());
        } catch (ParseException e) {
            throw new IllegalStateException("Keycloak returned an unparseable key set", e);
        }
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
        return passwordGrant(principal.username);
    }

    /**
     * Creates a uniquely named realm user holding {@code role}'s raw role, its {@code
     * participant_id} attribute set to {@code identifier} when non-null; the imported users are
     * never touched. The supplier issues a fresh token per call. A user's identifier is its {@code
     * sub}, which Keycloak assigns, so read it with {@link #subject(String)}.
     */
    public static Supplier<String> createPrincipal(RealmPrincipal role, String identifier) {
        String username = role.username + "-" + UUID.randomUUID();
        String attributes =
                identifier == null
                        ? "{}"
                        : "{\"%s\":[\"%s\"]}".formatted(PARTICIPANT_ATTRIBUTE, identifier);
        String body =
                """
                {"username":"%s","enabled":true,"emailVerified":true,"email":"%s",\
                "firstName":"Created","lastName":"Principal","requiredActions":[],\
                "attributes":%s,\
                "credentials":[{"type":"password","value":"%s","temporary":false}]}"""
                        .formatted(
                                username,
                                username + CREATED_EMAIL_DOMAIN,
                                attributes,
                                TEST_PASSWORD);
        HttpResponse<String> created =
                sendAdmin("POST", "/users", body, "Creating user " + username, HTTP_CREATED);
        String userId = createdComponentId(created);
        String roleRepresentation =
                sendAdmin("GET", "/roles/" + role.rawRole, null, "Reading role " + role.rawRole)
                        .body();
        sendAdmin(
                "POST",
                "/users/" + userId + "/role-mappings/realm",
                "[" + roleRepresentation + "]",
                "Granting " + role.rawRole + " to " + username,
                HTTP_NO_CONTENT);
        Supplier<String> tokens = () -> passwordGrant(username);
        requireParticipantClaim(tokens.get(), identifier);
        return tokens;
    }

    /** Fails fast when Keycloak dropped the attribute, rather than letting a case get 403. */
    private static void requireParticipantClaim(String token, String identifier) {
        if (identifier == null) {
            return;
        }
        try {
            Object claim = SignedJWT.parse(token).getJWTClaimsSet().getClaim(PARTICIPANT_ATTRIBUTE);
            if (!identifier.equals(claim)) {
                throw new IllegalStateException(
                        "Created principal's token carries participant_id " + claim);
            }
        } catch (ParseException e) {
            throw new IllegalArgumentException("Not a parseable signed JWT", e);
        }
    }

    /**
     * Sends an admin request under this realm and returns the response; fails unless the status is
     * one of {@code accepted}, or below 400 when none is given.
     */
    private static HttpResponse<String> sendAdmin(
            String method, String realmPath, String body, String action, int... accepted) {
        return sendAdminTo(
                URI.create(adminBaseUrl() + "/admin/realms/" + REALM + realmPath),
                method,
                body,
                action,
                accepted);
    }

    /** {@link #sendAdmin} against any admin URL, so a test-created realm can be managed too. */
    private static HttpResponse<String> sendAdminTo(
            URI target, String method, String body, String action, int... accepted) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(target)
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", "Bearer " + adminToken());
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = send(builder.build());
        boolean ok =
                accepted.length == 0
                        ? response.statusCode() < HTTP_BAD_REQUEST
                        : IntStream.of(accepted).anyMatch(code -> code == response.statusCode());
        if (!ok) {
            throw new IllegalStateException(
                    action + " failed with " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    /** A password-grant token for a realm user, via the test client. */
    private static String passwordGrant(String username) {
        Map<String, String> form =
                Map.of(
                        "grant_type", "password",
                        "client_id", TEST_CLIENT_ID,
                        "client_secret", TEST_CLIENT_SECRET,
                        "username", username,
                        "password", TEST_PASSWORD);
        Map<String, Object> response = postForm(URI.create(issuer() + TOKEN_PATH), form);
        Object token = response.get("access_token");
        if (!(token instanceof String accessToken)) {
            throw new IllegalStateException("Keycloak returned no access_token for " + username);
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
        return realmId(REALM);
    }

    /** {@link #realmId()} for any realm on this container. */
    private static String realmId(String name) {
        URI realm = URI.create(adminBaseUrl() + "/admin/realms/" + name);
        HttpRequest request =
                HttpRequest.newBuilder(realm)
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", "Bearer " + adminToken())
                        .GET()
                        .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= HTTP_BAD_REQUEST) {
            throw new IllegalStateException(
                    "Reading realm " + name + " failed with " + response.statusCode());
        }
        try {
            Object id = JSONObjectUtils.parse(response.body()).get("id");
            if (!(id instanceof String realmId)) {
                throw new IllegalStateException("Keycloak returned no realm id for " + name);
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
