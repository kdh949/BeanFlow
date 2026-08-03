ALTER TABLE operations_reprocessing_case
    ADD COLUMN resolution varchar(120),
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

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
            'PAYMENT_CANCELLATION_SETUP_REPAIR'
        )
    );

CREATE TABLE operations_payment_setup_repair_proposal (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES operations_reprocessing_case(id),
    case_version bigint NOT NULL CHECK (case_version >= 0),
    order_id uuid NOT NULL,
    cancellation_order_version bigint NOT NULL CHECK (cancellation_order_version >= 0),
    payment_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    snapshot_version bigint NOT NULL CHECK (snapshot_version >= 0),
    refund_id uuid NOT NULL,
    requested_amount_krw bigint NOT NULL CHECK (requested_amount_krw > 0),
    refund_source_fingerprint varchar(64) NOT NULL CHECK (refund_source_fingerprint ~ '^[0-9a-f]{64}$'),
    provider_key_fingerprint varchar(64) NOT NULL CHECK (provider_key_fingerprint ~ '^[0-9a-f]{64}$'),
    action varchar(64) NOT NULL CHECK (action = 'RECREATE_MISSING_CANCELLATION_REFUND'),
    state varchar(24) NOT NULL CHECK (
        state IN ('PENDING_APPROVAL', 'EXECUTED', 'REJECTED', 'EXPIRED', 'STALE')
    ),
    proposed_by uuid NOT NULL,
    proposal_reason varchar(500) NOT NULL CHECK (length(btrim(proposal_reason)) BETWEEN 1 AND 500),
    decided_by uuid,
    decision_reason varchar(500),
    correlation_id varchar(160) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    decided_at timestamptz,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CHECK (expires_at = created_at + interval '30 minutes'),
    CHECK (
        (state = 'PENDING_APPROVAL'
            AND decided_by IS NULL AND decision_reason IS NULL AND decided_at IS NULL)
        OR
        (state = 'EXPIRED'
            AND decided_by IS NULL AND decision_reason = 'PROPOSAL_TTL_EXPIRED' AND decided_at IS NOT NULL)
        OR
        (state IN ('EXECUTED', 'REJECTED', 'STALE')
            AND decided_by IS NOT NULL AND decision_reason IS NOT NULL AND decided_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_payment_setup_repair_active_case
    ON operations_payment_setup_repair_proposal (case_id)
    WHERE state = 'PENDING_APPROVAL';

CREATE INDEX idx_payment_setup_repair_expiry
    ON operations_payment_setup_repair_proposal (expires_at, id)
    WHERE state = 'PENDING_APPROVAL';

CREATE TABLE operations_payment_setup_repair_idempotency (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(16) NOT NULL CHECK (operation IN ('PROPOSE', 'DECIDE')),
    idempotency_key varchar(128) NOT NULL CHECK (
        length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    proposal_id uuid NOT NULL REFERENCES operations_payment_setup_repair_proposal(id),
    response_json text NOT NULL CHECK (length(btrim(response_json)) > 0),
    failure_code varchar(64),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, operation, idempotency_key),
    CHECK (retention_expires_at = created_at + interval '90 days')
);

CREATE INDEX idx_payment_setup_repair_idempotency_retention
    ON operations_payment_setup_repair_idempotency (retention_expires_at, id);
