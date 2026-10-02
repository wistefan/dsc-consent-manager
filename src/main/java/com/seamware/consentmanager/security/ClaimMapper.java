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
import java.util.regex.Pattern;

/**
 * Reads claims by the names a provider was configured with; makes no trust decision.
 *
 * <p>A configured name may be a dot-separated path into a nested object ({@code
 * realm_access.roles}) or a namespaced top-level claim that merely contains dots ({@code
 * https://example.com/roles}). Resolution is <strong>nested-first</strong>: the name is tried as a
 * path, and only if that does not resolve is it read as a literal claim name. Nested-first means a
 * flat claim cannot shadow the nested one a path names, which matters where a subject can influence
 * claim names; a namespaced name has no object to descend into and still resolves literally.
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

    /** Separator between the segments of a nested claim path. */
    public static final String NESTED_CLAIM_SEPARATOR = ".";

    /** {@link #NESTED_CLAIM_SEPARATOR} escaped for use as a regular expression. */
    private static final String SEPARATOR_PATTERN = Pattern.quote(NESTED_CLAIM_SEPARATOR);

    /**
     * {@code limit} for {@link String#split(String, int)} that keeps trailing empty segments, so a
     * malformed path such as {@code realm_access.} fails to resolve instead of naming its parent.
     */
    private static final int KEEP_TRAILING_EMPTY_SEGMENTS = -1;

    /**
     * Reads a claim required to hold a single non-blank string.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @param claimPath the configured claim name or nested path; may be {@code null} or blank
     * @return the value, or empty if the claim is absent or is not a non-blank string
     */
    public Optional<String> findString(JWTClaimsSet claims, String claimPath) {
        return resolve(claims, claimPath)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank());
    }

    /**
     * Reads a claim holding either a list of strings or a single string.
     *
     * <p>Providers publish roles both ways. A single string is one value and is never split on
     * whitespace or commas; non-string and blank list elements are skipped.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @param claimPath the configured claim name or nested path; may be {@code null} or blank
     * @return the non-blank string values in encounter order; empty if nothing usable is there
     */
    public List<String> findStrings(JWTClaimsSet claims, String claimPath) {
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
     * Resolves a configured claim name as a nested path, falling back to a literal claim name.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @param claimPath the configured claim name; may be {@code null} or blank
     * @return the resolved value, or empty if it does not resolve or resolves to {@code null}
     */
    private static Optional<Object> resolve(JWTClaimsSet claims, String claimPath) {
        if (claims == null || claimPath == null || claimPath.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> topLevel = claims.getClaims();
        return descend(topLevel, claimPath).or(() -> Optional.ofNullable(topLevel.get(claimPath)));
    }

    /**
     * Walks a dot-separated path from the top-level claims. Descent stops at the first segment that
     * is missing or whose parent is not an object, so an attacker-shaped claim set never throws.
     *
     * @param topLevel the token's top-level claims
     * @param claimPath the configured claim name, read as a path
     * @return the value the path names, or empty if it does not resolve
     */
    private static Optional<Object> descend(Map<String, Object> topLevel, String claimPath) {
        Object current = topLevel;
        for (String segment : claimPath.split(SEPARATOR_PATTERN, KEEP_TRAILING_EMPTY_SEGMENTS)) {
            if (segment.isEmpty()
                    || !(current instanceof Map<?, ?> enclosing)
                    || !enclosing.containsKey(segment)) {
                return Optional.empty();
            }
            current = enclosing.get(segment);
        }
        return Optional.ofNullable(current);
    }
}
