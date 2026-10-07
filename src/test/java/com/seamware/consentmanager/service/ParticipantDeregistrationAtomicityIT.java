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
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@code @Transactional} on {@link ParticipantService#deregister} is load-bearing rather
 * than decorative: every happy-path assertion elsewhere passes whether the advice applies or not,
 * so only a failure part-way through a deregistration tells the two apart.
 *
 * <p>The failure is injected by replacing {@link UserParticipantRepository} with one that refuses
 * every call. The cascade reaches it only after it has already terminated the unanswered consents
 * and appended an event to each trail, so without a transaction those writes would stand and leave
 * a participant whose offers are closed but which is still registered and still linked.
 */
@MicronautTest(transactional = false)
@Property(name = ParticipantDeregistrationAtomicityIT.FAILING_LINKS, value = "true")
@DisplayName("Participant deregistration atomicity")
class ParticipantDeregistrationAtomicityIT extends PostgresTestResource {

    /** Gates the refusing link repository, so it exists only for this test class. */
    static final String FAILING_LINKS = "test.failing-participant-link-repository.enabled";

    /** Carried by the injected failure, so the assertion can name what it expected to go wrong. */
    static final String FAILURE_MESSAGE = "link deletion refused by the atomicity fixture";

    private static final String SUBJECT_PARTICIPANT =
            "urn:test:participant:deregistration-atomicity-subject";

    private static final String COUNTERPARTY_PARTICIPANT =
            "urn:test:participant:deregistration-atomicity-counterparty";

    private static final String USER_IDENTIFIER = "urn:test:user:deregistration-atomicity";

    private static final String CONTRACT_URI = "urn:test:contract:deregistration-atomicity";

    private static final String LEGAL_NAME = "Atomicity Clinic Ltd";

    private static final String CONTACT_EMAIL = "privacy-officer@atomicity.example.org";

    private static final Map<String, Object> ENDPOINTS =
            Map.of("consentNotification", "https://atomicity.example.org/notify");

    @Inject ParticipantService service;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    /** Rows committed by this test outlive its transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        participant(SUBJECT_PARTICIPANT)
                .ifPresent(
                        subject -> {
                            consents.findByProviderIdAndStatusIn(
                                            subject.getId(), List.of(ConsentStatus.values()))
                                    .forEach(
                                            consent -> {
                                                events.findByConsentIdOrderByOccurredAtAsc(
                                                                consent.getId())
                                                        .forEach(events::delete);
                                                consents.delete(consent);
                                            });
                            notices.findByProviderIdAndArchivedAtIsNull(subject.getId())
                                    .forEach(notices::delete);
                        });
        users.findByIdentifier(USER_IDENTIFIER).ifPresent(users::delete);
        participant(SUBJECT_PARTICIPANT).ifPresent(participants::delete);
        participant(COUNTERPARTY_PARTICIPANT).ifPresent(participants::delete);
    }

    @Test
    @DisplayName("a failure after the consents are terminated rolls the whole cascade back")
    void rollsBackTheWholeDeregistration() {
        Participant subject = seeded(SUBJECT_PARTICIPANT);
        UUID consentId = pending(subject);

        assertThatThrownBy(() -> service.deregister(subject))
                .hasStackTraceContaining(FAILURE_MESSAGE);

        Consent stored = consents.findById(consentId).orElseThrow();
        assertThat(stored.getStatus())
                .as("the termination is undone with the deregistration that attempted it")
                .isEqualTo(ConsentStatus.PENDING);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .as("and so is the event it had already appended")
                .isEmpty();

        Participant reread = participants.findById(subject.getId()).orElseThrow();
        assertThat(reread.getDeregisteredAt()).isNull();
        assertThat(reread.getEmail()).isEqualTo(CONTACT_EMAIL);
        assertThat(reread.getEndpoints()).isEqualTo(ENDPOINTS);
    }

    /** Seeds one unanswered consent naming the subject, so the cascade has work to undo. */
    private UUID pending(Participant subject) {
        Participant counterparty = seeded(COUNTERPARTY_PARTICIPANT);
        User user =
                users.findByIdentifier(USER_IDENTIFIER)
                        .orElseGet(() -> users.save(new User(USER_IDENTIFIER, null, null, null)));
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                null,
                                subject.getId(),
                                counterparty.getId(),
                                new PrivacyNoticePayload(
                                        null, null, null, null, null, null, null, null, null)));
        return consents.save(
                        Consent.withStatus(
                                ConsentStatus.PENDING,
                                user.getId(),
                                notice.getId(),
                                subject.getId(),
                                counterparty.getId(),
                                null,
                                CONTRACT_URI,
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
                                                LEGAL_NAME,
                                                null,
                                                CONTACT_EMAIL,
                                                ENDPOINTS,
                                                null)));
    }

    private Optional<Participant> participant(String identifier) {
        return participants.findByIdentifier(identifier);
    }

    /**
     * Supplies a {@link UserParticipantRepository} that refuses every call, standing in for any
     * write that can fail part-way through the cascade.
     *
     * <p>A dynamic proxy rather than a hand-written stub: nothing in this test calls the repository
     * except the deregistration under test, so every method but {@link Object}'s own is a refusal.
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
