CREATE TABLE promotion_campaign (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL,
    active boolean NOT NULL,
    discount_type varchar(24) NOT NULL CHECK (discount_type IN ('FIXED_KRW', 'RATE_BPS')),
    fixed_amount_krw bigint,
    rate_bps integer,
    minimum_eligible_subtotal_krw bigint NOT NULL DEFAULT 0 CHECK (minimum_eligible_subtotal_krw >= 0),
    maximum_discount_krw bigint,
    all_menus_eligible boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CHECK (
        (discount_type = 'FIXED_KRW' AND fixed_amount_krw > 0 AND rate_bps IS NULL AND maximum_discount_krw IS NULL)
        OR
        (discount_type = 'RATE_BPS' AND fixed_amount_krw IS NULL AND rate_bps BETWEEN 1 AND 10000)
    ),
    CHECK (maximum_discount_krw IS NULL OR maximum_discount_krw > 0)
);

CREATE TABLE promotion_campaign_eligible_menu (
    id uuid PRIMARY KEY,
    campaign_id uuid NOT NULL REFERENCES promotion_campaign(id),
    menu_id uuid NOT NULL,
    UNIQUE (campaign_id, menu_id)
);

CREATE TABLE promotion_coupon_issuance (
    id uuid PRIMARY KEY,
    campaign_id uuid NOT NULL REFERENCES promotion_campaign(id),
    customer_id uuid NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('AVAILABLE', 'RESERVED', 'USED')),
    coupon_expires_at timestamptz NOT NULL,
    reserved_order_id uuid,
    version bigint NOT NULL DEFAULT 0,
    CHECK (
        (state = 'AVAILABLE' AND reserved_order_id IS NULL)
        OR
        (state IN ('RESERVED', 'USED') AND reserved_order_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_coupon_reserved_order
    ON promotion_coupon_issuance (reserved_order_id)
    WHERE reserved_order_id IS NOT NULL;

CREATE TABLE promotion_coupon_reservation (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    coupon_issuance_id uuid NOT NULL UNIQUE REFERENCES promotion_coupon_issuance(id),
    state varchar(24) NOT NULL CHECK (state IN ('RESERVED', 'USED', 'RELEASED')),
    discount_krw bigint NOT NULL CHECK (discount_krw > 0),
    eligible_line_sequences varchar(1000) NOT NULL,
    discount_type varchar(24) NOT NULL CHECK (discount_type IN ('FIXED_KRW', 'RATE_BPS')),
    fixed_amount_krw bigint,
    rate_bps integer,
    minimum_eligible_subtotal_krw bigint NOT NULL CHECK (minimum_eligible_subtotal_krw >= 0),
    maximum_discount_krw bigint,
    reservation_expires_at timestamptz NOT NULL,
    source_reference varchar(160) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX idx_coupon_reservation_due
    ON promotion_coupon_reservation (state, reservation_expires_at, id);

CREATE TABLE loyalty_point_account (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL UNIQUE,
    available_points_krw bigint NOT NULL CHECK (available_points_krw >= 0),
    reserved_points_krw bigint NOT NULL DEFAULT 0 CHECK (reserved_points_krw >= 0),
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE loyalty_point_lot (
    id uuid PRIMARY KEY,
    point_account_id uuid NOT NULL REFERENCES loyalty_point_account(id),
    available_amount_krw bigint NOT NULL CHECK (available_amount_krw >= 0),
    reserved_amount_krw bigint NOT NULL DEFAULT 0 CHECK (reserved_amount_krw >= 0),
    expires_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX idx_point_lot_allocation
    ON loyalty_point_lot (point_account_id, expires_at, id);

CREATE TABLE loyalty_point_reservation (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    point_account_id uuid NOT NULL REFERENCES loyalty_point_account(id),
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    state varchar(24) NOT NULL CHECK (state IN ('RESERVED', 'USED', 'RELEASED')),
    reservation_expires_at timestamptz NOT NULL,
    source_reference varchar(160) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE loyalty_point_reservation_allocation (
    id uuid PRIMARY KEY,
    point_reservation_id uuid NOT NULL REFERENCES loyalty_point_reservation(id),
    point_lot_id uuid NOT NULL REFERENCES loyalty_point_lot(id),
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    UNIQUE (point_reservation_id, point_lot_id)
);

CREATE TABLE loyalty_point_transaction (
    id uuid PRIMARY KEY,
    point_account_id uuid NOT NULL REFERENCES loyalty_point_account(id),
    point_lot_id uuid NOT NULL REFERENCES loyalty_point_lot(id),
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    type varchar(24) NOT NULL CHECK (type IN ('USE', 'EXPIRATION')),
    source_reference varchar(240) NOT NULL UNIQUE,
    occurred_at timestamptz NOT NULL
);
