package com.seamware.consentmanager.security;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The authorization roles understood by the Consent Manager, as published in {@code security.yaml}.
 * Never read verbatim from a token: each provider's {@link
 * IdentityProviderConfiguration#getRoleMapping() role-mapping} translates its raw strings.
 */
public enum Role {

    /** A natural person. May manage and read their own consent records. */
    USER,

    /** A machine client acting for an organisation. May manage and read its own consents. */
    PARTICIPANT,

    /** The dataspace catalog service. Reads consent state dataspace-wide; no identifier. */
    CATALOG;

    /**
     * Tie-break order for a multi-role token; {@link #CATALOG} first, since a catalog token also
     * bears the participant role. An operation accepting several roles (including every {@code
     * IS_AUTHENTICATED} route) <strong>must not scope its response by principal type</strong>: it
     * must name the single role it serves so the intersection decides instead of this list.
     */
    private static final List<Role> PRINCIPAL_PRECEDENCE = List.of(CATALOG, PARTICIPANT, USER);

    /**
     * Picks the typed principal a token acts as on an operation accepting {@code accepted}. {@code
     * accepted} narrows first; {@link #PRINCIPAL_PRECEDENCE} breaks a tie. Empty when {@code
     * granted} is null/empty or shares no role with a non-empty {@code accepted}.
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
     * Resolves a role from a configuration key, ignoring case, since Micronaut lower-cases keys.
     * Empty for a {@code null}, blank or unknown name.
     */
    public static Optional<Role> fromConfiguredName(String configuredName) {
        if (configuredName == null || configuredName.isBlank()) {
            return Optional.empty();
        }
        String normalized = configuredName.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(role -> role.name().equals(normalized)).findFirst();
    }
}
