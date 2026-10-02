package com.seamware.consentmanager.security;

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads claims by the names a provider was configured with; makes no trust decision.
 *
 * <p>A claim name is configured as a <em>list of segments</em>, so how it is read is fixed by
 * configuration at startup and never inferred from the shape of the token: {@code [realm_access,
 * roles]} walks into a nested object, while the single segment {@code [https://example.com/roles]}
 * names a top-level claim whatever characters it contains. There is no fallback between the two
 * readings, so a flat claim named like a path cannot shadow - or stand in for - the nested one the
 * operator configured.
 *
 * <p>Values are never coerced: a claim holding a number, object or array where a string is expected
 * resolves to empty rather than to its {@code toString()}. Role strings are compared exactly, case
 * included.
 *
 * @see IdentityProviderConfiguration.ClaimsConfiguration
 * @see ConsentManagerTokenValidator
 */
@Singleton
public class ClaimMapper {

    /**
     * Reads a claim required to hold a single non-blank string.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @param claimPath the configured claim-name segments; may be {@code null} or empty
     * @return the value, or empty if the claim is absent or is not a non-blank string
     */
    public Optional<String> findString(JWTClaimsSet claims, List<String> claimPath) {
        return findString(claimsOf(claims), claimPath);
    }

    /**
     * Reads a claim required to hold a single non-blank string from an already-extracted claim map.
     *
     * <p>The overload exists because an {@code Authentication} carries the token's claims as a map:
     * principal resolution reads them by the same configured names without re-parsing the token.
     *
     * @param claims the claims by name; may be {@code null}
     * @param claimPath the configured claim-name segments; may be {@code null} or empty
     * @return the value, or empty if the claim is absent or is not a non-blank string
     */
    public Optional<String> findString(Map<String, Object> claims, List<String> claimPath) {
        return resolve(claims, claimPath)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank());
    }

    /**
     * Reads a claim that is only true when it is present and holds the boolean {@code true}.
     *
     * <p>A missing claim, and the strings {@code "true"} and {@code "1"}, all read as {@code
     * false}: values are never coerced here either, and an unstated assertion is not an assertion.
     *
     * @param claims the claims by name; may be {@code null}
     * @param claimPath the configured claim-name segments; may be {@code null} or empty
     * @return {@code true} only if the claim is present and is the boolean {@code true}
     */
    public boolean isTrue(Map<String, Object> claims, List<String> claimPath) {
        return resolve(claims, claimPath)
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(Boolean.FALSE);
    }

    /**
     * Reads a claim holding either a list of strings or a single string.
     *
     * <p>Providers publish roles both ways. A single string is one value and is never split on
     * whitespace or commas; non-string and blank list elements are skipped.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @param claimPath the configured claim-name segments; may be {@code null} or empty
     * @return the non-blank string values in encounter order; empty if nothing usable is there
     */
    public List<String> findStrings(JWTClaimsSet claims, List<String> claimPath) {
        return findStrings(claimsOf(claims), claimPath);
    }

    /**
     * Reads a list-or-single-string claim from an already-extracted claim map.
     *
     * @param claims the claims by name; may be {@code null}
     * @param claimPath the configured claim-name segments; may be {@code null} or empty
     * @return the non-blank string values in encounter order; empty if nothing usable is there
     */
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
     * Translates the raw role strings a token carries into this service's roles.
     *
     * <p>A raw string the provider's {@code role-mapping} does not cover is dropped, not an error:
     * providers routinely emit roles belonging to other applications. A token mapping to no role
     * still authenticates and is refused by {@code @Secured} with {@code 403}.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @param provider the issuing provider's configuration, supplying the roles claim and mapping
     * @return the granted roles, possibly empty, never {@code null}
     */
    public Set<Role> mapRoles(JWTClaimsSet claims, IdentityProviderConfiguration provider) {
        return mapRoles(claimsOf(claims), provider);
    }

    /**
     * Translates the raw role strings in an already-extracted claim map into this service's roles.
     *
     * @param claims the claims by name; may be {@code null}
     * @param provider the issuing provider's configuration, supplying the roles claim and mapping
     * @return the granted roles, possibly empty, never {@code null}
     */
    public Set<Role> mapRoles(Map<String, Object> claims, IdentityProviderConfiguration provider) {
        Map<String, Role> rolesByRawValue = invert(provider.getResolvedRoleMapping());
        Set<Role> granted = EnumSet.noneOf(Role.class);
        for (String rawRole : findStrings(claims, provider.getClaims().getRoles())) {
            Role role = rolesByRawValue.get(rawRole);
            if (role != null) {
                granted.add(role);
            }
        }
        return granted;
    }

    /**
     * Inverts the configured role-to-string mapping into a string-to-role lookup. Lossless, because
     * {@link IdentityProviderConfiguration#validate()} rejects two roles sharing a raw string.
     *
     * @param roleMapping the provider's resolved mapping, keyed by role
     * @return the same mapping keyed by raw role string
     */
    private static Map<String, Role> invert(Map<Role, String> roleMapping) {
        Map<String, Role> inverted = new HashMap<>(roleMapping.size());
        roleMapping.forEach((role, rawRole) -> inverted.put(rawRole, role));
        return inverted;
    }

    /**
     * Walks the configured segments from the top-level claims. Descent stops at the first segment
     * that is absent or whose parent is not an object, so an attacker-shaped claim set never throws
     * and never resolves a path the operator did not configure.
     *
     * @param claims the claims by name; may be {@code null}
     * @param claimPath the configured claim-name segments; may be {@code null} or empty
     * @return the resolved value, or empty if it does not resolve or resolves to {@code null}
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

    /**
     * Unwraps a parsed claim set into the map every reader here works against.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @return the claims by name, or {@code null} if there is no claim set
     */
    private static Map<String, Object> claimsOf(JWTClaimsSet claims) {
        return claims == null ? null : claims.getClaims();
    }
}
