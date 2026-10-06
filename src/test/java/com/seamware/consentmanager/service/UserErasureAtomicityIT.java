package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
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
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.data.model.Pageable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@code @Transactional} on {@link UserService#erase} is load-bearing rather than
 * decorative: every happy-path assertion elsewhere passes whether the advice applies or not, so
 * only a failure part-way through an erasure tells the two apart.
 *
 * <p>The failure is injected by replacing {@link UserParticipantRepository} with one that refuses
 * every call. {@code erase} reaches it only after it has already moved each open consent to its
 * closing status and appended an event to each trail, so without a transaction those writes would
 * stand and leave the subject closed out but still named.
 */
@MicronautTest(transactional = false)
@Property(name = UserErasureAtomicityIT.FAILING_LINKS, value = "true")
@DisplayName("User erasure atomicity")
class UserErasureAtomicityIT extends PostgresTestResource {

    /** Gates the refusing link repository, so it exists only for this test class. */
    static final String FAILING_LINKS = "test.failing-link-repository.enabled";

    /** Carried by the injected failure, so the assertion can name what it expected to go wrong. */
    static final String FAILURE_MESSAGE = "link deletion refused by the atomicity fixture";

    private static final String PROVIDER_PARTICIPANT = "urn:test:participant:atomicity-provider";

    private static final String CONSUMER_PARTICIPANT = "urn:test:participant:atomicity-consumer";

    private static final String NOTICE_CONTRACT_URI = "urn:test:contract:atomicity";

    private static final String USER_IDENTIFIER = "urn:test:user:atomicity";

    private static final String USER_EMAIL = "atomicity@example.org";

    @Inject UserService service;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    /** Rows committed by this test outlive its transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        users.findByIdentifier(USER_IDENTIFIER)
                .ifPresent(
                        user -> {
                            consents.findByUserId(user.getId(), Pageable.UNPAGED)
                                    .forEach(
                                            consent -> {
                                                events.findByConsentIdOrderByOccurredAtAsc(
                                                                consent.getId())
                                                        .forEach(events::delete);
                                                consents.delete(consent);
                                            });
                            users.delete(user);
                        });
        participants
                .findByIdentifier(PROVIDER_PARTICIPANT)
                .ifPresent(
                        provider ->
                                notices.findByProviderIdAndArchivedAtIsNull(provider.getId())
                                        .forEach(notices::delete));
        participant(PROVIDER_PARTICIPANT).ifPresent(participants::delete);
        participant(CONSUMER_PARTICIPANT).ifPresent(participants::delete);
    }

    @Test
    @DisplayName("a failure after the consents are closed rolls the closures back")
    void rollsBackTheWholeErasure() {
        User user = users.save(new User(USER_IDENTIFIER, USER_EMAIL, null, null));
        UUID consentId = grant(user);

        assertThatThrownBy(() -> service.erase(user)).hasStackTraceContaining(FAILURE_MESSAGE);

        Consent stored = consents.findById(consentId).orElseThrow();
        assertThat(stored.getStatus())
                .as("the revocation is undone with the erasure that attempted it")
                .isEqualTo(ConsentStatus.GRANTED);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .as("and so is the event it had already appended")
                .isEmpty();

        User reread = users.findById(user.getId()).orElseThrow();
        assertThat(reread.getIdentifier()).isEqualTo(USER_IDENTIFIER);
        assertThat(reread.getEmail()).isEqualTo(USER_EMAIL);
    }

    /** Seeds one granted consent between the two participants, so the erasure has work to undo. */
    private UUID grant(User user) {
        Participant provider = seeded(PROVIDER_PARTICIPANT);
        Participant consumer = seeded(CONSUMER_PARTICIPANT);
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                NOTICE_CONTRACT_URI,
                                null,
                                provider.getId(),
                                consumer.getId(),
                                new PrivacyNoticePayload(
                                        null, null, null, null, null, null, null, null, null)));
        return consents.save(
                        Consent.withStatus(
                                ConsentStatus.GRANTED,
                                user.getId(),
                                notice.getId(),
                                provider.getId(),
                                consumer.getId(),
                                null,
                                NOTICE_CONTRACT_URI,
                                new ConsentSnapshot(null, null, null, null, null)))
                .getId();
    }

    private Participant seeded(String identifier) {
        return participant(identifier)
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

    private Optional<Participant> participant(String identifier) {
        return participants.findByIdentifier(identifier);
    }

    /**
     * Supplies a {@link UserParticipantRepository} that refuses every call, standing in for any
     * write that can fail part-way through an erasure.
     *
     * <p>A dynamic proxy rather than a hand-written stub: nothing in this test calls the repository
     * except the erasure under test, so every method but {@link Object}'s own is a refusal.
     */
    @Factory
    @Requires(property = FAILING_LINKS, value = "true")
    static class FailingLinkRepositoryFactory {

        @Singleton
        @Replaces(UserParticipantRepository.class)
        UserParticipantRepository links() {
            return (UserParticipantRepository)
                    Proxy.newProxyInstance(
                            UserParticipantRepository.class.getClassLoader(),
                            new Class<?>[] {UserParticipantRepository.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "toString" -> "FailingUserParticipantRepository";
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "equals" -> proxy == args[0];
                                        default -> throw new IllegalStateException(FAILURE_MESSAGE);
                                    });
        }
    }
}
