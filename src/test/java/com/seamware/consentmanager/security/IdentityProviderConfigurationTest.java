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
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.groups.Tuple;
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
 * <p>A trusted provider is one {@code consent-manager.identity-providers.<name>} block owned
 * entirely by this service — issuer, discovery URL, audience, durations, claim paths and role
 * mapping all live there, as recorded in {@code
 * docs/adr/0002-own-identity-provider-registry-on-nimbus.md}. These tests exercise the binding of
 * that block and the startup validation of the assembled list.
 *
 * <p>Each test starts a throwaway {@link ApplicationContext} from an explicit property map with
 * default property sources disabled, so neither {@code application.yml} nor {@code
 * application-test.yml} can leak a trust list in and mask a binding defect.
 *
 * <p>Every issuer used here points at {@link #UNREACHABLE_AUTHORITY}, a port nothing listens on, so
 * nothing in these tests can accidentally reach a real provider.
 */
@DisplayName("Identity provider trust list configuration")
class IdentityProviderConfigurationTest {

    /** Host and port that refuse connections instantly, keeping any discovery attempt cheap. */
    private static final String UNREACHABLE_AUTHORITY = "http://localhost:1";

    /** Name of the provider under test. */
    private static final String PROVIDER = "primary";

    /** Name of a second provider, used by the multi-provider tests. */
    private static final String OTHER_PROVIDER = "secondary";

    /**
     * An environment name that is not {@link Environment#TEST}, standing in for a deployment.
     *
     * <p>Used by the checks that must behave differently in a deployment than under test.
     */
    private static final String DEPLOYMENT_ENVIRONMENT = "deployment";

    private static final String ISSUER = UNREACHABLE_AUTHORITY + "/realms/dataspace";
    private static final String OTHER_ISSUER = UNREACHABLE_AUTHORITY + "/realms/other";

    /** Suffix at which an OpenID provider conventionally publishes its metadata document. */
    private static final String DISCOVERY_SUFFIX = "/.well-known/openid-configuration";

    private static final String AUDIENCE = "consent-manager";
    private static final String USER_ROLE_STRING = "consent-user";
    private static final String PARTICIPANT_ROLE_STRING = "consent-participant";
    private static final String CATALOG_ROLE_STRING = "consent-catalog";
    private static final String NESTED_ROLES_CLAIM = "realm_access.roles";
    private static final String PARTICIPANT_ID_CLAIM = "participant_id";
    private static final String CONFIGURED_CLOCK_SKEW = "45s";
    private static final String CONFIGURED_JWKS_CACHE_TTL = "15m";

    /**
     * Returns the configuration key prefix of a provider entry.
     *
     * @param name the provider name
     * @return for example {@code consent-manager.identity-providers.primary}
     */
    private static String settingsKey(String name) {
        return IdentityProviderConfiguration.PREFIX + "." + name;
    }

    /**
     * Adds a fully specified, valid entry for a provider.
     *
     * @param properties the map to populate
     * @param name the provider name
     * @param issuer the issuer the provider stamps into its tokens
     */
    private static void putValidProvider(
            Map<String, Object> properties, String name, String issuer) {
        String prefix = settingsKey(name);
        properties.put(prefix + ".issuer", issuer);
        properties.put(prefix + ".discovery-url", issuer + DISCOVERY_SUFFIX);
        properties.put(prefix + ".audience", AUDIENCE);
        properties.put(prefix + ".clock-skew", CONFIGURED_CLOCK_SKEW);
        properties.put(prefix + ".jwks-cache-ttl", CONFIGURED_JWKS_CACHE_TTL);
        properties.put(prefix + ".claims.user-identifier", "sub");
        properties.put(prefix + ".claims.participant-identifier", PARTICIPANT_ID_CLAIM);
        properties.put(prefix + ".claims.roles", NESTED_ROLES_CLAIM);
        properties.put(prefix + ".role-mapping.user", USER_ROLE_STRING);
        properties.put(prefix + ".role-mapping.participant", PARTICIPANT_ROLE_STRING);
        properties.put(prefix + ".role-mapping.catalog", CATALOG_ROLE_STRING);
    }

    /**
     * Builds a valid single-provider trust list.
     *
     * @return a mutable property map that individual tests mutate to produce variants
     */
    private static Map<String, Object> validTrustList() {
        Map<String, Object> properties = new LinkedHashMap<>();
        putValidProvider(properties, PROVIDER, ISSUER);
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
     * <p>Micronaut otherwise deduces {@link Environment#TEST} from the JUnit frames on the stack,
     * which would mask any check that treats a test context differently from a deployment.
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
    private static List<IdentityProviderConfiguration> providersOf(ApplicationContext context) {
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
        @DisplayName("binds every documented setting of a provider entry")
        void bindsProviderEntry() {
            try (ApplicationContext context = startContext(validTrustList())) {
                List<IdentityProviderConfiguration> providers = providersOf(context);

                assertThat(providers).as("exactly one provider should be configured").hasSize(1);

                IdentityProviderConfiguration provider = providers.get(0);
                assertThat(provider.getName()).isEqualTo(PROVIDER);
                assertThat(provider.getPropertyPath()).isEqualTo(settingsKey(PROVIDER));
                assertThat(provider.getIssuer()).isEqualTo(ISSUER);
                assertThat(provider.getDiscoveryUrl()).isEqualTo(ISSUER + DISCOVERY_SUFFIX);
                assertThat(provider.getAudience()).isEqualTo(AUDIENCE);
            }
        }

        @Test
        @DisplayName("parses Duration properties from their shorthand form")
        void parsesDurations() {
            try (ApplicationContext context = startContext(validTrustList())) {
                IdentityProviderConfiguration provider = providersOf(context).get(0);

                assertThat(provider.getClockSkew())
                        .as("clock-skew: %s should parse", CONFIGURED_CLOCK_SKEW)
                        .isEqualTo(Duration.ofSeconds(45));
                assertThat(provider.getJwksCacheTtl())
                        .as("jwks-cache-ttl: %s should parse", CONFIGURED_JWKS_CACHE_TTL)
                        .isEqualTo(Duration.ofMinutes(15));
            }
        }

        @Test
        @DisplayName("binds nested, dot-separated claim paths verbatim")
        void bindsNestedClaimPaths() {
            try (ApplicationContext context = startContext(validTrustList())) {
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
        @DisplayName("converts the role mapping to Role constants")
        void resolvesRoleMapping() {
            try (ApplicationContext context = startContext(validTrustList())) {
                assertThat(providersOf(context).get(0).getResolvedRoleMapping())
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
            properties.remove(settingsKey(PROVIDER) + ".jwks-cache-ttl");
            properties.remove(settingsKey(PROVIDER) + ".claims.user-identifier");

            try (ApplicationContext context = startContext(properties)) {
                IdentityProviderConfiguration provider = providersOf(context).get(0);

                assertThat(provider.getClockSkew())
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_CLOCK_SKEW);
                assertThat(provider.getJwksCacheTtl())
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_JWKS_CACHE_TTL);
                assertThat(provider.getClaims().getUserIdentifier())
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_USER_IDENTIFIER_CLAIM);
            }
        }

        @Test
        @DisplayName("holds as many providers as are configured, each with its own settings")
        void bindsSeveralProviders() {
            Map<String, Object> properties = validTrustList();
            putValidProvider(properties, OTHER_PROVIDER, OTHER_ISSUER);
            properties.put(settingsKey(OTHER_PROVIDER) + ".claims.roles", "groups");

            try (ApplicationContext context = startContext(properties)) {
                assertThat(providersOf(context))
                        .as("the trust list size is configurable, not capped")
                        .extracting(
                                IdentityProviderConfiguration::getName,
                                IdentityProviderConfiguration::getIssuer,
                                provider -> provider.getClaims().getRoles())
                        .containsExactly(
                                Tuple.tuple(PROVIDER, ISSUER, NESTED_ROLES_CLAIM),
                                Tuple.tuple(OTHER_PROVIDER, OTHER_ISSUER, "groups"));
            }
        }

        @Test
        @DisplayName("publishes the trust list as an unmodifiable collection")
        void publishesUnmodifiableTrustList() {
            try (ApplicationContext context = startContext(validTrustList())) {
                List<IdentityProviderConfiguration> providers = providersOf(context);

                assertThatThrownBy(() -> providers.add(providers.get(0)))
                        .as("the trust list must be immutable for the lifetime of the process")
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }
    }

    @Nested
    @DisplayName("A malformed provider entry")
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
                            "blank issuer",
                            Map.of(prefix + ".issuer", ""),
                            List.of(),
                            "issuer must not be blank"),
                    Arguments.of(
                            "missing discovery URL",
                            Map.of(),
                            List.of(prefix + ".discovery-url"),
                            "discovery-url must not be blank"),
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
                            "clock-skew must not be negative"),
                    Arguments.of(
                            "negative JWKS cache TTL",
                            Map.of(prefix + ".jwks-cache-ttl", "-1m"),
                            List.of(),
                            "jwks-cache-ttl must not be negative"));
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
        @DisplayName("rejects two providers that declare the same issuer")
        void rejectsDuplicateIssuers() {
            Map<String, Object> properties = validTrustList();
            putValidProvider(properties, OTHER_PROVIDER, ISSUER);

            // A token is matched to its provider by issuer alone, so a repeated issuer would make
            // the lookup - and therefore the audience and role mapping applied - ambiguous.
            assertStartupFails(properties, "each issuer must appear exactly once");
        }

        @ParameterizedTest(name = "unrecognised key \"{0}\" aborts startup")
        @ValueSource(strings = {"audiance", "claims.email", "role-mappings.user", "jwks-url"})
        @DisplayName("rejects a settings key this service does not read")
        void rejectsUnrecognisedSettingKeys(String key) {
            Map<String, Object> properties = validTrustList();
            properties.put(settingsKey(PROVIDER) + "." + key, "whatever");

            // @EachProperty ignores an unknown key silently, so without this check a misspelled
            // security-boundary setting would bind to nothing and leave the real one at its
            // default, with a clean startup.
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
        @DisplayName("does not report the guard removed when its switch did not remove it")
        void doesNotReportTheGuardRemovedWhenItIsStillRegistered() {
            Map<String, Object> properties = validTrustList();
            // The @Requires that decides the bean's fate compares the property to "false" as an
            // exact, case-sensitive string, so this spelling leaves the guard registered. A check
            // that re-derived the state by reading the property back as a Boolean would convert
            // this to false and abort startup, reporting a guard removed that is in fact present.
            properties.put(UnsignedTokenRejector.ENABLED_PROPERTY, "FALSE");

            try (ApplicationContext context =
                    startContextInEnvironments(properties, DEPLOYMENT_ENVIRONMENT)) {
                assertThat(context.findBean(UnsignedTokenRejector.class))
                        .as("the startup check must observe the bean, not re-derive it")
                        .isPresent();
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
            properties.put(settingsKey(PROVIDER) + ".issuer", issuer);

            // Assert the validator's own wording, not just the property name: Micronaut silently
            // repairs some malformed values and fails to convert others, and either outcome would
            // also name the property. Only these phrases prove that the trust-list check is what
            // rejected the value.
            assertStartupFails(properties, settingsKey(PROVIDER) + ".issuer", expectedMessage);
        }

        @ParameterizedTest(name = "discovery URL \"{0}\" aborts startup")
        @CsvSource({
            "not-a-url, 'URL with a host, but was'",
            "/.well-known/openid-configuration, 'URL with a host, but was'",
            "'ftp:', is not a valid URL"
        })
        @DisplayName("rejects a discovery URL that is not an absolute http(s) URL with a host")
        void rejectsMalformedDiscoveryUrl(String discoveryUrl, String expectedMessage) {
            Map<String, Object> properties = validTrustList();
            properties.put(settingsKey(PROVIDER) + ".discovery-url", discoveryUrl);

            assertStartupFails(
                    properties, settingsKey(PROVIDER) + ".discovery-url", expectedMessage);
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
     * <p>Externalised configuration is the documented deployment mechanism ({@code .env.sample}),
     * and every one of these behaviours is load-bearing: an operator who believes a second issuer
     * is trusted, or that a misspelled variable took effect, would otherwise get a service that
     * starts cleanly with a trust list it does not have. Micronaut does not enumerate
     * environment-supplied keys under a prefix, so {@link IdentityProviderRegistryValidator} reads
     * the raw variable names; these tests keep that path honest.
     */
    @Nested
    @DisplayName("environment-variable binding")
    class EnvironmentVariableBinding {

        /** Prefix of the environment variables that configure a provider entry. */
        private static final String ENV_PREFIX = "CONSENT_MANAGER_IDENTITY_PROVIDERS_";

        /**
         * Returns the environment-variable spelling of a key below the provider under test.
         *
         * @param key the key as written in YAML, for example {@code jwks-cache-ttl}
         * @return for example {@code CONSENT_MANAGER_IDENTITY_PROVIDERS_PRIMARY_JWKS_CACHE_TTL}
         */
        private static String variableFor(String key) {
            return ENV_PREFIX
                    + PROVIDER.toUpperCase(Locale.ROOT)
                    + "_"
                    + key.replace('-', '_').replace('.', '_').toUpperCase(Locale.ROOT);
        }

        @ParameterizedTest(name = "{0} supplied as a variable aborts startup")
        @CsvSource({
            "an indexed entry, " + ENV_PREFIX + "1_ISSUER",
            "an entry under an undeclared name, " + ENV_PREFIX + "OTHER_ISSUER"
        })
        @DisplayName("refuses to start when a variable names a provider that is not declared")
        void rejectsUndeclaredProvider(String description, String variable) {
            assertThatThrownBy(
                            () ->
                                    startContextWithEnvironmentVariables(
                                                    validTrustList(), Map.of(variable, ISSUER))
                                            .close())
                    .as(
                            "%s binds to nothing, so the provider it names would silently not be"
                                    + " trusted",
                            description)
                    .hasStackTraceContaining(variable)
                    .hasStackTraceContaining("is not declared");
        }

        @ParameterizedTest(name = "unrecognised key \"{0}\" supplied as a variable aborts startup")
        @ValueSource(strings = {"audiance", "jwks-url", "claims.email"})
        @DisplayName("rejects an unrecognised key that arrives only as an environment variable")
        void rejectsUnrecognisedKeyFromEnvironment(String key) {
            String variable = variableFor(key);

            assertThatThrownBy(
                            () ->
                                    startContextWithEnvironmentVariables(
                                                    validTrustList(), Map.of(variable, "whatever"))
                                            .close())
                    .as(
                            "a misspelling Micronaut does not list under the entry prefix must still"
                                    + " abort startup")
                    .hasStackTraceContaining(variable)
                    .hasStackTraceContaining("unrecognised key(s)");
        }

        /**
         * Supplies one documented key per case, with the value an operator would set and the
         * assertion that reads it back.
         *
         * @return the key, the value to set and the accessor that must return it
         */
        static Stream<Arguments> documentedKeys() {
            return Stream.of(
                    Arguments.of(
                            "audience",
                            "audience-from-the-environment",
                            (Function<IdentityProviderConfiguration, Object>)
                                    IdentityProviderConfiguration::getAudience,
                            (Object) "audience-from-the-environment"),
                    Arguments.of(
                            "discovery-url",
                            OTHER_ISSUER + DISCOVERY_SUFFIX,
                            (Function<IdentityProviderConfiguration, Object>)
                                    IdentityProviderConfiguration::getDiscoveryUrl,
                            (Object) (OTHER_ISSUER + DISCOVERY_SUFFIX)),
                    Arguments.of(
                            "jwks-cache-ttl",
                            "5m",
                            (Function<IdentityProviderConfiguration, Object>)
                                    IdentityProviderConfiguration::getJwksCacheTtl,
                            (Object) Duration.ofMinutes(5)),
                    Arguments.of(
                            "clock-skew",
                            "90s",
                            (Function<IdentityProviderConfiguration, Object>)
                                    IdentityProviderConfiguration::getClockSkew,
                            (Object) Duration.ofSeconds(90)),
                    Arguments.of(
                            "claims.roles",
                            "groups",
                            (Function<IdentityProviderConfiguration, Object>)
                                    provider -> provider.getClaims().getRoles(),
                            (Object) "groups"),
                    Arguments.of(
                            "role-mapping.user",
                            "role-from-the-environment",
                            (Function<IdentityProviderConfiguration, Object>)
                                    provider -> provider.getResolvedRoleMapping().get(Role.USER),
                            (Object) "role-from-the-environment"));
        }

        @ParameterizedTest(name = "{0} is settable from the environment")
        @MethodSource("documentedKeys")
        @DisplayName("overrides a declared provider's value with the documented variable spelling")
        void overridesDeclaredProvider(
                String key,
                String value,
                Function<IdentityProviderConfiguration, Object> accessor,
                Object expected) {
            Map<String, Object> environment = Map.of(variableFor(key), value);

            try (ApplicationContext context =
                    startContextWithEnvironmentVariables(validTrustList(), environment)) {
                assertThat(providersOf(context))
                        .singleElement()
                        .extracting(accessor)
                        .as("%s must be settable through an environment variable", key)
                        .isEqualTo(expected);
            }
        }
    }
}
