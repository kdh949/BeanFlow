ALTER TABLE ordering_order
    ADD COLUMN checkout_mode varchar(32) NOT NULL DEFAULT 'LEGACY_RESERVED',
    ADD COLUMN ordering_window_closes_at timestamptz,
    ADD COLUMN checkout_input_snapshot jsonb,
    ADD COLUMN checkout_input_schema_version integer,
    ADD COLUMN preparation_minutes integer,
    ADD COLUMN estimated_ready_at timestamptz;

ALTER TABLE ordering_order
    ALTER COLUMN pickup_slot_id DROP NOT NULL,
    ALTER COLUMN pickup_window_start_snapshot DROP NOT NULL,
    ALTER COLUMN pickup_window_end_snapshot DROP NOT NULL,
    DROP CONSTRAINT chk_order_state_lifecycle,
    DROP CONSTRAINT chk_order_lifecycle_chronology,
    DROP CONSTRAINT ck_ordering_order_display_snapshot_shape;

ALTER TABLE ordering_order
    ADD CONSTRAINT ck_ordering_order_checkout_mode
        CHECK (checkout_mode IN ('LEGACY_RESERVED', 'IMMEDIATE')),
    ADD CONSTRAINT ck_ordering_order_checkout_shape
        CHECK (
            (checkout_mode = 'LEGACY_RESERVED'
                AND pickup_slot_id IS NOT NULL
                AND pickup_window_start_snapshot IS NOT NULL
                AND pickup_window_end_snapshot IS NOT NULL
                AND pickup_window_end_snapshot > pickup_window_start_snapshot
                AND ordering_window_closes_at IS NULL
                AND checkout_input_snapshot IS NULL
                AND checkout_input_schema_version IS NULL)
            OR
            (checkout_mode = 'IMMEDIATE'
                AND pickup_slot_id IS NULL
                AND pickup_window_start_snapshot IS NULL
                AND pickup_window_end_snapshot IS NULL
                AND reservation_expires_at IS NULL
                AND ordering_window_closes_at IS NOT NULL
                AND ordering_window_closes_at > created_at
                AND jsonb_typeof(checkout_input_snapshot) = 'object'
                AND checkout_input_schema_version = 1)
        ),
    ADD CONSTRAINT ck_ordering_order_display_snapshot_shape
        CHECK (
            store_name_snapshot = btrim(store_name_snapshot)
            AND length(store_name_snapshot) BETWEEN 1 AND 200
            AND (
                (pickup_window_start_snapshot IS NULL AND pickup_window_end_snapshot IS NULL)
                OR
                (pickup_window_start_snapshot IS NOT NULL
                    AND pickup_window_end_snapshot IS NOT NULL
                    AND pickup_window_end_snapshot > pickup_window_start_snapshot)
            )
        ),
    ADD CONSTRAINT chk_order_state_lifecycle
        CHECK (
            (state = 'PENDING_PAYMENT'
                AND payable_krw > 0
                AND paid_at IS NULL
                AND (
                    (checkout_mode = 'LEGACY_RESERVED' AND reservation_expires_at IS NOT NULL)
                    OR
                    (checkout_mode = 'IMMEDIATE' AND reservation_expires_at IS NULL)
                ))
            OR
            (state = 'PAID'
                AND reservation_expires_at IS NULL
                AND paid_at IS NOT NULL
                AND accepted_at IS NULL
                AND rejected_at IS NULL
                AND (
                    (checkout_mode = 'LEGACY_RESERVED'
                        AND acceptance_warning_at = paid_at + interval '2 minutes'
                        AND acceptance_deadline_at = paid_at + interval '3 minutes')
                    OR
                    (checkout_mode = 'IMMEDIATE'
                        AND acceptance_deadline_at > paid_at
                        AND acceptance_deadline_at <= paid_at + interval '3 minutes'
                        AND acceptance_deadline_at <= ordering_window_closes_at
                        AND (
                            (paid_at + interval '2 minutes' < acceptance_deadline_at
                                AND acceptance_warning_at = paid_at + interval '2 minutes')
                            OR
                            (paid_at + interval '2 minutes' >= acceptance_deadline_at
                                AND acceptance_warning_at IS NULL)
                        ))
                ))
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
                AND checkout_mode = 'LEGACY_RESERVED'
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
        ),
    ADD CONSTRAINT ck_ordering_order_preparation
        CHECK (
            (preparation_minutes IS NULL AND estimated_ready_at IS NULL)
            OR
            (preparation_minutes BETWEEN 1 AND 120
                AND accepted_at IS NOT NULL
                AND estimated_ready_at = accepted_at + make_interval(mins => preparation_minutes))
        ),
    ADD CONSTRAINT ck_ordering_order_immediate_preparation
        CHECK (
            checkout_mode <> 'IMMEDIATE'
            OR accepted_at IS NULL
            OR (preparation_minutes IS NOT NULL AND estimated_ready_at IS NOT NULL)
        );

CREATE INDEX ix_ordering_order_immediate_pending_cutoff
    ON ordering_order (ordering_window_closes_at, id)
    WHERE checkout_mode = 'IMMEDIATE' AND state = 'PENDING_PAYMENT';

CREATE INDEX ix_ordering_order_store_immediate_cutoff
    ON ordering_order (store_id, ordering_window_closes_at, id)
    WHERE checkout_mode = 'IMMEDIATE' AND state IN ('PENDING_PAYMENT', 'PAID');

CREATE OR REPLACE FUNCTION protect_ordering_order_checkout_contract()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.checkout_mode IS DISTINCT FROM OLD.checkout_mode
        OR NEW.checkout_input_snapshot IS DISTINCT FROM OLD.checkout_input_snapshot
        OR NEW.checkout_input_schema_version IS DISTINCT FROM OLD.checkout_input_schema_version
    THEN
        RAISE EXCEPTION 'ordering_order_checkout_contract_immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.ordering_window_closes_at IS NOT NULL
        AND (NEW.ordering_window_closes_at IS NULL
            OR NEW.ordering_window_closes_at > OLD.ordering_window_closes_at)
    THEN
        RAISE EXCEPTION 'ordering_order_cutoff_must_not_increase'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ordering_order_checkout_contract_immutable
BEFORE UPDATE ON ordering_order
FOR EACH ROW
EXECUTE FUNCTION protect_ordering_order_checkout_contract();
