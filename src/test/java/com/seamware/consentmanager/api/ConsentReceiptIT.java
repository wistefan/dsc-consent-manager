package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises {@code GET /consents/{id}?format=receipt|receipt-kantara} against real Keycloak and
 * PostgreSQL.
 *
 * <p>The defining case is stability: a receipt rendered before its notice is archived and
 * regenerated with different terms must read the same afterwards, because it is rendered from the
 * snapshot the consent froze rather than from the live notice.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent receipts")
class ConsentReceiptIT extends KeycloakAndPostgresTestResource {

    private static final String PATH_CONSENT = "/consents/{id}";

    private static final String PARAM_FORMAT = "format";

    private static final String FORMAT_ISO = "receipt";

    private static final String FORMAT_KANTARA = "receipt-kantara";

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    /** The participant the {@code PARTICIPANT} token carries; registered as the provider. */
    private static final String CALLER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String COUNTERPARTY = "urn:test:participant:receipt-consumer";

    private static final String CONTRACT_URI =
            "https://contract.example.org/v1/contracts/receipt-1";

    private static final String SUBJECT = "urn:test:user:receipt-subject";

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

    private PrivacyNotice notice;

    private User subject;

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
        subject = users.save(new User(SUBJECT, null, null, null));
    }

    /** Consents restrict deletion of their user and participants, so they go first. */
    @AfterEach
    void removeCommittedRows() {
        consents.deleteAll();
        notices.deleteAll();
        users.findByIdentifier(SUBJECT).ifPresent(users::delete);
        List.of(CALLER, COUNTERPARTY)
                .forEach(
                        party ->
                                participants
                                        .findByIdentifier(party)
                                        .ifPresent(participants::delete));
    }

    @Test
    void rendersEveryFrozenTermAsAnIso29184Record() {
        UUID id = seedConsent(ConsentStatus.GRANTED);

        Map<String, Object> receipt = body(read(id, FORMAT_ISO));

        assertThat(receipt)
                .containsEntry("standard", "ISO/IEC 29184:2020")
                .containsEntry("consentId", id.toString())
                .containsEntry("privacyNoticeId", notice.getId().toString())
                .containsEntry("piiPrincipalId", SUBJECT)
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

    @Test
    @SuppressWarnings("unchecked")
    void rendersAKantaraReceiptForAGrant() {
        UUID id = seedConsent(ConsentStatus.GRANTED);

        Map<String, Object> receipt = body(read(id, FORMAT_KANTARA));

        assertThat(receipt)
                .containsEntry("version", "KI-CR-v1.1.0")
                .containsEntry("jurisdiction", JURISDICTION)
                .containsEntry("consentReceiptID", id.toString())
                .containsEntry("piiPrincipalId", SUBJECT)
                .containsEntry("policyUrl", CONTRACT_URI)
                .containsKeys("consentTimestamp", "collectionMethod")
                .containsEntry(
                        "piiControllers",
                        List.of(
                                Map.of(
                                        "piiController", CONTROLLER_NAME,
                                        "contact", CONTROLLER_CONTACT,
                                        "piiControllerUrl", CONTROLLER_SD)));
        Map<String, Object> service =
                ((List<Map<String, Object>>) receipt.get("services")).getFirst();
        assertThat(service).containsEntry("service", TITLE);
        assertThat((List<Map<String, Object>>) service.get("purposes"))
                .singleElement()
                .isEqualTo(
                        Map.of(
                                "purpose",
                                PURPOSE,
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

    @ParameterizedTest
    @ValueSource(strings = {FORMAT_ISO, FORMAT_KANTARA})
    void readsTheSameAfterItsNoticeIsArchivedAndRegenerated(String format) {
        UUID id = seedConsent(ConsentStatus.GRANTED);
        Map<String, Object> before = body(read(id, format));

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

        assertThat(body(read(id, format))).isEqualTo(before);
    }

    /** Every status but {@code GRANTED}: the ones a Kantara receipt cannot attest. */
    static Stream<Arguments> ungranted() {
        return Arrays.stream(ConsentStatus.values())
                .filter(status -> status != ConsentStatus.GRANTED)
                .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("ungranted")
    void refusesAKantaraReceiptButRecordsAnUngrantedConsent(ConsentStatus status) {
        UUID id = seedConsent(status);

        assertThat(read(id, FORMAT_KANTARA).code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(body(read(id, FORMAT_ISO)))
                .containsEntry("status", status.name())
                .containsEntry("consented", false);
    }

    @Test
    void refusesAnUnknownFormat() {
        UUID id = seedConsent(ConsentStatus.GRANTED);

        assertThat(read(id, "receipt-v2").code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
    }

    private UUID seedConsent(ConsentStatus status) {
        return consents.save(
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

    /** Reads the consent as the provider, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> read(UUID id, String format) {
        HttpRequest<?> request =
                HttpRequest.GET(
                                UriBuilder.of(PATH_CONSENT)
                                        .queryParam(PARAM_FORMAT, format)
                                        .expand(Map.of("id", id.toString())))
                        .bearerAuth(KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
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
