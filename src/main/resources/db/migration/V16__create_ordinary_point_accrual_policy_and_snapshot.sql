CREATE SEQUENCE operations_point_accrual_policy_version_seq;

CREATE TABLE operations_point_accrual_policy_version (
    policy_version_id bigint PRIMARY KEY
        DEFAULT nextval('operations_point_accrual_policy_version_seq'),
    scope_type varchar(16) NOT NULL,
    scope_reference uuid NOT NULL,
    state varchar(24) NOT NULL,
    accrual_rate_bps integer,
    rounding_mode varchar(16),
    issuer_type varchar(16),
    issuer_reference varchar(240),
    expiry_rule varchar(48),
    validity_days integer,
    effective_at timestamptz NOT NULL,
    actor_type varchar(24) NOT NULL,
    actor_reference varchar(500) NOT NULL,
    reason varchar(500) NOT NULL,
    idempotency_actor_id uuid,
    idempotency_key varchar(128),
    payload_hash varchar(64) NOT NULL,
    source_reference varchar(240) NOT NULL UNIQUE,
    CONSTRAINT uq_point_accrual_policy_version_scope
        UNIQUE (policy_version_id, scope_type, scope_reference),
    CONSTRAINT uq_point_accrual_policy_idempotency
        UNIQUE (idempotency_actor_id, idempotency_key),
    CONSTRAINT chk_point_accrual_policy_scope
        CHECK (
            (scope_type = 'GLOBAL'
                AND scope_reference = '00000000-0000-0000-0000-000000000000'::uuid
                AND state = 'OVERRIDE')
            OR
            (scope_type = 'STORE'
                AND scope_reference <> '00000000-0000-0000-0000-000000000000'::uuid
                AND state IN ('OVERRIDE', 'INHERIT_GLOBAL'))
        ),
    CONSTRAINT chk_point_accrual_policy_shape
        CHECK (
            (state = 'OVERRIDE'
                AND accrual_rate_bps BETWEEN 0 AND 10000
                AND rounding_mode IN ('FLOOR', 'HALF_UP')
                AND issuer_type IN ('PLATFORM', 'BRAND', 'STORE')
                AND issuer_reference IS NOT NULL
                AND issuer_reference = btrim(issuer_reference)
                AND length(issuer_reference) BETWEEN 1 AND 240
                AND issuer_reference !~ '[[:cntrl:]]'
                AND expiry_rule IN (
                    'EXACT_DURATION_FROM_COMPLETION',
                    'SEOUL_CALENDAR_DAYS_FROM_COMPLETION'
                )
                AND validity_days BETWEEN 1 AND 3650)
            OR
            (state = 'INHERIT_GLOBAL'
                AND scope_type = 'STORE'
                AND accrual_rate_bps IS NULL
                AND rounding_mode IS NULL
                AND issuer_type IS NULL
                AND issuer_reference IS NULL
                AND expiry_rule IS NULL
                AND validity_days IS NULL)
        ),
    CONSTRAINT chk_point_accrual_policy_actor
        CHECK (
            actor_type IN ('SYSTEM', 'PLATFORM_OPERATOR')
            AND actor_reference = btrim(actor_reference)
            AND length(actor_reference) BETWEEN 1 AND 500
            AND actor_reference !~ '[[:cntrl:]]'
        ),
    CONSTRAINT chk_point_accrual_policy_reason
        CHECK (
            reason = btrim(reason)
            AND length(reason) BETWEEN 1 AND 500
            AND reason !~ '[[:cntrl:]]'
        ),
    CONSTRAINT chk_point_accrual_policy_idempotency
        CHECK (
            (idempotency_actor_id IS NULL AND idempotency_key IS NULL)
            OR
            (actor_type = 'PLATFORM_OPERATOR'
                AND idempotency_actor_id IS NOT NULL
                AND length(idempotency_key) BETWEEN 8 AND 128)
        ),
    CONSTRAINT chk_point_accrual_policy_payload_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_point_accrual_policy_source_reference
        CHECK (
            source_reference = btrim(source_reference)
            AND length(source_reference) BETWEEN 1 AND 240
            AND source_reference !~ '[[:cntrl:]]'
        )
);

ALTER SEQUENCE operations_point_accrual_policy_version_seq
    OWNED BY operations_point_accrual_policy_version.policy_version_id;

CREATE INDEX idx_point_accrual_policy_scope_history
    ON operations_point_accrual_policy_version (
        scope_type,
        scope_reference,
        policy_version_id DESC
    );

CREATE TABLE operations_point_accrual_policy_head (
    scope_type varchar(16) NOT NULL,
    scope_reference uuid NOT NULL,
    policy_version_id bigint NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (scope_type, scope_reference),
    CONSTRAINT chk_point_accrual_policy_head_scope
        CHECK (
            (scope_type = 'GLOBAL'
                AND scope_reference = '00000000-0000-0000-0000-000000000000'::uuid)
            OR
            (scope_type = 'STORE'
                AND scope_reference <> '00000000-0000-0000-0000-000000000000'::uuid)
        ),
    CONSTRAINT fk_point_accrual_policy_head_version_scope
        FOREIGN KEY (policy_version_id, scope_type, scope_reference)
        REFERENCES operations_point_accrual_policy_version(
            policy_version_id,
            scope_type,
            scope_reference
        )
);

CREATE FUNCTION reject_point_accrual_policy_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'ordinary point accrual policy versions are immutable';
END;
$$;

CREATE TRIGGER point_accrual_policy_version_immutable
    BEFORE UPDATE OR DELETE ON operations_point_accrual_policy_version
    FOR EACH ROW EXECUTE FUNCTION reject_point_accrual_policy_version_mutation();

ALTER TABLE operations_operator_permission_grant
    DROP CONSTRAINT chk_operator_permission_vocabulary,
    ADD CONSTRAINT chk_operator_permission_vocabulary
        CHECK (permission IN (
            'EXPIRED_BENEFIT_POLICY_READ',
            'EXPIRED_BENEFIT_POLICY_WRITE',
            'POINT_ACCOUNT_READ',
            'POINT_ADJUSTMENT',
            'POINT_ACCRUAL_POLICY_READ',
            'POINT_ACCRUAL_POLICY_WRITE'
        ));

ALTER TABLE ordering_order_line
    ADD CONSTRAINT uq_ordering_order_line_order_id_sequence
        UNIQUE (order_id, id, line_sequence);

CREATE TABLE ordering_order_point_accrual_source (
    order_id uuid PRIMARY KEY REFERENCES ordering_order(id),
    source_state varchar(32) NOT NULL
        CHECK (source_state IN ('LEGACY_NOT_APPLICABLE', 'SNAPSHOTTED')),
    created_at timestamptz NOT NULL,
    UNIQUE (order_id, source_state)
);

CREATE TABLE ordering_order_point_accrual_snapshot (
    order_id uuid PRIMARY KEY,
    source_state varchar(32) NOT NULL DEFAULT 'SNAPSHOTTED'
        CHECK (source_state = 'SNAPSHOTTED'),
    policy_version_id bigint NOT NULL,
    selected_scope_type varchar(16) NOT NULL,
    selected_scope_reference uuid NOT NULL,
    selection_source varchar(32) NOT NULL,
    accrual_rate_bps integer NOT NULL CHECK (accrual_rate_bps BETWEEN 0 AND 10000),
    rounding_mode varchar(16) NOT NULL CHECK (rounding_mode IN ('FLOOR', 'HALF_UP')),
    issuer_type varchar(16) NOT NULL CHECK (issuer_type IN ('PLATFORM', 'BRAND', 'STORE')),
    issuer_reference varchar(240) NOT NULL,
    expiry_rule varchar(48) NOT NULL CHECK (expiry_rule IN (
        'EXACT_DURATION_FROM_COMPLETION',
        'SEOUL_CALENDAR_DAYS_FROM_COMPLETION'
    )),
    validity_days integer NOT NULL CHECK (validity_days BETWEEN 1 AND 3650),
    canonical_policy_hash varchar(64) NOT NULL
        CHECK (canonical_policy_hash ~ '^[0-9a-f]{64}$'),
    order_payable_krw bigint NOT NULL CHECK (order_payable_krw >= 0),
    gross_accrual_amount_krw bigint NOT NULL CHECK (
        gross_accrual_amount_krw >= 0
        AND gross_accrual_amount_krw <= order_payable_krw
    ),
    snapshot_schema_version integer NOT NULL CHECK (snapshot_schema_version = 1),
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_order_point_accrual_snapshot_source
        FOREIGN KEY (order_id, source_state)
        REFERENCES ordering_order_point_accrual_source(order_id, source_state),
    CONSTRAINT fk_order_point_accrual_snapshot_policy
        FOREIGN KEY (policy_version_id, selected_scope_type, selected_scope_reference)
        REFERENCES operations_point_accrual_policy_version(
            policy_version_id,
            scope_type,
            scope_reference
        ),
    CONSTRAINT chk_order_point_accrual_snapshot_scope_source
        CHECK (
            (selected_scope_type = 'STORE'
                AND selected_scope_reference <> '00000000-0000-0000-0000-000000000000'::uuid
                AND selection_source = 'STORE_OVERRIDE')
            OR
            (selected_scope_type = 'GLOBAL'
                AND selected_scope_reference = '00000000-0000-0000-0000-000000000000'::uuid
                AND selection_source IN ('GLOBAL_INHERITED', 'GLOBAL_NO_OVERRIDE'))
        ),
    CONSTRAINT chk_order_point_accrual_snapshot_issuer_reference
        CHECK (
            issuer_reference = btrim(issuer_reference)
            AND length(issuer_reference) BETWEEN 1 AND 240
            AND issuer_reference !~ '[[:cntrl:]]'
        )
);

CREATE TABLE ordering_order_point_accrual_unit (
    order_id uuid NOT NULL REFERENCES ordering_order_point_accrual_snapshot(order_id),
    order_line_id uuid NOT NULL,
    line_sequence integer NOT NULL CHECK (line_sequence >= 0),
    unit_position integer NOT NULL CHECK (unit_position >= 0),
    cash_payable_krw bigint NOT NULL CHECK (cash_payable_krw >= 0),
    accrued_amount_krw bigint NOT NULL CHECK (
        accrued_amount_krw >= 0
        AND accrued_amount_krw <= cash_payable_krw
    ),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (order_id, line_sequence, unit_position),
    UNIQUE (order_id, order_line_id, unit_position),
    CONSTRAINT fk_order_point_accrual_unit_line
        FOREIGN KEY (order_id, order_line_id, line_sequence)
        REFERENCES ordering_order_line(order_id, id, line_sequence)
);

INSERT INTO ordering_order_point_accrual_source (
    order_id,
    source_state,
    created_at
)
SELECT id,
       'LEGACY_NOT_APPLICABLE',
       clock_timestamp()
  FROM ordering_order;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM ordering_order_point_accrual_source) <>
       (SELECT COUNT(*) FROM ordering_order) THEN
        RAISE EXCEPTION 'V16 legacy point accrual source cardinality mismatch';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM ordering_order_point_accrual_source
         WHERE source_state <> 'LEGACY_NOT_APPLICABLE'
    ) THEN
        RAISE EXCEPTION 'V16 must not backfill policy values for legacy Orders';
    END IF;
END;
$$;

CREATE FUNCTION reject_new_legacy_order_point_accrual_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.source_state = 'LEGACY_NOT_APPLICABLE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'legacy point accrual source cannot be created after V16';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER order_point_accrual_source_no_new_legacy
    BEFORE INSERT ON ordering_order_point_accrual_source
    FOR EACH ROW EXECUTE FUNCTION reject_new_legacy_order_point_accrual_source();

CREATE FUNCTION reject_order_point_accrual_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = TG_TABLE_NAME || ' is an immutable ordinary point accrual snapshot';
END;
$$;

CREATE TRIGGER order_point_accrual_source_immutable
    BEFORE UPDATE OR DELETE ON ordering_order_point_accrual_source
    FOR EACH ROW EXECUTE FUNCTION reject_order_point_accrual_snapshot_mutation();

CREATE TRIGGER order_point_accrual_snapshot_immutable
    BEFORE UPDATE OR DELETE ON ordering_order_point_accrual_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_order_point_accrual_snapshot_mutation();

CREATE TRIGGER order_point_accrual_unit_immutable
    BEFORE UPDATE OR DELETE ON ordering_order_point_accrual_unit
    FOR EACH ROW EXECUTE FUNCTION reject_order_point_accrual_snapshot_mutation();

CREATE FUNCTION validate_order_point_accrual_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_order_id uuid;
    source_state_value varchar(32);
    expected_unit_count numeric;
    actual_unit_count numeric;
    unit_cash_total numeric;
    unit_accrual_total numeric;
    snapshot_payable numeric;
    snapshot_gross numeric;
    order_payable numeric;
BEGIN
    IF TG_TABLE_NAME = 'ordering_order' THEN
        target_order_id := NEW.id;
    ELSE
        target_order_id := NEW.order_id;
    END IF;

    SELECT source_state
      INTO source_state_value
      FROM ordering_order_point_accrual_source
     WHERE order_id = target_order_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Order requires exactly one ordinary point accrual source';
    END IF;

    IF source_state_value = 'LEGACY_NOT_APPLICABLE' THEN
        IF EXISTS (
            SELECT 1 FROM ordering_order_point_accrual_snapshot WHERE order_id = target_order_id
        ) OR EXISTS (
            SELECT 1 FROM ordering_order_point_accrual_unit WHERE order_id = target_order_id
        ) THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'legacy point accrual source cannot contain a snapshot';
        END IF;
        RETURN NULL;
    END IF;

    SELECT snapshot.order_payable_krw,
           snapshot.gross_accrual_amount_krw,
           order_row.payable_krw
      INTO snapshot_payable, snapshot_gross, order_payable
      FROM ordering_order_point_accrual_snapshot snapshot
      JOIN ordering_order order_row ON order_row.id = snapshot.order_id
     WHERE snapshot.order_id = target_order_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'snapshotted point accrual source requires a complete header';
    END IF;

    IF snapshot_payable <> order_payable THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'point accrual snapshot payable does not match the Order';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM ordering_order_point_accrual_snapshot snapshot
          JOIN operations_point_accrual_policy_version policy
            ON policy.policy_version_id = snapshot.policy_version_id
           AND policy.scope_type = snapshot.selected_scope_type
           AND policy.scope_reference = snapshot.selected_scope_reference
         WHERE snapshot.order_id = target_order_id
           AND policy.state = 'OVERRIDE'
           AND policy.accrual_rate_bps = snapshot.accrual_rate_bps
           AND policy.rounding_mode = snapshot.rounding_mode
           AND policy.issuer_type = snapshot.issuer_type
           AND policy.issuer_reference = snapshot.issuer_reference
           AND policy.expiry_rule = snapshot.expiry_rule
           AND policy.validity_days = snapshot.validity_days
           AND policy.payload_hash = snapshot.canonical_policy_hash
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'point accrual snapshot policy values do not match the immutable version';
    END IF;

    SELECT COALESCE(SUM(quantity), 0)
      INTO expected_unit_count
      FROM ordering_order_line
     WHERE order_id = target_order_id;

    SELECT COUNT(*),
           COALESCE(SUM(cash_payable_krw), 0),
           COALESCE(SUM(accrued_amount_krw), 0)
      INTO actual_unit_count, unit_cash_total, unit_accrual_total
      FROM ordering_order_point_accrual_unit
     WHERE order_id = target_order_id;

    IF expected_unit_count <= 0
        OR actual_unit_count <> expected_unit_count
        OR unit_cash_total <> snapshot_payable
        OR unit_accrual_total <> snapshot_gross THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'point accrual snapshot unit allocation does not tie out';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM ordering_order_line line
          LEFT JOIN ordering_order_point_accrual_unit unit
            ON unit.order_id = line.order_id
           AND unit.order_line_id = line.id
           AND unit.line_sequence = line.line_sequence
         WHERE line.order_id = target_order_id
         GROUP BY line.id, line.quantity, line.cash_payable_krw
        HAVING COUNT(unit.unit_position) <> line.quantity
            OR MIN(unit.unit_position) <> 0
            OR MAX(unit.unit_position) <> line.quantity - 1
            OR COUNT(DISTINCT unit.unit_position) <> line.quantity
            OR COALESCE(SUM(unit.cash_payable_krw), 0) <> line.cash_payable_krw
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'point accrual snapshot conceptual units do not match OrderLine pricing';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ordering_order_requires_point_accrual_source
    AFTER INSERT ON ordering_order
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_order_point_accrual_snapshot();

CREATE CONSTRAINT TRIGGER ordering_point_accrual_source_complete
    AFTER INSERT ON ordering_order_point_accrual_source
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_order_point_accrual_snapshot();

CREATE CONSTRAINT TRIGGER ordering_point_accrual_snapshot_complete
    AFTER INSERT ON ordering_order_point_accrual_snapshot
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_order_point_accrual_snapshot();

CREATE CONSTRAINT TRIGGER ordering_point_accrual_unit_complete
    AFTER INSERT ON ordering_order_point_accrual_unit
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_order_point_accrual_snapshot();
