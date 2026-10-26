package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.UpstreamServiceException;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.service.ContractDiscoveryService;
import com.seamware.consentmanager.service.ContractPartyResolver;
import com.seamware.consentmanager.service.DiscoveredContract;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.ConsentFixtures;
import com.seamware.consentmanager.support.KeyMaterialForms;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import com.seamware.consentmanager.support.LogCapture;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.SQLException;
import java.text.ParseException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Proves no event at INFO or above quotes a bearer token, a private key member, the pseudonym key
 * or a personal identifier, across an authenticated end-to-end run and two forced server failures.
 */
@MicronautTest(transactional = false)
@DisplayName("Log hygiene above DEBUG")
class LogLeakIT extends KeycloakAndPostgresTestResource {

    static final String FAILING_DISCOVERY = "test.log-leak.failing-discovery";

    private static final String SIGNING_KEY_PROPERTY = "consent-manager.receipts.signing-key";

    private static final String PSEUDONYM_KEY_PROPERTY = "consent-manager.logging.pseudonym-key";

    /** Shortest token segment searched for on its own; shorter ones would match by chance. */
    private static final int MIN_SECRET_SEGMENT_LENGTH = 16;

    private static final String TOKEN_SEGMENT_SEPARATOR = "\\.";

    private static final String SENTINEL_IDENTIFIER = "urn:test:user:log-leak-sentinel-c41d";

    private static final String SENTINEL_EMAIL = "sentinella.leakwatch@log-leak.test";

    private static final String SENTINEL_FIRST_NAME = "Sentinella";

    private static final String SENTINEL_LAST_NAME = "Leakwatch";

    private static final String MALFORMED_TOKEN =
            "malformed-log-leak-token.c2VudGluZWwtcGF5bG9hZC1sb2ctbGVhaw.not-a-signature";

    private static final String QUERY_TOKEN_PARAMETER = "access_token";

    private static final String PATH_REGISTER = "/users/register";

    private static final String PATH_ME = "/users/me";

    private static final String PATH_PARTICIPANT_USERS = "/participants/me/users";

    private static final String PATH_CONSENTS = "/consents";

    private static final String PATH_REVOKE = "/consents/{id}/revoke";

    private static final String PATH_DISCOVERY = "/contracts/available";

    private static final String MEMBER_ID = "id";

    private static final String PROVIDER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String CONSUMER = "urn:test:participant:log-leak-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:log-leak";

    private static final String PURPOSE = "Log hygiene";

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    private static final int RSA_KEY_SIZE = 2048;

    private static final RSAKey SIGNING_KEY = rsaKey();

    /** A server failure the replaced discovery service raises, with the status it must answer. */
    enum ForcedFailure {
        UNHANDLED_DATA_ACCESS(HttpStatus.INTERNAL_SERVER_ERROR, DataAccessException.class),
        UPSTREAM(HttpStatus.BAD_GATEWAY, UpstreamServiceException.class);

        private final HttpStatus status;

        private final Class<? extends RuntimeException> type;

        ForcedFailure(HttpStatus status, Class<? extends RuntimeException> type) {
            this.status = status;
            this.type = type;
        }

        /** A failure whose message, and for the unhandled one also its cause, quote the subject. */
        RuntimeException raise() {
            String quoted =
                    "Key (identifier)=(%s) already exists for %s"
                            .formatted(SENTINEL_IDENTIFIER, SENTINEL_EMAIL);
            return switch (this) {
                case UNHANDLED_DATA_ACCESS ->
                        new DataAccessException(quoted, new SQLException(quoted));
                case UPSTREAM -> new UpstreamServiceException(quoted);
            };
        }
    }

    /**
     * What no event at INFO or above may contain. The subject values cover both the sentinel the
     * participant registers and the token user who is provisioned, grants, revokes and is erased.
     */
    enum Forbidden {
        BEARER_TOKEN,
        MALFORMED_TOKEN,
        PRIVATE_KEY_MEMBER,
        PSEUDONYM_KEY,
        SUBJECT_IDENTIFIER,
        SUBJECT_EMAIL,
        SUBJECT_NAME
    }

    /** Attached before the context starts, so startup logging is captured too. */
    private final LogCapture captured = LogCapture.attach();

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    @Inject FailingDiscoveryService discovery;

    @Property(name = PSEUDONYM_KEY_PROPERTY)
    String pseudonymKey;

    private String userToken;

    /** Every bearer token the run sends, so each is searched for, not only {@link #userToken}. */
    private final Set<String> sentTokens = new LinkedHashSet<>();

    private UUID subjectId;

    /** The token user's row as provisioned, read before erasure clears it. */
    private User subject;

    private PrivacyNotice notice;

    /** Everything at INFO or above, rendered; null until the run has happened. */
    private List<String> rendered;

    /** Every ERROR event's formatted message from the run. */
    private List<String> errors;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put(SIGNING_KEY_PROPERTY, SIGNING_KEY.toJSONString());
        properties.put(FAILING_DISCOVERY, Boolean.TRUE.toString());
        return properties;
    }

    /** Runs the whole scenario once; every test then inspects what it logged. */
    @BeforeEach
    void runOnce() throws ParseException {
        if (rendered != null) {
            return;
        }
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        userToken = mint(RealmPrincipal.USER);
        notice = seedNotice();

        assertThat(exchange(HttpRequest.POST(PATH_REGISTER, ""), userToken).code())
                .isIn(HttpStatus.OK.getCode(), HttpStatus.CREATED.getCode());
        subject = users.findByIdentifier(KeycloakTestResource.subject(userToken)).orElseThrow();
        subjectId = subject.getId();
        registerSentinel();
        UUID consent = grant();
        assertThat(exchange(HttpRequest.POST(revokePath(consent), ""), userToken).code())
                .isEqualTo(HttpStatus.OK.getCode());
        assertThat(exchange(HttpRequest.DELETE(PATH_ME), userToken).code())
                .isEqualTo(HttpStatus.OK.getCode());

        assertThat(exchange(HttpRequest.GET(PATH_ME), MALFORMED_TOKEN).code())
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
        assertThat(
                        exchange(
                                        HttpRequest.GET(
                                                UriBuilder.of(PATH_ME)
                                                        .queryParam(
                                                                QUERY_TOKEN_PARAMETER, userToken)
                                                        .build()),
                                        null)
                                .code())
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
        for (ForcedFailure failure : ForcedFailure.values()) {
            discovery.failure = failure::raise;
            assertThat(exchange(HttpRequest.GET(PATH_DISCOVERY), mint(RealmPrincipal.USER)).code())
                    .isEqualTo(failure.status.getCode());
        }
        discovery.failure = null;

        List<ILoggingEvent> events = captured.events();
        rendered =
                events.stream()
                        .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                        .map(LogCapture::render)
                        .toList();
        errors =
                events.stream()
                        .filter(event -> event.getLevel() == Level.ERROR)
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList();
    }

    /** Deleting a consent cascades its events and receipts; consents restrict everything else. */
    @AfterAll
    void removeCommittedRowsAndDetach() {
        captured.close();
        if (subjectId != null) {
            consents.findByUserIdAndStatusIn(subjectId, List.of(ConsentStatus.values()))
                    .forEach(consents::delete);
            users.findById(subjectId).ifPresent(users::delete);
        }
        // Erasure unlinks the subject, so the discovery requests provisioned a fresh row.
        if (userToken != null) {
            users.findByIdentifier(KeycloakTestResource.subject(userToken))
                    .ifPresent(users::delete);
        }
        users.findByIdentifier(SENTINEL_IDENTIFIER).ifPresent(users::delete);
        if (notice != null) {
            notices.deleteById(notice.getId());
        }
        List.of(PROVIDER, CONSUMER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @ParameterizedTest
    @EnumSource(Forbidden.class)
    @DisplayName("quotes no forbidden value in any event at INFO or above")
    void noForbiddenValueAboveDebug(Forbidden forbidden) {
        Set<String> values = valuesOf(forbidden);

        assertThat(values).isNotEmpty();
        for (String value : values) {
            assertThat(rendered).noneMatch(event -> event.contains(value));
        }
    }

    @ParameterizedTest
    @EnumSource(ForcedFailure.class)
    @DisplayName("still logs the failure summary at ERROR")
    void forcedFailureLogsItsSummary(ForcedFailure failure) {
        assertThat(errors)
                .anyMatch(
                        line ->
                                line.contains(PATH_DISCOVERY)
                                        && line.contains(failure.type.getName()));
    }

    private Set<String> valuesOf(Forbidden forbidden) {
        return switch (forbidden) {
            case BEARER_TOKEN ->
                    sentTokens.stream()
                            .flatMap(
                                    token ->
                                            Stream.concat(
                                                    Stream.of(token),
                                                    Arrays.stream(
                                                                    token.split(
                                                                            TOKEN_SEGMENT_SEPARATOR))
                                                            .filter(
                                                                    segment ->
                                                                            segment.length()
                                                                                    >= MIN_SECRET_SEGMENT_LENGTH)))
                            .collect(Collectors.toSet());
            case MALFORMED_TOKEN -> Set.of(MALFORMED_TOKEN);
            case PRIVATE_KEY_MEMBER -> KeyMaterialForms.of(SIGNING_KEY);
            case PSEUDONYM_KEY -> Set.of(pseudonymKey);
            case SUBJECT_IDENTIFIER -> Set.of(SENTINEL_IDENTIFIER, subject.getIdentifier());
            case SUBJECT_EMAIL -> Set.of(SENTINEL_EMAIL, subject.getEmail());
            case SUBJECT_NAME ->
                    Set.of(
                            SENTINEL_FIRST_NAME,
                            SENTINEL_LAST_NAME,
                            subject.getFirstName(),
                            subject.getLastName());
        };
    }

    private PrivacyNotice seedNotice() {
        return notices.save(
                new PrivacyNotice(
                        CONTRACT_URI,
                        null,
                        ConsentFixtures.participant(participants, PROVIDER).getId(),
                        ConsentFixtures.participant(participants, CONSUMER).getId(),
                        new PrivacyNoticePayload(
                                List.of(PURPOSE), null, null, null, null, null, null, null, null)));
    }

    /** The participant registers the sentinel person, so every attribute crosses the service. */
    private void registerSentinel() {
        HttpResponse<String> registered =
                exchange(
                        HttpRequest.POST(
                                PATH_PARTICIPANT_USERS,
                                Map.of(
                                        "identifier", SENTINEL_IDENTIFIER,
                                        "email", SENTINEL_EMAIL,
                                        "firstName", SENTINEL_FIRST_NAME,
                                        "lastName", SENTINEL_LAST_NAME)),
                        mint(RealmPrincipal.PARTICIPANT));
        assertThat(registered.code()).isEqualTo(HttpStatus.CREATED.getCode());
    }

    private UUID grant() throws ParseException {
        HttpResponse<String> grant =
                exchange(
                        HttpRequest.POST(
                                PATH_CONSENTS,
                                Map.of(
                                        "privacyNoticeId",
                                        notice.getId(),
                                        "decision",
                                        ConsentStatus.GRANTED.name())),
                        userToken);
        assertThat(grant.code()).isEqualTo(HttpStatus.CREATED.getCode());
        return UUID.fromString(
                JSONObjectUtils.getString(JSONObjectUtils.parse(grant.body()), MEMBER_ID));
    }

    private static String revokePath(UUID consent) {
        return UriBuilder.of(PATH_REVOKE).expand(Map.of(MEMBER_ID, consent.toString())).toString();
    }

    /** Issues a token for {@code principal} and records it as one the scan must not find. */
    private String mint(RealmPrincipal principal) {
        String token = KeycloakTestResource.accessToken(principal);
        sentTokens.add(token);
        return token;
    }

    /** Sends {@code request} with {@code token} as bearer, if any; a refusal is returned. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request, String token) {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(RSA_KEY_SIZE).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The real discovery service, unless a failure is armed; the route the forced failures use. */
    @Singleton
    @Replaces(ContractDiscoveryService.class)
    @Requires(property = FAILING_DISCOVERY, value = "true")
    static class FailingDiscoveryService extends ContractDiscoveryService {

        volatile Supplier<RuntimeException> failure;

        FailingDiscoveryService(
                PrivacyNoticeRepository notices,
                UserParticipantRepository links,
                ParticipantRepository participants,
                ContractGateway contracts,
                ContractPartyResolver parties,
                @Named(TaskExecutors.BLOCKING) ExecutorService executor,
                ConsentManagerConfiguration.ContractDiscovery configuration) {
            super(notices, links, participants, contracts, parties, executor, configuration);
        }

        @Override
        public List<DiscoveredContract> forUser(@NonNull User subject) {
            Supplier<RuntimeException> armed = failure;
            if (armed != null) {
                throw armed.get();
            }
            return super.forUser(subject);
        }
    }
}
