package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins how a configured claim name is turned into a value.
 *
 * <p>Every case here is reachable from a token an attacker writes, so the interesting assertions
 * are the ones about claim sets whose <em>shape</em> differs from the configured path: an
 * identifier claim holding an object, a roles claim holding a number, a path descending through
 * something that is not an object. None of those may throw, and none may be coerced into a value.
 *
 * <p>The first-segment rule for dotted names gets its own cases because the two conventions it
 * reconciles - Keycloak's nested {@code realm_access.roles} and Auth0's namespaced {@code
 * https://example.com/roles} - are indistinguishable from the configured string alone, and because
 * a flat claim named like a path must not shadow the nested claim that path names. The case that
 * decides the rule is the one where the nested object is present but its leaf is absent: a caller
 * with no realm roles is precisely the one who would benefit from injecting the flat claim.
 */
@DisplayName("Claim lookup by configured name")
class ClaimMapperTest {

    /** Nested path a Keycloak-shaped token carries its realm roles under. */
    private static final String NESTED_ROLES_PATH = "realm_access.roles";

    /** First segment of {@link #NESTED_ROLES_PATH}. */
    private static final String REALM_ACCESS_CLAIM = "realm_access";

    /** Second segment of {@link #NESTED_ROLES_PATH}. */
    private static final String ROLES_CLAIM = "roles";

    /** A namespaced claim name that contains dots but names one top-level claim. */
    private static final String NAMESPACED_ROLES_CLAIM = "https://example.com/roles";

    /** Value a flat claim named like a nested path would inject if it were allowed to win. */
    private static final String SHADOWING_VALUE = "did:web:attacker.example";

    /** Claim name a provider carries its participant identifier under. */
    private static final String PARTICIPANT_CLAIM = "participant_id";

    /** Raw role string the trust-list entry maps {@link Role#USER} to. */
    private static final String USER_ROLE_VALUE = "consent-user";

    /** Raw role string the trust-list entry maps {@link Role#PARTICIPANT} to. */
    private static final String PARTICIPANT_ROLE_VALUE = "consent-participant";

    /** Raw role string the trust-list entry maps {@link Role#CATALOG} to. */
    private static final String CATALOG_ROLE_VALUE = "consent-catalog";

    /** A role string the provider emits that this service's mapping does not cover. */
    private static final String FOREIGN_ROLE_VALUE = "offline_access";

    /** Issuer a valid trust-list entry needs; never contacted by these tests. */
    private static final String ISSUER = "https://idp.example/realms/dataspace";

    /** Discovery URL a valid trust-list entry needs; never contacted by these tests. */
    private static final String DISCOVERY_URL = ISSUER + "/.well-known/openid-configuration";

    /** Audience a valid trust-list entry needs; no claim lookup inspects it. */
    private static final String AUDIENCE = "consent-manager";

    /** A participant identifier value used wherever a well-formed string claim is needed. */
    private static final String PARTICIPANT_ID = "did:web:participant.example";

    private final ClaimMapper mapper = new ClaimMapper();

    /**
     * Cases for {@link ClaimMapper#findString(JWTClaimsSet, String)}.
     *
     * @return description, claim set, configured claim name, expected value or {@code null}
     */
    private static Stream<Arguments> stringClaims() {
        return Stream.of(
                Arguments.of(
                        "a top-level string claim",
                        claims(Map.of(PARTICIPANT_CLAIM, PARTICIPANT_ID)),
                        PARTICIPANT_CLAIM,
                        PARTICIPANT_ID),
                Arguments.of(
                        "a nested string claim",
                        claims(Map.of(REALM_ACCESS_CLAIM, Map.of(ROLES_CLAIM, PARTICIPANT_ID))),
                        NESTED_ROLES_PATH,
                        PARTICIPANT_ID),
                Arguments.of(
                        "a namespaced claim whose name contains dots",
                        claims(Map.of(NAMESPACED_ROLES_CLAIM, PARTICIPANT_ID)),
                        NAMESPACED_ROLES_CLAIM,
                        PARTICIPANT_ID),
                Arguments.of(
                        "a nested claim shadowed by a flat claim named like the path",
                        claims(
                                Map.of(
                                        REALM_ACCESS_CLAIM,
                                        Map.of(ROLES_CLAIM, PARTICIPANT_ID),
                                        NESTED_ROLES_PATH,
                                        SHADOWING_VALUE)),
                        NESTED_ROLES_PATH,
                        PARTICIPANT_ID),
                Arguments.of(
                        "a flat claim named like a path whose nested form is absent",
                        claims(Map.of(NESTED_ROLES_PATH, PARTICIPANT_ID)),
                        NESTED_ROLES_PATH,
                        PARTICIPANT_ID),
                Arguments.of(
                        "a flat claim injected where the nested object exists but its leaf does not",
                        claims(
                                Map.of(
                                        REALM_ACCESS_CLAIM,
                                        Map.of(),
                                        NESTED_ROLES_PATH,
                                        SHADOWING_VALUE)),
                        NESTED_ROLES_PATH,
                        null),
                Arguments.of("a claim that is absent", claims(Map.of()), PARTICIPANT_CLAIM, null),
                Arguments.of(
                        "a claim holding a blank string",
                        claims(Map.of(PARTICIPANT_CLAIM, "   ")),
                        PARTICIPANT_CLAIM,
                        null),
                Arguments.of(
                        "a claim holding a number, which is never stringified",
                        claims(Map.of(PARTICIPANT_CLAIM, 42)),
                        PARTICIPANT_CLAIM,
                        null),
                Arguments.of(
                        "a claim holding an object, which is never stringified",
                        claims(Map.of(PARTICIPANT_CLAIM, Map.of(ROLES_CLAIM, PARTICIPANT_ID))),
                        PARTICIPANT_CLAIM,
                        null),
                Arguments.of(
                        "a claim holding an explicit null",
                        claimsWithNull(PARTICIPANT_CLAIM),
                        PARTICIPANT_CLAIM,
                        null),
                Arguments.of(
                        "a path descending into a value that is not an object",
                        claims(Map.of(REALM_ACCESS_CLAIM, PARTICIPANT_ID)),
                        NESTED_ROLES_PATH,
                        null),
                Arguments.of(
                        "a path whose first segment is absent",
                        claims(Map.of(PARTICIPANT_CLAIM, PARTICIPANT_ID)),
                        NESTED_ROLES_PATH,
                        null),
                Arguments.of(
                        "a path with a trailing separator, which names no claim",
                        claims(Map.of(REALM_ACCESS_CLAIM, Map.of(ROLES_CLAIM, PARTICIPANT_ID))),
                        REALM_ACCESS_CLAIM + ClaimMapper.NESTED_CLAIM_SEPARATOR,
                        null),
                Arguments.of(
                        "a configured name that is blank",
                        claims(Map.of(PARTICIPANT_CLAIM, PARTICIPANT_ID)),
                        "  ",
                        null),
                Arguments.of(
                        "a configured name that is null",
                        claims(Map.of(PARTICIPANT_CLAIM, PARTICIPANT_ID)),
                        null,
                        null),
                Arguments.of("a null claim set", null, PARTICIPANT_CLAIM, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stringClaims")
    @DisplayName("reads a string claim only when it really holds a non-blank string")
    void readsStringClaims(
            String description, JWTClaimsSet claims, String claimPath, String expected) {
        assertThat(mapper.findString(claims, claimPath))
                .as("%s", description)
                .isEqualTo(Optional.ofNullable(expected));
    }

    /**
     * Cases for {@link ClaimMapper#findStrings(JWTClaimsSet, String)}.
     *
     * @return description, claim set, configured claim name, expected values
     */
    private static Stream<Arguments> stringListClaims() {
        return Stream.of(
                Arguments.of(
                        "a nested list of role strings",
                        claims(
                                Map.of(
                                        REALM_ACCESS_CLAIM,
                                        Map.of(
                                                ROLES_CLAIM,
                                                List.of(USER_ROLE_VALUE, FOREIGN_ROLE_VALUE)))),
                        NESTED_ROLES_PATH,
                        List.of(USER_ROLE_VALUE, FOREIGN_ROLE_VALUE)),
                Arguments.of(
                        "a nested role list shadowed by a flat claim named like the path",
                        claims(
                                Map.of(
                                        REALM_ACCESS_CLAIM,
                                        Map.of(ROLES_CLAIM, List.of(USER_ROLE_VALUE)),
                                        NESTED_ROLES_PATH,
                                        List.of(CATALOG_ROLE_VALUE))),
                        NESTED_ROLES_PATH,
                        List.of(USER_ROLE_VALUE)),
                Arguments.of(
                        "a flat role list injected where the nested object carries no roles",
                        claims(
                                Map.of(
                                        REALM_ACCESS_CLAIM,
                                        Map.of(),
                                        NESTED_ROLES_PATH,
                                        List.of(CATALOG_ROLE_VALUE))),
                        NESTED_ROLES_PATH,
                        List.of()),
                Arguments.of(
                        "a bare string, which counts as exactly one value",
                        claims(Map.of(NAMESPACED_ROLES_CLAIM, USER_ROLE_VALUE)),
                        NAMESPACED_ROLES_CLAIM,
                        List.of(USER_ROLE_VALUE)),
                Arguments.of(
                        "a space-separated string, which is never split",
                        claims(
                                Map.of(
                                        NAMESPACED_ROLES_CLAIM,
                                        USER_ROLE_VALUE + " " + PARTICIPANT_ROLE_VALUE)),
                        NAMESPACED_ROLES_CLAIM,
                        List.of(USER_ROLE_VALUE + " " + PARTICIPANT_ROLE_VALUE)),
                Arguments.of(
                        "a mixed list, whose unusable entries are skipped",
                        claims(
                                Map.of(
                                        NAMESPACED_ROLES_CLAIM,
                                        Arrays.asList(USER_ROLE_VALUE, 7, "  ", null))),
                        NAMESPACED_ROLES_CLAIM,
                        List.of(USER_ROLE_VALUE)),
                Arguments.of(
                        "an empty list",
                        claims(Map.of(NAMESPACED_ROLES_CLAIM, List.of())),
                        NAMESPACED_ROLES_CLAIM,
                        List.of()),
                Arguments.of("an absent claim", claims(Map.of()), NESTED_ROLES_PATH, List.of()),
                Arguments.of(
                        "a claim holding an object",
                        claims(Map.of(NAMESPACED_ROLES_CLAIM, Map.of(ROLES_CLAIM, "x"))),
                        NAMESPACED_ROLES_CLAIM,
                        List.of()),
                Arguments.of("a null claim set", null, NESTED_ROLES_PATH, List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stringListClaims")
    @DisplayName("reads a multi-valued claim written either as a list or as a single string")
    void readsStringListClaims(
            String description, JWTClaimsSet claims, String claimPath, List<String> expected) {
        assertThat(mapper.findStrings(claims, claimPath))
                .as("%s", description)
                .containsExactlyElementsOf(expected);
    }

    /**
     * Cases for {@link ClaimMapper#mapRoles(JWTClaimsSet, IdentityProviderConfiguration)}.
     *
     * @return description, the raw role strings a token carries, the roles they grant
     */
    private static Stream<Arguments> roleClaims() {
        return Stream.of(
                Arguments.of("the user role string", List.of(USER_ROLE_VALUE), Set.of(Role.USER)),
                Arguments.of(
                        "the participant role string",
                        List.of(PARTICIPANT_ROLE_VALUE),
                        Set.of(Role.PARTICIPANT)),
                Arguments.of(
                        "the catalog role string",
                        List.of(CATALOG_ROLE_VALUE),
                        Set.of(Role.CATALOG)),
                Arguments.of(
                        "several mapped role strings at once",
                        List.of(USER_ROLE_VALUE, PARTICIPANT_ROLE_VALUE),
                        Set.of(Role.USER, Role.PARTICIPANT)),
                Arguments.of(
                        "a mapped role string beside a foreign one",
                        List.of(FOREIGN_ROLE_VALUE, USER_ROLE_VALUE),
                        Set.of(Role.USER)),
                Arguments.of(
                        "only role strings this provider's mapping does not cover",
                        List.of(FOREIGN_ROLE_VALUE),
                        Set.of()),
                Arguments.of(
                        "a role string differing only in case, which is not a match",
                        List.of(USER_ROLE_VALUE.toUpperCase(Locale.ROOT)),
                        Set.of()),
                Arguments.of("no role strings at all", List.of(), Set.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("roleClaims")
    @DisplayName("maps raw role strings through the provider's mapping and drops the rest")
    void mapsRoles(String description, List<String> rawRoles, Set<Role> expected) {
        JWTClaimsSet claims = claims(Map.of(REALM_ACCESS_CLAIM, Map.of(ROLES_CLAIM, rawRoles)));

        assertThat(mapper.mapRoles(claims, provider()))
                .as("%s", description)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("grants no role when the configured roles claim is absent entirely")
    void mapsNoRoleWhenTheClaimIsAbsent() {
        JWTClaimsSet withoutRoles = claims(Map.of(PARTICIPANT_CLAIM, PARTICIPANT_ID));

        assertThat(mapper.mapRoles(withoutRoles, provider()))
                .as("an absent roles claim is not an error; it simply grants nothing")
                .isEmpty();
    }

    /**
     * Builds a valid trust-list entry whose roles claim is {@link #NESTED_ROLES_PATH}.
     *
     * <p>{@link IdentityProviderConfiguration#validate()} is invoked because it is what derives the
     * type-safe role mapping {@link ClaimMapper} reads; a configuration that skipped it would carry
     * an empty mapping and make every role case pass for the wrong reason.
     *
     * @return a validated configuration mapping all three roles
     */
    private static IdentityProviderConfiguration provider() {
        IdentityProviderConfiguration configuration = new IdentityProviderConfiguration("primary");
        configuration.setIssuer(ISSUER);
        configuration.setDiscoveryUrl(DISCOVERY_URL);
        configuration.setAudience(AUDIENCE);
        IdentityProviderConfiguration.ClaimsConfiguration claims =
                new IdentityProviderConfiguration.ClaimsConfiguration();
        claims.setRoles(NESTED_ROLES_PATH);
        claims.setParticipantIdentifier(PARTICIPANT_CLAIM);
        configuration.setClaims(claims);
        configuration.setRoleMapping(
                Map.of(
                        Role.USER.name(), USER_ROLE_VALUE,
                        Role.PARTICIPANT.name(), PARTICIPANT_ROLE_VALUE,
                        Role.CATALOG.name(), CATALOG_ROLE_VALUE));
        configuration.validate();
        return configuration;
    }

    /**
     * Builds a claim set holding exactly the given claims.
     *
     * @param values the claims to carry
     * @return the claim set
     */
    private static JWTClaimsSet claims(Map<String, Object> values) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        values.forEach(builder::claim);
        return builder.build();
    }

    /**
     * Builds a claim set in which the named claim is present but holds JSON {@code null}.
     *
     * @param claimName the claim to carry as null
     * @return the claim set
     */
    private static JWTClaimsSet claimsWithNull(String claimName) {
        return new JWTClaimsSet.Builder().claim(claimName, null).build();
    }
}
