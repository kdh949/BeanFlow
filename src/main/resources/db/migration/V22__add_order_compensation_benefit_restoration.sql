DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM promotion_coupon_reservation)
        OR EXISTS (
            SELECT 1
              FROM promotion_coupon_issuance
             WHERE original_issuance_id IS NOT NULL
                OR restoration_source_reference IS NOT NULL
        ) THEN
        RAISE EXCEPTION
            'V22 benefit restoration clean-cutover precheck failed: legacy coupon restoration candidates exist';
    END IF;
END
$$;

ALTER TABLE operations_operator_permission_grant
    DROP CONSTRAINT chk_operator_permission_vocabulary,
    ADD CONSTRAINT chk_operator_permission_vocabulary
        CHECK (permission IN (
            'EXPIRED_BENEFIT_POLICY_READ',
            'EXPIRED_BENEFIT_POLICY_WRITE',
            'POINT_ACCOUNT_READ',
            'POINT_ADJUSTMENT',
            'POINT_ACCRUAL_POLICY_READ',
            'POINT_ACCRUAL_POLICY_WRITE',
            'ORDER_COMPENSATION_READ'
        ));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM loyalty_point_reservation
         WHERE state = 'RESTORED'
            OR restoration_source_reference IS NOT NULL
    )
        OR EXISTS (
            SELECT 1
              FROM loyalty_point_transaction
             WHERE type IN ('RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED')
               AND restoration_trigger IS NULL
        )
        OR EXISTS (
            SELECT 1
              FROM loyalty_point_lot
             WHERE compensation_source_reference IS NOT NULL
               AND restoration_trigger IS NULL
        ) THEN
        RAISE EXCEPTION
            'V22 benefit restoration clean-cutover precheck failed: legacy point restoration candidates exist';
    END IF;
END
$$;

ALTER TABLE promotion_coupon_reservation
    ADD COLUMN store_id uuid NOT NULL,
    ADD COLUMN all_menus_eligible boolean NOT NULL,
    ADD COLUMN eligible_menu_ids varchar(4000) NOT NULL,
    ADD COLUMN restoration_trigger varchar(32),
    ADD COLUMN restoration_policy_version_id bigint,
    ADD COLUMN restoration_disposition varchar(32),
    ADD CONSTRAINT fk_coupon_reservation_restoration_policy
        FOREIGN KEY (restoration_policy_version_id)
        REFERENCES operations_expired_benefit_policy_version(policy_version),
    ADD CONSTRAINT chk_coupon_reservation_restoration_metadata
        CHECK (
            (state = 'RESTORED'
                AND restoration_source_reference IS NOT NULL
                AND restoration_trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
                AND restoration_policy_version_id IS NOT NULL
                AND restoration_disposition IN (
                    'ORIGINAL_RESTORED', 'COMPENSATION_ISSUED', 'SKIPPED_EXPIRED'
                ))
            OR
            (state <> 'RESTORED'
                AND restoration_source_reference IS NULL
                AND restoration_trigger IS NULL
                AND restoration_policy_version_id IS NULL
                AND restoration_disposition IS NULL)
        );

ALTER TABLE promotion_coupon_issuance
    ADD COLUMN restoration_trigger varchar(32),
    ADD COLUMN restoration_policy_version_id bigint,
    ADD CONSTRAINT fk_coupon_issuance_restoration_policy
        FOREIGN KEY (restoration_policy_version_id)
        REFERENCES operations_expired_benefit_policy_version(policy_version),
    ADD CONSTRAINT chk_coupon_issuance_restoration_metadata
        CHECK (
            (original_issuance_id IS NULL
                AND restoration_source_reference IS NULL
                AND restoration_trigger IS NULL
                AND restoration_policy_version_id IS NULL)
            OR
            (original_issuance_id IS NOT NULL
                AND restoration_source_reference IS NOT NULL
                AND restoration_trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
                AND restoration_policy_version_id IS NOT NULL)
        );

CREATE TABLE promotion_compensation_coupon_terms_snapshot (
    coupon_issuance_id uuid PRIMARY KEY REFERENCES promotion_coupon_issuance(id) ON DELETE CASCADE,
    campaign_id uuid NOT NULL REFERENCES promotion_campaign(id),
    campaign_version bigint NOT NULL CHECK (campaign_version >= 0),
    store_id uuid NOT NULL,
    discount_type varchar(24) NOT NULL CHECK (discount_type IN ('FIXED_KRW', 'RATE_BPS')),
    fixed_amount_krw bigint,
    rate_bps integer,
    minimum_eligible_subtotal_krw bigint NOT NULL CHECK (minimum_eligible_subtotal_krw >= 0),
    maximum_discount_krw bigint,
    all_menus_eligible boolean NOT NULL,
    cost_bearer varchar(16) NOT NULL CHECK (cost_bearer IN ('PLATFORM', 'STORE', 'SHARED')),
    platform_share_bps integer NOT NULL,
    store_share_bps integer NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT chk_compensation_coupon_discount_terms CHECK (
        (discount_type = 'FIXED_KRW'
            AND fixed_amount_krw > 0
            AND rate_bps IS NULL)
        OR
        (discount_type = 'RATE_BPS'
            AND fixed_amount_krw IS NULL
            AND rate_bps BETWEEN 1 AND 10000)
    ),
    CONSTRAINT chk_compensation_coupon_maximum CHECK (
        maximum_discount_krw IS NULL OR maximum_discount_krw > 0
    ),
    CONSTRAINT chk_compensation_coupon_cost_burden CHECK (
        (cost_bearer = 'PLATFORM' AND platform_share_bps = 10000 AND store_share_bps = 0)
        OR
        (cost_bearer = 'STORE' AND platform_share_bps = 0 AND store_share_bps = 10000)
        OR
        (cost_bearer = 'SHARED'
            AND platform_share_bps BETWEEN 1 AND 9999
            AND store_share_bps BETWEEN 1 AND 9999
            AND platform_share_bps + store_share_bps = 10000)
    )
);

CREATE TABLE promotion_compensation_coupon_eligible_menu (
    id uuid PRIMARY KEY,
    coupon_issuance_id uuid NOT NULL
        REFERENCES promotion_compensation_coupon_terms_snapshot(coupon_issuance_id) ON DELETE CASCADE,
    menu_id uuid NOT NULL,
    UNIQUE (coupon_issuance_id, menu_id)
);

CREATE FUNCTION validate_compensation_coupon_terms(target_issuance_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    original_issuance uuid;
    all_menus boolean;
    terms_count bigint;
    eligible_count bigint;
BEGIN
    SELECT original_issuance_id INTO original_issuance
      FROM promotion_coupon_issuance
     WHERE id = target_issuance_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT count(*), bool_or(all_menus_eligible)
      INTO terms_count, all_menus
      FROM promotion_compensation_coupon_terms_snapshot
     WHERE coupon_issuance_id = target_issuance_id;
    SELECT count(*) INTO eligible_count
      FROM promotion_compensation_coupon_eligible_menu
     WHERE coupon_issuance_id = target_issuance_id;

    IF original_issuance IS NULL AND terms_count <> 0 THEN
        RAISE EXCEPTION 'Normal CouponIssuance must not have compensation terms';
    END IF;
    IF original_issuance IS NOT NULL AND terms_count <> 1 THEN
        RAISE EXCEPTION 'Compensation CouponIssuance requires exactly one terms snapshot';
    END IF;
    IF terms_count = 1 AND (
        (all_menus AND eligible_count <> 0)
        OR (NOT all_menus AND eligible_count = 0)
    ) THEN
        RAISE EXCEPTION 'Compensation coupon eligible menu cardinality conflicts with allMenusEligible';
    END IF;
END
$$;

CREATE FUNCTION validate_compensation_coupon_issuance_terms()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM validate_compensation_coupon_terms(COALESCE(NEW.id, OLD.id));
    RETURN NULL;
END
$$;

CREATE FUNCTION validate_compensation_coupon_child_terms()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM validate_compensation_coupon_terms(COALESCE(NEW.coupon_issuance_id, OLD.coupon_issuance_id));
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER compensation_coupon_issuance_terms
    AFTER INSERT OR UPDATE ON promotion_coupon_issuance
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_compensation_coupon_issuance_terms();

CREATE CONSTRAINT TRIGGER compensation_coupon_snapshot_terms
    AFTER INSERT OR UPDATE OR DELETE ON promotion_compensation_coupon_terms_snapshot
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_compensation_coupon_child_terms();

CREATE CONSTRAINT TRIGGER compensation_coupon_eligible_menu_terms
    AFTER INSERT OR UPDATE OR DELETE ON promotion_compensation_coupon_eligible_menu
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_compensation_coupon_child_terms();

CREATE FUNCTION reject_compensation_coupon_terms_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Compensation coupon terms snapshot is immutable';
END
$$;

CREATE TRIGGER compensation_coupon_terms_immutable
    BEFORE UPDATE OR DELETE ON promotion_compensation_coupon_terms_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_compensation_coupon_terms_mutation();

CREATE TRIGGER compensation_coupon_eligible_menu_immutable
    BEFORE UPDATE OR DELETE ON promotion_compensation_coupon_eligible_menu
    FOR EACH ROW EXECUTE FUNCTION reject_compensation_coupon_terms_mutation();

CREATE FUNCTION reject_coupon_reservation_extended_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.store_id IS DISTINCT FROM OLD.store_id
        OR NEW.all_menus_eligible IS DISTINCT FROM OLD.all_menus_eligible
        OR NEW.eligible_menu_ids IS DISTINCT FROM OLD.eligible_menu_ids THEN
        RAISE EXCEPTION 'Coupon reservation compensation terms source is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER coupon_reservation_extended_snapshot_immutable
    BEFORE UPDATE ON promotion_coupon_reservation
    FOR EACH ROW EXECUTE FUNCTION reject_coupon_reservation_extended_snapshot_mutation();

ALTER TABLE loyalty_point_reservation
    ADD COLUMN restoration_trigger varchar(32),
    ADD COLUMN restoration_policy_version_id bigint,
    ADD CONSTRAINT fk_point_reservation_restoration_policy
        FOREIGN KEY (restoration_policy_version_id)
        REFERENCES operations_expired_benefit_policy_version(policy_version),
    ADD CONSTRAINT chk_point_reservation_restoration_metadata
        CHECK (
            (state = 'RESTORED'
                AND restoration_source_reference IS NOT NULL
                AND restoration_trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
                AND restoration_policy_version_id IS NOT NULL)
            OR
            (state <> 'RESTORED'
                AND restoration_source_reference IS NULL
                AND restoration_trigger IS NULL
                AND restoration_policy_version_id IS NULL)
        );

ALTER TABLE loyalty_point_lot
    DROP CONSTRAINT chk_point_lot_partial_refund_metadata,
    DROP CONSTRAINT fk_point_lot_partial_refund_policy,
    ADD CONSTRAINT fk_point_lot_restoration_policy
        FOREIGN KEY (restoration_policy_version_id)
        REFERENCES operations_expired_benefit_policy_version(policy_version),
    ADD CONSTRAINT chk_point_lot_restoration_metadata CHECK (
        (original_point_lot_id IS NULL
            AND compensation_source_reference IS NULL
            AND restoration_trigger IS NULL
            AND restoration_policy_version_id IS NULL
            AND restoration_refund_id IS NULL)
        OR
        (original_point_lot_id IS NOT NULL
            AND compensation_source_reference IS NOT NULL
            AND restoration_trigger = 'PARTIAL_REFUND'
            AND restoration_policy_version_id IS NOT NULL
            AND restoration_refund_id IS NOT NULL)
        OR
        (original_point_lot_id IS NOT NULL
            AND compensation_source_reference IS NOT NULL
            AND restoration_trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
            AND restoration_policy_version_id IS NOT NULL
            AND restoration_refund_id IS NULL)
    );

ALTER TABLE loyalty_point_transaction
    DROP CONSTRAINT chk_point_transaction_partial_refund_metadata,
    DROP CONSTRAINT fk_point_transaction_partial_refund_policy,
    ADD CONSTRAINT fk_point_transaction_restoration_policy
        FOREIGN KEY (restoration_policy_version_id)
        REFERENCES operations_expired_benefit_policy_version(policy_version),
    ADD CONSTRAINT chk_point_transaction_restoration_metadata CHECK (
        (type NOT IN ('RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED')
            AND refund_id IS NULL
            AND order_line_id IS NULL
            AND point_reservation_allocation_id IS NULL
            AND restoration_trigger IS NULL
            AND restoration_policy_version_id IS NULL
            AND restoration_disposition IS NULL)
        OR
        (type IN ('RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED')
            AND refund_id IS NOT NULL
            AND order_line_id IS NOT NULL
            AND point_reservation_allocation_id IS NOT NULL
            AND restoration_trigger = 'PARTIAL_REFUND'
            AND restoration_policy_version_id IS NOT NULL
            AND (
                (type = 'RESTORE' AND restoration_disposition = 'ORIGINAL_LOT')
                OR (type = 'COMPENSATION' AND restoration_disposition = 'COMPENSATION_LOT')
                OR (type = 'RESTORE_SKIPPED_EXPIRED' AND restoration_disposition = 'SKIPPED_EXPIRED')
            ))
        OR
        (type IN ('RESTORE', 'COMPENSATION', 'RESTORE_SKIPPED_EXPIRED')
            AND refund_id IS NULL
            AND order_line_id IS NULL
            AND point_reservation_allocation_id IS NOT NULL
            AND restoration_trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
            AND restoration_policy_version_id IS NOT NULL
            AND (
                (type = 'RESTORE' AND restoration_disposition = 'ORIGINAL_LOT')
                OR (type = 'COMPENSATION' AND restoration_disposition = 'COMPENSATION_LOT')
                OR (type = 'RESTORE_SKIPPED_EXPIRED' AND restoration_disposition = 'SKIPPED_EXPIRED')
            ))
    );

CREATE UNIQUE INDEX uq_point_transaction_termination_allocation
    ON loyalty_point_transaction (point_reservation_allocation_id)
    WHERE restoration_trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION');

CREATE FUNCTION reject_coupon_restoration_metadata_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.state = 'RESTORED'
        AND (
            NEW.state IS DISTINCT FROM OLD.state
            OR NEW.restoration_source_reference IS DISTINCT FROM OLD.restoration_source_reference
            OR NEW.restoration_trigger IS DISTINCT FROM OLD.restoration_trigger
            OR NEW.restoration_policy_version_id IS DISTINCT FROM OLD.restoration_policy_version_id
            OR NEW.restoration_disposition IS DISTINCT FROM OLD.restoration_disposition
        ) THEN
        RAISE EXCEPTION 'Coupon restoration metadata is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION reject_point_restoration_metadata_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.state = 'RESTORED'
        AND (
            NEW.state IS DISTINCT FROM OLD.state
            OR NEW.restoration_source_reference IS DISTINCT FROM OLD.restoration_source_reference
            OR NEW.restoration_trigger IS DISTINCT FROM OLD.restoration_trigger
            OR NEW.restoration_policy_version_id IS DISTINCT FROM OLD.restoration_policy_version_id
        ) THEN
        RAISE EXCEPTION 'Point restoration metadata is immutable';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER coupon_restoration_metadata_immutable
    BEFORE UPDATE ON promotion_coupon_reservation
    FOR EACH ROW EXECUTE FUNCTION reject_coupon_restoration_metadata_mutation();

CREATE TRIGGER point_restoration_metadata_immutable
    BEFORE UPDATE ON loyalty_point_reservation
    FOR EACH ROW EXECUTE FUNCTION reject_point_restoration_metadata_mutation();
