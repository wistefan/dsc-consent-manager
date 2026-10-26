package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import com.seamware.consentmanager.support.PrivacyNoticeApiTestSupport;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Exercises {@code GET /contracts/available} and {@code GET /contracts/available/{role}} against a
 * real Keycloak, a real PostgreSQL and a stubbed Contract Service.
 *
 * <p>Each case serves its contracts under ids of its own: the gateway caches what it fetched, so a
 * shared id would let one case's contract answer another's.
 */
@MicronautTest(transactional = false)
class ContractDiscoveryIT extends PrivacyNoticeApiTestSupport {

    private static final String PATH_USER = "/contracts/available";

    private static final String PATH_PROVIDER = PATH_USER + "/provider";

    private static final String PATH_CONSUMER = PATH_USER + "/consumer";

    private static final String NOTICE_TITLE = "Discovery notice";

    private static final String LOCAL_IDENTIFIER = "discovery-local";

    /** Status an upstream failure is stubbed with; any 5xx the retry budget does not survive. */
    private static final int UPSTREAM_FAILURE = 503;

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject UserRepository users;

    @Inject UserParticipantRepository links;

    @Inject IdentityProviderRegistry registry;

    /** The realm user's global identifier, which is what JIT provisioning keys its row by. */
    private String subject;

    @BeforeEach
    void awaitTheRealm() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        subject =
                KeycloakTestResource.subject(KeycloakTestResource.accessToken(RealmPrincipal.USER));
    }

    @AfterEach
    void removeTheSubject() {
        users.findByIdentifier(subject)
                .ifPresent(
                        user -> {
                            links.findByIdUserId(user.getId()).forEach(links::delete);
                            users.delete(user);
                        });
    }

    @Test
    @DisplayName("a contract two of the subject's participants share is listed once, both sides")
    void subjectSeesEachContractOnceWithEverySide() {
        String shared = serveEcosystem("discovery-a-shared");
        String bilateral = serveBilateralConsumedByCaller("discovery-b-bilateral");
        String unrelated = serveEcosystem("discovery-c-unrelated");
        seed(shared, NOTICE_TITLE, CALLER, CONSUMER, payload());
        seed(bilateral, NOTICE_TITLE, FIXTURE_PROVIDER, CALLER, payload());
        seed(unrelated, NOTICE_TITLE, OTHER_CONSUMER, null, payload());
        linkSubjectTo(CALLER, CONSUMER);

        HttpResponse<String> response = exchange(PATH_USER, RealmPrincipal.USER);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        List<Map<String, Object>> listed = list(response);
        assertThat(listed).extracting("contractUri").containsExactly(shared, bilateral);
        assertThat(listed.get(0))
                .containsEntry("shape", "ECOSYSTEM")
                .containsEntry("roles", List.of("PROVIDER", "CONSUMER"))
                .doesNotContainKeys("dataProvider", "dataConsumer");
        assertThat(listed.get(1))
                .containsEntry("shape", "BILATERAL")
                .containsEntry("roles", List.of("CONSUMER"))
                .containsEntry("dataProvider", FIXTURE_PROVIDER)
                .containsEntry("dataConsumer", CALLER)
                .containsEntry("status", "signed")
                .doesNotContainKey("ecosystem");
        assertThat(contracts.ecosystemRequestCount(contractId(unrelated)))
                .as("a contract none of the subject's participants is party to is never read")
                .isZero();
    }

    @Test
    @DisplayName("a subject linked to no participant sees an empty list")
    void unlinkedSubjectSeesNothing() {
        seed(serveEcosystem("discovery-unlinked"), NOTICE_TITLE, CALLER, CONSUMER, payload());

        HttpResponse<String> response = exchange(PATH_USER, RealmPrincipal.USER);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(list(response)).isEmpty();
    }

    static Stream<Arguments> sides() {
        return Stream.of(
                Arguments.of(PATH_PROVIDER, "provider", "discovery-side-p"),
                Arguments.of(PATH_CONSUMER, "consumer", "discovery-side-c"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("sides")
    @DisplayName("a participant sees the contracts it takes the requested side in, marked so")
    void participantSeesOneSide(String path, String role, String prefix) {
        String provided = serveEcosystem(prefix + "-provided");
        String consumed = serveBilateralConsumedByCaller(prefix + "-consumed");
        seed(provided, NOTICE_TITLE, CALLER, CONSUMER, payload());
        seed(consumed, NOTICE_TITLE, FIXTURE_PROVIDER, CALLER, payload());
        String expected = "provider".equals(role) ? provided : consumed;

        HttpResponse<String> response = get(path);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(list(response))
                .singleElement()
                .satisfies(
                        contract ->
                                assertThat(contract)
                                        .containsEntry("contractUri", expected)
                                        .containsEntry(
                                                "roles", List.of(role.toUpperCase(Locale.ROOT))));
    }

    @Test
    @DisplayName("an archived notice no longer makes its contract available")
    void archivedNoticeIsNoCandidate() {
        String contract = serveEcosystem("discovery-archived");
        PrivacyNotice notice = seed(contract, NOTICE_TITLE, CALLER, CONSUMER, payload());
        notice.setArchivedAt(Instant.now());
        notices.update(notice);

        assertThat(list(get(PATH_PROVIDER))).isEmpty();
    }

    @Test
    @DisplayName("a contract the Contract Service no longer knows is left out")
    void unknownContractIsLeftOut() {
        String known = serveEcosystem("discovery-known");
        String gone = uri("discovery-gone");
        contracts.serveUnknown("discovery-gone");
        seed(known, NOTICE_TITLE, CALLER, CONSUMER, payload());
        seed(gone, NOTICE_TITLE, CALLER, CONSUMER, payload());

        HttpResponse<String> response = get(PATH_PROVIDER);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(list(response)).extracting("contractUri").containsExactly(known);
    }

    static Stream<Arguments> unconfirmedSides() {
        return Stream.of(
                Arguments.of(PATH_PROVIDER, "discovery-unconfirmed-p", CALLER, CONSUMER),
                Arguments.of(PATH_CONSUMER, "discovery-unconfirmed-c", FIXTURE_PROVIDER, CALLER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unconfirmedSides")
    @DisplayName("a contract whose document no longer names the caller on the side is left out")
    void sideTheDocumentNoLongerConfirmsIsLeftOut(
            String path, String prefix, String provider, String consumer) {
        String confirmed = serveEcosystem(prefix + "-a-confirmed");
        String unconfirmed =
                serveRawBilateral(
                        prefix + "-b-unconfirmed",
                        ContractFixtures.body(ContractFixtures.BILATERAL));
        seed(confirmed, NOTICE_TITLE, CALLER, CONSUMER, payload());
        seed(unconfirmed, NOTICE_TITLE, provider, consumer, payload());

        HttpResponse<String> response = get(path);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(list(response))
                .extracting("contractUri")
                .as("the notice still claims the side, but the document names someone else")
                .doesNotContain(unconfirmed);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"garbage", "Provider", "PROVIDER"})
    @DisplayName("a role other than provider or consumer is refused with 400")
    void unknownRoleIsRefused(String role) {
        HttpResponse<String> response = get(PATH_USER + "/" + role);

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(response.getContentType())
                .hasValueSatisfying(type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
    }

    static Stream<Arguments> upstreamFailures() {
        return Stream.of(
                Arguments.of(PATH_USER, RealmPrincipal.USER, "discovery-down-user"),
                Arguments.of(PATH_PROVIDER, RealmPrincipal.PARTICIPANT, "discovery-down-role"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("upstreamFailures")
    @DisplayName("an unavailable Contract Service is 502 with no partial list")
    void upstreamFailureIsBadGatewayWithoutAList(
            String path, RealmPrincipal caller, String prefix) {
        // Ordered so the served contract is read before the failing one.
        String served = serveEcosystem(prefix + "-a-served");
        String failing = uri(prefix + "-z-failing");
        contracts.serveStatus(contractId(failing), UPSTREAM_FAILURE);
        seed(served, NOTICE_TITLE, CALLER, CONSUMER, payload());
        seed(failing, NOTICE_TITLE, CALLER, CONSUMER, payload());
        linkSubjectTo(CALLER);

        HttpResponse<String> response = exchange(path, caller);

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_GATEWAY.getCode());
        assertThat(response.getContentType())
                .hasValueSatisfying(type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
        assertThat(response.body())
                .as("the body is a problem detail, never the contracts read before the failure")
                .doesNotContain(served)
                .startsWith("{");
    }

    /** Serves the bilateral fixture with the caller standing in as its consumer. */
    private String serveBilateralConsumedByCaller(String contractId) {
        return serveRawBilateral(
                contractId,
                ContractFixtures.body(ContractFixtures.BILATERAL).replace(CONSUMER, CALLER));
    }

    /** Serves a document on the bilateral endpoint alone, returning the URI it is held under. */
    private String serveRawBilateral(String contractId, String document) {
        contracts.serveBilateralContract(contractId, document);
        return uri(contractId);
    }

    private static PrivacyNoticePayload payload() {
        return PrivacyNoticeFixtures.emptyPayload();
    }

    private static String contractId(String contractUri) {
        return contractUri.substring(CONTRACT_URI_PREFIX.length());
    }

    /** Links the realm user to each participant, provisioning the user row first if needed. */
    private void linkSubjectTo(String... identifiers) {
        User user =
                users.findByIdentifier(subject)
                        .orElseGet(() -> users.save(new User(subject, null, null, null)));
        Stream.of(identifiers)
                .forEach(
                        identifier ->
                                links.save(
                                        new UserParticipant(
                                                user.getId(),
                                                participant(identifier).getId(),
                                                LOCAL_IDENTIFIER)));
    }

    private HttpResponse<String> exchange(String path, RealmPrincipal caller) {
        HttpRequest<?> request =
                HttpRequest.GET(path).bearerAuth(KeycloakTestResource.accessToken(caller));
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> refusal = (HttpResponse<String>) e.getResponse();
            return refusal;
        }
    }

    private List<Map<String, Object>> list(HttpResponse<String> response) {
        try {
            return json.readValue(
                    response.body(), Argument.listOf(Argument.mapOf(String.class, Object.class)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the response body", e);
        }
    }
}
