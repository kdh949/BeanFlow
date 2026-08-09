ALTER TABLE ordering_order_line
    ADD COLUMN option_selection_snapshot_state varchar(32),
    ADD COLUMN normalized_option_ids_json jsonb;

UPDATE ordering_order_line
   SET option_selection_snapshot_state = 'LEGACY_UNAVAILABLE',
       normalized_option_ids_json = NULL;

ALTER TABLE ordering_order_line
    ALTER COLUMN option_selection_snapshot_state SET NOT NULL,
    ADD CONSTRAINT ck_ordering_order_line_option_selection_snapshot
        CHECK (
            (option_selection_snapshot_state = 'LEGACY_UNAVAILABLE'
                AND normalized_option_ids_json IS NULL)
            OR
            (option_selection_snapshot_state = 'SNAPSHOTTED'
                AND normalized_option_ids_json IS NOT NULL
                AND jsonb_typeof(normalized_option_ids_json) = 'array')
        );

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM ordering_idempotency_record
         WHERE status IN ('COMPLETED', 'FAILED')
           AND completed_at IS NULL
    ) THEN
        RAISE EXCEPTION 'ordering idempotency terminal row without completed_at';
    END IF;
END
$$;

ALTER TABLE ordering_idempotency_record
    ADD COLUMN retention_expires_at timestamptz;

UPDATE ordering_idempotency_record
   SET retention_expires_at = completed_at + interval '90 days'
 WHERE status IN ('COMPLETED', 'FAILED');

ALTER TABLE ordering_idempotency_record
    ADD CONSTRAINT ck_ordering_idempotency_terminal_retention
        CHECK (
            (status IN ('COMPLETED', 'FAILED')
                AND completed_at IS NOT NULL
                AND retention_expires_at = completed_at + interval '90 days')
            OR
            (status IN ('PROCESSING', 'MANUAL_REVIEW')
                AND retention_expires_at IS NULL)
        );

CREATE INDEX idx_ordering_idempotency_terminal_retention
    ON ordering_idempotency_record (retention_expires_at, id)
    WHERE retention_expires_at IS NOT NULL;
