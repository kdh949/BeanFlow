SET LOCAL lock_timeout = '5s';

ALTER TABLE ordering_manual_publication_recovery
    ADD COLUMN case_version bigint,
    ADD COLUMN claimed_at timestamptz,
    ADD COLUMN started_at timestamptz,
    ADD COLUMN execution_outcome varchar(16),
    ADD COLUMN unknown_since timestamptz,
    ADD COLUMN result_pending boolean NOT NULL DEFAULT false,
    ADD COLUMN replay_unknown boolean NOT NULL DEFAULT false;

UPDATE ordering_manual_publication_recovery
SET case_version = (response_json::jsonb -> 'recoveryCase' ->> 'version')::bigint;
ALTER TABLE ordering_manual_publication_recovery ALTER COLUMN case_version SET NOT NULL;
ALTER TABLE ordering_manual_publication_recovery ADD CONSTRAINT chk_manual_publication_case_version CHECK (case_version >= 0),
    ADD CONSTRAINT chk_manual_publication_outcome CHECK (execution_outcome IN ('SUCCEEDED', 'FAILED', 'UNKNOWN'));

-- 기존 실행의 시각을 추정하지 않는다. 확인할 수 없는 시작은 결과 불명 조사로 보낸다.
UPDATE ordering_manual_publication_recovery r
SET claimed_at = p.last_resubmission_date
FROM event_publication p
WHERE p.id = r.publication_id AND p.completion_attempts > r.baseline_attempts;

CREATE INDEX idx_manual_publication_unknown ON ordering_manual_publication_recovery(publication_id, baseline_attempts)
    WHERE execution_outcome = 'UNKNOWN';
INSERT INTO operations_audit_action_category(action, audit_category)
VALUES ('PUBLICATION_EXECUTION_UNKNOWN', 'OPERATIONS_POLICY');
