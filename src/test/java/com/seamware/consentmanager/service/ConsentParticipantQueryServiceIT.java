package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.data.model.Page;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the participant-facing query reads only the consents the caller is a party to, scopes that
 * in the predicate rather than after the read, and never turns an unknown subject into a refusal.
 *
 * <p>Scoping is the load-bearing assertion: {@code GET /consents} is the service's primary
 * integration point, so a window in which another participant's row is materialised - or a {@code
 * 403} that let a caller probe which subjects exist - would be a disclosure. A third participant,
 * party to none of the seeded consents, is what every case checks the scope against.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant consent queries")
class ConsentParticipantQueryServiceIT extends PostgresTestResource {

    private static final String PROVIDER = "urn:test:participant:pq-provider";

    private static final String CONSUMER = "urn:test:participant:pq-consumer";

    /**
     * A participant party to none of the seeded consents, so the scope has something to exclude.
     */
    private static final String BYSTANDER = "urn:test:participant:pq-bystander";

    private static final String CONTRACT_URI = "urn:test:contract:pq";

    private static final String NOTICE_TITLE = "Participant query notice";

    private static final List<ConsentStatus> ALL_STATUSES = List.of(ConsentStatus.values());

    /** No status filter: the service must then default to GRANTED alone. */
    private static final List<ConsentStatus> NO_STATUS_FILTER = null;

    /** No notice filter: every notice is admitted. */
    private static final UUID ANY_NOTICE = null;

    /**
     * A page wide enough to hold every seeded consent, so the content size is never the limiter.
     */
    private static final int WIDE_PAGE = 100;

    @Inject ConsentQueryService queries;

    @Inject ConsentRepository consents;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject ConsentManagerConfiguration.Consents paging;

    private final List<User> seededUsers = new ArrayList<>();

    private final List<UUID> seededNotices = new ArrayList<>();

    @AfterEach
    void removeCommittedRows() {
        seededUsers.forEach(
                user -> {
                    consents.findByUserIdAndStatusIn(user.getId(), ALL_STATUSES)
                            .forEach(consents::delete);
                    users.delete(user);
                });
        seededUsers.clear();
        seededNotices.forEach(notices::deleteById);
        seededNotices.clear();
    }

    @AfterAll
    void removeParticipants() {
        List.of(PROVIDER, CONSUMER, BYSTANDER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    /** Each caller paired with whether the seeded consent names it, which decides what it sees. */
    static List<Arguments> callers() {
        return List.of(
                Arguments.of(
                        "the provider", (Function<Fixtures, Participant>) f -> f.provider, true),
                Arguments.of(
                        "the consumer", (Function<Fixtures, Participant>) f -> f.consumer, true),
                Arguments.of(
                        "a non-party", (Function<Fixtures, Participant>) f -> f.bystander, false));
    }

    @ParameterizedTest(name = "listing scopes to {0}")
    @MethodSource("callers")
    @DisplayName("the listing carries a consent only to the participants it names")
    void listScopesToParties(String name, Function<Fixtures, Participant> caller, boolean party) {
        Fixtures f = fixtures();
        UUID granted = seed(f.subject, f.provider, f.consumer, ConsentStatus.GRANTED);

        Page<ConsentDetails> page =
                queries.listForParticipant(
                        caller.apply(f),
                        null,
                        new ConsentQueryCriteria(NO_STATUS_FILTER, ANY_NOTICE, 0, WIDE_PAGE));

        if (party) {
            assertThat(page.getContent())
                    .extracting(details -> details.consent().getId())
                    .containsExactly(granted);
        } else {
            assertThat(page.getContent())
                    .as("a participant the consent does not name reads an empty page")
                    .isEmpty();
            assertThat(page.getTotalSize()).isZero();
        }
    }

    @ParameterizedTest(name = "get scopes to {0}")
    @MethodSource("callers")
    @DisplayName("a consent by id resolves only for the participants it names, else a 404")
    void getScopesToParties(String name, Function<Fixtures, Participant> caller, boolean party) {
        Fixtures f = fixtures();
        UUID granted = seed(f.subject, f.provider, f.consumer, ConsentStatus.GRANTED);

        if (party) {
            ConsentDetails details = queries.getForParticipant(caller.apply(f), granted);
            assertThat(details.consent().getId()).isEqualTo(granted);
            assertThat(details.providerIdentifier()).isEqualTo(PROVIDER);
            assertThat(details.consumerIdentifier()).isEqualTo(CONSUMER);
        } else {
            assertThatThrownBy(() -> queries.getForParticipant(caller.apply(f), granted))
                    .as("a consent the caller is no party to is hidden as an unknown id would be")
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Test
    @DisplayName("an unknown id earns the same 404 as a consent the caller is no party to")
    void hidesAnUnknownId() {
        Fixtures f = fixtures();

        assertThatThrownBy(() -> queries.getForParticipant(f.provider, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("an absent status filter returns only GRANTED consents")
    void defaultsToGrantedOnly() {
        Fixtures f = fixtures();
        UUID granted = seed(f.subject, f.provider, f.consumer, ConsentStatus.GRANTED);
        seed(f.subject, f.provider, f.consumer, ConsentStatus.REVOKED);

        Page<ConsentDetails> page =
                queries.listForParticipant(
                        f.provider,
                        null,
                        new ConsentQueryCriteria(NO_STATUS_FILTER, ANY_NOTICE, 0, WIDE_PAGE));

        assertThat(page.getContent())
                .as("the decisions in force are what an omitted status filter returns")
                .extracting(details -> details.consent().getId())
                .containsExactly(granted);
    }

    @Test
    @DisplayName("an explicit status filter overrides the GRANTED default")
    void anExplicitStatusOverridesTheDefault() {
        Fixtures f = fixtures();
        seed(f.subject, f.provider, f.consumer, ConsentStatus.GRANTED);
        UUID revoked = seed(f.subject, f.provider, f.consumer, ConsentStatus.REVOKED);

        Page<ConsentDetails> page =
                queries.listForParticipant(
                        f.provider,
                        null,
                        new ConsentQueryCriteria(
                                List.of(ConsentStatus.REVOKED), ANY_NOTICE, 0, WIDE_PAGE));

        assertThat(page.getContent())
                .extracting(details -> details.consent().getId())
                .containsExactly(revoked);
    }

    @Test
    @DisplayName("userIdentifier narrows the page to one subject's consents")
    void narrowsToOneSubject() {
        Fixtures f = fixtures();
        User other = user();
        UUID forSubject = seed(f.subject, f.provider, f.consumer, ConsentStatus.GRANTED);
        seed(other, f.provider, f.consumer, ConsentStatus.GRANTED);

        Page<ConsentDetails> page =
                queries.listForParticipant(
                        f.provider,
                        f.subject.getIdentifier(),
                        new ConsentQueryCriteria(NO_STATUS_FILTER, ANY_NOTICE, 0, WIDE_PAGE));

        assertThat(page.getContent())
                .extracting(details -> details.consent().getId())
                .containsExactly(forSubject);
    }

    @Test
    @DisplayName("an identifier no user holds yields an empty page, never a refusal")
    void unknownIdentifierIsAnEmptyPage() {
        Fixtures f = fixtures();
        seed(f.subject, f.provider, f.consumer, ConsentStatus.GRANTED);

        Page<ConsentDetails> page =
                queries.listForParticipant(
                        f.provider,
                        "urn:test:user:pq:nobody",
                        new ConsentQueryCriteria(NO_STATUS_FILTER, ANY_NOTICE, 0, WIDE_PAGE));

        assertThat(page.getContent())
                .as("an unknown subject discloses nothing about which identifiers exist")
                .isEmpty();
    }

    @Test
    @DisplayName("a subject the caller is party to no consent for yields an empty page")
    void unrelatedSubjectIsAnEmptyPage() {
        Fixtures f = fixtures();
        User unrelated = user();
        // The unrelated subject's only consent names the bystander, not the calling provider.
        seed(unrelated, f.bystander, f.consumer, ConsentStatus.GRANTED);

        Page<ConsentDetails> page =
                queries.listForParticipant(
                        f.provider,
                        unrelated.getIdentifier(),
                        new ConsentQueryCriteria(NO_STATUS_FILTER, ANY_NOTICE, 0, WIDE_PAGE));

        assertThat(page.getContent())
                .as(
                        "the scope predicate, not a 403, is what excludes a subject the caller shares"
                                + " no consent with")
                .isEmpty();
    }

    @Test
    @DisplayName("privacyNoticeId narrows the page to one notice's decisions")
    void narrowsToOneNotice() {
        Fixtures f = fixtures();
        PrivacyNotice first = notice(f.provider, f.consumer);
        PrivacyNotice second = notice(f.provider, f.consumer);
        UUID againstFirst = seed(f.subject, first, ConsentStatus.GRANTED);
        seed(f.subject, second, ConsentStatus.GRANTED);

        Page<ConsentDetails> page =
                queries.listForParticipant(
                        f.provider,
                        null,
                        new ConsentQueryCriteria(NO_STATUS_FILTER, first.getId(), 0, WIDE_PAGE));

        assertThat(page.getContent())
                .extracting(details -> details.consent().getId())
                .containsExactly(againstFirst);
    }

    @Test
    @DisplayName("a filtered page's total is counted under the same predicate as its content")
    void countsTheScopedPredicate() {
        Fixtures f = fixtures();
        seed(f.subject, f.provider, f.consumer, ConsentStatus.GRANTED);
        seed(f.subject, f.provider, f.consumer, ConsentStatus.GRANTED);
        // A consent the caller is no party to must never inflate its total.
        seed(user(), f.bystander, f.consumer, ConsentStatus.GRANTED);

        Page<ConsentDetails> page =
                queries.listForParticipant(
                        f.provider,
                        null,
                        new ConsentQueryCriteria(NO_STATUS_FILTER, ANY_NOTICE, 0, WIDE_PAGE));

        assertThat(page.getTotalSize())
                .as("the total counts only the caller's own two granted consents")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an oversized page size is clamped to the configured ceiling, not refused")
    void clampsAnOversizedPage() {
        Fixtures f = fixtures();

        Page<ConsentDetails> clamped =
                queries.listForParticipant(
                        f.provider,
                        null,
                        new ConsentQueryCriteria(
                                NO_STATUS_FILTER, ANY_NOTICE, 0, paging.getPageMaxSize() + 1));

        assertThat(clamped.getSize()).isEqualTo(paging.getPageMaxSize());
    }

    private UUID seed(
            User subject, Participant provider, Participant consumer, ConsentStatus status) {
        return seed(subject, notice(provider, consumer), status);
    }

    private UUID seed(User subject, PrivacyNotice notice, ConsentStatus status) {
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }

    private PrivacyNotice notice(Participant provider, Participant consumer) {
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI + ":" + UUID.randomUUID(),
                                NOTICE_TITLE,
                                provider.getId(),
                                consumer.getId(),
                                emptyPayload()));
        seededNotices.add(notice.getId());
        return notice;
    }

    private static PrivacyNoticePayload emptyPayload() {
        return new PrivacyNoticePayload(null, null, null, null, null, null, null, null, null);
    }

    private Fixtures fixtures() {
        return new Fixtures(
                participant(PROVIDER), participant(CONSUMER), participant(BYSTANDER), user());
    }

    private Participant participant(String identifier) {
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

    private User user() {
        User subject =
                users.save(new User("urn:test:user:pq:" + UUID.randomUUID(), null, null, null));
        seededUsers.add(subject);
        return subject;
    }

    /**
     * The participants and subject one case acts over, resolved together so a case reads cleanly.
     */
    private record Fixtures(
            Participant provider, Participant consumer, Participant bystander, User subject) {}
}
