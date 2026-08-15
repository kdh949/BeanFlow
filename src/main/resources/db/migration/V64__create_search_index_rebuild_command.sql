SET LOCAL lock_timeout = '5s';

-- 재색인은 매장별 transaction으로 진행하므로 전체 결과를 다시 실행하지 않고 재생할 독립 원장이
-- 필요하다. RUNNING row는 같은 actor/key의 동시 요청을 명시적으로 막고, COMPLETED row만 90일 뒤
-- 정리한다. process loss로 RUNNING 상태가 남으면 자동 재실행하지 않고 runbook의 조사 대상으로 둔다.
CREATE TABLE operations_search_index_rebuild_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK (
        length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key = btrim(idempotency_key)
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    state varchar(16) NOT NULL CHECK (state IN ('RUNNING', 'COMPLETED')),
    response_json text,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, idempotency_key),
    CONSTRAINT ck_operations_search_index_rebuild_command_state
        CHECK (
            (state = 'RUNNING' AND response_json IS NULL AND completed_at IS NULL)
            OR (state = 'COMPLETED' AND response_json IS NOT NULL AND length(btrim(response_json)) > 0 AND completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_operations_search_index_rebuild_command_retention
        CHECK (retention_expires_at = created_at + interval '90 days')
);

CREATE INDEX ix_operations_search_index_rebuild_command_retention
    ON operations_search_index_rebuild_command (retention_expires_at, id)
    WHERE state = 'COMPLETED';

-- 감사 action은 폐쇄 어휘다. 이 record는 재색인이 시작됐다는 operator request를 남기며, 완료 여부는
-- command ledger의 immutable replay result가 소유한다.
INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('STORE_SEARCH_INDEX_REBUILD_REQUESTED', 'OPERATIONS_POLICY');
