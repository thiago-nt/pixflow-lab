CREATE TABLE payments (
    payment_id UUID PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    external_reference VARCHAR(150) NOT NULL UNIQUE,
    lease_owner VARCHAR(150),
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    sent_to_psp_at TIMESTAMPTZ,
    result_code VARCHAR(100),
    CONSTRAINT payments_status_check CHECK (status IN ('RECEIVED','PROCESSING','SENT_TO_PSP','UNKNOWN','SUCCEEDED','FAILED','MANUAL_REVIEW'))
);

CREATE TABLE idempotency_keys (
    tenant_id VARCHAR(100) NOT NULL,
    key_digest VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    payment_id UUID NOT NULL REFERENCES payments(payment_id),
    state VARCHAR(32) NOT NULL,
    final_response_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, key_digest)
);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    topic VARCHAR(150) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    payload_json JSONB NOT NULL,
    partition_key VARCHAR(150) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claimed_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    UNIQUE (aggregate_id, aggregate_version)
);

CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE inbox_processed (
    consumer_name VARCHAR(150) NOT NULL,
    event_id UUID NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE payment_audit (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(payment_id),
    previous_status VARCHAR(32),
    new_status VARCHAR(32) NOT NULL,
    fencing_token BIGINT NOT NULL,
    event_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    correlation_id VARCHAR(150) NOT NULL,
    reason VARCHAR(500) NOT NULL
);

CREATE INDEX idx_payments_reconciliation ON payments (status, lease_until) WHERE status IN ('SENT_TO_PSP','UNKNOWN');
CREATE INDEX idx_payment_audit_payment_time ON payment_audit (payment_id, occurred_at);
