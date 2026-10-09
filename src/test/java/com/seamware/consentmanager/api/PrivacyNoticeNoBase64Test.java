package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps base64-encoded identifiers out of the privacy-notice module, on this service's API and on
 * the calls it makes upstream.
 *
 * <p>The API this ticket replaces passed self-description URLs base64-encoded, under parameter
 * names like {@code SD64}. Nothing here encodes anything: a contract URI and a participant
 * identifier travel verbatim. That is a decision a later change can undo in one line and nobody
 * would notice until a stored {@code contract_uri} no longer matched the key its uniqueness is held
 * against, so it is pinned rather than documented.
 *
 * <p>The guard targets the mechanism, not the word: an encoded identifier shows up as a name
 * carrying {@code 64}, as OpenAPI's {@code byte} format, or as a use of {@link java.util.Base64}.
 * Prose saying that nothing is encoded is left alone, because forbidding the sentence would only
 * delete the explanation.
 */
@DisplayName("No base64-encoded identifiers in the privacy-notice module")
class PrivacyNoticeNoBase64Test {

    /** The authored specification, which is the contract this service publishes. */
    private static final Path SPEC_ROOT = Path.of("api");

    /** The main sources, from which the privacy-notice module is selected. */
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/seamware/consentmanager");

    private static final String YAML_SUFFIX = ".yaml";

    private static final String JAVA_SUFFIX = ".java";

    /** Everything the privacy-notice flows call upstream lives here. */
    private static final String CLIENT_PACKAGE = "client";

    /** Name prefix of every privacy-notice source outside {@link #CLIENT_PACKAGE}. */
    private static final String MODULE_PREFIX = "PrivacyNotice";

    /** Sources the module cannot lose without the scan below quietly covering nothing. */
    private static final Set<String> REQUIRED_SOURCES =
            Set.of(
                    "ContractServiceClient" + JAVA_SUFFIX,
                    "SelfDescriptionClient" + JAVA_SUFFIX,
                    MODULE_PREFIX + "Service" + JAVA_SUFFIX,
                    MODULE_PREFIX + "Controller" + JAVA_SUFFIX);

    /** The encoding every spelling of an encoded identifier carries somewhere in its name. */
    private static final Pattern ENCODED_NAME = Pattern.compile("(?i).*(base64|sd64|64).*");

    /** {@link java.util.Base64} used as a type, which prose about base64 does not match. */
    private static final Pattern ENCODER_USE = Pattern.compile("\\bBase64\\b|(?i)\\bsd64\\b");

    /** OpenAPI's formats for a base64 payload, whatever the property is called. */
    private static final Set<String> ENCODED_FORMATS = Set.of("byte", "base64");

    /** Keys whose value names something a caller sends, rather than describing it. */
    private static final Set<String> NAMING_KEYS = Set.of("name", "operationId");

    private static final String FORMAT_KEY = "format";

    @ParameterizedTest(name = "{0} names nothing base64-encoded")
    @MethodSource("specificationDocuments")
    @DisplayName("the authored specification declares no encoded identifier")
    void theAuthoredSpecificationDeclaresNoEncodedIdentifier(Path document) {
        assertThat(encodedDeclarations(parse(document), document.toString())).isEmpty();
    }

    @ParameterizedTest(name = "{0} encodes nothing")
    @MethodSource("moduleSources")
    @DisplayName("no privacy-notice source reaches for an encoder")
    void noPrivacyNoticeSourceReachesForAnEncoder(Path source) {
        assertThat(ENCODER_USE.matcher(read(source)).find())
                .as("%s uses an encoder; identifiers travel verbatim", source)
                .isFalse();
    }

    @Test
    @DisplayName("the scanned module still holds the sources the guard exists for")
    void theScannedModuleStillHoldsTheSourcesTheGuardExistsFor() {
        assertThat(moduleSources().map(source -> source.getFileName().toString()))
                .containsAll(REQUIRED_SOURCES);
    }

    static Stream<Path> specificationDocuments() {
        return filesUnder(SPEC_ROOT, YAML_SUFFIX);
    }

    static Stream<Path> moduleSources() {
        return filesUnder(SOURCE_ROOT, JAVA_SUFFIX)
                .filter(PrivacyNoticeNoBase64Test::belongsToTheModule);
    }

    private static boolean belongsToTheModule(Path source) {
        return source.getFileName().toString().startsWith(MODULE_PREFIX)
                || Stream.of(source.getParent())
                        .map(Path::toString)
                        .anyMatch(parent -> parent.contains(CLIENT_PACKAGE));
    }

    /** Every place {@code document} names something encoded, as {@code pointer} descriptions. */
    private static List<String> encodedDeclarations(Object node, String pointer) {
        List<String> found = new ArrayList<>();
        if (node instanceof Map<?, ?> mapping) {
            mapping.forEach(
                    (key, value) -> {
                        String nested = pointer + "/" + key;
                        if (ENCODED_NAME.matcher(String.valueOf(key)).matches()
                                || namesSomethingEncoded(String.valueOf(key), value)) {
                            found.add(nested);
                        }
                        found.addAll(encodedDeclarations(value, nested));
                    });
        } else if (node instanceof List<?> items) {
            for (int index = 0; index < items.size(); index++) {
                found.addAll(encodedDeclarations(items.get(index), pointer + "/" + index));
            }
        }
        return found;
    }

    /** Whether a key that names or types a value carries an encoding in that value. */
    private static boolean namesSomethingEncoded(String key, Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        return NAMING_KEYS.contains(key) && ENCODED_NAME.matcher(text).matches()
                || FORMAT_KEY.equals(key) && ENCODED_FORMATS.contains(text.toLowerCase(Locale.ROOT));
    }

    private static Stream<Path> filesUnder(Path root, String suffix) {
        assertThat(root).as("the scan root exists").exists();
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(suffix))
                    .sorted()
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object parse(Path document) {
        try (InputStream stream = Files.newInputStream(document)) {
            return new Yaml().load(stream);
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
