-- Owner-scoped catalogue reads. These indexes support the published bound+1 queries without a
-- global scan/sort as stores and historical rows accumulate.
CREATE INDEX idx_pickup_slot_store_starts_id
    ON fulfillment_pickup_slot (store_id, starts_at, id)
    INCLUDE (ends_at, capacity, reserved_count, confirmed_count);

CREATE INDEX idx_merchant_menu_store_name_id
    ON merchant_menu (store_id, name, id)
    INCLUDE (base_price_krw, available);

CREATE INDEX idx_merchant_menu_store_id
    ON merchant_menu (store_id, id);

CREATE INDEX idx_merchant_menu_option_menu_name_id
    ON merchant_menu_option (menu_id, name, id)
    INCLUDE (additional_price_krw, available);
