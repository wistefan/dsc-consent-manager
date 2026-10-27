package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
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
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code GET /consents/check} end to end: guards a wrong answer, a leak of grants the caller is not
 * party to or of whether a subject exists, a deregistered caller being served, and caching.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent validity check endpoint")
class ConsentValidityIT extends KeycloakAndPostgresTestResource {

    private static final String PATH_CHECK = "/consents/check";

    private static final String PARAM_USER = "userIdentifier";

    private static final String PARAM_NOTICE = "privacyNoticeId";

    private static final String PARAM_PURPOSE = "purpose";

    /** Longest a cold Keycloak may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    /** The {@code PARTICIPANT} token's participant, registered as the notice's provider. */
    private static final String CALLER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String COUNTERPARTY = "urn:test:participant:check-consumer";

    /** Two participants the caller is neither of, for the grant it must not be able to read. */
    private static final String OUTSIDER_PROVIDER = "urn:test:participant:check-outsider-provider";

    private static final String OUTSIDER_CONSUMER = "urn:test:participant:check-outsider-consumer";

    private static final String CONTRACT_URI = "https://contract.example.org/v1/contracts/check-1";

    /** The contract of the notice the caller consumes under, for the consumer-side cases. */
    private static final String CONSUMED_CONTRACT_URI =
            "https://contract.example.org/v1/contracts/check-consumed";

    /** A second contract, because the active-notice index is unique per (contract, provider). */
    private static final String OUTSIDER_CONTRACT_URI =
            "https://contract.example.org/v1/contracts/check-2";

    private static final String NOTICE_TITLE = "Privacy notice the validity check consults";

    /** A subject that exists, holding whatever grant each case seeds. */
    private static final String SUBJECT = "urn:test:user:check-subject";

    /** An identifier no user holds, to prove it is indistinguishable from a bare subject. */
    private static final String UNKNOWN_SUBJECT = "urn:test:user:check-nobody";

    /** A purpose the seeded grant's frozen scope names. */
    private static final String COVERED_PURPOSE = "Produce aggregated mobility insights.";

    /** A purpose no seeded grant names, so a check against it is never valid. */
    private static final String UNCOVERED_PURPOSE = "Resell to third parties.";

    private static final String PROPERTY_VALID = "valid";

    private static final String PROPERTY_CONSENT_ID = "consentId";

    private static final String PROPERTY_STATUS = "status";

    private static final String PROPERTY_PURPOSES = "purposes";

    private static final String PROPERTY_GRANTED_AT = "grantedAt";

    private static final String PROPERTY_CHECKED_AT = "checkedAt";

    private static final String PROPERTY_REASON = "reason";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    /** The notice the caller is a party to, re-seeded per case so none inherits a consent. */
    private PrivacyNotice notice;

    /** The subject's row, re-seeded per case so a committed grant never outlives it. */
    private User subject;

    @BeforeEach
    void seedTheCallersNoticeAndTheSubject() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        removeCommittedRows();
        notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                NOTICE_TITLE,
                                register(CALLER).getId(),
                                register(COUNTERPARTY).getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        subject = users.save(new User(SUBJECT, null, null, null));
    }

    /** Removes committed rows; consents go first because they restrict their user and parties. */
    @AfterEach
    void removeCommittedRows() {
        consents.deleteAll();
        notices.deleteAll();
        users.findByIdentifier(SUBJECT).ifPresent(users::delete);
        List.of(CALLER, COUNTERPARTY, OUTSIDER_PROVIDER, OUTSIDER_CONSUMER)
                .forEach(
                        party ->
                                participants
                                        .findByIdentifier(party)
                                        .ifPresent(participants::delete));
    }

    /** The side of a notice the caller is party to; both halves of the scoping predicate. */
    enum CallerSide {
        PROVIDER,
        CONSUMER
    }

    /** Each caller side crossed with an omitted or a covered purpose. */
    static Stream<Arguments> coveredPurposes() {
        return Arrays.stream(CallerSide.values())
                .flatMap(
                        side ->
                                Stream.of(
                                        Arguments.of(side, "an omitted purpose", null),
                                        Arguments.of(
                                                side,
                                                "a purpose the grant covers",
                                                COVERED_PURPOSE)));
    }

    @ParameterizedTest(name = "caller as {0}, {1}")
    @MethodSource("coveredPurposes")
    @DisplayName("answers a grant the caller is party to with valid:true and the grant's terms")
    void answersAGrantAsValid(CallerSide side, String label, String purpose) {
        PrivacyNotice against = side == CallerSide.PROVIDER ? notice : consumedNotice();
        UUID consentId = seedConsent(against, ConsentStatus.GRANTED, grantedScope());

        HttpResponse<String> response = check(SUBJECT, against.getId(), purpose);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .containsEntry(PROPERTY_VALID, true)
                .containsEntry(PROPERTY_CONSENT_ID, consentId.toString())
                .containsEntry(PROPERTY_STATUS, ConsentStatus.GRANTED.name())
                .containsEntry(PROPERTY_PURPOSES, List.of(COVERED_PURPOSE))
                .containsKey(PROPERTY_GRANTED_AT)
                .containsKey(PROPERTY_CHECKED_AT)
                .as("a true answer states nothing is wrong, so it carries no reason")
                .doesNotContainKey(PROPERTY_REASON);
    }

    @ParameterizedTest(name = "a {0} consent")
    @EnumSource(
            value = ConsentStatus.class,
            names = {"PENDING", "DRAFT", "REVOKED", "EXPIRED", "TERMINATED", "REFUSED"})
    @DisplayName("answers a consent in any status but GRANTED with valid:false and a reason")
    void answersANonGrantAsInvalid(ConsentStatus status) {
        seedConsent(notice, status, grantedScope());

        HttpResponse<String> response = check(SUBJECT, notice.getId(), null);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .as("only a grant is in force, so any other status is as absent as none")
                .containsEntry(PROPERTY_VALID, false)
                .containsKey(PROPERTY_REASON)
                .doesNotContainKey(PROPERTY_CONSENT_ID)
                .doesNotContainKey(PROPERTY_STATUS);
    }

    @Test
    @DisplayName("answers a grant that does not cover the named purpose with valid:false")
    void answersAnUncoveredPurposeAsInvalid() {
        seedConsent(notice, ConsentStatus.GRANTED, grantedScope());

        HttpResponse<String> response = check(SUBJECT, notice.getId(), UNCOVERED_PURPOSE);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .as("a grant that exists but does not reach the purpose is not a yes for it")
                .containsEntry(PROPERTY_VALID, false)
                .containsKey(PROPERTY_REASON);
    }

    @Test
    @DisplayName("cannot read a grant naming another participant, answering as if none existed")
    void cannotReadAnotherParticipantsGrant() {
        PrivacyNotice outsiders =
                notices.save(
                        new PrivacyNotice(
                                OUTSIDER_CONTRACT_URI,
                                NOTICE_TITLE,
                                register(OUTSIDER_PROVIDER).getId(),
                                register(OUTSIDER_CONSUMER).getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        seedConsent(outsiders, ConsentStatus.GRANTED, grantedScope());

        HttpResponse<String> response = check(SUBJECT, outsiders.getId(), null);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .as("a grant the caller is no party to is never read, so it is not valid for it")
                .containsEntry(PROPERTY_VALID, false)
                .doesNotContainKey(PROPERTY_CONSENT_ID);
        assertThat(consents.existsByUserId(subject.getId()))
                .as("the outsider's grant is untouched by the probe")
                .isTrue();
    }

    @Test
    @DisplayName("gives an unknown subject the same answer as a known subject with no grant")
    void unknownSubjectIsIndistinguishableFromOneWithNoGrant() {
        Map<String, Object> knownButBare = withoutClock(body(check(SUBJECT, notice.getId(), null)));
        Map<String, Object> unknown =
                withoutClock(body(check(UNKNOWN_SUBJECT, notice.getId(), null)));

        assertThat(unknown)
                .as("the shape and reason must not reveal whether the subject exists")
                .isEqualTo(knownButBare);
        assertThat(knownButBare).containsEntry(PROPERTY_VALID, false).containsKey(PROPERTY_REASON);
    }

    /** A retained, deregistered caller, which the role matrix (unregistered only) misses. */
    @Test
    @DisplayName("refuses a deregistered participant with 403 rather than answering valid:true")
    void refusesADeregisteredParticipant() {
        seedConsent(notice, ConsentStatus.GRANTED, grantedScope());
        Participant caller = participants.findByIdentifier(CALLER).orElseThrow();
        caller.setDeregisteredAt(Instant.now());
        participants.update(caller);

        assertThat(check(SUBJECT, notice.getId(), null).code())
                .as("a departed participant is not licensed to process on a retained grant")
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
    }

    @Test
    @DisplayName("forbids every cached copy of the answer, since a stale yes is the worst answer")
    void neverCachesTheAnswer() {
        seedConsent(notice, ConsentStatus.GRANTED, grantedScope());

        HttpResponse<String> response = check(SUBJECT, notice.getId(), null);

        assertThat(response.getHeaders().get(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    @DisplayName("refuses an unauthenticated request with 401")
    void refusesAnUnauthenticatedRequest() {
        assertThat(exchange(HttpRequest.GET(uri(SUBJECT, notice.getId(), null)), null).code())
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
    }

    /** A notice naming the caller as its consumer, so the consumer half of the scope is read. */
    private PrivacyNotice consumedNotice() {
        return notices.save(
                new PrivacyNotice(
                        CONSUMED_CONTRACT_URI,
                        NOTICE_TITLE,
                        register(COUNTERPARTY).getId(),
                        register(CALLER).getId(),
                        PrivacyNoticeFixtures.emptyPayload()));
    }

    /** The frozen scope every valid-answer case seeds: one purpose a covered check names. */
    private ConsentSnapshot grantedScope() {
        return new ConsentSnapshot(
                null, List.of(COVERED_PURPOSE), null, null, null, null, null, null, null, null);
    }

    /** Writes a consent for the seeded subject straight to the table. */
    private UUID seedConsent(
            PrivacyNotice against, ConsentStatus status, ConsentSnapshot snapshot) {
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                against.getId(),
                                against.getProviderId(),
                                against.getConsumerId(),
                                null,
                                against.getContractUri(),
                                snapshot))
                .getId();
    }

    private Participant register(String participantIdentifier) {
        return participants
                .findByIdentifier(participantIdentifier)
                .orElseGet(
                        () ->
                                participants.save(
                                        new Participant(
                                                participantIdentifier,
                                                participantIdentifier,
                                                null,
                                                null,
                                                Map.of(),
                                                null)));
    }

    /** The body with its {@code checkedAt} dropped, so two answers compare on everything else. */
    private Map<String, Object> withoutClock(Map<String, Object> body) {
        Map<String, Object> copy = new HashMap<>(body);
        copy.remove(PROPERTY_CHECKED_AT);
        return copy;
    }

    private HttpResponse<String> check(String userIdentifier, UUID noticeId, String purpose) {
        return exchange(
                HttpRequest.GET(uri(userIdentifier, noticeId, purpose)), participantToken());
    }

    private URI uri(String userIdentifier, UUID noticeId, String purpose) {
        UriBuilder builder =
                UriBuilder.of(PATH_CHECK)
                        .queryParam(PARAM_USER, userIdentifier)
                        .queryParam(PARAM_NOTICE, noticeId.toString());
        if (purpose != null) {
            builder.queryParam(PARAM_PURPOSE, purpose);
        }
        return builder.build();
    }

    private static String participantToken() {
        return KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT);
    }

    private Map<String, Object> body(HttpResponse<String> response) {
        try {
            return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the response body", e);
        }
    }

    /** Issues the request, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request, String token) {
        MutableHttpRequest<?> sent = token == null ? request : request.bearerAuth(token);
        try {
            return client.toBlocking().exchange(sent, String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }
}
