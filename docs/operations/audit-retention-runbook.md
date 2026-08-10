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
- `operations_audit_record`의 `audit_category`, `retention_class`, `retention_policy_version_id`,
  `retention_provenance`와 `retention_expires_at`은 retention evidence다. `APPEND_SNAPSHOT`만 append 시점의
  policy snapshot이다. `LEGACY_MIGRATION_CLASSIFICATION`은 V39이 기존 expiry를 재계산하지 않고 부여한
  `PRESERVE_STORED_EXPIRY` 분류이고, 과거 append 결정을 주장하지 않는다. 기존 row를 current policy에 맞춰
  재계산하지 않는다.
- `DATABASE_COMPATIBILITY_SNAPSHOT`은 V39 배포 중 V38 binary가 모든 새 retention field를 생략했을 때만 DB
  trigger가 current head로 채운 compatibility provenance다. 누락 head/action 또는 partial field는 성공으로
  대체하지 않고 insert를 실패시킨다.

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

legacy 및 rollout compatibility 사용 여부는 PII 없이 다음처럼 확인한다.

```sql
SELECT retention_provenance, count(*) AS record_count, max(occurred_at) AS newest_occurred_at
FROM operations_audit_record
GROUP BY retention_provenance
ORDER BY retention_provenance;
```

## V39 deployment and later contract

V39은 `expand/backfill` migration이다. nullable retention columns, immutable policy/action mapping, legacy
classification과 `BEFORE INSERT` compatibility trigger를 한 transaction으로 추가한다. migration은 conflicting
audit-table lock을 5초 이상 기다리지 않도록 `lock_timeout`을 설정한다. timeout이나 preflight/backfill evidence
실패는 전체 migration을 rollback하며, default/current policy를 직접 써서 재시도하지 않는다.

V38 binary가 rollout 중 Audit을 insert해도 trigger가 known action과 current non-legacy head를 사용해 네 field를
함께 채운다. partial input, unknown action, missing head/version 또는 legacy policy의 snapshot 사용은 DB에서
실패한다. 따라서 physical `NOT NULL`, FK/CHECK validation과 compatibility population 제거는 **별도 contract
migration**으로만 수행한다. 그 migration은 모든 instance가 V39+임을 확인하고, `DATABASE_COMPATIBILITY_SNAPSHOT`
의 가장 최근 `occurred_at`이 fleet drain observation window보다 오래된 뒤 별도 migration-writer lease를 획득해
계획한다.

V39은 새 `(retention_class, retention_expires_at, id)` index를 추가하지 않는다. V4의
`idx_audit_retention (retention_expires_at, id)` column order는 purge predicate/order와 일치하지만, planner 선택은
주장하지 않는다. representative production-like data의 `EXPLAIN (ANALYZE, BUFFERS)`와 migration
duration/lock-wait 측정은 아직 수행하지 않았다. contract scheduling
전에는 해당 측정 결과를 이 runbook/후속 ExecPlan에 기록해야 하며, 측정 전에는 performance/no-lock claim을 하지
않는다.

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
