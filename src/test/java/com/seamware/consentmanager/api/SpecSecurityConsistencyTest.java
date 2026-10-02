package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.seamware.consentmanager.security.Role;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Head;
import io.micronaut.http.annotation.Options;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Trace;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts that every operation in the OpenAPI specification is served by a route whose
 * {@code @Secured} rule says the same thing the specification's {@code security} requirement says.
 *
 * <p>Cases are generated from the specification, so a later endpoint is covered the moment it is
 * specified; an operation with no implementing controller method fails rather than being skipped.
 * The specification is read from the classpath copy under {@code static/} - the same bytes Swagger
 * UI is served.
 */
@DisplayName("Specification security requirements versus @Secured")
class SpecSecurityConsistencyTest {

    /** The specification as the application serves it, relative to the classpath root. */
    private static final String SPEC_RESOURCE = "static/openapi.yaml";

    /** Package holding every hand-written concrete controller, and their generated supertypes. */
    private static final String CONTROLLER_PACKAGE = "com.seamware.consentmanager.api";

    /** Routing annotation per specification HTTP method, in the spelling the specification uses. */
    private static final Map<String, Class<? extends Annotation>> ROUTING_ANNOTATIONS =
            Map.of(
                    "get", Get.class,
                    "put", Put.class,
                    "post", Post.class,
                    "delete", Delete.class,
                    "patch", Patch.class,
                    "head", Head.class,
                    "options", Options.class,
                    "trace", Trace.class);

    /** Keys of a path item that are operations rather than metadata such as {@code parameters}. */
    private static final Set<String> OPERATION_KEYS = ROUTING_ANNOTATIONS.keySet();

    /** The URI an unparameterised Micronaut routing annotation carries. */
    private static final String DEFAULT_URI = "/";

    /** Annotation members that may carry a route URI, most specific first. */
    private static final List<String> URI_MEMBERS = List.of("uri", "value");

    /** Separator between path segments. */
    private static final String PATH_SEPARATOR = "/";

    /** Suffix of a compiled class file. */
    private static final String CLASS_SUFFIX = ".class";

    /** Marker distinguishing a nested or synthetic class from a top-level one. */
    private static final String NESTED_CLASS_MARKER = "$";

    /** The only authentication scheme this service implements. */
    private static final String EXPECTED_SCHEME_TYPE = "http";

    /** The only authentication scheme name this service implements. */
    private static final String EXPECTED_SCHEME_NAME = "bearer";

    /** One operation of the specification together with the access it declares. */
    record SpecOperation(
            String operationId,
            String httpMethod,
            String path,
            boolean anonymous,
            List<String> schemes) {

        @Override
        public String toString() {
            return httpMethod.toUpperCase(Locale.ROOT) + " " + path + " (" + operationId + ")";
        }
    }

    /** Every operation in the specification, with its effective security requirement resolved. */
    static Stream<SpecOperation> specOperations() {
        Map<String, Object> spec = loadYaml(SPEC_RESOURCE);
        List<?> globalSecurity = asList(spec.get("security"));
        Map<String, Object> paths = asMap(spec.get("paths"));
        List<SpecOperation> operations = new ArrayList<>();
        paths.forEach(
                (path, item) ->
                        asMap(item)
                                .forEach(
                                        (key, value) -> {
                                            if (OPERATION_KEYS.contains(key)) {
                                                operations.add(
                                                        operationOf(
                                                                path,
                                                                key,
                                                                asMap(value),
                                                                globalSecurity));
                                            }
                                        }));
        assertThat(operations).as("the specification declares at least one operation").isNotEmpty();
        return operations.stream();
    }

    /**
     * Asserts that the route implementing an operation enforces what the specification promises.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("specOperations")
    @DisplayName("every specified operation is implemented with a matching @Secured rule")
    void securedRuleMatchesSpecification(SpecOperation operation) {
        Method routed = routedMethodFor(operation);
        Secured secured = routed.getAnnotation(Secured.class);
        assertThat(secured)
                .as("%s is routed by %s, which carries no @Secured", operation, routed)
                .isNotNull();

        List<String> rules = List.of(secured.value());
        if (operation.anonymous()) {
            assertThat(rules)
                    .as("%s declares `security: []`, so it must be anonymous", operation)
                    .containsExactly(SecurityRule.IS_ANONYMOUS);
            return;
        }
        assertThat(rules)
                .as("%s requires %s, so it must not be anonymous", operation, operation.schemes())
                .isNotEmpty()
                .doesNotContain(SecurityRule.IS_ANONYMOUS);
        assertThat(rules)
                .as("%s must be restricted to authentication or to named roles", operation)
                .allSatisfy(
                        rule ->
                                assertThat(
                                                SecurityRule.IS_AUTHENTICATED.equals(rule)
                                                        || Role.fromConfiguredName(rule)
                                                                .isPresent())
                                        .as(
                                                "`%s` is neither isAuthenticated() nor a known role",
                                                rule)
                                        .isTrue());
    }

    /**
     * Asserts that every scheme the specification can require resolves - across {@code $ref}ed
     * component files - to the bearer-token scheme this service actually implements.
     *
     * <p>The document-level requirement counts even while no operation inherits it: it is what a
     * newly specified operation gets by default, so a scheme that does not resolve has to fail now
     * rather than on the day the first secured endpoint is added.
     */
    @Test
    @DisplayName("every required security scheme resolves to a bearer token scheme")
    void requiredSchemesAreBearerTokenSchemes() {
        Map<String, Object> spec = loadYaml(SPEC_RESOURCE);
        Map<String, Object> declared = asMap(asMap(spec.get("components")).get("securitySchemes"));
        Stream<String> global =
                asList(spec.get("security")).stream()
                        .flatMap(entry -> asMap(entry).keySet().stream());
        List<String> required =
                Stream.concat(
                                global,
                                specOperations().flatMap(operation -> operation.schemes().stream()))
                        .distinct()
                        .toList();
        assertThat(required).as("the specification requires authentication somewhere").isNotEmpty();

        for (String scheme : required) {
            Map<String, Object> resolved = resolve(declared.get(scheme), SPEC_RESOURCE);
            assertThat(resolved).as("security scheme `%s` is declared", scheme).isNotNull();
            assertThat(resolved.get("type"))
                    .as("`%s` type", scheme)
                    .isEqualTo(EXPECTED_SCHEME_TYPE);
            assertThat(resolved.get("scheme"))
                    .as("`%s` scheme", scheme)
                    .isEqualTo(EXPECTED_SCHEME_NAME);
        }
    }

    /** Builds the operation record for one path item entry. */
    private static SpecOperation operationOf(
            String path, String httpMethod, Map<String, Object> operation, List<?> globalSecurity) {
        Object operationId = operation.get("operationId");
        assertThat(operationId)
                .as(
                        "%s %s declares an operationId, without which it cannot be matched",
                        httpMethod, path)
                .isInstanceOf(String.class);
        List<?> effective =
                operation.containsKey("security")
                        ? asList(operation.get("security"))
                        : globalSecurity;
        List<String> schemes = new ArrayList<>();
        for (Object requirement : effective) {
            schemes.addAll(asMap(requirement).keySet());
        }
        return new SpecOperation(
                (String) operationId, httpMethod, path, effective.isEmpty(), List.copyOf(schemes));
    }

    /** Finds the routed method implementing an operation, failing when nothing implements it. */
    private static Method routedMethodFor(SpecOperation operation) {
        for (Class<?> controller : controllerClasses()) {
            for (Method method : controller.getMethods()) {
                Operation annotation = method.getAnnotation(Operation.class);
                if (annotation == null
                        || !operation.operationId().equals(annotation.operationId())) {
                    continue;
                }
                assertThat(routeOf(controller, method))
                        .as(
                                "the route implementing `%s` is mapped where the specification says",
                                operation.operationId())
                        .isEqualTo(operation.httpMethod() + " " + operation.path());
                return method;
            }
        }
        return fail(
                "%s has no implementing controller method. Every specified operation must be"
                        + " implemented by a @Controller in %s whose routed method carries"
                        + " @Operation(operationId = \"%s\").",
                operation, CONTROLLER_PACKAGE, operation.operationId());
    }

    /** Renders the route a method is actually mapped to, as {@code "<method> <path>"}. */
    private static String routeOf(Class<?> controller, Method method) {
        for (Map.Entry<String, Class<? extends Annotation>> entry :
                ROUTING_ANNOTATIONS.entrySet()) {
            Annotation annotation = method.getAnnotation(entry.getValue());
            if (annotation != null) {
                return entry.getKey() + " " + join(prefixOf(controller), uriOf(annotation));
            }
        }
        return "no routing annotation on " + method;
    }

    /** Reads the path prefix a controller declares. */
    private static String prefixOf(Class<?> controller) {
        Controller annotation = controller.getAnnotation(Controller.class);
        return annotation == null ? DEFAULT_URI : annotation.value();
    }

    /** Reads the URI out of a routing annotation, whichever member carries it. */
    private static String uriOf(Annotation annotation) {
        for (String member : URI_MEMBERS) {
            try {
                String value =
                        (String) annotation.annotationType().getMethod(member).invoke(annotation);
                if (value != null && !DEFAULT_URI.equals(value) && !value.isBlank()) {
                    return value;
                }
            } catch (ReflectiveOperationException ignored) {
                // The member is simply not part of this annotation; try the next one.
            }
        }
        return DEFAULT_URI;
    }

    /** Joins a controller prefix and a method URI into one path. */
    private static String join(String prefix, String uri) {
        String joined =
                (DEFAULT_URI.equals(prefix) ? "" : prefix) + (DEFAULT_URI.equals(uri) ? "" : uri);
        return joined.isEmpty()
                ? DEFAULT_URI
                : joined.replace(PATH_SEPARATOR + PATH_SEPARATOR, PATH_SEPARATOR);
    }

    /**
     * Loads every top-level class in the controller package from the compiled output.
     *
     * <p>Scanning the classpath rather than starting an application context keeps this a fast unit
     * test, and keeps it honest: it sees the annotations as they were compiled.
     */
    private static List<Class<?>> controllerClasses() {
        List<Class<?>> controllers = new ArrayList<>();
        for (Path root : packageRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(file -> file.getFileName().toString().endsWith(CLASS_SUFFIX))
                        .map(file -> className(root, file))
                        .filter(name -> !name.contains(NESTED_CLASS_MARKER))
                        .map(SpecSecurityConsistencyTest::load)
                        .filter(type -> type.isAnnotationPresent(Controller.class))
                        .forEach(controllers::add);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to scan " + root, e);
            }
        }
        assertThat(controllers)
                .as("%s contains at least one @Controller", CONTROLLER_PACKAGE)
                .isNotEmpty();
        return controllers;
    }

    /** Resolves the compiled-output directories holding the controller package. */
    private static List<Path> packageRoots() {
        String resource = CONTROLLER_PACKAGE.replace('.', '/');
        List<Path> roots = new ArrayList<>();
        try {
            for (URL url : Collections.list(classLoader().getResources(resource))) {
                roots.add(Path.of(url.toURI()));
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Unable to locate " + CONTROLLER_PACKAGE, e);
        }
        assertThat(roots).as("%s is on the classpath", CONTROLLER_PACKAGE).isNotEmpty();
        return roots;
    }

    /** Derives a binary class name from a compiled class file. */
    private static String className(Path root, Path file) {
        String relative = root.relativize(file).toString();
        String withoutSuffix = relative.substring(0, relative.length() - CLASS_SUFFIX.length());
        return CONTROLLER_PACKAGE + "." + withoutSuffix.replace(java.io.File.separatorChar, '.');
    }

    /** Loads a class by name. */
    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, classLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unable to load " + name, e);
        }
    }

    /** Resolves a possibly {@code $ref}ed node against the file that referenced it. */
    private static Map<String, Object> resolve(Object node, String fromResource) {
        Map<String, Object> mapping = asMap(node);
        Object reference = mapping.get("$ref");
        if (!(reference instanceof String ref)) {
            return mapping.isEmpty() ? null : mapping;
        }
        String[] parts = ref.split("#", 2);
        String target =
                parts[0].isEmpty()
                        ? fromResource
                        : Path.of(fromResource).resolveSibling(parts[0]).normalize().toString();
        Object current = loadYaml(target);
        if (parts.length > 1) {
            for (String segment : parts[1].split(PATH_SEPARATOR)) {
                if (!segment.isEmpty()) {
                    current = asMap(current).get(segment);
                }
            }
        }
        return resolve(current, target);
    }

    /** Parses a YAML document from the classpath. */
    private static Map<String, Object> loadYaml(String resource) {
        try (InputStream stream = classLoader().getResourceAsStream(resource)) {
            assertThat(stream).as("`%s` is on the classpath", resource).isNotNull();
            return new Yaml().load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + resource, e);
        }
    }

    /** The class loader holding both the compiled application and its resources. */
    private static ClassLoader classLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    /** Narrows an untyped YAML node to a mapping. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : new LinkedHashMap<>();
    }

    /** Narrows an untyped YAML node to a sequence. */
    private static List<?> asList(Object node) {
        return node instanceof List<?> list ? list : List.of();
    }
}
