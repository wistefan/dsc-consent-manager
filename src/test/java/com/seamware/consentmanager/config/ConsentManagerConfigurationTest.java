package com.seamware.consentmanager.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins that every configuration section still binds now that Lombok writes the accessors.
 *
 * <p>Micronaut binds a {@code @ConfigurationProperties} bean through the setters it can see at
 * annotation-processing time, so Lombok has to run before {@code micronaut-inject-java}. Get the
 * {@code annotationProcessorPaths} order wrong and nothing fails to compile - the beans simply bind
 * nothing and every deployment silently runs on its defaults. This suite is what makes that a build
 * failure.
 */
@DisplayName("Consent Manager configuration binding")
class ConsentManagerConfigurationTest {

    private static final String CONFIGURED_URL = "https://contract.example.org";
    private static final String CONTRACT_SERVICE_URL_VARIABLE = "CONTRACT_SERVICE_URL";

    /** One configured value per properties class, with the accessor that has to report it back. */
    static Stream<Arguments> boundSettings() {
        return Stream.of(
                setting(
                        "consent-manager.url",
                        "https://consent.example.org",
                        "https://consent.example.org",
                        context -> bean(context, ConsentManagerConfiguration.class).getUrl()),
                setting(
                        "consent-manager.contract-service.url",
                        "https://contract.example.org",
                        "https://contract.example.org",
                        context ->
                                bean(context, ConsentManagerConfiguration.ContractService.class)
                                        .getUrl()),
                setting(
                        "consent-manager.contract-service.read-timeout",
                        "11s",
                        Duration.ofSeconds(11),
                        context ->
                                bean(context, ConsentManagerConfiguration.ContractService.class)
                                        .getReadTimeout()),
                setting(
                        "consent-manager.contract-service.retry-multiplier",
                        "3.5",
                        3.5,
                        context ->
                                bean(context, ConsentManagerConfiguration.ContractService.class)
                                        .getRetryMultiplier()),
                setting(
                        "consent-manager.erasure.verification-secret",
                        "a-secret",
                        "a-secret",
                        context ->
                                bean(context, ConsentManagerConfiguration.Erasure.class)
                                        .getVerificationSecret()),
                setting(
                        "consent-manager.users.search-max-results",
                        "17",
                        17,
                        context ->
                                bean(context, ConsentManagerConfiguration.Users.class)
                                        .getSearchMaxResults()),
                setting(
                        "consent-manager.participants.page-max-size",
                        "23",
                        23,
                        context ->
                                bean(context, ConsentManagerConfiguration.Participants.class)
                                        .getPageMaxSize()));
    }

    /** The three settings application.yml forwards into the keys Micronaut's client reads. */
    static Stream<Arguments> forwardedHttpClientSettings() {
        return Stream.of(
                Arguments.of(
                        "consent-manager.contract-service.url",
                        "micronaut.http.services.contract-service.url",
                        CONFIGURED_URL),
                Arguments.of(
                        "consent-manager.contract-service.connect-timeout",
                        "micronaut.http.services.contract-service.connect-timeout",
                        "7s"),
                Arguments.of(
                        "consent-manager.contract-service.read-timeout",
                        "micronaut.http.services.contract-service.read-timeout",
                        "13s"));
    }

    @ParameterizedTest(name = "{0} binds")
    @MethodSource("boundSettings")
    @DisplayName("a configured value reaches the bean through the generated setter")
    void bindsEverySection(
            String key, String value, Object expected, Function<ApplicationContext, Object> read) {
        try (ApplicationContext context = startContext(Map.of(key, value))) {
            assertThat(read.apply(context))
                    .as("a bean bound to nothing would silently answer with its default")
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("an unset contract-service setting keeps the field's declared default")
    void keepsDeclaredDefaults() {
        try (ApplicationContext context = startContext(Map.of())) {
            ConsentManagerConfiguration.ContractService contractService =
                    bean(context, ConsentManagerConfiguration.ContractService.class);

            assertThat(contractService.getRetryAttempts())
                    .isEqualTo(ConsentManagerConfiguration.ContractService.DEFAULT_RETRY_ATTEMPTS);
            assertThat(contractService.getCacheTtl())
                    .isEqualTo(ConsentManagerConfiguration.ContractService.DEFAULT_CACHE_TTL);
        }
    }

    @ParameterizedTest(name = "{0} is forwarded to {1}")
    @MethodSource("forwardedHttpClientSettings")
    @DisplayName("a contract-service setting reaches the key the HTTP client actually reads")
    void forwardsHttpClientSettings(String source, String target, String value) {
        try (ApplicationContext context = startContext(Map.of(source, value))) {
            assertThat(context.getEnvironment().getProperty(target, String.class))
                    .as("the declarative client reads %s and nothing else", target)
                    .hasValue(value);
        }
    }

    @Test
    @DisplayName("CONTRACT_SERVICE_URL still reaches the contract-service bean")
    void bindsContractServiceUrlFromTheEnvironmentVariable() {
        try (ApplicationContext context =
                startContext(Map.of(CONTRACT_SERVICE_URL_VARIABLE, CONFIGURED_URL))) {
            assertThat(bean(context, ConsentManagerConfiguration.ContractService.class).getUrl())
                    .as(
                            "%s is the one variable a deployment must set",
                            CONTRACT_SERVICE_URL_VARIABLE)
                    .isEqualTo(CONFIGURED_URL);
        }
    }

    private static Arguments setting(
            String key, String value, Object expected, Function<ApplicationContext, Object> read) {
        return Arguments.of(key, value, expected, read);
    }

    private static <T> T bean(ApplicationContext context, Class<T> type) {
        return context.getBean(type);
    }

    /** Starts a context carrying only the overrides; no datasource, so no container is needed. */
    private static ApplicationContext startContext(Map<String, Object> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.putAll(overrides);
        return ApplicationContext.builder(Environment.TEST).properties(properties).start();
    }
}
