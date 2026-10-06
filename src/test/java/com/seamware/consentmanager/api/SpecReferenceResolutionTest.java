package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts the specification's {@code $ref} tree resolves in both layouts it is read in: the
 * hand-authored {@code api/} tree and the copy under {@code target/classes/static/} that Swagger UI
 * loads.
 *
 * <p>Every reference here is relative, so the tree is only as sound as the directory shape around
 * it. The generator reads the authored layout and the browser reads the copy, which means a
 * reference can resolve for the build and still leave Swagger UI with an unrenderable document -
 * the failure this suite exists to catch, since no other test loads the specification the way a
 * browser does.
 */
@DisplayName("Specification reference resolution")
class SpecReferenceResolutionTest {

    /** Root of the copy on the classpath, which is what Swagger UI is served. */
    private static final String BUNDLE_ROOT = "static";

    /** Root of the hand-authored tree, relative to the module directory. */
    private static final Path SOURCE_ROOT = Path.of("api");

    /** The entry document of either layout. */
    private static final String ROOT_DOCUMENT = "openapi.yaml";

    /** Directory a path item must never be externalised into; see {@code SpecSecurityConsistencyTest}. */
    private static final String FORBIDDEN_PATHS_DIRECTORY = "paths";

    private static final String REF_KEY = "$ref";

    /** Separates a reference's file part from its JSON pointer. */
    private static final char FRAGMENT_SEPARATOR = '#';

    private static final String POINTER_SEPARATOR = "/";

    /** JSON pointer escapes, longest first so {@code ~1} is not read as an escaped {@code ~}. */
    private static final Map<String, String> POINTER_ESCAPES = Map.of("~1", "/", "~0", "~");

    private static final String YAML_SUFFIX = ".yaml";

    /** Reads one document of a layout, addressed by its path relative to that layout's root. */
    @FunctionalInterface
    interface Layout {
        InputStream open(String relativePath) throws IOException;
    }

    /** The classpath copy, read exactly as the static resource handler serves it. */
    private static Layout bundle() {
        return path -> {
            String resource = BUNDLE_ROOT + POINTER_SEPARATOR + path;
            InputStream stream =
                    Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
            assertThat(stream).as("`%s` is on the classpath", resource).isNotNull();
            return stream;
        };
    }

    /** The authored tree on disk, which the generator and a human editor both read. */
    private static Layout source() {
        assertThat(SOURCE_ROOT.resolve(ROOT_DOCUMENT))
                .as("tests run from the module directory, so the authored specification is visible")
                .exists();
        return path -> Files.newInputStream(SOURCE_ROOT.resolve(path));
    }

    @Test
    @DisplayName("every reference in the copy Swagger UI loads resolves to a node that is there")
    void theBundledSpecificationResolvesEveryReference() {
        assertThat(resolvedReferences(bundle()))
                .as("the specification is a tree of relative references, not a flat document")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the authored tree and the copy resolve to the same set of references")
    void bothLayoutsResolveTheSameReferences() {
        assertThat(resolvedReferences(source()))
                .as(
                        "the generator reads `api/` and the browser reads the copy; a reference that"
                                + " resolves in one layout and not in the other is a broken"
                                + " specification for whichever reads it second")
                .isEqualTo(resolvedReferences(bundle()));
    }

    @Test
    @DisplayName("every authored component file is reachable from the root document")
    void everyComponentFileIsReachable() {
        Set<String> reachable =
                resolvedReferences(source()).stream()
                        .map(SpecReferenceResolutionTest::fileOf)
                        .collect(TreeSet::new, Set::add, Set::addAll);

        assertThat(authoredDocuments())
                .as(
                        "an unreferenced file is still copied to `static/` and still served, so it"
                                + " reads as part of the published contract while being part of"
                                + " nothing")
                .allSatisfy(document -> assertThat(reachable).contains(document));
    }

    @Test
    @DisplayName("no path item is externalised, in the authored tree either")
    void noPathItemIsExternalised() {
        assertThat(SOURCE_ROOT.resolve(FORBIDDEN_PATHS_DIRECTORY))
                .as(
                        "operations stay inline in `%s`: an externalised path item contributes no"
                                + " case to SpecSecurityConsistencyTest, which would leave its"
                                + " @Secured rule unchecked while the suite stayed green",
                        ROOT_DOCUMENT)
                .doesNotExist();

        SpecSecurityConsistencyTest.assertPathItemsDeclareVisibleOperations(
                asMap(load(source(), new LinkedHashMap<>(), ROOT_DOCUMENT).get("paths")));
    }

    /** Every reference reachable from the root document, each one resolved, as {@code file#pointer}. */
    private static Set<String> resolvedReferences(Layout layout) {
        Map<String, Object> documents = new LinkedHashMap<>();
        Set<String> resolved = new TreeSet<>();
        walk(layout, ROOT_DOCUMENT, load(layout, documents, ROOT_DOCUMENT), documents, resolved);
        return resolved;
    }

    /** Descends through a node, resolving every reference beneath it. */
    private static void walk(
            Layout layout,
            String document,
            Object node,
            Map<String, Object> documents,
            Set<String> resolved) {
        if (node instanceof Map<?, ?> map) {
            if (map.get(REF_KEY) instanceof String reference) {
                resolve(layout, document, reference, documents, resolved);
            }
            map.values().forEach(value -> walk(layout, document, value, documents, resolved));
        } else if (node instanceof List<?> list) {
            list.forEach(value -> walk(layout, document, value, documents, resolved));
        }
    }

    /**
     * Resolves one reference against the document holding it and descends into what it points at.
     *
     * <p>A reference already resolved is not followed twice, which also ends a cycle between two
     * schemas that name each other.
     */
    private static void resolve(
            Layout layout,
            String document,
            String reference,
            Map<String, Object> documents,
            Set<String> resolved) {
        int fragment = reference.indexOf(FRAGMENT_SEPARATOR);
        String file = fragment < 0 ? reference : reference.substring(0, fragment);
        String pointer = fragment < 0 ? "" : reference.substring(fragment + 1);
        String target =
                file.isEmpty()
                        ? document
                        : Path.of(document).resolveSibling(file).normalize().toString();
        if (!resolved.add(target + FRAGMENT_SEPARATOR + pointer)) {
            return;
        }
        Object node = dereference(load(layout, documents, target), pointer, reference, document);
        walk(layout, target, node, documents, resolved);
    }

    /** Follows a JSON pointer into a document, failing when a segment names nothing. */
    private static Object dereference(
            Map<String, Object> document, String pointer, String reference, String from) {
        Object node = document;
        for (String segment : pointer.split(POINTER_SEPARATOR)) {
            if (segment.isEmpty()) {
                continue;
            }
            String key = unescape(segment);
            assertThat(node)
                    .as("`%s`, referenced from `%s`, points into a mapping", reference, from)
                    .isInstanceOf(Map.class);
            node = asMap(node).get(key);
            assertThat(node)
                    .as("`%s`, referenced from `%s`, resolves to a node", reference, from)
                    .isNotNull();
        }
        return node;
    }

    /** Undoes the JSON pointer escapes, so a segment naming a path keeps its slashes. */
    private static String unescape(String segment) {
        String unescaped = segment;
        for (Map.Entry<String, String> escape : POINTER_ESCAPES.entrySet()) {
            unescaped = unescaped.replace(escape.getKey(), escape.getValue());
        }
        return unescaped;
    }

    /** Parses a document of the layout, once per path. */
    private static Map<String, Object> load(
            Layout layout, Map<String, Object> documents, String path) {
        return asMap(
                documents.computeIfAbsent(
                        path,
                        location -> {
                            try (InputStream stream = layout.open(location)) {
                                return new Yaml().load(stream);
                            } catch (IOException e) {
                                throw new UncheckedIOException("Unable to read " + location, e);
                            }
                        }));
    }

    /** Every authored document except the root, as paths relative to the authored root. */
    private static List<String> authoredDocuments() {
        try (Stream<Path> tree = Files.walk(SOURCE_ROOT)) {
            List<String> documents =
                    tree.filter(Files::isRegularFile)
                            .filter(file -> file.getFileName().toString().endsWith(YAML_SUFFIX))
                            .map(file -> SOURCE_ROOT.relativize(file).toString())
                            .filter(path -> !ROOT_DOCUMENT.equals(path))
                            .collect(ArrayList::new, List::add, List::addAll);
            assertThat(documents).as("the specification is split into component files").isNotEmpty();
            return documents;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to walk " + SOURCE_ROOT, e);
        }
    }

    /** The file part of a resolved {@code file#pointer} entry. */
    private static String fileOf(String resolved) {
        return resolved.substring(0, resolved.indexOf(FRAGMENT_SEPARATOR));
    }

    /** Narrows an untyped YAML node to a mapping. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : new LinkedHashMap<>();
    }
}
