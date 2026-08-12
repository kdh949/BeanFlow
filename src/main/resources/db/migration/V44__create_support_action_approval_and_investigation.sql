SET LOCAL lock_timeout = '5s';

CREATE TABLE support_action_request (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case (id),
    action varchar(40) NOT NULL CHECK (action IN (
        'ORDER_CANCELLATION', 'PICKUP_RESCHEDULE', 'POST_ACCEPTANCE_RESOLUTION'
    )),
    target_type varchar(24) NOT NULL CHECK (target_type = 'ORDER'),
    target_id uuid NOT NULL,
    requester_actor_id uuid NOT NULL,
    executor_actor_id uuid NOT NULL,
    current_revision_number integer NOT NULL CHECK (current_revision_number > 0),
    approval_route varchar(48) NOT NULL CHECK (approval_route IN (
        'NONE', 'SUPPORT_MANAGER', 'OPERATIONS', 'SUPPORT_MANAGER_THEN_OPERATIONS'
    )),
    state varchar(40) NOT NULL CHECK (state IN (
        'AWAITING_SUPPORT_MANAGER', 'AWAITING_OPERATIONS', 'READY_FOR_EXECUTION',
        'REASSIGNMENT_REQUIRED', 'REVISION_REQUIRED', 'DENIED', 'EXPIRED', 'STALE', 'MANUAL_REVIEW'
    )),
    support_approver_actor_id uuid,
    operations_approver_actor_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT chk_support_action_request_time CHECK (created_at <= updated_at),
    CONSTRAINT chk_support_action_request_actor_separation CHECK (
        (support_approver_actor_id IS NULL OR support_approver_actor_id <> requester_actor_id)
        AND (operations_approver_actor_id IS NULL OR operations_approver_actor_id <> requester_actor_id)
        AND (
            support_approver_actor_id IS NULL
            OR operations_approver_actor_id IS NULL
            OR support_approver_actor_id <> operations_approver_actor_id
        )
        AND (support_approver_actor_id IS NULL OR executor_actor_id <> support_approver_actor_id)
        AND (operations_approver_actor_id IS NULL OR executor_actor_id <> operations_approver_actor_id)
    )
);

CREATE INDEX idx_support_action_request_case
    ON support_action_request (support_case_id, updated_at DESC, id DESC);

CREATE INDEX idx_support_action_request_executor_state
    ON support_action_request (executor_actor_id, state, updated_at DESC, id DESC);

CREATE INDEX idx_support_action_request_target
    ON support_action_request (target_type, target_id, updated_at DESC, id DESC);

CREATE TABLE support_action_revision (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES support_action_request (id),
    revision_number integer NOT NULL CHECK (revision_number > 0),
    action varchar(40) NOT NULL CHECK (action IN (
        'ORDER_CANCELLATION', 'PICKUP_RESCHEDULE', 'POST_ACCEPTANCE_RESOLUTION'
    )),
    target_type varchar(24) NOT NULL CHECK (target_type = 'ORDER'),
    target_id uuid NOT NULL,
    action_payload_digest varchar(64) NOT NULL CHECK (action_payload_digest ~ '^[0-9a-f]{64}$'),
    verification_session_id uuid NOT NULL REFERENCES support_verification_session (id),
    policy_version varchar(160) NOT NULL CHECK (
        policy_version = btrim(policy_version)
        AND length(policy_version) BETWEEN 1 AND 160
        AND policy_version !~ '[[:cntrl:]]'
    ),
    target_version bigint NOT NULL CHECK (target_version >= 0),
    amount_krw bigint CHECK (amount_krw >= 0),
    reason varchar(500) NOT NULL CHECK (
        reason = btrim(reason)
        AND length(reason) BETWEEN 1 AND 500
        AND reason !~ '[[:cntrl:]]'
    ),
    evidence_digest varchar(64) NOT NULL CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz NOT NULL,
    created_by_actor_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_support_action_revision_number UNIQUE (request_id, revision_number),
    CONSTRAINT uq_support_action_revision_identity UNIQUE (request_id, id),
    CONSTRAINT uq_support_action_revision_lineage UNIQUE (request_id, id, revision_number),
    CONSTRAINT chk_support_action_revision_expiry CHECK (created_at < expires_at)
);

CREATE INDEX idx_support_action_revision_verification
    ON support_action_revision (verification_session_id, request_id, revision_number DESC);

CREATE TABLE support_action_approval_step (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    revision_number integer NOT NULL CHECK (revision_number > 0),
    step_type varchar(24) NOT NULL CHECK (step_type IN ('SUPPORT_MANAGER', 'OPERATIONS')),
    state varchar(16) NOT NULL CHECK (state IN (
        'APPROVED', 'DENIED', 'RETURNED', 'EXPIRED', 'STALE', 'ESCALATED'
    )),
    decided_by_actor_id uuid,
    decision_reason varchar(500) NOT NULL CHECK (
        decision_reason = btrim(decision_reason)
        AND length(decision_reason) BETWEEN 1 AND 500
        AND decision_reason !~ '[[:cntrl:]]'
    ),
    decided_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_support_action_approval_revision
        FOREIGN KEY (request_id, revision_id, revision_number)
        REFERENCES support_action_revision (request_id, id, revision_number),
    CONSTRAINT uq_support_action_approval_step UNIQUE (request_id, revision_number, step_type),
    CONSTRAINT chk_support_action_approval_actor CHECK (
        (state IN ('APPROVED', 'DENIED', 'RETURNED', 'ESCALATED') AND decided_by_actor_id IS NOT NULL)
        OR (state IN ('EXPIRED', 'STALE') AND decided_by_actor_id IS NULL)
    ),
    CONSTRAINT chk_support_action_approval_time CHECK (created_at = decided_at)
);

CREATE TRIGGER trg_support_action_approval_step_append_only
    BEFORE UPDATE OR DELETE ON support_action_approval_step
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TABLE support_action_reassignment (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES support_action_request (id),
    revision_number integer NOT NULL CHECK (revision_number > 0),
    previous_executor_actor_id uuid NOT NULL,
    current_executor_actor_id uuid NOT NULL,
    reassigned_by_actor_id uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (
        reason = btrim(reason)
        AND length(reason) BETWEEN 1 AND 500
        AND reason !~ '[[:cntrl:]]'
    ),
    case_version bigint NOT NULL CHECK (case_version >= 0),
    request_version bigint NOT NULL CHECK (request_version >= 0),
    occurred_at timestamptz NOT NULL,
    CONSTRAINT fk_support_action_reassignment_revision
        FOREIGN KEY (request_id, revision_number)
        REFERENCES support_action_revision (request_id, revision_number),
    CONSTRAINT chk_support_action_reassignment_actor CHECK (
        previous_executor_actor_id <> current_executor_actor_id
    )
);

CREATE INDEX idx_support_action_reassignment_request
    ON support_action_reassignment (request_id, occurred_at DESC, id DESC);

CREATE TRIGGER trg_support_action_reassignment_append_only
    BEFORE UPDATE OR DELETE ON support_action_reassignment
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TABLE support_action_command_idempotency (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(32) NOT NULL CHECK (operation IN (
        'CREATE_REQUEST', 'REVISE_REQUEST', 'MANAGER_DECISION', 'REASSIGN_REQUEST'
    )),
    idempotency_key varchar(128) NOT NULL CHECK (
        idempotency_key = btrim(idempotency_key)
        AND length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    request_id uuid NOT NULL REFERENCES support_action_request (id),
    response_status integer NOT NULL CHECK (response_status IN (200, 201, 409)),
    response_body text NOT NULL CHECK (length(response_body) BETWEEN 1 AND 50000),
    failure_code varchar(64),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    CONSTRAINT uq_support_action_command_idempotency_scope UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT chk_support_action_command_idempotency_retention CHECK (
        retention_expires_at = created_at + INTERVAL '90 days'
    ),
    CONSTRAINT chk_support_action_command_idempotency_outcome CHECK (
        (response_status IN (200, 201) AND failure_code IS NULL)
        OR (response_status = 409 AND failure_code IS NOT NULL)
    )
);

CREATE INDEX idx_support_action_command_idempotency_retention
    ON support_action_command_idempotency (retention_expires_at, id);

CREATE TABLE operations_support_investigation_case (
    id uuid PRIMARY KEY,
    support_action_request_id uuid NOT NULL,
    support_action_revision_id uuid NOT NULL,
    revision_number integer NOT NULL CHECK (revision_number > 0),
    requester_actor_id uuid NOT NULL,
    support_approver_actor_id uuid,
    executor_actor_id uuid NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN (
        'OPEN', 'APPROVED', 'DENIED', 'RETURNED', 'ESCALATED', 'EXPIRED', 'STALE'
    )),
    opened_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    decided_by_actor_id uuid,
    decision_reason varchar(500),
    decision_evidence_digest varchar(64),
    decided_at timestamptz,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_operations_support_investigation_revision UNIQUE (
        support_action_request_id, revision_number
    ),
    CONSTRAINT chk_operations_support_investigation_time CHECK (
        opened_at < expires_at AND opened_at <= updated_at
    ),
    CONSTRAINT chk_operations_support_investigation_decision CHECK (
        (state = 'OPEN' AND decided_by_actor_id IS NULL AND decision_reason IS NULL
            AND decision_evidence_digest IS NULL AND decided_at IS NULL)
        OR
        (state IN ('APPROVED', 'DENIED', 'RETURNED', 'ESCALATED')
            AND decided_by_actor_id IS NOT NULL
            AND decision_reason = btrim(decision_reason)
            AND length(decision_reason) BETWEEN 1 AND 500
            AND decision_reason !~ '[[:cntrl:]]'
            AND decision_evidence_digest ~ '^[0-9a-f]{64}$'
            AND decided_at IS NOT NULL
            AND decided_at >= opened_at
            AND decided_at = updated_at)
        OR
        (state IN ('EXPIRED', 'STALE') AND decided_by_actor_id IS NULL
            AND decision_reason IS NOT NULL AND decision_evidence_digest IS NULL
            AND decided_at IS NOT NULL AND decided_at = updated_at)
    ),
    CONSTRAINT chk_operations_support_investigation_actor_separation CHECK (
        support_approver_actor_id IS NULL OR support_approver_actor_id <> requester_actor_id
    ),
    CONSTRAINT chk_operations_support_investigation_reviewer_separation CHECK (
        decided_by_actor_id IS NULL
        OR (
            decided_by_actor_id <> requester_actor_id
            AND decided_by_actor_id <> executor_actor_id
            AND (support_approver_actor_id IS NULL OR decided_by_actor_id <> support_approver_actor_id)
        )
    )
);

CREATE INDEX idx_operations_support_investigation_state
    ON operations_support_investigation_case (state, expires_at, id);

CREATE TABLE operations_support_investigation_idempotency (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(16) NOT NULL CHECK (operation = 'DECIDE'),
    idempotency_key varchar(128) NOT NULL CHECK (
        idempotency_key = btrim(idempotency_key)
        AND length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    investigation_id uuid NOT NULL REFERENCES operations_support_investigation_case (id),
    response_status integer NOT NULL CHECK (response_status IN (200, 409)),
    response_body text NOT NULL CHECK (length(response_body) BETWEEN 1 AND 20000),
    failure_code varchar(64),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    CONSTRAINT uq_operations_support_investigation_idempotency_scope UNIQUE (
        actor_id, operation, idempotency_key
    ),
    CONSTRAINT chk_operations_support_investigation_idempotency_retention CHECK (
        retention_expires_at = created_at + INTERVAL '90 days'
    ),
    CONSTRAINT chk_operations_support_investigation_idempotency_outcome CHECK (
        (response_status = 200 AND failure_code IS NULL)
        OR (response_status = 409 AND failure_code IS NOT NULL)
    )
);

CREATE INDEX idx_operations_support_investigation_idempotency_retention
    ON operations_support_investigation_idempotency (retention_expires_at, id);

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('SUPPORT_ACTION_REQUEST_CREATED', 'OPERATIONS_POLICY'),
    ('SUPPORT_ACTION_REVISION_CREATED', 'OPERATIONS_POLICY'),
    ('SUPPORT_ACTION_SUPPORT_MANAGER_DECIDED', 'OPERATIONS_POLICY'),
    ('SUPPORT_ACTION_OPERATIONS_DECIDED', 'OPERATIONS_POLICY'),
    ('SUPPORT_ACTION_APPROVAL_EXPIRED', 'OPERATIONS_POLICY'),
    ('SUPPORT_ACTION_APPROVAL_STALE', 'OPERATIONS_POLICY'),
    ('SUPPORT_ACTION_REQUEST_REASSIGNED', 'OPERATIONS_POLICY'),
    ('OPERATIONS_SUPPORT_INVESTIGATION_OPENED', 'OPERATIONS_POLICY'),
    ('OPERATIONS_SUPPORT_INVESTIGATION_DECIDED', 'OPERATIONS_POLICY');
