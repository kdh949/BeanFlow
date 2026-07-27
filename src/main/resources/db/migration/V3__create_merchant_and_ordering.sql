CREATE TABLE merchant_store (
    id uuid PRIMARY KEY,
    accepting_orders boolean NOT NULL,
    pickup_enabled boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE merchant_menu (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES merchant_store(id),
    name varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    base_price_krw bigint NOT NULL CHECK (base_price_krw >= 0),
    available boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE merchant_menu_option (
    id uuid PRIMARY KEY,
    menu_id uuid NOT NULL REFERENCES merchant_menu(id),
    name varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    additional_price_krw bigint NOT NULL CHECK (additional_price_krw >= 0),
    available boolean NOT NULL
);

CREATE TABLE merchant_menu_configuration (
    id uuid PRIMARY KEY,
    menu_id uuid NOT NULL REFERENCES merchant_menu(id),
    normalized_option_key varchar(2000) NOT NULL,
    available boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (menu_id, normalized_option_key)
);

CREATE TABLE merchant_menu_configuration_requirement (
    id uuid PRIMARY KEY,
    menu_configuration_id uuid NOT NULL REFERENCES merchant_menu_configuration(id),
    sellable_unit_id uuid NOT NULL,
    quantity_per_line_unit bigint NOT NULL CHECK (quantity_per_line_unit > 0),
    UNIQUE (menu_configuration_id, sellable_unit_id)
);

CREATE TABLE ordering_order (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL,
    store_id uuid NOT NULL,
    pickup_slot_id uuid NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN ('PENDING_PAYMENT', 'PAID', 'EXPIRED')),
    subtotal_krw bigint NOT NULL CHECK (subtotal_krw >= 0),
    coupon_discount_krw bigint NOT NULL CHECK (coupon_discount_krw >= 0),
    points_applied_krw bigint NOT NULL CHECK (points_applied_krw >= 0),
    payable_krw bigint NOT NULL CHECK (payable_krw >= 0),
    currency varchar(3) NOT NULL CHECK (currency = 'KRW'),
    reservation_expires_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CHECK (subtotal_krw = coupon_discount_krw + points_applied_krw + payable_krw),
    CHECK (
        (state = 'PENDING_PAYMENT' AND payable_krw > 0 AND reservation_expires_at IS NOT NULL)
        OR
        (state = 'PAID' AND payable_krw = 0 AND reservation_expires_at IS NULL)
        OR
        (state = 'EXPIRED' AND reservation_expires_at IS NOT NULL)
    )
);

CREATE INDEX idx_ordering_order_due
    ON ordering_order (state, reservation_expires_at, id);

CREATE TABLE ordering_order_line (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES ordering_order(id),
    line_sequence integer NOT NULL CHECK (line_sequence >= 0),
    menu_id uuid NOT NULL,
    menu_name varchar(200) NOT NULL,
    option_names_json text NOT NULL,
    sellable_requirements_json text NOT NULL,
    unit_price_krw bigint NOT NULL CHECK (unit_price_krw >= 0),
    quantity bigint NOT NULL CHECK (quantity > 0),
    gross_krw bigint NOT NULL CHECK (gross_krw >= 0),
    coupon_discount_krw bigint NOT NULL CHECK (coupon_discount_krw >= 0),
    points_applied_krw bigint NOT NULL CHECK (points_applied_krw >= 0),
    cash_payable_krw bigint NOT NULL CHECK (cash_payable_krw >= 0),
    UNIQUE (order_id, line_sequence),
    CHECK (gross_krw = coupon_discount_krw + points_applied_krw + cash_payable_krw)
);

CREATE TABLE ordering_idempotency_record (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(80) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK (length(payload_hash) = 64),
    status varchar(24) NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW')),
    intended_order_id uuid NOT NULL,
    order_id uuid,
    response_status integer,
    response_body text,
    response_version integer,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (actor_id, operation, idempotency_key),
    CHECK (
        (status = 'PROCESSING' AND response_status IS NULL AND response_body IS NULL AND completed_at IS NULL)
        OR
        (status IN ('COMPLETED', 'FAILED') AND response_status IS NOT NULL AND response_body IS NOT NULL AND completed_at IS NOT NULL)
        OR
        status = 'MANUAL_REVIEW'
    )
);

CREATE INDEX idx_idempotency_processing
    ON ordering_idempotency_record (status, started_at, id);
