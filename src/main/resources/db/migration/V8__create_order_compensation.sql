DO $$
DECLARE
    legacy_case_count bigint := 0;
    legacy_step_count bigint := 0;
BEGIN
    IF to_regclass('operations_order_compensation_case') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM operations_order_compensation_case'
           INTO legacy_case_count;
    END IF;
    IF to_regclass('operations_order_compensation_step') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM operations_order_compensation_step'
           INTO legacy_step_count;
    END IF;
    IF legacy_case_count <> 0 OR legacy_step_count <> 0 THEN
        RAISE EXCEPTION
            'V8 OrderCompensation clean-cutover precheck failed: legacy case/step rows exist';
    END IF;
END
$$;

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

CREATE TABLE operations_order_compensation_case (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    terminal_order_version bigint NOT NULL CHECK (terminal_order_version > 0),
    customer_id uuid NOT NULL,
    store_id uuid NOT NULL,
    event_id uuid NOT NULL UNIQUE,
    trigger varchar(32) NOT NULL CHECK (
        trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
    ),
    source_reference varchar(240) NOT NULL UNIQUE
        CHECK (source_reference = btrim(source_reference) AND length(source_reference) BETWEEN 1 AND 240),
    state varchar(24) NOT NULL CHECK (
        state IN ('PROCESSING', 'RETRY_SCHEDULED', 'UNKNOWN', 'SUCCEEDED', 'MANUAL_REVIEW')
    ),
    correlation_id varchar(160) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (order_id, terminal_order_version, trigger)
);

CREATE TABLE operations_order_compensation_benefit_policy_snapshot (
    case_id uuid NOT NULL REFERENCES operations_order_compensation_case(id) ON DELETE CASCADE,
    benefit_type varchar(16) NOT NULL CHECK (benefit_type IN ('COUPON', 'POINTS')),
    policy_version_id bigint NOT NULL
        REFERENCES operations_expired_benefit_policy_version(policy_version),
    mode varchar(48) NOT NULL CHECK (
        mode IN ('COMPENSATE_WITH_NEW_ISSUANCE', 'PRESERVE_ORIGINAL_EXPIRY')
    ),
    compensation_validity_days integer NOT NULL
        CHECK (compensation_validity_days BETWEEN 1 AND 365),
    PRIMARY KEY (case_id, benefit_type)
);

CREATE TABLE operations_order_compensation_step (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES operations_order_compensation_case(id) ON DELETE CASCADE,
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

CREATE INDEX idx_order_compensation_case_state
    ON operations_order_compensation_case (state, updated_at, id)
    WHERE state <> 'SUCCEEDED';

CREATE FUNCTION assert_order_compensation_cardinality(target_case_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    policy_count bigint;
    step_count bigint;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM operations_order_compensation_case WHERE id = target_case_id) THEN
        RETURN;
    END IF;

    SELECT count(*) INTO policy_count
      FROM operations_order_compensation_benefit_policy_snapshot
     WHERE case_id = target_case_id;
    SELECT count(*) INTO step_count
      FROM operations_order_compensation_step
     WHERE case_id = target_case_id;

    IF policy_count <> 2 OR step_count <> 6 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'OrderCompensationCase requires exactly two benefit policies and six steps';
    END IF;
    RETURN;
END
$$;

CREATE FUNCTION validate_order_compensation_case_cardinality()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM assert_order_compensation_cardinality(COALESCE(NEW.id, OLD.id));
    RETURN NULL;
END
$$;

CREATE FUNCTION validate_order_compensation_child_cardinality()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM assert_order_compensation_cardinality(COALESCE(NEW.case_id, OLD.case_id));
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER order_compensation_case_cardinality
    AFTER INSERT OR UPDATE ON operations_order_compensation_case
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_order_compensation_case_cardinality();

CREATE CONSTRAINT TRIGGER order_compensation_policy_cardinality
    AFTER INSERT OR UPDATE OR DELETE ON operations_order_compensation_benefit_policy_snapshot
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_order_compensation_child_cardinality();

CREATE CONSTRAINT TRIGGER order_compensation_step_cardinality
    AFTER INSERT OR UPDATE OR DELETE ON operations_order_compensation_step
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_order_compensation_child_cardinality();
