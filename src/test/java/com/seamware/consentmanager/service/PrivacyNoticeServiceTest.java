package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.client.ContractGateway;
import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.support.ContractFixtures;
import io.micronaut.cache.CacheManager;
import io.micronaut.cache.SyncCache;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers the rules {@link PrivacyNoticeService} enforces before it writes, over both contract
 * shapes, plus the two branches {@link PrivacyNoticeGenerationIT} cannot force deterministically:
 * the write that fails because a concurrent generation won the slot, and the write that fails for
 * any other reason and must not be reported as "nothing changed".
 */
@DisplayName("Privacy notice service")
class PrivacyNoticeServiceTest {

    /** Issuer every principal in this test carries; generation does not read it. */
    private static final String ISSUER = "https://idp.example.org/realms/test";

    /** Subject every principal in this test carries; generation does not read it either. */
    private static final String SUBJECT = "privacy-notice-service-test";

    /** Provider both fixtures name, and the caller in every case that is not about the caller. */
    private static final String PROVIDER = "did:web:provider.example.org";

    /** Consumer both fixtures name. */
    private static final String CONSUMER = "did:web:consumer.example.org";

    /** A registered participant neither fixture binds. */
    private static final String OUTSIDER = "did:web:outsider.example.org";

    /** Identifier no participant is registered under. */
    private static final String UNREGISTERED = "did:web:never-registered.example.org";

    /** Contract URI every case fetches under; the gateway stub ignores it. */
    private static final String CONTRACT_URI = "https://contract.example.org/v1/contracts/c-1";

    private final StubParticipantRepository participants = new StubParticipantRepository();

    private final StubPrivacyNoticeRepository notices = new StubPrivacyNoticeRepository();

    private final StubContractGateway contracts = new StubContractGateway();

    private final ContractContentExtractor extractor = new ContractContentExtractor();

    private final StubWriter writer = new StubWriter();

    private final PrivacyNoticeService service =
            new PrivacyNoticeService(participants, notices, contracts, extractor, writer);

    private final Participant provider = register(PROVIDER);

    private final Participant consumer = register(CONSUMER);

    private final Participant outsider = register(OUTSIDER);

    /**
     * Who each contract shape binds, which is the check {@code PrivacyNoticeGenerationIT} can only
     * reach through the ecosystem endpoint.
     */
    static List<Arguments> bindingMatrix() {
        return List.of(
                Arguments.of(
                        "an ecosystem contract binding both parties",
                        ContractFixtures.ECOSYSTEM,
                        PROVIDER,
                        CONSUMER,
                        true),
                Arguments.of(
                        "a bilateral contract binding both parties",
                        ContractFixtures.BILATERAL,
                        PROVIDER,
                        CONSUMER,
                        true),
                Arguments.of(
                        "a bilateral contract naming another consumer",
                        ContractFixtures.BILATERAL,
                        PROVIDER,
                        OUTSIDER,
                        false),
                Arguments.of(
                        "a bilateral contract naming another provider",
                        ContractFixtures.BILATERAL,
                        OUTSIDER,
                        CONSUMER,
                        false),
                Arguments.of(
                        "a bilateral contract with its parties the wrong way round",
                        ContractFixtures.BILATERAL,
                        CONSUMER,
                        PROVIDER,
                        false),
                Arguments.of(
                        "an ecosystem contract that is no member's",
                        ContractFixtures.ECOSYSTEM,
                        PROVIDER,
                        OUTSIDER,
                        false),
                Arguments.of(
                        "an ecosystem contract whose consumer offers the service",
                        ContractFixtures.ECOSYSTEM,
                        CONSUMER,
                        PROVIDER,
                        false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindingMatrix")
    @DisplayName("generates only for the parties the fetched contract binds")
    void generatesOnlyForBoundParties(
            String description,
            String fixture,
            String declaredProvider,
            String declaredConsumer,
            boolean bound) {
        contracts.serve(ContractFixtures.contract(fixture));

        if (bound) {
            assertThat(generate(declaredProvider, declaredProvider, declaredConsumer).created())
                    .as(description)
                    .isTrue();
        } else {
            assertThatThrownBy(() -> generate(declaredProvider, declaredProvider, declaredConsumer))
                    .as(description)
                    .isInstanceOf(BadRequestException.class);
            assertThat(notices.rows()).isEmpty();
        }
    }

    @Test
    @DisplayName("refuses one participant named on both sides")
    void refusesAParticipantNamedAsBothParties() {
        contracts.serve(ContractFixtures.contract(ContractFixtures.ECOSYSTEM));

        assertThatThrownBy(() -> generate(PROVIDER, PROVIDER, PROVIDER))
                .as(
                        "an ecosystem contract binds its provider as a member too, so nothing else"
                                + " would refuse a notice saying a participant consumes its own data")
                .isInstanceOf(BadRequestException.class);
        assertThat(notices.rows()).isEmpty();
    }

    @Test
    @DisplayName("refuses a caller that is neither party before it reads the contract")
    void refusesACallerThatIsNeitherParty() {
        assertThatThrownBy(() -> generate(OUTSIDER, PROVIDER, CONSUMER))
                .isInstanceOf(ForbiddenException.class);
        assertThat(contracts.fetches()).isZero();
    }

    @Test
    @DisplayName("refuses a deregistered caller whatever it names")
    void refusesADeregisteredCaller() {
        provider.setDeregisteredAt(Instant.now());

        assertThatThrownBy(() -> generate(PROVIDER, PROVIDER, CONSUMER))
                .isInstanceOf(ForbiddenException.class);
        assertThat(contracts.fetches()).isZero();
    }

    @ParameterizedTest(name = "an unregistered {0} is not found")
    @MethodSource("unregisteredParties")
    @DisplayName("refuses a party no participant is registered under")
    void refusesAnUnregisteredParty(String role, String declaredProvider, String declaredConsumer) {
        contracts.serve(ContractFixtures.contract(ContractFixtures.ECOSYSTEM));

        assertThatThrownBy(() -> generate(PROVIDER, declaredProvider, declaredConsumer))
                .as(role)
                .isInstanceOf(NotFoundException.class);
    }

    static List<Arguments> unregisteredParties() {
        return List.of(
                Arguments.of("data provider", UNREGISTERED, CONSUMER),
                Arguments.of("data consumer", PROVIDER, UNREGISTERED));
    }

    @Test
    @DisplayName("a contract the upstream does not hold is not found")
    void refusesAnUnknownContract() {
        contracts.serveNothing();

        assertThatThrownBy(() -> generate(PROVIDER, PROVIDER, CONSUMER))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("regenerating an unchanged contract writes nothing and reports the notice held")
    void regenerationOfAnUnchangedContractWritesNothing() {
        contracts.serve(ContractFixtures.contract(ContractFixtures.ECOSYSTEM));
        GeneratedNotice first = generate(PROVIDER, PROVIDER, CONSUMER);

        GeneratedNotice second = generate(PROVIDER, PROVIDER, CONSUMER);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.notice().getId()).isEqualTo(first.notice().getId());
        assertThat(notices.rows()).hasSize(1);
    }

    @Test
    @DisplayName("the loser of the race is served the winner's notice")
    void aLostRaceIsServedTheWinnersNotice() {
        contracts.serve(ContractFixtures.contract(ContractFixtures.ECOSYSTEM));
        PrivacyNotice winner = whateverThisCallWouldHaveWritten();
        writer.failAfter(winner);

        GeneratedNotice served = generate(PROVIDER, PROVIDER, CONSUMER);

        assertThat(served.created()).as("the winner wrote it, so this call did not").isFalse();
        assertThat(served.notice().getId()).isEqualTo(winner.getId());
    }

    @Test
    @DisplayName("a write failure that is not the race is raised, not reported as unchanged")
    void aWriteFailureThatIsNotTheRaceIsRaised() {
        contracts.serve(ContractFixtures.contract(ContractFixtures.ECOSYSTEM));
        PrivacyNotice stale = notices.put(supersededNotice());
        writer.failAfter(null);

        assertThatThrownBy(() -> generate(PROVIDER, PROVIDER, CONSUMER))
                .as(
                        "the row left behind says something else, so answering 200 would drop the"
                                + " change the caller asked for and report success")
                .isInstanceOf(DataAccessException.class);
        assertThat(notices.rows().get(stale.getId()).getArchivedAt()).isNull();
    }

    /** The notice a generation would store right now, used to stand in for a race winner. */
    private PrivacyNotice whateverThisCallWouldHaveWritten() {
        NoticeContent content =
                extractor.extract(contracts.fetch(CONTRACT_URI).orElseThrow(), provider);
        return new PrivacyNotice(
                CONTRACT_URI,
                content.title(),
                provider.getId(),
                consumer.getId(),
                content.payload());
    }

    /** An active notice for the same contract and provider that says something else entirely. */
    private PrivacyNotice supersededNotice() {
        return new PrivacyNotice(
                CONTRACT_URI,
                "An earlier title",
                provider.getId(),
                consumer.getId(),
                new PrivacyNoticePayload(null, null, null, null, null, null, null, null, null));
    }

    private GeneratedNotice generate(
            String caller, String declaredProvider, String declaredConsumer) {
        return service.generate(
                principal(caller), CONTRACT_URI, declaredProvider, declaredConsumer);
    }

    private ParticipantPrincipal principal(String identifier) {
        return new ParticipantPrincipal(
                ISSUER,
                SUBJECT,
                identifier,
                participants.findByIdentifier(identifier).orElse(null));
    }

    private Participant register(String identifier) {
        return participants.put(
                new Participant(identifier, identifier, null, null, Map.of(), null));
    }

    /** A gateway serving whatever a case armed it with, counting what generation asked it for. */
    private static final class StubContractGateway extends ContractGateway {

        @Nullable private ContractResponse contract;

        private int fetches;

        private StubContractGateway() {
            super(null, new NoCacheManager(), new ConsentManagerConfiguration.ContractService());
        }

        void serve(ContractResponse served) {
            this.contract = served;
        }

        /** Arms the "no contract is held under this URI" answer. */
        void serveNothing() {
            this.contract = null;
        }

        int fetches() {
            return fetches;
        }

        @Override
        public Optional<ContractResponse> fetch(String contractUri) {
            fetches++;
            return Optional.ofNullable(contract);
        }
    }

    /**
     * A cache manager handing out no cache at all.
     *
     * <p>{@link StubContractGateway} overrides the only method that reads one, so the field it
     * leaves null is never touched.
     */
    private static final class NoCacheManager implements CacheManager<Object> {

        @Override
        public SyncCache<Object> getCache(String name) {
            return null;
        }

        @Override
        public Set<String> getCacheNames() {
            return Set.of();
        }
    }

    /** A writer that stores normally until a case arms it to lose the slot. */
    private final class StubWriter extends PrivacyNoticeWriter {

        @Nullable private PrivacyNotice winner;

        private boolean armed;

        private StubWriter() {
            super(null);
        }

        /**
         * Makes the next write fail, after committing {@code winner} - {@code null} models a
         * failure that was never the duplicate-key race.
         */
        void failAfter(@Nullable PrivacyNotice committed) {
            this.winner = committed;
            this.armed = true;
        }

        @Override
        public PrivacyNotice archiveAndCreate(
                @Nullable PrivacyNotice superseded, PrivacyNotice replacement) {
            if (armed) {
                armed = false;
                if (winner != null) {
                    notices.put(winner);
                }
                throw new DataAccessException("the write did not land");
            }
            if (superseded != null) {
                superseded.setArchivedAt(Instant.now());
                notices.put(superseded);
            }
            return notices.put(replacement);
        }
    }

    /** An in-memory {@link ParticipantRepository}; generation only ever reads from it. */
    private static final class StubParticipantRepository extends StubRepository<Participant>
            implements ParticipantRepository {

        @Override
        UUID keyOf(Participant row) {
            return row.getId();
        }

        @Override
        public Optional<Participant> findById(UUID id) {
            return Optional.ofNullable(rows().get(id));
        }

        @Override
        public Optional<Participant> findByIdentifier(String identifier) {
            return rows().values().stream()
                    .filter(participant -> participant.getIdentifier().equals(identifier))
                    .findFirst();
        }

        @Override
        public boolean existsByIdentifier(String identifier) {
            throw unsupported();
        }

        @Override
        public Optional<Participant> findByIdForUpdate(UUID id) {
            throw unsupported();
        }

        @Override
        public Page<Participant> findByDeregisteredAtIsNull(Pageable pageable) {
            throw unsupported();
        }

        @Override
        public Page<Participant> findByIdentifier(String identifier, Pageable pageable) {
            throw unsupported();
        }

        @Override
        public List<Participant> findByIdIn(Collection<UUID> ids) {
            throw unsupported();
        }
    }

    /** An in-memory {@link PrivacyNoticeRepository} holding what the stub writer stored. */
    private static final class StubPrivacyNoticeRepository extends StubRepository<PrivacyNotice>
            implements PrivacyNoticeRepository {

        @Override
        UUID keyOf(PrivacyNotice row) {
            return row.getId();
        }

        @Override
        public Optional<PrivacyNotice> findById(UUID id) {
            return Optional.ofNullable(rows().get(id));
        }

        @Override
        public Optional<PrivacyNotice> findByContractUriAndProviderIdAndArchivedAtIsNull(
                String contractUri, UUID providerId) {
            return rows().values().stream()
                    .filter(notice -> notice.getContractUri().equals(contractUri))
                    .filter(notice -> notice.getProviderId().equals(providerId))
                    .filter(notice -> notice.getArchivedAt() == null)
                    .findFirst();
        }

        @Override
        public List<PrivacyNotice> findByProviderIdAndArchivedAtIsNull(UUID providerId) {
            throw unsupported();
        }

        @Override
        public Page<PrivacyNotice> findByProviderIdAndArchivedAtIsNull(
                UUID providerId, Pageable pageable) {
            throw unsupported();
        }

        @Override
        public List<PrivacyNotice> findByConsumerIdAndArchivedAtIsNull(UUID consumerId) {
            throw unsupported();
        }

        @Override
        public int countByParticipant(UUID participantId) {
            throw unsupported();
        }
    }

    /**
     * The rows a stub holds, plus the {@code PageableRepository} methods generation never reaches.
     *
     * @param <E> the entity held, keyed on its own primary key
     */
    private abstract static class StubRepository<E> {

        private final Map<UUID, E> rows = new LinkedHashMap<>();

        /** The key a row is held under. */
        abstract UUID keyOf(E row);

        /** Seeds or replaces a row as if a write had stored it. */
        E put(E row) {
            rows.put(keyOf(row), row);
            return row;
        }

        Map<UUID, E> rows() {
            return rows;
        }

        public <S extends E> S save(S entity) {
            throw unsupported();
        }

        public <S extends E> S update(S entity) {
            throw unsupported();
        }

        public <S extends E> S insert(S entity) {
            throw unsupported();
        }

        public <S extends E> List<S> saveAll(Iterable<S> entities) {
            throw unsupported();
        }

        public <S extends E> List<S> insertAll(Iterable<S> entities) {
            throw unsupported();
        }

        public <S extends E> List<S> updateAll(Iterable<S> entities) {
            throw unsupported();
        }

        public boolean existsById(UUID id) {
            throw unsupported();
        }

        public List<E> findAll() {
            return new ArrayList<>(rows.values());
        }

        public List<E> findAll(Sort sort) {
            throw unsupported();
        }

        public Page<E> findAll(Pageable pageable) {
            throw unsupported();
        }

        public long count() {
            throw unsupported();
        }

        public void deleteById(UUID id) {
            throw unsupported();
        }

        public void delete(E entity) {
            throw unsupported();
        }

        public void deleteAll(Iterable<? extends E> entities) {
            throw unsupported();
        }

        public void deleteAll() {
            throw unsupported();
        }

        /** Guards the operations generation must never reach. */
        static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by privacy notice generation");
        }
    }

    /** Kept so the unused-import check does not fire on a helper a later case will want. */
    private static final UnaryOperator<String> IDENTITY = UnaryOperator.identity();
}
