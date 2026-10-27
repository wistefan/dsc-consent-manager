package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.TestJson;
import io.micronaut.core.type.Argument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests that extraction is deterministic and its lists follow the contract's order, since repeated
 * generation detects a contract change by comparing extracted content against the stored one.
 */
@DisplayName("Contract content extraction order")
class ContractContentExtractorOrderTest {

    /** The ecosystem member list this suite reorders; its entries become the recipients. */
    private static final String MEMBERS = "members";

    private final ContractContentExtractor extractor = new ContractContentExtractor();

    /** The documents whose extraction must not depend on when or how often it runs. */
    static Stream<String> fixtures() {
        return Stream.of(
                ContractFixtures.ECOSYSTEM,
                ContractFixtures.BILATERAL,
                ContractFixtures.ECOSYSTEM_NO_PURPOSE,
                ContractFixtures.ECOSYSTEM_DIVERGENT_TERMINATION,
                ContractFixtures.BILATERAL_NO_PURPOSE,
                ContractFixtures.ECOSYSTEM_UNNAMED_DISCLOSURE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("yields equal content for two independent readings of the same document")
    void extractsDeterministically(String fixture) {
        // Decoded twice: the reading, not the pure extraction, is where an order could differ.
        assertThat(extractor.extract(ContractFixtures.contract(fixture), provider()))
                .isEqualTo(extractor.extract(ContractFixtures.contract(fixture), provider()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("yields a payload that still equals itself after being stored and read back")
    void survivesSerialization(String fixture) {
        // Repeated generation compares a fresh payload against one read back from the JSONB column.
        PrivacyNoticePayload extracted =
                extractor.extract(ContractFixtures.contract(fixture), provider()).payload();

        assertThat(serialized(extracted)).isEqualTo(extracted);
    }

    @Test
    @DisplayName("reorders only the section whose source the document reordered")
    void followsDocumentOrder() {
        PrivacyNoticePayload original =
                extractor
                        .extract(
                                ContractFixtures.contract(ContractFixtures.ECOSYSTEM_NO_PURPOSE),
                                provider())
                        .payload();
        PrivacyNoticePayload reordered =
                extractor
                        .extract(
                                mutatedMembers(ContractContentExtractorOrderTest::reversed),
                                provider())
                        .payload();

        assertThat(reordered.recipients())
                .containsExactlyElementsOf(reversed(new ArrayList<>(original.recipients())));
        assertThat(withRecipients(reordered, original.recipients())).isEqualTo(original);
    }

    @Test
    @DisplayName("collapses a repeated source entry instead of repeating it")
    void collapsesRepeatedEntries() {
        PrivacyNoticePayload original =
                extractor
                        .extract(
                                ContractFixtures.contract(ContractFixtures.ECOSYSTEM_NO_PURPOSE),
                                provider())
                        .payload();
        PrivacyNoticePayload duplicated =
                extractor
                        .extract(
                                mutatedMembers(ContractContentExtractorOrderTest::concatenated),
                                provider())
                        .payload();

        assertThat(duplicated).isEqualTo(original);
    }

    /** The ecosystem fixture with its member list put through the given change. */
    private ContractResponse mutatedMembers(UnaryOperator<List<Object>> change) {
        Map<String, Object> document =
                new LinkedHashMap<>(
                        readMap(ContractFixtures.body(ContractFixtures.ECOSYSTEM_NO_PURPOSE)));
        @SuppressWarnings("unchecked")
        List<Object> members = (List<Object>) document.get(MEMBERS);
        document.put(MEMBERS, change.apply(new ArrayList<>(members)));
        return ContractFixtures.decode(write(document));
    }

    /** The payload with its recipients replaced, to compare everything else for equality. */
    private static PrivacyNoticePayload withRecipients(
            PrivacyNoticePayload payload, List<Recipient> recipients) {
        return new PrivacyNoticePayload(
                payload.purposes(),
                payload.categoriesOfData(),
                payload.data(),
                recipients,
                payload.controllerDetails(),
                payload.retentionPeriod(),
                payload.piiPrincipalRights(),
                payload.internationalTransfers(),
                payload.withdrawalOfConsent());
    }

    private static <T> List<T> reversed(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        Collections.reverse(copy);
        return copy;
    }

    private static List<Object> concatenated(List<Object> values) {
        List<Object> twice = new ArrayList<>(values);
        twice.addAll(values);
        return twice;
    }

    private static Participant provider() {
        return new Participant(
                "did:web:provider.example.org",
                "Example Provider GmbH",
                "https://catalog.example.org/v1/catalog/participants/provider",
                "privacy@provider.example.org",
                Map.of(),
                null);
    }

    /** The payload as it comes back out of the JSONB column, via its serialized form. */
    private static PrivacyNoticePayload serialized(PrivacyNoticePayload payload) {
        try {
            return TestJson.mapper()
                    .readValue(
                            TestJson.mapper().writeValueAsString(payload),
                            PrivacyNoticePayload.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not round-trip a notice payload", e);
        }
    }

    private static Map<String, Object> readMap(String body) {
        try {
            return TestJson.mapper().readValue(body, Argument.mapOf(String.class, Object.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read contract document as a tree", e);
        }
    }

    private static String write(Map<String, Object> document) {
        try {
            return TestJson.mapper().writeValueAsString(document);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write contract document back", e);
        }
    }
}
