ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('RECEIVED','PENDING','PROCESSING','SENT_TO_PSP','UNKNOWN','SUCCEEDED','FAILED','MANUAL_REVIEW'));

ALTER TABLE payments ADD COLUMN merchant_reference VARCHAR(120);
UPDATE payments SET merchant_reference = external_reference WHERE merchant_reference IS NULL;
ALTER TABLE payments ALTER COLUMN merchant_reference SET NOT NULL;

CREATE INDEX idx_payments_created_at ON payments (created_at DESC, payment_id DESC);
CREATE INDEX idx_idempotency_payment ON idempotency_keys (payment_id);
