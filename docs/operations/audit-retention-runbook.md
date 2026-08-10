# Audit Retention Runbook

## Scope

이 절차는 Operations-owned `AuditRecord`의 category/class/policy snapshot과 bounded retention worker를
확인하고 장애를 처리한다. SupportCase, Delivery owner row, LegalHold, object/index/backup deletion에는 적용하지
않는다. 보존 기간은 initial policy이며 production 적용 전 법률 검토가 필요하다.

## Source of truth

- `operations_retention_policy_version`은 immutable version이다. update/delete trigger를 우회하지 않는다.
- `operations_retention_policy_head`는 category별 current version pointer다. S10에는 runtime activation API가
  없으므로 직접 SQL로 head나 version을 변경하지 않는다.
- `operations_audit_action_category`는 기존 action과 category의 immutable closed mapping이다. 새 Audit action은
  forward migration으로 mapping을 추가한 뒤 배포한다.
- `operations_audit_record`의 `audit_category`, `retention_class`, `retention_policy_version_id`와
  `retention_expires_at`은 append 시점의 증거다. 기존 row를 current policy에 맞춰 재계산하지 않는다.

## Read-only checks

현재 head가 가리키는 immutable version을 PII 없이 확인한다.

```sql
SELECT h.category, h.policy_version_id, h.version AS head_version,
       v.retention_class, v.duration_basis, v.duration_value, v.effective_at
FROM operations_retention_policy_head h
JOIN operations_retention_policy_version v ON v.policy_version_id = h.policy_version_id
ORDER BY h.category;
```

due backlog는 category/class와 count/oldest expiry로만 집계한다. target, actor, reason, summary와 correlation은
운영 대시보드나 metric label에 복제하지 않는다.

```sql
SELECT audit_category, retention_class, count(*) AS due_count,
       min(retention_expires_at) AS oldest_due_at
FROM operations_audit_record
WHERE retention_expires_at <= now()
GROUP BY audit_category, retention_class
ORDER BY audit_category, retention_class;
```

## Worker behavior

scheduled worker는 `(retention_expires_at, id)` 순서로 최대 configured chunk를 하나의 transaction에서
`FOR UPDATE SKIP LOCKED` claim/delete한다. 동시 worker는 서로 잠긴 row를 기다리거나 중복 삭제하지 않는다.
정확한 due 경계는 `retention_expires_at <= now`다.

감시 대상은 closed class/outcome tag만 사용하는 삭제 건수, oldest due age와 failure count다. actor ID,
target ID, case ID, correlation, reason, evidence, Audit summary나 PII를 로그·metric tag에 넣지 않는다.

## Failure response

- policy head/version 부재나 category/class/duration 불일치: privileged operation은 실패해야 한다. 5년, 2년,
  local/in-memory policy로 대체하지 않는다. schema와 V39 적용 상태를 read-only로 확인한 뒤 원인을 수정한다.
- Audit insert/flush 실패: caller business write 성공으로 간주하지 않는다. 같은 idempotency/source contract로
  재시도하거나 owning runbook의 reconciliation 상태를 따른다.
- retention query/delete 실패: 0건 성공으로 기록하지 않는다. row는 다음 tick 재시도 대상으로 남기고
  dependency를 복구한다.
- unmapped 새 action: catch-all category를 쓰지 않는다. action의 목적을 정하고 forward migration과 test를
  먼저 추가한다.

Audit row, policy version/head 또는 action mapping을 직접 수정·삭제해 복구하지 않는다. LegalHold나 전체
component deletion이 필요하면 S120의 별도 승인·ledger·backup replay 절차가 구현될 때까지 실행하지 않는다.
