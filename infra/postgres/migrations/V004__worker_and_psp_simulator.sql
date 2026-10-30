CREATE SCHEMA psp_simulator;

CREATE TABLE psp_simulator.transactions (
    transaction_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE,
    external_reference VARCHAR(150) NOT NULL UNIQUE,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL,
    simulation_scenario VARCHAR(16) NOT NULL
        CHECK (simulation_scenario IN ('APPROVED','REJECTED','TIMEOUT')),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('APPROVED','REJECTED')),
    response_lost BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_psp_transactions_created_at
    ON psp_simulator.transactions (created_at DESC, transaction_id DESC);
