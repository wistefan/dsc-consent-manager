package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration for a single trusted OpenID Connect provider.
 *
 * <p>One bean is created per entry of the {@code consent-manager.identity-providers} configuration
 * list. Together these beans form the service's <em>trust list</em>: the set of issuers whose
 * access tokens the Consent Manager accepts. The list is read <strong>once at startup</strong> and
 * is immutable for the lifetime of the process — there is no runtime reload, no admin endpoint and
 * no auto-registration. Adding, removing or re-pointing a provider requires a configuration change
 * and a restart.
 *
 * <p>Example configuration:
 *
 * <pre>{@code
 * consent-manager:
 *   identity-providers:
 *     - issuer: https://keycloak.example.com/realms/dataspace
 *       discovery-url: https://keycloak.example.com/realms/dataspace/.well-known/openid-configuration
 *       audience: consent-manager
 *       jwks-cache-ttl: 1h
 *       clock-skew: 30s
 *       claims:
 *         user-identifier: sub
 *         participant-identifier: participant_id
 *         roles: realm_access.roles
 *       role-mapping:
 *         USER: consent-user
 *         PARTICIPANT: consent-participant
 *         CATALOG: consent-catalog
 * }</pre>
 *
 * <p>Every value is validated at startup. A malformed entry aborts context creation with a message
 * naming the offending property, because a resource server that silently trusts a misconfigured
 * issuer is worse than one that refuses to start.
 *
 * @see IdentityProviderRegistryValidator
 */
@EachProperty(value = IdentityProviderConfiguration.PREFIX, list = true)
public class IdentityProviderConfiguration {

    /** Configuration prefix holding the trust list. */
    public static final String PREFIX = "consent-manager.identity-providers";

    /**
     * Wildcard form of a single trust-list entry's property path.
     *
     * <p>Used to build Bean Validation messages that name the offending property in the
     * lower-case-kebab form an operator actually writes in configuration, rather than the Java
     * field name the default constraint message would report.
     */
    private static final String ENTRY_PATH = PREFIX + "[*]";

    /** Default lifetime of a cached JWKS document when {@code jwks-cache-ttl} is not configured. */
    public static final Duration DEFAULT_JWKS_CACHE_TTL = Duration.ofHours(1);

    /**
     * Default tolerance applied to {@code exp}, {@code iat} and {@code nbf} when {@code clock-skew}
     * is not configured.
     */
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);

    /** Default claim carrying the user identifier, per the published token contract. */
    public static final String DEFAULT_USER_IDENTIFIER_CLAIM = "sub";

    private final int index;

    private String issuer;
    private String discoveryUrl;
    private String audience;
    private Duration jwksCacheTtl = DEFAULT_JWKS_CACHE_TTL;
    private Duration clockSkew = DEFAULT_CLOCK_SKEW;
    private ClaimsConfiguration claims = new ClaimsConfiguration();
    private Map<String, String> roleMapping = Map.of();

    private Map<Role, String> resolvedRoleMapping = Map.of();

    /**
     * Creates a configuration bean for one entry of the trust list.
     *
     * @param index the zero-based position of this entry within {@code
     *     consent-manager.identity-providers}, supplied by Micronaut
     */
    public IdentityProviderConfiguration(@Parameter int index) {
        this.index = index;
    }

    /**
     * Returns the zero-based position of this entry within the configured trust list.
     *
     * @return the configuration list index
     */
    public int getIndex() {
        return index;
    }

    /**
     * Returns the fully-qualified configuration path of this entry, for use in diagnostics.
     *
     * @return for example {@code consent-manager.identity-providers[0]}
     */
    public String getPropertyPath() {
        return PREFIX + "[" + index + "]";
    }

    /**
     * Returns the expected {@code iss} claim value.
     *
     * <p>Compared byte-for-byte against both the token's {@code iss} and the {@code issuer} field
     * of the provider's discovery document.
     *
     * @return the absolute issuer URL
     */
    @NotBlank(message = ENTRY_PATH + ".issuer must not be blank")
    public String getIssuer() {
        return issuer;
    }

    /**
     * Sets the expected {@code iss} claim value.
     *
     * @param issuer the absolute issuer URL
     */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /**
     * Returns the OpenID Connect discovery endpoint used to locate this provider's {@code
     * jwks_uri}.
     *
     * @return the absolute discovery document URL
     */
    @NotBlank(message = ENTRY_PATH + ".discovery-url must not be blank")
    public String getDiscoveryUrl() {
        return discoveryUrl;
    }

    /**
     * Sets the OpenID Connect discovery endpoint.
     *
     * @param discoveryUrl the absolute discovery document URL
     */
    public void setDiscoveryUrl(String discoveryUrl) {
        this.discoveryUrl = discoveryUrl;
    }

    /**
     * Returns the audience this service expects to find in the token's {@code aud} claim.
     *
     * @return the expected audience
     */
    @NotBlank(message = ENTRY_PATH + ".audience must not be blank")
    public String getAudience() {
        return audience;
    }

    /**
     * Sets the expected audience.
     *
     * @param audience the expected audience
     */
    public void setAudience(String audience) {
        this.audience = audience;
    }

    /**
     * Returns how long a fetched JWKS document stays valid before it is refreshed.
     *
     * @return the JWKS cache time-to-live, never {@code null}
     */
    @NotNull(message = ENTRY_PATH + ".jwks-cache-ttl must not be null")
    public Duration getJwksCacheTtl() {
        return jwksCacheTtl;
    }

    /**
     * Sets the JWKS cache time-to-live.
     *
     * @param jwksCacheTtl the time-to-live; {@code null} restores {@link #DEFAULT_JWKS_CACHE_TTL}
     */
    public void setJwksCacheTtl(Duration jwksCacheTtl) {
        this.jwksCacheTtl = jwksCacheTtl == null ? DEFAULT_JWKS_CACHE_TTL : jwksCacheTtl;
    }

    /**
     * Returns the tolerance applied when validating time-based claims against this provider.
     *
     * @return the permitted clock skew, never {@code null}
     */
    @NotNull(message = ENTRY_PATH + ".clock-skew must not be null")
    public Duration getClockSkew() {
        return clockSkew;
    }

    /**
     * Sets the permitted clock skew.
     *
     * @param clockSkew the tolerance; {@code null} restores {@link #DEFAULT_CLOCK_SKEW}
     */
    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew == null ? DEFAULT_CLOCK_SKEW : clockSkew;
    }

    /**
     * Returns the per-provider claim names used to extract identifiers and roles.
     *
     * @return the claim configuration, never {@code null}
     */
    @Valid
    @NotNull(message = ENTRY_PATH + ".claims must not be null")
    public ClaimsConfiguration getClaims() {
        return claims;
    }

    /**
     * Sets the per-provider claim names.
     *
     * @param claims the claim configuration; {@code null} restores the defaults
     */
    public void setClaims(ClaimsConfiguration claims) {
        this.claims = claims == null ? new ClaimsConfiguration() : claims;
    }

    /**
     * Returns the raw, unconverted role mapping exactly as bound from configuration.
     *
     * <p>Keys are {@link Role} names (case-insensitive, because Micronaut normalises configuration
     * keys) and values are the raw role strings this provider emits. Prefer {@link
     * #getResolvedRoleMapping()}, which is type-safe and validated.
     *
     * @return an unmodifiable view of the configured role mapping
     */
    public Map<String, String> getRoleMapping() {
        return roleMapping;
    }

    /**
     * Sets the raw role mapping.
     *
     * @param roleMapping mapping from {@link Role} name to the raw role string this provider emits;
     *     {@code null} is treated as an empty mapping and rejected at validation time
     */
    public void setRoleMapping(Map<String, String> roleMapping) {
        this.roleMapping = roleMapping == null ? Map.of() : Map.copyOf(roleMapping);
    }

    /**
     * Returns the validated, type-safe role mapping.
     *
     * <p>Populated during startup validation from {@link #getRoleMapping()}. Each entry maps one of
     * this service's roles to the raw role string the provider emits for it.
     *
     * @return an unmodifiable, non-empty mapping from role to raw role string
     */
    public Map<Role, String> getResolvedRoleMapping() {
        return resolvedRoleMapping;
    }

    /**
     * Validates the bound values and derives the type-safe role mapping.
     *
     * <p>Invoked by Micronaut once the bean has been populated and its Bean Validation constraints
     * have passed, so presence and non-blankness are already guaranteed here. This method covers
     * only what those constraints cannot express: absolute-URL syntax, non-negative durations and
     * the conversion of the raw role mapping. Throws rather than logging: a resource server with a
     * malformed trust list must not start.
     *
     * @throws ConfigurationException if any value is malformed, naming the offending property
     */
    @PostConstruct
    public void validate() {
        requireAbsoluteUrl("issuer", issuer);
        requireAbsoluteUrl("discovery-url", discoveryUrl);
        requireNonNegative("jwks-cache-ttl", jwksCacheTtl);
        requireNonNegative("clock-skew", clockSkew);
        this.resolvedRoleMapping = resolveRoleMapping();
    }

    /**
     * Converts the raw configuration keys into {@link Role} constants.
     *
     * @return the resolved, unmodifiable mapping
     * @throws ConfigurationException if the mapping is empty, names an unknown role, maps a role
     *     twice or maps a role to a blank raw role string
     */
    private Map<Role, String> resolveRoleMapping() {
        if (roleMapping.isEmpty()) {
            throw new ConfigurationException(
                    getPropertyPath()
                            + ".role-mapping must map at least one of "
                            + java.util.Arrays.toString(Role.values())
                            + " to a role string issued by this provider");
        }
        Map<Role, String> resolved = new EnumMap<>(Role.class);
        Map<String, Role> seenRawRoles = new HashMap<>();
        roleMapping.forEach(
                (configuredRole, rawRole) -> {
                    Role role =
                            Role.fromConfiguredName(configuredRole)
                                    .orElseThrow(
                                            () ->
                                                    new ConfigurationException(
                                                            getPropertyPath()
                                                                    + ".role-mapping contains"
                                                                    + " unknown role '"
                                                                    + configuredRole
                                                                    + "'; expected one of "
                                                                    + java.util.Arrays.toString(
                                                                            Role.values())));
                    if (rawRole == null || rawRole.isBlank()) {
                        throw new ConfigurationException(
                                getPropertyPath()
                                        + ".role-mapping."
                                        + configuredRole
                                        + " must not be blank");
                    }
                    String trimmedRawRole = rawRole.strip();
                    if (resolved.put(role, trimmedRawRole) != null) {
                        throw new ConfigurationException(
                                getPropertyPath()
                                        + ".role-mapping maps role "
                                        + role
                                        + " more than once");
                    }
                    Role previousOwner =
                            seenRawRoles.put(trimmedRawRole.toLowerCase(Locale.ROOT), role);
                    if (previousOwner != null) {
                        throw new ConfigurationException(
                                getPropertyPath()
                                        + ".role-mapping maps the provider role '"
                                        + trimmedRawRole
                                        + "' to both "
                                        + previousOwner
                                        + " and "
                                        + role
                                        + "; the mapping would be ambiguous");
                    }
                });
        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Asserts that a configured value is present and not blank.
     *
     * @param property the simple property name, used in the failure message
     * @param value the configured value
     * @throws ConfigurationException if the value is {@code null} or blank
     */
    private void requireNotBlank(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(
                    getPropertyPath() + "." + property + " must not be blank");
        }
    }

    /**
     * Asserts that a configured value parses as an absolute URL.
     *
     * @param property the simple property name, used in the failure message
     * @param value the configured value
     * @throws ConfigurationException if the value is blank or not an absolute URL
     */
    private void requireAbsoluteUrl(String property, String value) {
        requireNotBlank(property, value);
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw new ConfigurationException(
                        getPropertyPath()
                                + "."
                                + property
                                + " must be an absolute URL with a host, but was '"
                                + value
                                + "'");
            }
        } catch (URISyntaxException e) {
            throw new ConfigurationException(
                    getPropertyPath() + "." + property + " is not a valid URL: '" + value + "'", e);
        }
    }

    /**
     * Asserts that a configured duration is not negative.
     *
     * @param property the simple property name, used in the failure message
     * @param value the configured duration
     * @throws ConfigurationException if the duration is negative
     */
    private void requireNonNegative(String property, Duration value) {
        if (value.isNegative()) {
            throw new ConfigurationException(
                    getPropertyPath() + "." + property + " must not be negative, but was " + value);
        }
    }

    /**
     * Claim names used to extract identifiers and roles from this provider's tokens.
     *
     * <p>Every value may be a dot-separated nested path (for example {@code realm_access.roles});
     * resolution of nested paths is implemented by the claim mapper.
     */
    @ConfigurationProperties("claims")
    public static class ClaimsConfiguration {

        private String userIdentifier = DEFAULT_USER_IDENTIFIER_CLAIM;
        private String participantIdentifier;
        private String roles;

        /**
         * Returns the claim carrying the opaque user identifier.
         *
         * @return the claim path, defaulting to {@value #DEFAULT_USER_IDENTIFIER_CLAIM}
         */
        @NotBlank(message = ENTRY_PATH + ".claims.user-identifier must not be blank")
        public String getUserIdentifier() {
            return userIdentifier;
        }

        /**
         * Sets the claim carrying the opaque user identifier.
         *
         * @param userIdentifier the claim path; {@code null} restores the default
         */
        public void setUserIdentifier(String userIdentifier) {
            this.userIdentifier =
                    userIdentifier == null ? DEFAULT_USER_IDENTIFIER_CLAIM : userIdentifier;
        }

        /**
         * Returns the claim carrying the opaque participant identifier.
         *
         * <p>Required even for deployments that only expect user tokens, because the claim name has
         * no defensible default: providers disagree on it. Catalog tokens are exempt from carrying
         * the claim, but the claim <em>name</em> must still be configured for participant tokens.
         *
         * @return the claim path
         */
        @NotBlank(message = ENTRY_PATH + ".claims.participant-identifier must not be blank")
        public String getParticipantIdentifier() {
            return participantIdentifier;
        }

        /**
         * Sets the claim carrying the opaque participant identifier.
         *
         * @param participantIdentifier the claim path
         */
        public void setParticipantIdentifier(String participantIdentifier) {
            this.participantIdentifier = participantIdentifier;
        }

        /**
         * Returns the claim carrying the token's raw role strings.
         *
         * @return the claim path, for example {@code realm_access.roles}
         */
        @NotBlank(message = ENTRY_PATH + ".claims.roles must not be blank")
        public String getRoles() {
            return roles;
        }

        /**
         * Sets the claim carrying the token's raw role strings.
         *
         * @param roles the claim path
         */
        public void setRoles(String roles) {
            this.roles = roles;
        }
    }
}
