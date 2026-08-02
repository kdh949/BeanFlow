DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ordering_order) THEN
        RAISE EXCEPTION
            'Settlement input activation failed: existing Orders require verified immutable source evidence';
    END IF;
END;
$$;

CREATE TABLE ordering_order_settlement_input_snapshot (
    order_id uuid PRIMARY KEY REFERENCES ordering_order(id),
    store_id uuid NOT NULL,
    store_settlement_terms_version_id uuid NOT NULL,
    store_settlement_terms_source_reference varchar(240) NOT NULL
        CHECK (length(trim(store_settlement_terms_source_reference)) > 0),
    coupon_reservation_id uuid,
    coupon_campaign_id uuid,
    coupon_campaign_version bigint,
    coupon_cost_bearer varchar(16),
    coupon_platform_share_bps integer,
    coupon_store_share_bps integer,
    coupon_discount_krw bigint NOT NULL CHECK (coupon_discount_krw >= 0),
    platform_coupon_cost_krw bigint NOT NULL CHECK (platform_coupon_cost_krw >= 0),
    coupon_cost_krw bigint NOT NULL CHECK (coupon_cost_krw >= 0),
    point_reservation_id uuid,
    point_allocation_hash varchar(64),
    points_applied_krw bigint NOT NULL CHECK (points_applied_krw >= 0),
    point_cost_krw bigint NOT NULL CHECK (point_cost_krw >= 0),
    gross_paid_krw bigint NOT NULL CHECK (gross_paid_krw >= 0),
    fee_base_krw bigint NOT NULL CHECK (fee_base_krw >= 0),
    fee_rate_bps integer NOT NULL CHECK (fee_rate_bps BETWEEN 0 AND 10000),
    fee_krw bigint NOT NULL CHECK (fee_krw >= 0),
    benefit_cost_krw bigint NOT NULL CHECK (benefit_cost_krw >= 0),
    net_settlement_krw bigint NOT NULL CHECK (net_settlement_krw >= 0),
    currency varchar(3) NOT NULL CHECK (currency = 'KRW'),
    snapshot_schema_version integer NOT NULL CHECK (snapshot_schema_version = 1),
    canonical_snapshot_hash varchar(64) NOT NULL
        CHECK (canonical_snapshot_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    CONSTRAINT ordering_settlement_coupon_source_check CHECK (
        (
            coupon_discount_krw = 0
            AND coupon_reservation_id IS NULL
            AND coupon_campaign_id IS NULL
            AND coupon_campaign_version IS NULL
            AND coupon_cost_bearer IS NULL
            AND coupon_platform_share_bps IS NULL
            AND coupon_store_share_bps IS NULL
            AND platform_coupon_cost_krw = 0
            AND coupon_cost_krw = 0
        )
        OR
        (
            coupon_discount_krw > 0
            AND coupon_reservation_id IS NOT NULL
            AND coupon_campaign_id IS NOT NULL
            AND coupon_campaign_version >= 0
            AND coupon_cost_bearer IS NOT NULL
            AND coupon_platform_share_bps IS NOT NULL
            AND coupon_store_share_bps IS NOT NULL
            AND (
                (
                    coupon_cost_bearer = 'PLATFORM'
                    AND coupon_platform_share_bps = 10000
                    AND coupon_store_share_bps = 0
                )
                OR
                (
                    coupon_cost_bearer = 'STORE'
                    AND coupon_platform_share_bps = 0
                    AND coupon_store_share_bps = 10000
                )
                OR
                (
                    coupon_cost_bearer = 'SHARED'
                    AND coupon_platform_share_bps BETWEEN 1 AND 9999
                    AND coupon_store_share_bps BETWEEN 1 AND 9999
                    AND coupon_platform_share_bps + coupon_store_share_bps = 10000
                )
            )
            AND platform_coupon_cost_krw + coupon_cost_krw = coupon_discount_krw
            AND coupon_cost_krw =
                floor((coupon_discount_krw::numeric * coupon_store_share_bps::numeric) / 10000)::bigint
        )
    ),
    CONSTRAINT ordering_settlement_point_source_check CHECK (
        (
            points_applied_krw = 0
            AND point_reservation_id IS NULL
            AND point_allocation_hash IS NULL
            AND point_cost_krw = 0
        )
        OR
        (
            points_applied_krw > 0
            AND point_reservation_id IS NOT NULL
            AND point_allocation_hash ~ '^[0-9a-f]{64}$'
            AND point_cost_krw <= points_applied_krw
        )
    ),
    CONSTRAINT ordering_settlement_amount_tie_out_check CHECK (
        fee_base_krw = gross_paid_krw - coupon_discount_krw - points_applied_krw
        AND fee_krw = floor((fee_base_krw::numeric * fee_rate_bps::numeric) / 10000)::bigint
        AND benefit_cost_krw = coupon_cost_krw + point_cost_krw
        AND net_settlement_krw = gross_paid_krw - fee_krw - benefit_cost_krw
    )
);

CREATE INDEX idx_order_settlement_terms_source
    ON ordering_order_settlement_input_snapshot (store_settlement_terms_version_id);

CREATE INDEX idx_order_settlement_coupon_source
    ON ordering_order_settlement_input_snapshot (coupon_reservation_id)
    WHERE coupon_reservation_id IS NOT NULL;

CREATE INDEX idx_order_settlement_point_source
    ON ordering_order_settlement_input_snapshot (point_reservation_id)
    WHERE point_reservation_id IS NOT NULL;

CREATE FUNCTION reject_order_settlement_input_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'Order settlement input snapshot is immutable';
END;
$$;

CREATE TRIGGER ordering_settlement_input_snapshot_immutable
    BEFORE UPDATE OR DELETE ON ordering_order_settlement_input_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_order_settlement_input_snapshot_mutation();

CREATE FUNCTION validate_order_settlement_input_snapshot()
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

    SELECT * INTO snapshot_row
      FROM ordering_order_settlement_input_snapshot
     WHERE order_id = target_order_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Order requires exactly one settlement input snapshot';
    END IF;

    SELECT * INTO order_row
      FROM ordering_order
     WHERE id = target_order_id;
    IF NOT FOUND
        OR snapshot_row.store_id <> order_row.store_id
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

CREATE CONSTRAINT TRIGGER ordering_order_requires_settlement_input_snapshot
    AFTER INSERT ON ordering_order
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_order_settlement_input_snapshot();

CREATE CONSTRAINT TRIGGER ordering_settlement_input_snapshot_complete
    AFTER INSERT ON ordering_order_settlement_input_snapshot
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_order_settlement_input_snapshot();
