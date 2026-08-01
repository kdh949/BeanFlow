ALTER TABLE operations_audit_record
    ALTER COLUMN actor_id TYPE varchar(500),
    ALTER COLUMN reason TYPE varchar(500);

CREATE SEQUENCE operations_expired_benefit_policy_version_seq;

SELECT setval(
    'operations_expired_benefit_policy_version_seq',
    (SELECT GREATEST(COALESCE(MAX(policy_version), 1), 1)
       FROM operations_expired_benefit_policy_version),
    true
);

ALTER SEQUENCE operations_expired_benefit_policy_version_seq
    OWNED BY operations_expired_benefit_policy_version.policy_version;

ALTER TABLE operations_expired_benefit_policy_version
    ALTER COLUMN policy_version SET DEFAULT nextval('operations_expired_benefit_policy_version_seq'),
    ADD COLUMN trigger varchar(32),
    ADD COLUMN benefit_type varchar(16);

UPDATE operations_expired_benefit_policy_version
   SET trigger = 'STORE_REJECTION',
       benefit_type = 'COUPON';

ALTER TABLE operations_expired_benefit_policy_version
    ALTER COLUMN trigger SET NOT NULL,
    ALTER COLUMN benefit_type SET NOT NULL,
    ADD CONSTRAINT chk_expired_benefit_policy_key
        CHECK (
            (trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
                AND benefit_type IN ('COUPON', 'POINTS'))
            OR (trigger = 'PARTIAL_REFUND' AND benefit_type = 'POINTS')
        ),
    ADD CONSTRAINT uq_expired_benefit_policy_version_key
        UNIQUE (policy_version, trigger, benefit_type);

CREATE TEMPORARY TABLE migrated_expired_benefit_policy_head ON COMMIT DROP AS
SELECT head.policy_version,
       version.mode,
       version.compensation_validity_days,
       version.effective_at,
       version.updated_by
  FROM operations_expired_benefit_policy_head head
  JOIN operations_expired_benefit_policy_version version
    ON version.policy_version = head.policy_version
 WHERE head.singleton_id = true;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM migrated_expired_benefit_policy_head) <> 1 THEN
        RAISE EXCEPTION 'legacy expired benefit policy head cardinality must be exactly one';
    END IF;
END
$$;

DROP TABLE operations_expired_benefit_policy_head;

CREATE TABLE operations_expired_benefit_policy_head (
    trigger varchar(32) NOT NULL,
    benefit_type varchar(16) NOT NULL,
    policy_version bigint NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (trigger, benefit_type),
    CONSTRAINT chk_expired_benefit_policy_head_key
        CHECK (
            (trigger IN ('STORE_REJECTION', 'CUSTOMER_CANCELLATION')
                AND benefit_type IN ('COUPON', 'POINTS'))
            OR (trigger = 'PARTIAL_REFUND' AND benefit_type = 'POINTS')
        ),
    CONSTRAINT fk_expired_benefit_policy_head_version
        FOREIGN KEY (policy_version, trigger, benefit_type)
        REFERENCES operations_expired_benefit_policy_version(policy_version, trigger, benefit_type)
);

INSERT INTO operations_expired_benefit_policy_head (
    trigger,
    benefit_type,
    policy_version
)
SELECT 'STORE_REJECTION', 'COUPON', policy_version
  FROM migrated_expired_benefit_policy_head;

WITH inserted AS (
    INSERT INTO operations_expired_benefit_policy_version (
        trigger,
        benefit_type,
        mode,
        compensation_validity_days,
        effective_at,
        updated_by,
        reason
    )
    SELECT 'STORE_REJECTION',
           'POINTS',
           mode,
           compensation_validity_days,
           effective_at,
           updated_by,
           'INITIAL_STORE_REJECTION_POINTS_FROM_LEGACY_HEAD'
      FROM migrated_expired_benefit_policy_head
    RETURNING policy_version
)
INSERT INTO operations_expired_benefit_policy_head (trigger, benefit_type, policy_version)
SELECT 'STORE_REJECTION', 'POINTS', policy_version
  FROM inserted;

WITH seed(trigger, benefit_type, mode, compensation_validity_days, reason) AS (
    VALUES
        ('CUSTOMER_CANCELLATION', 'COUPON', 'PRESERVE_ORIGINAL_EXPIRY', 30,
            'INITIAL_CUSTOMER_CANCELLATION_COUPON_POLICY'),
        ('CUSTOMER_CANCELLATION', 'POINTS', 'PRESERVE_ORIGINAL_EXPIRY', 30,
            'INITIAL_CUSTOMER_CANCELLATION_POINTS_POLICY'),
        ('PARTIAL_REFUND', 'POINTS', 'COMPENSATE_WITH_NEW_ISSUANCE', 30,
            'INITIAL_PARTIAL_REFUND_POINTS_POLICY')
), inserted AS (
    INSERT INTO operations_expired_benefit_policy_version (
        trigger,
        benefit_type,
        mode,
        compensation_validity_days,
        effective_at,
        updated_by,
        reason
    )
    SELECT trigger,
           benefit_type,
           mode,
           compensation_validity_days,
           TIMESTAMPTZ '2026-08-01 00:00:00+00',
           '00000000-0000-0000-0000-000000000000',
           reason
      FROM seed
    RETURNING policy_version, trigger, benefit_type
)
INSERT INTO operations_expired_benefit_policy_head (trigger, benefit_type, policy_version)
SELECT trigger, benefit_type, policy_version
  FROM inserted;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM operations_expired_benefit_policy_head) <> 5 THEN
        RAISE EXCEPTION 'expired benefit policy head seed cardinality must be exactly five';
    END IF;
END
$$;

CREATE FUNCTION reject_expired_benefit_policy_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'expired benefit policy versions are immutable';
END
$$;

CREATE TRIGGER trg_expired_benefit_policy_version_immutable
BEFORE UPDATE OR DELETE ON operations_expired_benefit_policy_version
FOR EACH ROW EXECUTE FUNCTION reject_expired_benefit_policy_version_mutation();

CREATE TABLE operations_operator_permission_grant (
    actor_id uuid NOT NULL,
    permission varchar(48) NOT NULL,
    state varchar(16) NOT NULL,
    granted_at timestamptz NOT NULL,
    revoked_at timestamptz,
    version bigint NOT NULL CHECK (version > 0),
    audit_source_reference varchar(200) NOT NULL UNIQUE,
    PRIMARY KEY (actor_id, permission),
    CONSTRAINT chk_operator_permission_vocabulary
        CHECK (permission IN (
            'EXPIRED_BENEFIT_POLICY_READ',
            'EXPIRED_BENEFIT_POLICY_WRITE',
            'POINT_ACCOUNT_READ',
            'POINT_ADJUSTMENT'
        )),
    CONSTRAINT chk_operator_permission_grant_state
        CHECK (
            (state = 'ACTIVE' AND revoked_at IS NULL)
            OR
            (state = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_at >= granted_at)
        )
);

CREATE INDEX idx_operator_permission_grant_active
    ON operations_operator_permission_grant (actor_id, permission)
    WHERE state = 'ACTIVE';
