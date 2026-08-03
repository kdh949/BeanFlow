DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM settlement_batch
         WHERE state <> 'OPEN'
    ) THEN
        RAISE EXCEPTION
            'Settlement lifecycle activation blocked: pre-existing closed Batch requires verified summary inventory';
    END IF;
END
$$;

ALTER TABLE settlement_batch
    ADD COLUMN item_count integer,
    ADD COLUMN gross_paid_krw bigint,
    ADD COLUMN fee_krw bigint,
    ADD COLUMN benefit_cost_krw bigint,
    ADD COLUMN item_net_settlement_krw bigint,
    ADD COLUMN adjustment_krw bigint,
    ADD COLUMN carry_forward_in_krw bigint,
    ADD COLUMN carry_forward_out_krw bigint,
    ADD COLUMN carry_forward_source_batch_id uuid REFERENCES settlement_batch(id),
    ADD COLUMN adjustment_cursor_effective_at timestamptz,
    ADD COLUMN adjustment_cursor_id uuid,
    ADD COLUMN calculated_at timestamptz,
    ADD COLUMN confirmed_at timestamptz,
    ADD CONSTRAINT chk_settlement_batch_lifecycle_summary
        CHECK (
            (
                state = 'OPEN'
                AND item_count IS NULL
                AND gross_paid_krw IS NULL
                AND fee_krw IS NULL
                AND benefit_cost_krw IS NULL
                AND item_net_settlement_krw IS NULL
                AND adjustment_krw IS NULL
                AND carry_forward_in_krw IS NULL
                AND carry_forward_out_krw IS NULL
                AND carry_forward_source_batch_id IS NULL
                AND adjustment_cursor_effective_at IS NULL
                AND adjustment_cursor_id IS NULL
                AND calculated_at IS NULL
                AND confirmed_at IS NULL
            )
            OR
            (
                state IN ('CALCULATED', 'CONFIRMED')
                AND item_count IS NOT NULL
                AND item_count >= 0
                AND gross_paid_krw IS NOT NULL
                AND gross_paid_krw >= 0
                AND fee_krw IS NOT NULL
                AND fee_krw >= 0
                AND benefit_cost_krw IS NOT NULL
                AND benefit_cost_krw >= 0
                AND item_net_settlement_krw IS NOT NULL
                AND item_net_settlement_krw >= 0
                AND item_net_settlement_krw = gross_paid_krw - fee_krw - benefit_cost_krw
                AND adjustment_krw IS NOT NULL
                AND carry_forward_in_krw IS NOT NULL
                AND carry_forward_in_krw <= 0
                AND carry_forward_out_krw IS NOT NULL
                AND carry_forward_out_krw <= 0
                AND carry_forward_out_krw =
                    CASE
                        WHEN item_net_settlement_krw + adjustment_krw + carry_forward_in_krw < 0
                            THEN item_net_settlement_krw + adjustment_krw + carry_forward_in_krw
                        ELSE 0
                    END
                AND (
                    (carry_forward_source_batch_id IS NULL AND carry_forward_in_krw = 0)
                    OR
                    (carry_forward_source_batch_id IS NOT NULL AND carry_forward_in_krw < 0)
                )
                AND carry_forward_source_batch_id IS DISTINCT FROM id
                AND (
                    (adjustment_cursor_effective_at IS NULL AND adjustment_cursor_id IS NULL)
                    OR
                    (adjustment_cursor_effective_at IS NOT NULL AND adjustment_cursor_id IS NOT NULL)
                )
                AND calculated_at IS NOT NULL
                AND (
                    (state = 'CALCULATED' AND confirmed_at IS NULL)
                    OR
                    (state = 'CONFIRMED' AND confirmed_at IS NOT NULL AND confirmed_at >= calculated_at)
                )
            )
        );

CREATE INDEX idx_settlement_batch_store_list
    ON settlement_batch (store_id, settlement_date DESC, id DESC);

CREATE INDEX idx_settlement_batch_open_date
    ON settlement_batch (settlement_date, store_id, id)
    WHERE state = 'OPEN';

CREATE FUNCTION settlement_batch_guard_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.state <> 'OPEN' THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'Calculated or confirmed SettlementBatch is immutable';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.store_id IS DISTINCT FROM NEW.store_id
        OR OLD.settlement_date IS DISTINCT FROM NEW.settlement_date
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementBatch identity is immutable';
    END IF;

    IF OLD.state = 'CONFIRMED' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Confirmed SettlementBatch is immutable';
    END IF;

    IF OLD.state = 'OPEN' AND NEW.state <> 'CALCULATED' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementBatch must transition from OPEN to CALCULATED';
    END IF;

    IF OLD.state = 'CALCULATED' THEN
        IF NEW.state <> 'CONFIRMED' THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'SettlementBatch must transition from CALCULATED to CONFIRMED';
        END IF;
        IF OLD.item_count IS DISTINCT FROM NEW.item_count
            OR OLD.gross_paid_krw IS DISTINCT FROM NEW.gross_paid_krw
            OR OLD.fee_krw IS DISTINCT FROM NEW.fee_krw
            OR OLD.benefit_cost_krw IS DISTINCT FROM NEW.benefit_cost_krw
            OR OLD.item_net_settlement_krw IS DISTINCT FROM NEW.item_net_settlement_krw
            OR OLD.adjustment_krw IS DISTINCT FROM NEW.adjustment_krw
            OR OLD.carry_forward_in_krw IS DISTINCT FROM NEW.carry_forward_in_krw
            OR OLD.carry_forward_out_krw IS DISTINCT FROM NEW.carry_forward_out_krw
            OR OLD.carry_forward_source_batch_id IS DISTINCT FROM NEW.carry_forward_source_batch_id
            OR OLD.adjustment_cursor_effective_at IS DISTINCT FROM NEW.adjustment_cursor_effective_at
            OR OLD.adjustment_cursor_id IS DISTINCT FROM NEW.adjustment_cursor_id
            OR OLD.calculated_at IS DISTINCT FROM NEW.calculated_at THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'Calculated SettlementBatch summary is immutable';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER settlement_batch_transition_guard
BEFORE UPDATE OR DELETE ON settlement_batch
FOR EACH ROW
EXECUTE FUNCTION settlement_batch_guard_transition();

CREATE TABLE settlement_adjustment (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES merchant_store(id),
    settlement_item_id uuid NOT NULL REFERENCES settlement_item(id),
    source_settlement_batch_id uuid NOT NULL REFERENCES settlement_batch(id),
    adjustment_source varchar(240) NOT NULL UNIQUE
        CHECK (
            adjustment_source = btrim(adjustment_source)
            AND length(adjustment_source) BETWEEN 1 AND 240
        ),
    reason_code varchar(32) NOT NULL
        CHECK (reason_code IN ('REFUND_SUCCEEDED', 'DISPUTE_ACCEPTED')),
    effective_at timestamptz NOT NULL,
    order_completed_at timestamptz NOT NULL,
    settlement_date date NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency = 'KRW'),
    amount_krw bigint NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (adjustment_source, reason_code),
    CHECK (settlement_date = (order_completed_at AT TIME ZONE 'Asia/Seoul')::date)
);

CREATE INDEX idx_settlement_adjustment_next_batch
    ON settlement_adjustment (store_id, effective_at, id);

CREATE INDEX idx_settlement_adjustment_item
    ON settlement_adjustment (settlement_item_id, effective_at, id);

CREATE FUNCTION settlement_adjustment_validate_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    item_store_id uuid;
    item_batch_id uuid;
    item_completed_at timestamptz;
    item_settlement_date date;
    batch_state varchar(24);
BEGIN
    SELECT item.store_id,
           item.settlement_batch_id,
           item.completed_at,
           item.settlement_date,
           batch.state
      INTO item_store_id,
           item_batch_id,
           item_completed_at,
           item_settlement_date,
           batch_state
      FROM settlement_item item
      JOIN settlement_batch batch ON batch.id = item.settlement_batch_id
     WHERE item.id = NEW.settlement_item_id
     FOR SHARE OF item, batch;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementAdjustment Item source is missing';
    END IF;
    IF batch_state <> 'CONFIRMED' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementAdjustment requires a confirmed Item';
    END IF;
    IF item_store_id IS DISTINCT FROM NEW.store_id
        OR item_batch_id IS DISTINCT FROM NEW.source_settlement_batch_id
        OR item_completed_at IS DISTINCT FROM NEW.order_completed_at
        OR item_settlement_date IS DISTINCT FROM NEW.settlement_date THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementAdjustment source scope does not match its Item';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER settlement_adjustment_source_guard
BEFORE INSERT ON settlement_adjustment
FOR EACH ROW
EXECUTE FUNCTION settlement_adjustment_validate_source();

CREATE FUNCTION settlement_adjustment_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'SettlementAdjustment is immutable';
END
$$;

CREATE TRIGGER settlement_adjustment_immutable
BEFORE UPDATE OR DELETE ON settlement_adjustment
FOR EACH ROW
EXECUTE FUNCTION settlement_adjustment_reject_mutation();

CREATE TABLE settlement_dispute (
    id uuid PRIMARY KEY,
    settlement_item_id uuid NOT NULL REFERENCES settlement_item(id),
    store_id uuid NOT NULL REFERENCES merchant_store(id),
    previous_dispute_id uuid UNIQUE REFERENCES settlement_dispute(id),
    refile_count smallint NOT NULL DEFAULT 0 CHECK (refile_count IN (0, 1)),
    state varchar(24) NOT NULL
        CHECK (state IN ('FILED', 'UNDER_REVIEW', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')),
    expected_adjustment_krw bigint NOT NULL,
    held_amount_krw bigint NOT NULL,
    reason varchar(1000) NOT NULL
        CHECK (reason = btrim(reason) AND length(reason) BETWEEN 1 AND 1000),
    evidence_references jsonb NOT NULL,
    actor_id uuid NOT NULL,
    operation varchar(64) NOT NULL,
    idempotency_key varchar(200) NOT NULL
        CHECK (idempotency_key = btrim(idempotency_key) AND length(idempotency_key) BETWEEN 1 AND 200),
    payload_hash varchar(64) NOT NULL
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    response_status integer NOT NULL CHECK (response_status = 201),
    response_body text NOT NULL CHECK (length(response_body) > 0),
    correlation_id varchar(240) NOT NULL
        CHECK (correlation_id = btrim(correlation_id) AND length(correlation_id) BETWEEN 1 AND 240),
    settlement_adjustment_id uuid UNIQUE REFERENCES settlement_adjustment(id),
    filed_at timestamptz NOT NULL,
    decided_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (actor_id, operation, idempotency_key),
    CHECK (
        (refile_count = 0 AND previous_dispute_id IS NULL)
        OR
        (refile_count = 1 AND previous_dispute_id IS NOT NULL)
    ),
    CHECK (
        jsonb_typeof(evidence_references) = 'array'
        AND jsonb_array_length(evidence_references) >= 1
    ),
    CHECK (
        (state IN ('FILED', 'UNDER_REVIEW')
            AND held_amount_krw = expected_adjustment_krw
            AND decided_at IS NULL
            AND settlement_adjustment_id IS NULL)
        OR
        (state IN ('REJECTED', 'WITHDRAWN')
            AND held_amount_krw = 0
            AND decided_at IS NOT NULL
            AND settlement_adjustment_id IS NULL)
        OR
        (state = 'ACCEPTED'
            AND held_amount_krw = 0
            AND decided_at IS NOT NULL
            AND settlement_adjustment_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_settlement_dispute_active_item
    ON settlement_dispute (settlement_item_id)
    WHERE state IN ('FILED', 'UNDER_REVIEW');

CREATE INDEX idx_settlement_dispute_store_filed
    ON settlement_dispute (store_id, filed_at, id);

CREATE INDEX idx_settlement_dispute_pending_decision
    ON settlement_dispute (state, filed_at, id)
    WHERE state IN ('FILED', 'UNDER_REVIEW');

CREATE FUNCTION settlement_dispute_validate_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    item_store_id uuid;
    item_batch_state varchar(24);
    previous_item_id uuid;
    previous_state varchar(24);
    previous_refile_count smallint;
    previous_evidence jsonb;
BEGIN
    SELECT item.store_id, batch.state
      INTO item_store_id, item_batch_state
      FROM settlement_item item
      JOIN settlement_batch batch ON batch.id = item.settlement_batch_id
     WHERE item.id = NEW.settlement_item_id
     FOR SHARE OF item, batch;

    IF NOT FOUND OR item_store_id IS DISTINCT FROM NEW.store_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementDispute Item scope is invalid';
    END IF;
    IF item_batch_state <> 'CONFIRMED' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementDispute requires a confirmed Item';
    END IF;

    IF NEW.previous_dispute_id IS NOT NULL THEN
        SELECT settlement_item_id, state, refile_count, evidence_references
          INTO previous_item_id, previous_state, previous_refile_count, previous_evidence
          FROM settlement_dispute
         WHERE id = NEW.previous_dispute_id
         FOR SHARE;
        IF NOT FOUND
            OR previous_item_id IS DISTINCT FROM NEW.settlement_item_id
            OR previous_state NOT IN ('ACCEPTED', 'REJECTED', 'WITHDRAWN')
            OR previous_refile_count <> 0
            OR previous_evidence = NEW.evidence_references THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'SettlementDispute refile guard failed';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER settlement_dispute_insert_guard
BEFORE INSERT ON settlement_dispute
FOR EACH ROW
EXECUTE FUNCTION settlement_dispute_validate_insert();

CREATE FUNCTION settlement_dispute_guard_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementDispute is append-preserving';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.settlement_item_id IS DISTINCT FROM NEW.settlement_item_id
        OR OLD.store_id IS DISTINCT FROM NEW.store_id
        OR OLD.previous_dispute_id IS DISTINCT FROM NEW.previous_dispute_id
        OR OLD.refile_count IS DISTINCT FROM NEW.refile_count
        OR OLD.expected_adjustment_krw IS DISTINCT FROM NEW.expected_adjustment_krw
        OR OLD.reason IS DISTINCT FROM NEW.reason
        OR OLD.evidence_references IS DISTINCT FROM NEW.evidence_references
        OR OLD.actor_id IS DISTINCT FROM NEW.actor_id
        OR OLD.operation IS DISTINCT FROM NEW.operation
        OR OLD.idempotency_key IS DISTINCT FROM NEW.idempotency_key
        OR OLD.payload_hash IS DISTINCT FROM NEW.payload_hash
        OR OLD.response_status IS DISTINCT FROM NEW.response_status
        OR OLD.response_body IS DISTINCT FROM NEW.response_body
        OR OLD.correlation_id IS DISTINCT FROM NEW.correlation_id
        OR OLD.filed_at IS DISTINCT FROM NEW.filed_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementDispute filing evidence is immutable';
    END IF;
    IF OLD.state IN ('ACCEPTED', 'REJECTED', 'WITHDRAWN') THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Terminal SettlementDispute is immutable';
    END IF;
    IF OLD.state = 'FILED' AND NEW.state <> 'UNDER_REVIEW' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementDispute must enter review before decision';
    END IF;
    IF OLD.state = 'UNDER_REVIEW'
        AND NEW.state NOT IN ('ACCEPTED', 'REJECTED', 'WITHDRAWN') THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementDispute review has an invalid outcome';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER settlement_dispute_transition_guard
BEFORE UPDATE OR DELETE ON settlement_dispute
FOR EACH ROW
EXECUTE FUNCTION settlement_dispute_guard_transition();

ALTER TABLE operations_reprocessing_case
    DROP CONSTRAINT chk_reprocessing_case_type,
    ADD CONSTRAINT chk_reprocessing_case_type CHECK (
        case_type IN (
            'PAYMENT_RECONCILIATION',
            'NOTIFICATION_DELIVERY',
            'EVENT_PUBLICATION',
            'SETTLEMENT_LATE_ITEM',
            'ACCEPTANCE_TIMEOUT_WORK',
            'PAYMENT_CANCELLATION_SETUP',
            'SETTLEMENT_ADJUSTMENT',
            'SETTLEMENT_DISPUTE'
        )
    );

CREATE INDEX idx_event_publication_settlement_retry
    ON event_publication (status, last_resubmission_date, publication_date, id)
    WHERE completion_date IS NULL
      AND event_type IN (
          'io.github.kdh949.beanflow.eventing.api.SettlementBatchConfirmedV1',
          'io.github.kdh949.beanflow.eventing.api.SettlementAdjustmentCreatedV1',
          'io.github.kdh949.beanflow.eventing.api.SettlementDisputeFiledV1',
          'io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1'
      );

CREATE INDEX idx_audit_settlement_lifecycle_source
    ON operations_audit_record (source_reference, occurred_at, id)
    WHERE action IN (
        'SETTLEMENT_BATCH_CONFIRMED',
        'SETTLEMENT_ADJUSTMENT_CREATED',
        'SETTLEMENT_DISPUTE_FILED',
        'SETTLEMENT_DISPUTE_DECIDED'
    );
