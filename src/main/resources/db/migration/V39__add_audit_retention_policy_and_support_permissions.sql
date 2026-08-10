-- This migration must fail rather than wait indefinitely for a conflicting audit-table lock.
SET LOCAL lock_timeout = '5s';

CREATE TABLE operations_audit_action_category (
    action varchar(100) PRIMARY KEY,
    audit_category varchar(48) NOT NULL,
    CONSTRAINT uq_audit_action_category_pair UNIQUE (action, audit_category),
    CONSTRAINT chk_audit_action_category_value CHECK (audit_category IN (
        'FINANCIAL_TRANSACTION',
        'ORDER_AND_FULFILLMENT',
        'SETTLEMENT_AND_DISPUTE',
        'SECURITY_AND_PERMISSION',
        'OPERATIONS_POLICY',
        'PII_ACCESS'
    ))
);

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('POINT_ACCOUNT_READ', 'FINANCIAL_TRANSACTION'),
    ('POINT_ADJUSTMENT_APPLIED', 'FINANCIAL_TRANSACTION'),
    ('ORDER_COMPENSATION_READ', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED', 'FINANCIAL_TRANSACTION'),
    ('CUSTOMER_CANCELLATION_REFUND_RECONCILIATION_SCHEDULED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_CANCELLATION_REPAIR_PROPOSED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_CANCELLATION_REPAIR_APPROVED_AND_EXECUTED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_CANCELLATION_REPAIR_REJECTED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_CANCELLATION_REPAIR_EXPIRED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_CANCELLATION_REPAIR_STALE', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_CANCELLATION_MISSING_REFUND_RECREATED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_APPROVED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_DECLINED', 'FINANCIAL_TRANSACTION'),
    ('PARTIAL_REFUND_REQUESTED', 'FINANCIAL_TRANSACTION'),
    ('PARTIAL_REFUND_CASH_SUCCEEDED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_REGISTRATION_READY', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_REGISTRATION_PROCESSING', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_REGISTRATION_COMPLETED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_REGISTRATION_REJECTED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_REGISTRATION_REGISTRATION_UNKNOWN', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_REGISTRATION_MISCONFIGURED_RETRYABLE', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_REGISTRATION_MANUAL_REVIEW', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_DEFAULT_SET', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_DEACTIVATION_REQUESTED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_DEACTIVATION_READY', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_DEACTIVATION_PROCESSING', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_DEACTIVATION_DEACTIVATION_UNKNOWN', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_DEACTIVATION_RECONCILING', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_DEACTIVATION_MANUAL_REVIEW', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_DEACTIVATION_COMPLETED', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_DEACTIVATION_UNKNOWN', 'FINANCIAL_TRANSACTION'),
    ('PAYMENT_METHOD_PROVIDER_DEACTIVATED', 'FINANCIAL_TRANSACTION'),
    ('ORDER_CREATED', 'ORDER_AND_FULFILLMENT'),
    ('PICKUP_RESERVED', 'ORDER_AND_FULFILLMENT'),
    ('STOCK_RESERVED', 'ORDER_AND_FULFILLMENT'),
    ('COUPON_RESERVED', 'ORDER_AND_FULFILLMENT'),
    ('POINTS_RESERVED', 'ORDER_AND_FULFILLMENT'),
    ('BENEFIT_ONLY_PAYMENT_APPROVED', 'ORDER_AND_FULFILLMENT'),
    ('PICKUP_CONFIRMED', 'ORDER_AND_FULFILLMENT'),
    ('STOCK_CONFIRMED', 'ORDER_AND_FULFILLMENT'),
    ('COUPON_CONFIRMED', 'ORDER_AND_FULFILLMENT'),
    ('POINTS_CONFIRMED', 'ORDER_AND_FULFILLMENT'),
    ('ORDER_PAID', 'FINANCIAL_TRANSACTION'),
    ('ORDER_CANCELLED', 'FINANCIAL_TRANSACTION'),
    ('PICKUP_RELEASED', 'ORDER_AND_FULFILLMENT'),
    ('STOCK_RELEASED', 'ORDER_AND_FULFILLMENT'),
    ('COUPON_RELEASED', 'ORDER_AND_FULFILLMENT'),
    ('POINTS_RELEASED', 'ORDER_AND_FULFILLMENT'),
    ('ORDER_EXPIRED', 'ORDER_AND_FULFILLMENT'),
    ('PICKUP_EXPIRED', 'ORDER_AND_FULFILLMENT'),
    ('STOCK_EXPIRED', 'ORDER_AND_FULFILLMENT'),
    ('ORDER_CUSTOMER_CANCELLED', 'ORDER_AND_FULFILLMENT'),
    ('PICKUP_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION', 'ORDER_AND_FULFILLMENT'),
    ('STOCK_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION', 'ORDER_AND_FULFILLMENT'),
    ('COUPON_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION', 'ORDER_AND_FULFILLMENT'),
    ('POINT_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION', 'ORDER_AND_FULFILLMENT'),
    ('ORDER_CANCELLATION_ACCEPTED_DELIVERY_CREATED', 'ORDER_AND_FULFILLMENT'),
    ('ORDER_COMPENSATION_CASE_CREATED', 'ORDER_AND_FULFILLMENT'),
    ('PAYMENT_CANCELLATION_RECOVERY_SNAPSHOT_CREATED', 'ORDER_AND_FULFILLMENT'),
    ('CUSTOMER_CANCELLATION_REFUND_REQUESTED', 'ORDER_AND_FULFILLMENT'),
    ('ACCEPTANCE_TIMEOUT_WORK_REQUESTED', 'ORDER_AND_FULFILLMENT'),
    ('STORE_ACCEPTANCE_WARNING_REQUESTED', 'ORDER_AND_FULFILLMENT'),
    ('STORE_ORDER_REJECTED_BY_TIMEOUT', 'ORDER_AND_FULFILLMENT'),
    ('STORE_ORDER_ACCEPTED', 'ORDER_AND_FULFILLMENT'),
    ('STORE_ORDER_PREPARING', 'ORDER_AND_FULFILLMENT'),
    ('STORE_ORDER_READY', 'ORDER_AND_FULFILLMENT'),
    ('STORE_ORDER_COMPLETED', 'ORDER_AND_FULFILLMENT'),
    ('STORE_ORDER_REJECTED', 'ORDER_AND_FULFILLMENT'),
    ('SETTLEMENT_DISPUTE_FILED', 'SETTLEMENT_AND_DISPUTE'),
    ('SETTLEMENT_DISPUTE_DECIDED', 'SETTLEMENT_AND_DISPUTE'),
    ('SETTLEMENT_ADJUSTMENT_CREATED', 'SETTLEMENT_AND_DISPUTE'),
    ('SETTLEMENT_BATCH_CONFIRMED', 'SETTLEMENT_AND_DISPUTE'),
    ('SETTLEMENT_REFUND_EXCLUDED', 'SETTLEMENT_AND_DISPUTE'),
    ('SETTLEMENT_ITEM_CREATED', 'SETTLEMENT_AND_DISPUTE'),
    ('OPERATOR_PERMISSION_GRANTED', 'SECURITY_AND_PERMISSION'),
    ('OPERATOR_PERMISSION_REVOKED', 'SECURITY_AND_PERMISSION'),
    ('OPERATOR_PERMISSION_REGRANTED', 'SECURITY_AND_PERMISSION'),
    ('EXPIRED_BENEFIT_POLICY_READ', 'OPERATIONS_POLICY'),
    ('EXPIRED_BENEFIT_POLICY_CHANGED', 'OPERATIONS_POLICY'),
    ('POINT_ACCRUAL_POLICY_BOOTSTRAPPED', 'OPERATIONS_POLICY'),
    ('POINT_ACCRUAL_POLICY_READ', 'OPERATIONS_POLICY'),
    ('POINT_ACCRUAL_POLICY_CHANGED', 'OPERATIONS_POLICY'),
    ('SUPPORT_PII_ACCESS_RECORDED', 'PII_ACCESS');

DO $$
DECLARE
    unmapped_actions text;
BEGIN
    SELECT string_agg(existing.action, ', ' ORDER BY existing.action)
      INTO unmapped_actions
      FROM (SELECT DISTINCT action FROM operations_audit_record) existing
      LEFT JOIN operations_audit_action_category mapping USING (action)
     WHERE mapping.action IS NULL;

    IF unmapped_actions IS NOT NULL THEN
        RAISE EXCEPTION 'V39 audit action preflight failed; unmapped actions: %', unmapped_actions;
    END IF;
END
$$;

CREATE SEQUENCE operations_retention_policy_version_seq;

CREATE TABLE operations_retention_policy_version (
    policy_version_id bigint PRIMARY KEY DEFAULT nextval('operations_retention_policy_version_seq'),
    category varchar(48) NOT NULL,
    retention_class varchar(48) NOT NULL,
    duration_basis varchar(48) NOT NULL,
    duration_value integer NOT NULL,
    effective_at timestamptz NOT NULL,
    actor_reference varchar(500) NOT NULL CHECK (
        actor_reference = btrim(actor_reference)
        AND length(actor_reference) BETWEEN 1 AND 500
        AND actor_reference !~ '[[:cntrl:]]'
    ),
    evidence_reference varchar(500) NOT NULL CHECK (
        evidence_reference = btrim(evidence_reference)
        AND length(evidence_reference) BETWEEN 1 AND 500
        AND evidence_reference !~ '[[:cntrl:]]'
    ),
    CONSTRAINT uq_retention_policy_version_category_class
        UNIQUE (policy_version_id, category, retention_class),
    CONSTRAINT uq_retention_policy_version_id_category
        UNIQUE (policy_version_id, category),
    CONSTRAINT chk_retention_policy_version_shape CHECK (
        (category IN (
            'FINANCIAL_TRANSACTION', 'ORDER_AND_FULFILLMENT', 'SETTLEMENT_AND_DISPUTE',
            'SECURITY_AND_PERMISSION', 'OPERATIONS_POLICY'
        ) AND retention_class = 'FINANCIAL_AUDIT'
            AND (
                (duration_basis = 'SEOUL_CALENDAR_YEARS' AND duration_value = 5)
                OR (duration_basis = 'PRESERVE_STORED_EXPIRY' AND duration_value = 0)
            ))
        OR (category = 'PII_ACCESS' AND retention_class = 'PII_ACCESS_AUDIT'
            AND duration_basis = 'SEOUL_CALENDAR_YEARS')
        OR (category = 'SUPPORT_CASE' AND retention_class = 'SUPPORT_CASE'
            AND duration_basis = 'SEOUL_CALENDAR_YEARS_FROM_CASE_CLOSE')
        OR (category = 'DELIVERY_CONTACT' AND retention_class = 'DELIVERY_CONTACT'
            AND duration_basis = 'EXACT_DAYS_FROM_TERMINAL')
        OR (category = 'CURRENT_LOCATION' AND retention_class = 'CURRENT_LOCATION'
            AND duration_basis = 'EXACT_HOURS_FROM_EVENT')
        OR (category = 'PROVIDER_RAW_WEBHOOK' AND retention_class = 'PROVIDER_RAW_WEBHOOK'
            AND duration_basis = 'EXACT_DAYS_FROM_RECEIPT')
    )
);

ALTER SEQUENCE operations_retention_policy_version_seq
    OWNED BY operations_retention_policy_version.policy_version_id;

INSERT INTO operations_retention_policy_version (
    policy_version_id, category, retention_class, duration_basis, duration_value,
    effective_at, actor_reference, evidence_reference
) VALUES
    (1, 'FINANCIAL_TRANSACTION', 'FINANCIAL_AUDIT', 'SEOUL_CALENDAR_YEARS', 5,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_BOOTSTRAP', 'BR-30;SP-13;ADR-089'),
    (2, 'ORDER_AND_FULFILLMENT', 'FINANCIAL_AUDIT', 'SEOUL_CALENDAR_YEARS', 5,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_BOOTSTRAP', 'BR-30;SP-13;ADR-089'),
    (3, 'SETTLEMENT_AND_DISPUTE', 'FINANCIAL_AUDIT', 'SEOUL_CALENDAR_YEARS', 5,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_BOOTSTRAP', 'BR-30;SP-13;ADR-089'),
    (4, 'SECURITY_AND_PERMISSION', 'FINANCIAL_AUDIT', 'SEOUL_CALENDAR_YEARS', 5,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_BOOTSTRAP', 'BR-30;SP-13;ADR-089'),
    (5, 'OPERATIONS_POLICY', 'FINANCIAL_AUDIT', 'SEOUL_CALENDAR_YEARS', 5,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_BOOTSTRAP', 'BR-30;SP-13;ADR-089'),
    (6, 'PII_ACCESS', 'PII_ACCESS_AUDIT', 'SEOUL_CALENDAR_YEARS', 2,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_BOOTSTRAP', 'SP-12;ADR-089'),
    (7, 'SUPPORT_CASE', 'SUPPORT_CASE', 'SEOUL_CALENDAR_YEARS_FROM_CASE_CLOSE', 3,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_BOOTSTRAP', 'SP-12;ADR-089'),
    (8, 'DELIVERY_CONTACT', 'DELIVERY_CONTACT', 'EXACT_DAYS_FROM_TERMINAL', 90,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_BOOTSTRAP', 'SP-12;ADR-089'),
    (9, 'CURRENT_LOCATION', 'CURRENT_LOCATION', 'EXACT_HOURS_FROM_EVENT', 24,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_BOOTSTRAP', 'SP-12;ADR-089'),
    (10, 'PROVIDER_RAW_WEBHOOK', 'PROVIDER_RAW_WEBHOOK', 'EXACT_DAYS_FROM_RECEIPT', 7,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_BOOTSTRAP', 'SP-12;ADR-089'),
    (11, 'FINANCIAL_TRANSACTION', 'FINANCIAL_AUDIT', 'PRESERVE_STORED_EXPIRY', 0,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_LEGACY_CLASSIFICATION',
        'V39_LEGACY_EXPIRY_PRESERVED;BR-30;SP-13;ADR-089'),
    (12, 'ORDER_AND_FULFILLMENT', 'FINANCIAL_AUDIT', 'PRESERVE_STORED_EXPIRY', 0,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_LEGACY_CLASSIFICATION',
        'V39_LEGACY_EXPIRY_PRESERVED;BR-30;SP-13;ADR-089'),
    (13, 'SETTLEMENT_AND_DISPUTE', 'FINANCIAL_AUDIT', 'PRESERVE_STORED_EXPIRY', 0,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_LEGACY_CLASSIFICATION',
        'V39_LEGACY_EXPIRY_PRESERVED;BR-30;SP-13;ADR-089'),
    (14, 'SECURITY_AND_PERMISSION', 'FINANCIAL_AUDIT', 'PRESERVE_STORED_EXPIRY', 0,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_LEGACY_CLASSIFICATION',
        'V39_LEGACY_EXPIRY_PRESERVED;BR-30;SP-13;ADR-089'),
    (15, 'OPERATIONS_POLICY', 'FINANCIAL_AUDIT', 'PRESERVE_STORED_EXPIRY', 0,
        TIMESTAMPTZ '2026-08-11 00:00:00+09', 'SYSTEM:S10_LEGACY_CLASSIFICATION',
        'V39_LEGACY_EXPIRY_PRESERVED;BR-30;SP-13;ADR-089');

SELECT setval('operations_retention_policy_version_seq', 15, true);

CREATE TABLE operations_retention_policy_head (
    category varchar(48) PRIMARY KEY,
    policy_version_id bigint NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT fk_retention_policy_head_version_category
        FOREIGN KEY (policy_version_id, category)
        REFERENCES operations_retention_policy_version(policy_version_id, category)
);

INSERT INTO operations_retention_policy_head (category, policy_version_id, version)
SELECT category, policy_version_id, 0
  FROM operations_retention_policy_version
 WHERE duration_basis <> 'PRESERVE_STORED_EXPIRY';

CREATE FUNCTION reject_retention_policy_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'retention policy versions are immutable';
END;
$$;

CREATE TRIGGER retention_policy_version_immutable
    BEFORE UPDATE OR DELETE ON operations_retention_policy_version
    FOR EACH ROW EXECUTE FUNCTION reject_retention_policy_version_mutation();

CREATE FUNCTION reject_audit_action_category_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'audit action categories are immutable';
END;
$$;

CREATE TRIGGER audit_action_category_immutable
    BEFORE UPDATE OR DELETE ON operations_audit_action_category
    FOR EACH ROW EXECUTE FUNCTION reject_audit_action_category_mutation();

CREATE TEMPORARY TABLE audit_retention_backfill_evidence ON COMMIT DROP AS
SELECT count(*) AS row_count,
       min(retention_expires_at) AS minimum_expiry,
       max(retention_expires_at) AS maximum_expiry,
       coalesce(sum(hashtextextended(id::text || '|' || retention_expires_at::text, 0)::numeric), 0) AS expiry_checksum
  FROM operations_audit_record;

ALTER TABLE operations_audit_record
    ADD COLUMN audit_category varchar(48),
    ADD COLUMN retention_class varchar(48),
    ADD COLUMN retention_policy_version_id bigint,
    ADD COLUMN retention_provenance varchar(48);

UPDATE operations_audit_record record
   SET audit_category = mapping.audit_category,
       retention_class = version.retention_class,
       retention_policy_version_id = version.policy_version_id,
       retention_provenance = 'LEGACY_MIGRATION_CLASSIFICATION'
  FROM operations_audit_action_category mapping
  JOIN operations_retention_policy_version version
    ON version.category = mapping.audit_category
   AND version.duration_basis = 'PRESERVE_STORED_EXPIRY'
 WHERE record.action = mapping.action;

DO $$
DECLARE
    before_evidence audit_retention_backfill_evidence%ROWTYPE;
    after_count bigint;
    after_minimum timestamptz;
    after_maximum timestamptz;
    after_checksum numeric;
BEGIN
    SELECT * INTO STRICT before_evidence FROM audit_retention_backfill_evidence;
    SELECT count(*), min(retention_expires_at), max(retention_expires_at),
           coalesce(sum(hashtextextended(id::text || '|' || retention_expires_at::text, 0)::numeric), 0)
      INTO after_count, after_minimum, after_maximum, after_checksum
      FROM operations_audit_record;

    IF EXISTS (
        SELECT 1 FROM operations_audit_record
         WHERE audit_category IS NULL
            OR retention_class IS NULL
            OR retention_policy_version_id IS NULL
            OR retention_provenance IS NULL
    ) OR before_evidence.row_count <> after_count
       OR before_evidence.minimum_expiry IS DISTINCT FROM after_minimum
       OR before_evidence.maximum_expiry IS DISTINCT FROM after_maximum
       OR before_evidence.expiry_checksum <> after_checksum THEN
        RAISE EXCEPTION 'V39 audit retention backfill evidence mismatch';
    END IF;
END
$$;

-- Expand/backfill phase: keep the columns nullable until a separately deployed contract migration.
-- This trigger classifies an all-null insert from a V38 binary using the current policy head;
-- partial inputs, unknown actions, missing heads, and invalid provenance fail closed.
CREATE FUNCTION populate_audit_retention_compatibility()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    mapped_category varchar(48);
    selected_policy_version_id bigint;
    selected_retention_class varchar(48);
    selected_duration_basis varchar(48);
BEGIN
    IF NEW.audit_category IS NULL
       AND NEW.retention_class IS NULL
       AND NEW.retention_policy_version_id IS NULL
       AND NEW.retention_provenance IS NULL THEN
        SELECT mapping.audit_category,
               head.policy_version_id,
               policy.retention_class,
               policy.duration_basis
          INTO mapped_category,
               selected_policy_version_id,
               selected_retention_class,
               selected_duration_basis
          FROM operations_audit_action_category mapping
          JOIN operations_retention_policy_head head
            ON head.category = mapping.audit_category
          JOIN operations_retention_policy_version policy
            ON policy.policy_version_id = head.policy_version_id
           AND policy.category = head.category
         WHERE mapping.action = NEW.action;

        IF NOT FOUND OR selected_duration_basis = 'PRESERVE_STORED_EXPIRY' THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'audit retention compatibility classification is unavailable';
        END IF;

        NEW.audit_category := mapped_category;
        NEW.retention_class := selected_retention_class;
        NEW.retention_policy_version_id := selected_policy_version_id;
        NEW.retention_provenance := 'DATABASE_COMPATIBILITY_SNAPSHOT';
    ELSIF NEW.audit_category IS NULL
       OR NEW.retention_class IS NULL
       OR NEW.retention_policy_version_id IS NULL
       OR NEW.retention_provenance IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'audit retention fields must be supplied together';
    END IF;

    SELECT duration_basis
      INTO selected_duration_basis
      FROM operations_retention_policy_version
     WHERE policy_version_id = NEW.retention_policy_version_id
       AND category = NEW.audit_category
       AND retention_class = NEW.retention_class;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'audit retention policy version does not match its category and class';
    END IF;

    IF (NEW.retention_provenance = 'LEGACY_MIGRATION_CLASSIFICATION'
        AND selected_duration_basis <> 'PRESERVE_STORED_EXPIRY')
       OR (NEW.retention_provenance IN ('APPEND_SNAPSHOT', 'DATABASE_COMPATIBILITY_SNAPSHOT')
        AND selected_duration_basis = 'PRESERVE_STORED_EXPIRY')
       OR NEW.retention_provenance NOT IN (
           'APPEND_SNAPSHOT',
           'LEGACY_MIGRATION_CLASSIFICATION',
           'DATABASE_COMPATIBILITY_SNAPSHOT'
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'audit retention provenance does not match the policy version';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER audit_retention_compatibility
    BEFORE INSERT ON operations_audit_record
    FOR EACH ROW EXECUTE FUNCTION populate_audit_retention_compatibility();

ALTER TABLE operations_audit_record
    ADD CONSTRAINT fk_audit_action_category
        FOREIGN KEY (action, audit_category)
        REFERENCES operations_audit_action_category(action, audit_category) NOT VALID,
    ADD CONSTRAINT fk_audit_retention_policy_version
        FOREIGN KEY (retention_policy_version_id, audit_category, retention_class)
        REFERENCES operations_retention_policy_version(policy_version_id, category, retention_class) NOT VALID,
    ADD CONSTRAINT chk_audit_retention_class CHECK (
        (audit_category = 'PII_ACCESS' AND retention_class = 'PII_ACCESS_AUDIT')
        OR (audit_category IN (
            'FINANCIAL_TRANSACTION', 'ORDER_AND_FULFILLMENT', 'SETTLEMENT_AND_DISPUTE',
            'SECURITY_AND_PERMISSION', 'OPERATIONS_POLICY'
        ) AND retention_class = 'FINANCIAL_AUDIT')
    ) NOT VALID,
    ADD CONSTRAINT chk_audit_retention_provenance CHECK (retention_provenance IN (
        'APPEND_SNAPSHOT',
        'LEGACY_MIGRATION_CLASSIFICATION',
        'DATABASE_COMPATIBILITY_SNAPSHOT'
    )) NOT VALID;

ALTER TABLE operations_operator_permission_grant
    DROP CONSTRAINT chk_operator_permission_vocabulary,
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
        'PRIVACY_AUDIT_READ'
    ));
