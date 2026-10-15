package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.signing.SigningKeyStore;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.ConsentFixtures;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.math.BigInteger;
import java.security.interfaces.ECPrivateKey;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

/**
 * Proves no private key member reaches a log event or a response body: from startup with a known
 * key, through signing and reading receipts, the ephemeral-key warning, and a genuine signing
 * failure that Nimbus raises from the configured key.
 */
@MicronautTest(transactional = false)
@DisplayName("Receipt signing key material")
class KeyMaterialLeakIT extends KeycloakAndPostgresTestResource {

    static final String BREAKABLE_STORE = "test.signing.breakable";

    private static final String SIGNING_KEY_PROPERTY = "consent-manager.receipts.signing-key";

    private static final String PATH_CONSENTS = "/consents";

    private static final String PATH_MY_CONSENT = "/consents/me/{id}";

    private static final String PATH_JWKS = "/.well-known/jwks.json";

    private static final String PARAM_FORMAT = "format";

    private static final String FORMAT_ISO = "receipt";

    private static final String FORMAT_KANTARA = "receipt-kantara";

    private static final String MEMBER_ID = "id";

    private static final String PROVIDER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String CONSUMER = "urn:test:participant:key-material-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:key-material";

    private static final String PURPOSE = "Key material";

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    private static final int RSA_KEY_SIZE = 2048;

    private static final int HEX = 16;

    /** RSA, the type with the most private members: d, p, q, dp, dq and qi. */
    private static final RSAKey SIGNING_KEY = rsaKey();

    /** What a scenario does; each must leave both logs and bodies free of every known secret. */
    enum Scenario {
        SIGN_AND_READ(null),
        EPHEMERAL_KEY(Level.WARN),
        FAILED_GRANT(Level.ERROR),
        FAILED_RECORD_READ(Level.ERROR);

        /** A level the scenario must log at, so the check is not vacuous; null when none. */
        private final Level logged;

        Scenario(Level logged) {
            this.logged = logged;
        }
    }

    private ReattachOnReset reattach;

    /** Attached before the context starts, so startup logging is captured too. */
    private final ListAppender<ILoggingEvent> captured = attach();

    private final Set<String> secrets = new HashSet<>(secretsOf(SIGNING_KEY));

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    @Inject BreakableSigningKeyStore store;

    private User subject;

    private PrivacyNotice notice;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put(SIGNING_KEY_PROPERTY, SIGNING_KEY.toJSONString());
        properties.put(BREAKABLE_STORE, Boolean.TRUE.toString());
        return properties;
    }

    @BeforeEach
    void seedTheNoticeAndTheSubject() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        String identifier = KeycloakTestResource.subject(userToken());
        subject =
                users.findByIdentifier(identifier)
                        .orElseGet(() -> users.save(new User(identifier, null, null, null)));
        notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                null,
                                ConsentFixtures.participant(participants, PROVIDER).getId(),
                                ConsentFixtures.participant(participants, CONSUMER).getId(),
                                new PrivacyNoticePayload(
                                        List.of(PURPOSE),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)));
    }

    /** Deleting a consent cascades its events and receipt; consents restrict everything else. */
    @AfterEach
    void removeCommittedRows() {
        store.broken = false;
        consents.findByUserIdAndStatusIn(subject.getId(), List.of(ConsentStatus.values()))
                .forEach(consents::delete);
        notices.deleteById(notice.getId());
        users.delete(subject);
        List.of(PROVIDER, CONSUMER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @AfterAll
    void detach() {
        rootLogger().getLoggerContext().removeListener(reattach);
        rootLogger().detachAppender(captured);
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    @DisplayName("quotes no private key member in any log event or response body")
    void noPrivateMemberLeaks(Scenario scenario) throws ParseException {
        int before = events().size();

        List<String> bodies = run(scenario);

        List<ILoggingEvent> events = events();
        if (scenario.logged != null) {
            assertThat(events.subList(before, events.size()))
                    .extracting(ILoggingEvent::getLevel)
                    .contains(scenario.logged);
        }
        assertThat(bodies).isNotEmpty();
        List<String> rendered = events.stream().map(KeyMaterialLeakIT::render).toList();
        for (String secret : secrets) {
            assertThat(rendered).noneMatch(event -> event.contains(secret));
            assertThat(bodies).noneMatch(body -> body.contains(secret));
        }
    }

    private List<String> run(Scenario scenario) throws ParseException {
        return switch (scenario) {
            case SIGN_AND_READ -> {
                HttpResponse<String> grant = grant();
                assertThat(grant.code()).isEqualTo(HttpStatus.CREATED.getCode());
                UUID id = UUID.fromString(JSONObjectUtils.getString(parse(grant), MEMBER_ID));
                List<String> bodies = new ArrayList<>(List.of(grant.body()));
                for (String format : List.of(FORMAT_KANTARA, FORMAT_ISO)) {
                    HttpResponse<String> read = read(id, format);
                    assertThat(read.code()).isEqualTo(HttpStatus.OK.getCode());
                    bodies.add(read.body());
                }
                bodies.add(exchange(HttpRequest.GET(PATH_JWKS)).body());
                yield bodies;
            }
            case EPHEMERAL_KEY -> {
                ConsentManagerConfiguration.Receipts settings =
                        new ConsentManagerConfiguration.Receipts();
                settings.setEphemeralSigningKey(true);
                SigningKeyStore ephemeral = new SigningKeyStore(settings);
                secrets.addAll(ephemeralSecrets(ephemeral));
                yield ephemeral.verificationKeys().stream().map(JWK::toJSONString).toList();
            }
            case FAILED_GRANT -> {
                store.broken = true;
                HttpResponse<String> grant = grant();
                assertThat(grant.code()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getCode());
                yield List.of(grant.body());
            }
            case FAILED_RECORD_READ -> {
                HttpResponse<String> grant = grant();
                UUID id = UUID.fromString(JSONObjectUtils.getString(parse(grant), MEMBER_ID));
                store.broken = true;
                HttpResponse<String> read = read(id, FORMAT_ISO);
                assertThat(read.code()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getCode());
                yield List.of(grant.body(), read.body());
            }
        };
    }

    private HttpResponse<String> grant() {
        return exchange(
                HttpRequest.POST(
                        PATH_CONSENTS,
                        Map.of(
                                "privacyNoticeId",
                                notice.getId(),
                                "decision",
                                ConsentStatus.GRANTED.name())));
    }

    private HttpResponse<String> read(UUID id, String format) {
        return exchange(
                HttpRequest.GET(
                        UriBuilder.of(
                                        UriBuilder.of(PATH_MY_CONSENT)
                                                .expand(Map.of("id", id.toString()))
                                                .toString())
                                .queryParam(PARAM_FORMAT, format)
                                .build()));
    }

    /** Sends {@code request} as the subject, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request.bearerAuth(userToken()), String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    /** A snapshot taken under the appender's lock, since request threads append concurrently. */
    private List<ILoggingEvent> events() {
        synchronized (captured) {
            return List.copyOf(captured.list);
        }
    }

    private static Map<String, Object> parse(HttpResponse<String> response) throws ParseException {
        return JSONObjectUtils.parse(response.body());
    }

    private static String userToken() {
        return KeycloakTestResource.accessToken(RealmPrincipal.USER);
    }

    /** Everything a log encoder could write: message, arguments, MDC and the throwable chain. */
    private static String render(ILoggingEvent event) {
        List<String> parts = new ArrayList<>();
        parts.add(event.getFormattedMessage());
        parts.add(event.getMessage());
        if (event.getArgumentArray() != null) {
            Stream.of(event.getArgumentArray()).map(String::valueOf).forEach(parts::add);
        }
        parts.addAll(event.getMDCPropertyMap().values());
        if (event.getThrowableProxy() != null) {
            parts.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        return String.join("\n", parts.stream().filter(Objects::nonNull).toList());
    }

    /** Each private member as base64url, and as the decimal and hex a key's toString may use. */
    private static Set<String> secretsOf(RSAKey key) {
        Set<String> secrets = new HashSet<>();
        Stream.of(
                        key.getPrivateExponent(),
                        key.getFirstPrimeFactor(),
                        key.getSecondPrimeFactor(),
                        key.getFirstFactorCRTExponent(),
                        key.getSecondFactorCRTExponent(),
                        key.getFirstCRTCoefficient())
                .forEach(member -> secrets.addAll(forms(member)));
        return secrets;
    }

    /**
     * The ephemeral key's {@code d}, recovered from its signer since the store never exposes it.
     */
    private static Set<String> ephemeralSecrets(SigningKeyStore ephemeral) {
        ECPrivateKey key = (ECPrivateKey) ((ECDSASigner) ephemeral.signer()).getPrivateKey();
        ECKey published = ephemeral.verificationKeys().getFirst().toECKey();
        return forms(new ECKey.Builder(published).privateKey(key).build().getD());
    }

    private static Set<String> forms(Base64URL member) {
        BigInteger value = member.decodeToBigInteger();
        return Set.of(
                member.toString(),
                Base64.encode(member.decode()).toString(),
                value.toString(),
                value.toString(HEX));
    }

    private ListAppender<ILoggingEvent> attach() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger().addAppender(appender);
        reattach = new ReattachOnReset(appender);
        rootLogger().getLoggerContext().addListener(reattach);
        return appender;
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(RSA_KEY_SIZE).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Logback drops every appender when the context reconfigures it at startup; this restores ours.
     */
    private record ReattachOnReset(ListAppender<ILoggingEvent> appender)
            implements LoggerContextListener {

        @Override
        public boolean isResetResistant() {
            return true;
        }

        @Override
        public void onReset(LoggerContext context) {
            // The reset stopped it as well as detaching it, and a stopped appender drops events.
            appender.start();
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        }

        @Override
        public void onStart(LoggerContext context) {}

        @Override
        public void onStop(LoggerContext context) {}

        @Override
        public void onLevelChange(Logger logger, Level level) {}
    }

    /**
     * The configured store, whose signer can be broken so Nimbus itself refuses to sign: it is
     * handed a header naming an algorithm the configured key cannot sign with.
     */
    @Singleton
    @Replaces(SigningKeyStore.class)
    @Requires(property = BREAKABLE_STORE, value = "true")
    static class BreakableSigningKeyStore extends SigningKeyStore {

        /** An algorithm no RSA key signs with. */
        private static final JWSAlgorithm FOREIGN_ALGORITHM = JWSAlgorithm.ES256;

        volatile boolean broken;

        BreakableSigningKeyStore(ConsentManagerConfiguration.Receipts settings) {
            super(settings);
        }

        @Override
        @NonNull
        public JWSSigner signer() {
            JWSSigner real = super.signer();
            if (!broken) {
                return real;
            }
            return new JWSSigner() {
                @Override
                public Base64URL sign(JWSHeader header, byte[] signingInput) throws JOSEException {
                    return real.sign(new JWSHeader(FOREIGN_ALGORITHM), signingInput);
                }

                @Override
                public Set<JWSAlgorithm> supportedJWSAlgorithms() {
                    return real.supportedJWSAlgorithms();
                }

                @Override
                public JCAContext getJCAContext() {
                    return real.getJCAContext();
                }
            };
        }
    }
}
