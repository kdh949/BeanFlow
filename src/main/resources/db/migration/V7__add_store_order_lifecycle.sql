CREATE TABLE identity_store_membership (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    store_id uuid NOT NULL,
    membership_role varchar(16) NOT NULL CHECK (membership_role IN ('OWNER', 'STAFF')),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (actor_id, store_id)
);

CREATE INDEX idx_identity_membership_store
    ON identity_store_membership (store_id, status, actor_id);

ALTER TABLE ordering_order
    ADD COLUMN paid_at timestamptz,
    ADD COLUMN acceptance_warning_at timestamptz,
    ADD COLUMN acceptance_warning_requested_at timestamptz,
    ADD COLUMN acceptance_deadline_at timestamptz,
    ADD COLUMN accepted_at timestamptz,
    ADD COLUMN rejected_at timestamptz,
    ADD COLUMN preparing_at timestamptz,
    ADD COLUMN ready_at timestamptz,
    ADD COLUMN completed_at timestamptz,
    ADD COLUMN rejection_reason varchar(500);

UPDATE ordering_order
   SET paid_at = updated_at,
       acceptance_warning_at = updated_at + interval '2 minutes',
       acceptance_deadline_at = updated_at + interval '3 minutes'
 WHERE state = 'PAID';

ALTER TABLE ordering_order
    DROP CONSTRAINT chk_order_state,
    DROP CONSTRAINT chk_order_state_payment_boundary,
    ADD CONSTRAINT chk_order_state
        CHECK (state IN (
            'PENDING_PAYMENT', 'PAID', 'ACCEPTED', 'PREPARING', 'READY',
            'COMPLETED', 'REJECTED', 'EXPIRED', 'CANCELLED'
        )),
    ADD CONSTRAINT chk_order_state_lifecycle
        CHECK (
            (state = 'PENDING_PAYMENT'
                AND payable_krw > 0
                AND reservation_expires_at IS NOT NULL
                AND paid_at IS NULL)
            OR
            (state = 'PAID'
                AND reservation_expires_at IS NULL
                AND paid_at IS NOT NULL
                AND acceptance_warning_at = paid_at + interval '2 minutes'
                AND acceptance_deadline_at = paid_at + interval '3 minutes'
                AND accepted_at IS NULL
                AND rejected_at IS NULL)
            OR
            (state = 'ACCEPTED'
                AND reservation_expires_at IS NULL
                AND paid_at IS NOT NULL
                AND accepted_at IS NOT NULL
                AND rejected_at IS NULL
                AND preparing_at IS NULL)
            OR
            (state = 'PREPARING'
                AND paid_at IS NOT NULL
                AND accepted_at IS NOT NULL
                AND preparing_at IS NOT NULL
                AND ready_at IS NULL)
            OR
            (state = 'READY'
                AND paid_at IS NOT NULL
                AND accepted_at IS NOT NULL
                AND preparing_at IS NOT NULL
                AND ready_at IS NOT NULL
                AND completed_at IS NULL)
            OR
            (state = 'COMPLETED'
                AND paid_at IS NOT NULL
                AND accepted_at IS NOT NULL
                AND preparing_at IS NOT NULL
                AND ready_at IS NOT NULL
                AND completed_at IS NOT NULL)
            OR
            (state = 'REJECTED'
                AND paid_at IS NOT NULL
                AND accepted_at IS NULL
                AND rejected_at IS NOT NULL
                AND length(trim(rejection_reason)) BETWEEN 1 AND 500)
            OR
            (state = 'EXPIRED'
                AND reservation_expires_at IS NOT NULL
                AND paid_at IS NULL)
            OR
            (state = 'CANCELLED'
                AND reservation_expires_at IS NULL)
        ),
    ADD CONSTRAINT chk_order_lifecycle_chronology
        CHECK (
            (paid_at IS NULL OR paid_at >= created_at)
            AND (acceptance_warning_requested_at IS NULL
                OR acceptance_warning_requested_at >= paid_at)
            AND (accepted_at IS NULL
                OR (accepted_at >= paid_at AND accepted_at < acceptance_deadline_at))
            AND (rejected_at IS NULL OR rejected_at >= paid_at)
            AND (preparing_at IS NULL OR preparing_at >= accepted_at)
            AND (ready_at IS NULL OR ready_at >= preparing_at)
            AND (completed_at IS NULL OR completed_at >= ready_at)
        );

CREATE INDEX idx_order_acceptance_warning
    ON ordering_order (acceptance_warning_at, id)
    WHERE state = 'PAID' AND acceptance_warning_requested_at IS NULL;

CREATE INDEX idx_order_acceptance_timeout
    ON ordering_order (acceptance_deadline_at, id)
    WHERE state = 'PAID';

CREATE TABLE ordering_store_command_idempotency (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    order_id uuid NOT NULL REFERENCES ordering_order(id),
    operation varchar(80) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK (length(payload_hash) = 64),
    response_status integer NOT NULL CHECK (response_status IN (200, 202)),
    response_body text NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (actor_id, operation, idempotency_key)
);

ALTER TABLE operations_audit_record
    DROP CONSTRAINT operations_audit_record_actor_type_check,
    ADD CONSTRAINT chk_audit_actor_type
        CHECK (actor_type IN (
            'CUSTOMER', 'STORE_OWNER', 'STORE_STAFF', 'PLATFORM_OPERATOR', 'SYSTEM'
        ));
