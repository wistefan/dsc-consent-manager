import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Bundles the authored specification into one document without external {@code $ref}. Every file
 * the root's {@code components} section points at is inlined there, and every other reference to
 * it becomes an internal pointer, so key order and component names stay as authored. Fails on a
 * reference to a file no component names. Run by the build in single-file source mode.
 */
public final class OpenApiBundler {

    private static final String REF = "$ref";
    private static final String COMPONENTS = "components";
    private static final String INTERNAL_PREFIX = "#";
    private static final String POINTER_SEPARATOR = "/";
    private static final String NEWLINE = "\n";
    private static final int INDENT = 2;

    private static final Pattern CORE_BOOLEAN = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

    private final Path rootDirectory;
    private final Map<String, String> internalPointers = new HashMap<>();
    private final Yaml yamlReader = newReader();

    private OpenApiBundler(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    /** Expects the authored root document and the bundle's output path. */
    public static void main(String[] args) throws IOException {
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path target = Path.of(args[1]);
        Map<String, Object> bundle = new OpenApiBundler(source.getParent()).bundle(source);
        Files.createDirectories(target.toAbsolutePath().getParent());
        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            newWriter().dump(bundle, writer);
        }
    }

    private Map<String, Object> bundle(Path source) throws IOException {
        Map<String, Object> root = asMap(load(source), source.toString());
        Map<String, Object> components = asMap(root.get(COMPONENTS), COMPONENTS);
        Map<String, String> externalTargets = new LinkedHashMap<>();
        for (Map.Entry<String, Object> section : components.entrySet()) {
            for (Map.Entry<String, Object> entry : asMap(section.getValue(), section.getKey()).entrySet()) {
                String ref = externalRef(entry.getValue());
                if (ref != null) {
                    String pointer = String.join(
                            POINTER_SEPARATOR, INTERNAL_PREFIX, COMPONENTS, section.getKey(), entry.getKey());
                    String key = canonical(rootDirectory, ref);
                    internalPointers.put(key, pointer);
                    externalTargets.put(pointer, key);
                }
            }
        }
        for (Map.Entry<String, Object> section : components.entrySet()) {
            for (Map.Entry<String, Object> entry : asMap(section.getValue(), section.getKey()).entrySet()) {
                String key = externalTargets.get(String.join(
                        POINTER_SEPARATOR, INTERNAL_PREFIX, COMPONENTS, section.getKey(), entry.getKey()));
                if (key != null) {
                    Path file = Path.of(fileOf(key));
                    entry.setValue(rewrite(resolvePointer(load(file), key), file.getParent(), false));
                } else {
                    entry.setValue(rewrite(entry.getValue(), rootDirectory, true));
                }
            }
        }
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (!COMPONENTS.equals(entry.getKey())) {
                entry.setValue(rewrite(entry.getValue(), rootDirectory, true));
            }
        }
        return root;
    }

    /**
     * Replaces every external reference under {@code node} with the component that inlines it.
     * {@code inRoot} says the node was read from the root document, the only place an internal ref
     * keeps its meaning; {@code directory} resolves its relative refs.
     */
    @SuppressWarnings("unchecked")
    private Object rewrite(Object node, Path directory, boolean inRoot) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> object = (Map<String, Object>) map;
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                if (REF.equals(entry.getKey()) && entry.getValue() instanceof String ref) {
                    entry.setValue(internalPointer(ref, directory, inRoot));
                } else {
                    entry.setValue(rewrite(entry.getValue(), directory, inRoot));
                }
            }
        } else if (node instanceof List<?> list) {
            ((List<Object>) list).replaceAll(item -> rewrite(item, directory, inRoot));
        }
        return node;
    }

    private String internalPointer(String ref, Path directory, boolean inRoot) {
        if (ref.startsWith(INTERNAL_PREFIX)) {
            if (!inRoot) {
                throw new IllegalStateException(
                        "Internal $ref " + ref + " in " + directory + " cannot survive bundling");
            }
            return ref;
        }
        String pointer = internalPointers.get(canonical(directory, ref));
        if (pointer == null) {
            throw new IllegalStateException(
                    "$ref " + ref + " from " + directory + " names a file no root component declares");
        }
        return pointer;
    }

    private static String externalRef(Object node) {
        if (node instanceof Map<?, ?> map
                && map.size() == 1
                && map.get(REF) instanceof String ref
                && !ref.startsWith(INTERNAL_PREFIX)) {
            return ref;
        }
        return null;
    }

    /** The absolute file path plus any fragment, so two spellings of one target compare equal. */
    private static String canonical(Path directory, String ref) {
        int hash = ref.indexOf(INTERNAL_PREFIX);
        String file = hash < 0 ? ref : ref.substring(0, hash);
        String fragment = hash < 0 ? "" : ref.substring(hash);
        return directory.resolve(file).normalize() + fragment;
    }

    private static String fileOf(String key) {
        int hash = key.indexOf(INTERNAL_PREFIX);
        return hash < 0 ? key : key.substring(0, hash);
    }

    private static Object resolvePointer(Object document, String key) {
        int hash = key.indexOf(INTERNAL_PREFIX);
        Object node = document;
        if (hash >= 0) {
            for (String token : key.substring(hash + 1).split(POINTER_SEPARATOR)) {
                if (!token.isEmpty()) {
                    node = asMap(node, key).get(token.replace("~1", "/").replace("~0", "~"));
                }
            }
        }
        if (node == null) {
            throw new IllegalStateException("$ref target " + key + " does not exist");
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node, String where) {
        if (node instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException(where + " is not a mapping");
    }

    private Object load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return yamlReader.load(reader);
        }
    }

    /**
     * Loads booleans by the YAML 1.2 core rule and resolves no timestamps, so an unquoted date or
     * {@code on} stays the string it was written as. Integers and floats keep SnakeYAML's YAML 1.1
     * patterns (sexagesimal, {@code 0b}, leading-zero octal); the authored spec writes none of them.
     */
    private static Yaml newReader() {
        Resolver resolver = new Resolver() {
            @Override
            protected void addImplicitResolvers() {
                addImplicitResolver(Tag.BOOL, CORE_BOOLEAN, "tTfF");
                addImplicitResolver(Tag.INT, INT, "-+0123456789");
                addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
                addImplicitResolver(Tag.NULL, NULL, "~nN\0");
                addImplicitResolver(Tag.NULL, EMPTY, null);
            }
        };
        LoaderOptions loader = new LoaderOptions();
        DumperOptions unused = new DumperOptions();
        return new Yaml(new SafeConstructor(loader), new Representer(unused), unused, loader, resolver);
    }

    /** Writes with the YAML 1.1 resolver, which quotes any string a 1.1 reader would retype. */
    private static Yaml newWriter() {
        DumperOptions dumper = new DumperOptions();
        dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumper.setIndent(INDENT);
        dumper.setIndicatorIndent(INDENT);
        dumper.setIndentWithIndicator(true);
        dumper.setSplitLines(false);
        dumper.setWidth(Integer.MAX_VALUE);
        Representer representer = new Representer(dumper) {
            @Override
            protected Node representScalar(Tag tag, String value, DumperOptions.ScalarStyle style) {
                return super.representScalar(
                        tag, value, value.contains(NEWLINE) ? DumperOptions.ScalarStyle.LITERAL : style);
            }
        };
        return new Yaml(representer, dumper);
    }
}
