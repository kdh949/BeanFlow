ALTER TABLE payment_payment
    DROP CONSTRAINT chk_payment_amounts,
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
                AND requested_amount_krw > 0
                AND (approved_amount_krw IS NULL OR approved_amount_krw >= 0)
                AND (approval_state <> 'APPROVED' OR approved_amount_krw IS NOT NULL)
                AND benefit_snapshot_reference IS NULL)
        );

CREATE TABLE payment_one_time_attempt (
    payment_id uuid PRIMARY KEY REFERENCES payment_payment(id),
    provider_order_id varchar(64) NOT NULL
        CHECK (provider_order_id ~ '^[A-Za-z0-9_-]{6,64}$'),
    customer_key varchar(50) NOT NULL
        CHECK (customer_key ~ '^bf_[A-Za-z0-9_-]{43}$'),
    order_name varchar(100) NOT NULL
        CHECK (length(order_name) BETWEEN 1 AND 100 AND order_name = trim(order_name)),
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    currency varchar(3) NOT NULL CHECK (currency = 'KRW'),
    state varchar(24) NOT NULL CHECK (state IN (
        'READY', 'CONFIRMING', 'APPROVED', 'FAILED',
        'UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW'
    )),
    payment_key varchar(200),
    callback_payload_hash varchar(64),
    provider_idempotency_key varchar(300) NOT NULL
        CHECK (length(provider_idempotency_key) BETWEEN 1 AND 300),
    claim_token uuid,
    claimed_at timestamptz,
    success_url varchar(500) NOT NULL CHECK (success_url ~ '^https?://'),
    fail_url varchar(500) NOT NULL CHECK (fail_url ~ '^https?://'),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_one_time_provider_order UNIQUE (provider_order_id),
    CONSTRAINT uq_payment_one_time_payment_key UNIQUE (payment_key),
    CONSTRAINT uq_payment_one_time_provider_idempotency UNIQUE (provider_idempotency_key),
    CONSTRAINT ck_payment_one_time_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_payment_one_time_callback CHECK (
        (state = 'READY'
            AND payment_key IS NULL
            AND callback_payload_hash IS NULL
            AND claim_token IS NULL
            AND claimed_at IS NULL)
        OR
        (state = 'CONFIRMING'
            AND payment_key IS NOT NULL
            AND length(trim(payment_key)) > 0
            AND callback_payload_hash ~ '^[0-9a-f]{64}$'
            AND claim_token IS NOT NULL
            AND claimed_at IS NOT NULL)
        OR
        (state IN ('APPROVED', 'FAILED', 'UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW')
            AND payment_key IS NOT NULL
            AND length(trim(payment_key)) > 0
            AND callback_payload_hash ~ '^[0-9a-f]{64}$'
            AND claim_token IS NULL
            AND claimed_at IS NULL)
    )
);

CREATE INDEX idx_payment_one_time_attempt_state
    ON payment_one_time_attempt (state, updated_at, payment_id);

CREATE OR REPLACE FUNCTION beanflow_guard_payment_one_time_attempt()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.provider_order_id IS DISTINCT FROM NEW.provider_order_id
        OR OLD.customer_key IS DISTINCT FROM NEW.customer_key
        OR OLD.order_name IS DISTINCT FROM NEW.order_name
        OR OLD.amount_krw IS DISTINCT FROM NEW.amount_krw
        OR OLD.currency IS DISTINCT FROM NEW.currency
        OR OLD.provider_idempotency_key IS DISTINCT FROM NEW.provider_idempotency_key
        OR OLD.success_url IS DISTINCT FROM NEW.success_url
        OR OLD.fail_url IS DISTINCT FROM NEW.fail_url
        OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'one-time payment prepare snapshot is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_payment_one_time_attempt_guard
BEFORE UPDATE ON payment_one_time_attempt
FOR EACH ROW
EXECUTE FUNCTION beanflow_guard_payment_one_time_attempt();

CREATE OR REPLACE FUNCTION beanflow_reject_payment_one_time_attempt_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'one-time payment attempt is append-retained';
END
$$;

CREATE TRIGGER trg_payment_one_time_attempt_delete
BEFORE DELETE ON payment_one_time_attempt
FOR EACH ROW
EXECUTE FUNCTION beanflow_reject_payment_one_time_attempt_delete();
