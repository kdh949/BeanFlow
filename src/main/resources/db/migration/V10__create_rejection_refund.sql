ALTER TABLE payment_payment
    ADD COLUMN succeeded_refund_amount_krw bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_payment_succeeded_refund_amount
        CHECK (
            succeeded_refund_amount_krw >= 0
            AND succeeded_refund_amount_krw <= COALESCE(approved_amount_krw, 0)
        );

CREATE TABLE payment_refund (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payment_payment(id),
    order_id uuid NOT NULL,
    requested_amount_krw bigint NOT NULL CHECK (requested_amount_krw > 0),
    succeeded_amount_krw bigint CHECK (
        succeeded_amount_krw IS NULL
        OR succeeded_amount_krw = requested_amount_krw
    ),
    reason varchar(80) NOT NULL CHECK (length(trim(reason)) > 0),
    state varchar(24) NOT NULL CHECK (state IN (
        'REQUESTED', 'PROCESSING', 'SUCCEEDED', 'FAILED',
        'UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW'
    )),
    provider_refund_reference varchar(200),
    provider_idempotency_key varchar(200) NOT NULL UNIQUE
        CHECK (length(trim(provider_idempotency_key)) > 0),
    source_reference varchar(200) NOT NULL UNIQUE
        CHECK (length(trim(source_reference)) > 0),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    next_attempt_at timestamptz,
    provider_request_started_at timestamptz,
    claim_token uuid,
    claim_until timestamptz,
    last_failure_code varchar(80),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (payment_id, source_reference),
    CONSTRAINT chk_payment_refund_terminal_fields CHECK (
        (state = 'SUCCEEDED'
            AND succeeded_amount_krw = requested_amount_krw
            AND provider_refund_reference IS NOT NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state IN ('FAILED', 'MANUAL_REVIEW')
            AND succeeded_amount_krw IS NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state IN ('REQUESTED', 'UNKNOWN')
            AND succeeded_amount_krw IS NULL
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state IN ('PROCESSING', 'RECONCILING')
            AND succeeded_amount_krw IS NULL
            AND claim_token IS NOT NULL
            AND claim_until IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_payment_refund_provider_reference
    ON payment_refund (provider_refund_reference)
    WHERE provider_refund_reference IS NOT NULL;

CREATE UNIQUE INDEX uq_payment_rejection_refund
    ON payment_refund (payment_id)
    WHERE reason = 'STORE_ORDER_REJECTED';

CREATE INDEX idx_payment_refund_due
    ON payment_refund (next_attempt_at, id)
    WHERE state IN ('REQUESTED', 'UNKNOWN', 'PROCESSING', 'RECONCILING');
