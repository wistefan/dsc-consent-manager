package com.seamware.consentmanager.support;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Canary for {@link DatabaseReset}: rows one method commits are gone by the next, and the shared
 * container's schema came from one successful run of every migration on an empty database.
 */
@MicronautTest(transactional = false)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Schema isolation between test methods")
class SchemaIsolationIT extends PostgresTestResource {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final String MIGRATION_SUFFIX = ".sql";

    private static final String PROVIDER = "urn:test:participant:isolation-provider";

    private static final String CONSUMER = "urn:test:participant:isolation-consumer";

    private static final String SUBJECT = "urn:test:user:isolation-subject";

    private static final String CONTRACT_URI = "urn:test:contract:isolation";

    private static final String APPLICATION_TABLES =
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> '"
                    + DatabaseReset.FLYWAY_HISTORY_TABLE
                    + "' ORDER BY tablename";

    private static final String APPLIED_MIGRATIONS =
            "SELECT script, success FROM " + DatabaseReset.FLYWAY_HISTORY_TABLE;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    /** Set by the writing method so the emptiness check cannot pass by running first. */
    private boolean written;

    @Test
    @Order(1)
    @DisplayName("commits a row to every core table")
    void commitsRows() throws SQLException {
        Participant provider = ConsentFixtures.participant(participants, PROVIDER);
        Participant consumer = ConsentFixtures.participant(participants, CONSUMER);
        User subject = users.save(new User(SUBJECT, null, null, null));
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                null,
                                provider.getId(),
                                consumer.getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        consents.save(
                Consent.withStatus(
                        ConsentStatus.GRANTED,
                        subject.getId(),
                        notice.getId(),
                        provider.getId(),
                        consumer.getId(),
                        null,
                        CONTRACT_URI,
                        noTerms()));

        assertThat(rowCounts()).containsEntry("consents", 1L).containsEntry("users", 1L);
        written = true;
    }

    @Test
    @Order(2)
    @DisplayName("finds every application table empty in the next method")
    void nextMethodStartsEmpty() throws SQLException {
        assertThat(written).as("the writing method ran first").isTrue();

        Map<String, Long> counts = rowCounts();

        assertThat(counts).isNotEmpty();
        assertThat(counts).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
    }

    @Test
    @Order(3)
    @DisplayName("records every migration file once, and successfully")
    void everyMigrationAppliedOnce() throws SQLException, IOException {
        List<String> files;
        try (Stream<Path> listing = Files.list(MIGRATIONS)) {
            files =
                    listing.map(path -> path.getFileName().toString())
                            .filter(name -> name.endsWith(MIGRATION_SUFFIX))
                            .toList();
        }
        List<String> applied = new ArrayList<>();
        try (Connection connection = PostgresTestResource.openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(APPLIED_MIGRATIONS)) {
            while (rows.next()) {
                assertThat(rows.getBoolean(2)).as(rows.getString(1)).isTrue();
                applied.add(rows.getString(1));
            }
        }

        assertThat(files).isNotEmpty();
        assertThat(applied).containsExactlyInAnyOrderElementsOf(files);
    }

    /** Rows per application table, read outside the Micronaut datasource. */
    private static Map<String, Long> rowCounts() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = PostgresTestResource.openConnection();
                Statement statement = connection.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(APPLICATION_TABLES)) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            for (String table : tables) {
                try (ResultSet count =
                        statement.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
                    count.next();
                    counts.put(table, count.getLong(1));
                }
            }
        }
        return counts;
    }
}
