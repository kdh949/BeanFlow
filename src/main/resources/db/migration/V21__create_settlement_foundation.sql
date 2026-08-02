DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM event_publication
         WHERE completion_date IS NULL
           AND event_type = 'io.github.kdh949.beanflow.eventing.api.OrderCompletedV1'
    ) THEN
        RAISE EXCEPTION
            'Settlement activation blocked: incomplete OrderCompletedV1 publication exists';
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM ordering_order
         WHERE state = 'CANCELLED'
    ) THEN
        RAISE EXCEPTION
            'Settlement activation blocked: legacy CANCELLED Order requires verified cancellation evidence';
    END IF;
END
$$;

ALTER TABLE ordering_order
    ADD COLUMN cancelled_at timestamptz,
    ADD COLUMN cancellation_cause varchar(32),
    ADD CONSTRAINT chk_order_cancellation_cause
        CHECK (
            cancellation_cause IS NULL
            OR cancellation_cause IN ('CUSTOMER_REQUEST', 'PAYMENT_DECLINED')
        ),
    ADD CONSTRAINT chk_order_cancellation_evidence
        CHECK (
            (state = 'CANCELLED'
                AND cancelled_at IS NOT NULL
                AND cancellation_cause IS NOT NULL)
            OR
            (state <> 'CANCELLED'
                AND cancelled_at IS NULL
                AND cancellation_cause IS NULL)
        );

CREATE TABLE settlement_batch (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES merchant_store(id),
    settlement_date date NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('OPEN', 'CALCULATED', 'CONFIRMED')),
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (store_id, settlement_date)
);

CREATE TABLE settlement_item (
    id uuid PRIMARY KEY,
    settlement_batch_id uuid NOT NULL REFERENCES settlement_batch(id),
    order_id uuid NOT NULL REFERENCES ordering_order(id),
    store_id uuid NOT NULL REFERENCES merchant_store(id),
    item_source varchar(240) NOT NULL UNIQUE
        CHECK (item_source = btrim(item_source) AND length(item_source) BETWEEN 1 AND 240),
    completed_at timestamptz NOT NULL,
    settlement_date date NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency = 'KRW'),
    gross_paid_krw bigint NOT NULL CHECK (gross_paid_krw >= 0),
    fee_rate_bps integer NOT NULL CHECK (fee_rate_bps BETWEEN 0 AND 10000),
    fee_krw bigint NOT NULL CHECK (fee_krw >= 0),
    coupon_cost_krw bigint NOT NULL CHECK (coupon_cost_krw >= 0),
    point_cost_krw bigint NOT NULL CHECK (point_cost_krw >= 0),
    benefit_cost_krw bigint NOT NULL CHECK (benefit_cost_krw >= 0),
    net_settlement_krw bigint NOT NULL CHECK (net_settlement_krw >= 0),
    created_at timestamptz NOT NULL,
    UNIQUE (order_id),
    CHECK (settlement_date = (completed_at AT TIME ZONE 'Asia/Seoul')::date),
    CHECK (benefit_cost_krw = coupon_cost_krw + point_cost_krw),
    CHECK (net_settlement_krw = gross_paid_krw - fee_krw - benefit_cost_krw)
);

CREATE INDEX idx_settlement_item_batch_cursor
    ON settlement_item (settlement_batch_id, completed_at, id);

CREATE INDEX idx_audit_settlement_refund_exclusion
    ON operations_audit_record (source_reference, target_id)
    WHERE action = 'SETTLEMENT_REFUND_EXCLUDED'
      AND target_type = 'REFUND';

ALTER TABLE operations_reprocessing_case
    DROP CONSTRAINT chk_reprocessing_case_type,
    ADD CONSTRAINT chk_reprocessing_case_type
        CHECK (
            case_type IN (
                'PAYMENT_RECONCILIATION',
                'NOTIFICATION_DELIVERY',
                'EVENT_PUBLICATION',
                'SETTLEMENT_LATE_ITEM'
            )
        );

CREATE FUNCTION settlement_item_validate_open_batch()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    batch_store_id uuid;
    batch_settlement_date date;
    batch_state varchar(24);
BEGIN
    SELECT store_id, settlement_date, state
      INTO batch_store_id, batch_settlement_date, batch_state
      FROM settlement_batch
     WHERE id = NEW.settlement_batch_id
     FOR KEY SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementItem batch source is missing';
    END IF;
    IF batch_state <> 'OPEN' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementItem cannot be attached to a closed batch';
    END IF;
    IF batch_store_id IS DISTINCT FROM NEW.store_id
        OR batch_settlement_date IS DISTINCT FROM NEW.settlement_date THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementItem store/date scope does not match its batch';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER settlement_item_open_batch_guard
BEFORE INSERT ON settlement_item
FOR EACH ROW
EXECUTE FUNCTION settlement_item_validate_open_batch();

CREATE FUNCTION settlement_item_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'SettlementItem is immutable';
END
$$;

CREATE TRIGGER settlement_item_immutable
BEFORE UPDATE OR DELETE ON settlement_item
FOR EACH ROW
EXECUTE FUNCTION settlement_item_reject_mutation();
