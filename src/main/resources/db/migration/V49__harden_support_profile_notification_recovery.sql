ALTER TABLE support_profile_change_notification
    ADD COLUMN source_occurred_at timestamptz,
    ADD COLUMN source_correlation_id varchar(128),
    ADD COLUMN claim_id uuid,
    ADD COLUMN claim_expires_at timestamptz;

UPDATE support_profile_change_notification
   SET source_occurred_at = created_at,
       source_correlation_id = 'support-profile-change:' || id;

DO $$
DECLARE
    state_constraint text;
BEGIN
    SELECT constraint_row.conname
      INTO state_constraint
      FROM pg_constraint constraint_row
     WHERE constraint_row.conrelid = 'support_profile_change_notification'::regclass
       AND constraint_row.contype = 'c'
       AND constraint_row.conname <> 'chk_support_profile_change_notification_result'
       AND pg_get_constraintdef(constraint_row.oid) LIKE '%state%'
       AND pg_get_constraintdef(constraint_row.oid) LIKE '%PENDING%'
       AND pg_get_constraintdef(constraint_row.oid) LIKE '%ACCEPTED%'
     LIMIT 1;
    IF state_constraint IS NULL THEN
        RAISE EXCEPTION 'support profile notification state constraint is missing';
    END IF;
    EXECUTE format(
        'ALTER TABLE support_profile_change_notification DROP CONSTRAINT %I',
        state_constraint
    );
END
$$;

ALTER TABLE support_profile_change_notification
    ALTER COLUMN source_occurred_at SET NOT NULL,
    ALTER COLUMN source_correlation_id SET NOT NULL,
    DROP CONSTRAINT chk_support_profile_change_notification_result,
    ADD CONSTRAINT support_profile_change_notification_state_check
        CHECK (state IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'RETRY_SCHEDULED', 'MANUAL_REVIEW')),
    ADD CONSTRAINT chk_support_profile_change_notification_result CHECK (
        (state = 'ACCEPTED' AND delivery_id IS NOT NULL AND failure_code IS NULL
            AND claim_id IS NULL AND claim_expires_at IS NULL)
        OR (state = 'PENDING' AND delivery_id IS NULL AND failure_code IS NULL
            AND claim_id IS NULL AND claim_expires_at IS NULL)
        OR (state = 'PROCESSING' AND delivery_id IS NULL AND failure_code IS NULL
            AND claim_id IS NOT NULL AND claim_expires_at IS NOT NULL)
        OR (state IN ('RETRY_SCHEDULED', 'MANUAL_REVIEW') AND delivery_id IS NULL AND failure_code IS NOT NULL
            AND claim_id IS NULL AND claim_expires_at IS NULL)
    ),
    ADD CONSTRAINT chk_support_profile_change_notification_source_time
        CHECK (source_occurred_at >= created_at AND source_occurred_at <= updated_at),
    ADD CONSTRAINT chk_support_profile_change_notification_source_correlation
        CHECK (
            source_correlation_id = btrim(source_correlation_id)
            AND length(source_correlation_id) BETWEEN 1 AND 128
            AND source_correlation_id !~ '[[:cntrl:]]'
        );

CREATE INDEX idx_support_profile_change_notification_recovery
    ON support_profile_change_notification (claim_expires_at, created_at, id)
    WHERE state IN ('PENDING', 'PROCESSING');
