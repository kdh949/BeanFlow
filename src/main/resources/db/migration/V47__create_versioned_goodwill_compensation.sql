SET LOCAL lock_timeout = '5s';

CREATE TABLE support_compensation_policy_version (
    id uuid PRIMARY KEY,
    code varchar(80) NOT NULL UNIQUE CHECK (
        code = btrim(code) AND code ~ '^[A-Z0-9_]{1,80}$'
    ),
    effective_at timestamptz NOT NULL,
    low_amount_maximum_krw bigint NOT NULL CHECK (low_amount_maximum_krw > 0),
    high_amount_maximum_krw bigint NOT NULL CHECK (high_amount_maximum_krw > low_amount_maximum_krw),
    supported_amount_maximum_krw bigint NOT NULL CHECK (supported_amount_maximum_krw > high_amount_maximum_krw),
    low_order_ratio_maximum_bps integer NOT NULL CHECK (low_order_ratio_maximum_bps BETWEEN 1 AND 10000),
    created_at timestamptz NOT NULL
);

CREATE TRIGGER trg_support_compensation_policy_version_immutable
    BEFORE UPDATE OR DELETE ON support_compensation_policy_version
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TABLE support_compensation_policy_head (
    name varchar(80) PRIMARY KEY CHECK (name = 'GOODWILL'),
    current_version_id uuid NOT NULL REFERENCES support_compensation_policy_version(id),
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version >= 0)
);

CREATE TABLE support_compensation_limit_rule (
    id uuid PRIMARY KEY,
    policy_version_id uuid NOT NULL REFERENCES support_compensation_policy_version(id),
    scope varchar(16) NOT NULL CHECK (scope IN ('CUSTOMER', 'ORDER', 'INCIDENT', 'ACTOR', 'STORE')),
    window_seconds bigint NOT NULL CHECK (window_seconds > 0),
    maximum_krw bigint NOT NULL CHECK (maximum_krw > 0),
    UNIQUE (policy_version_id, scope)
);

CREATE TRIGGER trg_support_compensation_limit_rule_immutable
    BEFORE UPDATE OR DELETE ON support_compensation_limit_rule
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

INSERT INTO support_compensation_policy_version (
    id, code, effective_at, low_amount_maximum_krw, high_amount_maximum_krw,
    supported_amount_maximum_krw, low_order_ratio_maximum_bps, created_at
) VALUES (
    '90000000-0000-0000-0000-000000000001', 'GOODWILL_V1',
    '2026-08-12T00:00:00Z', 3000, 10000, 30000, 5000, '2026-08-12T00:00:00Z'
);

INSERT INTO support_compensation_policy_head (name, current_version_id, updated_at, version) VALUES
    ('GOODWILL', '90000000-0000-0000-0000-000000000001', '2026-08-12T00:00:00Z', 0);

INSERT INTO support_compensation_limit_rule (id, policy_version_id, scope, window_seconds, maximum_krw) VALUES
    ('90000000-0000-0000-0000-000000000011', '90000000-0000-0000-0000-000000000001', 'CUSTOMER', 2592000, 30000),
    ('90000000-0000-0000-0000-000000000012', '90000000-0000-0000-0000-000000000001', 'ORDER', 2592000, 30000),
    ('90000000-0000-0000-0000-000000000013', '90000000-0000-0000-0000-000000000001', 'INCIDENT', 2592000, 30000),
    ('90000000-0000-0000-0000-000000000014', '90000000-0000-0000-0000-000000000001', 'ACTOR', 86400, 100000),
    ('90000000-0000-0000-0000-000000000015', '90000000-0000-0000-0000-000000000001', 'STORE', 86400, 300000);

CREATE TABLE promotion_goodwill_coupon_template (
    id uuid PRIMARY KEY,
    code varchar(80) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_]{1,80}$'),
    fixed_amount_krw bigint NOT NULL CHECK (fixed_amount_krw IN (3000, 10000, 30000)),
    validity_days integer NOT NULL CHECK (validity_days = 30),
    minimum_eligible_subtotal_krw bigint NOT NULL CHECK (minimum_eligible_subtotal_krw = 0),
    created_at timestamptz NOT NULL
);

CREATE TRIGGER trg_promotion_goodwill_coupon_template_immutable
    BEFORE UPDATE OR DELETE ON promotion_goodwill_coupon_template
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

INSERT INTO promotion_goodwill_coupon_template (
    id, code, fixed_amount_krw, validity_days, minimum_eligible_subtotal_krw, created_at
) VALUES
    ('91000000-0000-0000-0000-000000000003', 'GOODWILL_FIXED_3000_V1', 3000, 30, 0, '2026-08-12T00:00:00Z'),
    ('91000000-0000-0000-0000-000000000010', 'GOODWILL_FIXED_10000_V1', 10000, 30, 0, '2026-08-12T00:00:00Z'),
    ('91000000-0000-0000-0000-000000000030', 'GOODWILL_FIXED_30000_V1', 30000, 30, 0, '2026-08-12T00:00:00Z');

ALTER TABLE support_action_request
    DROP CONSTRAINT support_action_request_action_check,
    DROP CONSTRAINT support_action_request_target_type_check,
    ADD CONSTRAINT chk_support_action_request_action CHECK (action IN (
        'ORDER_CANCELLATION', 'PICKUP_RESCHEDULE', 'POST_ACCEPTANCE_RESOLUTION', 'GOODWILL_COMPENSATION'
    )),
    ADD CONSTRAINT chk_support_action_request_target_type CHECK (target_type IN ('ORDER', 'COMPENSATION_REQUEST')),
    ADD CONSTRAINT chk_support_action_request_target_binding CHECK (
        (action = 'GOODWILL_COMPENSATION' AND target_type = 'COMPENSATION_REQUEST')
        OR (action <> 'GOODWILL_COMPENSATION' AND target_type = 'ORDER')
    );

ALTER TABLE support_action_revision
    DROP CONSTRAINT support_action_revision_action_check,
    DROP CONSTRAINT support_action_revision_target_type_check,
    ADD CONSTRAINT chk_support_action_revision_action CHECK (action IN (
        'ORDER_CANCELLATION', 'PICKUP_RESCHEDULE', 'POST_ACCEPTANCE_RESOLUTION', 'GOODWILL_COMPENSATION'
    )),
    ADD CONSTRAINT chk_support_action_revision_target_type CHECK (target_type IN ('ORDER', 'COMPENSATION_REQUEST')),
    ADD CONSTRAINT chk_support_action_revision_target_binding CHECK (
        (action = 'GOODWILL_COMPENSATION' AND target_type = 'COMPENSATION_REQUEST')
        OR (action <> 'GOODWILL_COMPENSATION' AND target_type = 'ORDER')
    );

CREATE TABLE support_compensation_request (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case(id),
    customer_id uuid NOT NULL,
    incident_id uuid NOT NULL,
    order_id uuid,
    store_id uuid,
    requester_actor_id uuid NOT NULL,
    executor_actor_id uuid NOT NULL,
    benefit_type varchar(16) NOT NULL CHECK (benefit_type IN ('POINT', 'COUPON')),
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    coupon_template_id uuid REFERENCES promotion_goodwill_coupon_template(id),
    policy_version_id uuid NOT NULL REFERENCES support_compensation_policy_version(id),
    band varchar(16) NOT NULL CHECK (band IN ('LOW', 'MEDIUM', 'HIGH', 'EXCEPTIONAL')),
    approval_route varchar(48) NOT NULL CHECK (approval_route IN ('NONE', 'SUPPORT_MANAGER', 'OPERATIONS')),
    verification_session_id uuid NOT NULL REFERENCES support_verification_session(id),
    target_version bigint NOT NULL CHECK (target_version >= 0),
    responsibility varchar(16) NOT NULL CHECK (responsibility IN ('PLATFORM', 'STORE', 'SHARED')),
    evidence_basis varchar(32) CHECK (evidence_basis IN ('STORE_CONSENT', 'OPERATIONS_FINDING', 'CONTRACTUAL_RULE')),
    cost_evidence_digest varchar(64) CHECK (cost_evidence_digest ~ '^[0-9a-f]{64}$'),
    platform_share_bps integer NOT NULL CHECK (platform_share_bps BETWEEN 0 AND 10000),
    store_share_bps integer NOT NULL CHECK (store_share_bps BETWEEN 0 AND 10000),
    payload_digest varchar(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    evidence_digest varchar(64) NOT NULL CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    action_request_id uuid UNIQUE REFERENCES support_action_request(id),
    state varchar(32) NOT NULL CHECK (state IN (
        'AWAITING_APPROVAL', 'READY_FOR_EXECUTION', 'BENEFIT_ISSUED',
        'NOTIFICATION_RETRY', 'NOTIFICATION_ACCEPTED', 'MANUAL_REVIEW'
    )),
    terminal_benefit_id uuid UNIQUE,
    notification_delivery_id uuid UNIQUE,
    notification_failure_code varchar(80) CHECK (notification_failure_code ~ '^[A-Z0-9_]{1,80}$'),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version >= 0),
    CONSTRAINT uq_support_compensation_action_binding UNIQUE (action_request_id, id),
    CONSTRAINT chk_support_compensation_template CHECK (
        (benefit_type = 'COUPON' AND coupon_template_id IS NOT NULL)
        OR (benefit_type = 'POINT' AND coupon_template_id IS NULL)
    ),
    CONSTRAINT chk_support_compensation_approval CHECK (
        (approval_route = 'NONE' AND action_request_id IS NULL)
        OR (approval_route <> 'NONE' AND action_request_id IS NOT NULL)
    ),
    CONSTRAINT chk_support_compensation_cost CHECK (
        (responsibility = 'PLATFORM' AND platform_share_bps = 10000 AND store_share_bps = 0
            AND evidence_basis IS NULL AND cost_evidence_digest IS NULL)
        OR
        (responsibility = 'STORE' AND platform_share_bps = 0 AND store_share_bps = 10000
            AND store_id IS NOT NULL AND evidence_basis IS NOT NULL AND cost_evidence_digest IS NOT NULL)
        OR
        (responsibility = 'SHARED' AND platform_share_bps BETWEEN 1 AND 9999
            AND store_share_bps BETWEEN 1 AND 9999
            AND platform_share_bps + store_share_bps = 10000
            AND store_id IS NOT NULL AND evidence_basis IS NOT NULL AND cost_evidence_digest IS NOT NULL)
    ),
    CONSTRAINT chk_support_compensation_terminal CHECK (
        (state IN ('BENEFIT_ISSUED', 'NOTIFICATION_RETRY', 'NOTIFICATION_ACCEPTED', 'MANUAL_REVIEW')
            AND terminal_benefit_id IS NOT NULL)
        OR
        (state IN ('AWAITING_APPROVAL', 'READY_FOR_EXECUTION') AND terminal_benefit_id IS NULL)
    ),
    CONSTRAINT chk_support_compensation_notification CHECK (
        (state = 'NOTIFICATION_ACCEPTED' AND notification_delivery_id IS NOT NULL AND notification_failure_code IS NULL)
        OR
        (state IN ('NOTIFICATION_RETRY', 'MANUAL_REVIEW') AND notification_delivery_id IS NULL
            AND notification_failure_code IS NOT NULL)
        OR
        (state IN ('AWAITING_APPROVAL', 'READY_FOR_EXECUTION', 'BENEFIT_ISSUED')
            AND notification_delivery_id IS NULL AND notification_failure_code IS NULL)
    ),
    CONSTRAINT chk_support_compensation_time CHECK (created_at <= updated_at)
);

CREATE INDEX idx_support_compensation_case
    ON support_compensation_request(support_case_id, updated_at DESC, id DESC);
CREATE INDEX idx_support_compensation_customer
    ON support_compensation_request(customer_id, updated_at DESC, id DESC);
CREATE INDEX idx_support_compensation_order
    ON support_compensation_request(order_id, updated_at DESC, id DESC) WHERE order_id IS NOT NULL;
CREATE INDEX idx_support_compensation_notification_due
    ON support_compensation_request(updated_at, id) WHERE state = 'NOTIFICATION_RETRY';

ALTER TABLE support_action_request
    DROP CONSTRAINT chk_support_action_request_terminal_result,
    ADD COLUMN terminal_compensation_id uuid,
    ADD CONSTRAINT fk_support_action_request_terminal_compensation
        FOREIGN KEY (id, terminal_compensation_id)
        REFERENCES support_compensation_request(action_request_id, id),
    ADD CONSTRAINT uq_support_action_request_terminal_compensation UNIQUE (terminal_compensation_id),
    ADD CONSTRAINT chk_support_action_request_terminal_result CHECK (
        (state = 'RESOLUTION_REQUIRED'
            AND terminal_execution_id IS NOT NULL
            AND terminal_resolution_id IS NULL
            AND terminal_compensation_id IS NULL)
        OR
        (state = 'EXECUTED'
            AND num_nonnulls(terminal_execution_id, terminal_resolution_id, terminal_compensation_id) = 1)
        OR
        (state NOT IN ('EXECUTED', 'RESOLUTION_REQUIRED')
            AND terminal_execution_id IS NULL
            AND terminal_resolution_id IS NULL
            AND terminal_compensation_id IS NULL)
    ),
    ADD CONSTRAINT chk_support_action_request_terminal_action CHECK (
        (terminal_compensation_id IS NULL OR action = 'GOODWILL_COMPENSATION')
        AND (action <> 'GOODWILL_COMPENSATION' OR terminal_execution_id IS NULL AND terminal_resolution_id IS NULL)
    );

CREATE TABLE support_compensation_terminal_benefit (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES support_compensation_request(id),
    incident_id uuid NOT NULL,
    benefit_type varchar(16) NOT NULL CHECK (benefit_type IN ('POINT', 'COUPON')),
    owner_reference varchar(240) NOT NULL UNIQUE CHECK (
        owner_reference = btrim(owner_reference)
        AND length(owner_reference) BETWEEN 1 AND 240
        AND owner_reference !~ '[[:cntrl:]]'
    ),
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    policy_version_id uuid NOT NULL REFERENCES support_compensation_policy_version(id),
    issued_at timestamptz NOT NULL,
    CONSTRAINT uq_support_compensation_request_terminal UNIQUE (request_id),
    CONSTRAINT uq_support_compensation_incident_terminal UNIQUE (incident_id)
);

CREATE TRIGGER trg_support_compensation_terminal_benefit_append_only
    BEFORE UPDATE OR DELETE ON support_compensation_terminal_benefit
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TABLE support_compensation_limit_lock (
    scope varchar(16) NOT NULL CHECK (scope IN ('CUSTOMER', 'ORDER', 'INCIDENT', 'ACTOR', 'STORE')),
    scope_id uuid NOT NULL,
    PRIMARY KEY (scope, scope_id)
);

CREATE TABLE support_compensation_limit_consumption (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES support_compensation_request(id),
    policy_version_id uuid NOT NULL REFERENCES support_compensation_policy_version(id),
    scope varchar(16) NOT NULL CHECK (scope IN ('CUSTOMER', 'ORDER', 'INCIDENT', 'ACTOR', 'STORE')),
    scope_id uuid NOT NULL,
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    issued_at timestamptz NOT NULL,
    CONSTRAINT uq_support_compensation_consumption_scope UNIQUE (request_id, scope)
);

CREATE INDEX idx_support_compensation_limit_window
    ON support_compensation_limit_consumption(scope, scope_id, issued_at DESC, id DESC);

CREATE TRIGGER trg_support_compensation_limit_consumption_append_only
    BEFORE UPDATE OR DELETE ON support_compensation_limit_consumption
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TABLE support_compensation_command_idempotency (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(24) NOT NULL CHECK (operation IN ('CREATE', 'EXECUTE', 'RETRY_NOTIFICATION')),
    idempotency_key varchar(128) NOT NULL CHECK (
        idempotency_key = btrim(idempotency_key)
        AND length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    compensation_request_id uuid NOT NULL REFERENCES support_compensation_request(id),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    CONSTRAINT uq_support_compensation_command UNIQUE (actor_id, operation, idempotency_key),
    CHECK (retention_expires_at = created_at + INTERVAL '90 days')
);

CREATE INDEX idx_support_compensation_command_retention
    ON support_compensation_command_idempotency(retention_expires_at, id);

ALTER TABLE loyalty_point_transaction
    DROP CONSTRAINT chk_point_transaction_type,
    ADD CONSTRAINT chk_point_transaction_type CHECK (type IN (
        'USE', 'EXPIRATION', 'RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED',
        'ACCRUAL', 'RECOVERY', 'ADJUSTMENT', 'GOODWILL_COMPENSATION'
    ));

ALTER TABLE loyalty_point_transaction
    DROP CONSTRAINT chk_point_transaction_balance_effect,
    ADD CONSTRAINT chk_point_transaction_balance_effect CHECK (
        (type IN ('ACCRUAL', 'RESTORE', 'COMPENSATION', 'GOODWILL_COMPENSATION') AND balance_effect = 'CREDIT')
        OR (type IN ('USE', 'EXPIRATION', 'RECOVERY') AND balance_effect = 'DEBIT')
        OR (type = 'RESTORE_SKIPPED_EXPIRED' AND balance_effect = 'NONE')
        OR (type = 'ADJUSTMENT' AND balance_effect IN ('CREDIT', 'DEBIT'))
    );

ALTER TABLE loyalty_point_transaction
    DROP CONSTRAINT chk_point_transaction_restoration_metadata,
    ADD CONSTRAINT chk_point_transaction_restoration_metadata CHECK (
        (type NOT IN ('RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED', 'GOODWILL_COMPENSATION')
            AND refund_id IS NULL AND order_line_id IS NULL AND point_reservation_allocation_id IS NULL
            AND restoration_trigger IS NULL AND restoration_policy_version_id IS NULL
            AND restoration_disposition IS NULL)
        OR
        (type IN ('RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED')
            AND refund_id IS NOT NULL AND order_line_id IS NOT NULL
            AND point_reservation_allocation_id IS NOT NULL
            AND restoration_trigger = 'PARTIAL_REFUND'
            AND restoration_policy_version_id IS NOT NULL
            AND ((type = 'RESTORE' AND restoration_disposition = 'ORIGINAL_LOT')
                OR (type = 'COMPENSATION' AND restoration_disposition = 'COMPENSATION_LOT')
                OR (type = 'RESTORE_SKIPPED_EXPIRED' AND restoration_disposition = 'SKIPPED_EXPIRED')))
        OR
        (type IN ('RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED')
            AND refund_id IS NULL AND order_line_id IS NULL
            AND point_reservation_allocation_id IS NOT NULL
            AND restoration_trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
            AND restoration_policy_version_id IS NOT NULL
            AND ((type = 'RESTORE' AND restoration_disposition = 'ORIGINAL_LOT')
                OR (type = 'COMPENSATION' AND restoration_disposition = 'COMPENSATION_LOT')
                OR (type = 'RESTORE_SKIPPED_EXPIRED' AND restoration_disposition = 'SKIPPED_EXPIRED')))
        OR
        (type IN ('RESTORE', 'RESTORE_SKIPPED_EXPIRED')
            AND refund_id IS NULL AND order_line_id IS NULL
            AND point_reservation_allocation_id IS NOT NULL
            AND restoration_trigger = 'POST_ACCEPTANCE_RESOLUTION'
            AND restoration_policy_version_id IS NULL
            AND ((type = 'RESTORE' AND restoration_disposition = 'ORIGINAL_LOT')
                OR (type = 'RESTORE_SKIPPED_EXPIRED' AND restoration_disposition = 'SKIPPED_EXPIRED')))
        OR
        (type = 'GOODWILL_COMPENSATION'
            AND refund_id IS NULL AND order_line_id IS NULL
            AND point_reservation_allocation_id IS NULL
            AND restoration_trigger IS NULL AND restoration_policy_version_id IS NULL
            AND restoration_disposition IS NULL)
    );

CREATE TABLE loyalty_goodwill_point_issuance (
    id uuid PRIMARY KEY,
    compensation_request_id uuid NOT NULL UNIQUE REFERENCES support_compensation_request(id),
    point_account_id uuid NOT NULL REFERENCES loyalty_point_account(id),
    source_reference varchar(240) NOT NULL UNIQUE CHECK (
        source_reference = btrim(source_reference)
        AND length(source_reference) BETWEEN 1 AND 240
        AND source_reference !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    total_amount_krw bigint NOT NULL CHECK (total_amount_krw > 0),
    policy_version_id uuid NOT NULL REFERENCES support_compensation_policy_version(id),
    issued_at timestamptz NOT NULL
);

CREATE TABLE loyalty_goodwill_point_funding_leg (
    id uuid PRIMARY KEY,
    issuance_id uuid NOT NULL REFERENCES loyalty_goodwill_point_issuance(id),
    issuer_type varchar(16) NOT NULL CHECK (issuer_type IN ('PLATFORM', 'STORE')),
    store_id uuid,
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    point_lot_id uuid NOT NULL UNIQUE REFERENCES loyalty_point_lot(id),
    point_transaction_id uuid NOT NULL UNIQUE REFERENCES loyalty_point_transaction(id),
    UNIQUE (issuance_id, issuer_type),
    CHECK ((issuer_type = 'STORE') = (store_id IS NOT NULL))
);

CREATE TABLE promotion_goodwill_coupon_issuance (
    id uuid PRIMARY KEY,
    compensation_request_id uuid NOT NULL UNIQUE REFERENCES support_compensation_request(id),
    coupon_template_id uuid NOT NULL REFERENCES promotion_goodwill_coupon_template(id),
    campaign_id uuid NOT NULL UNIQUE REFERENCES promotion_campaign(id),
    coupon_issuance_id uuid NOT NULL UNIQUE REFERENCES promotion_coupon_issuance(id),
    source_reference varchar(240) NOT NULL UNIQUE CHECK (
        source_reference = btrim(source_reference)
        AND length(source_reference) BETWEEN 1 AND 240
        AND source_reference !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    responsibility varchar(16) NOT NULL CHECK (responsibility IN ('PLATFORM', 'STORE', 'SHARED')),
    platform_share_bps integer NOT NULL CHECK (platform_share_bps BETWEEN 0 AND 10000),
    store_share_bps integer NOT NULL CHECK (store_share_bps BETWEEN 0 AND 10000),
    issued_at timestamptz NOT NULL,
    CHECK (platform_share_bps + store_share_bps = 10000)
);

ALTER TABLE notification_delivery
    DROP CONSTRAINT chk_notification_delivery_template,
    ADD CONSTRAINT chk_notification_delivery_template CHECK (
        template IN (
            'STORE_ACCEPTANCE_WARNING', 'ORDER_REJECTED', 'ORDER_READY',
            'ORDER_CANCELLATION_ACCEPTED', 'CUSTOMER_CANCELLATION_REFUND_SUCCEEDED',
            'CUSTOMER_CANCELLATION_REFUND_DELAYED', 'SUPPORT_PICKUP_RESCHEDULED',
            'SUPPORT_POST_ACCEPTANCE_RESOLUTION', 'SUPPORT_GOODWILL_COMPENSATION_ISSUED'
        )
    );

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('SUPPORT_COMPENSATION_REQUEST_CREATED', 'OPERATIONS_POLICY'),
    ('SUPPORT_COMPENSATION_BENEFIT_ISSUED', 'FINANCIAL_TRANSACTION'),
    ('SUPPORT_COMPENSATION_NOTIFICATION_RETRY', 'OPERATIONS_POLICY'),
    ('LOYALTY_GOODWILL_POINTS_ISSUED', 'FINANCIAL_TRANSACTION'),
    ('PROMOTION_GOODWILL_COUPON_ISSUED', 'FINANCIAL_TRANSACTION');
