CREATE TABLE event_publication (
    id uuid NOT NULL PRIMARY KEY,
    listener_id text NOT NULL,
    event_type text NOT NULL,
    serialized_event text NOT NULL,
    publication_date timestamptz NOT NULL,
    completion_date timestamptz,
    status text,
    completion_attempts integer NOT NULL DEFAULT 0,
    last_resubmission_date timestamptz
);

CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);

CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication (completion_date);

CREATE TABLE operations_expired_benefit_policy_version (
    policy_version bigint PRIMARY KEY CHECK (policy_version > 0),
    mode varchar(48) NOT NULL CHECK (
        mode IN ('COMPENSATE_WITH_NEW_ISSUANCE', 'PRESERVE_ORIGINAL_EXPIRY')
    ),
    compensation_validity_days integer NOT NULL
        CHECK (compensation_validity_days BETWEEN 1 AND 365),
    effective_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 500),
    idempotency_actor_id uuid,
    idempotency_key varchar(128),
    payload_hash varchar(64),
    CHECK (
        (idempotency_actor_id IS NULL AND idempotency_key IS NULL AND payload_hash IS NULL)
        OR
        (idempotency_actor_id IS NOT NULL
            AND idempotency_key IS NOT NULL
            AND length(idempotency_key) BETWEEN 8 AND 128
            AND length(payload_hash) = 64)
    ),
    UNIQUE (idempotency_actor_id, idempotency_key)
);

CREATE TABLE operations_expired_benefit_policy_head (
    singleton_id boolean PRIMARY KEY DEFAULT true CHECK (singleton_id),
    policy_version bigint NOT NULL REFERENCES operations_expired_benefit_policy_version(policy_version),
    version bigint NOT NULL DEFAULT 0
);

INSERT INTO operations_expired_benefit_policy_version (
    policy_version,
    mode,
    compensation_validity_days,
    effective_at,
    updated_by,
    reason
) VALUES (
    1,
    'COMPENSATE_WITH_NEW_ISSUANCE',
    30,
    TIMESTAMPTZ '2026-07-30 00:00:00+00',
    '00000000-0000-0000-0000-000000000000',
    'INITIAL_DEFAULT'
);

INSERT INTO operations_expired_benefit_policy_head (singleton_id, policy_version)
VALUES (true, 1);

CREATE TABLE operations_rejection_compensation_case (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    customer_id uuid NOT NULL,
    store_id uuid NOT NULL,
    event_id uuid NOT NULL UNIQUE,
    source_reference varchar(200) NOT NULL UNIQUE,
    policy_version bigint NOT NULL,
    policy_mode varchar(48) NOT NULL,
    policy_validity_days integer NOT NULL,
    state varchar(24) NOT NULL CHECK (
        state IN ('PROCESSING', 'RETRY_SCHEDULED', 'UNKNOWN', 'SUCCEEDED', 'MANUAL_REVIEW')
    ),
    correlation_id varchar(160) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (policy_version)
        REFERENCES operations_expired_benefit_policy_version(policy_version)
);

CREATE TABLE operations_rejection_compensation_step (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES operations_rejection_compensation_case(id),
    step_type varchar(32) NOT NULL CHECK (
        step_type IN ('PAYMENT', 'PICKUP', 'STOCK', 'COUPON', 'POINTS', 'CUSTOMER_NOTIFICATION')
    ),
    state varchar(24) NOT NULL CHECK (
        state IN (
            'PROCESSING', 'RETRY_SCHEDULED', 'UNKNOWN',
            'SUCCEEDED', 'NOT_REQUIRED', 'MANUAL_REVIEW'
        )
    ),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error_code varchar(100),
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (case_id, step_type)
);

CREATE INDEX idx_rejection_case_state
    ON operations_rejection_compensation_case (state, updated_at, id)
    WHERE state <> 'SUCCEEDED';
