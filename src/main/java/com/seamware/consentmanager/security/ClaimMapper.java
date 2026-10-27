package com.seamware.consentmanager.security;

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads claims by a provider's configured names; makes no trust decision. A name is a fixed list of
 * segments (nested walk, no flat-name fallback), values are never coerced, and role strings compare
 * exactly. Absent or {@code null} inputs read as empty.
 */
@Singleton
public class ClaimMapper {

    /** Reads a single non-blank string claim; empty if absent or of another type. */
    public Optional<String> findString(JWTClaimsSet claims, List<String> claimPath) {
        return findString(claimsOf(claims), claimPath);
    }

    /** As {@link #findString(JWTClaimsSet, List)}, over an {@code Authentication}'s claim map. */
    public Optional<String> findString(Map<String, Object> claims, List<String> claimPath) {
        return resolve(claims, claimPath)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank());
    }

    /** {@code true} only for the boolean {@code true}; absent or {@code "true"} reads false. */
    public boolean isTrue(Map<String, Object> claims, List<String> claimPath) {
        return resolve(claims, claimPath)
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(Boolean.FALSE);
    }

    /**
     * Reads a list-of-strings or single-string claim, in encounter order. A single string is never
     * split; non-string and blank elements are skipped.
     */
    public List<String> findStrings(JWTClaimsSet claims, List<String> claimPath) {
        return findStrings(claimsOf(claims), claimPath);
    }

    /** As {@link #findStrings(JWTClaimsSet, List)}, over an already-extracted claim map. */
    public List<String> findStrings(Map<String, Object> claims, List<String> claimPath) {
        Optional<Object> value = resolve(claims, claimPath);
        if (value.isEmpty()) {
            return List.of();
        }
        Object resolved = value.get();
        if (resolved instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(element -> !element.isBlank())
                    .toList();
        }
        if (resolved instanceof String single && !single.isBlank()) {
            return List.of(single);
        }
        return List.of();
    }

    /**
     * Maps a token's raw role strings to this service's roles, never {@code null}. Unmapped strings
     * are dropped, not an error; a token with no role is refused by {@code @Secured}.
     */
    public Set<Role> mapRoles(JWTClaimsSet claims, IdentityProviderConfiguration provider) {
        return mapRoles(claimsOf(claims), provider);
    }

    /** As {@link #mapRoles(JWTClaimsSet, IdentityProviderConfiguration)}, over a claim map. */
    public Set<Role> mapRoles(Map<String, Object> claims, IdentityProviderConfiguration provider) {
        Map<String, Role> rolesByRawValue = invert(provider.getResolvedRoleMapping());
        return findStrings(claims, provider.getClaims().getRoles()).stream()
                .map(rolesByRawValue::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
    }

    /** Inverts role-to-string; lossless since validation rejects two roles sharing a string. */
    private static Map<String, Role> invert(Map<Role, String> roleMapping) {
        Map<String, Role> inverted = new HashMap<>(roleMapping.size());
        roleMapping.forEach((role, rawRole) -> inverted.put(rawRole, role));
        return inverted;
    }

    /**
     * Walks the configured segments, stopping empty at the first absent segment or non-object
     * parent, so an attacker-shaped claim set never throws.
     */
    private static Optional<Object> resolve(Map<String, Object> claims, List<String> claimPath) {
        if (claims == null || claimPath == null || claimPath.isEmpty()) {
            return Optional.empty();
        }
        Object current = claims;
        for (String segment : claimPath) {
            if (segment == null
                    || segment.isBlank()
                    || !(current instanceof Map<?, ?> enclosing)
                    || !enclosing.containsKey(segment)) {
                return Optional.empty();
            }
            current = enclosing.get(segment);
        }
        return Optional.ofNullable(current);
    }

    /** The claims by name, or {@code null} if there is no claim set. */
    private static Map<String, Object> claimsOf(JWTClaimsSet claims) {
        return claims == null ? null : claims.getClaims();
    }
}
