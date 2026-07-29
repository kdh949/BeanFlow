ALTER TABLE payment_payment
    DROP CONSTRAINT payment_payment_type_check,
    DROP CONSTRAINT payment_payment_approval_state_check,
    DROP CONSTRAINT payment_payment_approved_amount_krw_check;

ALTER TABLE payment_payment
    ALTER COLUMN benefit_snapshot_reference DROP NOT NULL,
    ALTER COLUMN approved_at DROP NOT NULL,
    ALTER COLUMN approved_amount_krw DROP NOT NULL,
    ADD COLUMN customer_id uuid,
    ADD COLUMN payment_method_id uuid,
    ADD COLUMN requested_amount_krw bigint NOT NULL DEFAULT 0,
    ADD COLUMN provider_transaction_reference varchar(200),
    ADD COLUMN last_failure_code varchar(80),
    ADD COLUMN created_at timestamptz,
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

UPDATE payment_payment
   SET created_at = updated_at
 WHERE created_at IS NULL;

ALTER TABLE payment_payment
    ALTER COLUMN requested_amount_krw DROP DEFAULT,
    ALTER COLUMN created_at SET NOT NULL,
    ADD CONSTRAINT chk_payment_type
        CHECK (type IN ('EXTERNAL', 'BENEFIT_ONLY')),
    ADD CONSTRAINT chk_payment_approval_state
        CHECK (approval_state IN (
            'READY', 'APPROVING', 'APPROVED', 'FAILED',
            'UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW'
        )),
    ADD CONSTRAINT chk_payment_amounts
        CHECK (
            (type = 'BENEFIT_ONLY'
                AND requested_amount_krw = 0
                AND approved_amount_krw = 0
                AND approval_state = 'APPROVED'
                AND payment_method_id IS NULL
                AND benefit_snapshot_reference IS NOT NULL)
            OR
            (type = 'EXTERNAL'
                AND customer_id IS NOT NULL
                AND payment_method_id IS NOT NULL
                AND requested_amount_krw > 0
                AND (approved_amount_krw IS NULL OR approved_amount_krw >= 0)
                AND (approval_state <> 'APPROVED' OR approved_amount_krw IS NOT NULL)
                AND benefit_snapshot_reference IS NULL)
        );

CREATE UNIQUE INDEX uq_payment_provider_transaction
    ON payment_payment (provider_transaction_reference)
    WHERE provider_transaction_reference IS NOT NULL;

CREATE TABLE payment_method (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL,
    provider varchar(40) NOT NULL CHECK (length(trim(provider)) > 0),
    token_reference varchar(200) NOT NULL CHECK (length(trim(token_reference)) > 0),
    display_alias varchar(80) NOT NULL CHECK (length(trim(display_alias)) > 0),
    card_brand varchar(40) NOT NULL CHECK (length(trim(card_brand)) > 0),
    last_four varchar(4) NOT NULL CHECK (last_four ~ '^[0-9]{4}$'),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (customer_id, provider, token_reference)
);

ALTER TABLE payment_payment
    ADD CONSTRAINT fk_payment_method
        FOREIGN KEY (payment_method_id) REFERENCES payment_method(id);

CREATE TABLE payment_idempotency_record (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(80) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK (length(payload_hash) = 64),
    payment_id uuid NOT NULL,
    order_id uuid NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN (
        'PROCESSING', 'UNKNOWN', 'RECONCILING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW'
    )),
    response_status integer,
    response_body text,
    started_at timestamptz NOT NULL,
    terminal_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (actor_id, operation, idempotency_key)
);

CREATE INDEX idx_payment_idempotency_non_terminal
    ON payment_idempotency_record (status, started_at, id)
    WHERE status IN ('PROCESSING', 'UNKNOWN', 'RECONCILING');

CREATE TABLE payment_reconciliation (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payment_payment(id),
    kind varchar(24) NOT NULL CHECK (kind IN ('APPROVAL_LOOKUP', 'LATE_VOID', 'LATE_REFUND')),
    status varchar(24) NOT NULL CHECK (status IN (
        'SCHEDULED', 'PROCESSING', 'RETRY_SCHEDULED', 'SUCCEEDED', 'MANUAL_REVIEW'
    )),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    next_attempt_at timestamptz NOT NULL,
    claim_token uuid,
    claim_until timestamptz,
    source_reference varchar(200) NOT NULL UNIQUE,
    last_failure_code varchar(80),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (payment_id, kind)
);

CREATE INDEX idx_payment_reconciliation_due
    ON payment_reconciliation (next_attempt_at, id)
    WHERE status IN ('SCHEDULED', 'RETRY_SCHEDULED', 'PROCESSING');

CREATE TABLE operations_reprocessing_case (
    id uuid PRIMARY KEY,
    case_type varchar(48) NOT NULL CHECK (case_type IN ('PAYMENT_RECONCILIATION')),
    owner_reference varchar(200) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('OPEN', 'RUNNING', 'RESOLVED', 'MANUAL_REVIEW')),
    reason varchar(200) NOT NULL CHECK (length(trim(reason)) > 0),
    correlation_id varchar(160) NOT NULL CHECK (length(trim(correlation_id)) > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (case_type, owner_reference)
);

ALTER TABLE fulfillment_pickup_reservation
    DROP CONSTRAINT fulfillment_pickup_reservation_state_check,
    ADD CONSTRAINT chk_pickup_reservation_state
        CHECK (state IN ('RESERVED', 'CONFIRMED', 'EXPIRED', 'RELEASED'));

ALTER TABLE inventory_stock_reservation
    DROP CONSTRAINT inventory_stock_reservation_state_check,
    ADD CONSTRAINT chk_stock_reservation_state
        CHECK (state IN ('RESERVED', 'CONFIRMED', 'EXPIRED', 'RELEASED'));

DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT conname
      INTO constraint_name
      FROM pg_constraint
     WHERE conrelid = 'ordering_order'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%PENDING_PAYMENT%'
       AND pg_get_constraintdef(oid) LIKE '%reservation_expires_at%';

    IF constraint_name IS NULL THEN
        RAISE EXCEPTION 'ordering_order payment boundary constraint was not found';
    END IF;

    EXECUTE format('ALTER TABLE ordering_order DROP CONSTRAINT %I', constraint_name);
END
$$;

ALTER TABLE ordering_order
    DROP CONSTRAINT ordering_order_state_check,
    ADD CONSTRAINT chk_order_state
        CHECK (state IN ('PENDING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED')),
    ADD CONSTRAINT chk_order_state_payment_boundary
        CHECK (
            (state = 'PENDING_PAYMENT' AND payable_krw > 0 AND reservation_expires_at IS NOT NULL)
            OR
            (state = 'PAID' AND reservation_expires_at IS NULL)
            OR
            (state = 'EXPIRED' AND reservation_expires_at IS NOT NULL)
            OR
            (state = 'CANCELLED' AND reservation_expires_at IS NULL)
        );
