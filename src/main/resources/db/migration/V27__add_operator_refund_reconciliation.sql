ALTER TABLE operations_operator_permission_grant
    DROP CONSTRAINT chk_operator_permission_vocabulary,
    ADD CONSTRAINT chk_operator_permission_vocabulary CHECK (
        permission IN (
            'EXPIRED_BENEFIT_POLICY_READ',
            'EXPIRED_BENEFIT_POLICY_WRITE',
            'POINT_ACCOUNT_READ',
            'POINT_ADJUSTMENT',
            'POINT_ACCRUAL_POLICY_READ',
            'POINT_ACCRUAL_POLICY_WRITE',
            'ORDER_COMPENSATION_READ',
            'PAYMENT_CANCELLATION_SETUP_REPAIR',
            'CUSTOMER_CANCELLATION_REFUND_RECONCILE'
        )
    );

ALTER TABLE payment_refund
    ADD COLUMN operator_reconciliation_pending boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT chk_payment_refund_operator_reconciliation_pending CHECK (
        NOT operator_reconciliation_pending
        OR (
            reason = 'CUSTOMER_ORDER_CANCELLED'
            AND next_action = 'LOOKUP'
            AND (
                (state = 'UNKNOWN'
                    AND next_attempt_at IS NOT NULL
                    AND claim_token IS NULL
                    AND claim_until IS NULL)
                OR
                (state = 'RECONCILING'
                    AND claim_token IS NOT NULL
                    AND claim_until IS NOT NULL)
            )
        )
    );

CREATE TABLE operations_customer_cancellation_refund_reconciliation_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK (
        length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    order_id uuid NOT NULL,
    cancellation_order_version bigint NOT NULL CHECK (cancellation_order_version > 0),
    operator_reason varchar(500) NOT NULL CHECK (
        operator_reason = btrim(operator_reason)
        AND length(operator_reason) BETWEEN 1 AND 500
        AND operator_reason !~ '[[:cntrl:]]'
    ),
    state varchar(32) NOT NULL CHECK (state = 'LOOKUP_SCHEDULED'),
    response_json text NOT NULL CHECK (length(btrim(response_json)) > 0),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, idempotency_key),
    CHECK (retention_expires_at = created_at + interval '90 days')
);

CREATE INDEX idx_customer_cancellation_refund_reconciliation_retention
    ON operations_customer_cancellation_refund_reconciliation_command (retention_expires_at, id);
