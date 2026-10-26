package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts the authored {@code $ref} tree resolves, and the bundle served at {@code /openapi.yaml}
 * is self-contained and dereferences to the same content.
 *
 * <p>The authored tree is a web of relative references, which the generator reads. The browser
 * reads the build's bundle, so a reference the bundler left external would leave Swagger UI with an
 * unrenderable document.
 */
@DisplayName("Specification reference resolution")
class SpecReferenceResolutionTest {

    /** Directory of the classpath holding the bundle, which is what the service serves. */
    private static final String BUNDLE_ROOT = "static";

    /** Root of the hand-authored tree, relative to the module directory. */
    private static final Path SOURCE_ROOT = Path.of("api");

    /** The entry document of either layout. */
    private static final String ROOT_DOCUMENT = "openapi.yaml";

    /**
     * Directory a path item must never be externalised into; see {@code
     * SpecSecurityConsistencyTest}.
     */
    private static final String FORBIDDEN_PATHS_DIRECTORY = "paths";

    private static final String REF_KEY = "$ref";

    /**
     * Stands in for a reference back into its own ancestry. Carries no depth: the authored tree
     * reaches a component through one more {@code $ref} hop than the bundle does.
     */
    private static final Map<String, Object> CYCLE = Map.of("$cycle", true);

    /** Separates a reference's file part from its JSON pointer. */
    private static final char FRAGMENT_SEPARATOR = '#';

    private static final String POINTER_SEPARATOR = "/";

    /** JSON pointer escapes, longest first so {@code ~1} is not read as an escaped {@code ~}. */
    private static final Map<String, String> POINTER_ESCAPES = Map.of("~1", "/", "~0", "~");

    private static final String YAML_SUFFIX = ".yaml";

    /** Marks tool configuration such as the Spectral ruleset, which is not part of the spec. */
    private static final String TOOL_CONFIGURATION_PREFIX = ".";

    /** The frozen copy oasdiff compares against, a whole document rather than a component. */
    private static final Path BASELINE_DIRECTORY = SOURCE_ROOT.resolve("baseline");

    /** Reads one document of a layout, addressed by its path relative to that layout's root. */
    @FunctionalInterface
    interface Layout {
        InputStream open(String relativePath) throws IOException;
    }

    /** The classpath bundle, read exactly as the static resource handler serves it. */
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
    @DisplayName("every reference in the authored tree resolves to a node that is there")
    void theAuthoredSpecificationResolvesEveryReference() {
        assertThat(resolvedReferences(source()))
                .as("the authored specification is a tree of relative references")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the served bundle resolves every reference within itself")
    void theServedBundleContainsNoExternalReference() {
        assertThat(resolvedReferences(bundle()))
                .as(
                        "a reference into another file has nothing to resolve against once the"
                                + " bundle is served on its own")
                .isNotEmpty()
                .allSatisfy(reference -> assertThat(fileOf(reference)).isEqualTo(ROOT_DOCUMENT));
    }

    @Test
    @DisplayName("the served bundle dereferences to the authored specification, section by section")
    void theServedBundleIsTheAuthoredContract() {
        Map<String, Object> authored = asMap(dereferenced(source()));
        Map<String, Object> served = asMap(dereferenced(bundle()));

        assertThat(served.keySet()).containsExactlyElementsOf(authored.keySet());
        SoftAssertions.assertSoftly(
                softly ->
                        authored.forEach(
                                (section, content) ->
                                        softly.assertThat(served.get(section))
                                                .as(
                                                        "`%s` of the bundle, every reference"
                                                                + " followed, is what the"
                                                                + " generator compiles",
                                                        section)
                                                .isEqualTo(content)));
    }

    @Test
    @DisplayName("the bundle is the only YAML document the static mapping can serve")
    void theBundleIsTheOnlyServedDocument() {
        assertThat(servedDocuments())
                .as(
                        "the `/*.yaml` mapping serves every top-level YAML file of `%s/`, and the"
                                + " build refreshes it by overwriting, never by deleting",
                        BUNDLE_ROOT)
                .containsExactly(ROOT_DOCUMENT);
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
                        "the bundler inlines only what the root references, so an unreferenced file"
                                + " reads as part of the contract while being dropped from what is"
                                + " served")
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

    /** The root document with every reference followed, so layouts compare by content alone. */
    private static Object dereferenced(Layout layout) {
        Map<String, Object> documents = new LinkedHashMap<>();
        return inlined(
                layout,
                ROOT_DOCUMENT,
                load(layout, documents, ROOT_DOCUMENT),
                documents,
                new ArrayList<>());
    }

    /**
     * Every reference reachable from the root document, each one resolved, as {@code file#pointer}.
     */
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
        String target = targetOf(document, reference);
        if (!resolved.add(target)) {
            return;
        }
        walk(
                layout,
                fileOf(target),
                follow(layout, documents, target, reference, document),
                documents,
                resolved);
    }

    /**
     * A copy of {@code node} with every {@code $ref} mapping replaced by a copy of its target, or
     * by {@link #CYCLE} when the target is already in {@code stack}. A {@code $ref} with siblings
     * keeps them, its target copied under the {@code $ref} key.
     */
    private static Object inlined(
            Layout layout,
            String document,
            Object node,
            Map<String, Object> documents,
            List<String> stack) {
        if (node instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.get(REF_KEY) instanceof String reference) {
                return inlinedTarget(layout, document, reference, documents, stack);
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach(
                    (key, value) ->
                            copy.put(
                                    (String) key,
                                    REF_KEY.equals(key) && value instanceof String reference
                                            ? inlinedTarget(
                                                    layout, document, reference, documents, stack)
                                            : inlined(layout, document, value, documents, stack)));
            return copy;
        }
        if (node instanceof List<?> list) {
            return list.stream()
                    .map(value -> inlined(layout, document, value, documents, stack))
                    .toList();
        }
        return node;
    }

    private static Object inlinedTarget(
            Layout layout,
            String document,
            String reference,
            Map<String, Object> documents,
            List<String> stack) {
        String target = targetOf(document, reference);
        if (stack.contains(target)) {
            return CYCLE;
        }
        stack.add(target);
        Object copy =
                inlined(
                        layout,
                        fileOf(target),
                        follow(layout, documents, target, reference, document),
                        documents,
                        stack);
        stack.removeLast();
        return copy;
    }

    /** The node a resolved {@code file#pointer} names, failing when it names nothing. */
    private static Object follow(
            Layout layout,
            Map<String, Object> documents,
            String target,
            String reference,
            String from) {
        return dereference(
                load(layout, documents, fileOf(target)),
                target.substring(target.indexOf(FRAGMENT_SEPARATOR) + 1),
                reference,
                from);
    }

    /** Resolves a reference against the document holding it, as {@code file#pointer}. */
    private static String targetOf(String document, String reference) {
        int fragment = reference.indexOf(FRAGMENT_SEPARATOR);
        String file = fragment < 0 ? reference : reference.substring(0, fragment);
        String pointer = fragment < 0 ? "" : reference.substring(fragment + 1);
        String target =
                file.isEmpty()
                        ? document
                        : Path.of(document).resolveSibling(file).normalize().toString();
        return target + FRAGMENT_SEPARATOR + pointer;
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
                            .filter(file -> !file.startsWith(BASELINE_DIRECTORY))
                            .filter(file -> file.getFileName().toString().endsWith(YAML_SUFFIX))
                            .filter(
                                    file ->
                                            !file.getFileName()
                                                    .toString()
                                                    .startsWith(TOOL_CONFIGURATION_PREFIX))
                            .map(file -> SOURCE_ROOT.relativize(file).toString())
                            .filter(path -> !ROOT_DOCUMENT.equals(path))
                            .collect(ArrayList::new, List::add, List::addAll);
            assertThat(documents)
                    .as("the specification is split into component files")
                    .isNotEmpty();
            return documents;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to walk " + SOURCE_ROOT, e);
        }
    }

    /** Every top-level YAML document beside the bundle on the classpath. */
    private static List<String> servedDocuments() {
        Path root = bundleRoot();
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(YAML_SUFFIX))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to list " + root, e);
        }
    }

    /** The directory the bundle is served from, located through it on the classpath. */
    private static Path bundleRoot() {
        String resource = BUNDLE_ROOT + POINTER_SEPARATOR + ROOT_DOCUMENT;
        URL root = Thread.currentThread().getContextClassLoader().getResource(resource);
        assertThat(root).as("`%s` is on the classpath", resource).isNotNull();
        try {
            return Path.of(root.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unable to locate " + resource, e);
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
