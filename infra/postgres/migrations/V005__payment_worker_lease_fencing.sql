CREATE TABLE payment_lease_audit (
    lease_event_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(payment_id),
    owner_id VARCHAR(180) NOT NULL,
    fencing_token BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'ACQUIRED','RENEWED','RELEASED','WRITE_REJECTED','BUSY_DEFERRED'
    )),
    lease_until TIMESTAMPTZ,
    event_id UUID,
    correlation_id VARCHAR(150) NOT NULL,
    detail VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_payment_lease_audit_payment_time
    ON payment_lease_audit (payment_id, occurred_at DESC);

CREATE TABLE worker_deferred_events (
    consumer_name VARCHAR(150) NOT NULL,
    event_id UUID NOT NULL,
    payment_id UUID NOT NULL REFERENCES payments(payment_id),
    partition_key VARCHAR(150) NOT NULL,
    payload_json JSONB NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error VARCHAR(500),
    claimed_by VARCHAR(180),
    claim_token UUID,
    claimed_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT worker_deferred_claim_consistency CHECK (
        (claimed_by IS NULL AND claim_token IS NULL AND claimed_until IS NULL)
        OR (claimed_by IS NOT NULL AND claim_token IS NOT NULL AND claimed_until IS NOT NULL)
    )
);

CREATE INDEX idx_worker_deferred_claimable
    ON worker_deferred_events (next_attempt_at, created_at)
    WHERE claimed_until IS NULL;
