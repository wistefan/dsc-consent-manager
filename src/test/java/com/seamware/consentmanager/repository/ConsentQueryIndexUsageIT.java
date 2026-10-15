package com.seamware.consentmanager.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.ApplicationContext;
import io.micronaut.data.annotation.Query;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the consent listings and the validity check are served by an index, not a sequential scan
 * of {@code consents}.
 *
 * <p>Each plan is taken for the SQL Micronaut Data compiled onto the repository method - read off
 * its {@link Query} metadata, so the assertion follows the query rather than a copy of it - with
 * the parameters inlined as literals. The table is seeded with enough subjects and participants
 * that one caller's rows are a sliver of it, and analysed, so the planner's choice is the one it
 * makes on a populated deployment rather than the seq scan any planner picks for a tiny table.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent query index usage")
class ConsentQueryIndexUsageIT extends PostgresTestResource {

    private static final String PREFIX = "urn:test:index-usage:";

    private static final int USERS = 2_000;

    /** Participants, each the provider of one notice and the consumer of the next one's. */
    private static final int PARTICIPANTS = 100;

    /** Ten consents per subject, so one subject's history is 0.05% of the table. */
    private static final int CONSENTS = 20_000;

    /** Page the listing plans are taken for; its value matters only in that one is applied. */
    private static final int PAGE_SIZE = 20;

    private static final String SEQ_SCAN_ON_CONSENTS = "Seq Scan on consents";

    private static final String SEQ_SCAN_ON_USERS = "Seq Scan on users";

    private static final String USER_INDEX = "idx_consents_user_id_status";

    private static final String PROVIDER_INDEX = "idx_consents_provider_id";

    private static final String CONSUMER_INDEX = "idx_consents_consumer_id";

    private static final String USER_IDENTIFIER_INDEX = "uq_users_identifier";

    /** The partial unique index over grants, which serves any {@code GRANTED}-only lookup. */
    private static final String GRANT_INDEX = "uq_consents_granted_per_user_notice";

    /** A subject's consents, reached by either index keyed on the subject. */
    private static final Set<String> BY_SUBJECT = Set.of(USER_INDEX, GRANT_INDEX);

    /** The sort and limit Micronaut appends to a page query ordered newest first. */
    private static final String NEWEST_FIRST_PAGE =
            " ORDER BY consent_.created_at DESC, consent_.id DESC LIMIT " + PAGE_SIZE;

    /** A named parameter in a native query, not the {@code ::} of a cast. */
    private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<!:):(\\w+)");

    private static final Pattern POSITIONAL_PARAMETER = Pattern.compile("\\?");

    private static final String SEED =
            """
            INSERT INTO participants (id, identifier, legal_name)
            SELECT gen_random_uuid(), '%1$sparticipant:' || g, 'Index usage ' || g
            FROM generate_series(1, %2$d) g;

            INSERT INTO users (id, identifier)
            SELECT gen_random_uuid(), '%1$suser:' || g FROM generate_series(1, %3$d) g;

            WITH numbered AS (
                SELECT id, row_number() OVER (ORDER BY id) AS n
                FROM participants WHERE identifier LIKE '%1$s%%')
            INSERT INTO privacy_notices (id, contract_uri, provider_id, consumer_id)
            SELECT gen_random_uuid(), '%1$scontract:' || p.n, p.id, c.id
            FROM numbered p JOIN numbered c ON c.n = p.n %% %2$d + 1;

            WITH subjects AS (
                SELECT id, row_number() OVER (ORDER BY id) AS n
                FROM users WHERE identifier LIKE '%1$s%%'),
            terms AS (
                SELECT id, provider_id, consumer_id, row_number() OVER (ORDER BY id) AS n
                FROM privacy_notices WHERE contract_uri LIKE '%1$s%%')
            INSERT INTO consents
                (id, user_id, privacy_notice_id, provider_id, consumer_id, status, consented)
            SELECT gen_random_uuid(), s.id, t.id, t.provider_id, t.consumer_id,
                   CASE WHEN g <= %3$d THEN 'GRANTED' ELSE 'REVOKED' END, g <= %3$d
            FROM generate_series(1, %4$d) g
            JOIN subjects s ON s.n = g %% %3$d + 1
            JOIN terms t ON t.n = g %% %2$d + 1;

            ANALYZE participants, users, privacy_notices, consents;
            """
                    .formatted(PREFIX, PARTICIPANTS, USERS, CONSENTS);

    private static final String UNSEED =
            """
            DELETE FROM consents WHERE user_id IN
                (SELECT id FROM users WHERE identifier LIKE '%1$s%%');
            DELETE FROM privacy_notices WHERE contract_uri LIKE '%1$s%%';
            DELETE FROM users WHERE identifier LIKE '%1$s%%';
            DELETE FROM participants WHERE identifier LIKE '%1$s%%';
            """
                    .formatted(PREFIX);

    @Inject DataSource dataSource;

    @Inject DataSourceResolver dataSources;

    @Inject ApplicationContext context;

    private Probe probe;

    @BeforeAll
    void seed() throws SQLException {
        execute(UNSEED);
        execute(SEED);
        probe = probe();
    }

    @AfterAll
    void unseed() throws SQLException {
        execute(UNSEED);
    }

    /** Each query a consent read issues; its plan must name one index of every listed set. */
    List<Arguments> queries() {
        String subject = uuid(probe.userId());
        String participant = uuid(probe.participantId());
        String notice = uuid(probe.noticeId());
        String granted = "'GRANTED'";
        return List.of(
                Arguments.of(
                        "subject listing",
                        positional(query("findByUserId"), subject) + NEWEST_FIRST_PAGE,
                        List.of(Set.of(USER_INDEX))),
                Arguments.of(
                        "subject listing count",
                        positional(countQuery("findByUserId"), subject),
                        List.of(Set.of(USER_INDEX))),
                Arguments.of(
                        "subject listing by status",
                        positional(query("findByUserIdAndStatusIn"), subject, granted)
                                + NEWEST_FIRST_PAGE,
                        List.of(BY_SUBJECT)),
                Arguments.of(
                        "participant listing",
                        named(query("findForParticipant"), participantFilters(participant, "NULL"))
                                + NEWEST_FIRST_PAGE,
                        List.of(Set.of(PROVIDER_INDEX), Set.of(CONSUMER_INDEX))),
                Arguments.of(
                        "participant listing count",
                        named(
                                countQuery("findForParticipant"),
                                participantFilters(participant, "NULL")),
                        List.of(Set.of(PROVIDER_INDEX), Set.of(CONSUMER_INDEX))),
                Arguments.of(
                        "participant listing for one subject",
                        named(query("findForParticipant"), participantFilters(participant, subject))
                                + NEWEST_FIRST_PAGE,
                        List.of(BY_SUBJECT)),
                Arguments.of(
                        "validity check",
                        named(
                                query("findForParticipantBySubjectAndNoticeAndStatus"),
                                Map.of(
                                        "userIdentifier", "'" + probe.userIdentifier() + "'",
                                        "privacyNoticeId", notice,
                                        "status", granted,
                                        "participantId", participant)),
                        List.of(Set.of(USER_IDENTIFIER_INDEX), BY_SUBJECT)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queries")
    @DisplayName("is served by an index scan")
    void usesAnIndex(String name, String sql, List<Set<String>> indexes) throws SQLException {
        String plan = explain(sql);

        assertThat(plan).doesNotContain(SEQ_SCAN_ON_CONSENTS, SEQ_SCAN_ON_USERS);
        indexes.forEach(
                alternatives ->
                        assertThat(plan).containsAnyOf(alternatives.toArray(String[]::new)));
    }

    private static Map<String, String> participantFilters(String participant, String userId) {
        return Map.of(
                "participantId",
                participant,
                "statuses",
                "'GRANTED'",
                "userId",
                userId,
                "privacyNoticeId",
                "NULL");
    }

    /** A subject, a participant party to its consents, and one notice it holds a grant under. */
    private Probe probe() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet row =
                        statement.executeQuery(
                                "SELECT u.identifier, c.user_id, c.provider_id,"
                                        + " c.privacy_notice_id FROM consents c"
                                        + " JOIN users u ON u.id = c.user_id"
                                        + " WHERE c.status = 'GRANTED' AND u.identifier LIKE '"
                                        + PREFIX
                                        + "%' LIMIT 1")) {
            assertThat(row.next()).isTrue();
            return new Probe(
                    row.getString(1), row.getObject(2), row.getObject(3), row.getObject(4));
        }
    }

    private String explain(String sql) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet plan = statement.executeQuery("EXPLAIN " + sql)) {
            while (plan.next()) {
                lines.add(plan.getString(1));
            }
        }
        return String.join("\n", lines);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * A pooled connection outside Micronaut's transaction-aware proxy, which needs a transaction.
     */
    private Connection connect() throws SQLException {
        return dataSources.resolve(dataSource).getConnection();
    }

    private String query(String method) {
        return repositoryMethod(method).stringValue(Query.class).orElseThrow();
    }

    private String countQuery(String method) {
        return repositoryMethod(method).stringValue(Query.class, "countQuery").orElseThrow();
    }

    private ExecutableMethod<?, ?> repositoryMethod(String name) {
        return context.getBeanDefinition(ConsentRepository.class).getExecutableMethods().stream()
                .filter(method -> method.getMethodName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** {@code sql} with each {@code :name} replaced by its literal. */
    private static String named(String sql, Map<String, String> literals) {
        return NAMED_PARAMETER
                .matcher(sql)
                .replaceAll(match -> Matcher.quoteReplacement(literals.get(match.group(1))));
    }

    /** {@code sql} with each {@code ?} replaced by the next literal, in order. */
    private static String positional(String sql, String... literals) {
        Iterator<String> next = List.of(literals).iterator();
        String bound =
                POSITIONAL_PARAMETER
                        .matcher(sql)
                        .replaceAll(match -> Matcher.quoteReplacement(next.next()));
        assertThat(next).as("every literal bound").isExhausted();
        return bound;
    }

    private static String uuid(Object value) {
        return "'" + value + "'::uuid";
    }

    /** The rows a plan is taken for; ids are kept as JDBC returns them. */
    private record Probe(
            String userIdentifier, Object userId, Object participantId, Object noticeId) {}
}
