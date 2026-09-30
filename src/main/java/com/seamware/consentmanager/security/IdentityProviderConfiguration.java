package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Consent Manager's own settings for one trusted OpenID Connect provider.
 *
 * <p>This type deliberately holds <strong>only what Micronaut Security does not already
 * provide</strong>. A trusted provider is declared to Micronaut as an OpenID Connect client:
 *
 * <pre>{@code
 * micronaut:
 *   security:
 *     oauth2:
 *       clients:
 *         keycloak:                 # <-- the provider name
 *           openid:
 *             issuer: https://keycloak.example.com/realms/dataspace
 * }</pre>
 *
 * <p>From that single property Micronaut fetches the provider's OpenID discovery document, reads
 * its {@code jwks_uri}, and registers a JWKS-backed signature configuration that caches the
 * provider's signing keys and re-fetches them when the cache expires. Issuer, discovery, key
 * retrieval and key caching therefore need no code here. No {@code client-id} or {@code
 * client-secret} is configured: the Consent Manager is a resource server, performs no OAuth2 flow
 * and stores no credentials.
 *
 * <p>What Micronaut does <em>not</em> offer is per-provider claim handling — its audience and roles
 * settings are single, global values and its roles lookup cannot follow a nested claim path. Those
 * gaps are what this class fills, under a matching name:
 *
 * <pre>{@code
 * consent-manager:
 *   identity-providers:
 *     keycloak:                     # <-- same name as the OIDC client above
 *       audience: consent-manager
 *       clock-skew: 30s
 *       claims:
 *         user-identifier: sub
 *         participant-identifier: participant_id
 *         roles: realm_access.roles
 *       role-mapping:
 *         user: consent-user
 *         participant: consent-participant
 *         catalog: consent-catalog
 * }</pre>
 *
 * <p>Entries are keyed by <strong>name</strong> rather than by list index. A named key binds from
 * an environment variable ({@code CONSENT_MANAGER_IDENTITY_PROVIDERS_KEYCLOAK_AUDIENCE}), whereas
 * an indexed one does not: Micronaut registers {@code CONSENT_MANAGER_IDENTITY_PROVIDERS_1_ISSUER}
 * verbatim as {@code CONSENT_MANAGER_IDENTITY_PROVIDERS[1]ISSUER}, which never normalises to a
 * bindable property, so an operator adding a second issuer that way would be silently ignored.
 *
 * <p>The trust list is read <strong>once at startup</strong> and is immutable for the lifetime of
 * the process — no runtime reload, no admin endpoint, no auto-registration. Adding, removing or
 * re-pointing a provider requires a configuration change and a restart.
 *
 * <p>Every value is validated at startup. A malformed entry aborts context creation with a message
 * naming the offending property, because a resource server that silently trusts a misconfigured
 * issuer is worse than one that refuses to start.
 *
 * @see IdentityProviderRegistryValidator
 */
@EachProperty(IdentityProviderConfiguration.PREFIX)
public class IdentityProviderConfiguration {

    /** Configuration prefix holding the per-provider claim settings. */
    public static final String PREFIX = "consent-manager.identity-providers";

    /**
     * Configuration prefix under which each trusted provider's issuer is declared.
     *
     * <p>Owned by Micronaut Security OAuth2, not by this application. Referenced here so that
     * startup diagnostics can point an operator at the exact property they need to add.
     */
    public static final String OIDC_CLIENTS_PREFIX = "micronaut.security.oauth2.clients";

    /**
     * Wildcard form of a single entry's property path.
     *
     * <p>Used to build Bean Validation messages that name the offending property in the
     * lower-case-kebab form an operator actually writes in configuration, rather than the Java
     * field name the default constraint message would report.
     */
    private static final String ENTRY_PATH = PREFIX + ".*";

    /**
     * Default tolerance applied to {@code exp}, {@code iat} and {@code nbf} when {@code clock-skew}
     * is not configured.
     *
     * <p>Micronaut's own expiration and not-before validators compare against the current instant
     * with no leeway, so this tolerance has no equivalent in the framework.
     */
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);

    /** Default claim carrying the user identifier, per the published token contract. */
    public static final String DEFAULT_USER_IDENTIFIER_CLAIM = "sub";

    private final String name;

    private String audience;
    private Duration clockSkew = DEFAULT_CLOCK_SKEW;
    private ClaimsConfiguration claims = new ClaimsConfiguration();
    private Map<String, String> roleMapping = Map.of();

    private Map<Role, String> resolvedRoleMapping = Map.of();

    /**
     * Creates the settings bean for one named trust-list entry.
     *
     * @param name the configuration key of this entry, which must match the name of a {@code
     *     micronaut.security.oauth2.clients.<name>} OpenID Connect client
     */
    public IdentityProviderConfiguration(@Parameter String name) {
        this.name = name;
    }

    /**
     * Returns the name of this entry, which is also the name of its OpenID Connect client.
     *
     * @return the configuration key, for example {@code keycloak}
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the fully-qualified configuration path of this entry, for use in diagnostics.
     *
     * @return for example {@code consent-manager.identity-providers.keycloak}
     */
    public String getPropertyPath() {
        return PREFIX + "." + name;
    }

    /**
     * Returns the audience this service expects to find in the token's {@code aud} claim.
     *
     * <p>Micronaut's {@code micronaut.security.token.jwt.claims-validators.audience} is a single
     * global string shared by every issuer, so it cannot express "issuer A must address audience X
     * and issuer B audience Y". This per-provider value can.
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
     * <p>Keys are {@link Role} names and values are the raw role strings this provider emits.
     * Prefer {@link #getResolvedRoleMapping()}, which is type-safe and validated.
     *
     * @return an unmodifiable view of the configured role mapping; values may be {@code null} when
     *     a key was written with no value
     */
    public Map<String, String> getRoleMapping() {
        return roleMapping;
    }

    /**
     * Sets the raw role mapping.
     *
     * <p>The defensive copy is deliberately null-tolerant. A YAML entry such as {@code
     * role-mapping:\n user:} binds the key with a {@code null} value; {@link Map#copyOf} would
     * reject that with a bare {@link NullPointerException} from inside the binder, whereas this
     * copy lets {@link #resolveRoleMapping()} report it as a named, operator-readable property
     * error.
     *
     * @param roleMapping mapping from {@link Role} name to the raw role string this provider emits;
     *     {@code null} is treated as an empty mapping and rejected at validation time
     */
    public void setRoleMapping(Map<String, String> roleMapping) {
        this.roleMapping =
                roleMapping == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(roleMapping));
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
     * only what those constraints cannot express: non-negative durations and the conversion of the
     * raw role mapping. Throws rather than logging: a resource server with a malformed trust list
     * must not start.
     *
     * @throws ConfigurationException if any value is malformed, naming the offending property
     */
    @PostConstruct
    public void validate() {
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
                            + Arrays.toString(Role.values())
                            + " to a role string issued by this provider");
        }
        Map<Role, String> resolved = new EnumMap<>(Role.class);
        Map<Role, String> keyForRole = new EnumMap<>(Role.class);
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
                                                                    + Arrays.toString(
                                                                            Role.values())));
                    if (rawRole == null || rawRole.isBlank()) {
                        throw new ConfigurationException(
                                getPropertyPath()
                                        + ".role-mapping."
                                        + configuredRole
                                        + " must not be blank");
                    }
                    String trimmedRawRole = rawRole.strip();
                    // Defence in depth: Micronaut normalises configuration keys to lower-case
                    // kebab, so two distinct keys cannot currently resolve to the same Role. The
                    // check guards a future binding path that does not normalise, and costs
                    // nothing.
                    String previousKey = keyForRole.put(role, configuredRole);
                    if (previousKey != null) {
                        throw new ConfigurationException(
                                getPropertyPath()
                                        + ".role-mapping names role "
                                        + role
                                        + " twice, as '"
                                        + previousKey
                                        + "' and '"
                                        + configuredRole
                                        + "'; role-mapping keys are compared ignoring case and a"
                                        + " single role must be mapped exactly once. Write the keys"
                                        + " in lower case so that an environment-variable override"
                                        + " replaces the configured value instead of adding a"
                                        + " second entry.");
                    }
                    resolved.put(role, trimmedRawRole);
                    Role previousOwner = seenRawRoles.put(trimmedRawRole, role);
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
     * The claim names this provider uses for identifiers and roles.
     *
     * <p>Each value may be a dot-separated nested path, for example {@code realm_access.roles}.
     * Micronaut's {@code micronaut.security.token.roles-name} is a single global key looked up with
     * a plain map access, so it can express neither a nested path nor a per-provider difference.
     */
    @ConfigurationProperties("claims")
    public static class ClaimsConfiguration {

        private String userIdentifier = DEFAULT_USER_IDENTIFIER_CLAIM;
        private String participantIdentifier;
        private String roles;

        /**
         * Returns the claim carrying the opaque user identifier.
         *
         * <p>The value is treated as opaque: it may be an email address, a DID, a URI or a hash of
         * an EUDI Wallet PID.
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
         * @return the claim path, for example {@code participant_id}
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
