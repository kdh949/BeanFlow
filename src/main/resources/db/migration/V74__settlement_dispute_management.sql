SET LOCAL lock_timeout = '5s';

ALTER TABLE operations_operator_permission_grant DROP CONSTRAINT chk_operator_permission_vocabulary,
 ADD CONSTRAINT chk_operator_permission_vocabulary CHECK (permission IN (
    'EXPIRED_BENEFIT_POLICY_READ',
    'EXPIRED_BENEFIT_POLICY_WRITE',
    'POINT_ACCOUNT_READ',
    'POINT_ADJUSTMENT',
    'POINT_ACCRUAL_POLICY_READ',
    'POINT_ACCRUAL_POLICY_WRITE',
    'ORDER_COMPENSATION_READ',
    'PAYMENT_CANCELLATION_SETUP_REPAIR',
    'CUSTOMER_CANCELLATION_REFUND_RECONCILE',
    'SUPPORT_CASE_READ',
    'SUPPORT_CASE_WRITE',
    'SUPPORT_CASE_ASSIGN',
    'SUPPORT_SUBJECT_SEARCH',
    'SUPPORT_VERIFICATION_MANAGE',
    'SUPPORT_PII_REVEAL_REQUEST',
    'SUPPORT_PII_REVEAL_APPROVE',
    'SUPPORT_PII_REVEAL_BASIC',
    'SUPPORT_PII_REVEAL_SENSITIVE',
    'SUPPORT_BREAK_GLASS_REQUEST',
    'SUPPORT_ACTION_REQUEST',
    'SUPPORT_ACTION_APPROVE',
    'SUPPORT_ACTION_EXECUTE',
    'SUPPORT_ORDER_READ',
    'SUPPORT_ORDER_CANCEL',
    'SUPPORT_PICKUP_RESCHEDULE',
    'SUPPORT_RESOLUTION_REQUEST',
    'SUPPORT_RESOLUTION_APPROVE',
    'SUPPORT_RESOLUTION_EXECUTE',
    'SUPPORT_COMPENSATION_REQUEST',
    'SUPPORT_COMPENSATION_APPROVE',
    'SUPPORT_COMPENSATION_EXECUTE',
    'SUPPORT_PROFILE_R1_CHANGE',
    'SUPPORT_PROFILE_R2_CHANGE',
    'SUPPORT_PROFILE_R3_REQUEST',
    'SUPPORT_PROFILE_R3_APPROVE',
    'SUPPORT_DELIVERY_READ',
    'SUPPORT_DELIVERY_INCIDENT_WRITE',
    'SUPPORT_DELIVERY_CHANGE',
    'OPERATIONS_SUPPORT_INVESTIGATION',
    'OPERATIONS_LEGAL_HOLD_MANAGE',
    'OPERATIONS_RETENTION_MANAGE',
    'PRIVACY_AUDIT_READ',
    'PRIVACY_BREAK_GLASS_REVIEW',
    'MERCHANT_CREDENTIAL_MANAGE',
    'STORE_BRAND_MANAGE',
    'STORE_MEDIA_MANAGE',
    'PROMOTION_CAMPAIGN_READ',
    'PROMOTION_CAMPAIGN_WRITE',
    'SETTLEMENT_DISPUTE_READ',
    'SETTLEMENT_DISPUTE_DECIDE'
));

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
 ('SETTLEMENT_DISPUTE_MANAGEMENT_REQUESTED', 'SETTLEMENT_AND_DISPUTE');

ALTER TABLE settlement_dispute
 ADD COLUMN decision_intent varchar(24),
 ADD COLUMN decision_actor_id uuid,
 ADD COLUMN decision_actor_type varchar(24),
 ADD COLUMN decision_reason varchar(500),
 ADD COLUMN decision_requested_at timestamptz,
 ADD COLUMN decision_correlation_id varchar(240),
 ADD CONSTRAINT chk_dispute_decision_intent CHECK (
   (decision_intent IS NULL AND decision_actor_id IS NULL AND decision_actor_type IS NULL
    AND decision_reason IS NULL AND decision_requested_at IS NULL AND decision_correlation_id IS NULL)
   OR (decision_intent IS NOT NULL AND decision_intent IN ('ACCEPTED', 'REJECTED', 'WITHDRAWN')
    AND decision_actor_id IS NOT NULL AND decision_actor_type IS NOT NULL
    AND decision_actor_type IN ('PLATFORM_OPERATOR', 'STORE_OWNER')
    AND decision_reason IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 1 AND 500
    AND decision_requested_at IS NOT NULL AND decision_requested_at >= filed_at
    AND decision_correlation_id IS NOT NULL AND length(btrim(decision_correlation_id)) BETWEEN 1 AND 240
    AND (state = 'UNDER_REVIEW' OR state = decision_intent))
 );
CREATE TABLE settlement_dispute_management_command (
 id uuid PRIMARY KEY,
 actor_id uuid NOT NULL,
 operation varchar(24) NOT NULL CHECK (operation IN ('REVIEW', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')),
 idempotency_key varchar(128) NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 128),
 dispute_id uuid NOT NULL REFERENCES settlement_dispute(id),
 payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
 response_json text,
 created_at timestamptz NOT NULL,
 completed_at timestamptz,
 UNIQUE(actor_id, operation, idempotency_key),
 CHECK ((response_json IS NULL) = (completed_at IS NULL))
);
CREATE INDEX idx_dispute_management_command_retention ON settlement_dispute_management_command(completed_at)
 WHERE completed_at IS NOT NULL;

CREATE OR REPLACE FUNCTION settlement_dispute_guard_transition()
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
    IF OLD.decision_intent IS NOT NULL AND (
        OLD.decision_intent IS DISTINCT FROM NEW.decision_intent
        OR OLD.decision_actor_id IS DISTINCT FROM NEW.decision_actor_id
        OR OLD.decision_actor_type IS DISTINCT FROM NEW.decision_actor_type
        OR OLD.decision_reason IS DISTINCT FROM NEW.decision_reason
        OR OLD.decision_requested_at IS DISTINCT FROM NEW.decision_requested_at
        OR OLD.decision_correlation_id IS DISTINCT FROM NEW.decision_correlation_id
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'SettlementDispute decision intent is immutable';
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
        AND NEW.state NOT IN ('ACCEPTED', 'REJECTED', 'WITHDRAWN')
        AND NOT (NEW.state = 'UNDER_REVIEW' AND OLD.decision_intent IS NULL AND NEW.decision_intent IS NOT NULL) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementDispute review has an invalid outcome';
    END IF;
    RETURN NEW;
END
$$;
