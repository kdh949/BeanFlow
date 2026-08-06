CREATE INDEX idx_point_transaction_account_occurred_id
    ON loyalty_point_transaction (point_account_id, occurred_at DESC, id DESC);
