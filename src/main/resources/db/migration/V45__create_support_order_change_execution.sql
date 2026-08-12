SET LOCAL lock_timeout = '5s';

ALTER TABLE support_action_request
    DROP CONSTRAINT support_action_request_state_check,
    ADD COLUMN terminal_execution_id uuid,
    ADD CONSTRAINT chk_support_action_request_state CHECK (state IN (
        'AWAITING_SUPPORT_MANAGER', 'AWAITING_OPERATIONS', 'READY_FOR_EXECUTION',
        'REASSIGNMENT_REQUIRED', 'REVISION_REQUIRED', 'DENIED', 'EXPIRED', 'STALE',
        'MANUAL_REVIEW', 'EXECUTED', 'RESOLUTION_REQUIRED'
    ));

CREATE TABLE support_order_change_authorization (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL,
    action varchar(40) NOT NULL CHECK (action IN ('ORDER_CANCELLATION', 'PICKUP_RESCHEDULE')),
    authorization_type varchar(16) NOT NULL CHECK (authorization_type IN ('CONFIRMATION', 'DELEGATION')),
    policy_version varchar(160) NOT NULL CHECK (policy_version = 'support-order-change-policy/2026-08-12/v1'),
    request_id uuid,
    revision_number integer,
    action_payload_digest varchar(64),
    target_version bigint,
    authorized_by_actor_id uuid NOT NULL,
    authorized_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    max_successful_uses integer NOT NULL CHECK (max_successful_uses BETWEEN 1 AND 3),
    cost_responsibility varchar(16) NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT fk_support_order_change_authorization_revision
        FOREIGN KEY (request_id, revision_number)
        REFERENCES support_action_revision (request_id, revision_number),
    CONSTRAINT chk_support_order_change_authorization_time CHECK (
        authorized_at < expires_at AND (revoked_at IS NULL OR revoked_at >= authorized_at)
    ),
    CONSTRAINT chk_support_order_change_authorization_cost CHECK (cost_responsibility = 'STORE')
);

ALTER TABLE support_order_change_authorization
    ADD CONSTRAINT chk_support_order_change_authorization_binding CHECK (
        (authorization_type = 'CONFIRMATION'
            AND request_id IS NOT NULL AND revision_number > 0
            AND action_payload_digest ~ '^[0-9a-f]{64}$'
            AND target_version >= 0 AND max_successful_uses = 1)
        OR
        (authorization_type = 'DELEGATION'
            AND request_id IS NULL AND revision_number IS NULL
            AND action_payload_digest IS NULL AND target_version IS NULL
            AND (
                (action = 'ORDER_CANCELLATION'
                    AND expires_at = authorized_at + INTERVAL '10 minutes'
                    AND max_successful_uses = 1)
                OR
                (action = 'PICKUP_RESCHEDULE'
                    AND expires_at = authorized_at + INTERVAL '30 minutes'
                    AND max_successful_uses = 3)
            ))
    );

CREATE INDEX idx_support_order_change_authorization_lookup
    ON support_order_change_authorization (store_id, action, expires_at, id)
    WHERE revoked_at IS NULL;

CREATE TABLE support_order_change_execution (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    revision_number integer NOT NULL CHECK (revision_number > 0),
    actor_id uuid NOT NULL,
    action varchar(40) NOT NULL CHECK (action IN ('ORDER_CANCELLATION', 'PICKUP_RESCHEDULE')),
    idempotency_key varchar(128) NOT NULL CHECK (
        idempotency_key = btrim(idempotency_key)
        AND length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    action_payload_digest varchar(64) NOT NULL CHECK (action_payload_digest ~ '^[0-9a-f]{64}$'),
    expected_target_version bigint NOT NULL CHECK (expected_target_version >= 0),
    target_version_after bigint CHECK (target_version_after >= 0),
    authorization_id uuid REFERENCES support_order_change_authorization (id),
    outcome varchar(32) NOT NULL CHECK (outcome IN ('EXECUTED', 'RESOLUTION_REQUIRED')),
    reason_code varchar(40) NOT NULL CHECK (
        reason_code = btrim(reason_code) AND length(reason_code) BETWEEN 1 AND 40
        AND reason_code !~ '[[:cntrl:]]'
    ),
    occurred_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    CONSTRAINT fk_support_order_change_execution_revision
        FOREIGN KEY (request_id, revision_id, revision_number)
        REFERENCES support_action_revision (request_id, id, revision_number),
    CONSTRAINT uq_support_order_change_execution_command UNIQUE (actor_id, idempotency_key),
    CONSTRAINT uq_support_order_change_execution_request UNIQUE (request_id),
    CONSTRAINT chk_support_order_change_execution_outcome CHECK (
        (outcome = 'EXECUTED' AND target_version_after IS NOT NULL)
        OR (outcome = 'RESOLUTION_REQUIRED' AND target_version_after IS NULL)
    ),
    CONSTRAINT chk_support_order_change_execution_retention CHECK (
        retention_expires_at = occurred_at + INTERVAL '90 days'
    )
);

CREATE TABLE support_order_change_authorization_use (
    execution_id uuid PRIMARY KEY REFERENCES support_order_change_execution (id),
    authorization_id uuid NOT NULL REFERENCES support_order_change_authorization (id),
    request_id uuid NOT NULL,
    revision_number integer NOT NULL CHECK (revision_number > 0),
    action_payload_digest varchar(64) NOT NULL CHECK (action_payload_digest ~ '^[0-9a-f]{64}$'),
    target_version bigint NOT NULL CHECK (target_version >= 0),
    used_at timestamptz NOT NULL,
    CONSTRAINT fk_support_order_change_authorization_use_revision
        FOREIGN KEY (request_id, revision_number)
        REFERENCES support_action_revision (request_id, revision_number),
    CONSTRAINT uq_support_order_change_authorization_use UNIQUE (authorization_id, execution_id)
);

CREATE TRIGGER trg_support_order_change_authorization_use_append_only
    BEFORE UPDATE OR DELETE ON support_order_change_authorization_use
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

ALTER TABLE support_action_request
    ADD CONSTRAINT fk_support_action_request_terminal_execution
        FOREIGN KEY (terminal_execution_id) REFERENCES support_order_change_execution (id),
    ADD CONSTRAINT uq_support_action_request_terminal_execution UNIQUE (terminal_execution_id),
    ADD CONSTRAINT chk_support_action_request_terminal_execution CHECK (
        (state IN ('EXECUTED', 'RESOLUTION_REQUIRED') AND terminal_execution_id IS NOT NULL)
        OR (state NOT IN ('EXECUTED', 'RESOLUTION_REQUIRED') AND terminal_execution_id IS NULL)
    );

CREATE INDEX idx_support_order_change_execution_retention
    ON support_order_change_execution (retention_expires_at, id);

CREATE TABLE ordering_support_order_change_history (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES ordering_order (id),
    support_request_id uuid NOT NULL,
    support_execution_id uuid NOT NULL,
    action varchar(40) NOT NULL CHECK (action IN ('ORDER_CANCELLATION', 'PICKUP_RESCHEDULE')),
    previous_state varchar(32) NOT NULL,
    current_state varchar(32) NOT NULL,
    previous_pickup_slot_id uuid NOT NULL,
    current_pickup_slot_id uuid NOT NULL,
    order_version bigint NOT NULL CHECK (order_version >= 0),
    payment_recovery_state varchar(32),
    source_reference varchar(240) NOT NULL UNIQUE CHECK (
        source_reference = btrim(source_reference)
        AND length(source_reference) BETWEEN 1 AND 240
        AND source_reference !~ '[[:cntrl:]]'
    ),
    occurred_at timestamptz NOT NULL,
    CONSTRAINT uq_ordering_support_order_change_execution UNIQUE (support_execution_id),
    CONSTRAINT chk_ordering_support_order_change_recovery CHECK (
        (action = 'ORDER_CANCELLATION' AND payment_recovery_state IS NOT NULL)
        OR (action = 'PICKUP_RESCHEDULE' AND payment_recovery_state IS NULL)
    )
);

CREATE TRIGGER trg_ordering_support_order_change_history_append_only
    BEFORE UPDATE OR DELETE ON ordering_support_order_change_history
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TABLE fulfillment_pickup_reschedule_history (
    id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES fulfillment_pickup_reservation (id),
    order_id uuid NOT NULL,
    previous_slot_id uuid NOT NULL REFERENCES fulfillment_pickup_slot (id),
    current_slot_id uuid NOT NULL REFERENCES fulfillment_pickup_slot (id),
    reservation_state varchar(32) NOT NULL CHECK (reservation_state IN ('RESERVED', 'CONFIRMED')),
    source_reference varchar(240) NOT NULL UNIQUE CHECK (
        source_reference = btrim(source_reference)
        AND length(source_reference) BETWEEN 1 AND 240
        AND source_reference !~ '[[:cntrl:]]'
    ),
    occurred_at timestamptz NOT NULL,
    CONSTRAINT chk_fulfillment_pickup_reschedule_changed CHECK (previous_slot_id <> current_slot_id)
);

CREATE INDEX idx_fulfillment_pickup_reschedule_order
    ON fulfillment_pickup_reschedule_history (order_id, occurred_at DESC, id DESC);

CREATE TRIGGER trg_fulfillment_pickup_reschedule_history_append_only
    BEFORE UPDATE OR DELETE ON fulfillment_pickup_reschedule_history
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

ALTER TABLE ordering_order
    DROP CONSTRAINT chk_order_cancellation_reason_fields,
    DROP CONSTRAINT chk_order_cancellation_cause,
    ADD CONSTRAINT chk_order_cancellation_cause CHECK (
        cancellation_cause IS NULL
        OR cancellation_cause IN ('CUSTOMER_REQUEST', 'PAYMENT_DECLINED', 'SUPPORT_REQUEST')
    );

ALTER TABLE ordering_order
    ADD CONSTRAINT chk_order_cancellation_reason_fields CHECK (
        (state = 'CANCELLED'
            AND cancellation_cause IN ('CUSTOMER_REQUEST', 'SUPPORT_REQUEST')
            AND cancellation_reason_code IS NOT NULL
            AND (cancellation_detail IS NULL OR (
                cancellation_detail = btrim(cancellation_detail)
                AND length(cancellation_detail) BETWEEN 1 AND 200
                AND cancellation_detail !~ '[[:cntrl:]]'
            )))
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

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('SUPPORT_ORDER_CHANGE_CONFIRMATION_CREATED', 'ORDER_AND_FULFILLMENT'),
    ('SUPPORT_ORDER_CHANGE_DELEGATION_CREATED', 'ORDER_AND_FULFILLMENT'),
    ('SUPPORT_ORDER_CHANGE_EXECUTED', 'ORDER_AND_FULFILLMENT'),
    ('SUPPORT_ORDER_CHANGE_RESOLUTION_REQUIRED', 'ORDER_AND_FULFILLMENT'),
    ('ORDER_SUPPORT_CANCELLED', 'ORDER_AND_FULFILLMENT'),
    ('ORDER_SUPPORT_PICKUP_RESCHEDULED', 'ORDER_AND_FULFILLMENT'),
    ('PICKUP_RESERVATION_RELEASED_BY_SUPPORT_CANCELLATION', 'ORDER_AND_FULFILLMENT'),
    ('STOCK_RESERVATION_RELEASED_BY_SUPPORT_CANCELLATION', 'ORDER_AND_FULFILLMENT'),
    ('COUPON_RESERVATION_RELEASED_BY_SUPPORT_CANCELLATION', 'ORDER_AND_FULFILLMENT'),
    ('POINT_RESERVATION_RELEASED_BY_SUPPORT_CANCELLATION', 'ORDER_AND_FULFILLMENT');
