package com.seamware.consentmanager.security;

import java.util.EnumSet;
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
     * <p>{@link #CATALOG} outranks {@link #PARTICIPANT} because a catalog token is a participant
     * token that additionally bears the catalog role, so the other order would leave no token able
     * to act as the catalog.
     *
     * <p>This order only ever decides a tie, and a tie means the operation did not say. An
     * operation that accepts more than one role - including every
     * {@code @Secured(IS_AUTHENTICATED)} route - therefore <strong>must not scope its response by
     * principal type</strong>: a caller holding both {@code USER} and {@code PARTICIPANT} is handed
     * the organisation here, and an operation whose data scope differs between the two has to name
     * the single role it serves so the intersection decides instead of this list.
     */
    private static final List<Role> PRINCIPAL_PRECEDENCE = List.of(CATALOG, PARTICIPANT, USER);

    /**
     * Picks the one role a token acts as on an operation that accepts {@code accepted}.
     *
     * <p>Authorization still reads the whole granted set; this only settles which typed principal
     * the caller is handed. The operation's own requirement decides first, so a {@code USER} who is
     * also a {@code PARTICIPANT} acts as a user on a user-scoped operation; {@link
     * #PRINCIPAL_PRECEDENCE} only breaks a tie, which is what an operation accepting any
     * authenticated caller ({@code accepted} empty) always is.
     *
     * @param granted the roles the token mapped to; {@code null} or empty yields empty
     * @param accepted the roles the operation names, empty when it names none; empty is also the
     *     result when the token grants none of them
     */
    public static Optional<Role> effective(Set<Role> granted, Set<Role> accepted) {
        if (granted == null || granted.isEmpty()) {
            return Optional.empty();
        }
        Set<Role> candidates =
                accepted == null || accepted.isEmpty() ? granted : intersect(granted, accepted);
        return PRINCIPAL_PRECEDENCE.stream().filter(candidates::contains).findFirst();
    }

    /** Roles present in both sets. */
    private static Set<Role> intersect(Set<Role> granted, Set<Role> accepted) {
        EnumSet<Role> both = EnumSet.copyOf(granted);
        both.retainAll(accepted);
        return both;
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
