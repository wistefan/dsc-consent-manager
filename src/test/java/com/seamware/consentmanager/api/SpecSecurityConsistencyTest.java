package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

import com.seamware.consentmanager.security.CatalogPrincipal;
import com.seamware.consentmanager.security.ConsentManagerPrincipal;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.Role;
import com.seamware.consentmanager.security.UserPrincipal;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Spec {@code security} versus {@code @Secured}, principal type and 401/403 refs per operation;
 * cases come from the spec, so an unimplemented or drifted operation fails rather than skips.
 */
@DisplayName("Specification security requirements versus @Secured")
class SpecSecurityConsistencyTest {

    /** Root of the authored specification, relative to the module directory. */
    private static final Path SPEC_ROOT = Path.of("api");

    /** The root document, relative to {@link #SPEC_ROOT}. */
    private static final String SPEC_RESOURCE = "openapi.yaml";

    /** Package holding the concrete controllers and their generated supertypes. */
    private static final String CONTROLLER_PACKAGE = "com.seamware.consentmanager.api";

    /** Routing annotation per specification HTTP method. */
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

    /** Operations {@code api/openapi.yaml} declares; raise it with every added operation. */
    private static final int SPECIFIED_OPERATION_COUNT = 35;

    /** Path item key that would hide every operation beneath it from this test. */
    private static final String REF_KEY = "$ref";

    /** Key holding the parameter list, declarable on a path item as well as on an operation. */
    private static final String PARAMETERS_KEY = "parameters";

    /** The URI an unparameterised Micronaut routing annotation carries. */
    private static final String DEFAULT_URI = "/";

    /** Annotation members that may carry a route URI, most specific first. */
    private static final List<String> URI_MEMBERS = List.of("uri", "value");

    private static final String PATH_SEPARATOR = "/";

    private static final String CLASS_SUFFIX = ".class";

    /** Marker distinguishing a nested or synthetic class from a top-level one. */
    private static final String NESTED_CLASS_MARKER = "$";

    /** The only authentication scheme this service implements. */
    private static final String EXPECTED_SCHEME_TYPE = "http";

    /** The only authentication scheme name this service implements. */
    private static final String EXPECTED_SCHEME_NAME = "bearer";

    private static final String UNAUTHORIZED_STATUS = "401";

    private static final String FORBIDDEN_STATUS = "403";

    /** Shared response component a secured operation's {@code 401} must reference. */
    private static final String UNAUTHORIZED_REF = "#/components/responses/Unauthorized";

    /** Shared response component a secured operation's {@code 403} must reference. */
    private static final String FORBIDDEN_REF = "#/components/responses/Forbidden";

    /** Suffix of the routed wrapper's name; the delegate keeps the bare {@code operationId}. */
    private static final String ROUTED_METHOD_SUFFIX = "Api";

    /** Roles each principal type can hold; the binder refuses any other with {@code 403}. */
    private static final Map<Class<?>, Set<Role>> ROLES_PER_PRINCIPAL_TYPE =
            Map.of(
                    UserPrincipal.class, Set.of(Role.USER),
                    ParticipantPrincipal.class, Set.of(Role.PARTICIPANT),
                    CatalogPrincipal.class, Set.of(Role.CATALOG),
                    ConsentManagerPrincipal.class, EnumSet.allOf(Role.class));

    /**
     * One specified operation and its declared access. {@code parameterNames} merges path-item
     * parameters; {@code responseRefs} omits inline responses.
     */
    record SpecOperation(
            String operationId,
            String httpMethod,
            String path,
            boolean anonymous,
            List<String> schemes,
            List<String> roles,
            List<String> parameterNames,
            Map<String, String> responseRefs) {

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
                (path, item) -> {
                    Map<String, Object> pathItem = asMap(item);
                    pathItem.forEach(
                            (key, value) -> {
                                if (OPERATION_KEYS.contains(key)) {
                                    operations.add(
                                            operationOf(
                                                    path,
                                                    pathItem,
                                                    key,
                                                    asMap(value),
                                                    globalSecurity));
                                }
                            });
                });
        assertThat(operations)
                .as(
                        "every case below is generated from this list, so an operation missing from"
                                + " it is not a failure but a silence; the count is stated here so"
                                + " that a path item this parser cannot see costs a build. Raise it"
                                + " in the commit that adds the operation")
                .hasSize(SPECIFIED_OPERATION_COUNT);
        return operations.stream();
    }

    /** Nothing here resolves a {@code $ref}, so an externalised path item would go unchecked. */
    @Test
    @DisplayName("every path item declares its operations inline, where this test can see them")
    void everyPathItemDeclaresItsOperationsInline() {
        assertPathItemsDeclareVisibleOperations(asMap(loadYaml(SPEC_RESOURCE).get("paths")));
    }

    /** Fails when any path item declares no operation key this test recognises. */
    static void assertPathItemsDeclareVisibleOperations(Map<String, Object> paths) {
        paths.forEach(
                (path, item) ->
                        assertThat(asMap(item).keySet())
                                .as(
                                        "path item `%s` must spell its operations out inline;"
                                                + " this test cannot resolve an external $ref, so an"
                                                + " operation behind one is never checked against its"
                                                + " @Secured rule",
                                        path)
                                .doesNotContain(REF_KEY)
                                .containsAnyElementsOf(OPERATION_KEYS));
    }

    /** Proves the inline-operations guard rejects what it claims to. */
    @Nested
    @DisplayName("the inline-operations guard itself")
    class InlineOperationsGuard {

        static Stream<Arguments> pathItems() {
            return Stream.of(
                    Arguments.of("an inline operation", Map.of("get", Map.of()), true),
                    Arguments.of(
                            "an operation beside path-level metadata",
                            Map.of("parameters", List.of(), "post", Map.of()),
                            true),
                    Arguments.of(
                            "an externalised path item",
                            Map.of(REF_KEY, "./paths/users.yaml#/~1users"),
                            false),
                    Arguments.of(
                            "an externalised path item carrying metadata too",
                            Map.of(
                                    REF_KEY,
                                    "./paths/users.yaml#/~1users",
                                    PARAMETERS_KEY,
                                    List.of()),
                            false),
                    Arguments.of(
                            "path-level metadata only", Map.of("parameters", List.of()), false),
                    Arguments.of("an empty path item", Map.of(), false));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("pathItems")
        void judgesPathItem(String description, Map<String, Object> pathItem, boolean accepted) {
            Map<String, Object> paths = Map.of("/users", pathItem);
            if (accepted) {
                assertThatCode(() -> assertPathItemsDeclareVisibleOperations(paths))
                        .doesNotThrowAnyException();
            } else {
                assertThatCode(() -> assertPathItemsDeclareVisibleOperations(paths))
                        .isInstanceOf(AssertionError.class)
                        .hasMessageContaining("/users");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specOperations")
    @DisplayName("every specified operation is implemented with a matching @Secured rule")
    void securedRuleMatchesSpecification(SpecOperation operation) {
        Method routed = routedMethodFor(operation).method();
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
        if (operation.roles().isEmpty()) {
            assertThat(rules)
                    .as("%s names no scope, so it may only require authentication", operation)
                    .containsExactly(SecurityRule.IS_AUTHENTICATED);
            return;
        }
        assertThat(rules)
                .as(
                        "%s is restricted to %s, so its route must enforce exactly those roles."
                                + " The generator reads the operation's `x-roles` extension and not its"
                                + " `security` scopes, so a mismatch here means the two have drifted"
                                + " apart - most likely `x-roles` is missing, which silently yields"
                                + " isAuthenticated() and admits every authenticated caller.",
                        operation, operation.roles())
                .containsExactlyInAnyOrderElementsOf(operation.roles());
        assertThat(rules)
                .as("%s must be restricted to roles this service knows", operation)
                .allSatisfy(
                        rule ->
                                assertThat(Role.fromConfiguredName(rule))
                                        .as("`%s` is not a known role", rule)
                                        .isPresent());
    }

    /** Without a principal parameter the resolution filter's refusals are silently skipped. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("specOperations")
    @DisplayName("every secured operation takes its caller as a typed principal")
    void securedOperationBindsATypedPrincipal(SpecOperation operation) {
        if (operation.anonymous()) {
            return;
        }
        Method routed = routedMethodFor(operation).method();
        assertThat(routed.getParameterTypes())
                .as(
                        "%s is secured, so %s must declare a %s parameter - that is what makes the"
                                + " principal resolution filter run its refusals for this route",
                        operation, routed, ConsentManagerPrincipal.class.getSimpleName())
                .anyMatch(ConsentManagerPrincipal.class::isAssignableFrom);
    }

    /** The inherited generated delegate compiles, routes and answers {@code 501}. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("specOperations")
    @DisplayName("every specified operation is handled by its controller, not the generated stub")
    void concreteControllerDeclaresTheHandler(SpecOperation operation) {
        RoutedMethod routed = routedMethodFor(operation);
        assertThat(delegateOf(routed).getDeclaringClass())
                .as(
                        "%s is routed through %s, which only inherits the generated delegate -"
                                + " override it, because the inherited body answers 501",
                        operation, routed.controller().getName())
                .isEqualTo(routed.controller());
    }

    /** A mismatched {@code x-principal}/{@code x-roles} pair only shows as a runtime 403. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("specOperations")
    @DisplayName("every secured operation's principal type can hold the roles it admits")
    void principalTypeHoldsEveryAdmittedRole(SpecOperation operation) {
        if (operation.anonymous() || operation.roles().isEmpty()) {
            return;
        }
        Class<?> principalType = principalParameterOf(routedMethodFor(operation).method());
        Set<Role> holdable = ROLES_PER_PRINCIPAL_TYPE.get(principalType);
        assertThat(holdable)
                .as("`%s` is not a principal type this suite knows", principalType.getSimpleName())
                .isNotNull();
        assertThat(operation.roles())
                .as(
                        "%s admits %s, so its `x-principal: %s` must be able to hold each of them;"
                                + " a caller holding one it cannot hold is refused by the argument"
                                + " binder with a 403",
                        operation, operation.roles(), principalType.getSimpleName())
                .allSatisfy(
                        role ->
                                assertThat(Role.fromConfiguredName(role))
                                        .hasValueSatisfying(
                                                held -> assertThat(holdable).contains(held)));
    }

    /** A newly permitted principal type must not silently skip the check above. */
    @Test
    @DisplayName("every permitted principal type declares the roles it can hold")
    void principalTypeTableCoversTheSealedHierarchy() {
        assertThat(ROLES_PER_PRINCIPAL_TYPE)
                .as("one entry per permitted principal type, plus the sealed supertype itself")
                .containsKey(ConsentManagerPrincipal.class)
                .containsKeys(ConsentManagerPrincipal.class.getPermittedSubclasses());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specOperations")
    @DisplayName("every secured operation references the shared 401 and 403 responses")
    void securedOperationDocumentsTheSharedFailureResponses(SpecOperation operation) {
        Map<String, String> refs = operation.responseRefs();
        if (operation.anonymous()) {
            assertThat(refs)
                    .as(
                            "%s declares `security: []`, so it must advertise neither failure",
                            operation)
                    .doesNotContainKeys(UNAUTHORIZED_STATUS, FORBIDDEN_STATUS);
            return;
        }
        assertThat(refs)
                .as("%s is secured, so it must reference the shared failure responses", operation)
                .containsEntry(UNAUTHORIZED_STATUS, UNAUTHORIZED_REF)
                .containsEntry(FORBIDDEN_STATUS, FORBIDDEN_REF);
    }

    /** Includes the document-level requirement, which a new operation inherits by default. */
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
            String path,
            Map<String, Object> pathItem,
            String httpMethod,
            Map<String, Object> operation,
            List<?> globalSecurity) {
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
        List<String> roles = new ArrayList<>();
        for (Object requirement : effective) {
            asMap(requirement)
                    .forEach(
                            (scheme, scopes) -> {
                                schemes.add(scheme);
                                asList(scopes).forEach(scope -> roles.add(String.valueOf(scope)));
                            });
        }
        return new SpecOperation(
                (String) operationId,
                httpMethod,
                path,
                effective.isEmpty(),
                List.copyOf(schemes),
                List.copyOf(roles),
                parameterNamesOf(path, httpMethod, pathItem, operation),
                responseRefsOf(operation));
    }

    /** Parameter names an operation accepts, merged with its path item's; duplicates collapse. */
    private static List<String> parameterNamesOf(
            String path,
            String httpMethod,
            Map<String, Object> pathItem,
            Map<String, Object> operation) {
        Set<String> names = new LinkedHashSet<>();
        Stream.of(pathItem.get(PARAMETERS_KEY), operation.get(PARAMETERS_KEY))
                .flatMap(declared -> asList(declared).stream())
                .forEach(parameter -> names.add(parameterNameOf(path, httpMethod, parameter)));
        return List.copyOf(names);
    }

    /** One parameter's name, refusing a {@code $ref} this test cannot resolve. */
    private static String parameterNameOf(String path, String httpMethod, Object parameter) {
        Map<String, Object> declared = asMap(parameter);
        assertThat(declared.keySet())
                .as(
                        "%s %s declares a parameter behind a $ref; nothing here resolves one, so"
                                + " its name would be read as absent and every check built on the"
                                + " parameter list would pass without seeing it. Write it inline,"
                                + " as the path items are",
                        httpMethod, path)
                .doesNotContain(REF_KEY);
        assertThat(declared.get("name"))
                .as("%s %s declares a parameter with no name", httpMethod, path)
                .isInstanceOf(String.class);
        return (String) declared.get("name");
    }

    /** The {@code $ref} each documented status code resolves to, skipping inline responses. */
    private static Map<String, String> responseRefsOf(Map<String, Object> operation) {
        Map<String, String> refs = new LinkedHashMap<>();
        asMap(operation.get("responses"))
                .forEach(
                        (status, response) -> {
                            Object ref = asMap(response).get("$ref");
                            if (ref instanceof String target) {
                                refs.put(status, target);
                            }
                        });
        return Map.copyOf(refs);
    }

    /**
     * A concrete controller and the generated wrapper routed to it; the wrapper carries
     * {@code @Secured}, the controller must declare the handler.
     */
    record RoutedMethod(Class<?> controller, Method method) {}

    /** Finds the routed method implementing an operation, failing when nothing implements it. */
    private static RoutedMethod routedMethodFor(SpecOperation operation) {
        RoutedMethod routed =
                controllerClasses().stream()
                        .flatMap(SpecSecurityConsistencyTest::routedMethodsOf)
                        .filter(candidate -> implementsOperation(candidate.method(), operation))
                        .findFirst()
                        .orElseGet(() -> unimplemented(operation));
        assertThat(routeOf(routed.controller(), routed.method()))
                .as(
                        "the route implementing `%s` is mapped where the specification says",
                        operation.operationId())
                .isEqualTo(operation.httpMethod() + " " + operation.path());
        return routed;
    }

    /** Pairs each public method of a controller with the controller declaring its route. */
    private static Stream<RoutedMethod> routedMethodsOf(Class<?> controller) {
        return Stream.of(controller.getMethods())
                .map(method -> new RoutedMethod(controller, method));
    }

    /** Fails the test for an operation that no controller method implements. */
    private static RoutedMethod unimplemented(SpecOperation operation) {
        return fail(
                "%s has no implementing controller method. Every specified operation must be"
                        + " implemented by a @Controller in %s whose routed method carries"
                        + " @Operation(operationId = \"%s\").",
                operation, CONTROLLER_PACKAGE, operation.operationId());
    }

    /** Whether a method carries the {@code @Operation} naming the operation's id. */
    private static boolean implementsOperation(Method method, SpecOperation operation) {
        Operation annotation = method.getAnnotation(Operation.class);
        return annotation != null && operation.operationId().equals(annotation.operationId());
    }

    /** The delegate the routed wrapper calls, which is the method a controller has to override. */
    private static Method delegateOf(RoutedMethod routed) {
        String wrapper = routed.method().getName();
        assertThat(wrapper)
                .as(
                        "the generator names the routed wrapper `<operationId>%s`",
                        ROUTED_METHOD_SUFFIX)
                .endsWith(ROUTED_METHOD_SUFFIX);
        String delegate = wrapper.substring(0, wrapper.length() - ROUTED_METHOD_SUFFIX.length());
        try {
            return routed.controller().getMethod(delegate, routed.method().getParameterTypes());
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    routed.controller().getName() + " declares no `" + delegate + "` delegate", e);
        }
    }

    /** The principal-typed parameter of a routed method. */
    private static Class<?> principalParameterOf(Method routed) {
        return Stream.of(routed.getParameterTypes())
                .filter(ConsentManagerPrincipal.class::isAssignableFrom)
                .findFirst()
                .orElseThrow(() -> new AssertionError(routed + " declares no principal parameter"));
    }

    /** Renders the route a method is actually mapped to, as {@code "<method> <path>"}. */
    private static String routeOf(Class<?> controller, Method method) {
        return ROUTING_ANNOTATIONS.entrySet().stream()
                .filter(entry -> method.getAnnotation(entry.getValue()) != null)
                .findFirst()
                .map(
                        entry ->
                                entry.getKey()
                                        + " "
                                        + join(
                                                prefixOf(controller),
                                                uriOf(method.getAnnotation(entry.getValue()))))
                .orElseGet(() -> "no routing annotation on " + method);
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

    /** Scans compiled controllers, seeing annotations as compiled without starting a context. */
    private static List<Class<?>> controllerClasses() {
        List<Class<?>> controllers =
                packageRoots().stream().flatMap(root -> controllersUnder(root).stream()).toList();
        assertThat(controllers)
                .as("%s contains at least one @Controller", CONTROLLER_PACKAGE)
                .isNotEmpty();
        return controllers;
    }

    /** Lists the top-level {@code @Controller} classes compiled under one package root. */
    private static List<Class<?>> controllersUnder(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.getFileName().toString().endsWith(CLASS_SUFFIX))
                    .map(file -> className(root, file))
                    .filter(name -> !name.contains(NESTED_CLASS_MARKER))
                    .<Class<?>>map(SpecSecurityConsistencyTest::load)
                    .filter(type -> type.isAnnotationPresent(Controller.class))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to scan " + root, e);
        }
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

    private static String className(Path root, Path file) {
        String relative = root.relativize(file).toString();
        String withoutSuffix = relative.substring(0, relative.length() - CLASS_SUFFIX.length());
        return CONTROLLER_PACKAGE + "." + withoutSuffix.replace(java.io.File.separatorChar, '.');
    }

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

    /** The status codes an operation's {@code responses} declare, {@code default} included. */
    static Set<String> documentedStatuses(String httpMethod, String path) {
        Map<String, Object> pathItem = asMap(asMap(loadYaml(SPEC_RESOURCE).get("paths")).get(path));
        return Set.copyOf(asMap(asMap(pathItem.get(httpMethod)).get("responses")).keySet());
    }

    /** The top-level members of an operation's JSON request body; empty when it declares none. */
    static Set<String> requestBodyMembers(String httpMethod, String path) {
        Map<String, Object> pathItem = asMap(asMap(loadYaml(SPEC_RESOURCE).get("paths")).get(path));
        Map<String, Object> body = asMap(asMap(pathItem.get(httpMethod)).get("requestBody"));
        Object schema =
                asMap(asMap(asMap(body.get("content")).get("application/json")).get("schema"));
        Map<String, Object> resolved = resolve(schema, SPEC_RESOURCE);
        return resolved == null ? Set.of() : Set.copyOf(asMap(resolved.get("properties")).keySet());
    }

    /** Parses a document of the authored specification. */
    private static Map<String, Object> loadYaml(String resource) {
        try (InputStream stream = Files.newInputStream(SPEC_ROOT.resolve(resource))) {
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
