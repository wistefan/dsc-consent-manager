package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * Represents a frozen copy of privacy notice terms captured at the time consent was granted, stored
 * as JSONB in the {@code snapshot} column of the {@code consents} table.
 *
 * <p>The snapshot preserves the exact terms that the user agreed to, ensuring that subsequent
 * changes to the privacy notice do not retroactively alter the record of what was consented. Its
 * structure mirrors the relevant subset of {@link PrivacyNoticePayload}.
 *
 * <p>All fields are nullable because the snapshot content depends on the specific privacy notice
 * configuration at the time of consent. This record is annotated with {@link Serdeable} to enable
 * automatic JSONB round-tripping via Micronaut Serde Jackson.
 *
 * @param purposes the declared purposes for data processing at the time of consent
 * @param dataCategories the categories of personal data being processed at the time of consent
 * @param recipients the entities or categories of entities that receive the data
 * @param retentionPeriod the duration for which personal data will be retained
 * @param jurisdiction the legal jurisdiction governing the consent and data processing
 */
@Serdeable
public record ConsentSnapshot(
        @Nullable List<String> purposes,
        @Nullable List<String> dataCategories,
        @Nullable List<String> recipients,
        @Nullable String retentionPeriod,
        @Nullable String jurisdiction) {}
