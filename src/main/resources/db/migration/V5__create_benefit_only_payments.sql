CREATE TABLE payment_payment (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    type varchar(24) NOT NULL CHECK (type = 'BENEFIT_ONLY'),
    approval_state varchar(24) NOT NULL CHECK (approval_state = 'APPROVED'),
    approved_amount_krw bigint NOT NULL CHECK (approved_amount_krw = 0),
    currency varchar(3) NOT NULL CHECK (currency = 'KRW'),
    benefit_snapshot_reference varchar(200) NOT NULL
        CHECK (length(trim(benefit_snapshot_reference)) > 0),
    source_reference varchar(200) NOT NULL UNIQUE
        CHECK (length(trim(source_reference)) > 0),
    correlation_id varchar(160) NOT NULL CHECK (length(trim(correlation_id)) > 0),
    approved_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_payment_order_type
    ON payment_payment (order_id, type);
