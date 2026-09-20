CREATE INDEX IF NOT EXISTS idx_payment_idempotency_payment_id
    ON payment_idempotency_record (payment_id);
