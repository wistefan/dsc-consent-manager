package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Consent Manager's settings for one trusted OpenID Connect provider.
 *
 * <p>The trust list lives entirely in this service's own configuration namespace, one block per
 * provider:
 *
 * <pre>{@code
 * consent-manager:
 *   identity-providers:
 *     keycloak:                     # <-- the provider name
 *       issuer: https://keycloak.example.com/realms/dataspace
 *       discovery-url: https://keycloak.example.com/realms/dataspace/.well-known/openid-configuration
 *       audience: consent-manager
 *       clock-skew: 30s
 *       claims:                     # each name is a list of segments
 *         user-identifier: [sub]
 *         participant-identifier: [participant_id]
 *         roles: [realm_access, roles]
 *       role-mapping:
 *         user: consent-user
 *         participant: consent-participant
 *         catalog: consent-catalog
 * }</pre>
 *
 * <p>No {@code client-id} and no {@code client-secret} appear anywhere: the Consent Manager is a
 * resource server. It runs no OAuth2 flow, issues no token and stores no credential.
 *
 * <p><strong>Why these settings are not declared as a Micronaut Security OpenID client.</strong>
 * Declaring {@code micronaut.security.oauth2.clients.<name>.openid.issuer} would make the framework
 * fetch the discovery document and register a JWKS-backed signature configuration, which looks like
 * the whole job. It is not: verified against micronaut-security 5.4.0, that path never compares the
 * discovered issuer with the configured one (AC 2), never retries a failed discovery (AC 3), has no
 * per-provider JWKS TTL, never re-fetches on an unknown {@code kid} (AC 13/14), and - decisively -
 * does not bind a key set to an issuer, so with two providers configured either can mint tokens
 * that authenticate as the other. The signature primitives underneath it, {@code
 * com.nimbusds:nimbus-jose-jwt}, are reused directly instead; they supply key-set caching,
 * rate-limited refetch and outage tolerance per issuer. The full comparison is recorded in {@code
 * docs/adr/0002-own-identity-provider-registry-on-nimbus.md}.
 *
 * <p>Entries are keyed by <strong>name</strong> rather than by list index. A named key binds from
 * an environment variable ({@code CONSENT_MANAGER_IDENTITY_PROVIDERS_KEYCLOAK_AUDIENCE}), whereas
 * an indexed one does not: Micronaut registers {@code CONSENT_MANAGER_IDENTITY_PROVIDERS_1_ISSUER}
 * verbatim as {@code CONSENT_MANAGER_IDENTITY_PROVIDERS[1]ISSUER}, which never normalises to a
 * bindable property, so an operator adding a second issuer that way would be silently ignored. This
 * is the one documented deviation from the implementation plan's {@code @EachProperty(list =
 * true)}: the plan's goal is a trust list of variable size, which a named map delivers while
 * staying configurable the way every other setting in this service is.
 *
 * <p>The trust list is read <strong>once at startup</strong> and is immutable for the lifetime of
 * the process - no runtime reload, no admin endpoint, no auto-registration. Adding, removing or
 * re-pointing a provider requires a configuration change and a restart. Only per-provider metadata
 * discovered for that fixed set (the {@code jwks_uri} and the cached signing keys) changes
 * afterwards, which is what lets a key rotation be picked up without a restart.
 *
 * <p>Every value is validated at startup. A malformed entry aborts context creation with a message
 * naming the offending property, because a resource server that silently trusts a misconfigured
 * issuer is worse than one that refuses to start. {@code @EachProperty} ignores an unknown key
 * silently, so {@link IdentityProviderRegistryValidator} additionally rejects any key below an
 * entry that this class does not read.
 *
 * @see IdentityProviderRegistryValidator
 */
@EachProperty(IdentityProviderConfiguration.PREFIX)
public class IdentityProviderConfiguration {

    /** Configuration prefix holding the trust list, one sub-key per trusted provider. */
    public static final String PREFIX = "consent-manager.identity-providers";

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
    public static final List<String> DEFAULT_USER_IDENTIFIER_CLAIM = List.of("sub");

    /**
     * Whether a provider may be reached over cleartext {@code http} when it does not say otherwise.
     *
     * <p>{@code false}: the discovery document is this service's trust anchor. It names the issuer
     * that the byte-for-byte check compares against and the {@code jwks_uri} the signing keys are
     * fetched from, so an on-path attacker who can rewrite it chooses both - the issuer check
     * agrees with itself and the forged keys validate forged tokens. Over TLS that attack needs a
     * certificate for the provider's host; over cleartext it needs nothing. The default therefore
     * refuses {@code http} and an operator who wants it - a local Keycloak, a WireMock stub - opts
     * in per provider and is warned at startup.
     */
    public static final boolean DEFAULT_ALLOW_INSECURE_TRANSPORT = false;

    private final String name;

    private String issuer;
    private String discoveryUrl;
    private String audience;
    private Duration clockSkew = DEFAULT_CLOCK_SKEW;
    private ClaimsConfiguration claims = new ClaimsConfiguration();
    private Map<String, String> roleMapping = Map.of();
    private boolean allowInsecureTransport = DEFAULT_ALLOW_INSECURE_TRANSPORT;

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
     * Returns the issuer this provider stamps into the {@code iss} claim of every token it mints.
     *
     * <p>This is the trust anchor: a token is only ever considered against the provider whose
     * configured issuer matches its {@code iss} exactly, and the value is compared byte-for-byte
     * rather than by suffix or host. It must equal the {@code issuer} field of the provider's
     * discovery document; step 3 verifies that at startup and refuses the provider when they
     * disagree.
     *
     * @return the expected issuer
     */
    @NotBlank(message = ENTRY_PATH + ".issuer must not be blank")
    public String getIssuer() {
        return issuer;
    }

    /**
     * Sets the expected issuer.
     *
     * @param issuer the expected issuer
     */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /**
     * Returns the URL of this provider's OpenID Provider Metadata document.
     *
     * <p>Fetched once at startup to discover the provider's {@code jwks_uri}, then retried with
     * backoff while it is unreachable (step 3). Kept separate from {@link #getIssuer()} because a
     * provider may publish its metadata at a path that is not the issuer plus the well-known
     * suffix, and because the two are compared against each other rather than derived from one
     * another.
     *
     * @return the discovery document URL
     */
    @NotBlank(message = ENTRY_PATH + ".discovery-url must not be blank")
    public String getDiscoveryUrl() {
        return discoveryUrl;
    }

    /**
     * Sets the discovery document URL.
     *
     * @param discoveryUrl the discovery document URL
     */
    public void setDiscoveryUrl(String discoveryUrl) {
        this.discoveryUrl = discoveryUrl;
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
     * <p>Carries no {@code @NotNull}: {@link #setClockSkew(Duration)} substitutes {@link
     * #DEFAULT_CLOCK_SKEW} for a {@code null}, so the field is non-null by construction and such a
     * constraint could never fail. A constraint that cannot fire is worse than none, because the
     * next reader reads it as evidence the case is covered.
     *
     * @return the permitted clock skew, never {@code null}
     */
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
     * Reports whether this provider may be addressed over cleartext {@code http}.
     *
     * <p>See {@link #DEFAULT_ALLOW_INSECURE_TRANSPORT} for why this is off by default. {@code
     * IdentityProviderRegistryValidator} fails startup when an {@code http} issuer or discovery URL
     * is configured without this flag, and warns when it is configured with it.
     *
     * @return {@code true} if an {@code http} issuer and discovery URL are tolerated
     */
    public boolean isAllowInsecureTransport() {
        return allowInsecureTransport;
    }

    /**
     * Sets whether cleartext {@code http} is tolerated for this provider.
     *
     * @param allowInsecureTransport {@code true} to permit {@code http}; intended for local
     *     development and tests only
     */
    public void setAllowInsecureTransport(boolean allowInsecureTransport) {
        this.allowInsecureTransport = allowInsecureTransport;
    }

    /**
     * Returns the per-provider claim names used to extract identifiers and roles.
     *
     * <p>Carries no {@code @NotNull} for the same reason as {@link #getClockSkew()}: {@link
     * #setClaims(ClaimsConfiguration)} substitutes a fresh instance for a {@code null}, so the
     * field is non-null by construction. {@code @Valid} remains, because the nested object's own
     * constraints very much can fail.
     *
     * @return the claim configuration, never {@code null}
     */
    @Valid
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
     * <p>Invoked by Micronaut once the bean has been populated, which is <strong>before</strong>
     * its Bean Validation constraints run, not after: {@code DefaultBeanContext.postBeanCreated}
     * calls {@code ValidatedBeanDefinition.validate} only once {@code instantiate} has returned,
     * and the generated bytecode invokes the {@code @PostConstruct} hooks inside that call
     * (verified against micronaut-inject 5.2.8). The values seen here have therefore <em>not</em>
     * been constraint-checked, so anything added to this method must tolerate a {@code null} it
     * would otherwise expect {@code @NotBlank} to have rejected — dereferencing one produces a raw
     * {@code NullPointerException} from inside the binder instead of the property-naming message
     * this class works to produce. The checks below are written accordingly: {@code clockSkew} is
     * never null because its setter guards it, and the role-mapping copy is null-tolerant.
     *
     * <p>This method covers only what the constraints cannot express: non-negative durations and
     * the conversion of the raw role mapping. Throws rather than logging: a resource server with a
     * malformed trust list must not start.
     *
     * @throws ConfigurationException if any value is malformed, naming the offending property
     */
    @PostConstruct
    public void validate() {
        requireNonNegative("clock-skew", clockSkew);
        claims.validateSegments(getPropertyPath());
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
     * <p>Each name is a <strong>list of segments</strong> rather than a dotted string: {@code
     * [realm_access, roles]} names the {@code roles} member of the nested {@code realm_access}
     * object, while the single segment {@code [https://example.com/roles]} names a top-level claim
     * that merely contains dots. Configuring the reading this way fixes it at startup, so no claim
     * a token carries can change how the operator's configured name is interpreted. A scalar binds
     * as a one-element list, and a comma-separated scalar binds as its segments, which is how an
     * environment variable supplies a nested path ({@code IDP_CLAIM_ROLES=realm_access,roles}).
     *
     * <p>Micronaut's {@code micronaut.security.token.roles-name} is a single global key looked up
     * with a plain map access, so it can express neither a nested path nor a per-provider
     * difference.
     */
    @ConfigurationProperties("claims")
    public static class ClaimsConfiguration {

        private List<String> userIdentifier = DEFAULT_USER_IDENTIFIER_CLAIM;
        private List<String> participantIdentifier = List.of();
        private List<String> roles = List.of();

        /**
         * Returns the segments naming the claim that carries the opaque user identifier.
         *
         * <p>The value is treated as opaque: it may be an email address, a DID, a URI or a hash of
         * an EUDI Wallet PID.
         *
         * @return the claim-name segments, defaulting to {@code [sub]}
         */
        @NotEmpty(message = ENTRY_PATH + ".claims.user-identifier must name at least one segment")
        public List<String> getUserIdentifier() {
            return userIdentifier;
        }

        /**
         * Sets the segments naming the claim that carries the opaque user identifier.
         *
         * @param userIdentifier the claim-name segments; {@code null} or empty restores the default
         */
        public void setUserIdentifier(List<String> userIdentifier) {
            this.userIdentifier =
                    userIdentifier == null || userIdentifier.isEmpty()
                            ? DEFAULT_USER_IDENTIFIER_CLAIM
                            : List.copyOf(userIdentifier);
        }

        /**
         * Returns the segments naming the claim that carries the opaque participant identifier.
         *
         * @return the claim-name segments, for example {@code [participant_id]}
         */
        @NotEmpty(
                message =
                        ENTRY_PATH
                                + ".claims.participant-identifier must name at least one segment")
        public List<String> getParticipantIdentifier() {
            return participantIdentifier;
        }

        /**
         * Sets the segments naming the claim that carries the opaque participant identifier.
         *
         * @param participantIdentifier the claim-name segments
         */
        public void setParticipantIdentifier(List<String> participantIdentifier) {
            this.participantIdentifier =
                    participantIdentifier == null ? List.of() : List.copyOf(participantIdentifier);
        }

        /**
         * Returns the segments naming the claim that carries the token's raw role strings.
         *
         * @return the claim-name segments, for example {@code [realm_access, roles]}
         */
        @NotEmpty(message = ENTRY_PATH + ".claims.roles must name at least one segment")
        public List<String> getRoles() {
            return roles;
        }

        /**
         * Sets the segments naming the claim that carries the token's raw role strings.
         *
         * @param roles the claim-name segments
         */
        public void setRoles(List<String> roles) {
            this.roles = roles == null ? List.of() : List.copyOf(roles);
        }

        /**
         * Rejects a blank segment, which would otherwise name no claim and silently resolve to
         * empty for every token - indistinguishable from a provider that stopped emitting the
         * claim.
         *
         * @param propertyPath the configuration path of the owning entry, for the message
         * @throws ConfigurationException if any configured name contains a blank segment
         */
        void validateSegments(String propertyPath) {
            requireNoBlankSegment(propertyPath, "user-identifier", userIdentifier);
            requireNoBlankSegment(propertyPath, "participant-identifier", participantIdentifier);
            requireNoBlankSegment(propertyPath, "roles", roles);
        }

        /**
         * Checks one configured claim name for blank segments.
         *
         * @param propertyPath the configuration path of the owning entry
         * @param property the claim key being checked
         * @param segments the configured segments
         * @throws ConfigurationException if a segment is {@code null} or blank
         */
        private static void requireNoBlankSegment(
                String propertyPath, String property, List<String> segments) {
            if (segments.stream().anyMatch(segment -> segment == null || segment.isBlank())) {
                throw new ConfigurationException(
                        propertyPath
                                + ".claims."
                                + property
                                + " must not contain a blank segment, but was "
                                + segments);
            }
        }
    }
}
