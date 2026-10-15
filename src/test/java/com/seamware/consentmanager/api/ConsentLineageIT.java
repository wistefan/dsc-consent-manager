package com.seamware.consentmanager.api;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
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
import io.micronaut.context.annotation.Property;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises {@code includeLineage} on {@code GET /consents/me}, {@code /consents/me/{id}} and
 * {@code /consents/{id}} against real Keycloak and PostgreSQL.
 *
 * <p>The depth is configured to {@link #MAX_DEPTH} so a chain one record longer proves the cut, and
 * the chains are seeded straight into the repository so a cycle - which no API path produces - can
 * be built to prove the walk ends.
 */
@MicronautTest(transactional = false)
@Property(name = "consent-manager.consents.lineage-max-depth", value = ConsentLineageIT.MAX_DEPTH)
@DisplayName("Consent lineage")
class ConsentLineageIT extends KeycloakAndPostgresTestResource {

    /** The configured depth; small, so a chain exceeding it stays cheap to seed. */
    static final String MAX_DEPTH = "3";

    private static final int DEPTH = Integer.parseInt(MAX_DEPTH);

    private static final String PATH_MY_CONSENTS = "/consents/me";

    private static final String PARAM_INCLUDE_LINEAGE = "includeLineage";

    private static final String PARAM_FORMAT = "format";

    private static final String LINEAGE = "lineage";

    private static final String CONTENT = "content";

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    /** The participant the {@code PARTICIPANT} token carries; registered as the provider. */
    private static final String CALLER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String COUNTERPARTY = "urn:test:participant:lineage-consumer";

    /** A participant the caller shares no consent with, so a hop can leave the caller's scope. */
    private static final String STRANGER = "urn:test:participant:lineage-stranger";

    /** A data subject other than the {@code USER} token's, so a hop can leave its scope. */
    private static final String OTHER_SUBJECT = "urn:test:user:lineage-other";

    private static final String CONTRACT_URI = "urn:test:contract:lineage";

    private static final String TITLE = "Lineage notice";

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

    /** The two single-consent reads lineage renders on, each with the token it admits. */
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
        notice = notice(register(CALLER), register(COUNTERPARTY));
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
        List.of(subjectIdentifier(), OTHER_SUBJECT)
                .forEach(identifier -> users.findByIdentifier(identifier).ifPresent(users::delete));
        List.of(CALLER, COUNTERPARTY, STRANGER)
                .forEach(
                        party ->
                                participants
                                        .findByIdentifier(party)
                                        .ifPresent(participants::delete));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Route.class)
    @DisplayName("renders the superseded records newest first")
    void rendersTheChainNewestFirst(Route route) {
        List<UUID> chain = chain(subject, notice, DEPTH);

        List<Map<String, Object>> lineage = lineage(read(route, chain.getLast()));

        assertThat(lineage)
                .extracting(link -> link.get("id"))
                .containsExactly(chain.get(1).toString(), chain.get(0).toString());
        assertThat(lineage.getLast()).doesNotContainKey("parentConsentId");
        assertThat(lineage.getFirst()).containsEntry("parentConsentId", chain.get(0).toString());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Route.class)
    @DisplayName("renders an empty lineage for a chain's first record")
    void rendersAnEmptyLineageForTheFirstRecord(Route route) {
        UUID first = chain(subject, notice, 1).getFirst();

        assertThat(lineage(read(route, first))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Route.class)
    @DisplayName("renders no lineage unless asked")
    void rendersNoLineageUnlessAsked(Route route) {
        List<UUID> chain = chain(subject, notice, DEPTH);

        Map<String, Object> body =
                body(exchange(path(route, chain.getLast()), Map.of(), route.caller));

        assertThat(body).doesNotContainKey(LINEAGE);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Route.class)
    @DisplayName("stops at the configured depth")
    void stopsAtTheConfiguredDepth(Route route) {
        List<UUID> chain = chain(subject, notice, DEPTH + 2);

        List<Map<String, Object>> lineage = lineage(read(route, chain.getLast()));

        assertThat(lineage)
                .extracting(link -> link.get("id"))
                .containsExactlyElementsOf(
                        chain.reversed().subList(1, DEPTH + 1).stream()
                                .map(UUID::toString)
                                .toList());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Route.class)
    @DisplayName("ends on a cycle across rows without repeating a link")
    void endsOnACycle(Route route) {
        List<UUID> chain = chain(subject, notice, 2);
        Consent first = consents.findById(chain.getFirst()).orElseThrow();
        first.setParentConsentId(chain.getLast());
        consents.update(first);

        List<Map<String, Object>> lineage = lineage(read(route, chain.getLast()));

        assertThat(lineage)
                .extracting(link -> link.get("id"))
                .containsExactly(chain.getFirst().toString());
    }

    @Test
    @DisplayName("a subject's chain stops at a record another subject owns")
    void subjectChainStopsOutsideItsScope() {
        User other = users.save(new User(OTHER_SUBJECT, null, null, null));
        UUID foreign = chain(other, notice, 1).getFirst();
        UUID own = save(subject, notice, foreign, ConsentStatus.REVOKED);
        UUID latest = save(subject, notice, own, ConsentStatus.GRANTED);

        assertThat(lineage(read(Route.SUBJECT, latest)))
                .extracting(link -> link.get("id"))
                .containsExactly(own.toString());
    }

    @Test
    @DisplayName("a participant's chain stops at a record it is no party to")
    void participantChainStopsOutsideItsScope() {
        PrivacyNotice foreignNotice = notice(register(STRANGER), register(COUNTERPARTY));
        UUID foreign = chain(subject, foreignNotice, 1).getFirst();
        UUID own = save(subject, notice, foreign, ConsentStatus.REVOKED);
        UUID latest = save(subject, notice, own, ConsentStatus.GRANTED);

        assertThat(lineage(read(Route.PARTY, latest)))
                .extracting(link -> link.get("id"))
                .containsExactly(own.toString());
    }

    @Test
    @DisplayName("the subject's listing renders each record's lineage")
    void listingRendersEachLineage() {
        List<UUID> chain = chain(subject, notice, 2);

        HttpResponse<String> response =
                exchange(
                        PATH_MY_CONSENTS,
                        Map.of(PARAM_INCLUDE_LINEAGE, "true", "status", "GRANTED"),
                        RealmPrincipal.USER);

        List<Map<String, Object>> content = maps(body(response).get(CONTENT));
        assertThat(content)
                .singleElement()
                .satisfies(
                        entry ->
                                assertThat(maps(entry.get(LINEAGE)))
                                        .extracting(link -> link.get("id"))
                                        .containsExactly(chain.getFirst().toString()));
    }

    @ParameterizedTest(name = "{0} refused on every route")
    @ValueSource(strings = {"receipt", "receipt-kantara"})
    @DisplayName("refuses a receipt asked for with its lineage")
    void refusesAReceiptWithLineage(String format) {
        UUID granted = chain(subject, notice, 1).getFirst();

        for (Route route : Route.values()) {
            HttpResponse<String> response =
                    exchange(
                            path(route, granted),
                            Map.of(PARAM_INCLUDE_LINEAGE, "true", PARAM_FORMAT, format),
                            route.caller);
            assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        }
    }

    /**
     * A chain of {@code length} records, oldest first, each superseding the one before; only the
     * newest is {@code GRANTED}, as one grant per subject and notice requires.
     */
    private List<UUID> chain(User owner, PrivacyNotice under, int length) {
        List<UUID> chain = new ArrayList<>();
        IntStream.range(0, length)
                .forEach(
                        i ->
                                chain.add(
                                        save(
                                                owner,
                                                under,
                                                chain.isEmpty() ? null : chain.getLast(),
                                                i == length - 1
                                                        ? ConsentStatus.GRANTED
                                                        : ConsentStatus.REVOKED)));
        return chain;
    }

    private UUID save(User owner, PrivacyNotice under, UUID parentId, ConsentStatus status) {
        return consents.save(
                        Consent.withStatus(
                                status,
                                owner.getId(),
                                under.getId(),
                                under.getProviderId(),
                                under.getConsumerId(),
                                parentId,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }

    private PrivacyNotice notice(Participant provider, Participant consumer) {
        return notices.save(
                new PrivacyNotice(
                        CONTRACT_URI + ":" + UUID.randomUUID(),
                        TITLE,
                        provider.getId(),
                        consumer.getId(),
                        new PrivacyNoticePayload(
                                null, null, null, null, null, null, null, null, null)));
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

    /** Reads the consent through {@code route} with its lineage, as the caller it admits. */
    private HttpResponse<String> read(Route route, UUID id) {
        return exchange(path(route, id), Map.of(PARAM_INCLUDE_LINEAGE, "true"), route.caller);
    }

    private static String path(Route route, UUID id) {
        return UriBuilder.of(route.path).expand(Map.of("id", id.toString())).toString();
    }

    /** Sends a {@code GET}, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(
            String path, Map<String, String> query, RealmPrincipal caller) {
        UriBuilder uri = UriBuilder.of(path);
        query.forEach(uri::queryParam);
        HttpRequest<?> request =
                HttpRequest.GET(uri.build()).bearerAuth(KeycloakTestResource.accessToken(caller));
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    private List<Map<String, Object>> lineage(HttpResponse<String> response) {
        Map<String, Object> body = body(response);
        assertThat(body).containsKey(LINEAGE);
        return maps(body.get(LINEAGE));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
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
