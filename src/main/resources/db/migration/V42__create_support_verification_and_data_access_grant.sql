SET LOCAL lock_timeout = '5s';

ALTER TABLE operations_operator_permission_grant
    DROP CONSTRAINT chk_operator_permission_vocabulary,
    ADD CONSTRAINT chk_operator_permission_vocabulary CHECK (permission IN (
        'EXPIRED_BENEFIT_POLICY_READ', 'EXPIRED_BENEFIT_POLICY_WRITE', 'POINT_ACCOUNT_READ', 'POINT_ADJUSTMENT',
        'POINT_ACCRUAL_POLICY_READ', 'POINT_ACCRUAL_POLICY_WRITE', 'ORDER_COMPENSATION_READ',
        'PAYMENT_CANCELLATION_SETUP_REPAIR', 'CUSTOMER_CANCELLATION_REFUND_RECONCILE',
        'SUPPORT_CASE_READ', 'SUPPORT_CASE_WRITE', 'SUPPORT_CASE_ASSIGN', 'SUPPORT_SUBJECT_SEARCH',
        'SUPPORT_VERIFICATION_MANAGE', 'SUPPORT_PII_REVEAL_REQUEST', 'SUPPORT_PII_REVEAL_APPROVE',
        'SUPPORT_PII_REVEAL_BASIC', 'SUPPORT_PII_REVEAL_SENSITIVE', 'SUPPORT_BREAK_GLASS_REQUEST',
        'SUPPORT_ACTION_REQUEST', 'SUPPORT_ACTION_APPROVE', 'SUPPORT_ACTION_EXECUTE', 'SUPPORT_ORDER_READ',
        'SUPPORT_ORDER_CANCEL', 'SUPPORT_PICKUP_RESCHEDULE', 'SUPPORT_RESOLUTION_REQUEST',
        'SUPPORT_RESOLUTION_APPROVE', 'SUPPORT_RESOLUTION_EXECUTE', 'SUPPORT_COMPENSATION_REQUEST',
        'SUPPORT_COMPENSATION_APPROVE', 'SUPPORT_COMPENSATION_EXECUTE', 'SUPPORT_PROFILE_R1_CHANGE',
        'SUPPORT_PROFILE_R2_CHANGE', 'SUPPORT_PROFILE_R3_REQUEST', 'SUPPORT_PROFILE_R3_APPROVE',
        'SUPPORT_DELIVERY_READ', 'SUPPORT_DELIVERY_INCIDENT_WRITE', 'SUPPORT_DELIVERY_CHANGE',
        'OPERATIONS_SUPPORT_INVESTIGATION', 'OPERATIONS_LEGAL_HOLD_MANAGE', 'OPERATIONS_RETENTION_MANAGE',
        'PRIVACY_AUDIT_READ', 'PRIVACY_BREAK_GLASS_REVIEW'
    ));

CREATE TABLE support_verification_lockout (
    support_case_id uuid NOT NULL REFERENCES support_case(id),
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('CUSTOMER', 'STORE', 'DELIVERY')),
    subject_id uuid NOT NULL,
    locked_until timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (support_case_id, subject_type, subject_id),
    CONSTRAINT chk_support_verification_lockout_time CHECK (locked_until >= updated_at)
);

CREATE INDEX idx_support_verification_lockout_expiry
    ON support_verification_lockout (locked_until, support_case_id, subject_type, subject_id);

ALTER TABLE support_case_subject_link
    ADD CONSTRAINT uq_support_case_subject_link_binding
        UNIQUE (id, support_case_id, subject_type, subject_id);

CREATE TABLE support_verification_session (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case(id),
    subject_link_id uuid NOT NULL REFERENCES support_case_subject_link(id),
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('CUSTOMER', 'STORE', 'DELIVERY')),
    subject_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    purpose varchar(32) NOT NULL CHECK (purpose IN (
        'CONTACT_CONFIRMATION', 'CASE_RESOLUTION', 'SAFETY_RESPONSE', 'FRAUD_INVESTIGATION', 'PRIVACY_INCIDENT'
    )),
    action_scope varchar(32) NOT NULL CHECK (action_scope = 'PERSONAL_DATA_REVEAL'),
    requested_level varchar(16) NOT NULL CHECK (requested_level IN ('BASIC', 'ENHANCED')),
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'VERIFIED', 'LOCKED', 'EXPIRED', 'REVOKED')),
    invalid_attempts smallint NOT NULL DEFAULT 0 CHECK (invalid_attempts BETWEEN 0 AND 5),
    started_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    verified_at timestamptz,
    revoked_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_support_verification_session_binding
        UNIQUE (id, support_case_id, subject_link_id, subject_type, subject_id, purpose),
    CONSTRAINT fk_support_verification_session_link_binding
        FOREIGN KEY (subject_link_id, support_case_id, subject_type, subject_id)
        REFERENCES support_case_subject_link(id, support_case_id, subject_type, subject_id),
    CONSTRAINT chk_support_verification_session_ttl CHECK (expires_at = started_at + INTERVAL '15 minutes'),
    CONSTRAINT chk_support_verification_session_state CHECK (
        (state = 'VERIFIED' AND verified_at IS NOT NULL AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (state = 'EXPIRED' AND revoked_at IS NULL)
        OR (state IN ('PENDING', 'LOCKED') AND verified_at IS NULL AND revoked_at IS NULL)
    ),
    CONSTRAINT chk_support_verification_session_lock CHECK (
        state <> 'LOCKED' OR invalid_attempts = 5
    )
);

CREATE UNIQUE INDEX uq_support_verification_session_active_binding
    ON support_verification_session (
        support_case_id, subject_link_id, actor_id, purpose, action_scope, requested_level
    ) WHERE state = 'PENDING';

CREATE INDEX idx_support_verification_session_expiry
    ON support_verification_session (expires_at, id) WHERE state IN ('PENDING', 'VERIFIED');

CREATE TABLE support_verification_challenge (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES support_verification_session(id),
    channel varchar(24) NOT NULL CHECK (channel IN ('IN_APP', 'REGISTERED_PHONE', 'REGISTERED_EMAIL')),
    state varchar(32) NOT NULL CHECK (state IN (
        'PENDING_ISSUE', 'ISSUED', 'ISSUE_UNKNOWN', 'VERIFYING', 'VERIFIED', 'INVALID',
        'VERIFICATION_UNKNOWN', 'EXPIRED', 'REVOKED'
    )),
    opaque_provider_reference varchar(1000),
    requested_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT chk_support_verification_challenge_ttl CHECK (expires_at = requested_at + INTERVAL '5 minutes'),
    CONSTRAINT chk_support_verification_challenge_provider_reference CHECK (
        (state IN ('PENDING_ISSUE', 'ISSUE_UNKNOWN', 'REVOKED') AND opaque_provider_reference IS NULL)
        OR (state NOT IN ('PENDING_ISSUE', 'ISSUE_UNKNOWN')
            AND opaque_provider_reference = btrim(opaque_provider_reference)
            AND length(opaque_provider_reference) BETWEEN 1 AND 1000
            AND opaque_provider_reference !~ '[[:cntrl:]]')
    )
);

CREATE INDEX idx_support_verification_challenge_session
    ON support_verification_challenge (session_id, requested_at, id);

CREATE TABLE support_verification_attempt (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES support_verification_session(id),
    challenge_id uuid NOT NULL UNIQUE REFERENCES support_verification_challenge(id),
    actor_id uuid NOT NULL,
    channel varchar(24) NOT NULL CHECK (channel IN ('IN_APP', 'REGISTERED_PHONE', 'REGISTERED_EMAIL')),
    outcome varchar(16) NOT NULL CHECK (outcome IN ('VERIFIED', 'INVALID', 'UNKNOWN')),
    occurred_at timestamptz NOT NULL
);

CREATE TRIGGER trg_support_verification_attempt_append_only
    BEFORE UPDATE OR DELETE ON support_verification_attempt
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TABLE support_data_access_grant (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case(id),
    subject_link_id uuid NOT NULL REFERENCES support_case_subject_link(id),
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('CUSTOMER', 'STORE', 'DELIVERY')),
    subject_id uuid NOT NULL,
    requester_id uuid NOT NULL,
    verification_session_id uuid NOT NULL,
    purpose varchar(32) NOT NULL CHECK (purpose IN (
        'CONTACT_CONFIRMATION', 'CASE_RESOLUTION', 'SAFETY_RESPONSE', 'FRAUD_INVESTIGATION', 'PRIVACY_INCIDENT'
    )),
    reason_code varchar(32) NOT NULL CHECK (reason_code IN (
        'CASE_HANDLING', 'CONTACT_CONFIRMATION', 'FRAUD_INVESTIGATION', 'SAFETY_RESPONSE', 'PRIVACY_INCIDENT'
    )),
    risk varchar(16) NOT NULL CHECK (risk IN ('BASIC', 'SENSITIVE')),
    state varchar(24) NOT NULL CHECK (state IN (
        'REQUESTED', 'APPROVAL_PENDING', 'ACTIVE', 'DENIED', 'CONSUMED', 'EXPIRED', 'REVOKED'
    )),
    max_reveals smallint NOT NULL CHECK (
        (risk = 'BASIC' AND max_reveals = 3) OR (risk = 'SENSITIVE' AND max_reveals = 1)
    ),
    reserved_reveals smallint NOT NULL DEFAULT 0 CHECK (reserved_reveals >= 0 AND reserved_reveals <= max_reveals),
    requested_at timestamptz NOT NULL,
    expires_at timestamptz,
    approver_id uuid,
    decided_at timestamptz,
    revoked_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT fk_support_data_access_grant_link_binding
        FOREIGN KEY (subject_link_id, support_case_id, subject_type, subject_id)
        REFERENCES support_case_subject_link(id, support_case_id, subject_type, subject_id),
    CONSTRAINT fk_support_data_access_grant_verification_binding
        FOREIGN KEY (verification_session_id, support_case_id, subject_link_id, subject_type, subject_id, purpose)
        REFERENCES support_verification_session(id, support_case_id, subject_link_id, subject_type, subject_id, purpose),
    CONSTRAINT chk_support_data_access_grant_approval CHECK (
        (risk = 'BASIC' AND approver_id IS NULL AND decided_at IS NULL)
        OR (risk = 'SENSITIVE' AND (
            (state IN ('REQUESTED', 'APPROVAL_PENDING', 'REVOKED') AND approver_id IS NULL AND decided_at IS NULL)
            OR (state IN ('ACTIVE', 'CONSUMED', 'EXPIRED', 'DENIED', 'REVOKED') AND approver_id IS NOT NULL
                AND approver_id <> requester_id AND decided_at IS NOT NULL)
        ))
    ),
    CONSTRAINT chk_support_data_access_grant_activation CHECK (
        (state IN ('REQUESTED', 'APPROVAL_PENDING', 'DENIED') AND expires_at IS NULL)
        OR (state IN ('ACTIVE', 'CONSUMED', 'EXPIRED') AND expires_at IS NOT NULL)
        OR state = 'REVOKED'
    ),
    CONSTRAINT chk_support_data_access_grant_consumed CHECK (
        state <> 'CONSUMED' OR reserved_reveals = max_reveals
    )
);

CREATE INDEX idx_support_data_access_grant_case_state
    ON support_data_access_grant (support_case_id, state, id);

CREATE TABLE support_data_access_grant_field (
    grant_id uuid NOT NULL REFERENCES support_data_access_grant(id),
    field varchar(48) NOT NULL CHECK (field IN (
        'CUSTOMER_DISPLAY_NAME', 'CUSTOMER_PRIMARY_PHONE', 'CUSTOMER_PRIMARY_EMAIL',
        'STORE_LEGAL_DISPLAY_NAME', 'STORE_SUPPORT_PHONE', 'STORE_SUPPORT_EMAIL',
        'COURIER_DISPLAY_NAME', 'COURIER_PROVIDER_REFERENCE', 'COURIER_RELAY_PHONE', 'COURIER_RELAY_EMAIL'
    )),
    PRIMARY KEY (grant_id, field)
);

CREATE TABLE support_data_access_grant_decision (
    id uuid PRIMARY KEY,
    grant_id uuid NOT NULL UNIQUE REFERENCES support_data_access_grant(id),
    actor_id uuid NOT NULL,
    decision varchar(16) NOT NULL CHECK (decision IN ('APPROVED', 'DENIED')),
    reason_code varchar(32) NOT NULL CHECK (reason_code IN ('CASE_HANDLING', 'FRAUD_INVESTIGATION', 'SAFETY_RESPONSE', 'PRIVACY_INCIDENT')),
    grant_version bigint NOT NULL CHECK (grant_version >= 0),
    decided_at timestamptz NOT NULL
);

CREATE TRIGGER trg_support_data_access_grant_decision_append_only
    BEFORE UPDATE OR DELETE ON support_data_access_grant_decision
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TABLE support_break_glass_request (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case(id),
    subject_link_id uuid NOT NULL REFERENCES support_case_subject_link(id),
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('CUSTOMER', 'STORE', 'DELIVERY')),
    subject_id uuid NOT NULL,
    requester_id uuid NOT NULL,
    field varchar(48) NOT NULL CHECK (field IN (
        'CUSTOMER_DISPLAY_NAME', 'CUSTOMER_PRIMARY_PHONE', 'CUSTOMER_PRIMARY_EMAIL',
        'STORE_LEGAL_DISPLAY_NAME', 'STORE_SUPPORT_PHONE', 'STORE_SUPPORT_EMAIL',
        'COURIER_DISPLAY_NAME', 'COURIER_PROVIDER_REFERENCE', 'COURIER_RELAY_PHONE', 'COURIER_RELAY_EMAIL'
    )),
    purpose varchar(32) NOT NULL CHECK (purpose IN ('SAFETY_RESPONSE', 'FRAUD_INVESTIGATION', 'PRIVACY_INCIDENT')),
    reason_code varchar(32) NOT NULL CHECK (reason_code IN ('IMMEDIATE_SAFETY', 'ACTIVE_FRAUD', 'PRIVACY_INCIDENT')),
    state varchar(24) NOT NULL CHECK (state IN (
        'APPROVAL_PENDING', 'ACTIVE', 'DENIED', 'REVIEW_PENDING', 'REVIEWED', 'EXPIRED', 'REVOKED'
    )),
    requested_at timestamptz NOT NULL,
    expires_at timestamptz,
    approver_id uuid,
    approved_at timestamptz,
    revealed_at timestamptz,
    reviewer_id uuid,
    reviewed_at timestamptz,
    revoked_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT fk_support_break_glass_link_binding
        FOREIGN KEY (subject_link_id, support_case_id, subject_type, subject_id)
        REFERENCES support_case_subject_link(id, support_case_id, subject_type, subject_id),
    CONSTRAINT chk_support_break_glass_approval CHECK (
        (state IN ('APPROVAL_PENDING', 'REVOKED') AND approver_id IS NULL AND approved_at IS NULL AND expires_at IS NULL)
        OR (state = 'DENIED' AND approver_id IS NOT NULL AND approver_id <> requester_id AND approved_at IS NULL)
        OR (state IN ('ACTIVE', 'REVIEW_PENDING', 'REVIEWED', 'EXPIRED', 'REVOKED') AND approver_id IS NOT NULL
            AND approver_id <> requester_id AND approved_at IS NOT NULL AND expires_at IS NOT NULL)
    ),
    CONSTRAINT chk_support_break_glass_ttl CHECK (
        expires_at IS NULL OR expires_at = approved_at + INTERVAL '2 minutes'
    ),
    CONSTRAINT chk_support_break_glass_review CHECK (
        (state IN ('REVIEW_PENDING', 'REVIEWED') AND revealed_at IS NOT NULL)
        OR (state NOT IN ('REVIEW_PENDING', 'REVIEWED') AND revealed_at IS NULL)
    ),
    CONSTRAINT chk_support_break_glass_reviewer CHECK (
        (state = 'REVIEWED' AND reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL
            AND reviewer_id <> requester_id AND reviewer_id <> approver_id)
        OR (state <> 'REVIEWED' AND reviewer_id IS NULL AND reviewed_at IS NULL)
    )
);

CREATE INDEX idx_support_break_glass_case_state
    ON support_break_glass_request (support_case_id, state, id);

CREATE TABLE support_break_glass_decision (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES support_break_glass_request(id),
    decision_type varchar(16) NOT NULL CHECK (decision_type IN ('PRE_APPROVAL', 'POST_REVIEW')),
    actor_id uuid NOT NULL,
    decision varchar(16) NOT NULL CHECK (decision IN ('APPROVED', 'DENIED', 'CONFIRMED', 'ESCALATED')),
    reason_code varchar(32) NOT NULL,
    request_version bigint NOT NULL CHECK (request_version >= 0),
    decided_at timestamptz NOT NULL,
    CONSTRAINT uq_support_break_glass_decision_type UNIQUE (request_id, decision_type),
    CONSTRAINT chk_support_break_glass_decision_shape CHECK (
        (decision_type = 'PRE_APPROVAL' AND decision IN ('APPROVED', 'DENIED'))
        OR (decision_type = 'POST_REVIEW' AND decision IN ('CONFIRMED', 'ESCALATED'))
    )
);

CREATE TRIGGER trg_support_break_glass_decision_append_only
    BEFORE UPDATE OR DELETE ON support_break_glass_decision
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TABLE support_reveal_attempt (
    id uuid PRIMARY KEY,
    access_path varchar(16) NOT NULL CHECK (access_path IN ('GRANT', 'BREAK_GLASS')),
    grant_id uuid REFERENCES support_data_access_grant(id),
    break_glass_request_id uuid REFERENCES support_break_glass_request(id),
    support_case_id uuid NOT NULL REFERENCES support_case(id),
    subject_link_id uuid NOT NULL REFERENCES support_case_subject_link(id),
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('CUSTOMER', 'STORE', 'DELIVERY')),
    subject_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    purpose varchar(32) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('RESERVED', 'REVEALED', 'FAILED')),
    failure_class varchar(32),
    reserved_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT chk_support_reveal_attempt_path CHECK (
        (access_path = 'GRANT' AND grant_id IS NOT NULL AND break_glass_request_id IS NULL)
        OR (access_path = 'BREAK_GLASS' AND grant_id IS NULL AND break_glass_request_id IS NOT NULL)
    ),
    CONSTRAINT chk_support_reveal_attempt_terminal CHECK (
        (state = 'RESERVED' AND completed_at IS NULL AND failure_class IS NULL)
        OR (state = 'REVEALED' AND completed_at IS NOT NULL AND failure_class IS NULL)
        OR (state = 'FAILED' AND completed_at IS NOT NULL AND failure_class IS NOT NULL)
    )
);

CREATE TABLE support_reveal_attempt_field (
    reveal_attempt_id uuid NOT NULL REFERENCES support_reveal_attempt(id),
    field varchar(48) NOT NULL CHECK (field IN (
        'CUSTOMER_DISPLAY_NAME', 'CUSTOMER_PRIMARY_PHONE', 'CUSTOMER_PRIMARY_EMAIL',
        'STORE_LEGAL_DISPLAY_NAME', 'STORE_SUPPORT_PHONE', 'STORE_SUPPORT_EMAIL',
        'COURIER_DISPLAY_NAME', 'COURIER_PROVIDER_REFERENCE', 'COURIER_RELAY_PHONE', 'COURIER_RELAY_EMAIL'
    )),
    PRIMARY KEY (reveal_attempt_id, field)
);

CREATE TABLE support_security_notification_intent (
    id uuid PRIMARY KEY,
    break_glass_request_id uuid NOT NULL REFERENCES support_break_glass_request(id),
    event_type varchar(24) NOT NULL CHECK (event_type IN ('REQUESTED', 'APPROVED', 'REVEALED')),
    state varchar(24) NOT NULL CHECK (state IN ('PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'SENT', 'MANUAL_REVIEW')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL,
    last_failure_class varchar(32),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_support_security_notification_event UNIQUE (break_glass_request_id, event_type)
);

CREATE INDEX idx_support_security_notification_due
    ON support_security_notification_intent (state, next_attempt_at, id);

CREATE TABLE support_security_command_idempotency (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(32) NOT NULL CHECK (operation IN (
        'CREATE_SESSION', 'ISSUE_CHALLENGE', 'VERIFY_CHALLENGE', 'REVOKE_SESSION',
        'REQUEST_GRANT', 'DECIDE_GRANT', 'REVEAL_GRANT', 'REQUEST_BREAK_GLASS',
        'DECIDE_BREAK_GLASS', 'REVEAL_BREAK_GLASS', 'REVIEW_BREAK_GLASS'
    )),
    idempotency_key varchar(128) NOT NULL CHECK (
        idempotency_key = btrim(idempotency_key)
        AND length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    resource_id uuid NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PROCESSING', 'COMPLETED')),
    response_status integer,
    response_body text,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    retention_expires_at timestamptz NOT NULL,
    CONSTRAINT uq_support_security_command_scope UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT chk_support_security_command_completion CHECK (
        (state = 'PROCESSING' AND response_status IS NULL AND response_body IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND response_status IN (200, 201) AND response_body IS NOT NULL
            AND length(response_body) BETWEEN 1 AND 20000 AND completed_at IS NOT NULL)
    ),
    CONSTRAINT chk_support_security_command_retention CHECK (
        retention_expires_at = created_at + INTERVAL '90 days'
    )
);

CREATE INDEX idx_support_security_command_retention
    ON support_security_command_idempotency (retention_expires_at, id);

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('SUPPORT_VERIFICATION_SESSION_CREATED', 'SECURITY_AND_PERMISSION'),
    ('SUPPORT_VERIFICATION_CHALLENGE_ISSUED', 'SECURITY_AND_PERMISSION'),
    ('SUPPORT_VERIFICATION_ATTEMPT_RECORDED', 'SECURITY_AND_PERMISSION'),
    ('SUPPORT_VERIFICATION_SESSION_REVOKED', 'SECURITY_AND_PERMISSION'),
    ('SUPPORT_DATA_ACCESS_GRANT_REQUESTED', 'SECURITY_AND_PERMISSION'),
    ('SUPPORT_DATA_ACCESS_GRANT_DECIDED', 'SECURITY_AND_PERMISSION'),
    ('SUPPORT_BREAK_GLASS_REQUESTED', 'SECURITY_AND_PERMISSION'),
    ('SUPPORT_BREAK_GLASS_DECIDED', 'SECURITY_AND_PERMISSION'),
    ('SUPPORT_BREAK_GLASS_REVIEWED', 'SECURITY_AND_PERMISSION');

COMMENT ON TABLE support_verification_attempt IS 'Append-only provider outcome; proof, OTP and raw links are never stored';
COMMENT ON TABLE support_reveal_attempt IS 'PII-free reveal ledger. Raw field values must never be stored';
COMMENT ON TABLE support_security_notification_intent IS 'Durable break-glass security notification intent without raw PII';
