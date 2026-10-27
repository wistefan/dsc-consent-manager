package com.seamware.consentmanager.observability;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.service.ConsentStateMachine;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.ConsentFixtures;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drives every consent move an HTTP route can make and asserts it increments exactly its {@code
 * (from,to)} counter while a refused write increments nothing; counters are process-wide, so each
 * case compares deltas.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent transition metrics")
class ConsentMetricsIT extends KeycloakAndPostgresTestResource {

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    /** Longest either racer may take once released. */
    private static final Duration RACE_TIMEOUT = Duration.ofSeconds(30);

    /** Number of concurrent callers in a race. */
    private static final int RACERS = 2;

    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String COUNTERPARTY = "urn:test:participant:metrics-counterparty";

    private static final String CONTRACT_URI = "https://contract.example.org/v1/contracts/metrics";

    private static final String NOTICE_TITLE = "Privacy notice the metrics cases move consents on";

    private static final String LOCAL_IDENTIFIER = "metrics-local";

    private static final String PATH_CONSENTS = "/consents";

    private static final String PATH_CONSENT_REQUESTS = "/consents/requests";

    private static final String PATH_USERS_ME = "/users/me";

    private static final String PATH_PARTICIPANTS_ME = "/participants/me";

    private static final String REVOKE = "revoke";

    private static final String REFUSE = "refuse";

    private static final String TERMINATE = "terminate";

    private static final String RESUME = "resume";

    private static final String PROPERTY_NOTICE = "privacyNoticeId";

    private static final String PROPERTY_DECISION = "decision";

    private static final String PROPERTY_SUBJECT = "userIdentifier";

    /** Routes carrying no request body still need one to post. */
    private static final Map<String, Object> NO_BODY = Map.of();

    private static final String NONE = ConsentMetrics.NO_STATUS;

    /** State-table edges no code path makes, e.g. GRANTED → EXPIRED: nothing expires a consent. */
    private static final Set<Edge> NOT_YET_REACHABLE =
            Set.of(new Edge(ConsentStatus.GRANTED.name(), ConsentStatus.EXPIRED.name()));

    @Inject
    @Client("/")
    HttpClient client;

    @Inject MeterRegistry meters;

    @Inject ConsentStateMachine states;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject UserParticipantRepository links;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    /** The subject's global identifier, resolved once because reading it costs a token request. */
    private String identifier;

    /** The notice every consent of a case is recorded against. */
    private PrivacyNotice notice;

    /** The subject's current row id. */
    private UUID subject;

    /** A status change counted under its {@code from} and {@code to} tags. */
    record Edge(String from, String to) {

        static Edge of(ConsentStatus from, ConsentStatus to) {
            return new Edge(from.name(), to.name());
        }

        static Edge created(ConsentStatus to) {
            return new Edge(NONE, to.name());
        }

        @Override
        public String toString() {
            return from + "->" + to;
        }
    }

    /** One committed write driven over HTTP, and the counters it must move by exactly one each. */
    record Case(String name, Consumer<ConsentMetricsIT> drive, Map<Edge, Long> expected) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> committedWrites() {
        return Stream.of(
                new Case(
                        "a participant's request creates a PENDING",
                        it -> it.expectCode(it.request(), HttpStatus.CREATED),
                        Map.of(Edge.created(ConsentStatus.PENDING), 1L)),
                new Case(
                        "a grant with no open request creates a GRANTED",
                        it -> it.expectCode(it.decide(ConsentStatus.GRANTED), HttpStatus.CREATED),
                        Map.of(Edge.created(ConsentStatus.GRANTED), 1L)),
                new Case(
                        "a refusal with no open request creates a REFUSED",
                        it -> it.expectCode(it.decide(ConsentStatus.REFUSED), HttpStatus.CREATED),
                        Map.of(Edge.created(ConsentStatus.REFUSED), 1L)),
                new Case(
                        "a grant answers the open request",
                        it -> {
                            it.seed(ConsentStatus.PENDING);
                            it.expectCode(it.decide(ConsentStatus.GRANTED), HttpStatus.OK);
                        },
                        Map.of(Edge.of(ConsentStatus.PENDING, ConsentStatus.GRANTED), 1L)),
                new Case(
                        "a refusal answers the open request",
                        it -> {
                            it.seed(ConsentStatus.PENDING);
                            it.expectCode(it.decide(ConsentStatus.REFUSED), HttpStatus.OK);
                        },
                        Map.of(Edge.of(ConsentStatus.PENDING, ConsentStatus.REFUSED), 1L)),
                new Case(
                        "a superseding grant revokes the open grant",
                        it -> {
                            it.seed(ConsentStatus.GRANTED);
                            it.expectCode(it.decide(ConsentStatus.GRANTED), HttpStatus.CREATED);
                        },
                        Map.of(
                                Edge.of(ConsentStatus.GRANTED, ConsentStatus.REVOKED), 1L,
                                Edge.created(ConsentStatus.GRANTED), 1L)),
                new Case(
                        "a grant over two open requests closes the redundant one",
                        it -> {
                            it.seed(ConsentStatus.PENDING);
                            it.seed(ConsentStatus.PENDING);
                            it.expectCode(it.decide(ConsentStatus.GRANTED), HttpStatus.OK);
                        },
                        Map.of(
                                Edge.of(ConsentStatus.PENDING, ConsentStatus.GRANTED), 1L,
                                Edge.of(ConsentStatus.PENDING, ConsentStatus.TERMINATED), 1L)),
                lifecycle(REVOKE, ConsentStatus.GRANTED, ConsentStatus.REVOKED),
                lifecycle(REFUSE, ConsentStatus.PENDING, ConsentStatus.REFUSED),
                lifecycle(REFUSE, ConsentStatus.DRAFT, ConsentStatus.REFUSED),
                lifecycle(RESUME, ConsentStatus.PENDING, ConsentStatus.GRANTED),
                lifecycle(RESUME, ConsentStatus.DRAFT, ConsentStatus.GRANTED),
                lifecycle(TERMINATE, ConsentStatus.GRANTED, ConsentStatus.TERMINATED),
                lifecycle(TERMINATE, ConsentStatus.PENDING, ConsentStatus.TERMINATED),
                lifecycle(TERMINATE, ConsentStatus.DRAFT, ConsentStatus.TERMINATED),
                new Case(
                        "an erasure revokes the grant and terminates the unanswered offers",
                        it -> {
                            it.seed(ConsentStatus.GRANTED);
                            it.seed(ConsentStatus.PENDING);
                            it.seed(ConsentStatus.DRAFT);
                            it.expectCode(it.erase(), HttpStatus.OK);
                        },
                        Map.of(
                                Edge.of(ConsentStatus.GRANTED, ConsentStatus.REVOKED), 1L,
                                Edge.of(ConsentStatus.PENDING, ConsentStatus.TERMINATED), 1L,
                                Edge.of(ConsentStatus.DRAFT, ConsentStatus.TERMINATED), 1L)),
                new Case(
                        "a deregistration terminates the unanswered offers naming it",
                        it -> {
                            it.seed(ConsentStatus.PENDING);
                            it.seed(ConsentStatus.DRAFT);
                            it.expectCode(it.deregister(), HttpStatus.OK);
                        },
                        Map.of(
                                Edge.of(ConsentStatus.PENDING, ConsentStatus.TERMINATED), 1L,
                                Edge.of(ConsentStatus.DRAFT, ConsentStatus.TERMINATED), 1L)));
    }

    static Stream<Case> refusedWrites() {
        return Stream.of(
                new Case(
                        "a revocation of an unanswered request",
                        it -> {
                            UUID pending = it.seed(ConsentStatus.PENDING);
                            it.expectCode(it.move(REVOKE, pending), HttpStatus.BAD_REQUEST);
                        },
                        Map.of()),
                new Case(
                        "a deregistration while a grant is open",
                        it -> {
                            it.seed(ConsentStatus.GRANTED);
                            it.seed(ConsentStatus.PENDING);
                            it.expectCode(it.deregister(), HttpStatus.CONFLICT);
                        },
                        Map.of()));
    }

    /** A lifecycle route moving a seeded consent from {@code held} to {@code reached}. */
    private static Case lifecycle(String verb, ConsentStatus held, ConsentStatus reached) {
        return new Case(
                verb + " moves a " + held,
                it -> it.expectCode(it.move(verb, it.seed(held)), HttpStatus.OK),
                Map.of(Edge.of(held, reached), 1L));
    }

    @BeforeEach
    void seedTheNoticeAndTheAffiliatedSubject() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        identifier = KeycloakTestResource.subject(userToken());
        Participant caller = ConsentFixtures.participant(participants, CALLER_PARTICIPANT);
        Participant counterparty = ConsentFixtures.participant(participants, COUNTERPARTY);
        notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                NOTICE_TITLE,
                                caller.getId(),
                                counterparty.getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        subject = provisionTheSubject();
        links.save(new UserParticipant(subject, caller.getId(), LOCAL_IDENTIFIER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("committedWrites")
    @DisplayName("counts each committed move once under its own (from,to)")
    void countsEachCommittedMove(Case write) {
        Map<Edge, Long> before = transitions();

        write.drive().accept(this);

        assertThat(delta(before, transitions())).isEqualTo(write.expected());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedWrites")
    @DisplayName("counts nothing for a refused write")
    void countsNothingForARefusedWrite(Case write) {
        Map<Edge, Long> before = transitions();

        write.drive().accept(this);

        assertThat(delta(before, transitions())).isEmpty();
    }

    /** An edge wrongly listed in {@link #NOT_YET_REACHABLE} is not caught; list only dead ones. */
    @Test
    @DisplayName("drives every edge of the state table except those no code path makes")
    void drivesEveryEdgeOfTheStateTable() {
        Set<Edge> driven =
                committedWrites()
                        .flatMap(write -> write.expected().keySet().stream())
                        .collect(Collectors.toSet());
        Set<Edge> table = new HashSet<>();
        Arrays.stream(ConsentStatus.values())
                .forEach(
                        to -> {
                            if (states.permitsCreation(to)) {
                                table.add(Edge.created(to));
                            }
                            Arrays.stream(ConsentStatus.values())
                                    .filter(from -> states.permits(from, to))
                                    .forEach(from -> table.add(Edge.of(from, to)));
                        });

        assertThat(driven).doesNotContainAnyElementsOf(NOT_YET_REACHABLE);
        Set<Edge> covered = new HashSet<>(driven);
        covered.addAll(NOT_YET_REACHABLE);
        assertThat(covered).isEqualTo(table);
    }

    /** The second grant supersedes the first, or loses on the one-grant index and counts none. */
    @Test
    @DisplayName("counts only what committed when two grants race on an empty pair")
    void countsOnlyWhatCommittedWhenTwoGrantsRace() throws Exception {
        Map<Edge, Long> before = transitions();

        List<HttpResponse<String>> answers =
                race(() -> decide(ConsentStatus.GRANTED), () -> decide(ConsentStatus.GRANTED));

        long conflicts =
                answers.stream().filter(a -> a.code() == HttpStatus.CONFLICT.getCode()).count();
        Map<Edge, Long> expected =
                conflicts == 1
                        ? Map.of(Edge.created(ConsentStatus.GRANTED), 1L)
                        : Map.of(
                                Edge.created(ConsentStatus.GRANTED), 2L,
                                Edge.of(ConsentStatus.GRANTED, ConsentStatus.REVOKED), 1L);
        assertThat(delta(before, transitions())).isEqualTo(expected);
    }

    /** Both writers lock the row, so the second reads what the first left. */
    @Test
    @DisplayName("counts exactly one move out of a PENDING an erasure and a grant race for")
    void countsOneMoveOutOfARacedPending() throws Exception {
        UUID pending = seed(ConsentStatus.PENDING);
        Map<Edge, Long> before = transitions();

        race(this::erase, () -> decide(ConsentStatus.GRANTED));

        Map<Edge, Long> moved = delta(before, transitions());
        long granted =
                moved.getOrDefault(Edge.of(ConsentStatus.PENDING, ConsentStatus.GRANTED), 0L);
        long terminated =
                moved.getOrDefault(Edge.of(ConsentStatus.PENDING, ConsentStatus.TERMINATED), 0L);
        assertThat(granted + terminated).as("moves out of the raced PENDING").isEqualTo(1L);
        ConsentStatus expected = granted == 1L ? ConsentStatus.REVOKED : ConsentStatus.TERMINATED;
        assertThat(consents.findById(pending).orElseThrow().getStatus())
                .as("the stored status is what the last committed move left")
                .isEqualTo(expected);
        if (granted == 1L) {
            assertThat(moved)
                    .as("the erasure, committing second, revoked the grant it found")
                    .containsEntry(Edge.of(ConsentStatus.GRANTED, ConsentStatus.REVOKED), 1L);
        }
    }

    /** Deregistration locks the row before counting grants, so it terminates or refuses. */
    @Test
    @DisplayName("never deregisters past a grant a racing decision committed")
    void neverDeregistersPastARacedGrant() throws Exception {
        UUID pending = seed(ConsentStatus.PENDING);
        Map<Edge, Long> before = transitions();

        HttpResponse<String> deregistration =
                race(this::deregister, () -> decide(ConsentStatus.GRANTED)).getFirst();

        Map<Edge, Long> moved = delta(before, transitions());
        long granted =
                moved.getOrDefault(Edge.of(ConsentStatus.PENDING, ConsentStatus.GRANTED), 0L);
        long terminated =
                moved.getOrDefault(Edge.of(ConsentStatus.PENDING, ConsentStatus.TERMINATED), 0L);
        assertThat(granted + terminated).as("moves out of the raced PENDING").isEqualTo(1L);
        boolean deregistered = deregistration.code() == HttpStatus.OK.getCode();
        assertThat(deregistered || deregistration.code() == HttpStatus.CONFLICT.getCode())
                .as("deregistration answered %d", deregistration.code())
                .isTrue();
        assertThat(consents.findById(pending).orElseThrow().getStatus())
                .as("a successful deregistration terminated the row, a refused one left the grant")
                .isEqualTo(deregistered ? ConsentStatus.TERMINATED : ConsentStatus.GRANTED);
    }

    /** Releases both callers together and waits for both answers. */
    private static List<HttpResponse<String>> race(
            Supplier<HttpResponse<String>> first, Supplier<HttpResponse<String>> second)
            throws Exception {
        CyclicBarrier start = new CyclicBarrier(RACERS);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            List<CompletableFuture<HttpResponse<String>>> racers =
                    Stream.of(first, second)
                            .map(
                                    caller ->
                                            CompletableFuture.supplyAsync(
                                                    () -> {
                                                        await(start);
                                                        return caller.get();
                                                    },
                                                    pool))
                            .toList();
            CompletableFuture.allOf(racers.toArray(CompletableFuture[]::new))
                    .get(RACE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return racers.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(RACE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("A racer was never released", e);
        }
    }

    /** Every transition counter now registered, by edge. */
    private Map<Edge, Long> transitions() {
        return meters.find(ConsentMetrics.TRANSITIONS_METRIC).counters().stream()
                .collect(
                        Collectors.toMap(
                                counter ->
                                        new Edge(
                                                counter.getId().getTag(ConsentMetrics.FROM_TAG),
                                                counter.getId().getTag(ConsentMetrics.TO_TAG)),
                                ConsentMetricsIT::count));
    }

    private static long count(Counter counter) {
        return (long) counter.count();
    }

    /** The edges whose counter moved between the two readings, and by how much. */
    private static Map<Edge, Long> delta(Map<Edge, Long> before, Map<Edge, Long> after) {
        Map<Edge, Long> moved = new HashMap<>();
        after.forEach(
                (edge, now) -> {
                    long change = now - before.getOrDefault(edge, 0L);
                    if (change != 0) {
                        moved.put(edge, change);
                    }
                });
        return moved;
    }

    /** Writes a consent for the subject straight to the table, as no route creates a DRAFT. */
    private UUID seed(ConsentStatus status) {
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject,
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }

    /** Provisions the subject from its token and returns the row it was given. */
    private UUID provisionTheSubject() {
        expectCode(exchange(HttpRequest.GET(PATH_USERS_ME), userToken()), HttpStatus.OK);
        return users.findByIdentifier(identifier).orElseThrow().getId();
    }

    private void expectCode(HttpResponse<String> response, HttpStatus expected) {
        assertThat(response.code()).as(response.body()).isEqualTo(expected.getCode());
    }

    private HttpResponse<String> request() {
        return exchange(
                HttpRequest.POST(
                        PATH_CONSENT_REQUESTS,
                        Map.of(
                                PROPERTY_NOTICE,
                                notice.getId().toString(),
                                PROPERTY_SUBJECT,
                                identifier)),
                participantToken());
    }

    private HttpResponse<String> decide(ConsentStatus decision) {
        return exchange(
                HttpRequest.POST(
                        PATH_CONSENTS,
                        Map.of(
                                PROPERTY_NOTICE,
                                notice.getId().toString(),
                                PROPERTY_DECISION,
                                decision.name())),
                userToken());
    }

    /** Posts a lifecycle move as whoever the route admits: the participant for a termination. */
    private HttpResponse<String> move(String verb, UUID consentId) {
        String token = TERMINATE.equals(verb) ? participantToken() : userToken();
        return exchange(
                HttpRequest.POST(PATH_CONSENTS + "/" + consentId + "/" + verb, NO_BODY), token);
    }

    private HttpResponse<String> erase() {
        return exchange(HttpRequest.DELETE(PATH_USERS_ME), userToken());
    }

    private HttpResponse<String> deregister() {
        return exchange(HttpRequest.DELETE(PATH_PARTICIPANTS_ME), participantToken());
    }

    private static String userToken() {
        return KeycloakTestResource.accessToken(RealmPrincipal.USER);
    }

    private static String participantToken() {
        return KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT);
    }

    /** Issues the request, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request, String token) {
        try {
            return client.toBlocking().exchange(request.bearerAuth(token), String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }
}
