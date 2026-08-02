DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM promotion_campaign WHERE active) THEN
        RAISE EXCEPTION
            'Coupon burden activation failed: active legacy campaigns require explicit cost burden configuration';
    END IF;
    IF EXISTS (SELECT 1 FROM promotion_coupon_reservation) THEN
        RAISE EXCEPTION
            'Coupon burden activation failed: legacy reservations cannot be backfilled from current campaign terms';
    END IF;
END;
$$;

ALTER TABLE promotion_campaign
    ADD COLUMN cost_bearer varchar(16),
    ADD COLUMN platform_share_bps integer,
    ADD COLUMN store_share_bps integer,
    ADD CONSTRAINT promotion_campaign_cost_burden_check CHECK (
        (
            NOT active
            AND cost_bearer IS NULL
            AND platform_share_bps IS NULL
            AND store_share_bps IS NULL
        )
        OR
        (
            cost_bearer IS NOT NULL
            AND platform_share_bps IS NOT NULL
            AND store_share_bps IS NOT NULL
            AND (
                (
                    cost_bearer = 'PLATFORM'
                    AND platform_share_bps = 10000
                    AND store_share_bps = 0
                )
                OR
                (
                    cost_bearer = 'STORE'
                    AND platform_share_bps = 0
                    AND store_share_bps = 10000
                )
                OR
                (
                    cost_bearer = 'SHARED'
                    AND platform_share_bps BETWEEN 1 AND 9999
                    AND store_share_bps BETWEEN 1 AND 9999
                    AND platform_share_bps + store_share_bps = 10000
                )
            )
        )
    );

ALTER TABLE promotion_coupon_reservation
    ADD COLUMN campaign_id uuid NOT NULL REFERENCES promotion_campaign(id),
    ADD COLUMN campaign_version bigint NOT NULL CHECK (campaign_version >= 0),
    ADD COLUMN cost_bearer varchar(16) NOT NULL,
    ADD COLUMN platform_share_bps integer NOT NULL,
    ADD COLUMN store_share_bps integer NOT NULL,
    ADD COLUMN platform_coupon_cost_krw bigint NOT NULL CHECK (platform_coupon_cost_krw >= 0),
    ADD COLUMN store_coupon_cost_krw bigint NOT NULL CHECK (store_coupon_cost_krw >= 0),
    ADD CONSTRAINT promotion_coupon_reservation_cost_burden_check CHECK (
        (
            cost_bearer = 'PLATFORM'
            AND platform_share_bps = 10000
            AND store_share_bps = 0
        )
        OR
        (
            cost_bearer = 'STORE'
            AND platform_share_bps = 0
            AND store_share_bps = 10000
        )
        OR
        (
            cost_bearer = 'SHARED'
            AND platform_share_bps BETWEEN 1 AND 9999
            AND store_share_bps BETWEEN 1 AND 9999
            AND platform_share_bps + store_share_bps = 10000
        )
    ),
    ADD CONSTRAINT promotion_coupon_reservation_cost_tie_out_check CHECK (
        platform_coupon_cost_krw + store_coupon_cost_krw = discount_krw
        AND store_coupon_cost_krw =
            floor((discount_krw::numeric * store_share_bps::numeric) / 10000)::bigint
    );

CREATE INDEX idx_coupon_reservation_campaign_source
    ON promotion_coupon_reservation (campaign_id, campaign_version);

CREATE FUNCTION reject_coupon_reservation_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.campaign_id IS DISTINCT FROM OLD.campaign_id
        OR NEW.campaign_version IS DISTINCT FROM OLD.campaign_version
        OR NEW.discount_krw IS DISTINCT FROM OLD.discount_krw
        OR NEW.eligible_line_sequences IS DISTINCT FROM OLD.eligible_line_sequences
        OR NEW.discount_type IS DISTINCT FROM OLD.discount_type
        OR NEW.fixed_amount_krw IS DISTINCT FROM OLD.fixed_amount_krw
        OR NEW.rate_bps IS DISTINCT FROM OLD.rate_bps
        OR NEW.minimum_eligible_subtotal_krw IS DISTINCT FROM OLD.minimum_eligible_subtotal_krw
        OR NEW.maximum_discount_krw IS DISTINCT FROM OLD.maximum_discount_krw
        OR NEW.cost_bearer IS DISTINCT FROM OLD.cost_bearer
        OR NEW.platform_share_bps IS DISTINCT FROM OLD.platform_share_bps
        OR NEW.store_share_bps IS DISTINCT FROM OLD.store_share_bps
        OR NEW.platform_coupon_cost_krw IS DISTINCT FROM OLD.platform_coupon_cost_krw
        OR NEW.store_coupon_cost_krw IS DISTINCT FROM OLD.store_coupon_cost_krw
    THEN
        RAISE EXCEPTION 'Coupon reservation settlement snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_coupon_reservation_snapshot_immutable
BEFORE UPDATE ON promotion_coupon_reservation
FOR EACH ROW
EXECUTE FUNCTION reject_coupon_reservation_snapshot_mutation();
