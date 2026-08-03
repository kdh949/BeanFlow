CREATE INDEX idx_settlement_adjustment_created_cursor
    ON settlement_adjustment (store_id, created_at, id);
