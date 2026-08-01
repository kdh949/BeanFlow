-- Existing V10 refunds do not contain immutable OrderLine or PointLot attribution.
-- The release must not guess that history. BeanFlow is pre-release and the V15
-- activation contract is therefore an empty legacy refund table.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM payment_refund) THEN
        RAISE EXCEPTION
            'V15 partial-refund precheck failed: legacy payment_refund rows have no verifiable line allocation';
    END IF;
END
$$;

DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'payment_refund'::regclass
           AND contype = 'c'
    LOOP
        EXECUTE format('ALTER TABLE payment_refund DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END
$$;

ALTER TABLE payment_refund
    ADD COLUMN requested_points_krw bigint NOT NULL DEFAULT 0,
    ADD COLUMN actor_id uuid,
    ADD COLUMN idempotency_key varchar(128),
    ADD COLUMN payload_hash varchar(64),
    ADD COLUMN correlation_id varchar(160),
    ADD COLUMN point_restoration_policy_version_id bigint,
    ADD COLUMN point_restoration_policy_trigger varchar(32),
    ADD COLUMN point_restoration_policy_benefit_type varchar(16),
    ADD COLUMN point_restoration_policy_mode varchar(40),
    ADD COLUMN point_restoration_policy_validity_days integer,
    ADD COLUMN request_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN lookup_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_action varchar(16) NOT NULL DEFAULT 'REQUEST',
    ADD COLUMN response_status integer,
    ADD COLUMN response_body text,
    ADD COLUMN response_recorded_at timestamptz,
    ADD CONSTRAINT chk_payment_refund_requested_amounts
        CHECK (
            requested_amount_krw >= 0
            AND requested_points_krw >= 0
            AND requested_amount_krw + requested_points_krw > 0
        ),
    ADD CONSTRAINT chk_payment_refund_succeeded_amount
        CHECK (
            succeeded_amount_krw IS NULL
            OR succeeded_amount_krw = requested_amount_krw
        ),
    ADD CONSTRAINT chk_payment_refund_state
        CHECK (state IN (
            'REQUESTED', 'PROCESSING', 'RETRY_SCHEDULED', 'SUCCEEDED', 'FAILED',
            'UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW'
        )),
    ADD CONSTRAINT chk_payment_refund_attempt_budgets
        CHECK (
            request_attempt_count BETWEEN 0 AND 3
            AND lookup_attempt_count BETWEEN 0 AND 5
            AND attempt_count = request_attempt_count + lookup_attempt_count
        ),
    ADD CONSTRAINT chk_payment_refund_next_action
        CHECK (next_action IN ('REQUEST', 'LOOKUP')),
    ADD CONSTRAINT chk_payment_refund_partial_command
        CHECK (
            (reason <> 'PARTIAL_REFUND'
                AND actor_id IS NULL
                AND idempotency_key IS NULL
                AND payload_hash IS NULL
                AND correlation_id IS NULL
                AND point_restoration_policy_version_id IS NULL
                AND point_restoration_policy_trigger IS NULL
                AND point_restoration_policy_benefit_type IS NULL
                AND point_restoration_policy_mode IS NULL
                AND point_restoration_policy_validity_days IS NULL)
            OR
            (reason = 'PARTIAL_REFUND'
                AND actor_id IS NOT NULL
                AND idempotency_key IS NOT NULL
                AND length(idempotency_key) BETWEEN 8 AND 128
                AND payload_hash IS NOT NULL
                AND length(payload_hash) = 64
                AND correlation_id IS NOT NULL
                AND length(btrim(correlation_id)) > 0
                AND point_restoration_policy_version_id IS NOT NULL
                AND point_restoration_policy_trigger = 'PARTIAL_REFUND'
                AND point_restoration_policy_benefit_type = 'POINTS'
                AND point_restoration_policy_mode IN (
                    'COMPENSATE_WITH_NEW_ISSUANCE', 'PRESERVE_ORIGINAL_EXPIRY'
                )
                AND point_restoration_policy_validity_days BETWEEN 1 AND 365)
        ),
    ADD CONSTRAINT fk_payment_refund_point_policy
        FOREIGN KEY (
            point_restoration_policy_version_id,
            point_restoration_policy_trigger,
            point_restoration_policy_benefit_type
        ) REFERENCES operations_expired_benefit_policy_version(
            policy_version,
            trigger,
            benefit_type
        ),
    ADD CONSTRAINT chk_payment_refund_response_snapshot
        CHECK (
            (response_status IS NULL AND response_body IS NULL AND response_recorded_at IS NULL)
            OR
            (response_status IN (201, 202)
                AND response_body IS NOT NULL
                AND response_recorded_at IS NOT NULL)
        ),
    ADD CONSTRAINT chk_payment_refund_terminal_fields CHECK (
        (state = 'SUCCEEDED'
            AND succeeded_amount_krw = requested_amount_krw
            AND (requested_amount_krw = 0 OR provider_refund_reference IS NOT NULL)
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state IN ('FAILED', 'MANUAL_REVIEW')
            AND succeeded_amount_krw IS NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state IN ('REQUESTED', 'RETRY_SCHEDULED', 'UNKNOWN')
            AND succeeded_amount_krw IS NULL
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state IN ('PROCESSING', 'RECONCILING')
            AND succeeded_amount_krw IS NULL
            AND claim_token IS NOT NULL
            AND claim_until IS NOT NULL)
    ),
    ADD CONSTRAINT uq_payment_refund_command_key
        UNIQUE (actor_id, idempotency_key);

DROP INDEX idx_payment_refund_due;
CREATE INDEX idx_payment_refund_due
    ON payment_refund (next_attempt_at, id)
    WHERE state IN ('REQUESTED', 'RETRY_SCHEDULED', 'UNKNOWN', 'PROCESSING', 'RECONCILING');

CREATE TABLE payment_refund_line_request (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES payment_refund(id) ON DELETE CASCADE,
    order_line_id uuid NOT NULL REFERENCES ordering_order_line(id),
    line_sequence integer NOT NULL CHECK (line_sequence >= 0),
    first_unit_index bigint NOT NULL CHECK (first_unit_index >= 0),
    quantity bigint NOT NULL CHECK (quantity > 0),
    original_quantity bigint NOT NULL CHECK (original_quantity > 0),
    gross_krw bigint NOT NULL CHECK (gross_krw >= 0),
    coupon_attribution_krw bigint NOT NULL CHECK (coupon_attribution_krw >= 0),
    points_restoration_krw bigint NOT NULL CHECK (points_restoration_krw >= 0),
    cash_refund_krw bigint NOT NULL CHECK (cash_refund_krw >= 0),
    source_reference varchar(240) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    UNIQUE (refund_id, order_line_id),
    UNIQUE (refund_id, line_sequence),
    CHECK (first_unit_index + quantity <= original_quantity),
    CHECK (gross_krw = coupon_attribution_krw + points_restoration_krw + cash_refund_krw)
);

CREATE TABLE payment_refund_point_request (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES payment_refund(id) ON DELETE CASCADE,
    refund_line_request_id uuid NOT NULL REFERENCES payment_refund_line_request(id) ON DELETE CASCADE,
    order_line_id uuid NOT NULL REFERENCES ordering_order_line(id),
    point_reservation_allocation_id uuid NOT NULL
        REFERENCES loyalty_point_reservation_allocation(id),
    original_point_lot_id uuid NOT NULL REFERENCES loyalty_point_lot(id),
    issuer_type varchar(16) NOT NULL CHECK (issuer_type IN ('PLATFORM', 'BRAND', 'STORE')),
    issuer_reference varchar(240) NOT NULL CHECK (length(btrim(issuer_reference)) > 0),
    requested_amount_krw bigint NOT NULL CHECK (requested_amount_krw > 0),
    source_reference varchar(240) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    UNIQUE (refund_id, order_line_id, point_reservation_allocation_id)
);

CREATE TABLE payment_refund_line_allocation (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES payment_refund(id),
    refund_line_request_id uuid NOT NULL UNIQUE REFERENCES payment_refund_line_request(id),
    order_line_id uuid NOT NULL REFERENCES ordering_order_line(id),
    first_unit_index bigint NOT NULL CHECK (first_unit_index >= 0),
    quantity bigint NOT NULL CHECK (quantity > 0),
    gross_krw bigint NOT NULL CHECK (gross_krw >= 0),
    coupon_attribution_krw bigint NOT NULL CHECK (coupon_attribution_krw >= 0),
    points_restored_krw bigint NOT NULL CHECK (points_restored_krw >= 0),
    cash_refunded_krw bigint NOT NULL CHECK (cash_refunded_krw >= 0),
    source_reference varchar(240) NOT NULL UNIQUE,
    succeeded_at timestamptz NOT NULL,
    UNIQUE (refund_id, order_line_id),
    CHECK (gross_krw = coupon_attribution_krw + points_restored_krw + cash_refunded_krw)
);

CREATE TABLE payment_refund_point_allocation (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES payment_refund(id),
    refund_point_request_id uuid NOT NULL UNIQUE REFERENCES payment_refund_point_request(id),
    order_line_id uuid NOT NULL REFERENCES ordering_order_line(id),
    point_reservation_allocation_id uuid NOT NULL
        REFERENCES loyalty_point_reservation_allocation(id),
    original_point_lot_id uuid NOT NULL REFERENCES loyalty_point_lot(id),
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    source_reference varchar(240) NOT NULL UNIQUE,
    succeeded_at timestamptz NOT NULL,
    UNIQUE (refund_id, order_line_id, point_reservation_allocation_id)
);

CREATE TABLE payment_refund_restoration_work (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL UNIQUE REFERENCES payment_refund(id),
    state varchar(24) NOT NULL CHECK (state IN (
        'PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'SUCCEEDED', 'MANUAL_REVIEW'
    )),
    requested_amount_krw bigint NOT NULL CHECK (requested_amount_krw > 0),
    restored_amount_krw bigint,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    next_attempt_at timestamptz,
    claim_token uuid,
    claim_until timestamptz,
    last_failure_code varchar(80),
    source_reference varchar(240) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT chk_payment_refund_restoration_work_state CHECK (
        (state IN ('PENDING', 'RETRY_SCHEDULED')
            AND restored_amount_krw IS NULL
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'PROCESSING'
            AND restored_amount_krw IS NULL
            AND claim_token IS NOT NULL
            AND claim_until IS NOT NULL)
        OR
        (state = 'SUCCEEDED'
            AND restored_amount_krw = requested_amount_krw
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR
        (state = 'MANUAL_REVIEW'
            AND restored_amount_krw IS NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
    )
);

CREATE INDEX idx_payment_refund_restoration_work_due
    ON payment_refund_restoration_work (next_attempt_at, id)
    WHERE state IN ('PENDING', 'RETRY_SCHEDULED', 'PROCESSING');

ALTER TABLE loyalty_point_lot
    ADD COLUMN restoration_trigger varchar(32),
    ADD COLUMN restoration_policy_version_id bigint,
    ADD COLUMN restoration_refund_id uuid,
    ADD CONSTRAINT chk_point_lot_partial_refund_metadata CHECK (
        (restoration_trigger IS NULL
            AND restoration_policy_version_id IS NULL
            AND restoration_refund_id IS NULL)
        OR
        (restoration_trigger = 'PARTIAL_REFUND'
            AND restoration_policy_version_id IS NOT NULL
            AND restoration_refund_id IS NOT NULL)
    ),
    ADD CONSTRAINT fk_point_lot_partial_refund_policy
        FOREIGN KEY (restoration_policy_version_id)
        REFERENCES operations_expired_benefit_policy_version(policy_version);

ALTER TABLE loyalty_point_transaction
    ADD COLUMN refund_id uuid,
    ADD COLUMN order_line_id uuid,
    ADD COLUMN point_reservation_allocation_id uuid,
    ADD COLUMN restoration_trigger varchar(32),
    ADD COLUMN restoration_policy_version_id bigint,
    ADD COLUMN restoration_disposition varchar(32),
    ADD CONSTRAINT chk_point_transaction_partial_refund_metadata CHECK (
        (refund_id IS NULL
            AND order_line_id IS NULL
            AND point_reservation_allocation_id IS NULL
            AND restoration_trigger IS NULL
            AND restoration_policy_version_id IS NULL
            AND restoration_disposition IS NULL)
        OR
        (refund_id IS NOT NULL
            AND order_line_id IS NOT NULL
            AND point_reservation_allocation_id IS NOT NULL
            AND restoration_trigger = 'PARTIAL_REFUND'
            AND restoration_policy_version_id IS NOT NULL
            AND restoration_disposition IN ('ORIGINAL_LOT', 'COMPENSATION_LOT', 'SKIPPED_EXPIRED'))
    ),
    ADD CONSTRAINT fk_point_transaction_partial_refund_line
        FOREIGN KEY (order_line_id) REFERENCES ordering_order_line(id),
    ADD CONSTRAINT fk_point_transaction_partial_refund_allocation
        FOREIGN KEY (point_reservation_allocation_id)
        REFERENCES loyalty_point_reservation_allocation(id),
    ADD CONSTRAINT fk_point_transaction_partial_refund_policy
        FOREIGN KEY (restoration_policy_version_id)
        REFERENCES operations_expired_benefit_policy_version(policy_version),
    ADD CONSTRAINT uq_point_transaction_partial_refund_source
        UNIQUE (refund_id, order_line_id, point_reservation_allocation_id);

CREATE TABLE loyalty_partial_refund_restoration (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL,
    order_id uuid NOT NULL,
    order_line_id uuid NOT NULL REFERENCES ordering_order_line(id),
    point_reservation_id uuid NOT NULL REFERENCES loyalty_point_reservation(id),
    point_reservation_allocation_id uuid NOT NULL
        REFERENCES loyalty_point_reservation_allocation(id),
    original_point_lot_id uuid NOT NULL REFERENCES loyalty_point_lot(id),
    restored_point_lot_id uuid NOT NULL REFERENCES loyalty_point_lot(id),
    issuer_type varchar(16) NOT NULL CHECK (issuer_type IN ('PLATFORM', 'BRAND', 'STORE')),
    issuer_reference varchar(160) NOT NULL CHECK (length(btrim(issuer_reference)) > 0),
    amount_krw bigint NOT NULL CHECK (amount_krw > 0),
    disposition varchar(32) NOT NULL CHECK (disposition IN (
        'ORIGINAL_LOT', 'COMPENSATION_LOT', 'SKIPPED_EXPIRED'
    )),
    policy_version_id bigint NOT NULL
        REFERENCES operations_expired_benefit_policy_version(policy_version),
    policy_mode varchar(40) NOT NULL CHECK (policy_mode IN (
        'COMPENSATE_WITH_NEW_ISSUANCE', 'PRESERVE_ORIGINAL_EXPIRY'
    )),
    policy_validity_days integer NOT NULL CHECK (policy_validity_days BETWEEN 1 AND 365),
    source_reference varchar(240) NOT NULL UNIQUE,
    restored_at timestamptz NOT NULL,
    UNIQUE (refund_id, order_line_id, point_reservation_allocation_id)
);

CREATE FUNCTION reject_immutable_financial_ledger_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = TG_TABLE_NAME || ' is an immutable financial ledger';
END;
$$;

CREATE TRIGGER payment_refund_line_allocation_immutable
    BEFORE UPDATE OR DELETE ON payment_refund_line_allocation
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_financial_ledger_change();

CREATE TRIGGER payment_refund_line_request_immutable
    BEFORE UPDATE OR DELETE ON payment_refund_line_request
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_financial_ledger_change();

CREATE TRIGGER payment_refund_point_request_immutable
    BEFORE UPDATE OR DELETE ON payment_refund_point_request
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_financial_ledger_change();

CREATE TRIGGER payment_refund_point_allocation_immutable
    BEFORE UPDATE OR DELETE ON payment_refund_point_allocation
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_financial_ledger_change();

CREATE FUNCTION reject_payment_refund_request_snapshot_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.payment_id IS DISTINCT FROM OLD.payment_id
        OR NEW.order_id IS DISTINCT FROM OLD.order_id
        OR NEW.requested_amount_krw IS DISTINCT FROM OLD.requested_amount_krw
        OR NEW.requested_points_krw IS DISTINCT FROM OLD.requested_points_krw
        OR NEW.reason IS DISTINCT FROM OLD.reason
        OR NEW.provider_idempotency_key IS DISTINCT FROM OLD.provider_idempotency_key
        OR NEW.source_reference IS DISTINCT FROM OLD.source_reference
        OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
        OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash
        OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
        OR NEW.point_restoration_policy_version_id IS DISTINCT FROM OLD.point_restoration_policy_version_id
        OR NEW.point_restoration_policy_trigger IS DISTINCT FROM OLD.point_restoration_policy_trigger
        OR NEW.point_restoration_policy_benefit_type IS DISTINCT FROM OLD.point_restoration_policy_benefit_type
        OR NEW.point_restoration_policy_mode IS DISTINCT FROM OLD.point_restoration_policy_mode
        OR NEW.point_restoration_policy_validity_days IS DISTINCT FROM OLD.point_restoration_policy_validity_days
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Payment Refund request snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_refund_request_snapshot_immutable
    BEFORE UPDATE ON payment_refund
    FOR EACH ROW EXECUTE FUNCTION reject_payment_refund_request_snapshot_change();

CREATE TRIGGER loyalty_partial_refund_restoration_immutable
    BEFORE UPDATE OR DELETE ON loyalty_partial_refund_restoration
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_financial_ledger_change();

CREATE FUNCTION validate_partial_refund_allocation_tie_out()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_refund_id uuid := COALESCE(NEW.refund_id, OLD.refund_id);
BEGIN
    IF EXISTS (
        SELECT 1
          FROM payment_refund_line_allocation allocation
          JOIN payment_refund_line_request request
            ON request.id = allocation.refund_line_request_id
         WHERE allocation.refund_id = target_refund_id
           AND (
               allocation.refund_id <> request.refund_id
               OR allocation.order_line_id <> request.order_line_id
               OR allocation.first_unit_index <> request.first_unit_index
               OR allocation.quantity <> request.quantity
               OR allocation.gross_krw <> request.gross_krw
               OR allocation.coupon_attribution_krw <> request.coupon_attribution_krw
               OR allocation.points_restored_krw <> request.points_restoration_krw
               OR allocation.cash_refunded_krw <> request.cash_refund_krw
           )
    ) THEN
        RAISE EXCEPTION 'Refund line allocation differs from immutable request snapshot';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM payment_refund_point_allocation allocation
          JOIN payment_refund_point_request request
            ON request.id = allocation.refund_point_request_id
         WHERE allocation.refund_id = target_refund_id
           AND (
               allocation.refund_id <> request.refund_id
               OR allocation.order_line_id <> request.order_line_id
               OR allocation.point_reservation_allocation_id <> request.point_reservation_allocation_id
               OR allocation.original_point_lot_id <> request.original_point_lot_id
               OR allocation.amount_krw <> request.requested_amount_krw
           )
    ) THEN
        RAISE EXCEPTION 'Refund point allocation differs from immutable request snapshot';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM payment_refund refund
          LEFT JOIN (
              SELECT refund_id,
                     sum(cash_refunded_krw) AS cash_amount,
                     sum(points_restored_krw) AS points_amount
                FROM payment_refund_line_allocation
               GROUP BY refund_id
          ) sums ON sums.refund_id = refund.id
         WHERE refund.id = target_refund_id
           AND refund.state = 'SUCCEEDED'
           AND (
               COALESCE(sums.cash_amount, 0) <> refund.requested_amount_krw
               OR COALESCE(sums.points_amount, 0) <> refund.requested_points_krw
           )
    ) THEN
        RAISE EXCEPTION 'Successful Refund does not tie out to line allocations';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM payment_refund_line_allocation allocation
          JOIN payment_refund_line_allocation other
            ON other.order_line_id = allocation.order_line_id
           AND other.id <> allocation.id
           AND int8range(other.first_unit_index, other.first_unit_index + other.quantity, '[)')
               && int8range(allocation.first_unit_index, allocation.first_unit_index + allocation.quantity, '[)')
         WHERE allocation.refund_id = target_refund_id
    ) THEN
        RAISE EXCEPTION 'Successful Refund unit ranges overlap';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM ordering_order_line line
          JOIN (
              SELECT order_line_id,
                     sum(cash_refunded_krw) AS cash_amount,
                     sum(points_restored_krw) AS points_amount,
                     sum(coupon_attribution_krw) AS coupon_amount,
                     sum(quantity) AS quantity
                FROM payment_refund_line_allocation
               GROUP BY order_line_id
          ) sums ON sums.order_line_id = line.id
         WHERE sums.cash_amount > line.cash_payable_krw
            OR sums.points_amount > line.points_applied_krw
            OR sums.coupon_amount > line.coupon_discount_krw
            OR sums.quantity > line.quantity
    ) THEN
        RAISE EXCEPTION 'Successful Refund exceeds immutable OrderLine allocation';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM payment_refund_point_allocation allocation
          JOIN loyalty_point_reservation_allocation original
            ON original.id = allocation.point_reservation_allocation_id
         GROUP BY original.id, original.amount_krw
        HAVING sum(allocation.amount_krw) > original.amount_krw
    ) THEN
        RAISE EXCEPTION 'Successful Refund exceeds original PointReservation allocation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER payment_refund_line_allocation_tie_out
    AFTER INSERT ON payment_refund_line_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_partial_refund_allocation_tie_out();

CREATE CONSTRAINT TRIGGER payment_refund_point_allocation_tie_out
    AFTER INSERT ON payment_refund_point_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_partial_refund_allocation_tie_out();

CREATE FUNCTION validate_partial_refund_request_tie_out()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_refund_id uuid := COALESCE(NEW.refund_id, OLD.refund_id);
BEGIN
    IF EXISTS (
        SELECT 1
          FROM payment_refund refund
          LEFT JOIN (
              SELECT refund_id,
                     sum(cash_refund_krw) AS cash_amount,
                     sum(points_restoration_krw) AS points_amount
                FROM payment_refund_line_request
               GROUP BY refund_id
          ) sums ON sums.refund_id = refund.id
         WHERE refund.id = target_refund_id
           AND (
               COALESCE(sums.cash_amount, 0) <> refund.requested_amount_krw
               OR COALESCE(sums.points_amount, 0) <> refund.requested_points_krw
           )
    ) THEN
        RAISE EXCEPTION 'Refund request does not tie out to immutable line snapshots';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM payment_refund refund
          LEFT JOIN (
              SELECT refund_id, sum(requested_amount_krw) AS points_amount
                FROM payment_refund_point_request
               GROUP BY refund_id
          ) sums ON sums.refund_id = refund.id
         WHERE refund.id = target_refund_id
           AND COALESCE(sums.points_amount, 0) <> refund.requested_points_krw
    ) THEN
        RAISE EXCEPTION 'Refund point request does not tie out to line point snapshots';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER payment_refund_line_request_tie_out
    AFTER INSERT ON payment_refund_line_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_partial_refund_request_tie_out();

CREATE CONSTRAINT TRIGGER payment_refund_point_request_tie_out
    AFTER INSERT ON payment_refund_point_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_partial_refund_request_tie_out();

CREATE FUNCTION validate_partial_refund_restoration_tie_out()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM loyalty_partial_refund_restoration restoration
          JOIN loyalty_point_reservation_allocation original
            ON original.id = restoration.point_reservation_allocation_id
         GROUP BY original.id, original.amount_krw
        HAVING sum(restoration.amount_krw) > original.amount_krw
    ) THEN
        RAISE EXCEPTION 'Partial-refund restoration exceeds original PointReservation allocation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER loyalty_partial_refund_restoration_tie_out
    AFTER INSERT ON loyalty_partial_refund_restoration
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_partial_refund_restoration_tie_out();
