CREATE INDEX ix_ordering_order_customer_recent_store
    ON ordering_order (customer_id, state, created_at DESC, store_id);
