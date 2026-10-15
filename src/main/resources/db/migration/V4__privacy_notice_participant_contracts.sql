-- V4__privacy_notice_participant_contracts.sql
-- Indexes contract discovery's candidate queries: the distinct contract URIs of a
-- participant's active notices, once as provider and once as consumer.
--
-- `uq_privacy_notices_active_contract (contract_uri, provider_id)` leads with the
-- URI, so neither side's predicate could use it and every listing scanned the
-- whole table, once per side per linked participant. Partial on the same
-- condition the queries filter on, and carrying `contract_uri` so the DISTINCT
-- is answered from the index alone.
CREATE INDEX idx_privacy_notices_active_provider_contract
    ON privacy_notices (provider_id, contract_uri)
    WHERE archived_at IS NULL;

CREATE INDEX idx_privacy_notices_active_consumer_contract
    ON privacy_notices (consumer_id, contract_uri)
    WHERE archived_at IS NULL;
