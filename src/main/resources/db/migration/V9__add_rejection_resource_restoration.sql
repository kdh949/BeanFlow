ALTER TABLE fulfillment_pickup_reservation
    DROP CONSTRAINT chk_pickup_reservation_state,
    ADD COLUMN restoration_source_reference varchar(200),
    ADD CONSTRAINT chk_pickup_reservation_state
        CHECK (state IN (
            'RESERVED', 'CONFIRMED', 'EXPIRED', 'RELEASED', 'RELEASED_BY_REJECTION'
        )),
    ADD CONSTRAINT uq_pickup_restoration_source UNIQUE (restoration_source_reference);

ALTER TABLE inventory_stock_reservation
    DROP CONSTRAINT chk_stock_reservation_state,
    ADD COLUMN restoration_source_reference varchar(200),
    ADD CONSTRAINT chk_stock_reservation_state
        CHECK (state IN (
            'RESERVED', 'CONFIRMED', 'EXPIRED', 'RELEASED', 'RELEASED_BY_REJECTION'
        ));

CREATE UNIQUE INDEX uq_stock_restoration_source_unit
    ON inventory_stock_reservation (restoration_source_reference, sellable_unit_id)
    WHERE restoration_source_reference IS NOT NULL;

ALTER TABLE promotion_coupon_issuance
    DROP CONSTRAINT promotion_coupon_issuance_state_check,
    DROP CONSTRAINT promotion_coupon_issuance_check,
    ADD COLUMN original_issuance_id uuid REFERENCES promotion_coupon_issuance(id),
    ADD COLUMN restoration_source_reference varchar(200),
    ADD CONSTRAINT chk_coupon_issuance_state
        CHECK (state IN ('AVAILABLE', 'RESERVED', 'USED', 'RESTORED')),
    ADD CONSTRAINT chk_coupon_issuance_order
        CHECK (
            (state IN ('AVAILABLE', 'RESTORED') AND reserved_order_id IS NULL)
            OR
            (state IN ('RESERVED', 'USED') AND reserved_order_id IS NOT NULL)
        ),
    ADD CONSTRAINT uq_coupon_issuance_restoration_source
        UNIQUE (restoration_source_reference);

ALTER TABLE promotion_coupon_reservation
    DROP CONSTRAINT promotion_coupon_reservation_state_check,
    DROP CONSTRAINT promotion_coupon_reservation_coupon_issuance_id_key,
    ADD COLUMN restoration_source_reference varchar(200),
    ADD CONSTRAINT chk_coupon_reservation_state
        CHECK (state IN ('RESERVED', 'USED', 'RELEASED', 'RESTORED')),
    ADD CONSTRAINT uq_coupon_reservation_restoration_source
        UNIQUE (restoration_source_reference);

CREATE UNIQUE INDEX uq_coupon_active_issuance
    ON promotion_coupon_reservation (coupon_issuance_id)
    WHERE state IN ('RESERVED', 'USED');

ALTER TABLE loyalty_point_lot
    ADD COLUMN original_point_lot_id uuid REFERENCES loyalty_point_lot(id),
    ADD COLUMN compensation_source_reference varchar(240),
    ADD CONSTRAINT uq_point_lot_compensation_source
        UNIQUE (compensation_source_reference);

ALTER TABLE loyalty_point_reservation
    DROP CONSTRAINT loyalty_point_reservation_state_check,
    ADD COLUMN restoration_source_reference varchar(200),
    ADD CONSTRAINT chk_point_reservation_state
        CHECK (state IN ('RESERVED', 'USED', 'RELEASED', 'RESTORED')),
    ADD CONSTRAINT uq_point_reservation_restoration_source
        UNIQUE (restoration_source_reference);

ALTER TABLE loyalty_point_transaction
    DROP CONSTRAINT loyalty_point_transaction_type_check,
    ADD CONSTRAINT chk_point_transaction_type
        CHECK (type IN (
            'USE', 'EXPIRATION', 'RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED'
        ));
