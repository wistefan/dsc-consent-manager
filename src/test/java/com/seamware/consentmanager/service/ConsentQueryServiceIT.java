package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the subject-facing query reads only the caller's own consents and counts a filtered page
 * under the same predicate as its content.
 *
 * <p>The filtered total is the load-bearing assertion: the original service counted the whole
 * collection and reported it as the subject's total (bug 18.3). Here a narrowed listing's {@code
 * totalElements} must be the number of the subject's consents the filter matches, never their whole
 * history nor another subject's rows.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent queries")
class ConsentQueryServiceIT extends PostgresTestResource {

    private static final String PROVIDER = "urn:test:participant:consent-query-provider";

    private static final String CONSUMER = "urn:test:participant:consent-query-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:consent-query";

    private static final String NOTICE_TITLE = "Consent query notice";

    private static final List<ConsentStatus> ALL_STATUSES = List.of(ConsentStatus.values());

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
        List.of(PROVIDER, CONSUMER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    /** Each filter paired with how many of the seeded subject's four consents it must count. */
    static List<Arguments> filters() {
        return List.of(
                Arguments.of("no filter", null, false, 4),
                Arguments.of("one status", List.of(ConsentStatus.GRANTED), false, 2),
                Arguments.of(
                        "two statuses",
                        List.of(ConsentStatus.GRANTED, ConsentStatus.REVOKED),
                        false,
                        3),
                Arguments.of("a status matching nothing", List.of(ConsentStatus.EXPIRED), false, 0),
                Arguments.of("one notice", null, true, 1),
                Arguments.of(
                        "status and notice together", List.of(ConsentStatus.GRANTED), true, 1));
    }

    @ParameterizedTest(name = "{0} counts {3}")
    @MethodSource("filters")
    @DisplayName("a filtered page's total is counted under the same predicate as its content")
    void countsTheFilteredPredicateNotTheWholeHistory(
            String name, List<ConsentStatus> statuses, boolean byFirstNotice, int expectedMatches) {
        User subject = user();
        List<UUID> notices = fourNotices();
        seed(subject, notices.get(0), ConsentStatus.GRANTED);
        seed(subject, notices.get(1), ConsentStatus.GRANTED);
        seed(subject, notices.get(2), ConsentStatus.REVOKED);
        seed(subject, notices.get(3), ConsentStatus.REFUSED);
        // A second subject's grant must never leak into the first subject's total.
        seed(user(), notices.get(0), ConsentStatus.GRANTED);
        UUID noticeFilter = byFirstNotice ? notices.get(0) : null;

        Page<ConsentDetails> page =
                queries.listForUser(
                        subject, new ConsentQueryCriteria(statuses, noticeFilter, 0, WIDE_PAGE));

        assertThat(page.getTotalSize())
                .as("the total is the matching count, not the subject's whole history of 4")
                .isEqualTo(expectedMatches);
        assertThat(page.getContent())
                .as("the content of a wide page is exactly the matching consents")
                .hasSize(expectedMatches)
                .allSatisfy(
                        details ->
                                assertThat(details.consent().getUserId())
                                        .isEqualTo(subject.getId()));
    }

    @Test
    @DisplayName("the newest consent is the first of the page")
    void ordersNewestFirst() {
        User subject = user();
        List<UUID> notices = fourNotices();
        UUID older = seed(subject, notices.get(0), ConsentStatus.REFUSED);
        UUID newer = seed(subject, notices.get(1), ConsentStatus.GRANTED);

        Page<ConsentDetails> page =
                queries.listForUser(subject, new ConsentQueryCriteria(null, null, 0, WIDE_PAGE));

        assertThat(page.getContent())
                .extracting(details -> details.consent().getId())
                .containsExactly(newer, older);
    }

    @Test
    @DisplayName("an oversized page size is clamped to the configured ceiling, not refused")
    void clampsAnOversizedPage() {
        User subject = user();

        Page<ConsentDetails> clamped =
                queries.listForUser(
                        subject,
                        new ConsentQueryCriteria(null, null, 0, paging.getPageMaxSize() + 1));

        assertThat(clamped.getSize())
                .as("the response reports the size actually applied, which is the ceiling")
                .isEqualTo(paging.getPageMaxSize());
    }

    @Test
    @DisplayName("omitting the size applies the configured default")
    void appliesTheConfiguredDefaultSize() {
        User subject = user();

        Page<ConsentDetails> defaulted =
                queries.listForUser(subject, new ConsentQueryCriteria(null, null, null, null));

        assertThat(defaulted.getSize()).isEqualTo(paging.getPageDefaultSize());
    }

    @Test
    @DisplayName("a subject reads their own consent by id, with both parties named")
    void readsAnOwnedConsent() {
        User subject = user();
        UUID owned = seed(subject, fourNotices().get(0), ConsentStatus.GRANTED);

        ConsentDetails details = queries.getForUser(subject, owned);

        assertThat(details.consent().getId()).isEqualTo(owned);
        assertThat(details.providerIdentifier()).isEqualTo(PROVIDER);
        assertThat(details.consumerIdentifier()).isEqualTo(CONSUMER);
    }

    @Test
    @DisplayName(
            "a consent owned by another subject is a 404, indistinguishable from an unknown id")
    void hidesAnotherSubjectsConsent() {
        User owner = user();
        User other = user();
        UUID ownersConsent = seed(owner, fourNotices().get(0), ConsentStatus.GRANTED);

        assertThatThrownBy(() -> queries.getForUser(other, ownersConsent))
                .as("the response never lets a caller learn a consent they do not own exists")
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> queries.getForUser(owner, UUID.randomUUID()))
                .as("an unknown id earns the same 404 as one owned by somebody else")
                .isInstanceOf(NotFoundException.class);
    }

    private UUID seed(User subject, UUID noticeId, ConsentStatus status) {
        PrivacyNotice notice = notices.findById(noticeId).orElseThrow();
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

    private List<UUID> fourNotices() {
        return IntStream.range(0, 4).mapToObj(i -> notice().getId()).toList();
    }

    private PrivacyNotice notice() {
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI + ":" + UUID.randomUUID(),
                                NOTICE_TITLE,
                                participant(PROVIDER).getId(),
                                participant(CONSUMER).getId(),
                                emptyPayload()));
        seededNotices.add(notice.getId());
        return notice;
    }

    private com.seamware.consentmanager.domain.Participant participant(String identifier) {
        return participants
                .findByIdentifier(identifier)
                .orElseGet(
                        () ->
                                participants.save(
                                        new com.seamware.consentmanager.domain.Participant(
                                                identifier,
                                                identifier,
                                                null,
                                                null,
                                                Map.of(),
                                                null)));
    }

    private static PrivacyNoticePayload emptyPayload() {
        return new PrivacyNoticePayload(null, null, null, null, null, null, null, null, null);
    }

    private User user() {
        User subject =
                users.save(
                        new User(
                                "urn:test:user:consent-query:" + UUID.randomUUID(),
                                null,
                                null,
                                null));
        seededUsers.add(subject);
        return subject;
    }
}
