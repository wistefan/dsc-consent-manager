package com.seamware.consentmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps {@code consented} derivable from {@code status} by keeping the two setters unused.
 *
 * <p>{@link Consent#withStatus} and {@link Consent#updateStatus} set both fields together; the
 * setters exist for Micronaut Data hydration alone. A single call to either from application code
 * would let a refusal be stored as a grant, which no runtime assertion would catch, so the ban is
 * enforced over the sources rather than written down in a javadoc nobody re-reads.
 */
@DisplayName("Consent status/consented invariant")
class ConsentStatusInvariantTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    private static final String JAVA_SUFFIX = ".java";

    /** The entity itself, which is where the two setters are declared and kept in sync. */
    private static final Path ENTITY =
            SOURCE_ROOT.resolve("com/seamware/consentmanager/domain/Consent.java");

    /**
     * An invocation of either setter on a receiver.
     *
     * <p>The receiver is what keeps javadoc out: prose names a setter bare, a javadoc link reaches
     * it through a {@code #}, and only a call carries the dot.
     */
    private static final Pattern SETTER_CALL =
            Pattern.compile("\\.\\s*set(Status|Consented)\\s*\\(");

    @ParameterizedTest(name = "{0} derives consented from status")
    @MethodSource("sourcesOutsideTheEntity")
    @DisplayName("no source outside the entity sets status or consented directly")
    void noSourceOutsideTheEntitySetsStatusOrConsentedDirectly(Path source) {
        assertThat(SETTER_CALL.matcher(read(source)).find())
                .as("%s sets status or consented directly; use withStatus or updateStatus", source)
                .isFalse();
    }

    @Test
    @DisplayName("the scan still excludes an entity that exists")
    void theScanStillExcludesAnEntityThatExists() {
        assertThat(ENTITY).exists();
    }

    static Stream<Path> sourcesOutsideTheEntity() {
        assertThat(SOURCE_ROOT).as("the scan root exists").exists();
        try (Stream<Path> tree = Files.walk(SOURCE_ROOT)) {
            return tree
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(JAVA_SUFFIX))
                    .filter(path -> !path.equals(ENTITY))
                    .sorted()
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
