ALTER TABLE ordering_order
    ADD COLUMN cancellation_reason_code varchar(32),
    ADD COLUMN cancellation_detail varchar(200),
    ADD CONSTRAINT chk_order_cancellation_reason_code
        CHECK (
            cancellation_reason_code IS NULL
            OR cancellation_reason_code IN (
                'CHANGED_MIND',
                'ORDER_MISTAKE',
                'WAIT_TOO_LONG',
                'PICKUP_TIME_CONFLICT',
                'PAYMENT_ISSUE',
                'OTHER'
            )
        ),
    ADD CONSTRAINT chk_order_cancellation_reason_fields
        CHECK (
            (state = 'CANCELLED'
                AND cancellation_cause = 'CUSTOMER_REQUEST'
                AND cancellation_reason_code IS NOT NULL
                AND (
                    cancellation_detail IS NULL
                    OR (
                        cancellation_detail = btrim(cancellation_detail)
                        AND length(cancellation_detail) BETWEEN 1 AND 200
                        AND cancellation_detail !~ '[[:cntrl:]]'
                    )
                ))
            OR
            (state = 'CANCELLED'
                AND cancellation_cause = 'PAYMENT_DECLINED'
                AND cancellation_reason_code IS NULL
                AND cancellation_detail IS NULL)
            OR
            (state <> 'CANCELLED'
                AND cancellation_reason_code IS NULL
                AND cancellation_detail IS NULL)
        );

CREATE TABLE ordering_cancellation_command_idempotency (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    order_id uuid NOT NULL REFERENCES ordering_order(id),
    operation varchar(80) NOT NULL
        CHECK (operation = 'CUSTOMER_ORDER_CANCELLATION'),
    idempotency_key varchar(128) NOT NULL
        CHECK (
            length(idempotency_key) BETWEEN 8 AND 128
            AND idempotency_key !~ '[[:cntrl:]]'
        ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    response_status integer NOT NULL CHECK (response_status IN (200, 202)),
    response_body text NOT NULL CHECK (length(btrim(response_body)) > 0),
    response_version integer NOT NULL CHECK (response_version > 0),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, operation, idempotency_key),
    CHECK (retention_expires_at = created_at + interval '90 days')
);

CREATE INDEX idx_ordering_cancellation_idempotency_retention
    ON ordering_cancellation_command_idempotency (retention_expires_at, id);

CREATE TABLE ordering_acceptance_timeout_work (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES ordering_order(id),
    acceptance_deadline_at timestamptz NOT NULL,
    state varchar(24) NOT NULL CHECK (
        state IN ('PENDING', 'CLAIMED', 'COMPLETED', 'MANUAL_REVIEW')
    ),
    completion_outcome varchar(24) CHECK (
        completion_outcome IS NULL OR completion_outcome IN ('REJECTED', 'NOT_APPLICABLE')
    ),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 4),
    next_attempt_at timestamptz,
    claim_token uuid,
    claim_until timestamptz,
    last_failure_code varchar(80),
    source_reference varchar(240) NOT NULL UNIQUE
        CHECK (source_reference = btrim(source_reference) AND length(source_reference) BETWEEN 1 AND 240),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    retention_expires_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (order_id, acceptance_deadline_at),
    CONSTRAINT chk_acceptance_timeout_work_state_fields CHECK (
        (state = 'PENDING'
            AND completion_outcome IS NULL
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claim_until IS NULL
            AND completed_at IS NULL
            AND retention_expires_at IS NULL)
        OR
        (state = 'CLAIMED'
            AND completion_outcome IS NULL
            AND claim_token IS NOT NULL
            AND claim_until IS NOT NULL
            AND completed_at IS NULL
            AND retention_expires_at IS NULL)
        OR
        (state = 'COMPLETED'
            AND completion_outcome IS NOT NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL
            AND completed_at IS NOT NULL
            AND retention_expires_at = completed_at + interval '90 days')
        OR
        (state = 'MANUAL_REVIEW'
            AND completion_outcome IS NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL
            AND completed_at IS NULL
            AND retention_expires_at IS NULL)
    )
);

CREATE INDEX idx_ordering_acceptance_timeout_work_due
    ON ordering_acceptance_timeout_work (next_attempt_at, id)
    WHERE state IN ('PENDING', 'CLAIMED');

CREATE INDEX idx_ordering_acceptance_timeout_work_retention
    ON ordering_acceptance_timeout_work (retention_expires_at, id)
    WHERE state = 'COMPLETED';

ALTER TABLE payment_refund
    ADD COLUMN customer_reason_code varchar(32),
    ADD CONSTRAINT chk_payment_refund_customer_reason CHECK (
        (reason = 'CUSTOMER_ORDER_CANCELLED'
            AND customer_reason_code IN (
                'CHANGED_MIND',
                'ORDER_MISTAKE',
                'WAIT_TOO_LONG',
                'PICKUP_TIME_CONFLICT',
                'PAYMENT_ISSUE',
                'OTHER'
            ))
        OR
        (reason <> 'CUSTOMER_ORDER_CANCELLED' AND customer_reason_code IS NULL)
    );

CREATE TABLE payment_cancellation_recovery_snapshot (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL UNIQUE REFERENCES payment_payment(id),
    order_id uuid NOT NULL UNIQUE REFERENCES ordering_order(id),
    cancellation_order_version bigint NOT NULL CHECK (cancellation_order_version > 0),
    approved_amount_krw bigint NOT NULL CHECK (approved_amount_krw >= 0),
    succeeded_refund_amount_before_cancellation_krw bigint NOT NULL
        CHECK (succeeded_refund_amount_before_cancellation_krw >= 0),
    cancellation_requested_refund_amount_krw bigint NOT NULL
        CHECK (cancellation_requested_refund_amount_krw >= 0),
    cancellation_refund_id uuid UNIQUE,
    refund_source_reference varchar(240) UNIQUE,
    provider_idempotency_key varchar(240) UNIQUE,
    correlation_id varchar(160) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CHECK (
        approved_amount_krw =
            succeeded_refund_amount_before_cancellation_krw
            + cancellation_requested_refund_amount_krw
    ),
    CHECK (
        (cancellation_requested_refund_amount_krw = 0
            AND cancellation_refund_id IS NULL
            AND refund_source_reference IS NULL
            AND provider_idempotency_key IS NULL)
        OR
        (cancellation_requested_refund_amount_krw > 0
            AND cancellation_refund_id IS NOT NULL
            AND refund_source_reference = btrim(refund_source_reference)
            AND length(refund_source_reference) BETWEEN 1 AND 240
            AND provider_idempotency_key = btrim(provider_idempotency_key)
            AND length(provider_idempotency_key) BETWEEN 1 AND 240)
    )
);

ALTER TABLE notification_delivery
    DROP CONSTRAINT notification_delivery_template_check,
    ADD CONSTRAINT chk_notification_delivery_template CHECK (
        template IN (
            'STORE_ACCEPTANCE_WARNING',
            'ORDER_REJECTED',
            'ORDER_READY',
            'ORDER_CANCELLATION_ACCEPTED'
        )
    );

ALTER TABLE operations_reprocessing_case
    DROP CONSTRAINT chk_reprocessing_case_type,
    ADD CONSTRAINT chk_reprocessing_case_type CHECK (
        case_type IN (
            'PAYMENT_RECONCILIATION',
            'NOTIFICATION_DELIVERY',
            'EVENT_PUBLICATION',
            'SETTLEMENT_LATE_ITEM',
            'ACCEPTANCE_TIMEOUT_WORK'
        )
    );
