package com.seamware.consentmanager.service;

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
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.annotation.Property;
import io.micronaut.data.model.Page;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests both consent listings' paging at its edges, including an oversized {@code size} clamped to
 * {@link #PAGE_MAX_SIZE} rather than refused. Each seeded consent is GRANTED under its own notice.
 */
@MicronautTest(transactional = false)
@Property(name = "consent-manager.consents.page-max-size", value = ConsentPaginationBoundaryIT.MAX)
@DisplayName("Consent pagination boundaries")
class ConsentPaginationBoundaryIT extends PostgresTestResource {

    /** The configured ceiling; small, so an oversized request is cheap to seed past. */
    static final String MAX = "6";

    private static final int PAGE_MAX_SIZE = Integer.parseInt(MAX);

    private static final int SIZE = 5;

    private static final int OVERSIZED = 50;

    private static final String PROVIDER = "urn:test:participant:paging-provider";

    private static final String CONSUMER = "urn:test:participant:paging-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:paging";

    private static final String NOTICE_TITLE = "Paging notice";

    @Inject ConsentQueryService queries;

    @Inject ConsentRepository consents;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    private User subject;

    /** The two paged listings, each read for the seeded subject's consents. */
    enum Listing {
        SUBJECT,
        PARTICIPANT
    }

    /** One paging edge: what is seeded and asked for, and the page that must come back. */
    private record Boundary(
            String name,
            int seeded,
            int size,
            int page,
            int content,
            int appliedSize,
            int totalPages) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Boundary> BOUNDARIES =
            List.of(
                    new Boundary("empty result", 0, SIZE, 0, 0, SIZE, 0),
                    new Boundary("single page", 3, SIZE, 0, 3, SIZE, 1),
                    new Boundary("exact multiple, last page", 2 * SIZE, SIZE, 1, SIZE, SIZE, 2),
                    new Boundary("partial last page", SIZE + 2, SIZE, 1, 2, SIZE, 2),
                    new Boundary("page past the end", SIZE + 2, SIZE, 2, 0, SIZE, 2),
                    new Boundary(
                            "oversized size clamped",
                            PAGE_MAX_SIZE + 1,
                            OVERSIZED,
                            0,
                            PAGE_MAX_SIZE,
                            PAGE_MAX_SIZE,
                            2));

    /** Every boundary on both listings. */
    static Stream<Arguments> boundaries() {
        return Stream.of(Listing.values())
                .flatMap(listing -> BOUNDARIES.stream().map(b -> Arguments.of(listing, b)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("boundaries")
    @DisplayName("reports the page it applied")
    void reportsThePageItApplied(Listing listing, Boundary boundary) {
        Participant provider = participant(PROVIDER);
        Participant consumer = participant(CONSUMER);
        subject =
                users.save(new User("urn:test:user:paging:" + UUID.randomUUID(), null, null, null));
        IntStream.range(0, boundary.seeded()).forEach(i -> seed(provider, consumer));
        ConsentQueryCriteria criteria =
                new ConsentQueryCriteria(null, null, boundary.page(), boundary.size());

        Page<ConsentDetails> result =
                switch (listing) {
                    case SUBJECT -> queries.listForUser(subject, criteria);
                    case PARTICIPANT ->
                            queries.listForParticipant(provider, subject.getIdentifier(), criteria);
                };

        assertThat(result.getContent()).hasSize(boundary.content());
        assertThat(result.getPageNumber()).isEqualTo(boundary.page());
        assertThat(result.getSize()).isEqualTo(boundary.appliedSize());
        assertThat(result.getTotalSize()).isEqualTo(boundary.seeded());
        assertThat(result.getTotalPages()).isEqualTo(boundary.totalPages());
    }

    private void seed(Participant provider, Participant consumer) {
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI + ":" + UUID.randomUUID(),
                                NOTICE_TITLE,
                                provider.getId(),
                                consumer.getId(),
                                new PrivacyNoticePayload(
                                        null, null, null, null, null, null, null, null, null)));
        consents.save(
                Consent.withStatus(
                        ConsentStatus.GRANTED,
                        subject.getId(),
                        notice.getId(),
                        provider.getId(),
                        consumer.getId(),
                        null,
                        notice.getContractUri(),
                        noTerms()));
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
}
