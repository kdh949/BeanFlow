-- Plan 13 starts from the repository's verified pre-release clean database.
-- A snapshotted completion or successful partial Refund produced by V16 code
-- without the Plan 13 owner receipts cannot be guessed or silently skipped.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM ordering_order order_row
          JOIN ordering_order_point_accrual_source source ON source.order_id = order_row.id
         WHERE source.source_state = 'SNAPSHOTTED'
           AND order_row.state = 'COMPLETED'
    ) OR EXISTS (
        SELECT 1
          FROM payment_refund refund
          JOIN ordering_order_point_accrual_source source ON source.order_id = refund.order_id
         WHERE refund.reason = 'PARTIAL_REFUND'
           AND refund.state = 'SUCCEEDED'
           AND source.source_state = 'SNAPSHOTTED'
    ) THEN
        RAISE EXCEPTION
            'V17 point recovery activation failed: existing snapshotted completion/refund requires an explicit owner-source migration';
    END IF;
END
$$;

CREATE TABLE payment_order_point_accrual_outcome (
    order_id uuid PRIMARY KEY,
    outcome_state varchar(24) NOT NULL CHECK (outcome_state IN ('COMPLETED', 'NOT_APPLICABLE')),
    order_state varchar(24) NOT NULL CHECK (order_state IN (
        'COMPLETED', 'REJECTED', 'CANCELLED', 'EXPIRED'
    )),
    outcome_at timestamptz NOT NULL,
    source_reference varchar(240) NOT NULL UNIQUE,
    aggregate_version bigint NOT NULL CHECK (aggregate_version > 0),
    snapshot_schema_version integer,
    snapshot_hash varchar(64),
    created_at timestamptz NOT NULL,
    CONSTRAINT chk_payment_point_accrual_outcome_shape CHECK (
        (outcome_state = 'COMPLETED'
            AND order_state = 'COMPLETED'
            AND snapshot_schema_version > 0
            AND snapshot_hash ~ '^[0-9a-f]{64}$')
        OR
        (outcome_state = 'NOT_APPLICABLE'
            AND order_state IN ('COMPLETED', 'REJECTED', 'CANCELLED', 'EXPIRED')
            AND snapshot_schema_version IS NULL
            AND snapshot_hash IS NULL)
    )
);

CREATE TABLE payment_refund_point_recovery_work (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL UNIQUE REFERENCES payment_refund(id),
    order_id uuid NOT NULL,
    outcome_order_id uuid REFERENCES payment_order_point_accrual_outcome(order_id),
    state varchar(32) NOT NULL CHECK (state IN (
        'PENDING', 'ELIGIBILITY_PROCESSING', 'ELIGIBILITY_RETRY',
        'READY', 'PROCESSING', 'RETRY_SCHEDULED', 'SUCCEEDED',
        'EXCLUDED_BEFORE_ACCRUAL', 'NOT_REQUIRED', 'NOT_APPLICABLE', 'MANUAL_REVIEW'
    )),
    refund_succeeded_at timestamptz NOT NULL,
    target_amount_krw bigint CHECK (target_amount_krw >= 0),
    recovered_amount_krw bigint CHECK (recovered_amount_krw >= 0),
    pending_amount_krw bigint CHECK (pending_amount_krw >= 0),
    snapshot_schema_version integer,
    snapshot_hash varchar(64),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    next_attempt_at timestamptz,
    claim_token uuid,
    claim_until timestamptz,
    last_failure_code varchar(80),
    source_reference varchar(240) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT chk_payment_refund_point_recovery_snapshot CHECK (
        (snapshot_schema_version IS NULL AND snapshot_hash IS NULL)
        OR
        (snapshot_schema_version > 0 AND snapshot_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT chk_payment_refund_point_recovery_state CHECK (
        (state = 'PENDING'
            AND target_amount_krw IS NULL
            AND recovered_amount_krw IS NULL
            AND pending_amount_krw IS NULL
            AND snapshot_schema_version IS NULL
            AND snapshot_hash IS NULL
            AND attempt_count = 0
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'ELIGIBILITY_PROCESSING'
            AND target_amount_krw IS NULL
            AND recovered_amount_krw IS NULL
            AND pending_amount_krw IS NULL
            AND snapshot_schema_version IS NULL
            AND snapshot_hash IS NULL
            AND attempt_count BETWEEN 1 AND 5
            AND next_attempt_at IS NULL
            AND claim_token IS NOT NULL
            AND claim_until IS NOT NULL)
        OR
        (state = 'ELIGIBILITY_RETRY'
            AND target_amount_krw IS NULL
            AND recovered_amount_krw IS NULL
            AND pending_amount_krw IS NULL
            AND snapshot_schema_version IS NULL
            AND snapshot_hash IS NULL
            AND attempt_count BETWEEN 1 AND 4
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'READY'
            AND outcome_order_id IS NOT NULL
            AND target_amount_krw > 0
            AND recovered_amount_krw IS NULL
            AND pending_amount_krw IS NULL
            AND snapshot_schema_version IS NOT NULL
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'PROCESSING'
            AND outcome_order_id IS NOT NULL
            AND target_amount_krw > 0
            AND recovered_amount_krw IS NULL
            AND pending_amount_krw IS NULL
            AND snapshot_schema_version IS NOT NULL
            AND attempt_count BETWEEN 1 AND 5
            AND next_attempt_at IS NULL
            AND claim_token IS NOT NULL
            AND claim_until IS NOT NULL)
        OR
        (state = 'RETRY_SCHEDULED'
            AND outcome_order_id IS NOT NULL
            AND target_amount_krw > 0
            AND recovered_amount_krw IS NULL
            AND pending_amount_krw IS NULL
            AND snapshot_schema_version IS NOT NULL
            AND attempt_count BETWEEN 1 AND 4
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'SUCCEEDED'
            AND outcome_order_id IS NOT NULL
            AND target_amount_krw > 0
            AND recovered_amount_krw >= 0
            AND pending_amount_krw >= 0
            AND recovered_amount_krw + pending_amount_krw = target_amount_krw
            AND snapshot_schema_version IS NOT NULL
            AND attempt_count BETWEEN 1 AND 5
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'EXCLUDED_BEFORE_ACCRUAL'
            AND outcome_order_id IS NOT NULL
            AND target_amount_krw >= 0
            AND recovered_amount_krw IS NULL
            AND pending_amount_krw IS NULL
            AND snapshot_schema_version IS NOT NULL
            AND attempt_count = 0
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'NOT_REQUIRED'
            AND outcome_order_id IS NOT NULL
            AND target_amount_krw = 0
            AND recovered_amount_krw IS NULL
            AND pending_amount_krw IS NULL
            AND snapshot_schema_version IS NOT NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'NOT_APPLICABLE'
            AND outcome_order_id IS NOT NULL
            AND target_amount_krw IS NULL
            AND recovered_amount_krw IS NULL
            AND pending_amount_krw IS NULL
            AND snapshot_schema_version IS NULL
            AND snapshot_hash IS NULL
            AND attempt_count = 0
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'MANUAL_REVIEW'
            AND recovered_amount_krw IS NULL
            AND pending_amount_krw IS NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL
            AND last_failure_code IS NOT NULL)
    )
);

CREATE INDEX idx_payment_refund_point_recovery_due
    ON payment_refund_point_recovery_work (next_attempt_at, id)
    WHERE state IN (
        'PENDING', 'ELIGIBILITY_PROCESSING', 'ELIGIBILITY_RETRY',
        'READY', 'PROCESSING', 'RETRY_SCHEDULED'
    );

ALTER TABLE loyalty_point_account
    ADD COLUMN recovery_pending_krw bigint NOT NULL DEFAULT 0
        CHECK (recovery_pending_krw >= 0);

ALTER TABLE loyalty_point_lot
    ADD COLUMN accrual_order_id uuid,
    ADD COLUMN accrual_source_reference varchar(240),
    ADD COLUMN accrual_snapshot_hash varchar(64),
    ADD CONSTRAINT uq_point_lot_accrual_source UNIQUE (accrual_source_reference),
    ADD CONSTRAINT chk_point_lot_accrual_source CHECK (
        (accrual_order_id IS NULL
            AND accrual_source_reference IS NULL
            AND accrual_snapshot_hash IS NULL)
        OR
        (accrual_order_id IS NOT NULL
            AND length(btrim(accrual_source_reference)) > 0
            AND accrual_snapshot_hash ~ '^[0-9a-f]{64}$'
            AND restoration_refund_id IS NULL)
    );

CREATE TABLE loyalty_point_recovery_pending (
    id uuid PRIMARY KEY,
    point_account_id uuid NOT NULL REFERENCES loyalty_point_account(id),
    refund_source_reference varchar(240) NOT NULL,
    initial_amount_krw bigint NOT NULL CHECK (initial_amount_krw > 0),
    remaining_amount_krw bigint NOT NULL CHECK (
        remaining_amount_krw >= 0 AND remaining_amount_krw <= initial_amount_krw
    ),
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'SETTLED')),
    created_at timestamptz NOT NULL,
    settled_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (point_account_id, refund_source_reference),
    CONSTRAINT chk_point_recovery_pending_state CHECK (
        (state = 'PENDING' AND remaining_amount_krw > 0 AND settled_at IS NULL)
        OR
        (state = 'SETTLED' AND remaining_amount_krw = 0 AND settled_at IS NOT NULL)
    )
);

CREATE INDEX idx_point_recovery_pending_oldest
    ON loyalty_point_recovery_pending (point_account_id, state, created_at, id);

ALTER TABLE loyalty_point_transaction
    DROP CONSTRAINT chk_point_transaction_type,
    ADD COLUMN point_recovery_pending_id uuid REFERENCES loyalty_point_recovery_pending(id),
    ADD CONSTRAINT chk_point_transaction_type CHECK (type IN (
        'ACCRUAL', 'USE', 'EXPIRATION', 'RESTORE', 'COMPENSATION',
        'RESTORE_SKIPPED_EXPIRED', 'RECOVERY'
    )),
    ADD CONSTRAINT chk_point_transaction_recovery_pending CHECK (
        point_recovery_pending_id IS NULL OR type = 'RECOVERY'
    );

CREATE TABLE loyalty_point_recovery_result (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL UNIQUE,
    order_id uuid NOT NULL,
    point_account_id uuid NOT NULL REFERENCES loyalty_point_account(id),
    refund_source_reference varchar(240) NOT NULL UNIQUE,
    completion_source_reference varchar(240) NOT NULL,
    completion_aggregate_version bigint NOT NULL CHECK (completion_aggregate_version >= 0),
    snapshot_schema_version integer NOT NULL CHECK (snapshot_schema_version > 0),
    snapshot_hash varchar(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    target_amount_krw bigint NOT NULL CHECK (target_amount_krw > 0),
    recovered_amount_krw bigint NOT NULL CHECK (recovered_amount_krw >= 0),
    pending_amount_krw bigint NOT NULL CHECK (pending_amount_krw >= 0),
    refund_succeeded_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CHECK (refund_succeeded_at > completed_at),
    CHECK (recovered_amount_krw + pending_amount_krw = target_amount_krw)
);

CREATE TABLE loyalty_point_accrual_result (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    point_account_id uuid REFERENCES loyalty_point_account(id),
    completion_source_reference varchar(240) NOT NULL UNIQUE,
    completion_aggregate_version bigint NOT NULL CHECK (completion_aggregate_version >= 0),
    source_state varchar(32) NOT NULL CHECK (source_state IN (
        'LEGACY_NOT_APPLICABLE', 'APPLIED', 'NO_ACCRUAL'
    )),
    snapshot_schema_version integer,
    snapshot_hash varchar(64),
    excluded_units_hash varchar(64),
    snapshot_gross_amount_krw bigint,
    excluded_amount_krw bigint,
    accrued_amount_krw bigint,
    offset_amount_krw bigint,
    available_amount_krw bigint,
    point_lot_id uuid REFERENCES loyalty_point_lot(id),
    completed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT chk_point_accrual_result_shape CHECK (
        (source_state = 'LEGACY_NOT_APPLICABLE'
            AND point_account_id IS NULL
            AND snapshot_schema_version IS NULL
            AND snapshot_hash IS NULL
            AND excluded_units_hash IS NULL
            AND snapshot_gross_amount_krw IS NULL
            AND excluded_amount_krw IS NULL
            AND accrued_amount_krw IS NULL
            AND offset_amount_krw IS NULL
            AND available_amount_krw IS NULL
            AND point_lot_id IS NULL)
        OR
        (source_state = 'NO_ACCRUAL'
            AND point_account_id IS NOT NULL
            AND snapshot_schema_version > 0
            AND snapshot_hash ~ '^[0-9a-f]{64}$'
            AND excluded_units_hash ~ '^[0-9a-f]{64}$'
            AND snapshot_gross_amount_krw >= 0
            AND excluded_amount_krw BETWEEN 0 AND snapshot_gross_amount_krw
            AND accrued_amount_krw = snapshot_gross_amount_krw - excluded_amount_krw
            AND accrued_amount_krw = 0
            AND offset_amount_krw = 0
            AND available_amount_krw = 0
            AND point_lot_id IS NULL)
        OR
        (source_state = 'APPLIED'
            AND point_account_id IS NOT NULL
            AND snapshot_schema_version > 0
            AND snapshot_hash ~ '^[0-9a-f]{64}$'
            AND excluded_units_hash ~ '^[0-9a-f]{64}$'
            AND snapshot_gross_amount_krw > 0
            AND excluded_amount_krw BETWEEN 0 AND snapshot_gross_amount_krw
            AND accrued_amount_krw = snapshot_gross_amount_krw - excluded_amount_krw
            AND accrued_amount_krw > 0
            AND offset_amount_krw BETWEEN 0 AND accrued_amount_krw
            AND available_amount_krw = accrued_amount_krw - offset_amount_krw
            AND point_lot_id IS NOT NULL)
    )
);

CREATE FUNCTION validate_point_recovery_pending_summary()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_account_id uuid;
    account_summary numeric;
    pending_summary numeric;
BEGIN
    IF TG_TABLE_NAME = 'loyalty_point_account' THEN
        target_account_id := NEW.id;
    ELSE
        target_account_id := NEW.point_account_id;
    END IF;

    SELECT recovery_pending_krw
      INTO account_summary
      FROM loyalty_point_account
     WHERE id = target_account_id;

    SELECT COALESCE(SUM(remaining_amount_krw), 0)
      INTO pending_summary
      FROM loyalty_point_recovery_pending
     WHERE point_account_id = target_account_id
       AND state = 'PENDING';

    IF account_summary IS NULL OR account_summary <> pending_summary THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'PointAccount recovery pending summary does not tie out';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER point_account_recovery_pending_summary
    AFTER INSERT OR UPDATE OF recovery_pending_krw ON loyalty_point_account
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_point_recovery_pending_summary();

CREATE CONSTRAINT TRIGGER point_recovery_pending_account_summary
    AFTER INSERT OR UPDATE OF remaining_amount_krw, state ON loyalty_point_recovery_pending
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_point_recovery_pending_summary();

CREATE FUNCTION reject_point_recovery_pending_identity_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.point_account_id IS DISTINCT FROM OLD.point_account_id
        OR NEW.refund_source_reference IS DISTINCT FROM OLD.refund_source_reference
        OR NEW.initial_amount_krw IS DISTINCT FROM OLD.initial_amount_krw
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.remaining_amount_krw > OLD.remaining_amount_krw
        OR (OLD.state = 'SETTLED' AND NEW.state <> 'SETTLED')
        OR (OLD.settled_at IS NOT NULL AND NEW.settled_at IS DISTINCT FROM OLD.settled_at) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'PointRecoveryPending identity and monotonic state are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER point_recovery_pending_monotonic
    BEFORE UPDATE ON loyalty_point_recovery_pending
    FOR EACH ROW EXECUTE FUNCTION reject_point_recovery_pending_identity_change();

CREATE FUNCTION reject_plan13_immutable_row_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'Plan 13 source/result rows are immutable';
END;
$$;

CREATE TRIGGER payment_point_accrual_outcome_immutable
    BEFORE UPDATE OR DELETE ON payment_order_point_accrual_outcome
    FOR EACH ROW EXECUTE FUNCTION reject_plan13_immutable_row_change();

CREATE TRIGGER loyalty_point_recovery_result_immutable
    BEFORE UPDATE OR DELETE ON loyalty_point_recovery_result
    FOR EACH ROW EXECUTE FUNCTION reject_plan13_immutable_row_change();

CREATE TRIGGER loyalty_point_accrual_result_immutable
    BEFORE UPDATE OR DELETE ON loyalty_point_accrual_result
    FOR EACH ROW EXECUTE FUNCTION reject_plan13_immutable_row_change();
