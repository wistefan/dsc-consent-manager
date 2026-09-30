package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * Represents the contract-derived content of a privacy notice, stored as JSONB in the {@code
 * payload} column of the {@code privacy_notices} table.
 *
 * <p>All fields are nullable because the payload structure is contract-derived and may vary between
 * different privacy notice configurations. This record is annotated with {@link Serdeable} to
 * enable automatic JSONB round-tripping via Micronaut Serde Jackson.
 *
 * @param purposes the declared purposes for data processing (e.g. "marketing",
 *     "service-improvement")
 * @param dataCategories the categories of personal data being processed (e.g. "name", "email",
 *     "location")
 * @param recipients the entities or categories of entities that will receive the data
 * @param controllerName the name of the data controller responsible for the processing
 * @param controllerContact the contact information for the data controller
 * @param retentionPeriod the duration for which personal data will be retained (e.g. "24 months")
 * @param piiPrincipalRights the rights available to the PII principal (e.g. "access", "erasure",
 *     "portability")
 * @param internationalTransfers the countries or regions to which data may be transferred
 * @param withdrawalMethod a description of how consent can be withdrawn
 */
@Serdeable
public record PrivacyNoticePayload(
        @Nullable List<String> purposes,
        @Nullable List<String> dataCategories,
        @Nullable List<String> recipients,
        @Nullable String controllerName,
        @Nullable String controllerContact,
        @Nullable String retentionPeriod,
        @Nullable List<String> piiPrincipalRights,
        @Nullable List<String> internationalTransfers,
        @Nullable String withdrawalMethod) {}
