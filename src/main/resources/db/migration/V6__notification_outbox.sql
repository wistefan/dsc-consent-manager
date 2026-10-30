-- V6__notification_outbox.sql
-- Transactional outbox of participant notifications (ticket #94, ADR-0016).
--
-- A row is written in the same transaction as the consent move it reports, one
-- per recipient, and drained by a lease-based dispatcher: a claim pushes
-- `next_attempt_at` forward by the lease and stamps a fresh `lease_token`, and
-- every outcome write is fenced on that token, so a replica that lost its lease
-- can never overwrite a row another replica already moved.
--
-- PENDING has not been attempted; FAILED awaits a scheduled retry; DELIVERED
-- and ABANDONED are terminal. Both PENDING and FAILED rows are due, which is
-- why the due index covers both.
--
-- `payload` is the frozen document that is signed and sent, and it names the
-- data subject. A terminal row no longer needs it, so it carries none
-- (ADR-0007): the history route reads the columns, never the payload.
--
-- Every timestamp is set from the database's now(), the clock every replica's
-- claim compares against, so skew between replicas cannot move a lease, a
-- retry or an age cut-off.
--
-- The foreign keys cascade: a row means nothing without its recipient or the
-- consent event it reports. Neither is deleted while a row can still be owed.
CREATE TABLE notification_outbox (
    id               UUID        PRIMARY KEY,
    participant_id   UUID        NOT NULL,
    event_id         UUID        NOT NULL,
    consent_id       UUID        NOT NULL,
    event_type       TEXT        NOT NULL,
    payload          JSONB,
    status           TEXT        NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token      UUID,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ,

    CONSTRAINT fk_notification_outbox_participant
        FOREIGN KEY (participant_id) REFERENCES participants (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_notification_outbox_consent
        FOREIGN KEY (consent_id) REFERENCES consents (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_notification_outbox_event
        FOREIGN KEY (event_id) REFERENCES consent_events (id)
        ON DELETE CASCADE,

    -- One event is queued at most once per recipient.
    CONSTRAINT uq_notification_outbox_event_participant
        UNIQUE (event_id, participant_id),

    CONSTRAINT chk_notification_outbox_event_type
        CHECK (event_type IN (
            'CONSENT_GRANTED', 'CONSENT_REVOKED',
            'CONSENT_TERMINATED', 'CONSENT_EXPIRED'
        )),
    CONSTRAINT chk_notification_outbox_status
        CHECK (status IN ('PENDING', 'FAILED', 'DELIVERED', 'ABANDONED')),
    CONSTRAINT chk_notification_outbox_attempts
        CHECK (attempts >= 0),

    -- A terminal row holds no subject identifier (ADR-0007).
    CONSTRAINT chk_notification_outbox_terminal_payload
        CHECK (status IN ('PENDING', 'FAILED') OR payload IS NULL)
);

-- The claim query: due rows in retry order.
CREATE INDEX notification_outbox_due_idx
    ON notification_outbox (next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

-- The participant's delivery history, newest first.
CREATE INDEX notification_outbox_participant_idx
    ON notification_outbox (participant_id, created_at DESC);
