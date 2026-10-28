package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.service.ConsentReceiptIssuer;
import com.seamware.consentmanager.service.RecordedConsent;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.ConsentFixtures;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The ownership dimension of the authorization matrix: each id-addressed route called on the
 * caller's own record, on another principal's, and on none. A foreign record must answer as the
 * specification documents, stay untouched, and stay reachable by its owner, so a refusal is proven
 * to be ownership's and not a broken fixture's.
 */
@MicronautTest(transactional = false)
@DisplayName("Ownership matrix over the id-addressed routes")
class OwnershipMatrixIT extends KeycloakAndPostgresTestResource {

    /** Participant the realm's {@code PARTICIPANT} token acts as; owns the OWN records. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** Participant of the created second principal; owns the FOREIGN records. */
    private static final String FOREIGN_PARTICIPANT = "urn:test:participant:ownership-foreign";

    /** The other side of every seeded notice, never a caller. */
    private static final String COUNTERPARTY = "urn:test:participant:ownership-counterparty";

    private static final String ABSENT_USER = "urn:test:user:ownership-absent";

    private static final String ABSENT_PARTICIPANT = "urn:test:participant:ownership-absent";

    private static final String ABSENT_CONSENT = "0199a4f2-0002-7000-8000-000000000000";

    private static final String ABSENT_NOTICE = "0199a4f2-0003-7000-8000-000000000000";

    private static final String CONTRACT_URI = "https://contracts.example.org/contracts/ownership";

    private static final String NOTICE_TITLE = "Privacy notice the ownership matrix addresses";

    private static final String OWN_LOCAL_IDENTIFIER = "ownership-own-local";

    private static final String FOREIGN_LOCAL_IDENTIFIER = "ownership-foreign-local";

    /** The label a {@code PATCH} writes, so a write that lands on the foreign link shows. */
    private static final String RELABELLED = "ownership-relabelled";

    private static final String PATH_CONSENTS = "/consents";

    private static final String PATH_MY_CONSENTS = PATH_CONSENTS + "/me";

    private static final String PATH_LINKS = "/participants/me/users";

    private static final String QUERY_RECEIPT = "?format=receipt";

    private static final String QUERY_KANTARA = "?format=receipt-kantara";

    private static final String QUERY_LINEAGE = "?includeLineage=true";

    /** Retrieval as stored, so no case reaches for the Contract Service. */
    private static final String QUERY_UNRESOLVED = "?resolve=false";

    /** Templated routes whose parameter names no record, so ownership does not apply. */
    private static final Set<String> NOT_ID_ADDRESSED = Set.of("get /contracts/available/{role}");

    private static final String PATH_TEMPLATE_MARKER = "{";

    private static final String PROBLEM_JSON = "application/problem+json";

    /** The per-request member of a problem body, which no two answers share. */
    private static final Pattern CORRELATION_ID = Pattern.compile("\"correlationId\":\"[^\"]*\"");

    private static final String ADDRESSED_MASK = "<addressed>";

    private static final String CORRELATION_MASK = "\"correlationId\":\"<masked>\"";

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject UserParticipantRepository links;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject ConsentReceiptIssuer receiptIssuer;

    /**
     * Second principals owning the FOREIGN records; created once per JVM, as realm users persist.
     */
    private static final class Owners {
        static final Supplier<String> USER =
                KeycloakTestResource.createPrincipal(RealmPrincipal.USER, null);

        static final Supplier<String> PARTICIPANT =
                KeycloakTestResource.createPrincipal(
                        RealmPrincipal.PARTICIPANT, FOREIGN_PARTICIPANT);
    }

    /** The roles that address records; {@code OUTSIDER} and anonymous are the role matrix's. */
    enum Caller {
        USER(RealmPrincipal.USER),
        PARTICIPANT(RealmPrincipal.PARTICIPANT),
        CATALOG(RealmPrincipal.CATALOG);

        private final RealmPrincipal realmPrincipal;

        Caller(RealmPrincipal realmPrincipal) {
            this.realmPrincipal = realmPrincipal;
        }

        String token() {
            return KeycloakTestResource.accessToken(realmPrincipal);
        }

        /** A token, in this role, of whoever owns the FOREIGN records. */
        String foreignOwnerToken() {
            return switch (this) {
                case USER -> Owners.USER.get();
                case PARTICIPANT -> Owners.PARTICIPANT.get();
                case CATALOG -> token();
            };
        }
    }

    /** Whose record a case addresses. */
    enum Ownership {
        OWN,
        FOREIGN,
        ABSENT
    }

    /** What a case must answer; the listing outcomes also say whether the record shows. */
    enum Outcome {
        OK(HttpStatus.OK),
        EMPTIED(HttpStatus.NO_CONTENT),
        HIDDEN(HttpStatus.NOT_FOUND),
        REFUSED(HttpStatus.FORBIDDEN),
        LISTED(HttpStatus.OK),
        UNLISTED(HttpStatus.OK);

        private final HttpStatus status;

        Outcome(HttpStatus status) {
            this.status = status;
        }

        void assertAnswered(HttpResponse<String> response, Probe probe) {
            assertThat(response.code()).isEqualTo(status.getCode());
            if (this == HIDDEN || this == REFUSED) {
                assertThat(response.getContentType())
                        .hasValueSatisfying(
                                type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
            }
            if (this == LISTED) {
                assertThat(response.body()).contains(probe.consentId());
            }
            if (this == UNLISTED) {
                assertThat(response.body()).doesNotContain(probe.consentId());
            }
        }
    }

    /**
     * The values one case addresses, and the subject a catalog relays for (the realm user, except
     * in the owner's control call).
     */
    record Probe(
            String consentId,
            String noticeId,
            String userIdentifier,
            String participantIdentifier,
            String relayedSubject) {

        Probe relayedFor(String subject) {
            return new Probe(consentId, noticeId, userIdentifier, participantIdentifier, subject);
        }

        /** The body with this probe's values and the correlation id masked, so answers compare. */
        String normalised(String body) {
            String result = CORRELATION_ID.matcher(body).replaceAll(CORRELATION_MASK);
            for (String value :
                    Stream.of(consentId, noticeId, userIdentifier, participantIdentifier)
                            .filter(value -> value != null)
                            .toList()) {
                result = result.replace(value, ADDRESSED_MASK);
            }
            return result;
        }
    }

    /**
     * One request shape per id-addressed route and variant, with the consent status it needs seeded
     * ({@code null} when it addresses no consent).
     */
    enum Endpoint {
        GET_CONSENT("get /consents/{id}", "", ConsentStatus.GRANTED, p -> get(consent(p))),
        GET_CONSENT_RECEIPT(
                "get /consents/{id}",
                QUERY_RECEIPT,
                ConsentStatus.GRANTED,
                p -> get(consent(p) + QUERY_RECEIPT)),
        GET_CONSENT_KANTARA(
                "get /consents/{id}",
                QUERY_KANTARA,
                ConsentStatus.GRANTED,
                p -> get(consent(p) + QUERY_KANTARA)),
        GET_CONSENT_LINEAGE(
                "get /consents/{id}",
                QUERY_LINEAGE,
                ConsentStatus.GRANTED,
                p -> get(consent(p) + QUERY_LINEAGE)),
        GET_MY_CONSENT("get /consents/me/{id}", "", ConsentStatus.GRANTED, p -> get(mine(p))),
        GET_MY_CONSENT_RECEIPT(
                "get /consents/me/{id}",
                QUERY_RECEIPT,
                ConsentStatus.GRANTED,
                p -> get(mine(p) + QUERY_RECEIPT)),
        GET_MY_CONSENT_KANTARA(
                "get /consents/me/{id}",
                QUERY_KANTARA,
                ConsentStatus.GRANTED,
                p -> get(mine(p) + QUERY_KANTARA)),
        GET_MY_CONSENT_LINEAGE(
                "get /consents/me/{id}",
                QUERY_LINEAGE,
                ConsentStatus.GRANTED,
                p -> get(mine(p) + QUERY_LINEAGE)),
        REVOKE(
                "post /consents/{id}/revoke",
                "",
                ConsentStatus.GRANTED,
                p -> HttpRequest.POST(consent(p) + "/revoke", Map.of())),
        REFUSE(
                "post /consents/{id}/refuse",
                "",
                ConsentStatus.PENDING,
                p -> HttpRequest.POST(consent(p) + "/refuse", Map.of())),
        RE_CONFIRM(
                "post /consents/{id}/re-confirm",
                "",
                ConsentStatus.GRANTED,
                p -> HttpRequest.POST(consent(p) + "/re-confirm", Map.of())),
        TERMINATE(
                "post /consents/{id}/terminate",
                "",
                ConsentStatus.GRANTED,
                p -> HttpRequest.POST(consent(p) + "/terminate", Map.of())),
        RESUME(
                "post /consents/{id}/resume",
                "",
                ConsentStatus.PENDING,
                p ->
                        HttpRequest.POST(
                                consent(p) + "/resume",
                                Map.of("userIdentifier", p.relayedSubject()))),
        LIST_CONSENTS(
                "get /consents",
                "?userIdentifier",
                ConsentStatus.GRANTED,
                p -> get(PATH_CONSENTS + "?userIdentifier=" + p.userIdentifier())),
        CHECK_CONSENT(
                "get /consents/check",
                "",
                ConsentStatus.GRANTED,
                p ->
                        get(
                                PATH_CONSENTS
                                        + "/check?userIdentifier="
                                        + p.userIdentifier()
                                        + "&privacyNoticeId="
                                        + p.noticeId())),
        LIST_MY_CONSENTS(
                "get /consents/me",
                "?privacyNoticeId",
                ConsentStatus.GRANTED,
                p -> get(PATH_MY_CONSENTS + "?privacyNoticeId=" + p.noticeId())),
        GET_NOTICE(
                "get /privacy-notices/{id}",
                "",
                null,
                p -> get("/privacy-notices/" + p.noticeId() + QUERY_UNRESOLVED)),
        LOOKUP_USER("get /users/{identifier}", "", null, p -> get("/users/" + p.userIdentifier())),
        UPDATE_LINK(
                "patch /participants/me/users/{identifier}",
                "",
                null,
                p ->
                        HttpRequest.PATCH(
                                PATH_LINKS + "/" + p.userIdentifier(),
                                Map.of("localIdentifier", RELABELLED))),
        UNLINK(
                "delete /participants/me/users/{identifier}",
                "",
                null,
                p -> HttpRequest.DELETE(PATH_LINKS + "/" + p.userIdentifier())),
        LOOKUP_PARTICIPANT(
                "get /participants/{identifier}",
                "",
                null,
                p -> get("/participants/" + p.participantIdentifier()));

        private final String specification;
        private final String variant;
        private final ConsentStatus seeded;
        private final Function<Probe, MutableHttpRequest<?>> factory;

        Endpoint(
                String specification,
                String variant,
                ConsentStatus seeded,
                Function<Probe, MutableHttpRequest<?>> factory) {
            this.specification = specification;
            this.variant = variant;
            this.seeded = seeded;
            this.factory = factory;
        }

        MutableHttpRequest<?> request(Probe probe) {
            return factory.apply(probe);
        }

        String specification() {
            return specification;
        }

        @Override
        public String toString() {
            return specification + variant;
        }

        private static MutableHttpRequest<?> get(String uri) {
            return HttpRequest.GET(uri);
        }

        private static String consent(Probe probe) {
            return PATH_CONSENTS + "/" + probe.consentId();
        }

        private static String mine(Probe probe) {
            return PATH_MY_CONSENTS + "/" + probe.consentId();
        }
    }

    /**
     * Rows of (endpoint, caller, OWN, FOREIGN, ABSENT). Consents and links are scoped, so a foreign
     * one is the absent {@code 404}; notices and the directory are public by design.
     */
    static Stream<Arguments> ownershipMatrix() {
        Outcome ok = Outcome.OK;
        Outcome hidden = Outcome.HIDDEN;
        return Stream.of(
                        row(Endpoint.GET_CONSENT, Caller.PARTICIPANT, ok, hidden, hidden),
                        row(Endpoint.GET_CONSENT_RECEIPT, Caller.PARTICIPANT, ok, hidden, hidden),
                        row(Endpoint.GET_CONSENT_KANTARA, Caller.PARTICIPANT, ok, hidden, hidden),
                        row(Endpoint.GET_CONSENT_LINEAGE, Caller.PARTICIPANT, ok, hidden, hidden),
                        row(Endpoint.GET_MY_CONSENT, Caller.USER, ok, hidden, hidden),
                        row(Endpoint.GET_MY_CONSENT_RECEIPT, Caller.USER, ok, hidden, hidden),
                        row(Endpoint.GET_MY_CONSENT_KANTARA, Caller.USER, ok, hidden, hidden),
                        row(Endpoint.GET_MY_CONSENT_LINEAGE, Caller.USER, ok, hidden, hidden),
                        row(Endpoint.REVOKE, Caller.USER, ok, hidden, hidden),
                        row(Endpoint.REFUSE, Caller.USER, ok, hidden, hidden),
                        row(Endpoint.RE_CONFIRM, Caller.USER, ok, hidden, hidden),
                        // Documented in terminateConsent: a non-party is told so, not 404.
                        row(Endpoint.TERMINATE, Caller.PARTICIPANT, ok, Outcome.REFUSED, hidden),
                        row(Endpoint.RESUME, Caller.USER, ok, hidden, hidden),
                        row(Endpoint.RESUME, Caller.CATALOG, ok, hidden, hidden),
                        row(
                                Endpoint.LIST_CONSENTS,
                                Caller.PARTICIPANT,
                                Outcome.LISTED,
                                Outcome.UNLISTED,
                                Outcome.UNLISTED),
                        row(
                                Endpoint.CHECK_CONSENT,
                                Caller.PARTICIPANT,
                                Outcome.LISTED,
                                Outcome.UNLISTED,
                                Outcome.UNLISTED),
                        row(
                                Endpoint.LIST_MY_CONSENTS,
                                Caller.USER,
                                Outcome.LISTED,
                                Outcome.UNLISTED,
                                Outcome.UNLISTED),
                        row(Endpoint.GET_NOTICE, Caller.USER, ok, ok, hidden),
                        row(Endpoint.GET_NOTICE, Caller.PARTICIPANT, ok, ok, hidden),
                        row(Endpoint.GET_NOTICE, Caller.CATALOG, ok, ok, hidden),
                        row(Endpoint.LOOKUP_USER, Caller.PARTICIPANT, ok, hidden, hidden),
                        // The catalog's lookup is not scoped to any participant's links.
                        row(Endpoint.LOOKUP_USER, Caller.CATALOG, ok, ok, hidden),
                        row(Endpoint.UPDATE_LINK, Caller.PARTICIPANT, ok, hidden, hidden),
                        row(Endpoint.UNLINK, Caller.PARTICIPANT, Outcome.EMPTIED, hidden, hidden),
                        row(Endpoint.LOOKUP_PARTICIPANT, Caller.USER, ok, ok, hidden),
                        row(Endpoint.LOOKUP_PARTICIPANT, Caller.PARTICIPANT, ok, ok, hidden),
                        row(Endpoint.LOOKUP_PARTICIPANT, Caller.CATALOG, ok, ok, hidden))
                .flatMap(row -> row);
    }

    /** Expands one row into a case per ownership; each case also carries the OWN outcome. */
    private static Stream<Arguments> row(
            Endpoint endpoint, Caller caller, Outcome own, Outcome foreign, Outcome absent) {
        return Stream.of(
                Arguments.of(endpoint, caller, Ownership.OWN, own, own),
                Arguments.of(endpoint, caller, Ownership.FOREIGN, foreign, own),
                Arguments.of(endpoint, caller, Ownership.ABSENT, absent, own));
    }

    @BeforeEach
    void awaitTheRealm() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
    }

    @ParameterizedTest(name = "{1} on {0}, {2} record: {3}")
    @MethodSource("ownershipMatrix")
    @DisplayName("whose record it is decides the answer, and a foreign one is never touched")
    void ownershipDecidesTheAnswer(
            Endpoint endpoint, Caller caller, Ownership ownership, Outcome expected, Outcome own) {
        Fixture fixture = seed(endpoint);
        Probe probe = fixture.probe(ownership);
        String foreignBefore = fixture.foreignState();

        HttpResponse<String> response = exchange(endpoint.request(probe), caller.token());

        expected.assertAnswered(response, probe);
        if (ownership != Ownership.FOREIGN) {
            return;
        }
        assertThat(fixture.foreignState())
                .as("%s on another principal's record must leave it as it was", endpoint)
                .isEqualTo(foreignBefore);
        if (expected == Outcome.HIDDEN) {
            Probe absent = fixture.probe(Ownership.ABSENT);
            HttpResponse<String> absentResponse =
                    exchange(endpoint.request(absent), caller.token());
            assertThat(probe.normalised(response.body()))
                    .as("a foreign record must be indistinguishable from an absent one")
                    .isEqualTo(absent.normalised(absentResponse.body()));
        }
        Probe owned = probe.relayedFor(fixture.foreignSubject());
        own.assertAnswered(exchange(endpoint.request(owned), caller.foreignOwnerToken()), owned);
    }

    @Test
    @DisplayName("every operation addressing a record by path has an ownership row")
    void everyIdAddressedOperationHasARow() {
        Set<String> covered =
                ownershipMatrix()
                        .map(arguments -> ((Endpoint) arguments.get()[0]).specification())
                        .collect(Collectors.toSet());
        List<String> addressed =
                SpecSecurityConsistencyTest.specOperations()
                        .map(operation -> operation.httpMethod() + " " + operation.path())
                        .filter(specification -> specification.contains(PATH_TEMPLATE_MARKER))
                        .filter(specification -> !NOT_ID_ADDRESSED.contains(specification))
                        .toList();

        assertThat(addressed).isNotEmpty();
        assertThat(covered)
                .as("a route addressing a record by id with no row here skips the ownership matrix")
                .containsAll(addressed);
    }

    @Test
    @DisplayName("every ownership row expects a status its operation documents")
    void everyExpectedStatusIsDocumented() {
        ownershipMatrix()
                .forEach(
                        arguments -> {
                            Endpoint endpoint = (Endpoint) arguments.get()[0];
                            Outcome expected = (Outcome) arguments.get()[3];
                            String[] parts = endpoint.specification().split(" ", 2);
                            assertThat(
                                            SpecSecurityConsistencyTest.documentedStatuses(
                                                    parts[0], parts[1]))
                                    .as("%s does not document %s", endpoint, expected)
                                    .contains(String.valueOf(expected.status.getCode()));
                        });
    }

    /** The seeded records, as each ownership addresses them. */
    private record Fixture(
            Probe own,
            Probe foreign,
            Probe absent,
            Supplier<String> foreignStateReader,
            String foreignSubject) {

        Probe probe(Ownership ownership) {
            return switch (ownership) {
                case OWN -> own;
                case FOREIGN -> foreign;
                case ABSENT -> absent;
            };
        }

        /** The foreign consent's status and the foreign user's links, for a before/after diff. */
        String foreignState() {
            return foreignStateReader.get();
        }
    }

    /**
     * Two of everything: the realm user and participant own one notice, link and consent; the
     * created principals own the other. Consents are seeded only when the endpoint addresses one.
     */
    private Fixture seed(Endpoint endpoint) {
        User subject = provision(Caller.USER.token());
        User foreignSubject = provision(Owners.USER.get());
        Participant caller = ConsentFixtures.participant(participants, CALLER_PARTICIPANT);
        Participant foreign = ConsentFixtures.participant(participants, FOREIGN_PARTICIPANT);
        Participant counterparty = ConsentFixtures.participant(participants, COUNTERPARTY);
        PrivacyNotice ownNotice = notice(caller, counterparty);
        PrivacyNotice foreignNotice = notice(foreign, counterparty);
        links.save(new UserParticipant(subject.getId(), caller.getId(), OWN_LOCAL_IDENTIFIER));
        links.save(
                new UserParticipant(
                        foreignSubject.getId(), foreign.getId(), FOREIGN_LOCAL_IDENTIFIER));
        String ownConsent = consentOf(endpoint, subject, ownNotice);
        String foreignConsent = consentOf(endpoint, foreignSubject, foreignNotice);
        String relayed = subject.getIdentifier();
        return new Fixture(
                new Probe(
                        ownConsent,
                        ownNotice.getId().toString(),
                        subject.getIdentifier(),
                        CALLER_PARTICIPANT,
                        relayed),
                new Probe(
                        foreignConsent,
                        foreignNotice.getId().toString(),
                        foreignSubject.getIdentifier(),
                        FOREIGN_PARTICIPANT,
                        relayed),
                new Probe(ABSENT_CONSENT, ABSENT_NOTICE, ABSENT_USER, ABSENT_PARTICIPANT, relayed),
                () -> stateOf(foreignConsent, foreignSubject),
                foreignSubject.getIdentifier());
    }

    /**
     * The foreign record as a caller could change it: status, audit events and links. The events
     * count because re-confirm leaves the consent row as it was and only appends one.
     */
    private String stateOf(String consentId, User subject) {
        String status =
                consentId == null
                        ? "no consent"
                        : consents.findById(UUID.fromString(consentId))
                                .map(Consent::getStatus)
                                .map(String::valueOf)
                                .orElse("deleted");
        String audited =
                consentId == null
                        ? ""
                        : events
                                .findByConsentIdOrderByOccurredAtAsc(UUID.fromString(consentId))
                                .stream()
                                .map(
                                        event ->
                                                event.getEventState()
                                                        + "/"
                                                        + event.getEventType()
                                                        + " by "
                                                        + event.getActor())
                                .sorted()
                                .collect(Collectors.joining(","));
        String linked =
                links.findByIdUserId(subject.getId()).stream()
                        .map(link -> link.getParticipantId() + "=" + link.getLocalIdentifier())
                        .sorted()
                        .collect(Collectors.joining(","));
        return status + "; events " + audited + "; links " + linked;
    }

    /** Provisions the token's {@code users} row through one authenticated call, as JIT does. */
    private User provision(String token) {
        assertThat(exchange(HttpRequest.GET("/users/me"), token).code())
                .isEqualTo(HttpStatus.OK.getCode());
        return users.findByIdentifier(KeycloakTestResource.subject(token)).orElseThrow();
    }

    private PrivacyNotice notice(Participant provider, Participant consumer) {
        return notices.save(
                new PrivacyNotice(
                        CONTRACT_URI,
                        NOTICE_TITLE,
                        provider.getId(),
                        consumer.getId(),
                        PrivacyNoticeFixtures.emptyPayload()));
    }

    /**
     * A consent in the endpoint's status, or {@code null} when it addresses none. A grant also gets
     * its granting event and stored receipt, as the grant flow writes them.
     */
    private String consentOf(Endpoint endpoint, User subject, PrivacyNotice under) {
        if (endpoint.seeded == null) {
            return null;
        }
        Consent consent =
                consents.save(
                        Consent.withStatus(
                                endpoint.seeded,
                                subject.getId(),
                                under.getId(),
                                under.getProviderId(),
                                under.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                ConsentSnapshot.of(under, null, null)));
        if (endpoint.seeded == ConsentStatus.GRANTED) {
            ConsentEvent given =
                    events.save(
                            new ConsentEvent(
                                    consent.getId(),
                                    ConsentEventState.CONSENT_GIVEN,
                                    ConsentEventTrigger.EXPLICIT.eventType(),
                                    subject.getIdentifier(),
                                    Instant.now().truncatedTo(ChronoUnit.MICROS),
                                    null));
            receiptIssuer.issue(new RecordedConsent(consent, given, true));
        }
        return consent.getId().toString();
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
