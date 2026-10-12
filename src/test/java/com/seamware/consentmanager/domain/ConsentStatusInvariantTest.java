package com.seamware.consentmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps a consent's status changeable only through the one guarded path, over the sources.
 *
 * <p>Two bans, because two invariants would otherwise be prose: the setters stay unused outside the
 * entity, so {@code consented} cannot be stored out of step with {@code status}; and {@code
 * withStatus}/{@code updateStatus} stay unused outside {@code ConsentFactory}, so no transition
 * reaches the entity without {@code ConsentStateMachine} having admitted it first. Neither
 * violation would fail a runtime assertion, which is why the scan exists.
 */
@DisplayName("Consent status/consented invariant")
class ConsentStatusInvariantTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    private static final String JAVA_SUFFIX = ".java";

    private static final String PACKAGE_ROOT = "com/seamware/consentmanager/";

    /** The entity itself, which is where the two setters are declared and kept in sync. */
    private static final Path ENTITY = SOURCE_ROOT.resolve(PACKAGE_ROOT + "domain/Consent.java");

    /** The single guarded path: the only source allowed to move a consent between statuses. */
    private static final Path FACTORY =
            SOURCE_ROOT.resolve(PACKAGE_ROOT + "service/ConsentFactory.java");

    /**
     * An invocation of either setter on a receiver.
     *
     * <p>The receiver is what keeps javadoc out: prose names a setter bare, a javadoc link reaches
     * it through a {@code #}, and only a call carries the dot.
     */
    private static final Pattern SETTER_CALL =
            Pattern.compile("\\.\\s*set(Status|Consented)\\s*\\(");

    /** An invocation of either status-moving method, matched the same way. */
    private static final Pattern TRANSITION_CALL =
            Pattern.compile("\\.\\s*(withStatus|updateStatus)\\s*\\(");

    @ParameterizedTest(name = "{0} derives consented from status")
    @MethodSource("sourcesOutsideTheEntity")
    @DisplayName("no source outside the entity sets status or consented directly")
    void noSourceOutsideTheEntitySetsStatusOrConsentedDirectly(Path source) {
        assertThat(SETTER_CALL.matcher(read(source)).find())
                .as("%s sets status or consented directly; use the ConsentFactory", source)
                .isFalse();
    }

    @ParameterizedTest(name = "{0} leaves transitions to the factory")
    @MethodSource("sourcesOutsideTheFactory")
    @DisplayName("no source outside the factory moves a consent between statuses")
    void noSourceOutsideTheFactoryMovesAConsentBetweenStatuses(Path source) {
        assertThat(TRANSITION_CALL.matcher(read(source)).find())
                .as(
                        "%s calls withStatus or updateStatus; route it through ConsentFactory so"
                                + " ConsentStateMachine sees the move",
                        source)
                .isFalse();
    }

    @Test
    @DisplayName("the scan still excludes files that exist")
    void theScanStillExcludesFilesThatExist() {
        assertThat(ENTITY).exists();
        assertThat(FACTORY).exists();
    }

    static Stream<Path> sourcesOutsideTheEntity() {
        return sourcesExcept(Set.of(ENTITY));
    }

    static Stream<Path> sourcesOutsideTheFactory() {
        return sourcesExcept(Set.of(ENTITY, FACTORY));
    }

    private static Stream<Path> sourcesExcept(Set<Path> exempt) {
        assertThat(SOURCE_ROOT).as("the scan root exists").exists();
        try (Stream<Path> tree = Files.walk(SOURCE_ROOT)) {
            return tree
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(JAVA_SUFFIX))
                    .filter(path -> !exempt.contains(path))
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
