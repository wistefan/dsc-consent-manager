package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.principalFor;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.generated.model.SignedConsentReceiptKantara;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.IssuedReceipt;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.IssuedReceiptRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.signing.CanonicalJsonSerializer;
import com.seamware.consentmanager.signing.ConsentSigningService;
import com.seamware.consentmanager.signing.JwsConsentSigningService;
import com.seamware.consentmanager.signing.SignedReceipt;
import com.seamware.consentmanager.signing.SigningKeyStore;
import com.seamware.consentmanager.support.ConsentFixtures;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Proves a grant is issued its signed Kantara receipt once, in the granting write, over every route
 * into {@code GRANTED}, and that reading the receipt back serves the stored bytes without signing.
 */
@MicronautTest(transactional = false)
@Property(name = ConsentReceiptIssuanceIT.COUNTING_SIGNER, value = "true")
@DisplayName("Grant receipt issuance")
class ConsentReceiptIssuanceIT extends PostgresTestResource {

    static final String COUNTING_SIGNER = "test.signing.count";

    private static final String PROVIDER = "urn:test:participant:receipt-issuance-provider";

    private static final String CONSUMER = "urn:test:participant:receipt-issuance-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:receipt-issuance";

    private static final String SUBJECT = "urn:test:user:receipt-issuance";

    private static final String ISSUER = "https://issuer.test/receipt-issuance";

    private static final String PURPOSE = "Receipt issuance";

    /** Two reads are enough to show the second one did not sign either. */
    private static final int READS = 2;

    /** Each route into {@code GRANTED} the API exposes. */
    enum GrantPath {
        FRESH_DECISION,
        ANSWERED_REQUEST,
        RESUME
    }

    @Inject ConsentService service;

    @Inject ConsentLifecycleService lifecycle;

    @Inject ConsentWriter writer;

    @Inject ConsentParties parties;

    @Inject ConsentReceiptService receipts;

    @Inject ConsentRepository consents;

    @Inject IssuedReceiptRepository issued;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject ConsentSigningService signing;

    @Inject CanonicalJsonSerializer canonical;

    @Inject CountingSigningService counter;

    private User subject;

    private PrivacyNotice notice;

    @BeforeEach
    void seedPair() {
        subject = users.save(new User(SUBJECT, null, null, null));
        Participant provider = ConsentFixtures.participant(participants, PROVIDER);
        Participant consumer = ConsentFixtures.participant(participants, CONSUMER);
        notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                null,
                                provider.getId(),
                                consumer.getId(),
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

    /** Deleting a consent cascades its events and its receipt. */
    @AfterEach
    void removeCommittedRows() {
        consents.findByUserIdAndStatusIn(subject.getId(), List.of(ConsentStatus.values()))
                .forEach(consents::delete);
        users.delete(subject);
        notices.deleteById(notice.getId());
        List.of(PROVIDER, CONSUMER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @ParameterizedTest
    @EnumSource(GrantPath.class)
    @DisplayName("each grant path stores exactly one receipt whose JWS verifies over its text")
    void issuesOneVerifiableReceipt(GrantPath path) {
        UUID consentId = grant(path);

        List<IssuedReceipt> stored = receiptsOfSubject();
        assertThat(stored)
                .singleElement()
                .extracting(IssuedReceipt::consentId)
                .isEqualTo(consentId);
        IssuedReceipt receipt = stored.getFirst();
        assertThat(signing.verifyCanonical(receipt.canonicalReceipt(), receipt.jws())).isTrue();
    }

    @Test
    @DisplayName("a refusal stores no receipt")
    void refusalIssuesNothing() {
        service.decide(subject, decision(ConsentStatus.REFUSED));

        assertThat(receiptsOfSubject()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(GrantPath.class)
    @DisplayName("a Kantara read serves the stored receipt and signs nothing")
    void readsServeTheStoredBytes(GrantPath path) {
        UUID consentId = grant(path);
        IssuedReceipt stored = issued.findById(consentId).orElseThrow();
        int signedBeforeReads = counter.signed();

        List<SignedConsentReceiptKantara> served =
                IntStream.range(0, READS).mapToObj(read -> readKantara(consentId)).toList();

        assertThat(counter.signed()).as("a read never signs").isEqualTo(signedBeforeReads);
        assertThat(served)
                .allSatisfy(
                        receipt -> {
                            assertThat(receipt.getJws()).isEqualTo(stored.jws());
                            assertThat(canonical.toJson(receipt.getReceipt()))
                                    .isEqualTo(stored.canonicalReceipt());
                        });
    }

    private UUID grant(GrantPath path) {
        return switch (path) {
            case FRESH_DECISION ->
                    service.decide(subject, decision(ConsentStatus.GRANTED)).consent().getId();
            case ANSWERED_REQUEST -> {
                request();
                yield service.decide(subject, decision(ConsentStatus.GRANTED)).consent().getId();
            }
            case RESUME ->
                    lifecycle
                            .resume(principalFor(ISSUER, subject), request(), null)
                            .consent()
                            .getId();
        };
    }

    /** An unanswered request for the pair, as a participant's ask would leave it. */
    private UUID request() {
        return writer.request(subject, notice, ConsentSnapshot.of(notice, null, null), PROVIDER)
                .primary()
                .consent()
                .getId();
    }

    private ConsentDecision decision(ConsentStatus status) {
        return new ConsentDecision(notice.getId(), status, null, null);
    }

    private SignedConsentReceiptKantara readKantara(UUID consentId) {
        return receipts.toKantara(
                parties.details(consents.findById(consentId).orElseThrow(), false));
    }

    private List<IssuedReceipt> receiptsOfSubject() {
        return consents
                .findByUserIdAndStatusIn(subject.getId(), List.of(ConsentStatus.values()))
                .stream()
                .map(consent -> issued.findById(consent.getId()))
                .flatMap(Optional::stream)
                .toList();
    }

    /** The real signer, counting its {@code sign} calls so a read can be shown to make none. */
    @Singleton
    @Replaces(JwsConsentSigningService.class)
    @Requires(property = COUNTING_SIGNER, value = "true")
    static class CountingSigningService implements ConsentSigningService {

        private final JwsConsentSigningService delegate;

        private final AtomicInteger signed = new AtomicInteger();

        CountingSigningService(SigningKeyStore keys, CanonicalJsonSerializer canonical) {
            this.delegate = new JwsConsentSigningService(keys, canonical);
        }

        int signed() {
            return signed.get();
        }

        @Override
        @NonNull
        public <R> SignedReceipt<R> sign(@NonNull R receipt) {
            signed.incrementAndGet();
            return delegate.sign(receipt);
        }

        @Override
        public boolean verify(@NonNull Object receipt, @NonNull String detachedJws) {
            return delegate.verify(receipt, detachedJws);
        }

        @Override
        public boolean verifyCanonical(@NonNull String canonicalJson, @NonNull String detachedJws) {
            return delegate.verifyCanonical(canonicalJson, detachedJws);
        }
    }
}
