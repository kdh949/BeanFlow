SET LOCAL lock_timeout = '5s';

CREATE TABLE support_case (
    id uuid PRIMARY KEY,
    external_reference varchar(200),
    requester_type varchar(32) NOT NULL CHECK (requester_type IN (
        'CUSTOMER', 'STORE_OWNER', 'STORE_MEMBER', 'RIDER', 'THIRD_PARTY', 'INTERNAL_OPERATOR', 'SYSTEM', 'UNKNOWN'
    )),
    requester_reference varchar(200) NOT NULL CHECK (
        requester_reference = btrim(requester_reference)
        AND length(requester_reference) BETWEEN 1 AND 200
        AND requester_reference !~ '[[:cntrl:]]'
    ),
    category varchar(32) NOT NULL CHECK (category IN (
        'ORDER_STATUS', 'PICKUP_RESCHEDULE', 'ORDER_CANCELLATION', 'PAYMENT_OR_REFUND', 'COUPON_OR_POINT',
        'COMPENSATION', 'CUSTOMER_PROFILE', 'STORE_PROFILE', 'DELIVERY_STATUS', 'DELIVERY_INCIDENT', 'SETTLEMENT',
        'DISPUTE', 'ACCOUNT_RECOVERY', 'PRIVACY', 'SAFETY', 'OTHER'
    )),
    priority varchar(16) NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    reason varchar(500) NOT NULL CHECK (
        reason = btrim(reason)
        AND length(reason) BETWEEN 1 AND 500
        AND reason !~ '[[:cntrl:]]'
        AND (category <> 'OTHER' OR length(reason) >= 3)
    ),
    state varchar(16) NOT NULL CHECK (state IN ('OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED')),
    current_assignee_id uuid NOT NULL,
    opened_at timestamptz NOT NULL,
    last_changed_at timestamptz NOT NULL,
    closed_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    retention_policy_version_id bigint NOT NULL,
    retention_policy_category varchar(48) NOT NULL DEFAULT 'SUPPORT_CASE' CHECK (retention_policy_category = 'SUPPORT_CASE'),
    CONSTRAINT chk_support_case_external_reference CHECK (
        external_reference IS NULL OR (
            external_reference = btrim(external_reference)
            AND length(external_reference) BETWEEN 1 AND 200
            AND external_reference !~ '[[:cntrl:]]'
        )
    ),
    CONSTRAINT chk_support_case_time_order CHECK (opened_at <= last_changed_at),
    CONSTRAINT chk_support_case_closed_state CHECK (
        (state = 'CLOSED' AND closed_at IS NOT NULL AND closed_at >= opened_at)
        OR (state <> 'CLOSED' AND closed_at IS NULL)
    ),
    CONSTRAINT fk_support_case_retention_policy
        FOREIGN KEY (retention_policy_version_id, retention_policy_category)
        REFERENCES operations_retention_policy_version (policy_version_id, category)
);

CREATE INDEX idx_support_case_list
    ON support_case (state, current_assignee_id, opened_at DESC, id DESC);

CREATE TABLE support_case_assignment_history (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case (id),
    sequence integer NOT NULL CHECK (sequence >= 0),
    previous_assignee_id uuid,
    current_assignee_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    case_version bigint NOT NULL CHECK (case_version >= 0),
    occurred_at timestamptz NOT NULL,
    CONSTRAINT uq_support_case_assignment_history_sequence UNIQUE (support_case_id, sequence),
    CONSTRAINT chk_support_case_assignment_initial_entry CHECK (
        (sequence = 0 AND previous_assignee_id IS NULL AND case_version = 0)
        OR (sequence > 0 AND previous_assignee_id IS NOT NULL AND case_version > 0)
    )
);

CREATE TABLE support_case_state_history (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case (id),
    sequence integer NOT NULL CHECK (sequence >= 0),
    previous_state varchar(16) CHECK (previous_state IN ('OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED')),
    current_state varchar(16) NOT NULL CHECK (current_state IN ('OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED')),
    actor_id uuid NOT NULL,
    case_version bigint NOT NULL CHECK (case_version >= 0),
    occurred_at timestamptz NOT NULL,
    CONSTRAINT uq_support_case_state_history_sequence UNIQUE (support_case_id, sequence),
    CONSTRAINT chk_support_case_state_initial_entry CHECK (
        (sequence = 0 AND previous_state IS NULL AND current_state = 'OPEN' AND case_version = 0)
        OR (sequence > 0 AND previous_state IS NOT NULL AND case_version > 0)
    )
);

CREATE OR REPLACE FUNCTION reject_support_case_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_support_case_assignment_history_append_only
    BEFORE UPDATE OR DELETE ON support_case_assignment_history
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TRIGGER trg_support_case_state_history_append_only
    BEFORE UPDATE OR DELETE ON support_case_state_history
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE TABLE support_case_interaction (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case (id),
    sequence integer NOT NULL CHECK (sequence >= 0),
    channel varchar(24) NOT NULL CHECK (channel IN ('PHONE', 'CHAT', 'EMAIL', 'IN_PERSON', 'SYSTEM')),
    direction varchar(16) NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND', 'INTERNAL')),
    redacted_summary varchar(1000) NOT NULL CHECK (
        redacted_summary = btrim(redacted_summary)
        AND length(redacted_summary) BETWEEN 1 AND 1000
        AND redacted_summary !~ '[[:cntrl:]]'
    ),
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by_actor_id uuid NOT NULL,
    retention_policy_version_id bigint NOT NULL,
    retention_policy_category varchar(48) NOT NULL DEFAULT 'SUPPORT_CASE' CHECK (retention_policy_category = 'SUPPORT_CASE'),
    CONSTRAINT uq_support_case_interaction_sequence UNIQUE (support_case_id, sequence),
    CONSTRAINT chk_support_case_interaction_time CHECK (occurred_at <= recorded_at),
    CONSTRAINT fk_support_case_interaction_retention_policy
        FOREIGN KEY (retention_policy_version_id, retention_policy_category)
        REFERENCES operations_retention_policy_version (policy_version_id, category)
);

CREATE INDEX idx_support_case_interaction_cursor
    ON support_case_interaction (support_case_id, occurred_at DESC, id DESC);

CREATE TABLE support_case_note (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case (id),
    sequence integer NOT NULL CHECK (sequence >= 0),
    content varchar(2000) NOT NULL CHECK (
        content = btrim(content)
        AND length(content) BETWEEN 1 AND 2000
        AND content !~ '[[:cntrl:]]'
    ),
    reason varchar(500) NOT NULL CHECK (
        reason = btrim(reason)
        AND length(reason) BETWEEN 1 AND 500
        AND reason !~ '[[:cntrl:]]'
    ),
    author_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    retention_policy_version_id bigint NOT NULL,
    retention_policy_category varchar(48) NOT NULL DEFAULT 'SUPPORT_CASE' CHECK (retention_policy_category = 'SUPPORT_CASE'),
    CONSTRAINT uq_support_case_note_sequence UNIQUE (support_case_id, sequence),
    CONSTRAINT fk_support_case_note_retention_policy
        FOREIGN KEY (retention_policy_version_id, retention_policy_category)
        REFERENCES operations_retention_policy_version (policy_version_id, category)
);

CREATE INDEX idx_support_case_note_cursor
    ON support_case_note (support_case_id, created_at DESC, id DESC);

CREATE TABLE support_case_subject_link (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case (id),
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('CUSTOMER', 'STORE', 'ORDER', 'DELIVERY')),
    subject_id uuid NOT NULL,
    relationship varchar(32) NOT NULL CHECK (relationship IN (
        'REQUESTER', 'AFFECTED_CUSTOMER', 'AFFECTED_STORE', 'RELATED_ORDER', 'RELATED_DELIVERY', 'OTHER'
    )),
    linked_by_actor_id uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (
        reason = btrim(reason)
        AND length(reason) BETWEEN 1 AND 500
        AND reason !~ '[[:cntrl:]]'
    ),
    linked_at timestamptz NOT NULL,
    unlinked_by_actor_id uuid,
    unlink_reason varchar(500),
    unlinked_at timestamptz,
    unlink_case_version bigint,
    CONSTRAINT chk_support_case_subject_link_unlink CHECK (
        (unlinked_at IS NULL AND unlinked_by_actor_id IS NULL AND unlink_reason IS NULL AND unlink_case_version IS NULL)
        OR (
            unlinked_at IS NOT NULL
            AND unlinked_at >= linked_at
            AND unlinked_by_actor_id IS NOT NULL
            AND unlink_reason = btrim(unlink_reason)
            AND length(unlink_reason) BETWEEN 1 AND 500
            AND unlink_reason !~ '[[:cntrl:]]'
            AND unlink_case_version >= 0
        )
    )
);

CREATE UNIQUE INDEX uq_support_case_subject_link_active
    ON support_case_subject_link (support_case_id, subject_type, subject_id, relationship)
    WHERE unlinked_at IS NULL;

CREATE INDEX idx_support_case_subject_link_case
    ON support_case_subject_link (support_case_id, linked_at DESC, id DESC);

CREATE TABLE support_case_command_idempotency (
    idempotency_key varchar(128) PRIMARY KEY CHECK (
        idempotency_key = btrim(idempotency_key)
        AND length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    operation varchar(32) NOT NULL CHECK (operation IN (
        'CREATE_CASE', 'ASSIGN_CASE', 'TRANSITION_CASE', 'APPEND_INTERACTION', 'APPEND_NOTE', 'LINK_SUBJECT', 'UNLINK_SUBJECT'
    )),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    response_status integer NOT NULL CHECK (response_status IN (200, 201)),
    response_body text NOT NULL CHECK (length(response_body) BETWEEN 1 AND 10000),
    created_at timestamptz NOT NULL
);

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('SUPPORT_CASE_CREATED', 'OPERATIONS_POLICY'),
    ('SUPPORT_CASE_ASSIGNED', 'OPERATIONS_POLICY'),
    ('SUPPORT_CASE_STATE_TRANSITIONED', 'OPERATIONS_POLICY'),
    ('SUPPORT_CASE_INTERACTION_APPENDED', 'OPERATIONS_POLICY'),
    ('SUPPORT_CASE_NOTE_APPENDED', 'OPERATIONS_POLICY'),
    ('SUPPORT_CASE_SUBJECT_LINKED', 'OPERATIONS_POLICY'),
    ('SUPPORT_CASE_SUBJECT_UNLINKED', 'OPERATIONS_POLICY');
