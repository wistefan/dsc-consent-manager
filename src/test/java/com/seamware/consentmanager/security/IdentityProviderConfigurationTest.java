package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micronaut.context.ApplicationContext;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the identity provider trust list: {@link Role}, {@link
 * IdentityProviderConfiguration} and the startup check in {@link
 * IdentityProviderRegistryValidator}.
 *
 * <p>Each test starts a throwaway {@link ApplicationContext} from an explicit property map. Loading
 * of {@code application.yml} and {@code application-test.yml} is disabled so the deployment's own
 * trust list cannot leak in and mask a binding defect — every property under test is supplied by
 * the test itself.
 */
@DisplayName("Identity provider trust list configuration")
class IdentityProviderConfigurationTest {

    /** Prefix of the first trust-list entry, as it appears in a flattened property map. */
    private static final String ENTRY_0 = IdentityProviderConfiguration.PREFIX + "[0]";

    /** Prefix of the second trust-list entry. */
    private static final String ENTRY_1 = IdentityProviderConfiguration.PREFIX + "[1]";

    private static final String ISSUER = "https://keycloak.example.com/realms/dataspace";
    private static final String DISCOVERY_URL = ISSUER + "/.well-known/openid-configuration";
    private static final String AUDIENCE = "consent-manager";
    private static final String USER_ROLE_STRING = "consent-user";
    private static final String PARTICIPANT_ROLE_STRING = "consent-participant";
    private static final String CATALOG_ROLE_STRING = "consent-catalog";
    private static final String NESTED_ROLES_CLAIM = "realm_access.roles";
    private static final String PARTICIPANT_ID_CLAIM = "participant_id";

    /**
     * Builds a fully specified, valid single-entry trust list.
     *
     * @return a mutable property map that individual tests mutate to produce variants
     */
    private static Map<String, Object> validEntry() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ENTRY_0 + ".issuer", ISSUER);
        properties.put(ENTRY_0 + ".discovery-url", DISCOVERY_URL);
        properties.put(ENTRY_0 + ".audience", AUDIENCE);
        properties.put(ENTRY_0 + ".jwks-cache-ttl", "2h");
        properties.put(ENTRY_0 + ".clock-skew", "45s");
        properties.put(ENTRY_0 + ".claims.user-identifier", "sub");
        properties.put(ENTRY_0 + ".claims.participant-identifier", PARTICIPANT_ID_CLAIM);
        properties.put(ENTRY_0 + ".claims.roles", NESTED_ROLES_CLAIM);
        properties.put(ENTRY_0 + ".role-mapping.USER", USER_ROLE_STRING);
        properties.put(ENTRY_0 + ".role-mapping.PARTICIPANT", PARTICIPANT_ROLE_STRING);
        properties.put(ENTRY_0 + ".role-mapping.CATALOG", CATALOG_ROLE_STRING);
        return properties;
    }

    /**
     * Starts an isolated application context from the supplied properties.
     *
     * <p>Default property sources are disabled so neither {@code application.yml} nor {@code
     * application-test.yml} contributes a trust list.
     *
     * @param properties the complete configuration for the context
     * @return the started context; the caller is responsible for closing it
     */
    private static ApplicationContext startContext(Map<String, Object> properties) {
        return ApplicationContext.builder()
                .enableDefaultPropertySources(false)
                .properties(properties)
                .build()
                .start();
    }

    /**
     * Returns the configured providers, ordered by their position in configuration.
     *
     * @param context a started context
     * @return the trust list as the validator sees it
     */
    private static List<IdentityProviderConfiguration> providersOf(ApplicationContext context) {
        return context.getBean(IdentityProviderRegistryValidator.class).getProviders();
    }

    @Nested
    @DisplayName("A well-formed trust list")
    class WellFormed {

        @Test
        @DisplayName("binds every property of a single entry")
        void bindsAllProperties() {
            try (ApplicationContext context = startContext(validEntry())) {
                List<IdentityProviderConfiguration> providers = providersOf(context);

                assertThat(providers).as("exactly one provider should be configured").hasSize(1);

                IdentityProviderConfiguration provider = providers.get(0);
                assertThat(provider.getIssuer()).isEqualTo(ISSUER);
                assertThat(provider.getDiscoveryUrl()).isEqualTo(DISCOVERY_URL);
                assertThat(provider.getAudience()).isEqualTo(AUDIENCE);
                assertThat(provider.getIndex()).isZero();
                assertThat(provider.getPropertyPath()).isEqualTo(ENTRY_0);
            }
        }

        @Test
        @DisplayName("parses Duration properties from their ISO-like shorthand")
        void parsesDurations() {
            try (ApplicationContext context = startContext(validEntry())) {
                IdentityProviderConfiguration provider = providersOf(context).get(0);

                assertThat(provider.getJwksCacheTtl())
                        .as("jwks-cache-ttl: 2h should parse")
                        .isEqualTo(Duration.ofHours(2));
                assertThat(provider.getClockSkew())
                        .as("clock-skew: 45s should parse")
                        .isEqualTo(Duration.ofSeconds(45));
            }
        }

        @Test
        @DisplayName("binds nested, dot-separated claim paths verbatim")
        void bindsNestedClaimPaths() {
            try (ApplicationContext context = startContext(validEntry())) {
                IdentityProviderConfiguration.ClaimsConfiguration claims =
                        providersOf(context).get(0).getClaims();

                assertThat(claims.getRoles())
                        .as("a nested roles path must survive binding unchanged")
                        .isEqualTo(NESTED_ROLES_CLAIM);
                assertThat(claims.getUserIdentifier()).isEqualTo("sub");
                assertThat(claims.getParticipantIdentifier()).isEqualTo(PARTICIPANT_ID_CLAIM);
            }
        }

        @Test
        @DisplayName("resolves the role mapping into typed Role keys")
        void resolvesRoleMapping() {
            try (ApplicationContext context = startContext(validEntry())) {
                Map<Role, String> mapping = providersOf(context).get(0).getResolvedRoleMapping();

                assertThat(mapping)
                        .containsEntry(Role.USER, USER_ROLE_STRING)
                        .containsEntry(Role.PARTICIPANT, PARTICIPANT_ROLE_STRING)
                        .containsEntry(Role.CATALOG, CATALOG_ROLE_STRING)
                        .hasSize(Role.values().length);
            }
        }

        @Test
        @DisplayName("publishes the trust list as an unmodifiable collection")
        void trustListIsUnmodifiable() {
            try (ApplicationContext context = startContext(validEntry())) {
                IdentityProviderConfiguration provider = providersOf(context).get(0);

                assertThatThrownBy(() -> providersOf(context).add(provider))
                        .as("the trust list is fixed at startup and must not be mutable")
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(
                                () ->
                                        provider.getResolvedRoleMapping()
                                                .put(Role.USER, "something-else"))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }

        @Test
        @DisplayName("applies defaults for the optional properties")
        void appliesDefaults() {
            Map<String, Object> properties = validEntry();
            properties.remove(ENTRY_0 + ".jwks-cache-ttl");
            properties.remove(ENTRY_0 + ".clock-skew");
            properties.remove(ENTRY_0 + ".claims.user-identifier");

            try (ApplicationContext context = startContext(properties)) {
                IdentityProviderConfiguration provider = providersOf(context).get(0);

                assertThat(provider.getJwksCacheTtl())
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_JWKS_CACHE_TTL);
                assertThat(provider.getClockSkew())
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_CLOCK_SKEW);
                assertThat(provider.getClaims().getUserIdentifier())
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_USER_IDENTIFIER_CLAIM);
            }
        }

        @ParameterizedTest(name = "role-mapping key written as \"{0}\"")
        @ValueSource(strings = {"USER", "user", "User"})
        @DisplayName("accepts a role-mapping key in any case")
        void acceptsRoleKeyInAnyCase(String roleKey) {
            Map<String, Object> properties = validEntry();
            properties.remove(ENTRY_0 + ".role-mapping.USER");
            properties.remove(ENTRY_0 + ".role-mapping.PARTICIPANT");
            properties.remove(ENTRY_0 + ".role-mapping.CATALOG");
            properties.put(ENTRY_0 + ".role-mapping." + roleKey, USER_ROLE_STRING);

            try (ApplicationContext context = startContext(properties)) {
                assertThat(providersOf(context).get(0).getResolvedRoleMapping())
                        .containsExactly(Map.entry(Role.USER, USER_ROLE_STRING));
            }
        }

        @Test
        @DisplayName("binds several providers in configuration order")
        void bindsMultipleProviders() {
            Map<String, Object> properties = validEntry();
            String secondIssuer = "https://other.example.com/realms/partners";
            properties.put(ENTRY_1 + ".issuer", secondIssuer);
            properties.put(
                    ENTRY_1 + ".discovery-url", secondIssuer + "/.well-known/openid-configuration");
            properties.put(ENTRY_1 + ".audience", "consent-manager");
            properties.put(ENTRY_1 + ".claims.participant-identifier", "org_id");
            properties.put(ENTRY_1 + ".claims.roles", "roles");
            properties.put(ENTRY_1 + ".role-mapping.PARTICIPANT", "partner");

            try (ApplicationContext context = startContext(properties)) {
                List<IdentityProviderConfiguration> providers = providersOf(context);

                assertThat(providers)
                        .extracting(IdentityProviderConfiguration::getIssuer)
                        .containsExactly(ISSUER, secondIssuer);
                assertThat(providers)
                        .isSortedAccordingTo(
                                Comparator.comparingInt(IdentityProviderConfiguration::getIndex));
                assertThat(providers.get(1).getResolvedRoleMapping())
                        .as("each provider keeps its own role vocabulary")
                        .containsExactly(Map.entry(Role.PARTICIPANT, "partner"));
            }
        }
    }

    @Nested
    @DisplayName("A malformed trust list")
    class Malformed {

        /**
         * Supplies one malformed variant of the valid entry per case, together with the fragment
         * the startup failure must mention so an operator can find the offending property.
         *
         * @return the rejection matrix
         */
        static Stream<Arguments> malformedRegistries() {
            return Stream.of(
                    Arguments.of(
                            "no providers at all",
                            Map.<String, Object>of(),
                            IdentityProviderConfiguration.PREFIX),
                    Arguments.of(
                            "blank issuer",
                            mutate(m -> m.put(ENTRY_0 + ".issuer", "  ")),
                            "issuer"),
                    Arguments.of(
                            "issuer is not a URL",
                            mutate(m -> m.put(ENTRY_0 + ".issuer", "not a url")),
                            "issuer"),
                    Arguments.of(
                            "issuer is a relative URL",
                            mutate(m -> m.put(ENTRY_0 + ".issuer", "/realms/dataspace")),
                            "issuer"),
                    Arguments.of(
                            "blank discovery URL",
                            mutate(m -> m.put(ENTRY_0 + ".discovery-url", "")),
                            "discovery-url"),
                    Arguments.of(
                            "discovery URL is not a URL",
                            mutate(m -> m.put(ENTRY_0 + ".discovery-url", "well-known")),
                            "discovery-url"),
                    Arguments.of(
                            "blank audience",
                            mutate(m -> m.put(ENTRY_0 + ".audience", "")),
                            "audience"),
                    Arguments.of(
                            "missing roles claim",
                            mutate(m -> m.remove(ENTRY_0 + ".claims.roles")),
                            "claims.roles"),
                    Arguments.of(
                            "missing participant identifier claim",
                            mutate(m -> m.remove(ENTRY_0 + ".claims.participant-identifier")),
                            "claims.participant-identifier"),
                    Arguments.of(
                            "empty role mapping",
                            mutate(
                                    m -> {
                                        m.remove(ENTRY_0 + ".role-mapping.USER");
                                        m.remove(ENTRY_0 + ".role-mapping.PARTICIPANT");
                                        m.remove(ENTRY_0 + ".role-mapping.CATALOG");
                                    }),
                            "role-mapping"),
                    // Micronaut normalises configuration keys to lower-case kebab before binding,
                    // so a YAML key of `ADMIN` reaches the registry — and the failure message — as
                    // `admin`. The original casing is not recoverable.
                    Arguments.of(
                            "unknown role name in the role mapping",
                            mutate(m -> m.put(ENTRY_0 + ".role-mapping.ADMIN", "consent-admin")),
                            "unknown role 'admin'"),
                    Arguments.of(
                            "blank raw role string",
                            mutate(m -> m.put(ENTRY_0 + ".role-mapping.USER", "   ")),
                            "role-mapping"),
                    Arguments.of(
                            "two roles mapped to the same provider role string",
                            mutate(m -> m.put(ENTRY_0 + ".role-mapping.CATALOG", USER_ROLE_STRING)),
                            "ambiguous"),
                    Arguments.of(
                            "negative clock skew",
                            mutate(m -> m.put(ENTRY_0 + ".clock-skew", "-5s")),
                            "clock-skew"));
        }

        /**
         * Applies a mutation to a copy of the valid entry.
         *
         * @param mutation the change that makes the entry invalid
         * @return the mutated property map
         */
        private static Map<String, Object> mutate(
                java.util.function.Consumer<Map<String, Object>> mutation) {
            Map<String, Object> properties = validEntry();
            mutation.accept(properties);
            return properties;
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("malformedRegistries")
        @DisplayName("aborts startup with a message naming the offending property")
        void abortsStartup(
                String caseName, Map<String, Object> properties, String expectedFragment) {
            assertThatThrownBy(() -> startContext(properties).close())
                    .as("'%s' must prevent the application context from starting", caseName)
                    .hasStackTraceContaining(expectedFragment);
        }

        @Test
        @DisplayName("rejects two providers declaring the same issuer")
        void rejectsDuplicateIssuers() {
            Map<String, Object> properties = validEntry();
            properties.put(ENTRY_1 + ".issuer", ISSUER);
            properties.put(ENTRY_1 + ".discovery-url", DISCOVERY_URL);
            properties.put(ENTRY_1 + ".audience", AUDIENCE);
            properties.put(ENTRY_1 + ".claims.participant-identifier", PARTICIPANT_ID_CLAIM);
            properties.put(ENTRY_1 + ".claims.roles", NESTED_ROLES_CLAIM);
            properties.put(ENTRY_1 + ".role-mapping.USER", USER_ROLE_STRING);

            assertThatThrownBy(() -> startContext(properties).close())
                    .as("an issuer appearing twice makes lookup by issuer ambiguous")
                    .hasStackTraceContaining(ISSUER)
                    .hasStackTraceContaining("exactly once");
        }

        @Test
        @DisplayName("fails even when micronaut.security is disabled")
        void failsRegardlessOfSecurityToggle() {
            Map<String, Object> properties = new HashMap<>();
            properties.put("micronaut.security.enabled", false);

            assertThatThrownBy(() -> startContext(properties).close())
                    .as("a resource server with no trust list is misconfigured either way")
                    .hasStackTraceContaining(IdentityProviderConfiguration.PREFIX);
        }

        @Test
        @DisplayName("accepts a valid trust list when micronaut.security is disabled")
        void acceptsValidListWhenSecurityDisabled() {
            Map<String, Object> properties = validEntry();
            properties.put("micronaut.security.enabled", false);

            assertThatCode(() -> startContext(properties).close()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Role.fromConfiguredName")
    class RoleLookup {

        @ParameterizedTest(name = "\"{0}\" resolves to {1}")
        @CsvSource({
            "USER, USER",
            "user, USER",
            "UsEr, USER",
            "PARTICIPANT, PARTICIPANT",
            "participant, PARTICIPANT",
            "CATALOG, CATALOG",
            "catalog, CATALOG",
            "'  user  ', USER"
        })
        @DisplayName("resolves a known role regardless of case or surrounding whitespace")
        void resolvesKnownRole(String configuredName, Role expected) {
            assertThat(Role.fromConfiguredName(configuredName)).contains(expected);
        }

        @ParameterizedTest(name = "\"{0}\" resolves to empty")
        @ValueSource(strings = {"ADMIN", "", "   ", "user-role", "USERS"})
        @DisplayName("returns empty for anything that is not a role")
        void rejectsUnknownRole(String configuredName) {
            assertThat(Role.fromConfiguredName(configuredName)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for null")
        void rejectsNull() {
            assertThat(Role.fromConfiguredName(null)).isEqualTo(Optional.empty());
        }
    }
}
