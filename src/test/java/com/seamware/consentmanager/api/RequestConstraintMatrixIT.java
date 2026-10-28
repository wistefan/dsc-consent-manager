package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import com.seamware.consentmanager.support.OpenApiResponseValidationFilter;
import com.seamware.consentmanager.support.PrivacyNoticeApiTestSupport;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Crosses every constrained request property with {valid, empty, oversized, malformed} over HTTP.
 * Bounds are read from the bundled spec, so a changed {@code maxLength} moves the probe with it.
 */
@MicronautTest(transactional = false)
@DisplayName("request constraint matrix")
class RequestConstraintMatrixIT extends PrivacyNoticeApiTestSupport {

    /**
     * Constrained request properties another matrix already crosses, keyed by that matrix. Together
     * with {@link Constraint} they must equal the spec's constrained request properties.
     */
    static final Map<String, Set<String>> COVERED_ELSEWHERE =
            Map.of(
                    "ParticipantValidationIT",
                    Set.of(
                            "ParticipantRegistration.legalName",
                            "ParticipantRegistration.selfDescriptionUri",
                            "ParticipantRegistration.email",
                            "ParticipantUpdate.legalName",
                            "ParticipantUpdate.selfDescriptionUri",
                            "ParticipantUpdate.email",
                            "ParticipantLegalPerson.registrationNumber",
                            "ParticipantLegalPerson.headquartersAddress",
                            "ParticipantLegalPerson.legalAddress",
                            "ParticipantLegalPerson.parentOrganization",
                            "ParticipantLegalPerson.subOrganization",
                            "ParticipantEndpoints.consentNotification"),
                    "ParticipantUserRegistrationIT",
                    Set.of(
                            "UserRegistration.identifier",
                            "UserRegistration.localIdentifier",
                            "UserRegistration.firstName",
                            "UserRegistration.lastName"));

    /** The bundled spec, parsed without resolution so request schemas keep their names. */
    private static final OpenAPI SPEC = parseBundle();

    /** Prefix of an internal schema reference, stripped to recover the schema's name. */
    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    /** User every route registers, links or names. */
    private static final String SUBJECT = "urn:test:user:constraint-matrix";

    /** Contract the generation routes are served. */
    private static final String CONTRACT_ID = "constraint-matrix";

    /** Values tried, in order, against a pattern; the first the pattern refuses is sent. */
    private static final List<String> PATTERN_VIOLATIONS =
            List.of(" ", "\t", "http://plain.example.org");

    /** Value of a {@code uuid} property that is no UUID. */
    private static final String NOT_A_UUID = "not-a-uuid";

    /** Value of an {@code email} property that is no address. */
    private static final String NOT_AN_EMAIL = "not-an-email";

    /** Local part and domain an over-long address is grown from. */
    private static final String EMAIL_LOCAL_PART = "user@";

    private static final String EMAIL_DOMAIN = "example.org";

    /** Longest DNS label, so an over-long address stays well-formed. */
    private static final int MAX_DOMAIN_LABEL_LENGTH = 63;

    private static final String FORMAT_UUID = "uuid";

    private static final String FORMAT_EMAIL = "email";

    /** Longest a cold Keycloak may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject IdentityProviderRegistry registry;

    /** Each kind of value the matrix sends for one property. */
    enum Kind {
        VALID,
        EMPTY,
        OVERSIZED,
        MALFORMED
    }

    /** A route taking a request body, with the caller and the status a valid body earns. */
    enum Route {
        GENERATE_NOTICE(HttpStatus.CREATED) {
            @Override
            Map<String, Object> prepare(RequestConstraintMatrixIT it) {
                return Map.of(
                        "contractUri", it.serveEcosystem(CONTRACT_ID),
                        "dataProvider", CALLER,
                        "dataConsumer", CONSUMER);
            }

            @Override
            MutableHttpRequest<?> request(Map<String, Object> body) {
                return HttpRequest.POST("/privacy-notices", body);
            }
        },
        GENERATE_BY_CONTRACT(HttpStatus.CREATED) {
            @Override
            Map<String, Object> prepare(RequestConstraintMatrixIT it) {
                return Map.of("contractUri", it.serveEcosystem(CONTRACT_ID));
            }

            @Override
            MutableHttpRequest<?> request(Map<String, Object> body) {
                return HttpRequest.POST("/privacy-notices/by-contract", body);
            }
        },
        REGISTER_USER(HttpStatus.CREATED) {
            @Override
            Map<String, Object> prepare(RequestConstraintMatrixIT it) {
                return Map.of("identifier", SUBJECT, "email", "ada@example.org");
            }

            @Override
            MutableHttpRequest<?> request(Map<String, Object> body) {
                return HttpRequest.POST("/participants/me/users", body);
            }
        },
        UPDATE_LINK(HttpStatus.OK) {
            @Override
            Map<String, Object> prepare(RequestConstraintMatrixIT it) {
                it.linkSubject();
                return Map.of("localIdentifier", "local-10427");
            }

            @Override
            MutableHttpRequest<?> request(Map<String, Object> body) {
                return HttpRequest.PATCH(
                        "/participants/me/users/"
                                + URLEncoder.encode(SUBJECT, StandardCharsets.UTF_8),
                        body);
            }
        },
        REGISTER_BULK(HttpStatus.MULTI_STATUS) {
            @Override
            Map<String, Object> prepare(RequestConstraintMatrixIT it) {
                return Map.of(
                        "identifier", SUBJECT,
                        "localIdentifier", "local-10427",
                        "email", "ada@example.org",
                        "firstName", "Ada",
                        "lastName", "Lovelace");
            }

            /** The property under test sits in the batch's one entry. */
            @Override
            MutableHttpRequest<?> request(Map<String, Object> entry) {
                return HttpRequest.POST(
                        "/participants/me/users/bulk", Map.of("users", List.of(entry)));
            }
        },
        GRANT_CONSENT(HttpStatus.CREATED) {
            @Override
            Map<String, Object> prepare(RequestConstraintMatrixIT it) {
                return Map.of("privacyNoticeId", it.seedNotice(), "decision", "GRANTED");
            }

            @Override
            MutableHttpRequest<?> request(Map<String, Object> body) {
                return HttpRequest.POST("/consents", body);
            }

            @Override
            RealmPrincipal caller() {
                return RealmPrincipal.USER;
            }
        },
        REQUEST_CONSENT(HttpStatus.CREATED) {
            @Override
            Map<String, Object> prepare(RequestConstraintMatrixIT it) {
                it.linkSubject();
                return Map.of("privacyNoticeId", it.seedNotice(), "userIdentifier", SUBJECT);
            }

            @Override
            MutableHttpRequest<?> request(Map<String, Object> body) {
                return HttpRequest.POST("/consents/requests", body);
            }
        };

        private final HttpStatus accepted;

        Route(HttpStatus accepted) {
            this.accepted = accepted;
        }

        /** Writes what a valid body needs and returns that body. */
        abstract Map<String, Object> prepare(RequestConstraintMatrixIT it);

        abstract MutableHttpRequest<?> request(Map<String, Object> body);

        RealmPrincipal caller() {
            return RealmPrincipal.PARTICIPANT;
        }
    }

    /** One constrained property of a request schema, and the route that reads it. */
    enum Constraint {
        GENERATE_CONTRACT_URI("GeneratePrivacyNotice", "contractUri", Route.GENERATE_NOTICE),
        GENERATE_DATA_PROVIDER("GeneratePrivacyNotice", "dataProvider", Route.GENERATE_NOTICE),
        GENERATE_DATA_CONSUMER("GeneratePrivacyNotice", "dataConsumer", Route.GENERATE_NOTICE),
        BY_CONTRACT_URI("GenerateByContract", "contractUri", Route.GENERATE_BY_CONTRACT),
        REGISTRATION_EMAIL("UserRegistration", "email", Route.REGISTER_USER),
        LINK_LOCAL_IDENTIFIER("ParticipantUserLinkUpdate", "localIdentifier", Route.UPDATE_LINK),
        BULK_IDENTIFIER("BulkUserRegistrationEntry", "identifier", Route.REGISTER_BULK),
        BULK_LOCAL_IDENTIFIER("BulkUserRegistrationEntry", "localIdentifier", Route.REGISTER_BULK),
        BULK_EMAIL("BulkUserRegistrationEntry", "email", Route.REGISTER_BULK),
        BULK_FIRST_NAME("BulkUserRegistrationEntry", "firstName", Route.REGISTER_BULK),
        BULK_LAST_NAME("BulkUserRegistrationEntry", "lastName", Route.REGISTER_BULK),
        GRANT_NOTICE_ID("GrantConsent", "privacyNoticeId", Route.GRANT_CONSENT),
        GRANT_DECISION("GrantConsent", "decision", Route.GRANT_CONSENT),
        REQUEST_NOTICE_ID("ConsentRequest", "privacyNoticeId", Route.REQUEST_CONSENT);

        private final String schema;
        private final String property;
        private final Route route;

        Constraint(String schema, String property, Route route) {
            this.schema = schema;
            this.property = property;
            this.route = route;
        }

        /** The property's schema as the bundled spec declares it. */
        Schema<?> declared() {
            return Objects.requireNonNull(
                    component(schema).getProperties().get(property),
                    () -> schema + " declares no property " + property);
        }

        String qualifiedName() {
            return schema + "." + property;
        }

        @Override
        public String toString() {
            return qualifiedName();
        }
    }

    @BeforeEach
    void awaitTheRealm() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
    }

    /** Every applicable kind of every constraint; a kind the spec does not constrain is skipped. */
    static Stream<Arguments> matrix() {
        return Arrays.stream(Constraint.values())
                .flatMap(
                        constraint ->
                                Arrays.stream(Kind.values())
                                        .filter(kind -> applies(kind, constraint.declared()))
                                        .map(kind -> Arguments.of(constraint, kind)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("matrix")
    void answersAsTheSpecDeclares(Constraint constraint, Kind kind) {
        Schema<?> declared = constraint.declared();
        Route route = constraint.route;
        Map<String, Object> body = new LinkedHashMap<>(route.prepare(this));
        String valid = (String) body.get(constraint.property);
        body.put(constraint.property, valueOf(kind, declared, valid));
        HttpStatus expected =
                kind == Kind.VALID || (kind == Kind.EMPTY && admitsEmpty(declared))
                        ? route.accepted
                        : HttpStatus.BAD_REQUEST;

        HttpResponse<String> response =
                exchange(route.request(body), KeycloakTestResource.accessToken(route.caller()));

        assertThat(response.code()).as(response.body()).isEqualTo(expected.getCode());
        if (expected == HttpStatus.BAD_REQUEST) {
            assertThat(response.getContentType().orElseThrow().toString()).isEqualTo(PROBLEM_JSON);
            assertThat(namedFields(body(response)))
                    .as("the refusal names the property it refused")
                    .contains(constraint.property);
        }
    }

    @Test
    @DisplayName("every constrained property of every request body has a row here or elsewhere")
    void everyConstrainedRequestPropertyIsCovered() {
        Set<String> covered =
                Arrays.stream(Constraint.values())
                        .map(Constraint::qualifiedName)
                        .collect(Collectors.toCollection(TreeSet::new));
        COVERED_ELSEWHERE.values().forEach(covered::addAll);

        assertThat(covered).isEqualTo(constrainedRequestProperties());
    }

    /** Whether the declared constraints give the kind something to probe. */
    private static boolean applies(Kind kind, Schema<?> declared) {
        return switch (kind) {
            case VALID, EMPTY -> true;
            case OVERSIZED -> declared.getMaxLength() != null;
            case MALFORMED ->
                    declared.getEnum() != null
                            || declared.getFormat() != null
                            || declared.getPattern() != null;
        };
    }

    /** Whether the spec admits {@code ""}: no minimum, no format, no enum, no refusing pattern. */
    private static boolean admitsEmpty(Schema<?> declared) {
        boolean noMinimum = declared.getMinLength() == null || declared.getMinLength() == 0;
        boolean patternAdmits =
                declared.getPattern() == null
                        || Pattern.compile(declared.getPattern()).matcher("").find();
        return noMinimum
                && patternAdmits
                && declared.getFormat() == null
                && declared.getEnum() == null;
    }

    private static String valueOf(Kind kind, Schema<?> declared, String valid) {
        return switch (kind) {
            case VALID -> valid;
            case EMPTY -> "";
            case OVERSIZED -> oversized(declared, valid);
            case MALFORMED -> malformed(declared);
        };
    }

    /** One character over the bound, otherwise as well-formed as the valid value. */
    private static String oversized(Schema<?> declared, String valid) {
        int length = declared.getMaxLength() + 1;
        if (FORMAT_EMAIL.equals(declared.getFormat())) {
            return emailOfLength(length);
        }
        return valid + "x".repeat(length - valid.length());
    }

    /** A short value breaking the enum, the format or the pattern, in that order of precedence. */
    private static String malformed(Schema<?> declared) {
        if (declared.getEnum() != null) {
            String outside =
                    "NOT_"
                            + declared.getEnum().stream()
                                    .map(String::valueOf)
                                    .collect(Collectors.joining("_"));
            assertThat(declared.getEnum().stream().map(String::valueOf)).doesNotContain(outside);
            return outside;
        }
        if (declared.getFormat() != null) {
            return switch (declared.getFormat()) {
                case FORMAT_UUID -> NOT_A_UUID;
                case FORMAT_EMAIL -> NOT_AN_EMAIL;
                default ->
                        throw new IllegalStateException(
                                "No malformed value for format " + declared.getFormat());
            };
        }
        Pattern pattern = Pattern.compile(declared.getPattern());
        return PATTERN_VIOLATIONS.stream()
                .filter(candidate -> !pattern.matcher(candidate).find())
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No candidate breaks " + declared.getPattern()));
    }

    /** A well-formed address of exactly {@code length} characters, grown by DNS labels. */
    private static String emailOfLength(int length) {
        StringBuilder local = new StringBuilder(EMAIL_LOCAL_PART);
        StringBuilder domain = new StringBuilder(EMAIL_DOMAIN);
        int remaining = length - local.length() - domain.length();
        while (remaining > 1) {
            int label = Math.min(MAX_DOMAIN_LABEL_LENGTH, remaining - 1);
            domain.insert(0, "x".repeat(label) + ".");
            remaining -= label + 1;
        }
        if (remaining == 1) {
            local.insert(0, 'x');
        }
        return local.append(domain).toString();
    }

    /**
     * The fields a problem names: its {@code errors} when present, else the properties its detail
     * quotes as whole words, which is all a body that failed decoding carries.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> namedFields(Map<String, Object> problem) {
        if (problem.get("errors") instanceof List<?> errors) {
            return errors.stream()
                    .map(error -> (String) ((Map<String, Object>) error).get("field"))
                    .collect(Collectors.toSet());
        }
        String detail = String.valueOf(problem.get("detail"));
        return Arrays.stream(Constraint.values())
                .map(constraint -> constraint.property)
                .filter(
                        property ->
                                Pattern.compile("\\b" + Pattern.quote(property) + "\\b")
                                        .matcher(detail)
                                        .find())
                .collect(Collectors.toSet());
    }

    /** {@code Schema.property} for every constrained property of every request-body schema. */
    private static Set<String> constrainedRequestProperties() {
        Set<String> named = new LinkedHashSet<>();
        SPEC.getPaths().values().stream()
                .flatMap(path -> path.readOperations().stream())
                .map(Operation::getRequestBody)
                .filter(Objects::nonNull)
                .flatMap(requestBody -> requestBody.getContent().values().stream())
                .forEach(
                        media -> {
                            assertThat(media.getSchema().get$ref())
                                    .as("a request body is a named schema, so this walk sees it")
                                    .isNotNull();
                            collectNamed(media.getSchema(), named);
                        });
        return named.stream()
                .flatMap(
                        name ->
                                propertiesOf(component(name)).entrySet().stream()
                                        .filter(property -> isConstrained(property.getValue()))
                                        .map(property -> name + "." + property.getKey()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Adds every schema name reachable from {@code schema} through refs, properties and items. */
    private static void collectNamed(Schema<?> schema, Set<String> named) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(SCHEMA_REF_PREFIX.length());
            if (named.add(name)) {
                collectNamed(component(name), named);
            }
            return;
        }
        propertiesOf(schema).values().forEach(property -> collectNamed(property, named));
        collectNamed(schema.getItems(), named);
    }

    private static boolean isConstrained(Schema<?> property) {
        return Stream.of(
                        property.getMinLength(),
                        property.getMaxLength(),
                        property.getPattern(),
                        property.getFormat(),
                        property.getEnum(),
                        property.getMinimum(),
                        property.getMaximum(),
                        property.getMinItems(),
                        property.getMaxItems())
                .anyMatch(Objects::nonNull);
    }

    @SuppressWarnings("rawtypes")
    private static Map<String, Schema> propertiesOf(Schema<?> schema) {
        return schema.getProperties() == null ? Map.of() : schema.getProperties();
    }

    private static Schema<?> component(String name) {
        return Objects.requireNonNull(
                SPEC.getComponents().getSchemas().get(name), () -> "No schema " + name);
    }

    private static OpenAPI parseBundle() {
        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        return Objects.requireNonNull(
                new OpenAPIV3Parser()
                        .read(
                                OpenApiResponseValidationFilter.bundleFile().toUri().toString(),
                                null,
                                options),
                "the bundled spec did not parse; run ./mvnw generate-resources first");
    }

    /** Registers and links {@link #SUBJECT} to the caller, as the routes acting on it require. */
    private void linkSubject() {
        HttpResponse<String> response =
                exchange(
                        HttpRequest.POST("/participants/me/users", Map.of("identifier", SUBJECT)),
                        KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
    }

    /** A notice the caller provides, written straight to the table. */
    private String seedNotice() {
        return seed(
                        uri(CONTRACT_ID),
                        "Constraint matrix notice",
                        CALLER,
                        CONSUMER,
                        PrivacyNoticeFixtures.emptyPayload())
                .getId()
                .toString();
    }

    /** Issues the request under the token, returning a refusal as a response. */
    private HttpResponse<String> exchange(MutableHttpRequest<?> request, String token) {
        try {
            return client.toBlocking().exchange(request.bearerAuth(token), String.class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> refusal = (HttpResponse<String>) e.getResponse();
            return refusal;
        }
    }
}
