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

    /**
     * Operations {@code api/openapi.yaml} declares, which is how many cases every generated test
     * below must run.
     */
    private static final int SPECIFIED_OPERATION_COUNT = 16;

    /** Path item key that would hide every operation beneath it from this test. */
    private static final String REF_KEY = "$ref";

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

    /** Status code every secured operation must document as a failed authentication. */
    private static final String UNAUTHORIZED_STATUS = "401";

    /** Status code every secured operation must document as a refused authorization. */
    private static final String FORBIDDEN_STATUS = "403";

    /** Shared response component a secured operation's {@code 401} must reference. */
    private static final String UNAUTHORIZED_REF = "#/components/responses/Unauthorized";

    /** Shared response component a secured operation's {@code 403} must reference. */
    private static final String FORBIDDEN_REF = "#/components/responses/Forbidden";

    /**
     * Suffix the generator appends to the routed wrapper's name; the delegate keeps the bare {@code
     * operationId}.
     */
    private static final String ROUTED_METHOD_SUFFIX = "Api";

    /**
     * Roles a parameter of each principal type can actually hold.
     *
     * <p>{@code PrincipalArgumentBinder} refuses with a {@code 403} when the resolved principal is
     * not an instance of the declared type, so an operation whose {@code x-principal} cannot hold
     * one of its {@code x-roles} is unservable for every caller holding that role.
     */
    private static final Map<Class<?>, Set<Role>> ROLES_PER_PRINCIPAL_TYPE =
            Map.of(
                    UserPrincipal.class, Set.of(Role.USER),
                    ParticipantPrincipal.class, Set.of(Role.PARTICIPANT),
                    CatalogPrincipal.class, Set.of(Role.CATALOG),
                    ConsentManagerPrincipal.class, EnumSet.allOf(Role.class));

    /**
     * One operation of the specification together with the access it declares.
     *
     * @param roles the scopes the operation's security requirements name, which are the role names
     *     its route must enforce; empty when it is anonymous or names none
     * @param responseRefs {@code $ref} target per documented status code, for codes that are a bare
     *     reference; a response written out inline contributes no entry
     */
    record SpecOperation(
            String operationId,
            String httpMethod,
            String path,
            boolean anonymous,
            List<String> schemes,
            List<String> roles,
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
        assertThat(operations)
                .as(
                        "every case below is generated from this list, so an operation missing from"
                                + " it is not a failure but a silence; the count is stated here so"
                                + " that a path item this parser cannot see costs a build. Raise it"
                                + " in the commit that adds the operation")
                .hasSize(SPECIFIED_OPERATION_COUNT);
        return operations.stream();
    }

    /**
     * Asserts that no path item hides its operations behind an external {@code $ref}.
     *
     * <p>The specification is read with plain SnakeYAML and nothing here resolves a reference, so a
     * path item written as {@code /users: {$ref: "./paths/users.yaml#/~1users"}} has exactly one
     * key and contributes zero cases: every operation beneath it would go unchecked while this
     * suite stayed green. The guard is therefore a refusal rather than a resolution - operations
     * stay inline in {@code api/openapi.yaml}, and only schemas and responses are externalised.
     */
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

    /**
     * Exercises the guard above against hand-built path items, so that it is known to reject what
     * it claims to rather than merely being present and never triggered.
     */
    @Nested
    @DisplayName("the inline-operations guard itself")
    class InlineOperationsGuard {

        /** A path item per shape the guard has to judge, with the verdict expected of it. */
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
                            Map.of(REF_KEY, "./paths/users.yaml#/~1users", "parameters", List.of()),
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

    /**
     * Asserts that the route implementing an operation enforces what the specification promises.
     */
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

    /**
     * Asserts that a secured operation takes its caller as a typed principal.
     *
     * <p>{@link com.seamware.consentmanager.security.PrincipalResolutionFilter} resolves - and so
     * runs its "identifier claim present" and "participant is registered" refusals - only for a
     * route that declares such a parameter. Without this assertion a secured route that injects
     * {@code Authentication}, or only path variables, would skip both checks silently and be served
     * on the strength of its {@code @Secured} authority alone.
     */
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

    /**
     * Asserts that the concrete controller declares the handler rather than inheriting the
     * generator's stub.
     *
     * <p>{@code generateOperationsToReturnNotImplemented} leaves the delegate with a {@code 501}
     * body instead of declaring it abstract, so an operation specified without a handler compiles,
     * routes, and answers {@code 501} in production. The routed wrapper is always inherited, which
     * is why it cannot be the thing checked.
     */
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

    /**
     * Asserts that an operation's principal type can hold every role it admits.
     *
     * <p>Nothing in the specification ties {@code x-principal} to {@code x-roles}, and a mismatched
     * pair only shows at runtime: {@code PrincipalArgumentBinder} refuses with a {@code 403} when
     * the resolved principal is of another shape, so the operation looks correctly specified and
     * serves nobody holding the unholdable role.
     */
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

    /**
     * Asserts the principal-to-roles table covers the sealed hierarchy, so a newly permitted
     * principal type cannot silently skip the check above.
     */
    @Test
    @DisplayName("every permitted principal type declares the roles it can hold")
    void principalTypeTableCoversTheSealedHierarchy() {
        assertThat(ROLES_PER_PRINCIPAL_TYPE)
                .as("one entry per permitted principal type, plus the sealed supertype itself")
                .containsKey(ConsentManagerPrincipal.class)
                .containsKeys(ConsentManagerPrincipal.class.getPermittedSubclasses());
    }

    /**
     * Asserts that an operation documents exactly the failure responses its access implies: the
     * shared {@code 401} and {@code 403} when it is secured, and neither when it is anonymous.
     */
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
                responseRefsOf(operation));
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
     * The concrete controller serving an operation and the generated wrapper routed to it.
     *
     * <p>Both halves matter: the wrapper carries {@code @Secured} and the typed principal
     * parameter, while the controller is where the handler must actually be declared.
     */
    record RoutedMethod(Class<?> controller, Method method) {}

    /** Finds the routed method implementing an operation, failing when nothing implements it. */
    private static RoutedMethod routedMethodFor(SpecOperation operation) {
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
                return new RoutedMethod(controller, method);
            }
        }
        return fail(
                "%s has no implementing controller method. Every specified operation must be"
                        + " implemented by a @Controller in %s whose routed method carries"
                        + " @Operation(operationId = \"%s\").",
                operation, CONTROLLER_PACKAGE, operation.operationId());
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
