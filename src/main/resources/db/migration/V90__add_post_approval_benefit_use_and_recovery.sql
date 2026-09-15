ALTER TABLE ordering_order
    ADD COLUMN payment_commitment_failure_code varchar(80);

ALTER TABLE ordering_support_order_change_history
    ALTER COLUMN previous_pickup_slot_id DROP NOT NULL,
    ALTER COLUMN current_pickup_slot_id DROP NOT NULL;

ALTER TABLE support_order_change_execution
    ALTER COLUMN previous_pickup_slot_id DROP NOT NULL,
    ALTER COLUMN current_pickup_slot_id DROP NOT NULL;

CREATE INDEX idx_order_store_state_immediate_board_sort
    ON ordering_order (store_id, state, (coalesce(pickup_window_start_snapshot, estimated_ready_at)), id);

ALTER TABLE promotion_coupon_reservation
    ALTER COLUMN reservation_expires_at DROP NOT NULL,
    ADD CONSTRAINT ck_coupon_reservation_lease_shape
        CHECK (reservation_expires_at IS NOT NULL OR state = 'USED');

ALTER TABLE loyalty_point_reservation
    ALTER COLUMN reservation_expires_at DROP NOT NULL,
    ADD CONSTRAINT ck_point_reservation_lease_shape
        CHECK (reservation_expires_at IS NOT NULL OR state = 'USED');

CREATE OR REPLACE FUNCTION validate_order_settlement_input_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_order_id uuid;
    snapshot_row ordering_order_settlement_input_snapshot%ROWTYPE;
    order_row ordering_order%ROWTYPE;
    allocation_count numeric;
    allocation_total numeric;
    store_allocation_total numeric;
BEGIN
    IF TG_TABLE_NAME = 'ordering_order' THEN
        target_order_id := NEW.id;
    ELSE
        target_order_id := NEW.order_id;
    END IF;

    SELECT * INTO order_row
      FROM ordering_order
     WHERE id = target_order_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Settlement input snapshot has no Order';
    END IF;

    SELECT * INTO snapshot_row
      FROM ordering_order_settlement_input_snapshot
     WHERE order_id = target_order_id;
    IF NOT FOUND THEN
        IF order_row.checkout_mode = 'IMMEDIATE'
            AND order_row.paid_at IS NULL
            AND order_row.state IN ('PENDING_PAYMENT', 'EXPIRED', 'CANCELLED')
        THEN
            RETURN NULL;
        END IF;
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Order requires exactly one settlement input snapshot';
    END IF;

    IF snapshot_row.store_id <> order_row.store_id
        OR snapshot_row.gross_paid_krw <> order_row.subtotal_krw
        OR snapshot_row.coupon_discount_krw <> order_row.coupon_discount_krw
        OR snapshot_row.points_applied_krw <> order_row.points_applied_krw
        OR snapshot_row.fee_base_krw <> order_row.payable_krw
        OR snapshot_row.currency <> order_row.currency
        OR snapshot_row.created_at <> order_row.created_at
    THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Settlement input snapshot does not match immutable Order pricing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM merchant_store_settlement_terms terms
         WHERE terms.terms_version_id = snapshot_row.store_settlement_terms_version_id
           AND terms.store_id = snapshot_row.store_id
           AND terms.source_reference = snapshot_row.store_settlement_terms_source_reference
           AND terms.fee_rate_bps = snapshot_row.fee_rate_bps
           AND terms.effective_from <= snapshot_row.created_at
           AND (terms.effective_to IS NULL OR terms.effective_to > snapshot_row.created_at)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Settlement input snapshot store terms source does not match';
    END IF;

    IF snapshot_row.coupon_discount_krw > 0 AND NOT EXISTS (
        SELECT 1
          FROM promotion_coupon_reservation reservation
         WHERE reservation.id = snapshot_row.coupon_reservation_id
           AND reservation.order_id = target_order_id
           AND reservation.campaign_id = snapshot_row.coupon_campaign_id
           AND reservation.campaign_version = snapshot_row.coupon_campaign_version
           AND reservation.cost_bearer = snapshot_row.coupon_cost_bearer
           AND reservation.platform_share_bps = snapshot_row.coupon_platform_share_bps
           AND reservation.store_share_bps = snapshot_row.coupon_store_share_bps
           AND reservation.discount_krw = snapshot_row.coupon_discount_krw
           AND reservation.platform_coupon_cost_krw = snapshot_row.platform_coupon_cost_krw
           AND reservation.store_coupon_cost_krw = snapshot_row.coupon_cost_krw
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Settlement input snapshot coupon source does not match';
    END IF;

    IF snapshot_row.points_applied_krw > 0 THEN
        IF NOT EXISTS (
            SELECT 1
              FROM loyalty_point_reservation reservation
             WHERE reservation.id = snapshot_row.point_reservation_id
               AND reservation.order_id = target_order_id
               AND reservation.amount_krw = snapshot_row.points_applied_krw
        ) THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'Settlement input snapshot point reservation source does not match';
        END IF;

        SELECT COUNT(*),
               COALESCE(SUM(allocation.amount_krw), 0),
               COALESCE(SUM(
                   CASE WHEN lot.issuer_type = 'STORE' THEN allocation.amount_krw ELSE 0 END
               ), 0)
          INTO allocation_count, allocation_total, store_allocation_total
          FROM loyalty_point_reservation_allocation allocation
          JOIN loyalty_point_lot lot ON lot.id = allocation.point_lot_id
         WHERE allocation.point_reservation_id = snapshot_row.point_reservation_id;

        IF allocation_count <= 0
            OR allocation_total <> snapshot_row.points_applied_krw
            OR store_allocation_total <> snapshot_row.point_cost_krw
            OR EXISTS (
                SELECT 1
                  FROM loyalty_point_reservation_allocation allocation
                  JOIN loyalty_point_lot lot ON lot.id = allocation.point_lot_id
                 WHERE allocation.point_reservation_id = snapshot_row.point_reservation_id
                   AND lot.issuer_type = 'STORE'
                   AND lot.issuer_reference <> snapshot_row.store_id::text
            )
        THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'Settlement input snapshot point allocation source does not tie out';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

ALTER TABLE ordering_order
    DROP CONSTRAINT chk_order_state_lifecycle,
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
                AND paid_at IS NULL
                AND (
                    (checkout_mode = 'LEGACY_RESERVED' AND reservation_expires_at IS NOT NULL)
                    OR
                    (checkout_mode = 'IMMEDIATE' AND reservation_expires_at IS NULL)
                ))
            OR
            (state = 'CANCELLED'
                AND reservation_expires_at IS NULL)
        );

ALTER TABLE ordering_order
    DROP CONSTRAINT chk_order_cancellation_cause,
    DROP CONSTRAINT chk_order_cancellation_reason_fields,
    ADD CONSTRAINT chk_order_cancellation_cause CHECK (
        cancellation_cause IS NULL
        OR cancellation_cause IN (
            'CUSTOMER_REQUEST', 'PAYMENT_DECLINED', 'PAYMENT_COMMITMENT_FAILED', 'SUPPORT_REQUEST'
        )
    ),
    ADD CONSTRAINT chk_order_cancellation_reason_fields CHECK (
        (state = 'CANCELLED'
            AND cancellation_cause IN ('CUSTOMER_REQUEST', 'SUPPORT_REQUEST')
            AND cancellation_reason_code IS NOT NULL
            AND payment_commitment_failure_code IS NULL
            AND (cancellation_detail IS NULL OR (
                cancellation_detail = btrim(cancellation_detail)
                AND length(cancellation_detail) BETWEEN 1 AND 200
                AND cancellation_detail !~ '[[:cntrl:]]'
            )))
        OR
        (state = 'CANCELLED'
            AND cancellation_cause = 'PAYMENT_DECLINED'
            AND cancellation_reason_code IS NULL
            AND cancellation_detail IS NULL
            AND payment_commitment_failure_code IS NULL)
        OR
        (state = 'CANCELLED'
            AND cancellation_cause = 'PAYMENT_COMMITMENT_FAILED'
            AND cancellation_reason_code IS NULL
            AND cancellation_detail IS NULL
            AND payment_commitment_failure_code = btrim(payment_commitment_failure_code)
            AND length(payment_commitment_failure_code) BETWEEN 1 AND 80
            AND payment_commitment_failure_code !~ '[[:cntrl:]]')
        OR
        (state <> 'CANCELLED'
            AND cancellation_reason_code IS NULL
            AND cancellation_detail IS NULL
            AND payment_commitment_failure_code IS NULL)
    );

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('ORDER_CHECKOUT_INPUT_CAPTURED', 'ORDER_AND_FULFILLMENT'),
    ('ORDER_EXPIRED_AT_STORE_CLOSE', 'ORDER_AND_FULFILLMENT'),
    ('COUPON_USED_AT_APPROVAL', 'FINANCIAL_TRANSACTION'),
    ('POINTS_USED_AT_APPROVAL', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_COMMITMENT_RECOVERY_REQUESTED', 'FINANCIAL_TRANSACTION');
