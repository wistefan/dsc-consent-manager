package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import com.nimbusds.jose.jwk.JWKSet;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.security.ReceiptSigner;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import com.seamware.consentmanager.support.ReceiptSignatures;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises {@code GET /consents/me/{id}} and {@code GET /consents/{id}} with {@code
 * format=receipt|receipt-kantara} against real Keycloak and PostgreSQL.
 *
 * <p>The defining case is stability: a receipt rendered before its notice is archived and
 * regenerated with different terms must read the same afterwards, because it is rendered from the
 * snapshot the consent froze rather than from the live notice.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent receipts")
class ConsentReceiptIT extends KeycloakAndPostgresTestResource {

    private static final String PATH_MY_CONSENTS = "/consents/me";

    private static final String PARAM_FORMAT = "format";

    private static final String FORMAT_ISO = "receipt";

    private static final String FORMAT_KANTARA = "receipt-kantara";

    private static final String PATH_JWKS = "/.well-known/jwks.json";

    /** A data subject a forger swaps in for the one the receipt was signed for. */
    private static final String FORGED_SUBJECT = "urn:test:user:someone-else";

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    /** The participant the {@code PARTICIPANT} token carries; registered as the provider. */
    private static final String CALLER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String COUNTERPARTY = "urn:test:participant:receipt-consumer";

    private static final String CONTRACT_URI =
            "https://contract.example.org/v1/contracts/receipt-1";

    private static final String TITLE = "Fitness data for research";

    private static final String PURPOSE = "Clinical research";

    private static final String CATEGORY = "Heart rate";

    private static final String DATA_URI = "https://catalog.example.org/offerings/heart-rate";

    private static final String RECIPIENT_NAME = "Research Lab";

    private static final String CONTROLLER_NAME = "Fitness Provider Ltd";

    private static final String CONTROLLER_CONTACT = "dpo@provider.example.org";

    private static final String CONTROLLER_SD = "https://provider.example.org/sd.json";

    private static final String RETENTION = "Two years";

    private static final String RIGHTS = "Access, rectification, erasure";

    private static final String TRANSFER = "US";

    private static final String WITHDRAWAL = "Revoke through the Consent Manager";

    /** The configured default jurisdiction a Kantara receipt names. */
    private static final String JURISDICTION = "EU";

    /** When the seeded grant was given: well before the row is written, so the two differ. */
    private static final Instant GRANTED_AT = Instant.parse("2026-01-01T00:00:00Z");

    /** Terms the regenerated notice carries, all different from the frozen ones. */
    private static final String REGENERATED = "regenerated";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    private PrivacyNotice notice;

    private User subject;

    /** The two single-consent reads a receipt renders on, each with the token it admits. */
    enum Route {
        SUBJECT("/consents/me/{id}", RealmPrincipal.USER),
        PARTY("/consents/{id}", RealmPrincipal.PARTICIPANT);

        private final String path;
        private final RealmPrincipal caller;

        Route(String path, RealmPrincipal caller) {
            this.path = path;
            this.caller = caller;
        }
    }

    @BeforeEach
    void seedTheNoticeAndTheSubject() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        removeCommittedRows();
        notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                TITLE,
                                register(CALLER).getId(),
                                register(COUNTERPARTY).getId(),
                                terms()));
        String identifier = subjectIdentifier();
        subject =
                users.findByIdentifier(identifier)
                        .orElseGet(() -> users.save(new User(identifier, null, null, null)));
    }

    /** Consents restrict deletion of their user and participants, so they go first. */
    @AfterEach
    void removeCommittedRows() {
        consents.deleteAll();
        notices.deleteAll();
        users.findByIdentifier(subjectIdentifier()).ifPresent(users::delete);
        List.of(CALLER, COUNTERPARTY)
                .forEach(
                        party ->
                                participants
                                        .findByIdentifier(party)
                                        .ifPresent(participants::delete));
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void rendersEveryFrozenTermAsAnIso29184Record(Route route) {
        UUID id = seedConsent(ConsentStatus.GRANTED);

        Map<String, Object> receipt = body(read(route, id, FORMAT_ISO));

        assertThat(receipt)
                .containsEntry("standard", "ISO/IEC 29184:2020")
                .containsEntry("consentId", id.toString())
                .containsEntry("privacyNoticeId", notice.getId().toString())
                .containsEntry("piiPrincipalId", subject.getIdentifier())
                .containsEntry("status", ConsentStatus.GRANTED.name())
                .containsEntry("consented", true)
                .containsEntry("provider", CALLER)
                .containsEntry("consumer", COUNTERPARTY)
                .containsEntry("contractUri", CONTRACT_URI)
                .containsEntry("title", TITLE)
                .containsEntry("purposes", List.of(PURPOSE))
                .containsEntry("dataCategories", List.of(CATEGORY))
                .containsEntry("retentionPeriod", RETENTION)
                .containsEntry("piiPrincipalRights", RIGHTS)
                .containsEntry("internationalTransfers", List.of(TRANSFER))
                .containsEntry("withdrawalOfConsent", WITHDRAWAL)
                .containsEntry(
                        "controllerDetails",
                        Map.of(
                                "name", CONTROLLER_NAME,
                                "contact", CONTROLLER_CONTACT,
                                "selfDescriptionUri", CONTROLLER_SD))
                .containsEntry("dataReferences", List.of(Map.of("uri", DATA_URI)))
                .containsKeys("receiptId", "createdAt", "updatedAt");
        assertThat(receipt.get("recipients"))
                .asInstanceOf(LIST)
                .singleElement()
                .asInstanceOf(MAP)
                .containsEntry("name", RECIPIENT_NAME);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    @SuppressWarnings("unchecked")
    void rendersAKantaraReceiptForAGrant(Route route) {
        UUID id = seedConsent(ConsentStatus.GRANTED);

        Map<String, Object> receipt = body(read(route, id, FORMAT_KANTARA));

        assertThat(receipt)
                .containsEntry("version", "KI-CR-v1.1.0")
                .containsEntry("jurisdiction", JURISDICTION)
                .containsEntry("consentReceiptID", id.toString())
                .containsEntry("piiPrincipalId", subject.getIdentifier())
                .containsEntry("policyUrl", CONTRACT_URI)
                .containsEntry("sensitive", false)
                .containsEntry("spiCat", List.of())
                .containsKeys("consentTimestamp", "collectionMethod")
                .containsEntry(
                        "piiControllers",
                        List.of(
                                Map.of(
                                        "piiController",
                                        CONTROLLER_NAME,
                                        "contact",
                                        CONTROLLER_CONTACT,
                                        "address",
                                        Map.of(),
                                        "email",
                                        "",
                                        "phone",
                                        "",
                                        "piiControllerUrl",
                                        CONTROLLER_SD)));
        assertThat(((Number) receipt.get("consentTimestamp")).longValue())
                .isEqualTo(GRANTED_AT.getEpochSecond());
        Map<String, Object> service =
                ((List<Map<String, Object>>) receipt.get("services")).getFirst();
        assertThat(service).containsEntry("service", TITLE);
        assertThat((List<Map<String, Object>>) service.get("purposes"))
                .singleElement()
                .isEqualTo(
                        Map.of(
                                "purpose",
                                PURPOSE,
                                "purposeCategory",
                                List.of(),
                                "consentType",
                                "EXPLICIT",
                                "piiCategory",
                                List.of(CATEGORY),
                                "termination",
                                WITHDRAWAL,
                                "thirdPartyDisclosure",
                                true,
                                "thirdPartyName",
                                RECIPIENT_NAME));
    }

    /** Every route, in every receipt format. */
    static Stream<Arguments> routesAndFormats() {
        return Arrays.stream(Route.values())
                .flatMap(
                        route ->
                                Stream.of(FORMAT_ISO, FORMAT_KANTARA)
                                        .map(format -> Arguments.of(route, format)));
    }

    @ParameterizedTest
    @MethodSource("routesAndFormats")
    void readsTheSameAfterItsNoticeIsArchivedAndRegenerated(Route route, String format) {
        UUID id = seedConsent(ConsentStatus.GRANTED);
        Map<String, Object> before = signed(body(read(route, id, format)));

        notice.setArchivedAt(Instant.now());
        notice.setTitle(REGENERATED);
        notice.setPayload(regeneratedTerms());
        notices.update(notice);
        notices.save(
                new PrivacyNotice(
                        CONTRACT_URI,
                        REGENERATED,
                        notice.getProviderId(),
                        notice.getConsumerId(),
                        regeneratedTerms()));

        assertThat(signed(body(read(route, id, format)))).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("routesAndFormats")
    @DisplayName(
            "signs every receipt so the anonymously published JWKS verifies it, and not a forgery")
    void carriesASignatureThePublishedKeysVerify(Route route, String format) throws Exception {
        UUID id = seedConsent(ConsentStatus.GRANTED);
        String receipt = read(route, id, format).body();
        JWKSet published = JWKSet.parse(publishedKeys());

        assertThat(ReceiptSignatures.verifies(receipt, published)).isTrue();
        assertThat(receipt).contains(subjectIdentifier());
        assertThat(
                        ReceiptSignatures.verifies(
                                receipt.replace(subjectIdentifier(), FORGED_SUBJECT), published))
                .isFalse();
    }

    /** Every route, with every status but {@code GRANTED}: the ones Kantara cannot attest. */
    static Stream<Arguments> ungranted() {
        return Arrays.stream(Route.values())
                .flatMap(
                        route ->
                                Arrays.stream(ConsentStatus.values())
                                        .filter(status -> status != ConsentStatus.GRANTED)
                                        .map(status -> Arguments.of(route, status)));
    }

    @ParameterizedTest
    @MethodSource("ungranted")
    void refusesAKantaraReceiptButRecordsAnUngrantedConsent(Route route, ConsentStatus status) {
        UUID id = seedConsent(status);

        assertThat(read(route, id, FORMAT_KANTARA).code())
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(body(read(route, id, FORMAT_ISO)))
                .containsEntry("status", status.name())
                .containsEntry("consented", false);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void refusesAnUnknownFormat(Route route) {
        UUID id = seedConsent(ConsentStatus.GRANTED);

        assertThat(read(route, id, "receipt-v2").code())
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
    }

    /** A listing renders {@code full} alone, so asking it for a receipt is refused, not ignored. */
    @ParameterizedTest
    @ValueSource(strings = {FORMAT_ISO, FORMAT_KANTARA})
    void refusesAReceiptListing(String format) {
        seedConsent(ConsentStatus.GRANTED);

        assertThat(exchange(PATH_MY_CONSENTS, format, RealmPrincipal.USER).code())
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
    }

    /** Seeds a consent in {@code status}; a grant is recorded as given at {@link #GRANTED_AT}. */
    private UUID seedConsent(ConsentStatus status) {
        UUID id =
                consents.save(
                                Consent.withStatus(
                                        status,
                                        subject.getId(),
                                        notice.getId(),
                                        notice.getProviderId(),
                                        notice.getConsumerId(),
                                        null,
                                        notice.getContractUri(),
                                        ConsentSnapshot.of(notice, null, null)))
                        .getId();
        if (status == ConsentStatus.GRANTED) {
            events.save(
                    new ConsentEvent(
                            id,
                            ConsentEventState.CONSENT_GIVEN,
                            ConsentEventTrigger.EXPLICIT.eventType(),
                            subject.getIdentifier(),
                            GRANTED_AT,
                            null));
        }
        return id;
    }

    private static PrivacyNoticePayload terms() {
        return new PrivacyNoticePayload(
                List.of(PURPOSE),
                List.of(CATEGORY),
                List.of(PrivacyNoticePayload.DataReference.offering(DATA_URI)),
                List.of(new PrivacyNoticePayload.Recipient(null, RECIPIENT_NAME, null, null)),
                new PrivacyNoticePayload.ControllerDetails(
                        CONTROLLER_NAME, CONTROLLER_CONTACT, null, CONTROLLER_SD),
                RETENTION,
                RIGHTS,
                List.of(TRANSFER),
                WITHDRAWAL);
    }

    private static PrivacyNoticePayload regeneratedTerms() {
        return new PrivacyNoticePayload(
                List.of(REGENERATED),
                List.of(REGENERATED),
                List.of(PrivacyNoticeFixtures.reference(REGENERATED)),
                List.of(PrivacyNoticeFixtures.recipient(REGENERATED)),
                new PrivacyNoticePayload.ControllerDetails(REGENERATED, null, null, null),
                REGENERATED,
                REGENERATED,
                List.of(REGENERATED),
                REGENERATED);
    }

    private Participant register(String identifier) {
        return participants
                .findByIdentifier(identifier)
                .orElseGet(
                        () ->
                                participants.save(
                                        new Participant(
                                                identifier,
                                                identifier,
                                                null,
                                                null,
                                                Map.of(),
                                                null)));
    }

    /** The data subject the {@code USER} token carries, so both routes reach the same consent. */
    private static String subjectIdentifier() {
        return KeycloakTestResource.subject(KeycloakTestResource.accessToken(RealmPrincipal.USER));
    }

    /** Reads the consent through {@code route}, as the caller that route admits. */
    private HttpResponse<String> read(Route route, UUID id, String format) {
        return exchange(
                UriBuilder.of(route.path).expand(Map.of("id", id.toString())).toString(),
                format,
                route.caller);
    }

    /** Sends a {@code GET}, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(String path, String format, RealmPrincipal caller) {
        HttpRequest<?> request =
                HttpRequest.GET(UriBuilder.of(path).queryParam(PARAM_FORMAT, format).build())
                        .bearerAuth(KeycloakTestResource.accessToken(caller));
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    /**
     * The members {@code receipt}'s signature covers. The signature itself is left out because an
     * ECDSA signature is randomized, so two signings of the same content differ.
     */
    private static Map<String, Object> signed(Map<String, Object> receipt) {
        Map<String, Object> members = new HashMap<>(receipt);
        members.remove(ReceiptSigner.SIGNATURE_MEMBER);
        return members;
    }

    /** The receipt JWKS, fetched with no credential at all. */
    private String publishedKeys() {
        HttpResponse<String> response =
                client.toBlocking().exchange(HttpRequest.GET(PATH_JWKS), String.class);
        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        return response.body();
    }

    private Map<String, Object> body(HttpResponse<String> response) {
        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        try {
            return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the response body", e);
        }
    }
}
