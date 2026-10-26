package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Fails the build when the bundled specification breaks a client of {@code api/baseline/}, and
 * proves oasdiff detects each {@link Change} that must fail it and passes the one that must not.
 */
@DisplayName("Detecting breaking changes against the baseline specification")
class SpecCompatibilityIT {

    /**
     * Pinned oasdiff CLI; bump deliberately, since a release can change what counts as breaking.
     */
    private static final String OASDIFF_IMAGE = "tufin/oasdiff:v1.34.1";

    private static final String BASE_IN_CONTAINER = "/work/base.yaml";
    private static final String REVISION_IN_CONTAINER = "/work/revision.yaml";
    private static final Path BASELINE = Path.of("api", "baseline", "openapi.yaml");

    /** System property Failsafe sets from the pom's {@code openapi.bundle.file}. */
    private static final String BUNDLE_FILE_PROPERTY = "openapi.bundle.file";

    // The baseline's nodes a Change edits.
    private static final String STATUS_PATH = "/api-status";
    private static final String STATUS_SCHEMA = "ApiStatus";
    private static final String STATUS_PROPERTY = "status";
    private static final String GRANT_SCHEMA = "GrantConsent";
    private static final String ADDED_PROPERTY = "note";

    /** oasdiff's exit status when it found nothing at or above {@code --fail-on}. */
    private static final long EXIT_COMPATIBLE = 0;

    /** oasdiff's exit status when it found an error-level change, as distinct from failing. */
    private static final long EXIT_BREAKING = 1;

    private static final Duration DIFF_TIMEOUT = Duration.ofMinutes(2);

    @Test
    @DisplayName("the bundled specification breaks no client of the baseline")
    void bundledSpecificationIsCompatible() {
        Path bundle =
                Path.of(
                        Objects.requireNonNull(
                                System.getProperty(BUNDLE_FILE_PROPERTY),
                                BUNDLE_FILE_PROPERTY + " is unset; run under Failsafe"));

        Diff diff = diff(MountableFile.forHostPath(bundle));

        assertThat(diff.findings()).as(diff.report()).isEmpty();
        assertThat(diff.exitCode()).as(diff.report()).isEqualTo(EXIT_COMPATIBLE);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Change.class)
    @DisplayName("one edit to the baseline is reported breaking exactly when it should be")
    void changeIsClassified(Change change) {
        Diff diff = diff(Transferable.of(change.applyTo(baseline())));

        if (change.breakingRule == null) {
            assertThat(diff.findings()).as(diff.report()).isEmpty();
            assertThat(diff.exitCode()).as(diff.report()).isEqualTo(EXIT_COMPATIBLE);
        } else {
            assertThat(diff.ruleIds()).as(diff.report()).contains(change.breakingRule);
            assertThat(diff.exitCode()).as(diff.report()).isEqualTo(EXIT_BREAKING);
        }
    }

    private static Map<String, Object> baseline() {
        try (InputStream in = Files.newInputStream(BASELINE)) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Diff diff(Transferable revision) {
        try (GenericContainer<?> oasdiff =
                new GenericContainer<>(DockerImageName.parse(OASDIFF_IMAGE))
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(BASELINE), BASE_IN_CONTAINER)
                        .withCopyToContainer(revision, REVISION_IN_CONTAINER)
                        .withCommand(
                                "breaking",
                                "--fail-on",
                                "ERR",
                                "--format",
                                "json",
                                BASE_IN_CONTAINER,
                                REVISION_IN_CONTAINER)
                        .withStartupCheckStrategy(new RunToExit().withTimeout(DIFF_TIMEOUT))) {
            oasdiff.start();
            Long exitCode =
                    oasdiff.getDockerClient()
                            .inspectContainerCmd(oasdiff.getContainerId())
                            .exec()
                            .getState()
                            .getExitCodeLong();
            return Diff.of(
                    exitCode,
                    oasdiff.getLogs(OutputType.STDOUT),
                    oasdiff.getLogs(OutputType.STDERR));
        }
    }

    /** One edit to the baseline and the oasdiff rule it must trip, or null for a compatible one. */
    enum Change {
        REMOVED_OPERATION(
                "api-path-removed-without-deprecation",
                spec -> child(spec, "paths").remove(STATUS_PATH)),
        REMOVED_RESPONSE_PROPERTY(
                "response-required-property-removed",
                spec -> {
                    Map<String, Object> schema = schema(spec, STATUS_SCHEMA);
                    child(schema, "properties").remove(STATUS_PROPERTY);
                    schema.remove("required");
                }),
        NEWLY_REQUIRED_REQUEST_PROPERTY(
                "new-required-request-property",
                spec -> {
                    Map<String, Object> schema = addProperty(spec, GRANT_SCHEMA);
                    List<Object> required = new ArrayList<>(list(schema, "required"));
                    required.add(ADDED_PROPERTY);
                    schema.put("required", required);
                }),
        ADDED_OPTIONAL_REQUEST_PROPERTY(null, spec -> addProperty(spec, GRANT_SCHEMA));

        private final String breakingRule;
        private final Consumer<Map<String, Object>> mutation;

        Change(String breakingRule, Consumer<Map<String, Object>> mutation) {
            this.breakingRule = breakingRule;
            this.mutation = mutation;
        }

        String applyTo(Map<String, Object> spec) {
            mutation.accept(spec);
            return new Yaml().dump(spec);
        }

        @Override
        public String toString() {
            return breakingRule == null ? name() + " (compatible)" : name() + " -> " + breakingRule;
        }

        private static Map<String, Object> addProperty(Map<String, Object> spec, String name) {
            Map<String, Object> schema = schema(spec, name);
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", "string");
            property.put("description", "Added by the compatibility fixture.");
            child(schema, "properties").put(ADDED_PROPERTY, property);
            return schema;
        }

        private static Map<String, Object> schema(Map<String, Object> spec, String name) {
            return child(child(child(spec, "components"), "schemas"), name);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> child(Map<String, Object> node, String key) {
            return (Map<String, Object>) Objects.requireNonNull(node.get(key), key);
        }

        @SuppressWarnings("unchecked")
        private static List<Object> list(Map<String, Object> node, String key) {
            return (List<Object>) Objects.requireNonNull(node.get(key), key);
        }
    }

    /** One oasdiff run: its exit status, its JSON findings and whatever it wrote to stderr. */
    private record Diff(long exitCode, List<Map<String, Object>> findings, String stderr) {

        private static final TypeReference<List<Map<String, Object>>> FINDINGS =
                new TypeReference<>() {};

        static Diff of(Long exitCode, String stdout, String stderr) {
            Objects.requireNonNull(exitCode, "oasdiff did not exit");
            try {
                List<Map<String, Object>> findings =
                        stdout.isBlank()
                                ? List.of()
                                : new ObjectMapper().readValue(stdout, FINDINGS);
                return new Diff(exitCode, findings, stderr);
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException("oasdiff wrote no JSON:%n%s".formatted(stdout), e);
            }
        }

        Set<String> ruleIds() {
            return findings.stream()
                    .map(finding -> (String) finding.get("id"))
                    .collect(Collectors.toSet());
        }

        /** The findings one per line, for the assertion message. */
        String report() {
            String lines =
                    findings.stream()
                            .map(
                                    finding ->
                                            "%s %s %s %s %s"
                                                    .formatted(
                                                            finding.get("level"),
                                                            finding.get("id"),
                                                            finding.get("operation"),
                                                            finding.get("path"),
                                                            finding.get("text")))
                            .collect(Collectors.joining(System.lineSeparator()));
            return "oasdiff exited %d against %s:%n%s%n%s"
                    .formatted(exitCode, BASELINE, lines, stderr);
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
