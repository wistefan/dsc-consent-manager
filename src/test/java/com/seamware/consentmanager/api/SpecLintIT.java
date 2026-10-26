package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/**
 * Lints the bundled specification with {@code api/.spectral.yaml} and fails on any error, and
 * proves every custom rule fails the valid fixture under {@code spectral/} once one edit breaks it.
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

    /** The fixture each {@link Violation} breaks with one edit. */
    private static final String VALID_FIXTURE = "valid";

    // The valid fixture's nodes a Violation edits.
    private static final String SECURED_PATH = "/things/{thingId}";
    private static final String PUBLIC_PATH = "/thing-status";
    private static final String THING_PROPERTY = "thingName";
    private static final String FORBIDDEN_STATUS = "403";
    private static final String NOT_FOUND_STATUS = "404";

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
    @DisplayName("the fixture each violation edits passes")
    void validFixturePasses() {
        assertClean(lint(fixture(VALID_FIXTURE)));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Violation.class)
    @DisplayName("the valid fixture with one rule broken fails on that rule alone")
    void fixtureBreakingRuleFails(Violation violation) {
        Lint lint = lint(Transferable.of(violation.applyTo(validFixture())));

        assertThat(lint.exitCode()).as(lint.report()).isEqualTo(EXIT_ERRORS_FOUND);
        assertThat(lint.errorCodes()).as(lint.report()).containsExactly(violation.rule);
    }

    private static void assertClean(Lint lint) {
        assertThat(lint.errorCodes()).as(lint.report()).isEmpty();
        assertThat(lint.exitCode()).as(lint.report()).isEqualTo(EXIT_CLEAN);
    }

    private static MountableFile fixture(String name) {
        return MountableFile.forClasspathResource(FIXTURE_DIRECTORY + name + FIXTURE_EXTENSION);
    }

    private static Map<String, Object> validFixture() {
        try (InputStream in =
                SpecLintIT.class
                        .getClassLoader()
                        .getResourceAsStream(
                                FIXTURE_DIRECTORY + VALID_FIXTURE + FIXTURE_EXTENSION)) {
            return new Yaml().load(Objects.requireNonNull(in, "valid fixture missing"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Lint lint(Transferable spec) {
        try (GenericContainer<?> spectral =
                new GenericContainer<>(DockerImageName.parse(SPECTRAL_IMAGE))
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(RULESET), RULESET_IN_CONTAINER)
                        .withCopyToContainer(spec, SPEC_IN_CONTAINER)
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

    /** One custom rule and the single edit to the valid fixture that breaks it. */
    enum Violation {
        OPERATION_ID("operation-operationId", spec -> securedGet(spec).remove("operationId")),
        SUMMARY("operation-summary", spec -> securedGet(spec).remove("summary")),
        SECURITY_DECLARED(
                "operation-security-declared", spec -> securedGet(spec).remove("security")),
        TAG_DEFINED(
                "operation-tag-defined",
                spec -> securedGet(spec).put("tags", List.of("Undeclared"))),
        SHARED_AUTH_RESPONSES(
                "secured-operation-shared-auth-responses",
                spec -> child(securedGet(spec), "responses").remove(FORBIDDEN_STATUS)),
        CLIENT_ERRORS_SHARED(
                "client-error-responses-shared",
                spec ->
                        child(securedGet(spec), "responses")
                                .put(NOT_FOUND_STATUS, Map.of("description", "Inline 404."))),
        PROPERTY_DESCRIPTION(
                "schema-property-description",
                spec -> child(thingProperties(spec), THING_PROPERTY).remove("description")),
        PATH_KEBAB_CASE(
                "path-segments-kebab-case",
                spec -> rename(child(spec, "paths"), PUBLIC_PATH, "/thingStatus")),
        PROPERTY_CAMEL_CASE(
                "property-names-camel-case",
                spec -> rename(thingProperties(spec), THING_PROPERTY, "thing_name"));

        private final String rule;
        private final Consumer<Map<String, Object>> mutation;

        Violation(String rule, Consumer<Map<String, Object>> mutation) {
            this.rule = rule;
            this.mutation = mutation;
        }

        String applyTo(Map<String, Object> spec) {
            mutation.accept(spec);
            return new Yaml().dump(spec);
        }

        @Override
        public String toString() {
            return rule;
        }

        private static Map<String, Object> securedGet(Map<String, Object> spec) {
            return child(child(child(spec, "paths"), SECURED_PATH), "get");
        }

        private static Map<String, Object> thingProperties(Map<String, Object> spec) {
            return child(child(child(child(spec, "components"), "schemas"), "Thing"), "properties");
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> child(Map<String, Object> node, String key) {
            return (Map<String, Object>) Objects.requireNonNull(node.get(key), key);
        }

        private static void rename(Map<String, Object> node, String from, String to) {
            node.put(to, node.remove(from));
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
