-- V1__initial_schema.sql
-- Creates the core consent-manager schema: users, participants,
-- user_participants link, privacy_notices, consents, and consent_events.

-- ============================================================
-- users
-- ============================================================
CREATE TABLE users (
    id              UUID        PRIMARY KEY,
    identifier      TEXT        NOT NULL,
    email           TEXT,
    first_name      TEXT,
    last_name       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_identifier UNIQUE (identifier)
);

-- Case-insensitive email lookup (only rows with a non-null email).
CREATE INDEX idx_users_email_lower
    ON users (lower(email))
    WHERE email IS NOT NULL;

-- ============================================================
-- participants
-- ============================================================
CREATE TABLE participants (
    id                   UUID        PRIMARY KEY,
    identifier           TEXT        NOT NULL,
    legal_name           TEXT        NOT NULL,
    self_description_uri TEXT,
    email                TEXT,
    endpoints            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    legal_person         JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_participants_identifier UNIQUE (identifier)
);

-- ============================================================
-- user_participants (link table)
-- ============================================================
CREATE TABLE user_participants (
    user_id          UUID        NOT NULL,
    participant_id   UUID        NOT NULL,
    local_identifier TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_user_participants
        PRIMARY KEY (user_id, participant_id),

    CONSTRAINT fk_user_participants_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_user_participants_participant
        FOREIGN KEY (participant_id) REFERENCES participants (id)
        ON DELETE CASCADE
);

-- Speed up lookups by participant when the leading PK column is user_id.
CREATE INDEX idx_user_participants_participant_id
    ON user_participants (participant_id);

-- ============================================================
-- privacy_notices
-- ============================================================
CREATE TABLE privacy_notices (
    id           UUID        PRIMARY KEY,
    contract_uri TEXT        NOT NULL,
    title        TEXT,
    provider_id  UUID        NOT NULL,
    consumer_id  UUID,
    payload      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_privacy_notices_provider
        FOREIGN KEY (provider_id) REFERENCES participants (id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_privacy_notices_consumer
        FOREIGN KEY (consumer_id) REFERENCES participants (id)
        ON DELETE RESTRICT
);

-- Only one active (non-archived) privacy notice per (contract_uri, provider_id).
CREATE UNIQUE INDEX uq_privacy_notices_active_contract
    ON privacy_notices (contract_uri, provider_id)
    WHERE archived_at IS NULL;

-- ============================================================
-- consents
-- ============================================================
CREATE TABLE consents (
    id                 UUID        PRIMARY KEY,
    user_id            UUID        NOT NULL,
    privacy_notice_id  UUID        NOT NULL,
    provider_id        UUID        NOT NULL,
    consumer_id        UUID        NOT NULL,
    parent_consent_id  UUID,
    status             TEXT        NOT NULL,
    consented          BOOLEAN     NOT NULL DEFAULT false,
    contract_uri       TEXT,
    snapshot           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Foreign keys
    CONSTRAINT fk_consents_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_consents_privacy_notice
        FOREIGN KEY (privacy_notice_id) REFERENCES privacy_notices (id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_consents_provider
        FOREIGN KEY (provider_id) REFERENCES participants (id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_consents_consumer
        FOREIGN KEY (consumer_id) REFERENCES participants (id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_consents_parent
        FOREIGN KEY (parent_consent_id) REFERENCES consents (id)
        ON DELETE SET NULL,

    -- Status must be one of the 7 valid consent lifecycle states.
    CONSTRAINT chk_consents_status
        CHECK (status IN (
            'PENDING', 'DRAFT', 'GRANTED',
            'REVOKED', 'EXPIRED', 'TERMINATED', 'REFUSED'
        )),

    -- A consent cannot be its own parent.
    CONSTRAINT chk_consents_no_self_parent
        CHECK (parent_consent_id IS DISTINCT FROM id)
);

-- Common query patterns: filter consents by user + status.
CREATE INDEX idx_consents_user_id_status
    ON consents (user_id, status);

-- Provider and consumer lookups.
CREATE INDEX idx_consents_provider_id
    ON consents (provider_id);

CREATE INDEX idx_consents_consumer_id
    ON consents (consumer_id);

-- Privacy notice lookups.
CREATE INDEX idx_consents_privacy_notice_id
    ON consents (privacy_notice_id);

-- At most one GRANTED consent per (user, privacy_notice) at a time.
CREATE UNIQUE INDEX uq_consents_granted_per_user_notice
    ON consents (user_id, privacy_notice_id)
    WHERE status = 'GRANTED';

-- ============================================================
-- consent_events (audit log)
-- ============================================================
CREATE TABLE consent_events (
    id           UUID        PRIMARY KEY,
    consent_id   UUID        NOT NULL,
    event_state  TEXT        NOT NULL,
    event_type   TEXT        NOT NULL DEFAULT 'explicit',
    actor        TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    details      JSONB,

    CONSTRAINT fk_consent_events_consent
        FOREIGN KEY (consent_id) REFERENCES consents (id)
        ON DELETE CASCADE
);

-- Chronological event retrieval per consent.
CREATE INDEX idx_consent_events_consent_id_occurred_at
    ON consent_events (consent_id, occurred_at);
