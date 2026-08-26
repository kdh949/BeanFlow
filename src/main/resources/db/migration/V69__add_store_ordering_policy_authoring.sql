SET LOCAL lock_timeout = '5s';

-- 주문 접수와 픽업 가능 여부의 거래 의미는 이미지 같은 표시 변경에 쓰이는 JPA version과 분리한다.
-- 기존 매장은 현재 flag를 그대로 유지하고 deterministic epoch에서 version 0으로 시작한다.
ALTER TABLE merchant_store
    ADD COLUMN ordering_policy_version bigint NOT NULL DEFAULT 0
        CHECK (ordering_policy_version >= 0),
    ADD COLUMN ordering_policy_updated_at timestamptz NOT NULL
        DEFAULT TIMESTAMPTZ '1970-01-01 00:00:00+00';

-- 최초 terminal response를 90일 보존하는 purpose-specific replay 원장이다. 같은 actor가 같은 key를
-- 다른 payload에 쓰면 operation이 같더라도 payload hash 비교로 409를 반환한다.
CREATE TABLE merchant_store_ordering_policy_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(40) NOT NULL CHECK (operation = 'REPLACE_STORE_ORDERING_POLICY_V1'),
    idempotency_key varchar(128) NOT NULL CHECK (
        length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key = btrim(idempotency_key)
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    store_id uuid NOT NULL REFERENCES merchant_store(id) ON DELETE CASCADE,
    response_json text NOT NULL CHECK (length(btrim(response_json)) > 0),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, idempotency_key),
    CONSTRAINT ck_store_ordering_policy_command_retention
        CHECK (retention_expires_at = created_at + interval '90 days')
);

CREATE INDEX ix_store_ordering_policy_command_retention
    ON merchant_store_ordering_policy_command (retention_expires_at, id);

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('STORE_ORDERING_POLICY_UPDATED', 'OPERATIONS_POLICY');
