package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.PrivacyNoticeFixtures.emptyPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link ParticipantService#deregister} is atomic: a {@link UserParticipantRepository}
 * failing after the consents were terminated must roll the whole cascade back.
 */
@MicronautTest(transactional = false)
@Property(name = ParticipantDeregistrationAtomicityIT.FAILING_LINKS, value = "true")
@DisplayName("Participant deregistration atomicity")
class ParticipantDeregistrationAtomicityIT extends PostgresTestResource {

    /** Gates the refusing link repository, so it exists only for this test class. */
    static final String FAILING_LINKS = "test.failing-participant-link-repository.enabled";

    /** Carried by the injected failure, so the assertion can recognise it. */
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
                                emptyPayload()));
        return consents.save(
                        Consent.withStatus(
                                ConsentStatus.PENDING,
                                user.getId(),
                                notice.getId(),
                                subject.getId(),
                                counterparty.getId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
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
     * Supplies a {@link UserParticipantRepository} proxy that refuses every call but {@link
     * Object}'s own, standing in for any write that can fail part-way through the cascade.
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
