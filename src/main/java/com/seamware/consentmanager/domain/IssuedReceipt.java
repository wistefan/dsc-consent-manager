package com.seamware.consentmanager.domain;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The signed Kantara receipt a grant was issued with, stored as the exact bytes that were signed.
 *
 * <p>Immutable: written once in the granting transaction and served verbatim, never re-signed.
 */
@MappedEntity("consent_receipts")
public record IssuedReceipt(
        @Id @MappedProperty("consent_id") UUID consentId,
        @MappedProperty("event_id") UUID eventId,
        @MappedProperty("canonical_receipt") String canonicalReceipt,
        String jws,
        @MappedProperty("key_id") String keyId,
        @MappedProperty("issued_at") Instant issuedAt) {}
