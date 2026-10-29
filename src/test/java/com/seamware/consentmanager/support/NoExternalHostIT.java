package com.seamware.consentmanager.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.env.Environment;
import io.micronaut.context.env.EnvironmentPropertySource;
import io.micronaut.context.env.KubernetesEnvironmentPropertySource;
import io.micronaut.context.env.SystemPropertiesPropertySource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Proves the test configuration dials no external host: every URL-valued property the context
 * resolves names loopback or the Testcontainers Docker host, which is not loopback on a sidecar.
 */
@MicronautTest(transactional = false)
@DisplayName("Test configuration names no external host")
class NoExternalHostIT extends KeycloakAndPostgresTestResource {

    /** A value the service would dial: an absolute HTTP(S) URL or a JDBC one. */
    private static final Pattern DIALLED_URL =
            Pattern.compile("^(https?://|jdbc:).+", Pattern.CASE_INSENSITIVE);

    private static final String JDBC_SCHEME = "jdbc:";

    private static final String LOOPBACK_PREFIX = "127.";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "::1", "[::1]");

    /** Host-supplied sources, not test configuration; their contents vary by machine. */
    private static final Set<String> HOST_SOURCES =
            Set.of(
                    EnvironmentPropertySource.NAME,
                    KubernetesEnvironmentPropertySource.NAME,
                    SystemPropertiesPropertySource.NAME);

    /** A URL the context certainly carries, so the walk cannot pass vacuously. */
    private static final String DATASOURCE_URL = "datasources.default.url";

    @Inject Environment environment;

    @Test
    @DisplayName("every URL-valued property names loopback or the Docker host")
    void everyDialledUrlStaysLocal() {
        String dockerHost = DockerClientFactory.instance().dockerHostIpAddress();
        Map<String, String> urls = dialledUrls();

        assertThat(urls).containsKey(DATASOURCE_URL);
        assertThat(urls)
                .allSatisfy(
                        (key, url) ->
                                assertThat(isLocal(hostOf(url), dockerHost))
                                        .as("%s = %s names an external host", key, url)
                                        .isTrue());
    }

    /** Every resolved property value that is a URL, by key; placeholders are resolved first. */
    private Map<String, String> dialledUrls() {
        Map<String, String> urls = new TreeMap<>();
        environment.getPropertySources().stream()
                .filter(source -> !HOST_SOURCES.contains(source.getName()))
                .flatMap(source -> StreamSupport.stream(source.spliterator(), false))
                .forEach(
                        key ->
                                environment
                                        .getProperty(key, Object.class)
                                        .ifPresent(value -> collect(key, value, urls)));
        return urls;
    }

    private static void collect(String key, Object value, Map<String, String> urls) {
        switch (value) {
            case Collection<?> values -> values.forEach(element -> collect(key, element, urls));
            case Map<?, ?> nested ->
                    nested.forEach((name, element) -> collect(key + "." + name, element, urls));
            case CharSequence text when DIALLED_URL.matcher(text.toString().trim()).matches() ->
                    urls.put(key, text.toString().trim());
            default -> {}
        }
    }

    private static String hostOf(String url) {
        String dialled =
                url.regionMatches(true, 0, JDBC_SCHEME, 0, JDBC_SCHEME.length())
                        ? url.substring(JDBC_SCHEME.length())
                        : url;
        String host = URI.create(dialled).getHost();
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    private static boolean isLocal(String host, String dockerHost) {
        return LOOPBACK_HOSTS.contains(host)
                || host.startsWith(LOOPBACK_PREFIX)
                || host.equalsIgnoreCase(dockerHost);
    }
}
