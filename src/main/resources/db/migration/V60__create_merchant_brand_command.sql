SET LOCAL lock_timeout = '5s';

-- 브랜드 생성·수정·보관과 매장 브랜드 지정·해제는 ADR-112 4절대로 PLATFORM_OPERATOR 전용이다.
-- 역할(ROLE_PLATFORM_OPERATOR)만으로는 부족하고 명시적 grant를 함께 요구한다.
ALTER TABLE operations_operator_permission_grant
    DROP CONSTRAINT chk_operator_permission_vocabulary,
    ADD CONSTRAINT chk_operator_permission_vocabulary CHECK (permission IN (
        'EXPIRED_BENEFIT_POLICY_READ', 'EXPIRED_BENEFIT_POLICY_WRITE', 'POINT_ACCOUNT_READ', 'POINT_ADJUSTMENT',
        'POINT_ACCRUAL_POLICY_READ', 'POINT_ACCRUAL_POLICY_WRITE', 'ORDER_COMPENSATION_READ',
        'PAYMENT_CANCELLATION_SETUP_REPAIR', 'CUSTOMER_CANCELLATION_REFUND_RECONCILE',
        'SUPPORT_CASE_READ', 'SUPPORT_CASE_WRITE', 'SUPPORT_CASE_ASSIGN', 'SUPPORT_SUBJECT_SEARCH',
        'SUPPORT_VERIFICATION_MANAGE', 'SUPPORT_PII_REVEAL_REQUEST', 'SUPPORT_PII_REVEAL_APPROVE',
        'SUPPORT_PII_REVEAL_BASIC', 'SUPPORT_PII_REVEAL_SENSITIVE', 'SUPPORT_BREAK_GLASS_REQUEST',
        'SUPPORT_ACTION_REQUEST', 'SUPPORT_ACTION_APPROVE', 'SUPPORT_ACTION_EXECUTE', 'SUPPORT_ORDER_READ',
        'SUPPORT_ORDER_CANCEL', 'SUPPORT_PICKUP_RESCHEDULE', 'SUPPORT_RESOLUTION_REQUEST',
        'SUPPORT_RESOLUTION_APPROVE', 'SUPPORT_RESOLUTION_EXECUTE', 'SUPPORT_COMPENSATION_REQUEST',
        'SUPPORT_COMPENSATION_APPROVE', 'SUPPORT_COMPENSATION_EXECUTE', 'SUPPORT_PROFILE_R1_CHANGE',
        'SUPPORT_PROFILE_R2_CHANGE', 'SUPPORT_PROFILE_R3_REQUEST', 'SUPPORT_PROFILE_R3_APPROVE',
        'SUPPORT_DELIVERY_READ', 'SUPPORT_DELIVERY_INCIDENT_WRITE', 'SUPPORT_DELIVERY_CHANGE',
        'OPERATIONS_SUPPORT_INVESTIGATION', 'OPERATIONS_LEGAL_HOLD_MANAGE', 'OPERATIONS_RETENTION_MANAGE',
        'PRIVACY_AUDIT_READ', 'PRIVACY_BREAK_GLASS_REVIEW', 'MERCHANT_CREDENTIAL_MANAGE',
        'STORE_BRAND_MANAGE'
    ));

-- 브랜드 명령의 재실행 원장이다. V26의 operations_payment_setup_repair_idempotency와 같은 모양이며
-- 확정된 응답 본문을 그대로 담아 재요청이 새 명령을 실행하지 않고 같은 응답을 돌려주게 한다.
--
-- UNIQUE는 (actor_id, idempotency_key)다. command_type을 키에 넣지 않는 이유는 같은 키를 다른
-- 명령에 재사용하는 것도 재사용이기 때문이다. command_type은 payload_hash에 들어가므로 그런
-- 요청은 409 IDEMPOTENCY_KEY_REUSED가 된다.
CREATE TABLE merchant_brand_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    command_type varchar(24) NOT NULL CHECK (command_type IN (
        'CREATE_BRAND', 'UPDATE_BRAND', 'ASSIGN_STORE_BRAND', 'CLEAR_STORE_BRAND'
    )),
    idempotency_key varchar(128) NOT NULL CHECK (
        length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key = btrim(idempotency_key)
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    response_json text NOT NULL CHECK (length(btrim(response_json)) > 0),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, idempotency_key),
    CONSTRAINT ck_merchant_brand_command_retention
        CHECK (retention_expires_at = created_at + interval '90 days')
);

CREATE INDEX ix_merchant_brand_command_retention
    ON merchant_brand_command (retention_expires_at, id);

-- 감사 기록의 action은 폐쇄 어휘다. 새 명령 넷을 등록하지 않으면 fk_audit_action_category가
-- 거절한다. 브랜드는 여러 매장이 공유하는 자원의 운영 결정이므로 OPERATIONS_POLICY다.
INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('BRAND_CREATED', 'OPERATIONS_POLICY'),
    ('BRAND_UPDATED', 'OPERATIONS_POLICY'),
    ('STORE_BRAND_ASSIGNED', 'OPERATIONS_POLICY'),
    ('STORE_BRAND_CLEARED', 'OPERATIONS_POLICY');
