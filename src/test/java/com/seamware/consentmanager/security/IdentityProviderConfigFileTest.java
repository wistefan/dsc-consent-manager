package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.TestPseudonymizer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.yaml.YamlPropertySourceLoader;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the Helm chart's split trust list binds: {@code primary} from the {@code IDP_*} env vars
 * through application.yml's placeholders, the rest from the chart-rendered YAML the pod loads via
 * {@code MICRONAUT_CONFIG_FILES} (fixture regenerated from the chart; see its header).
 */
@DisplayName("Helm-rendered identity provider configuration")
class IdentityProviderConfigFileTest {

    /** Chart-rendered identity-providers.yml, as mounted in the pod. */
    private static final String RENDERED_FILE_RESOURCE = "helm/identity-providers.yml";

    private static final String APPLICATION_YAML = "application.yml";

    /** The block of application.yml that the IDP_* placeholders feed. */
    private static final String PRIMARY_PREFIX = IdentityProviderConfiguration.PREFIX + ".primary";

    /** Unreachable authorities: startup must not need discovery. */
    private static final String PRIMARY_ISSUER = "https://localhost:1/realms/consent-manager";

    private static final String PARTNER_ISSUER = "https://partner-idp.example.org/realms/dataspace";

    /** The pod environment the chart's ConfigMap renders from ci/all-enabled-values.yaml. */
    private static final Map<String, Object> CHART_ENVIRONMENT =
            Map.ofEntries(
                    Map.entry("IDP_ISSUER", PRIMARY_ISSUER),
                    Map.entry(
                            "IDP_DISCOVERY_URL",
                            PRIMARY_ISSUER + "/.well-known/openid-configuration"),
                    Map.entry("IDP_AUDIENCE", "consent-manager"),
                    Map.entry("IDP_ALLOW_INSECURE_TRANSPORT", "false"),
                    Map.entry("IDP_CLOCK_SKEW", "30s"),
                    Map.entry("IDP_CLAIM_USER_IDENTIFIER", "sub"),
                    Map.entry("IDP_CLAIM_PARTICIPANT_IDENTIFIER", "participant_id"),
                    Map.entry("IDP_CLAIM_ROLES", "realm_access,roles"),
                    Map.entry("IDP_ROLE_USER", "consent-user"),
                    Map.entry("IDP_ROLE_PARTICIPANT", "consent-participant"),
                    Map.entry("IDP_ROLE_CATALOG", "consent-catalog"));

    /** Keys the receipt signer and log pseudonymizer need, since no default source is read. */
    private static final Map<String, Object> STARTUP_KEYS =
            Map.of(
                    "consent-manager.receipts.ephemeral-signing-key",
                    true,
                    "consent-manager.logging.pseudonym-key",
                    TestPseudonymizer.KEY);

    private static ApplicationContext context;

    @BeforeAll
    static void startContext() throws IOException {
        context =
                ApplicationContext.builder()
                        .enableDefaultPropertySources(false)
                        .properties(STARTUP_KEYS)
                        .propertySources(
                                primaryPlaceholders(),
                                PropertySource.of(
                                        "chart-configmap",
                                        CHART_ENVIRONMENT,
                                        PropertySource.PropertyConvention.ENVIRONMENT_VARIABLE),
                                PropertySource.of(
                                        RENDERED_FILE_RESOURCE, readYaml(RENDERED_FILE_RESOURCE)))
                        .build()
                        .start();
    }

    @AfterAll
    static void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * The primary entry of application.yml with its {@code ${IDP_*}} placeholders unresolved, so
     * binding follows the production path without the datasource the rest of the file declares.
     */
    private static PropertySource primaryPlaceholders() throws IOException {
        Map<String, Object> primary =
                readYaml(APPLICATION_YAML).entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(PRIMARY_PREFIX + "."))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return PropertySource.of(APPLICATION_YAML, primary);
    }

    /** Flattens a classpath YAML with the loader Micronaut applies to its config files. */
    private static Map<String, Object> readYaml(String resource) throws IOException {
        try (InputStream yaml =
                IdentityProviderConfigFileTest.class
                        .getClassLoader()
                        .getResourceAsStream(resource)) {
            return new YamlPropertySourceLoader()
                    .read(resource, Objects.requireNonNull(yaml, resource));
        }
    }

    static Stream<Arguments> expectedProviders() {
        return Stream.of(
                Arguments.of(
                        "primary",
                        PRIMARY_ISSUER,
                        List.of("participant_id"),
                        List.of("realm_access", "roles"),
                        Map.of(
                                Role.USER,
                                "consent-user",
                                Role.PARTICIPANT,
                                "consent-participant",
                                Role.CATALOG,
                                "consent-catalog")),
                Arguments.of(
                        "partner",
                        PARTNER_ISSUER,
                        List.of("participant"),
                        List.of("groups"),
                        Map.of(
                                Role.USER,
                                "partner-user",
                                Role.PARTICIPANT,
                                "partner-participant")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expectedProviders")
    @DisplayName("binds each provider from its source and passes registry validation")
    void bindsProvider(
            String name,
            String issuer,
            List<String> participantIdentifier,
            List<String> roles,
            Map<Role, String> roleMapping) {
        List<IdentityProviderConfiguration> providers =
                context.getBean(IdentityProviderRegistryValidator.class).getProviders();

        assertThat(providers)
                .extracting(IdentityProviderConfiguration::getName)
                .containsExactly("partner", "primary");
        IdentityProviderConfiguration provider =
                providers.stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
        assertThat(provider.getIssuer()).isEqualTo(issuer);
        assertThat(provider.getClaims().getUserIdentifier()).containsExactly("sub");
        assertThat(provider.getClaims().getParticipantIdentifier())
                .isEqualTo(participantIdentifier);
        assertThat(provider.getClaims().getRoles()).isEqualTo(roles);
        assertThat(provider.getResolvedRoleMapping()).isEqualTo(roleMapping);
    }
}
