-- Plan 10's issuer provenance is a fail-closed prerequisite for audited adjustments.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM loyalty_point_lot
         WHERE issuer_type NOT IN ('PLATFORM', 'BRAND', 'STORE')
            OR issuer_reference IS NULL
            OR length(btrim(issuer_reference)) = 0
    ) THEN
        RAISE EXCEPTION
            'V31 point adjustment activation failed: PointLot issuer provenance is unresolved';
    END IF;
END
$$;

ALTER TABLE loyalty_point_transaction
    ADD COLUMN balance_effect varchar(16);

UPDATE loyalty_point_transaction
   SET balance_effect = CASE
       WHEN type IN ('ACCRUAL', 'RESTORE', 'COMPENSATION') THEN 'CREDIT'
       WHEN type IN ('USE', 'EXPIRATION', 'RECOVERY') THEN 'DEBIT'
       WHEN type = 'RESTORE_SKIPPED_EXPIRED' THEN 'NONE'
       ELSE NULL
   END;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM loyalty_point_transaction
         WHERE balance_effect IS NULL
    ) THEN
        RAISE EXCEPTION
            'V31 point adjustment activation failed: PointTransaction balance effect cannot be mapped';
    END IF;
END
$$;

ALTER TABLE loyalty_point_transaction
    ALTER COLUMN balance_effect SET NOT NULL,
    DROP CONSTRAINT chk_point_transaction_type,
    ADD CONSTRAINT chk_point_transaction_type CHECK (type IN (
        'ACCRUAL', 'USE', 'EXPIRATION', 'RESTORE', 'COMPENSATION',
        'RESTORE_SKIPPED_EXPIRED', 'RECOVERY', 'ADJUSTMENT'
    )),
    ADD CONSTRAINT chk_point_transaction_balance_effect CHECK (
        (type IN ('ACCRUAL', 'RESTORE', 'COMPENSATION') AND balance_effect = 'CREDIT')
        OR (type IN ('USE', 'EXPIRATION', 'RECOVERY') AND balance_effect = 'DEBIT')
        OR (type = 'RESTORE_SKIPPED_EXPIRED' AND balance_effect = 'NONE')
        OR (type = 'ADJUSTMENT' AND balance_effect IN ('CREDIT', 'DEBIT'))
    ),
    ADD CONSTRAINT chk_point_transaction_adjustment_source CHECK (
        type <> 'ADJUSTMENT'
        OR source_reference ~ '^point-adjustment:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:lot:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    );

CREATE TABLE loyalty_point_adjustment_command_idempotency (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    point_account_id uuid NOT NULL REFERENCES loyalty_point_account(id),
    operation varchar(32) NOT NULL CHECK (operation = 'POINT_ADJUSTMENT'),
    idempotency_key varchar(128) NOT NULL,
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    response_status smallint NOT NULL CHECK (response_status = 201),
    response_body text NOT NULL CHECK (length(response_body) > 0),
    response_version integer NOT NULL CHECK (response_version > 0),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    CONSTRAINT uq_point_adjustment_idempotency_scope
        UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT chk_point_adjustment_idempotency_key CHECK (
        length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key = btrim(idempotency_key)
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    CONSTRAINT chk_point_adjustment_retention CHECK (
        retention_expires_at = created_at + interval '90 days'
    )
);

CREATE INDEX idx_point_adjustment_idempotency_retention
    ON loyalty_point_adjustment_command_idempotency (retention_expires_at, id);

CREATE FUNCTION reject_point_adjustment_idempotency_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'Point adjustment terminal idempotency row is immutable';
END;
$$;

CREATE TRIGGER point_adjustment_idempotency_immutable
    BEFORE UPDATE ON loyalty_point_adjustment_command_idempotency
    FOR EACH ROW EXECUTE FUNCTION reject_point_adjustment_idempotency_change();
