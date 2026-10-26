package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Lints the bundled specification with {@code api/.spectral.yaml} and fails on any error, and
 * proves every custom rule fails a fixture under {@code spectral/} that breaks it.
 */
@DisplayName("Linting the OpenAPI specification with Spectral")
class SpecLintIT {

    /** Pinned Spectral CLI; bump deliberately, since a release can change the built-in rules. */
    private static final String SPECTRAL_IMAGE = "stoplight/spectral:6.15.0";

    private static final String RULESET_IN_CONTAINER = "/work/.spectral.yaml";
    private static final String SPEC_IN_CONTAINER = "/work/openapi.yaml";
    private static final Path RULESET = Path.of("api", ".spectral.yaml");

    /**
     * System property Failsafe sets from the pom's {@code openapi.bundle.file}, the bundle's path.
     */
    private static final String BUNDLE_FILE_PROPERTY = "openapi.bundle.file";

    private static final String FIXTURE_DIRECTORY = "spectral/";
    private static final String FIXTURE_EXTENSION = ".yaml";

    /** The fixture every rule-breaking fixture is a one-change variant of. */
    private static final String VALID_FIXTURE = "valid";

    /** Spectral's numeric severity for an error. */
    private static final int ERROR_SEVERITY = 0;

    /** Spectral's exit status when it found no error; any runtime failure exits non-zero too. */
    private static final long EXIT_CLEAN = 0;

    /** Spectral's exit status when it found an error, as distinct from failing to run. */
    private static final long EXIT_ERRORS_FOUND = 1;

    private static final Duration LINT_TIMEOUT = Duration.ofMinutes(2);

    @Test
    @DisplayName("the bundled specification has no error-severity finding")
    void bundledSpecificationPasses() {
        Path bundle =
                Path.of(
                        Objects.requireNonNull(
                                System.getProperty(BUNDLE_FILE_PROPERTY),
                                BUNDLE_FILE_PROPERTY + " is unset; run under Failsafe"));

        assertClean(lint(MountableFile.forHostPath(bundle)));
    }

    @Test
    @DisplayName("the fixture the rule-breaking fixtures vary passes")
    void validFixturePasses() {
        assertClean(lint(fixture(VALID_FIXTURE)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "operation-operationId",
                "operation-summary",
                "operation-security-declared",
                "operation-tag-defined",
                "secured-operation-shared-auth-responses",
                "client-error-responses-shared",
                "schema-property-description",
                "path-segments-kebab-case",
                "property-names-camel-case"
            })
    @DisplayName("a fixture breaking a rule fails on that rule alone")
    void fixtureBreakingRuleFails(String rule) {
        Lint lint = lint(fixture(rule));

        assertThat(lint.exitCode()).as(lint.report()).isEqualTo(EXIT_ERRORS_FOUND);
        assertThat(lint.errorCodes()).as(lint.report()).containsExactly(rule);
    }

    private static void assertClean(Lint lint) {
        assertThat(lint.errorCodes()).as(lint.report()).isEmpty();
        assertThat(lint.exitCode()).as(lint.report()).isEqualTo(EXIT_CLEAN);
    }

    private static MountableFile fixture(String name) {
        return MountableFile.forClasspathResource(FIXTURE_DIRECTORY + name + FIXTURE_EXTENSION);
    }

    private static Lint lint(MountableFile spec) {
        try (GenericContainer<?> spectral =
                new GenericContainer<>(DockerImageName.parse(SPECTRAL_IMAGE))
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(RULESET), RULESET_IN_CONTAINER)
                        .withCopyFileToContainer(spec, SPEC_IN_CONTAINER)
                        .withCommand(
                                "lint",
                                "--ruleset",
                                RULESET_IN_CONTAINER,
                                "--format",
                                "json",
                                "--quiet",
                                SPEC_IN_CONTAINER)
                        .withStartupCheckStrategy(new RunToExit().withTimeout(LINT_TIMEOUT))) {
            spectral.start();
            Long exitCode =
                    spectral.getDockerClient()
                            .inspectContainerCmd(spectral.getContainerId())
                            .exec()
                            .getState()
                            .getExitCodeLong();
            return Lint.of(
                    exitCode,
                    spectral.getLogs(OutputType.STDOUT),
                    spectral.getLogs(OutputType.STDERR));
        }
    }

    /** One Spectral run: its exit status, its JSON findings and whatever it wrote to stderr. */
    private record Lint(long exitCode, List<Map<String, Object>> findings, String stderr) {

        private static final TypeReference<List<Map<String, Object>>> FINDINGS =
                new TypeReference<>() {};

        static Lint of(Long exitCode, String stdout, String stderr) {
            Objects.requireNonNull(exitCode, "Spectral did not exit");
            try {
                // With --quiet a clean run may print nothing rather than `[]`.
                List<Map<String, Object>> findings =
                        stdout.isBlank()
                                ? List.of()
                                : new ObjectMapper().readValue(stdout, FINDINGS);
                return new Lint(exitCode, findings, stderr);
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException("Spectral wrote no JSON:%n%s".formatted(stdout), e);
            }
        }

        Set<String> errorCodes() {
            return findings.stream()
                    .filter(finding -> finding.get("severity").equals(ERROR_SEVERITY))
                    .map(finding -> (String) finding.get("code"))
                    .collect(Collectors.toSet());
        }

        /** The findings one per line, for the assertion message. */
        String report() {
            String lines =
                    findings.stream()
                            .map(
                                    finding ->
                                            "%s %s %s %s"
                                                    .formatted(
                                                            finding.get("severity"),
                                                            finding.get("code"),
                                                            finding.get("path"),
                                                            finding.get("message")))
                            .collect(Collectors.joining(System.lineSeparator()));
            return "Spectral exited %d (severity 0 is an error):%n%s%n%s"
                    .formatted(exitCode, lines, stderr);
        }
    }

    /** Waits for the one-shot container to exit, whatever its status, which is read afterwards. */
    private static final class RunToExit extends StartupCheckStrategy {

        @Override
        public StartupStatus checkStartupState(DockerClient dockerClient, String containerId) {
            return Boolean.TRUE.equals(getCurrentState(dockerClient, containerId).getRunning())
                    ? StartupStatus.NOT_YET_KNOWN
                    : StartupStatus.SUCCESSFUL;
        }
    }
}
