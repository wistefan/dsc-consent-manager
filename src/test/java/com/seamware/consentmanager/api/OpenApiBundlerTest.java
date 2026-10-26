package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Runs the build's bundler, in the single-file mode the build uses, against fixture specifications.
 */
@DisplayName("OpenAPI bundler")
class OpenApiBundlerTest {

    /** The bundler source, relative to the module directory the tests run from. */
    private static final Path BUNDLER = Path.of("src", "build", "java", "OpenApiBundler.java");

    /** One directory per case, each holding a root document and the files it references. */
    private static final Path FIXTURES = Path.of("src", "test", "resources", "bundler");

    private static final String ROOT_DOCUMENT = "openapi.yaml";

    /** The bundle the {@code valid} fixture must produce, byte for byte. */
    private static final String EXPECTED_BUNDLE = "expected-bundle.yaml";

    private static final String VALID_FIXTURE = "valid";

    /** Generous for a JVM compiling one source file, short enough to fail a hung run. */
    private static final long RUN_TIMEOUT_SECONDS = 60;

    @TempDir Path output;

    @Test
    @DisplayName("bundles a spec into one document, keeping key order, scalars and component names")
    void bundlesTheValidFixture() throws IOException {
        Run run = bundle(VALID_FIXTURE);

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(Files.readString(bundleFile(), StandardCharsets.UTF_8))
                .isEqualTo(
                        Files.readString(
                                FIXTURES.resolve(VALID_FIXTURE).resolve(EXPECTED_BUNDLE),
                                StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "unnamed-file, names a file no root component declares",
        "stray-ref-in-inline-component, names a file no root component declares",
        "internal-ref-in-component-file, cannot survive bundling",
        "internal-ref-in-sibling-file, cannot survive bundling",
        "duplicate-component-target, ambiguous",
        "missing-pointer-target, does not exist",
    })
    @DisplayName("refuses a spec it cannot bundle faithfully, writing nothing")
    void refusesAnUnbundleableSpec(String fixture, String reason) {
        Run run = bundle(fixture);

        assertThat(run.exitCode()).as(run.output()).isNotZero();
        assertThat(run.output()).contains(reason);
        assertThat(bundleFile()).doesNotExist();
    }

    private Path bundleFile() {
        return output.resolve(ROOT_DOCUMENT);
    }

    private Run bundle(String fixture) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder =
                new ProcessBuilder(
                                java.toString(),
                                "-classpath",
                                snakeYamlJar().toString(),
                                BUNDLER.toString(),
                                FIXTURES.resolve(fixture).resolve(ROOT_DOCUMENT).toString(),
                                bundleFile().toString())
                        .redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the bundler finishes")
                    .isTrue();
            return new Run(process.exitValue(), output);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run the bundler", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running the bundler", e);
        }
    }

    /** The jar the build hands the bundler, located through the test classpath. */
    private static Path snakeYamlJar() {
        try {
            return Path.of(Yaml.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unable to locate SnakeYAML", e);
        }
    }

    /** What one bundler run exited with and printed, stderr included. */
    private record Run(int exitCode, String output) {}
}
