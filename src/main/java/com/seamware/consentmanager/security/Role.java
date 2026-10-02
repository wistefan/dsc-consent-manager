package com.seamware.consentmanager.security;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The authorization roles understood by the Consent Manager.
 *
 * <p>A role is never read verbatim from an access token. Every trusted identity provider declares
 * its own {@code role-mapping} (see {@link IdentityProviderConfiguration#getRoleMapping()}) that
 * translates the raw role strings that provider emits into these constants, so deployments can
 * consume tokens from providers with different role vocabularies without changing code.
 *
 * <p>The scopes below are the authorization contract published in {@code
 * api/components/security.yaml}.
 */
public enum Role {

    /** A natural person. May manage and read their own consent records. */
    USER,

    /**
     * A dataspace participant — a machine client acting for an organisation. May manage and read
     * consent records relating to itself as a participant.
     */
    PARTICIPANT,

    /**
     * The dataspace catalog service. May read consent state across the dataspace. Unlike {@link
     * #PARTICIPANT}, a catalog token acts for the dataspace rather than for a single organisation
     * and therefore carries no participant identifier.
     */
    CATALOG;

    /**
     * Order in which the roles of a multi-role token are considered when deciding which single
     * identity the caller acts as.
     *
     * <p>Machine roles outrank {@link #USER}, and {@link #CATALOG} outranks {@link #PARTICIPANT}
     * because the catalog token is defined as a participant token that additionally bears the
     * catalog role - the natural way to issue one is a service account holding both. Ordering them
     * the other way round would mean no token could ever act as the catalog.
     */
    private static final List<Role> PRINCIPAL_PRECEDENCE = List.of(CATALOG, PARTICIPANT, USER);

    /**
     * Picks the one role a token acts as, from every role it was granted.
     *
     * <p>Authorization itself still reads the full set - {@code @Secured} matches any granted role.
     * This only settles which typed principal a multi-role token resolves to, deterministically and
     * in one place, so the published token contract has an answer instead of a refusal.
     *
     * @param granted every role the token's roles claim mapped to; may be empty
     * @return the highest-precedence granted role, or empty if none was granted
     */
    public static Optional<Role> effective(Set<Role> granted) {
        if (granted == null) {
            return Optional.empty();
        }
        return PRINCIPAL_PRECEDENCE.stream().filter(granted::contains).findFirst();
    }

    /**
     * Resolves a role from a configuration key, ignoring case.
     *
     * <p>Micronaut normalises configuration keys to lower-case kebab form, so the role-mapping key
     * {@code USER} written in {@code application.yml} reaches the binder as {@code user}. This
     * lookup is therefore deliberately case-insensitive rather than relying on {@link
     * Enum#valueOf(Class, String)}.
     *
     * @param configuredName the role name as written in configuration; may be {@code null} or blank
     * @return the matching role, or {@link Optional#empty()} if the name is {@code null}, blank or
     *     not a known role
     */
    public static Optional<Role> fromConfiguredName(String configuredName) {
        if (configuredName == null || configuredName.isBlank()) {
            return Optional.empty();
        }
        String normalized = configuredName.strip().toUpperCase(Locale.ROOT);
        for (Role role : values()) {
            if (role.name().equals(normalized)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
