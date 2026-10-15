-- V5__consent_receipts.sql
-- The signed Kantara receipt of a grant, issued once in the transaction that
-- moved the consent to GRANTED (ADR-0012, ADR-0013).
--
-- Keyed by the consent: a row reaches GRANTED at most once, so it has at most
-- one grant receipt. `event_id` names the event that granted it, which is what
-- the receipt's `consentTimestamp` is dated by.
--
-- `canonical_receipt` is TEXT rather than JSONB on purpose. JSONB normalises
-- what it stores (key order, whitespace, number forms), and the stored bytes
-- must be exactly the RFC 8785 text `jws` was computed over, so a reader can
-- verify the receipt without re-serialising it.
--
-- Both foreign keys cascade: a receipt means nothing without the consent and
-- the event it attests. Erasure deletes a subject's receipts explicitly
-- (ADR-0007), because a signed receipt names the subject and cannot be
-- pseudonymised without breaking its signature.
CREATE TABLE consent_receipts (
    consent_id         UUID        PRIMARY KEY,
    event_id           UUID        NOT NULL,
    canonical_receipt  TEXT        NOT NULL,
    jws                TEXT        NOT NULL,
    key_id             TEXT        NOT NULL,
    issued_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_consent_receipts_consent
        FOREIGN KEY (consent_id) REFERENCES consents (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_consent_receipts_event
        FOREIGN KEY (event_id) REFERENCES consent_events (id)
        ON DELETE CASCADE
);
