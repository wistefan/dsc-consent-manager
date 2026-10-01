package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.assertj.core.api.AbstractThrowableAssert;
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
 * <p>A trusted provider is described by two configuration blocks that share one name — the issuer
 * half under {@code micronaut.security.oauth2.clients.<name>.openid}, owned by Micronaut Security,
 * and the claim half under {@code consent-manager.identity-providers.<name>}, owned by this
 * service. These tests exercise the binding of the claim half and the startup validation of the
 * pairing.
 *
 * <p>Each test starts a throwaway {@link ApplicationContext} from an explicit property map with
 * default property sources disabled, so neither {@code application.yml} nor {@code
 * application-test.yml} can leak a trust list in and mask a binding defect.
 *
 * <p>Every issuer used here points at {@link #UNREACHABLE_AUTHORITY}, a port nothing listens on, so
 * Micronaut's OpenID discovery fails immediately with a connection refusal instead of waiting on
 * DNS or a socket timeout. Discovery failure is deliberately non-fatal, so it does not disturb
 * these assertions.
 */
@DisplayName("Identity provider trust list configuration")
class IdentityProviderConfigurationTest {

    /** Host and port that refuse connections instantly, keeping discovery attempts cheap. */
    private static final String UNREACHABLE_AUTHORITY = "http://localhost:1";

    /** Name shared by both configuration halves of the provider under test. */
    private static final String PROVIDER = "primary";

    /**
     * An environment name that is not {@link Environment#TEST}, standing in for a deployment.
     *
     * <p>Used by the checks that must behave differently in a deployment than under test.
     */
    private static final String DEPLOYMENT_ENVIRONMENT = "deployment";

    /** Name of a second provider, used by the multi-provider and pairing tests. */
    private static final String OTHER_PROVIDER = "secondary";

    private static final String ISSUER = UNREACHABLE_AUTHORITY + "/realms/dataspace";
    private static final String OTHER_ISSUER = UNREACHABLE_AUTHORITY + "/realms/other";
    private static final String AUDIENCE = "consent-manager";
    private static final String USER_ROLE_STRING = "consent-user";
    private static final String PARTICIPANT_ROLE_STRING = "consent-participant";
    private static final String CATALOG_ROLE_STRING = "consent-catalog";
    private static final String NESTED_ROLES_CLAIM = "realm_access.roles";
    private static final String PARTICIPANT_ID_CLAIM = "participant_id";
    private static final String CONFIGURED_CLOCK_SKEW = "45s";

    /**
     * Returns the configuration key of the issuer half of a provider.
     *
     * @param name the provider name
     * @return for example {@code micronaut.security.oauth2.clients.primary.openid.issuer}
     */
    private static String issuerKey(String name) {
        return IdentityProviderConfiguration.OIDC_CLIENTS_PREFIX + "." + name + ".openid.issuer";
    }

    /**
     * Returns the configuration key prefix of the claim half of a provider.
     *
     * @param name the provider name
     * @return for example {@code consent-manager.identity-providers.primary}
     */
    private static String settingsKey(String name) {
        return IdentityProviderConfiguration.PREFIX + "." + name;
    }

    /**
     * Adds a fully specified, valid claim-settings block for a provider.
     *
     * @param properties the map to populate
     * @param name the provider name
     */
    private static void putValidSettings(Map<String, Object> properties, String name) {
        String prefix = settingsKey(name);
        properties.put(prefix + ".audience", AUDIENCE);
        properties.put(prefix + ".clock-skew", CONFIGURED_CLOCK_SKEW);
        properties.put(prefix + ".claims.user-identifier", "sub");
        properties.put(prefix + ".claims.participant-identifier", PARTICIPANT_ID_CLAIM);
        properties.put(prefix + ".claims.roles", NESTED_ROLES_CLAIM);
        properties.put(prefix + ".role-mapping.user", USER_ROLE_STRING);
        properties.put(prefix + ".role-mapping.participant", PARTICIPANT_ROLE_STRING);
        properties.put(prefix + ".role-mapping.catalog", CATALOG_ROLE_STRING);
    }

    /**
     * Adds the interim global claim validators exactly as {@code application.yml} declares them.
     *
     * <p>They are placeholders pointing back at the two configuration halves rather than literal
     * copies of {@link #ISSUER} and {@link #AUDIENCE}, so a test that overrides either half — from
     * a property or from an environment variable — keeps the guard aimed at the trust list, which
     * is precisely the production behaviour {@link IdentityProviderRegistryValidator} asserts.
     *
     * @param properties the map to populate
     * @param name the provider name the guard should follow
     */
    private static void putInterimGuard(Map<String, Object> properties, String name) {
        properties.put(
                IdentityProviderRegistryValidator.GLOBAL_ISSUER_VALIDATOR_PROPERTY,
                "${" + issuerKey(name) + "}");
        properties.put(
                IdentityProviderRegistryValidator.GLOBAL_AUDIENCE_VALIDATOR_PROPERTY,
                "${" + settingsKey(name) + ".audience}");
    }

    /**
     * Builds a valid single-provider trust list covering both configuration halves.
     *
     * @return a mutable property map that individual tests mutate to produce variants
     */
    private static Map<String, Object> validTrustList() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(issuerKey(PROVIDER), ISSUER);
        putValidSettings(properties, PROVIDER);
        putInterimGuard(properties, PROVIDER);
        return properties;
    }

    /**
     * Starts an isolated application context from the supplied properties.
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
     * Starts an isolated application context in which the given values are delivered the way the
     * operating system delivers environment variables.
     *
     * @param properties the configuration the context would otherwise have
     * @param environmentVariables raw environment-variable names and their values
     * @return the started context; the caller is responsible for closing it
     */
    private static ApplicationContext startContextWithEnvironmentVariables(
            Map<String, Object> properties, Map<String, Object> environmentVariables) {
        return ApplicationContext.builder()
                .enableDefaultPropertySources(false)
                .properties(properties)
                .propertySources(
                        PropertySource.of(
                                "test-environment",
                                environmentVariables,
                                PropertySource.PropertyConvention.ENVIRONMENT_VARIABLE))
                .build()
                .start();
    }

    /**
     * Starts an isolated application context with environment deduction switched off, so the active
     * environment names are exactly those given.
     *
     * <p>Micronaut otherwise deduces {@link io.micronaut.context.env.Environment#TEST} from the
     * JUnit frames on the stack, which would mask any check that treats a test context differently
     * from a deployment.
     *
     * @param properties the complete configuration for the context
     * @param environments the environment names to activate
     * @return the started context; the caller is responsible for closing it
     */
    private static ApplicationContext startContextInEnvironments(
            Map<String, Object> properties, String... environments) {
        return ApplicationContext.builder()
                .enableDefaultPropertySources(false)
                .deduceEnvironment(false)
                .environments(environments)
                .properties(properties)
                .build()
                .start();
    }

    /**
     * Returns the assembled trust list, ordered by provider name.
     *
     * @param context a started context
     * @return the trusted providers as the validator assembled them
     */
    private static List<TrustedIdentityProvider> providersOf(ApplicationContext context) {
        return context.getBean(IdentityProviderRegistryValidator.class).getProviders();
    }

    /**
     * Asserts that a configuration prevents the context from starting, naming the offending
     * property.
     *
     * <p>Every expected fragment must appear somewhere in the stack trace. Pass both the property
     * name and a distinctive phrase from the validator's own message wherever the property name
     * alone would also be produced by an unrelated binding or conversion failure on that property.
     *
     * @param properties the configuration under test
     * @param expectedMessageFragments fragments the failure message must all contain
     */
    private static void assertStartupFails(
            Map<String, Object> properties, String... expectedMessageFragments) {
        AbstractThrowableAssert<?, ? extends Throwable> thrown =
                assertThatThrownBy(() -> startContext(properties).close())
                        .as("startup should fail, naming the offending property");
        for (String fragment : expectedMessageFragments) {
            thrown.hasStackTraceContaining(fragment);
        }
    }

    @Nested
    @DisplayName("A well-formed trust list")
    class WellFormed {

        @Test
        @DisplayName("joins the issuer half and the claim half under one name")
        void joinsBothHalves() {
            try (ApplicationContext context = startContext(validTrustList())) {
                List<TrustedIdentityProvider> providers = providersOf(context);

                assertThat(providers).as("exactly one provider should be configured").hasSize(1);

                TrustedIdentityProvider provider = providers.get(0);
                assertThat(provider.name()).isEqualTo(PROVIDER);
                assertThat(provider.issuer()).isEqualTo(ISSUER);
                assertThat(provider.settings().getName()).isEqualTo(PROVIDER);
                assertThat(provider.settings().getPropertyPath()).isEqualTo(settingsKey(PROVIDER));
                assertThat(provider.settings().getAudience()).isEqualTo(AUDIENCE);
            }
        }

        @Test
        @DisplayName("parses Duration properties from their shorthand form")
        void parsesDurations() {
            try (ApplicationContext context = startContext(validTrustList())) {
                assertThat(providersOf(context).get(0).settings().getClockSkew())
                        .as("clock-skew: %s should parse", CONFIGURED_CLOCK_SKEW)
                        .isEqualTo(Duration.ofSeconds(45));
            }
        }

        @Test
        @DisplayName("binds nested, dot-separated claim paths verbatim")
        void bindsNestedClaimPaths() {
            try (ApplicationContext context = startContext(validTrustList())) {
                IdentityProviderConfiguration.ClaimsConfiguration claims =
                        providersOf(context).get(0).settings().getClaims();

                assertThat(claims.getRoles())
                        .as("a nested roles path must survive binding unchanged")
                        .isEqualTo(NESTED_ROLES_CLAIM);
                assertThat(claims.getUserIdentifier()).isEqualTo("sub");
                assertThat(claims.getParticipantIdentifier()).isEqualTo(PARTICIPANT_ID_CLAIM);
            }
        }

        @Test
        @DisplayName("converts the role mapping to Role constants")
        void resolvesRoleMapping() {
            try (ApplicationContext context = startContext(validTrustList())) {
                assertThat(providersOf(context).get(0).settings().getResolvedRoleMapping())
                        .containsExactlyInAnyOrderEntriesOf(
                                Map.of(
                                        Role.USER, USER_ROLE_STRING,
                                        Role.PARTICIPANT, PARTICIPANT_ROLE_STRING,
                                        Role.CATALOG, CATALOG_ROLE_STRING));
            }
        }

        @Test
        @DisplayName("applies the documented defaults for optional properties")
        void appliesDefaults() {
            Map<String, Object> properties = validTrustList();
            properties.remove(settingsKey(PROVIDER) + ".clock-skew");
            properties.remove(settingsKey(PROVIDER) + ".claims.user-identifier");

            try (ApplicationContext context = startContext(properties)) {
                IdentityProviderConfiguration settings = providersOf(context).get(0).settings();

                assertThat(settings.getClockSkew())
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_CLOCK_SKEW);
                assertThat(settings.getClaims().getUserIdentifier())
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_USER_IDENTIFIER_CLAIM);
            }
        }

        @Test
        @DisplayName("publishes the trust list as an unmodifiable collection")
        void publishesUnmodifiableTrustList() {
            try (ApplicationContext context = startContext(validTrustList())) {
                List<TrustedIdentityProvider> providers = providersOf(context);

                assertThatThrownBy(() -> providers.add(providers.get(0)))
                        .as("the trust list must be immutable for the lifetime of the process")
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }
    }

    @Nested
    @DisplayName("A malformed claim-settings entry")
    class MalformedSettings {

        /**
         * Supplies one malformed variant of the valid trust list per case.
         *
         * @return the case name, the property overrides to apply, the property keys to remove and
         *     the fragment the failure message must contain
         */
        static Stream<Arguments> malformedVariants() {
            String prefix = settingsKey(PROVIDER);
            return Stream.of(
                    Arguments.of(
                            "blank audience",
                            Map.of(prefix + ".audience", ""),
                            List.of(),
                            "audience must not be blank"),
                    Arguments.of(
                            "blank user-identifier claim",
                            Map.of(prefix + ".claims.user-identifier", "  "),
                            List.of(),
                            "claims.user-identifier must not be blank"),
                    Arguments.of(
                            "missing roles claim",
                            Map.of(),
                            List.of(prefix + ".claims.roles"),
                            "claims.roles must not be blank"),
                    Arguments.of(
                            "empty role mapping",
                            Map.of(),
                            List.of(
                                    prefix + ".role-mapping.user",
                                    prefix + ".role-mapping.participant",
                                    prefix + ".role-mapping.catalog"),
                            "role-mapping must map at least one"),
                    Arguments.of(
                            "unknown role name in the role mapping",
                            Map.of(prefix + ".role-mapping.admin", "consent-admin"),
                            List.of(),
                            "role-mapping contains unknown role 'admin'"),
                    Arguments.of(
                            "blank raw role string",
                            Map.of(prefix + ".role-mapping.user", "   "),
                            List.of(),
                            "role-mapping.user must not be blank"),
                    Arguments.of(
                            "the same raw role string mapped to two roles",
                            Map.of(prefix + ".role-mapping.participant", USER_ROLE_STRING),
                            List.of(),
                            "the mapping would be ambiguous"),
                    Arguments.of(
                            "negative clock skew",
                            Map.of(prefix + ".clock-skew", "-5s"),
                            List.of(),
                            "clock-skew must not be negative"));
        }

        @ParameterizedTest(name = "{0} aborts startup")
        @MethodSource("malformedVariants")
        @DisplayName("aborts startup with a message naming the offending property")
        void abortsStartup(
                String description,
                Map<String, Object> overrides,
                List<String> removals,
                String expectedMessageFragment) {
            Map<String, Object> properties = validTrustList();
            removals.forEach(properties::remove);
            properties.putAll(overrides);

            assertStartupFails(properties, expectedMessageFragment);
        }
    }

    @Nested
    @DisplayName("The startup trust-list check")
    class RegistryValidation {

        @Test
        @DisplayName("rejects a context with no provider at all")
        void rejectsEmptyTrustList() {
            assertStartupFails(new LinkedHashMap<>(), "No identity provider is configured");
        }

        @Test
        @DisplayName("rejects an OpenID client that has no claim settings")
        void rejectsClientWithoutSettings() {
            Map<String, Object> properties = validTrustList();
            properties.put(issuerKey(OTHER_PROVIDER), OTHER_ISSUER);

            assertStartupFails(properties, "have no claim settings");
        }

        @Test
        @DisplayName("rejects claim settings that have no OpenID client")
        void rejectsSettingsWithoutClient() {
            Map<String, Object> properties = validTrustList();
            putValidSettings(properties, OTHER_PROVIDER);

            assertStartupFails(properties, "but no issuer");
        }

        @Test
        @DisplayName("rejects two providers that declare the same issuer")
        void rejectsDuplicateIssuers() {
            Map<String, Object> properties = validTrustList();
            properties.put(issuerKey(OTHER_PROVIDER), ISSUER);
            putValidSettings(properties, OTHER_PROVIDER);

            assertStartupFails(properties, "each issuer must appear exactly once");
        }

        @Test
        @DisplayName("rejects a second provider, which could otherwise forge the first's tokens")
        void rejectsMoreThanOneProvider() {
            Map<String, Object> properties = validTrustList();
            properties.put(issuerKey(OTHER_PROVIDER), OTHER_ISSUER);
            putValidSettings(properties, OTHER_PROVIDER);

            // Signature verification is delegated to Micronaut, which tries every configured key
            // set and stops at the first that matches without checking whose key it is. A second
            // provider could therefore sign a token carrying the first provider's `iss` and it
            // would authenticate, so more than one entry must not start.
            assertStartupFails(
                    properties, "only one is supported until per-provider token validation");
        }

        @Test
        @DisplayName("rejects a sole provider configured under a name the interim guard misses")
        void rejectsProviderUnderAnotherName() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put(issuerKey(OTHER_PROVIDER), OTHER_ISSUER);
            putValidSettings(properties, OTHER_PROVIDER);
            // The guard keeps naming `primary`, as application.yml does: a placeholder cannot say
            // "whichever provider is configured", which is what makes renaming the entry unsafe.
            putInterimGuard(properties, PROVIDER);

            assertStartupFails(
                    properties,
                    IdentityProviderRegistryValidator.GLOBAL_ISSUER_VALIDATOR_PROPERTY,
                    "does not guard identity provider '" + OTHER_PROVIDER + "'");
        }

        /**
         * Supplies one interim-guard property per case, with a value that does not match the trust
         * list.
         *
         * @return the guard property and the trust-list value it was supposed to resolve to
         */
        static Stream<Arguments> misaimedGuards() {
            return Stream.of(
                    Arguments.of(
                            IdentityProviderRegistryValidator.GLOBAL_ISSUER_VALIDATOR_PROPERTY,
                            ISSUER),
                    Arguments.of(
                            IdentityProviderRegistryValidator.GLOBAL_AUDIENCE_VALIDATOR_PROPERTY,
                            AUDIENCE));
        }

        @ParameterizedTest(name = "{0} pointed elsewhere aborts startup")
        @MethodSource("misaimedGuards")
        @DisplayName("rejects an interim claim validator aimed at a value no provider uses")
        void rejectsMisaimedInterimGuard(String property, String expectedValue) {
            Map<String, Object> properties = validTrustList();
            properties.put(property, "something-else");

            // The placeholders in application.yml are only defaults: either property can be set
            // independently, which would aim the single issuer and audience check this step
            // provides at a value no trusted provider uses - with a clean startup.
            assertStartupFails(
                    properties,
                    property,
                    "must resolve to '" + expectedValue + "', but resolved to 'something-else'");
        }

        @ParameterizedTest(name = "{0} left unset aborts startup")
        @ValueSource(
                strings = {
                    "micronaut.security.token.jwt.claims-validators.issuer",
                    "micronaut.security.token.jwt.claims-validators.audience"
                })
        @DisplayName("rejects an interim claim validator that is not configured at all")
        void rejectsAbsentInterimGuard(String property) {
            Map<String, Object> properties = validTrustList();
            properties.remove(property);

            assertStartupFails(properties, property, "but resolved to nothing");
        }

        @ParameterizedTest(name = "unrecognised key \"{0}\" aborts startup")
        @ValueSource(
                strings = {"discovery-url", "jwks-cache-ttl", "claims.email", "role-mappings.user"})
        @DisplayName("rejects a settings key this service does not read")
        void rejectsUnrecognisedSettingKeys(String key) {
            Map<String, Object> properties = validTrustList();
            properties.put(settingsKey(PROVIDER) + "." + key, "whatever");

            // @EachProperty ignores an unknown key silently, so without this check `discovery-url`
            // and `jwks-cache-ttl` - both of which look like security-boundary settings - would
            // bind to nothing and do nothing, with a clean startup.
            assertStartupFails(properties, "unrecognised key(s)");
        }

        @Test
        @DisplayName("rejects the unsigned-token guard being disabled outside a test context")
        void rejectsDisabledUnsignedTokenRejectorInDeployment() {
            Map<String, Object> properties = validTrustList();
            properties.put(UnsignedTokenRejector.ENABLED_PROPERTY, "false");

            assertThatThrownBy(() -> startContextInEnvironments(properties, DEPLOYMENT_ENVIRONMENT))
                    .as("disabling the alg:none guard must not be possible from a deployment")
                    .hasStackTraceContaining(UnsignedTokenRejector.ENABLED_PROPERTY)
                    .hasStackTraceContaining("outside a test context");
        }

        @Test
        @DisplayName("allows the unsigned-token guard to be disabled in a test context")
        void allowsDisabledUnsignedTokenRejectorInTests() {
            Map<String, Object> properties = validTrustList();
            properties.put(UnsignedTokenRejector.ENABLED_PROPERTY, "false");

            try (ApplicationContext context =
                    startContextInEnvironments(properties, Environment.TEST)) {
                assertThat(context.findBean(UnsignedTokenRejector.class))
                        .as("the control case that proves the guard is load-bearing must still run")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("keeps the unsigned-token guard registered by default")
        void registersUnsignedTokenRejectorByDefault() {
            try (ApplicationContext context =
                    startContextInEnvironments(validTrustList(), DEPLOYMENT_ENVIRONMENT)) {
                assertThat(context.findBean(UnsignedTokenRejector.class))
                        .as("the alg:none guard must be present without any opt-in")
                        .isPresent();
            }
        }

        @ParameterizedTest(name = "issuer \"{0}\" aborts startup")
        @CsvSource({
            "not-a-url, 'URL with a host, but was'",
            "/realms/dataspace, 'URL with a host, but was'",
            "ftp://an.example, 'URL with a host, but was'",
            "'ftp:', is not a valid URL"
        })
        @DisplayName("rejects an issuer that is not an absolute http(s) URL with a host")
        void rejectsMalformedIssuer(String issuer, String expectedMessage) {
            Map<String, Object> properties = validTrustList();
            properties.put(issuerKey(PROVIDER), issuer);

            // Assert the validator's own wording, not just the property name: Micronaut silently
            // repairs some malformed values and fails to convert others, and either outcome would
            // also name the property. Only these phrases prove that the trust-list check is what
            // rejected the value.
            assertStartupFails(properties, issuerKey(PROVIDER), expectedMessage);
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

    /**
     * Pins what environment variables can and cannot do to the trust list.
     *
     * <p>The behaviour is documented in {@code .env.sample} and {@code application.yml} and is
     * load-bearing: an operator who believes a second issuer can be trusted through environment
     * variables would get a service that starts cleanly while trusting only the first. These tests
     * keep the documentation honest.
     */
    @Nested
    @DisplayName("environment-variable binding")
    class EnvironmentVariableBinding {

        /** Prefix of the environment variables that configure the claim half of a provider. */
        private static final String ENV_PREFIX = "CONSENT_MANAGER_IDENTITY_PROVIDERS_";

        @ParameterizedTest(name = "{0} does not add a provider")
        @CsvSource({
            "an indexed entry, " + ENV_PREFIX + "1",
            "an entry under a new name, " + ENV_PREFIX + "OTHER"
        })
        @DisplayName("cannot add a provider that is not already declared")
        void cannotAddProvider(String description, String variablePrefix) {
            Map<String, Object> environment =
                    Map.of(
                            variablePrefix + "_AUDIENCE", AUDIENCE,
                            variablePrefix + "_CLAIMS_ROLES", NESTED_ROLES_CLAIM);

            try (ApplicationContext context =
                    startContextWithEnvironmentVariables(validTrustList(), environment)) {
                assertThat(providersOf(context))
                        .as(
                                "%s must be ignored: the trust list still holds only the declared"
                                        + " provider",
                                description)
                        .extracting(TrustedIdentityProvider::name)
                        .containsExactly(PROVIDER);
            }
        }

        @ParameterizedTest(name = "unrecognised key \"{0}\" supplied as a variable aborts startup")
        @ValueSource(strings = {"jwks-cache-ttl", "discovery-url"})
        @DisplayName("rejects an unrecognised key that arrives only as an environment variable")
        void rejectsUnrecognisedKeyFromEnvironment(String key) {
            String variable =
                    ENV_PREFIX
                            + PROVIDER.toUpperCase(Locale.ROOT)
                            + "_"
                            + key.replace('-', '_').toUpperCase(Locale.ROOT);

            assertThatThrownBy(
                            () ->
                                    startContextWithEnvironmentVariables(
                                                    validTrustList(), Map.of(variable, "5m"))
                                            .close())
                    .as(
                            "a key Micronaut resolves but does not list under the entry prefix must"
                                    + " still abort startup")
                    .hasStackTraceContaining(key)
                    .hasStackTraceContaining("unrecognised key(s)");
        }

        @Test
        @DisplayName("overrides a value of a provider that is already declared")
        void overridesDeclaredProvider() {
            String overridden = "audience-from-the-environment";
            Map<String, Object> environment =
                    Map.of(
                            ENV_PREFIX + PROVIDER.toUpperCase(Locale.ROOT) + "_AUDIENCE",
                            overridden);

            try (ApplicationContext context =
                    startContextWithEnvironmentVariables(validTrustList(), environment)) {
                assertThat(providersOf(context))
                        .singleElement()
                        .extracting(provider -> provider.settings().getAudience())
                        .as("an environment variable must override the declared value")
                        .isEqualTo(overridden);
            }
        }
    }
}
