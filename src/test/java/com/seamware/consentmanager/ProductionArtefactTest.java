package com.seamware.consentmanager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the shipped artefact free of test doubles: WireMock and simulated upstream routes live only
 * in tests and in {@code docker/wiremock}, never in our classes nor among the runtime dependencies.
 */
@DisplayName("Production artefact")
class ProductionArtefactTest {

    /** What the jar and the image are built from. */
    private static final Path CLASSES = Path.of("target", "classes");

    /** A class or resource the scan must reach, so an empty or moved directory cannot pass. */
    private static final Path APPLICATION_CLASS =
            Path.of("com", "seamware", "consentmanager", "Application.class");

    /**
     * The runtime-scope dependency list {@code maven-dependency-plugin} writes before tests run.
     */
    private static final Path RUNTIME_DEPENDENCIES = Path.of("target", "runtime-dependencies.txt");

    /** A runtime dependency the list must name, so an empty or stale list cannot pass. */
    private static final String KNOWN_RUNTIME_DEPENDENCY =
            "io.micronaut:micronaut-http-server-netty:";

    /**
     * Every file under {@link #CLASSES}; class files are read raw, their constant pool is ASCII.
     */
    private static List<Scanned> artefact;

    @BeforeAll
    static void scan() throws IOException {
        try (Stream<Path> files = Files.walk(CLASSES)) {
            artefact = files.filter(Files::isRegularFile).map(Scanned::read).toList();
        }
    }

    @Test
    @DisplayName("the scan reaches the compiled application")
    void scanReachesTheApplication() {
        assertThat(artefact).extracting(Scanned::path).contains(CLASSES.resolve(APPLICATION_CLASS));
    }

    @ParameterizedTest(name = "nothing references \"{0}\"")
    @ValueSource(strings = {"wiremock", "tomakehurst", "/__admin", "simulat", "/mock", "/stub"})
    @DisplayName("no class or resource references a test double")
    void noClassOrResourceReferences(String marker) {
        assertThat(artefact)
                .filteredOn(scanned -> scanned.content().contains(marker))
                .extracting(Scanned::path)
                .isEmpty();
    }

    @Test
    @DisplayName("the runtime dependency list is the build's own")
    void runtimeDependencyListIsPopulated() throws IOException {
        assertThat(Files.readString(RUNTIME_DEPENDENCIES)).contains(KNOWN_RUNTIME_DEPENDENCY);
    }

    @ParameterizedTest(name = "no runtime dependency from \"{0}\"")
    @ValueSource(strings = {"org.wiremock:", "com.github.tomakehurst:"})
    @DisplayName("no test-double library ships as a runtime dependency")
    void noRuntimeDependencyFrom(String groupId) throws IOException {
        assertThat(Files.readAllLines(RUNTIME_DEPENDENCIES))
                .filteredOn(line -> line.strip().startsWith(groupId))
                .isEmpty();
    }

    /** One file of the artefact, lower-cased so a marker matches whatever its casing. */
    private record Scanned(Path path, String content) {

        static Scanned read(Path path) {
            try {
                String content = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                return new Scanned(path, content.toLowerCase(Locale.ROOT));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + path, e);
            }
        }
    }
}
