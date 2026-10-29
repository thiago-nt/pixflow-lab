ALTER TABLE outbox_events ADD COLUMN claimed_by VARCHAR(150);
ALTER TABLE outbox_events ADD COLUMN claim_token UUID;
ALTER TABLE outbox_events ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp();

CREATE INDEX idx_outbox_claimable
    ON outbox_events (next_attempt_at, created_at)
    WHERE published_at IS NULL;

ALTER TABLE outbox_events ADD CONSTRAINT outbox_claim_consistency CHECK (
    (claimed_by IS NULL AND claim_token IS NULL AND claimed_until IS NULL)
    OR (claimed_by IS NOT NULL AND claim_token IS NOT NULL AND claimed_until IS NOT NULL)
);
