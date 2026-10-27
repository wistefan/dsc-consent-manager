package com.seamware.consentmanager.api;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.IssuedReceiptRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.signing.ConsentSigningService;
import com.seamware.consentmanager.signing.JwsConsentSigningService;
import com.seamware.consentmanager.signing.ReceiptSigningException;
import com.seamware.consentmanager.signing.SignedReceipt;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.ConsentFixtures;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpMethod;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves a grant whose receipt cannot be signed is not recorded at all: the whole granting write
 * rolls back and the caller is answered {@code 500}, while a write that issues nothing still lands.
 */
@MicronautTest(transactional = false)
@Property(name = SigningFailureIT.FAILING_SIGNER, value = "true")
@DisplayName("Receipt signing failures")
class SigningFailureIT extends KeycloakAndPostgresTestResource {

    static final String FAILING_SIGNER = "test.signing.fail";

    private static final String FAILURE_MESSAGE = "signing refused by SigningFailureIT";

    private static final String PATH_CONSENTS = "/consents";

    private static final String PATH_RESUME = "/consents/{id}/resume";

    private static final String PATH_MY_CONSENT = "/consents/me/{id}";

    private static final String PARAM_FORMAT = "format";

    private static final String FORMAT_ISO = "receipt";

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String PROVIDER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String CONSUMER = "urn:test:participant:signing-failure-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:signing-failure";

    private static final String PURPOSE = "Signing failure";

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject IssuedReceiptRepository issued;

    private User subject;

    private PrivacyNotice notice;

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

    @Test
    @DisplayName("a grant that cannot be signed answers 500 and leaves no row behind")
    void unsignableGrantLeavesNothing() {
        HttpResponse<String> response = decide(ConsentStatus.GRANTED);

        assertServerError(response);
        assertThat(heldBySubject()).isEmpty();
    }

    @Test
    @DisplayName("an unsignable re-grant does not revoke the grant it would have superseded")
    void unsignableRegrantKeepsTheOldGrant() {
        Consent held = seed(ConsentStatus.GRANTED);

        HttpResponse<String> response = decide(ConsentStatus.GRANTED);

        assertServerError(response);
        assertThat(heldBySubject())
                .singleElement()
                .satisfies(
                        consent -> {
                            assertThat(consent.getId()).isEqualTo(held.getId());
                            assertThat(consent.getStatus()).isEqualTo(ConsentStatus.GRANTED);
                        });
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(held.getId())).isEmpty();
    }

    @Test
    @DisplayName("an unsignable resume answers 500 and leaves the request pending")
    void unsignableResumeKeepsThePendingRow() {
        Consent pending = seed(ConsentStatus.PENDING);

        HttpResponse<String> response =
                exchange(HttpRequest.create(HttpMethod.POST, expand(PATH_RESUME, pending.getId())));

        assertServerError(response);
        assertThat(consents.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.PENDING);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(pending.getId())).isEmpty();
        assertThat(issued.findById(pending.getId())).isEmpty();
    }

    @Test
    @DisplayName("an ISO/IEC 29184 record that cannot be signed answers 500")
    void unsignableIsoReadFails() {
        Consent granted = seed(ConsentStatus.GRANTED);

        HttpResponse<String> response =
                exchange(
                        HttpRequest.GET(
                                UriBuilder.of(expand(PATH_MY_CONSENT, granted.getId()))
                                        .queryParam(PARAM_FORMAT, FORMAT_ISO)
                                        .build()));

        assertServerError(response);
    }

    @Test
    @DisplayName("a refusal issues no receipt, so it is recorded regardless")
    void refusalStillLands() {
        HttpResponse<String> response = decide(ConsentStatus.REFUSED);

        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(heldBySubject())
                .singleElement()
                .extracting(Consent::getStatus)
                .isEqualTo(ConsentStatus.REFUSED);
    }

    private HttpResponse<String> decide(ConsentStatus decision) {
        return exchange(
                HttpRequest.POST(
                        PATH_CONSENTS,
                        Map.of("privacyNoticeId", notice.getId(), "decision", decision.name())));
    }

    private Consent seed(ConsentStatus status) {
        return consents.save(
                Consent.withStatus(
                        status,
                        subject.getId(),
                        notice.getId(),
                        notice.getProviderId(),
                        notice.getConsumerId(),
                        null,
                        CONTRACT_URI,
                        noTerms()));
    }

    private List<Consent> heldBySubject() {
        return consents.findByUserIdAndStatusIn(subject.getId(), List.of(ConsentStatus.values()));
    }

    private static void assertServerError(HttpResponse<String> response) {
        assertThat(response.code()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getCode());
        assertThat(response.getContentType())
                .hasValueSatisfying(type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
        assertThat(response.body()).doesNotContain(FAILURE_MESSAGE);
    }

    private static String expand(String template, UUID id) {
        return UriBuilder.of(template).expand(Map.of("id", id.toString())).toString();
    }

    private static String userToken() {
        return KeycloakTestResource.accessToken(RealmPrincipal.USER);
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

    /** A signer that refuses every receipt, standing in for a broken key or a JOSE failure. */
    @Singleton
    @Replaces(JwsConsentSigningService.class)
    @Requires(property = FAILING_SIGNER, value = "true")
    static class FailingSigningService implements ConsentSigningService {

        @Override
        @NonNull
        public <R> SignedReceipt<R> sign(@NonNull R receipt) {
            throw new ReceiptSigningException(FAILURE_MESSAGE, new IllegalStateException());
        }

        @Override
        public boolean verify(@NonNull Object receipt, @NonNull String detachedJws) {
            return false;
        }

        @Override
        public boolean verifyCanonical(@NonNull String canonicalJson, @NonNull String detachedJws) {
            return false;
        }
    }
}
