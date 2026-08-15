SET LOCAL lock_timeout = '5s';

-- 매장 지역 지정의 재실행 원장이다. V60의 merchant_brand_command와 같은 모양이며 명령군마다
-- 원장을 따로 두는 MD-2026-019 규약을 따른다. 브랜드 원장에 얹지 않는 이유는 두 명령군의
-- 행위자가 다르기 때문이다. 브랜드는 운영자, 지역은 매장주이므로 (actor_id, idempotency_key)
-- 유일성이 서로 다른 주체 집합 위에서 성립한다.
--
-- command_type의 허용값이 현재 하나뿐인 것은 지역이 비워질 수 없기 때문이다. region_code는
-- 다음 migration에서 NOT NULL이 되므로 해제 명령 자체가 존재하지 않는다. 그래도 열을 두는 이유는
-- payload_hash가 명령 종류를 포함해야 같은 키의 다른 명령을 재사용으로 판별할 수 있어서다.
CREATE TABLE merchant_store_region_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    command_type varchar(24) NOT NULL CHECK (command_type IN (
        'ASSIGN_STORE_REGION'
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
    CONSTRAINT ck_merchant_store_region_command_retention
        CHECK (retention_expires_at = created_at + interval '90 days')
);

CREATE INDEX ix_merchant_store_region_command_retention
    ON merchant_store_region_command (retention_expires_at, id);

-- GET /regions의 cursor 정렬은 (full_name ASC, code ASC)다. keyset 비교와 ORDER BY가 같은
-- 인덱스를 쓰게 해 두 페이지 사이에서 행이 새거나 겹치지 않게 한다.
CREATE INDEX ix_merchant_region_full_name
    ON merchant_region (full_name, code);

-- 감사 기록의 action은 폐쇄 어휘라 등록하지 않으면 fk_audit_action_category가 append를 거절한다.
-- 지역은 매장 하나에 붙는 사실이고 개인정보가 아닌 공개 법정동 코드이므로 PII_ACCESS가 아니라
-- OPERATIONS_POLICY다.
INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('STORE_REGION_ASSIGNED', 'OPERATIONS_POLICY');
