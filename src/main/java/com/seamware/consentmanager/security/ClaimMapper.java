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
 * Reads the claims a token carries using the claim names a provider was configured with.
 *
 * <p>Claim vocabularies differ between identity providers, so nothing in this service reads a claim
 * by a hard-coded name. {@link IdentityProviderConfiguration.ClaimsConfiguration} states, per
 * provider, which claim carries the user identifier, which carries the participant identifier and
 * which carries the roles; this type turns one of those configured names plus a parsed claim set
 * into a value. It performs no validation and makes no trust decision - it is purely the lookup
 * half of {@link ConsentManagerTokenValidator}.
 *
 * <h2>Nested paths</h2>
 *
 * <p>Providers do not all publish roles at the top level. Keycloak nests them as {@code
 * {"realm_access": {"roles": ["..."]}}}, which a configured claim name has to be able to reach. A
 * configured name may therefore be a <strong>dot-separated path</strong>: {@code
 * realm_access.roles} descends into the {@code realm_access} object and reads its {@code roles}
 * member.
 *
 * <p>That convention collides with a second, equally common one: namespaced claim names such as
 * Auth0's {@code https://example.com/roles}, which contain dots but name a single top-level claim.
 * Splitting those on {@code .} would resolve nothing. The rule is therefore
 * <strong>literal-first</strong>: if a claim exists under the configured name <em>exactly</em>, its
 * value is returned and no splitting happens; only when no such claim exists is the name treated as
 * a path. The outcome is deterministic for every token - the literal claim wins whenever it is
 * present - and both conventions work without the operator having to escape anything.
 *
 * <h2>What is deliberately not done</h2>
 *
 * <p>Values are never coerced. A claim configured as an identifier that holds a number, an object
 * or an array resolves to {@link Optional#empty()} rather than to that value's {@code toString()},
 * because an identifier is stored verbatim and compared for equality elsewhere in this service, and
 * a silently stringified value would be a different identifier depending on which library parsed
 * the token. Role strings are compared <strong>exactly</strong>, case included: a role mapping is a
 * statement about the strings one named provider emits, and case-folding it would let {@code
 * Consent-User} satisfy a mapping written for {@code consent-user} at a provider that treats the
 * two as different roles.
 *
 * @see IdentityProviderConfiguration.ClaimsConfiguration
 * @see ConsentManagerTokenValidator
 */
@Singleton
public class ClaimMapper {

    /**
     * Separator between the segments of a nested claim path.
     *
     * <p>Only consulted when no claim exists under the configured name verbatim; see the
     * literal-first rule in this class's documentation.
     */
    public static final String NESTED_CLAIM_SEPARATOR = ".";

    /** {@link #NESTED_CLAIM_SEPARATOR} escaped for use as a regular expression. */
    private static final String SEPARATOR_PATTERN = Pattern.quote(NESTED_CLAIM_SEPARATOR);

    /**
     * {@code limit} argument to {@link String#split(String, int)} that keeps trailing empty
     * segments.
     *
     * <p>Without it {@code "realm_access."} would split to a single usable segment and resolve to
     * the enclosing object, quietly accepting a path that names no claim. Keeping the trailing
     * empty segment makes it fail to resolve, which is what a malformed path should do.
     */
    private static final int KEEP_TRAILING_EMPTY_SEGMENTS = -1;

    /**
     * Reads a claim that is required to hold a single non-blank string.
     *
     * <p>This is how identifier claims are read. A missing claim, a claim holding {@code null}, a
     * claim holding a blank string and a claim holding a non-string value are all reported the same
     * way, because every one of them means "this token carries no identifier here" and the caller
     * has no use for the distinction.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @param claimPath the configured claim name, possibly a dot-separated nested path; may be
     *     {@code null} or blank
     * @return the claim's value, or {@link Optional#empty()} if it is absent or is not a non-blank
     *     string
     */
    public Optional<String> findString(JWTClaimsSet claims, String claimPath) {
        return resolve(claims, claimPath)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank());
    }

    /**
     * Reads a claim that may hold either a list of strings or a single string.
     *
     * <p>Role claims are published both ways: Keycloak emits {@code ["consent-user"]} while other
     * providers emit a bare {@code "consent-user"}. Both are accepted. A single string is taken as
     * <strong>one</strong> value and is never split on whitespace or commas - splitting would
     * invent a delimiter convention that no provider on the trust list has declared, and would turn
     * one unrecognised role string into several.
     *
     * <p>Non-string and blank elements of a list are skipped rather than failing the read: a
     * provider that publishes a mixed array still has its usable entries honoured, and the unusable
     * ones simply map to no role.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @param claimPath the configured claim name, possibly a dot-separated nested path; may be
     *     {@code null} or blank
     * @return the non-blank string values, in encounter order; empty if the claim is absent or
     *     holds nothing usable
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
     * <p>The provider's {@code role-mapping} is the whole vocabulary: a raw role string it does not
     * cover maps to nothing and is dropped. That is not an error. Identity providers routinely put
     * roles unrelated to this service into the same claim - {@code offline_access}, {@code
     * uma_authorization}, roles belonging to other applications in the same realm - and a token is
     * not malformed for carrying them.
     *
     * <p>A token that ends up with <strong>no</strong> role still authenticates; it simply carries
     * no authority and is refused by the {@code @Secured} check on whatever operation it reaches,
     * with {@code 403} rather than {@code 401}. "The roles claim was absent" and "the roles claim
     * held only strings this provider does not map" are deliberately indistinguishable here: both
     * mean the caller holds a valid token and lacks the role, which is one answer, not two.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @param provider the configuration of the provider that issued the token, supplying both the
     *     roles claim name and the mapping
     * @return the roles the token grants, possibly empty, never {@code null}
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
     * Turns the configured role-to-string mapping into the string-to-role lookup this class needs.
     *
     * <p>The inversion is lossless because {@link IdentityProviderConfiguration#validate()} already
     * refuses a configuration that maps two roles to the same raw string, so no entry can be
     * overwritten here.
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
     * Resolves a configured claim name, literal first and then as a nested path.
     *
     * <p>Descent stops at the first segment that is missing or whose parent is not an object, so a
     * path never throws on a token whose shape differs from the configured one - an attacker-chosen
     * claim set is exactly what this walks over.
     *
     * @param claims the parsed claim set; may be {@code null}
     * @param claimPath the configured claim name; may be {@code null} or blank
     * @return the resolved value, or {@link Optional#empty()} if the path does not resolve or
     *     resolves to {@code null}
     */
    private static Optional<Object> resolve(JWTClaimsSet claims, String claimPath) {
        if (claims == null || claimPath == null || claimPath.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> topLevel = claims.getClaims();
        if (topLevel.containsKey(claimPath)) {
            return Optional.ofNullable(topLevel.get(claimPath));
        }
        Object current = topLevel;
        for (String segment : claimPath.split(SEPARATOR_PATTERN, KEEP_TRAILING_EMPTY_SEGMENTS)) {
            if (segment.isEmpty() || !(current instanceof Map<?, ?> enclosing)) {
                return Optional.empty();
            }
            if (!enclosing.containsKey(segment)) {
                return Optional.empty();
            }
            current = enclosing.get(segment);
        }
        return Optional.ofNullable(current);
    }
}
