package com.seamware.consentmanager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Keeps sources free of ticket references, which rot once the ticket closes. */
@DisplayName("Source hygiene")
class SourceHygieneTest {

    /** Root of every scanned source; tests run from the project directory. */
    private static final Path SOURCES = Path.of("src");

    /** A file the scan must reach, so an empty or moved source tree cannot pass. */
    private static final Path APPLICATION_SOURCE =
            Path.of("src/main/java/com/seamware/consentmanager/Application.java");

    /** Configuration files the scan must reach, for the same reason. */
    private static final List<Path> APPLICATION_CONFIGS =
            List.of(
                    Path.of("src/main/resources/application.yml"),
                    Path.of("src/test/resources/application-test.yml"));

    /** Java sources plus Micronaut's {@code application*.yml} environments. */
    private static final Pattern SCANNED_FILE =
            Pattern.compile("(.*\\.java|application[^/]*\\.yml)$");

    /** A tracker id, a hash-numbered ticket or a plan step of this ticket, in any case. */
    private static final Pattern TICKET_REFERENCE =
            Pattern.compile(
                    "TICKET-\\d+|ticket #\\d+|step \\d+ of this ticket", Pattern.CASE_INSENSITIVE);

    /** Every scanned file, sorted so the report order is stable. */
    static List<Path> scannedFiles() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> SCANNED_FILE.matcher(file.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("the scan reaches the application source and its configuration")
    void scanReachesTheSources() throws IOException {
        assertThat(scannedFiles()).contains(APPLICATION_SOURCE).containsAll(APPLICATION_CONFIGS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scannedFiles")
    @DisplayName("no source references a ticket")
    void noTicketReference(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        assertThat(
                        IntStream.range(0, lines.size())
                                .filter(index -> TICKET_REFERENCE.matcher(lines.get(index)).find())
                                .mapToObj(index -> (index + 1) + ": " + lines.get(index).strip())
                                .toList())
                .as("ticket references in %s", file)
                .isEmpty();
    }
}
