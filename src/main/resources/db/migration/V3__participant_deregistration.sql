-- V3__participant_deregistration.sql
-- Marks a participant that has deregistered but whose row is still referenced.
--
-- Deregistration deletes the `participants` row when nothing points at it any
-- more, and otherwise retains it intact and marks it with this column. The row
-- is deliberately *not* pseudonymised the way an erased user is: a participant
-- is an organization rather than a data subject, there is no keyed verifier
-- counterpart to recover a scrubbed value from, and a retained consent naming a
-- counterparty nobody can identify would be worse than useless. Since
-- `privacy_notices` restricts deletion on both of its participant foreign keys,
-- a participant that ever published a notice always lands on the retain branch,
-- which makes that branch the normal one.
--
-- Null for every active participant, which is what the directory listing and
-- every participant-scoped route treat as "registered".
ALTER TABLE participants ADD COLUMN deregistered_at TIMESTAMPTZ;

-- Partial, because the only query shape is the directory listing: active
-- participants ordered by identifier. A full index on the column would carry
-- the deregistered minority for no reader.
CREATE INDEX idx_participants_active_identifier
    ON participants (identifier)
    WHERE deregistered_at IS NULL;
