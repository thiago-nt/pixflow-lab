CREATE TABLE reconciliation_jobs (
    payment_id UUID PRIMARY KEY REFERENCES payments(payment_id),
    correlation_id VARCHAR(150) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_attempt_at TIMESTAMPTZ,
    last_result VARCHAR(40),
    last_error VARCHAR(500),
    claimed_by VARCHAR(180),
    claim_token UUID,
    claimed_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT reconciliation_claim_consistency CHECK (
        (claimed_by IS NULL AND claim_token IS NULL AND claimed_until IS NULL)
        OR (claimed_by IS NOT NULL AND claim_token IS NOT NULL AND claimed_until IS NOT NULL)
    )
);

CREATE INDEX idx_reconciliation_jobs_due
    ON reconciliation_jobs (next_attempt_at, created_at)
    WHERE completed_at IS NULL;

CREATE TABLE reconciliation_audit (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(payment_id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    owner_id VARCHAR(180) NOT NULL,
    fencing_token BIGINT NOT NULL,
    correlation_id VARCHAR(150) NOT NULL,
    external_reference VARCHAR(150) NOT NULL,
    outcome VARCHAR(40) NOT NULL CHECK (outcome IN (
        'CLAIMED','CONFIRMED_APPROVED','CONFIRMED_REJECTED','STATUS_UNKNOWN',
        'NOT_FOUND','TEMPORARY_FAILURE','STALE_WRITE_REJECTED',
        'TERMINAL_PRESERVED','MANUAL_REVIEW'
    )),
    psp_transaction_id UUID,
    detail VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_reconciliation_audit_payment_time
    ON reconciliation_audit (payment_id, occurred_at DESC);

ALTER TABLE psp_simulator.transactions
    DROP CONSTRAINT transactions_simulation_scenario_check;

ALTER TABLE psp_simulator.transactions
    ADD CONSTRAINT transactions_simulation_scenario_check
    CHECK (simulation_scenario IN ('APPROVED','REJECTED','TIMEOUT','REJECTED_TIMEOUT'));
