SET LOCAL lock_timeout = '5s';

ALTER TABLE payment_refund
    DROP CONSTRAINT chk_payment_refund_command_shape,
    DROP CONSTRAINT chk_payment_refund_operator_reconciliation_pending,
    ADD COLUMN support_resolution_id uuid,
    ADD COLUMN resolution_order_state varchar(16),
    ADD COLUMN resolution_order_completed_at timestamptz,
    ADD COLUMN resolution_order_version bigint;

ALTER TABLE support_action_request
    DROP CONSTRAINT fk_support_action_request_terminal_execution,
    DROP CONSTRAINT uq_support_action_request_terminal_execution,
    DROP CONSTRAINT chk_support_action_request_terminal_execution,
    ADD COLUMN terminal_resolution_id uuid,
    ADD CONSTRAINT uq_support_action_request_id_action UNIQUE (id, action);

CREATE TABLE support_post_acceptance_resolution (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case (id),
    request_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    revision_number integer NOT NULL CHECK (revision_number > 0),
    action varchar(40) NOT NULL CHECK (action = 'POST_ACCEPTANCE_RESOLUTION'),
    action_payload_digest varchar(64) NOT NULL CHECK (action_payload_digest ~ '^[0-9a-f]{64}$'),
    order_id uuid NOT NULL,
    trigger_order_state varchar(32) NOT NULL,
    trigger_order_version bigint NOT NULL CHECK (trigger_order_version >= 0),
    requester_actor_id uuid NOT NULL,
    executor_actor_id uuid NOT NULL,
    outcome varchar(40) NOT NULL,
    responsibility varchar(20) NOT NULL,
    cash_refund_krw bigint NOT NULL,
    restore_points boolean NOT NULL,
    restore_coupon boolean NOT NULL,
    settlement_adjustment_krw bigint,
    evidence_digest varchar(64) NOT NULL CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    idempotency_key varchar(128) NOT NULL CHECK (
        idempotency_key = btrim(idempotency_key)
        AND length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    state varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version >= 0),
    CONSTRAINT fk_support_resolution_request_action
        FOREIGN KEY (request_id, action)
        REFERENCES support_action_request (id, action),
    CONSTRAINT fk_support_resolution_revision
        FOREIGN KEY (request_id, revision_id, revision_number)
        REFERENCES support_action_revision (request_id, id, revision_number),
    CONSTRAINT uq_support_resolution_request UNIQUE (request_id),
    CONSTRAINT uq_support_resolution_request_id UNIQUE (request_id, id),
    CONSTRAINT uq_support_resolution_command UNIQUE (requester_actor_id, idempotency_key),
    CONSTRAINT chk_support_resolution_actor CHECK (requester_actor_id <> executor_actor_id),
    CONSTRAINT chk_support_resolution_trigger_state CHECK (
        trigger_order_state IN ('PREPARING', 'READY', 'COMPLETED')
    ),
    CONSTRAINT chk_support_resolution_state CHECK (
        state IN (
            'PLANNED', 'EXECUTING', 'PARTIALLY_RESOLVED',
            'RECONCILING', 'RESOLVED', 'MANUAL_REVIEW'
        )
    ),
    CONSTRAINT chk_support_resolution_plan CHECK (
        (outcome IN ('FULL_REFUND', 'PARTIAL_REFUND') AND cash_refund_krw > 0)
        OR
        (outcome IN ('NO_MONETARY_RESOLUTION', 'MANUAL_SETTLEMENT_REVIEW')
            AND cash_refund_krw = 0
            AND NOT restore_points
            AND NOT restore_coupon)
    ),
    CONSTRAINT chk_support_resolution_responsibility CHECK (
        (responsibility IN ('STORE', 'SHARED')
            AND (
                (outcome = 'MANUAL_SETTLEMENT_REVIEW' AND settlement_adjustment_krw IS NULL)
                OR settlement_adjustment_krw < 0
            ))
        OR
        (responsibility IN ('CUSTOMER', 'PLATFORM', 'UNDETERMINED')
            AND settlement_adjustment_krw IS NULL)
    ),
    CONSTRAINT chk_support_resolution_time CHECK (
        updated_at >= created_at
        AND retention_expires_at = created_at + INTERVAL '90 days'
    )
);

CREATE INDEX idx_support_resolution_case
    ON support_post_acceptance_resolution (support_case_id, updated_at DESC, id DESC);

CREATE INDEX idx_support_resolution_order
    ON support_post_acceptance_resolution (order_id, updated_at DESC, id DESC);

ALTER TABLE payment_refund
    ADD CONSTRAINT fk_payment_refund_support_resolution
        FOREIGN KEY (support_resolution_id)
        REFERENCES support_post_acceptance_resolution (id),
    ADD CONSTRAINT uq_payment_refund_support_resolution UNIQUE (support_resolution_id),
    ADD CONSTRAINT chk_payment_refund_command_shape CHECK (
        (reason NOT IN ('PARTIAL_REFUND', 'CUSTOMER_ORDER_CANCELLED', 'SUPPORT_POST_ACCEPTANCE_RESOLUTION')
            AND actor_id IS NULL
            AND idempotency_key IS NULL
            AND payload_hash IS NULL
            AND correlation_id IS NULL
            AND point_restoration_policy_version_id IS NULL
            AND point_restoration_policy_trigger IS NULL
            AND point_restoration_policy_benefit_type IS NULL
            AND point_restoration_policy_mode IS NULL
            AND point_restoration_policy_validity_days IS NULL
            AND support_resolution_id IS NULL
            AND resolution_order_state IS NULL
            AND resolution_order_completed_at IS NULL
            AND resolution_order_version IS NULL)
        OR
        (reason = 'PARTIAL_REFUND'
            AND actor_id IS NOT NULL
            AND idempotency_key IS NOT NULL
            AND length(idempotency_key) BETWEEN 8 AND 128
            AND payload_hash ~ '^[0-9a-f]{64}$'
            AND correlation_id IS NOT NULL
            AND length(btrim(correlation_id)) > 0
            AND point_restoration_policy_version_id IS NOT NULL
            AND point_restoration_policy_trigger = 'PARTIAL_REFUND'
            AND point_restoration_policy_benefit_type = 'POINTS'
            AND point_restoration_policy_mode IN (
                'COMPENSATE_WITH_NEW_ISSUANCE', 'PRESERVE_ORIGINAL_EXPIRY'
            )
            AND point_restoration_policy_validity_days BETWEEN 1 AND 365
            AND support_resolution_id IS NULL
            AND resolution_order_state IS NULL
            AND resolution_order_completed_at IS NULL
            AND resolution_order_version IS NULL)
        OR
        (reason = 'CUSTOMER_ORDER_CANCELLED'
            AND actor_id IS NULL
            AND idempotency_key IS NULL
            AND payload_hash IS NULL
            AND correlation_id IS NOT NULL
            AND length(btrim(correlation_id)) > 0
            AND point_restoration_policy_version_id IS NULL
            AND point_restoration_policy_trigger IS NULL
            AND point_restoration_policy_benefit_type IS NULL
            AND point_restoration_policy_mode IS NULL
            AND point_restoration_policy_validity_days IS NULL
            AND support_resolution_id IS NULL
            AND resolution_order_state IS NULL
            AND resolution_order_completed_at IS NULL
            AND resolution_order_version IS NULL)
        OR
        (reason = 'SUPPORT_POST_ACCEPTANCE_RESOLUTION'
            AND actor_id IS NOT NULL
            AND idempotency_key IS NOT NULL
            AND length(idempotency_key) BETWEEN 8 AND 128
            AND payload_hash ~ '^[0-9a-f]{64}$'
            AND correlation_id IS NOT NULL
            AND length(btrim(correlation_id)) > 0
            AND point_restoration_policy_version_id IS NULL
            AND point_restoration_policy_trigger IS NULL
            AND point_restoration_policy_benefit_type IS NULL
            AND point_restoration_policy_mode IS NULL
            AND point_restoration_policy_validity_days IS NULL
            AND support_resolution_id IS NOT NULL
            AND resolution_order_state IN ('PREPARING', 'READY', 'COMPLETED')
            AND resolution_order_version >= 0
            AND ((resolution_order_state = 'COMPLETED') = (resolution_order_completed_at IS NOT NULL)))
    ),
    ADD CONSTRAINT chk_payment_refund_operator_reconciliation_pending CHECK (
        NOT operator_reconciliation_pending
        OR (
            reason IN ('CUSTOMER_ORDER_CANCELLED', 'SUPPORT_POST_ACCEPTANCE_RESOLUTION')
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

ALTER TABLE loyalty_point_reservation
    DROP CONSTRAINT chk_point_reservation_restoration_metadata,
    ADD CONSTRAINT chk_point_reservation_restoration_metadata CHECK (
        (state = 'RESTORED'
            AND restoration_source_reference IS NOT NULL
            AND (
                (restoration_trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
                    AND restoration_policy_version_id IS NOT NULL)
                OR
                (restoration_trigger = 'POST_ACCEPTANCE_RESOLUTION'
                    AND restoration_policy_version_id IS NULL)
            ))
        OR
        (state <> 'RESTORED'
            AND restoration_source_reference IS NULL
            AND restoration_trigger IS NULL
            AND restoration_policy_version_id IS NULL)
    );

ALTER TABLE loyalty_point_transaction
    DROP CONSTRAINT chk_point_transaction_restoration_metadata,
    ADD CONSTRAINT chk_point_transaction_restoration_metadata CHECK (
        (type NOT IN ('RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED')
            AND refund_id IS NULL
            AND order_line_id IS NULL
            AND point_reservation_allocation_id IS NULL
            AND restoration_trigger IS NULL
            AND restoration_policy_version_id IS NULL
            AND restoration_disposition IS NULL)
        OR
        (type IN ('RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED')
            AND refund_id IS NOT NULL
            AND order_line_id IS NOT NULL
            AND point_reservation_allocation_id IS NOT NULL
            AND restoration_trigger = 'PARTIAL_REFUND'
            AND restoration_policy_version_id IS NOT NULL
            AND (
                (type = 'RESTORE' AND restoration_disposition = 'ORIGINAL_LOT')
                OR (type = 'COMPENSATION' AND restoration_disposition = 'COMPENSATION_LOT')
                OR (type = 'RESTORE_SKIPPED_EXPIRED' AND restoration_disposition = 'SKIPPED_EXPIRED')
            ))
        OR
        (type IN ('RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED')
            AND refund_id IS NULL
            AND order_line_id IS NULL
            AND point_reservation_allocation_id IS NOT NULL
            AND restoration_trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
            AND restoration_policy_version_id IS NOT NULL
            AND (
                (type = 'RESTORE' AND restoration_disposition = 'ORIGINAL_LOT')
                OR (type = 'COMPENSATION' AND restoration_disposition = 'COMPENSATION_LOT')
                OR (type = 'RESTORE_SKIPPED_EXPIRED' AND restoration_disposition = 'SKIPPED_EXPIRED')
            ))
        OR
        (type IN ('RESTORE', 'RESTORE_SKIPPED_EXPIRED')
            AND refund_id IS NULL
            AND order_line_id IS NULL
            AND point_reservation_allocation_id IS NOT NULL
            AND restoration_trigger = 'POST_ACCEPTANCE_RESOLUTION'
            AND restoration_policy_version_id IS NULL
            AND (
                (type = 'RESTORE' AND restoration_disposition = 'ORIGINAL_LOT')
                OR (type = 'RESTORE_SKIPPED_EXPIRED' AND restoration_disposition = 'SKIPPED_EXPIRED')
            ))
    );

CREATE UNIQUE INDEX uq_point_transaction_support_resolution_allocation
    ON loyalty_point_transaction (point_reservation_allocation_id)
    WHERE restoration_trigger = 'POST_ACCEPTANCE_RESOLUTION';

ALTER TABLE promotion_coupon_reservation
    DROP CONSTRAINT chk_coupon_reservation_restoration_metadata,
    ADD CONSTRAINT chk_coupon_reservation_restoration_metadata CHECK (
        (state = 'RESTORED'
            AND restoration_source_reference IS NOT NULL
            AND restoration_disposition IN (
                'ORIGINAL_RESTORED', 'COMPENSATION_ISSUED', 'SKIPPED_EXPIRED'
            )
            AND (
                (restoration_trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
                    AND restoration_policy_version_id IS NOT NULL)
                OR
                (restoration_trigger = 'POST_ACCEPTANCE_RESOLUTION'
                    AND restoration_policy_version_id IS NULL
                    AND restoration_disposition <> 'COMPENSATION_ISSUED')
            ))
        OR
        (state <> 'RESTORED'
            AND restoration_source_reference IS NULL
            AND restoration_trigger IS NULL
            AND restoration_policy_version_id IS NULL
            AND restoration_disposition IS NULL)
    );

CREATE TABLE loyalty_support_resolution_point_restoration (
    id uuid PRIMARY KEY,
    resolution_id uuid NOT NULL REFERENCES support_post_acceptance_resolution(id),
    order_id uuid NOT NULL,
    point_reservation_id uuid REFERENCES loyalty_point_reservation(id),
    source_reference varchar(240) NOT NULL UNIQUE CHECK (
        source_reference = btrim(source_reference)
        AND length(source_reference) BETWEEN 1 AND 240
        AND source_reference !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    disposition varchar(32) NOT NULL CHECK (
        disposition IN ('RESTORED', 'PARTIALLY_RESTORED', 'SKIPPED_EXPIRED', 'NOT_ELIGIBLE')
    ),
    restored_amount_krw bigint NOT NULL CHECK (restored_amount_krw >= 0),
    restored_at timestamptz NOT NULL,
    UNIQUE (resolution_id),
    CHECK (
        (disposition IN ('RESTORED', 'PARTIALLY_RESTORED') AND restored_amount_krw > 0)
        OR
        (disposition IN ('SKIPPED_EXPIRED', 'NOT_ELIGIBLE') AND restored_amount_krw = 0)
    )
);

CREATE TABLE promotion_support_resolution_coupon_restoration (
    id uuid PRIMARY KEY,
    resolution_id uuid NOT NULL REFERENCES support_post_acceptance_resolution(id),
    order_id uuid NOT NULL,
    coupon_reservation_id uuid REFERENCES promotion_coupon_reservation(id),
    source_reference varchar(240) NOT NULL UNIQUE CHECK (
        source_reference = btrim(source_reference)
        AND length(source_reference) BETWEEN 1 AND 240
        AND source_reference !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    disposition varchar(32) NOT NULL CHECK (
        disposition IN ('RESTORED', 'SKIPPED_EXPIRED', 'NOT_ELIGIBLE')
    ),
    restored_at timestamptz NOT NULL,
    UNIQUE (resolution_id)
);

CREATE TABLE support_post_acceptance_resolution_step (
    id uuid PRIMARY KEY,
    resolution_id uuid NOT NULL REFERENCES support_post_acceptance_resolution (id),
    step_type varchar(32) NOT NULL,
    state varchar(24) NOT NULL,
    source_reference varchar(240) NOT NULL UNIQUE CHECK (
        source_reference = btrim(source_reference)
        AND length(source_reference) BETWEEN 1 AND 240
        AND source_reference !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    attempt_count integer NOT NULL CHECK (attempt_count >= 0),
    next_attempt_at timestamptz,
    result_reference varchar(240) CHECK (
        result_reference IS NULL
        OR (
            result_reference = btrim(result_reference)
            AND length(result_reference) BETWEEN 1 AND 240
            AND result_reference !~ '[[:cntrl:]]'
        )
    ),
    failure_code varchar(80) CHECK (
        failure_code IS NULL OR failure_code ~ '^[A-Z0-9_]{1,80}$'
    ),
    claim_token uuid,
    claim_until timestamptz,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version >= 0),
    CONSTRAINT uq_support_resolution_step_type UNIQUE (resolution_id, step_type),
    CONSTRAINT chk_support_resolution_step_type CHECK (
        step_type IN (
            'PAYMENT_REFUND', 'POINT_RESTORATION', 'COUPON_RESTORATION',
            'SETTLEMENT_ADJUSTMENT', 'CUSTOMER_NOTIFICATION'
        )
    ),
    CONSTRAINT chk_support_resolution_step_state CHECK (
        state IN (
            'PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'SUCCEEDED', 'NOT_REQUIRED',
            'UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW', 'BLOCKED'
        )
    ),
    CONSTRAINT chk_support_resolution_step_result CHECK (
        (state = 'PENDING' AND attempt_count = 0
            AND next_attempt_at IS NOT NULL AND result_reference IS NULL
            AND failure_code IS NULL AND claim_token IS NULL AND claim_until IS NULL)
        OR
        (state = 'PROCESSING' AND attempt_count > 0
            AND next_attempt_at IS NULL AND result_reference IS NULL
            AND failure_code IS NULL AND claim_token IS NOT NULL AND claim_until IS NOT NULL)
        OR
        (state = 'RETRY_SCHEDULED' AND attempt_count > 0
            AND next_attempt_at IS NOT NULL AND result_reference IS NULL
            AND failure_code IS NOT NULL AND claim_token IS NULL AND claim_until IS NULL)
        OR
        (state = 'SUCCEEDED' AND attempt_count > 0
            AND next_attempt_at IS NULL AND result_reference IS NOT NULL
            AND failure_code IS NULL AND claim_token IS NULL AND claim_until IS NULL)
        OR
        (state IN ('NOT_REQUIRED', 'BLOCKED') AND attempt_count = 0
            AND next_attempt_at IS NULL AND result_reference IS NULL
            AND failure_code IS NULL AND claim_token IS NULL AND claim_until IS NULL)
        OR
        (state = 'UNKNOWN' AND attempt_count > 0
            AND next_attempt_at IS NOT NULL AND result_reference IS NULL
            AND failure_code IS NOT NULL AND claim_token IS NULL AND claim_until IS NULL)
        OR
        (state = 'RECONCILING' AND attempt_count > 0
            AND next_attempt_at IS NULL AND result_reference IS NULL
            AND failure_code IS NOT NULL AND claim_token IS NOT NULL AND claim_until IS NOT NULL)
        OR
        (state = 'MANUAL_REVIEW'
            AND next_attempt_at IS NULL AND result_reference IS NULL
            AND claim_token IS NULL AND claim_until IS NULL)
    )
);

CREATE INDEX idx_support_resolution_step_due
    ON support_post_acceptance_resolution_step (next_attempt_at, resolution_id, step_type)
    WHERE state IN ('PENDING', 'RETRY_SCHEDULED', 'UNKNOWN');

CREATE UNIQUE INDEX uq_support_resolution_step_claim
    ON support_post_acceptance_resolution_step (claim_token)
    WHERE claim_token IS NOT NULL;

ALTER TABLE support_action_request
    ADD CONSTRAINT fk_support_action_request_terminal_execution
        FOREIGN KEY (id, terminal_execution_id)
        REFERENCES support_order_change_execution (request_id, id),
    ADD CONSTRAINT fk_support_action_request_terminal_resolution
        FOREIGN KEY (id, terminal_resolution_id)
        REFERENCES support_post_acceptance_resolution (request_id, id),
    ADD CONSTRAINT uq_support_action_request_terminal_execution UNIQUE (terminal_execution_id),
    ADD CONSTRAINT uq_support_action_request_terminal_resolution UNIQUE (terminal_resolution_id),
    ADD CONSTRAINT chk_support_action_request_terminal_result CHECK (
        (state = 'RESOLUTION_REQUIRED'
            AND terminal_execution_id IS NOT NULL
            AND terminal_resolution_id IS NULL)
        OR
        (state = 'EXECUTED'
            AND num_nonnulls(terminal_execution_id, terminal_resolution_id) = 1)
        OR
        (state NOT IN ('EXECUTED', 'RESOLUTION_REQUIRED')
            AND terminal_execution_id IS NULL
            AND terminal_resolution_id IS NULL)
    );
