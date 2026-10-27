package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.support.TestPseudonymizer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Tests the trust list: {@link Role}, binding of {@link IdentityProviderConfiguration} and the
 * {@link IdentityProviderRegistryValidator} startup check, each from explicit properties only.
 */
@DisplayName("Identity provider trust list configuration")
class IdentityProviderConfigurationTest {

    /**
     * No property file is read, so the receipt signer and the log pseudonymizer, which both fail
     * startup keyless, need these.
     */
    private static final Map<String, Object> STARTUP_KEYS =
            Map.of(
                    "consent-manager.receipts.ephemeral-signing-key",
                    true,
                    "consent-manager.logging.pseudonym-key",
                    TestPseudonymizer.KEY);

    /**
     * Authority that refuses connections instantly; {@code https} so the baseline list is a
     * deployment's, with the cleartext opt-in exercised only in {@link RegistryValidation}.
     */
    private static final String UNREACHABLE_AUTHORITY = "https://localhost:1";

    /** Cleartext counterpart of {@link #UNREACHABLE_AUTHORITY}, for the transport checks. */
    private static final String CLEARTEXT_AUTHORITY = "http://localhost:1";

    /** Per-entry key that tolerates a cleartext provider URL. */
    private static final String ALLOW_INSECURE_TRANSPORT = ".allow-insecure-transport";

    /** Name of the provider under test. */
    private static final String PROVIDER = "primary";

    /** Name of a second provider, used by the multi-provider tests. */
    private static final String OTHER_PROVIDER = "secondary";

    /**
     * Per-entry cache lifetime key that cannot be honoured: the JWK Set cache is global, so both
     * spellings must abort startup rather than bind to nothing.
     */
    private static final String RETIRED_JWKS_CACHE_TTL_KEY = "jwks-cache-ttl";

    /** An environment name other than {@link Environment#TEST}, standing in for a deployment. */
    private static final String DEPLOYMENT_ENVIRONMENT = "deployment";

    private static final String ISSUER = UNREACHABLE_AUTHORITY + "/realms/dataspace";
    private static final String OTHER_ISSUER = UNREACHABLE_AUTHORITY + "/realms/other";

    /** Suffix at which an OpenID provider conventionally publishes its metadata document. */
    private static final String DISCOVERY_SUFFIX = "/.well-known/openid-configuration";

    private static final String AUDIENCE = "consent-manager";
    private static final String USER_ROLE_STRING = "consent-user";
    private static final String PARTICIPANT_ROLE_STRING = "consent-participant";
    private static final String CATALOG_ROLE_STRING = "consent-catalog";
    private static final List<String> NESTED_ROLES_CLAIM = List.of("realm_access", "roles");
    private static final String PARTICIPANT_ID_CLAIM = "participant_id";

    /** A flat roles claim name, used where a second provider must differ from the first. */
    private static final String OTHER_ROLES_CLAIM = "groups";

    private static final String CONFIGURED_CLOCK_SKEW = "45s";

    /** Returns the configuration key prefix of the named provider entry. */
    private static String settingsKey(String name) {
        return IdentityProviderConfiguration.PREFIX + "." + name;
    }

    /** Adds a fully specified, valid entry for a provider stamping the given issuer. */
    private static void putValidProvider(
            Map<String, Object> properties, String name, String issuer) {
        String prefix = settingsKey(name);
        properties.put(prefix + ".issuer", issuer);
        properties.put(prefix + ".discovery-url", issuer + DISCOVERY_SUFFIX);
        properties.put(prefix + ".audience", AUDIENCE);
        properties.put(prefix + ".clock-skew", CONFIGURED_CLOCK_SKEW);
        properties.put(prefix + ".claims.user-identifier", List.of("sub"));
        properties.put(prefix + ".claims.participant-identifier", List.of(PARTICIPANT_ID_CLAIM));
        properties.put(prefix + ".claims.roles", NESTED_ROLES_CLAIM);
        properties.put(prefix + ".role-mapping.user", USER_ROLE_STRING);
        properties.put(prefix + ".role-mapping.participant", PARTICIPANT_ROLE_STRING);
        properties.put(prefix + ".role-mapping.catalog", CATALOG_ROLE_STRING);
    }

    /** Builds a mutable, valid single-provider trust list for tests to vary. */
    private static Map<String, Object> validTrustList() {
        Map<String, Object> properties = new LinkedHashMap<>();
        putValidProvider(properties, PROVIDER, ISSUER);
        return properties;
    }

    /** Starts an isolated context from the supplied properties; the caller closes it. */
    private static ApplicationContext startContext(Map<String, Object> properties) {
        return ApplicationContext.builder()
                .enableDefaultPropertySources(false)
                .properties(STARTUP_KEYS)
                .properties(properties)
                .build()
                .start();
    }

    /**
     * Starts an isolated context with the given values delivered as raw environment variables; the
     * caller closes it.
     */
    private static ApplicationContext startContextWithEnvironmentVariables(
            Map<String, Object> properties, Map<String, Object> environmentVariables) {
        return ApplicationContext.builder()
                .enableDefaultPropertySources(false)
                .properties(STARTUP_KEYS)
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
     * Starts an isolated context with environment deduction off, so the active environments are
     * exactly those given rather than an inferred {@link Environment#TEST}.
     */
    private static ApplicationContext startContextInEnvironments(
            Map<String, Object> properties, String... environments) {
        return ApplicationContext.builder()
                .enableDefaultPropertySources(false)
                .properties(STARTUP_KEYS)
                .deduceEnvironment(false)
                .environments(environments)
                .properties(properties)
                .build()
                .start();
    }

    /** Returns the assembled trust list, ordered by provider name. */
    private static List<IdentityProviderConfiguration> providersOf(ApplicationContext context) {
        return context.getBean(IdentityProviderRegistryValidator.class).getProviders();
    }

    /**
     * Asserts the configuration prevents startup with every fragment in the stack trace; pass a
     * validator phrase too where the property name alone could come from a binding failure.
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
            }
        }

        @Test
        @DisplayName("binds a claim path as the segments it was configured with")
        void bindsNestedClaimPaths() {
            try (ApplicationContext context = startContext(validTrustList())) {
                IdentityProviderConfiguration.ClaimsConfiguration claims =
                        providersOf(context).get(0).getClaims();

                assertThat(claims.getRoles())
                        .as("a nested roles path must survive binding unchanged")
                        .containsExactlyElementsOf(NESTED_ROLES_CLAIM);
                assertThat(claims.getUserIdentifier()).containsExactly("sub");
                assertThat(claims.getParticipantIdentifier()).containsExactly(PARTICIPANT_ID_CLAIM);
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
            properties.remove(settingsKey(PROVIDER) + ".claims.user-identifier");

            try (ApplicationContext context = startContext(properties)) {
                IdentityProviderConfiguration provider = providersOf(context).get(0);

                assertThat(provider.getClockSkew())
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_CLOCK_SKEW);
                assertThat(provider.getClaims().getUserIdentifier())
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_USER_IDENTIFIER_CLAIM);
                assertThat(provider.isAllowInsecureTransport())
                        .as("cleartext is something an operator has to ask for in writing")
                        .isEqualTo(IdentityProviderConfiguration.DEFAULT_ALLOW_INSECURE_TRANSPORT);
            }
        }

        @Test
        @DisplayName("holds as many providers as are configured, each with its own settings")
        void bindsSeveralProviders() {
            Map<String, Object> properties = validTrustList();
            putValidProvider(properties, OTHER_PROVIDER, OTHER_ISSUER);
            properties.put(
                    settingsKey(OTHER_PROVIDER) + ".claims.roles", List.of(OTHER_ROLES_CLAIM));

            try (ApplicationContext context = startContext(properties)) {
                assertThat(providersOf(context))
                        .as("the trust list size is configurable, not capped")
                        .extracting(
                                IdentityProviderConfiguration::getName,
                                IdentityProviderConfiguration::getIssuer,
                                provider -> provider.getClaims().getRoles())
                        .containsExactly(
                                Tuple.tuple(PROVIDER, ISSUER, NESTED_ROLES_CLAIM),
                                Tuple.tuple(
                                        OTHER_PROVIDER, OTHER_ISSUER, List.of(OTHER_ROLES_CLAIM)));
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

        /** One malformed variant per case: name, overrides, removed keys, expected fragment. */
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
                            "blank user-identifier claim segment",
                            Map.of(prefix + ".claims.user-identifier", List.of("  ")),
                            List.of(),
                            "claims.user-identifier must not contain a blank segment"),
                    Arguments.of(
                            "blank segment inside a nested roles path",
                            Map.of(prefix + ".claims.roles", List.of("realm_access", " ")),
                            List.of(),
                            "claims.roles must not contain a blank segment"),
                    Arguments.of(
                            "missing roles claim",
                            Map.of(),
                            List.of(prefix + ".claims.roles"),
                            "claims.roles must name at least one segment"),
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
        @DisplayName("rejects two providers that declare the same issuer")
        void rejectsDuplicateIssuers() {
            Map<String, Object> properties = validTrustList();
            putValidProvider(properties, OTHER_PROVIDER, ISSUER);

            // A token is matched to its provider by issuer alone, so a repeated issuer would make
            // the lookup - and therefore the audience and role mapping applied - ambiguous.
            assertStartupFails(properties, "each issuer must appear exactly once");
        }

        @ParameterizedTest(name = "unrecognised key \"{0}\" aborts startup")
        @ValueSource(
                strings = {
                    "audiance",
                    "claims.email",
                    "role-mappings.user",
                    "jwks-url",
                    // The JWK Set cache is global to micronaut-security-jwt, so a per-provider
                    // lifetime would bind to nothing; a silently ignored knob must stay rejected.
                    RETIRED_JWKS_CACHE_TTL_KEY
                })
        @DisplayName("rejects a settings key this service does not read")
        void rejectsUnrecognisedSettingKeys(String key) {
            Map<String, Object> properties = validTrustList();
            properties.put(settingsKey(PROVIDER) + "." + key, "whatever");

            // @EachProperty ignores unknown keys silently, so a misspelled security setting would
            // otherwise bind to nothing and start cleanly.
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
            // @Requires compares the property to "false" case-sensitively, so this spelling keeps
            // the guard registered; reading it back as a Boolean would wrongly report it removed.
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

        @ParameterizedTest(name = "a cleartext {0} aborts startup")
        @CsvSource({"issuer", "discovery-url"})
        @DisplayName("rejects a provider addressed over cleartext http without its opt-in")
        void rejectsCleartextProviderUrl(String key) {
            Map<String, Object> properties = validTrustList();
            properties.put(
                    settingsKey(PROVIDER) + "." + key,
                    cleartext(String.valueOf(properties.get(settingsKey(PROVIDER) + "." + key))));

            // The trust anchor is what is at stake, so the message has to say so rather than just
            // name a property: an operator who reads only "use https" will reach for the opt-in.
            assertStartupFails(
                    properties,
                    settingsKey(PROVIDER) + "." + key,
                    "trust anchor",
                    ALLOW_INSECURE_TRANSPORT.substring(1));
        }

        @Test
        @DisplayName("accepts a cleartext provider once its entry opts in")
        void acceptsCleartextProviderWithOptIn() {
            Map<String, Object> properties = validTrustList();
            properties.put(settingsKey(PROVIDER) + ".issuer", cleartext(ISSUER));
            properties.put(
                    settingsKey(PROVIDER) + ".discovery-url", cleartext(ISSUER + DISCOVERY_SUFFIX));
            properties.put(settingsKey(PROVIDER) + ALLOW_INSECURE_TRANSPORT, true);

            try (ApplicationContext context = startContext(properties)) {
                assertThat(providersOf(context).get(0).isAllowInsecureTransport())
                        .as(
                                "a local Keycloak is legitimately cleartext, so this is an opt-in"
                                        + " rather than a prohibition")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("keeps the opt-in to the entry that declares it")
        void optInDoesNotLeakToAnotherProvider() {
            Map<String, Object> properties = validTrustList();
            properties.put(settingsKey(PROVIDER) + ".issuer", cleartext(ISSUER));
            properties.put(
                    settingsKey(PROVIDER) + ".discovery-url", cleartext(ISSUER + DISCOVERY_SUFFIX));
            properties.put(settingsKey(PROVIDER) + ALLOW_INSECURE_TRANSPORT, true);
            putValidProvider(properties, OTHER_PROVIDER, cleartext(OTHER_ISSUER));

            assertStartupFails(properties, settingsKey(OTHER_PROVIDER) + ".issuer", "trust anchor");
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

            // Assert the validator's own wording: Micronaut repairs some malformed values and fails
            // to convert others, either of which would also name the property.
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

    /** Rewrites a URL built on {@link #UNREACHABLE_AUTHORITY} to its {@code http} equivalent. */
    private static String cleartext(String url) {
        return url.replace(UNREACHABLE_AUTHORITY, CLEARTEXT_AUTHORITY);
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
     * Tests the role precedence a multi-role token settles on, a compatibility promise published in
     * {@code api/components/security.yaml}.
     */
    @Nested
    @DisplayName("Role.effective")
    class RolePrecedence {

        /** Each granted role set and its effective role on an any-authenticated operation. */
        static Stream<Arguments> grantedRoles() {
            return Stream.of(
                    Arguments.of(Set.of(Role.USER), Role.USER),
                    Arguments.of(Set.of(Role.PARTICIPANT), Role.PARTICIPANT),
                    Arguments.of(Set.of(Role.CATALOG), Role.CATALOG),
                    Arguments.of(Set.of(Role.USER, Role.PARTICIPANT), Role.PARTICIPANT),
                    Arguments.of(Set.of(Role.USER, Role.CATALOG), Role.CATALOG),
                    Arguments.of(Set.of(Role.PARTICIPANT, Role.CATALOG), Role.CATALOG),
                    Arguments.of(Set.of(Role.USER, Role.PARTICIPANT, Role.CATALOG), Role.CATALOG));
        }

        @ParameterizedTest(name = "{0} acts as {1}")
        @MethodSource("grantedRoles")
        @DisplayName("acts as the highest-precedence role the token grants")
        void picksTheHighestPrecedenceRole(Set<Role> granted, Role expected) {
            assertThat(Role.effective(granted, Set.of())).contains(expected);
        }

        /** Granted roles, roles the operation accepts, and the identity the token acts as. */
        static Stream<Arguments> scopedOperations() {
            Set<Role> userAndParticipant = Set.of(Role.USER, Role.PARTICIPANT);
            Set<Role> participantAndCatalog = Set.of(Role.PARTICIPANT, Role.CATALOG);
            return Stream.of(
                    Arguments.of(userAndParticipant, Set.of(Role.USER), Role.USER),
                    Arguments.of(userAndParticipant, Set.of(Role.PARTICIPANT), Role.PARTICIPANT),
                    Arguments.of(
                            userAndParticipant,
                            Set.of(Role.USER, Role.PARTICIPANT),
                            Role.PARTICIPANT),
                    Arguments.of(participantAndCatalog, Set.of(Role.PARTICIPANT), Role.PARTICIPANT),
                    Arguments.of(participantAndCatalog, Set.of(Role.CATALOG), Role.CATALOG));
        }

        @ParameterizedTest(name = "{0} on an operation accepting {1} acts as {2}")
        @MethodSource("scopedOperations")
        @DisplayName("acts as a role the operation itself accepts")
        void prefersARoleTheOperationAccepts(Set<Role> granted, Set<Role> accepted, Role expected) {
            assertThat(Role.effective(granted, accepted)).contains(expected);
        }

        @Test
        @DisplayName("returns empty when the token grants no role the operation accepts")
        void returnsEmptyWhenNoGrantedRoleIsAccepted() {
            assertThat(Role.effective(Set.of(Role.USER), Set.of(Role.CATALOG))).isEmpty();
        }

        @Test
        @DisplayName("returns empty when the token grants no role at all")
        void returnsEmptyWithoutAnyRole() {
            assertThat(Role.effective(Set.of(), Set.of())).isEmpty();
        }

        @Test
        @DisplayName("returns empty for null")
        void returnsEmptyForNull() {
            assertThat(Role.effective(null, Set.of())).isEqualTo(Optional.empty());
        }
    }

    /**
     * Tests what environment variables can and cannot do to the trust list; the validator reads raw
     * variable names because Micronaut does not enumerate them under a prefix.
     */
    @Nested
    @DisplayName("environment-variable binding")
    class EnvironmentVariableBinding {

        /** Prefix of the environment variables that configure a provider entry. */
        private static final String ENV_PREFIX = "CONSENT_MANAGER_IDENTITY_PROVIDERS_";

        /** Environment-variable spelling of a YAML key below the provider under test. */
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
        @ValueSource(
                strings = {
                    "audiance",
                    "jwks-url",
                    "claims.email",
                    // Environment-variable spelling of the per-provider cache lifetime, checked
                    // against a separate list from the YAML spelling, so both must reject it.
                    RETIRED_JWKS_CACHE_TTL_KEY
                })
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

        /** One documented key per case: the key, the value an operator sets, its accessor. */
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
                            "clock-skew",
                            "90s",
                            (Function<IdentityProviderConfiguration, Object>)
                                    IdentityProviderConfiguration::getClockSkew,
                            (Object) Duration.ofSeconds(90)),
                    // A nested path is supplied comma-separated, which is the form .env.sample
                    // documents; a name that merely contains dots carries no comma and binds as
                    // the single segment it is.
                    Arguments.of(
                            "claims.roles",
                            "resource_access,consent-manager,roles",
                            (Function<IdentityProviderConfiguration, Object>)
                                    provider -> provider.getClaims().getRoles(),
                            (Object) List.of("resource_access", "consent-manager", "roles")),
                    Arguments.of(
                            "claims.participant-identifier",
                            "https://example.com/participant_id",
                            (Function<IdentityProviderConfiguration, Object>)
                                    provider -> provider.getClaims().getParticipantIdentifier(),
                            (Object) List.of("https://example.com/participant_id")),
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

    @Nested
    @DisplayName("Claim names configured as segments")
    class ClaimNameSegments {

        /** One configured claim name per case, and whether it reads as an unsplit dotted path. */
        static Stream<Arguments> claimNames() {
            return Stream.of(
                    Arguments.of("a dotted name left unsplit", List.of("realm_access.roles"), true),
                    Arguments.of("a URI-shaped name", List.of("https://example.com/roles"), false),
                    Arguments.of(
                            "the same name split into segments",
                            List.of("realm_access", "roles"),
                            false),
                    Arguments.of("a plain top-level name", List.of("sub"), false),
                    Arguments.of("no name at all", List.of(), false));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("claimNames")
        @DisplayName(
                "are reported as an unsplit path only when a dot can no longer have been meant")
        void detectsUnsplitPaths(String description, List<String> segments, boolean expected) {
            assertThat(
                            IdentityProviderConfiguration.ClaimsConfiguration.looksLikeUnsplitPath(
                                    segments))
                    .as("%s", description)
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "an explicitly empty {0} aborts startup")
        @ValueSource(strings = {"user-identifier", "participant-identifier", "roles"})
        @DisplayName("abort startup when configured empty, rather than falling back to a default")
        void rejectsAnExplicitlyEmptyClaimName(String property) {
            Map<String, Object> properties = validTrustList();
            properties.put(settingsKey(PROVIDER) + ".claims." + property, List.of());

            assertStartupFails(
                    properties, "claims." + property + " must name at least one segment");
        }
    }
}
