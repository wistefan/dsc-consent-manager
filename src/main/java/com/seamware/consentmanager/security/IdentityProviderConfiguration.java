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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings for one trusted OpenID Connect provider, under {@code
 * consent-manager.identity-providers.<name>}; keyed by name so entries bind from env vars.
 *
 * <p>The trust list is read once at startup and immutable thereafter; any malformed value aborts
 * startup naming the property, and {@link IdentityProviderRegistryValidator} rejects unknown keys.
 * No client credentials: this is a resource server. See ADR-0002.
 */
@EachProperty(IdentityProviderConfiguration.PREFIX)
public class IdentityProviderConfiguration {

    /** Configuration prefix holding the trust list, one sub-key per trusted provider. */
    public static final String PREFIX = "consent-manager.identity-providers";

    /** Wildcard entry path, so constraint messages name the kebab-case property, not a field. */
    private static final String ENTRY_PATH = PREFIX + ".*";

    /** Default tolerance on {@code exp}, {@code iat} and {@code nbf} when unconfigured. */
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);

    /** Default claim carrying the user identifier, per the published token contract. */
    public static final List<String> DEFAULT_USER_IDENTIFIER_CLAIM = List.of("sub");

    /** Dotted-path separator, detected only to warn about an unsplit claim name. */
    private static final String LEGACY_PATH_SEPARATOR = ".";

    /** Marker of a URI-shaped claim name, which legitimately holds dots in one segment. */
    private static final String URI_SCHEME_SEPARATOR = "://";

    /** Separator Micronaut splits a scalar property into list elements on. */
    private static final String SEGMENT_SEPARATOR = ",";

    /**
     * Cleartext {@code http} is refused by default: the discovery document is the trust anchor,
     * naming both issuer and {@code jwks_uri}, so an on-path attacker could forge both.
     */
    public static final boolean DEFAULT_ALLOW_INSECURE_TRANSPORT = false;

    private static final Logger LOG = LoggerFactory.getLogger(IdentityProviderConfiguration.class);

    private final String name;

    private String issuer;
    private String discoveryUrl;
    private String audience;
    private Duration clockSkew = DEFAULT_CLOCK_SKEW;
    private ClaimsConfiguration claims = new ClaimsConfiguration();
    private Map<String, String> roleMapping = Map.of();
    private boolean allowInsecureTransport = DEFAULT_ALLOW_INSECURE_TRANSPORT;

    private Map<Role, String> resolvedRoleMapping = Map.of();

    /** Creates the settings bean for one named trust-list entry. */
    public IdentityProviderConfiguration(@Parameter String name) {
        this.name = name;
    }

    /** The entry's configuration key, for example {@code keycloak}. */
    public String getName() {
        return name;
    }

    /** The entry's full configuration path, for diagnostics. */
    public String getPropertyPath() {
        return PREFIX + "." + name;
    }

    /**
     * The expected {@code iss}, the trust anchor, compared byte-for-byte; must equal the discovery
     * document's {@code issuer} or the provider is refused.
     */
    @NotBlank(message = ENTRY_PATH + ".issuer must not be blank")
    public String getIssuer() {
        return issuer;
    }

    /** Sets the expected issuer. */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /**
     * The OpenID Provider Metadata URL, fetched (with retry) to discover {@code jwks_uri}. Separate
     * from the issuer, since metadata may live elsewhere and the two are compared.
     */
    @NotBlank(message = ENTRY_PATH + ".discovery-url must not be blank")
    public String getDiscoveryUrl() {
        return discoveryUrl;
    }

    /** Sets the discovery document URL. */
    public void setDiscoveryUrl(String discoveryUrl) {
        this.discoveryUrl = discoveryUrl;
    }

    /** The audience expected in {@code aud}; per provider, unlike Micronaut's global setting. */
    @NotBlank(message = ENTRY_PATH + ".audience must not be blank")
    public String getAudience() {
        return audience;
    }

    /** Sets the expected audience. */
    public void setAudience(String audience) {
        this.audience = audience;
    }

    /** The time-claim tolerance, never {@code null}: the setter defaults it. */
    public Duration getClockSkew() {
        return clockSkew;
    }

    /** Sets the clock skew; {@code null} restores {@link #DEFAULT_CLOCK_SKEW}. */
    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew == null ? DEFAULT_CLOCK_SKEW : clockSkew;
    }

    /**
     * Whether {@code http} issuer and discovery URLs are tolerated; without it they fail startup,
     * with it they warn. See {@link #DEFAULT_ALLOW_INSECURE_TRANSPORT}.
     */
    public boolean isAllowInsecureTransport() {
        return allowInsecureTransport;
    }

    /** Sets whether cleartext {@code http} is tolerated; for local development and tests only. */
    public void setAllowInsecureTransport(boolean allowInsecureTransport) {
        this.allowInsecureTransport = allowInsecureTransport;
    }

    /** The per-provider claim names, never {@code null} (the setter defaults it). */
    @Valid
    public ClaimsConfiguration getClaims() {
        return claims;
    }

    /** Sets the claim names; {@code null} restores the defaults. */
    public void setClaims(ClaimsConfiguration claims) {
        this.claims = claims == null ? new ClaimsConfiguration() : claims;
    }

    /**
     * The raw role mapping as bound (role name to provider string; values may be {@code null}).
     * Prefer {@link #getResolvedRoleMapping()}.
     */
    public Map<String, String> getRoleMapping() {
        return roleMapping;
    }

    /**
     * Sets the raw role mapping; {@code null} reads as empty. The copy tolerates {@code null}
     * values so validation can name the property instead of throwing a bare NPE.
     */
    public void setRoleMapping(Map<String, String> roleMapping) {
        this.roleMapping =
                roleMapping == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(roleMapping));
    }

    /** The validated, non-empty role-to-provider-string mapping, populated at startup. */
    public Map<Role, String> getResolvedRoleMapping() {
        return resolvedRoleMapping;
    }

    /**
     * Validates what constraints cannot express and derives the role mapping. Runs
     * <strong>before</strong> Bean Validation, so it must tolerate {@code null}s.
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
     * Converts the raw keys into {@link Role} constants.
     *
     * @throws ConfigurationException if empty, unknown, duplicated, blank or ambiguous
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
                    // Defence in depth: Micronaut lower-cases keys today, so this cannot fire yet.
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

    /** Rejects a negative duration with a {@link ConfigurationException} naming it. */
    private void requireNonNegative(String property, Duration value) {
        if (value.isNegative()) {
            throw new ConfigurationException(
                    getPropertyPath() + "." + property + " must not be negative, but was " + value);
        }
    }

    /**
     * The claim names for identifiers and roles, each a <strong>list of segments</strong> ({@code
     * [realm_access, roles]} is nested), so no token can change how a name is read. An env var
     * supplies segments comma-separated ({@code IDP_CLAIM_ROLES=realm_access,roles}).
     */
    @ConfigurationProperties("claims")
    public static class ClaimsConfiguration {

        private List<String> userIdentifier = DEFAULT_USER_IDENTIFIER_CLAIM;
        private List<String> participantIdentifier = List.of();
        private List<String> roles = List.of();

        /** The opaque user-identifier claim; defaults to {@code [sub]}. */
        @NotEmpty(message = ENTRY_PATH + ".claims.user-identifier must name at least one segment")
        public List<String> getUserIdentifier() {
            return userIdentifier;
        }

        /** Sets the user-identifier claim; only {@code null} restores the default, empty fails. */
        public void setUserIdentifier(List<String> userIdentifier) {
            this.userIdentifier =
                    userIdentifier == null
                            ? DEFAULT_USER_IDENTIFIER_CLAIM
                            : List.copyOf(userIdentifier);
        }

        /** The opaque participant-identifier claim, for example {@code [participant_id]}. */
        @NotEmpty(
                message =
                        ENTRY_PATH
                                + ".claims.participant-identifier must name at least one segment")
        public List<String> getParticipantIdentifier() {
            return participantIdentifier;
        }

        /** Sets the participant-identifier claim segments. */
        public void setParticipantIdentifier(List<String> participantIdentifier) {
            this.participantIdentifier =
                    participantIdentifier == null ? List.of() : List.copyOf(participantIdentifier);
        }

        /** The raw-roles claim, for example {@code [realm_access, roles]}. */
        @NotEmpty(message = ENTRY_PATH + ".claims.roles must name at least one segment")
        public List<String> getRoles() {
            return roles;
        }

        /** Sets the raw-roles claim segments. */
        public void setRoles(List<String> roles) {
            this.roles = roles == null ? List.of() : List.copyOf(roles);
        }

        /**
         * Rejects blank segments, which would silently resolve to empty for every token.
         *
         * @throws ConfigurationException if any configured name contains a blank segment
         */
        void validateSegments(String propertyPath) {
            requireNoBlankSegment(propertyPath, "user-identifier", userIdentifier);
            requireNoBlankSegment(propertyPath, "participant-identifier", participantIdentifier);
            requireNoBlankSegment(propertyPath, "roles", roles);
            warnIfUnsplitPath(propertyPath, "user-identifier", userIdentifier);
            warnIfUnsplitPath(propertyPath, "participant-identifier", participantIdentifier);
            warnIfUnsplitPath(propertyPath, "roles", roles);
        }

        /**
         * Whether the name is one non-URI segment containing a dot, likely a nested path left
         * unsplit that would silently resolve to empty; warned about, not refused.
         */
        static boolean looksLikeUnsplitPath(List<String> segments) {
            if (segments == null || segments.size() != 1) {
                return false;
            }
            String only = segments.get(0);
            return only != null
                    && only.contains(LEGACY_PATH_SEPARATOR)
                    && !only.contains(URI_SCHEME_SEPARATOR);
        }

        /** Logs the line that makes a silent authorization outage diagnosable. */
        private static void warnIfUnsplitPath(
                String propertyPath, String property, List<String> segments) {
            if (!looksLikeUnsplitPath(segments)) {
                return;
            }
            String only = segments.get(0);
            LOG.warn(
                    "{}.claims.{} is the single claim name '{}'. Dots carry no meaning in a claim"
                            + " name: nested claims are named segment by segment, so this is read as"
                            + " one top-level claim literally called '{}' and will resolve to empty"
                            + " unless the provider really emits it. If a nested claim was meant,"
                            + " configure the segments instead (for example '{}').",
                    propertyPath,
                    property,
                    only,
                    only,
                    only.replace(LEGACY_PATH_SEPARATOR, SEGMENT_SEPARATOR));
        }

        /** Throws a {@link ConfigurationException} if a segment is {@code null} or blank. */
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
