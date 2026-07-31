ALTER TABLE operations_reprocessing_case
    DROP CONSTRAINT operations_reprocessing_case_case_type_check,
    ADD CONSTRAINT chk_reprocessing_case_type
        CHECK (case_type IN ('PAYMENT_RECONCILIATION', 'NOTIFICATION_DELIVERY'));

CREATE TABLE notification_delivery (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    event_type varchar(80) NOT NULL CHECK (length(trim(event_type)) > 0),
    order_id uuid NOT NULL,
    recipient_type varchar(16) NOT NULL CHECK (recipient_type IN ('STORE', 'CUSTOMER')),
    recipient_id uuid NOT NULL,
    logical_channel varchar(32) NOT NULL CHECK (
        logical_channel IN ('STORE_OPERATIONS', 'CUSTOMER_APP')
    ),
    template varchar(48) NOT NULL CHECK (
        template IN ('STORE_ACCEPTANCE_WARNING', 'ORDER_REJECTED', 'ORDER_READY')
    ),
    payload_json text NOT NULL CHECK (length(trim(payload_json)) > 0),
    state varchar(24) NOT NULL CHECK (
        state IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'RETRY_SCHEDULED', 'MANUAL_REVIEW')
    ),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 4),
    next_attempt_at timestamptz,
    provider_idempotency_key varchar(200) NOT NULL UNIQUE
        CHECK (length(trim(provider_idempotency_key)) > 0),
    provider_delivery_reference varchar(200),
    claim_token uuid,
    claim_until timestamptz,
    last_failure_code varchar(80),
    correlation_id varchar(160) NOT NULL CHECK (length(trim(correlation_id)) > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (event_id, recipient_id, logical_channel),
    CONSTRAINT chk_notification_delivery_state_fields CHECK (
        (state IN ('PENDING', 'RETRY_SCHEDULED')
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'PROCESSING'
            AND claim_token IS NOT NULL
            AND claim_until IS NOT NULL)
        OR
        (state = 'SUCCEEDED'
            AND provider_delivery_reference IS NOT NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'MANUAL_REVIEW'
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
    )
);

CREATE UNIQUE INDEX uq_notification_provider_delivery_reference
    ON notification_delivery (provider_delivery_reference)
    WHERE provider_delivery_reference IS NOT NULL;

CREATE INDEX idx_notification_delivery_due
    ON notification_delivery (next_attempt_at, id)
    WHERE state IN ('PENDING', 'RETRY_SCHEDULED', 'PROCESSING');
