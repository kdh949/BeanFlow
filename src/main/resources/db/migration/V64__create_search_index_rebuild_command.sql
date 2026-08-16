SET LOCAL lock_timeout = '5s';

-- 재색인은 매장별 transaction으로 진행하므로 전체 결과를 다시 실행하지 않고 재생할 독립 원장이
-- 필요하다. RUNNING row는 같은 actor/key의 동시 요청을 명시적으로 막는다.
--
-- 실패한 command의 row는 삭제하지 않는다. 삭제하면 (actor_id, idempotency_key)에 묶인
-- payload_hash가 사라져서 같은 key에 다른 reason을 보낸 요청이 IDEMPOTENCY_KEY_REUSED 대신
-- 새 command로 통과한다. 그래서 실패도 상태로 남긴다.
--
--   RUNNING          실행 중. 같은 key 재요청은 Retry-After와 함께 409.
--   COMPLETED        결과 확정. 90일간 같은 응답을 재생한다.
--   FAILED_RETRYABLE 실패했고 재시도가 안전하다. 매장별 재색인은 멱등이므로 같은 payload면
--                    같은 row에서 attempt를 올려 다시 실행한다.
--   UNKNOWN          결과 저장을 확인하지 못했다. 실제 commit 여부를 알 수 없으므로 자동
--                    재실행하지 않고 운영자가 확인한다.
--   MANUAL_REVIEW    재시도 상한을 넘겼다. 자동 재실행하지 않는다.
--
-- process loss로 RUNNING이 남는 경우도 자동 재실행하지 않고 runbook의 조사 대상으로 둔다.
CREATE TABLE operations_search_index_rebuild_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK (
        length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key = btrim(idempotency_key)
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    state varchar(16) NOT NULL CHECK (
        state IN ('RUNNING', 'COMPLETED', 'FAILED_RETRYABLE', 'UNKNOWN', 'MANUAL_REVIEW')
    ),
    attempt_count integer NOT NULL DEFAULT 1 CHECK (attempt_count BETWEEN 1 AND 5),
    response_json text,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    last_failure_at timestamptz,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, idempotency_key),
    CONSTRAINT ck_operations_search_index_rebuild_command_state
        CHECK (
            (state = 'RUNNING' AND response_json IS NULL AND completed_at IS NULL)
            OR (state = 'COMPLETED' AND response_json IS NOT NULL AND length(btrim(response_json)) > 0 AND completed_at IS NOT NULL)
            OR (
                state IN ('FAILED_RETRYABLE', 'UNKNOWN', 'MANUAL_REVIEW')
                AND response_json IS NULL
                AND completed_at IS NULL
                AND last_failure_at IS NOT NULL
            )
        ),
    CONSTRAINT ck_operations_search_index_rebuild_command_retention
        CHECK (retention_expires_at = created_at + interval '90 days')
);

-- UNKNOWN과 MANUAL_REVIEW는 자동 정리 대상이 아니다. 운영자가 결론을 내려야 하는 상태를
-- retention worker가 조용히 지우면 조사할 근거가 사라진다.
CREATE INDEX ix_operations_search_index_rebuild_command_retention
    ON operations_search_index_rebuild_command (retention_expires_at, id)
    WHERE state IN ('COMPLETED', 'FAILED_RETRYABLE');

-- 감사 action은 폐쇄 어휘다. 이 record는 재색인이 시작됐다는 operator request를 남기며, 완료 여부는
-- command ledger의 immutable replay result가 소유한다. sourceReference는 재사용 가능한
-- Idempotency-Key가 아니라 command id와 attempt로 만들어 재시도마다 별도 기록이 남는다.
INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('STORE_SEARCH_INDEX_REBUILD_REQUESTED', 'OPERATIONS_POLICY');
