# S10 Audit retention classification과 Support permission foundation을 구현한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s00-documentation-contracts.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. PaymentMethod cursor tampering test를 padding bit에도 결정적으로
서명을 바꾸는 helper로 안정화한 뒤, targeted 10회 재실행과 full-suite 2회가 모두 통과해
`Implementation-Ready=true`다. Migration scheduling과 lease acquisition은 readiness와 별개인 실행 시점
preflight이며, 이 plan의 migration lease나 번호는 아직 획득·예약하지 않았다.

## Purpose / Big Picture

현재 모든 `AuditRecord`에 적용되는 서울 달력 5년 retention을 약화하지 않으면서, Operations-owned
`AuditCategory`, `RetentionClass`, immutable `RetentionPolicyVersion` foundation을 추가한다. 동시에 후속
Support Stage가 JWT role fallback 없이 사용할 closed `OperatorPermission` vocabulary를 기존 persistent
grant/revoke/regrant 경계에 추가한다.

완료 후 기존 Audit row는 원래 `retention_expires_at`을 그대로 유지하고 명시적 category/class/policy version을
가진다. 신규 Audit command는 category를 필수로 제공하며 Operations가 current immutable policy version을
같은 transaction에서 snapshot한다. S10은 SupportCase, reveal, action, API 또는 retention deletion automation을
구현하지 않는다.

## Current State

### Current code and schema

- `operations/api/OperatorPermissionOperations.kt`에는 9개 permission enum과
  `OperatorPermissionAuthorization.requireActive()`가 있다.
- `operations/api/AuditRecordOperations.kt`의 `AppendAuditRecordCommand`에는 category/policy가 없다.
- `operations/internal/AuditRecordService.kt`는 `Propagation.MANDATORY` append와
  `occurredAt.atZone(Asia/Seoul).plusYears(5)`를 모든 row에 적용한다.
- `operations/internal/AuditRecordPersistence.kt`의 `operations_audit_record` mapping은
  `retention_expires_at`만 가진다.
- `operations/internal/OperatorPermissionPersistence.kt`는 `(actor_id, permission)` PK와 pessimistic row lock을
  사용한다.
- `operations/internal/OperatorPermissionGrantService.kt`는 verified OIDC offline bootstrap, grant/revoke/regrant,
  permission row advisory lock과 Audit append를 한 local transaction으로 묶는다.
- `operations/internal/AuditRetentionWorker.kt`는 due row를 100개 기본 chunk로 삭제하며 failure를 재throw한다.
- V4가 Audit table/index를, V13이 permission table과 최초 check를 만들었고 V16/V22/V27이 vocabulary를 현재
  9개까지 확장했다. Latest main inventory는 V38까지다.
- `AuditRecordTest`는 sensitive key, append-only unique와 서울 윤년 5주년 경계를 검증한다.
- `OperatorPermissionIntegrationTest`는 active grant, revoke/regrant 즉시성, closed vocabulary, Audit rollback과
  authorization-versus-revoke lock race를 PostgreSQL Testcontainers로 검증한다.

### Audit append call-site inventory

S10은 다음 production call site 모두에 명시적 `AuditCategory`를 부여하고 default/fallback을 두지 않는다.

- Dispute: `SettlementDisputeService.kt`
- Loyalty: `PointAccountQueryService.kt`, `PointAdjustmentService.kt`
- Operations: `CustomerCancellationRefundReconciliationService.kt`, `ExpiredBenefitRestorationPolicyService.kt`,
  `OperatorCompensationQueryService.kt`, `OperatorPermissionGrantService.kt`,
  `OrdinaryPointAccrualPolicyBootstrapService.kt`, `OrdinaryPointAccrualPolicyQueryService.kt`,
  `OrdinaryPointAccrualPolicyService.kt`, `PaymentCancellationSetupIntegrityService.kt`, `PaymentSetupRepairService.kt`
- Ordering: `CustomerCancellationService.kt`, `OrderCreationAuditFactory.kt`, `PaymentResultTransaction.kt`,
  `ReservationExpiryService.kt`, `StoreAcceptanceDeadlineService.kt`, `StoreOrderTransitionService.kt`
- Payment: `PartialRefundPaymentService.kt`, `PaymentMethodAuditWriter.kt`
- Settlement: `CustomerCancellationRefundExclusionService.kt`, `SettlementAdjustmentService.kt`,
  `SettlementBatchLifecycleService.kt`, `SettlementItemCreationService.kt`

Implementation preflight reruns `rg -l 'AppendAuditRecordCommand\\(' src/main/kotlin` and fails the Stage review if an
unclassified caller remains.

## Definitions

- **AuditCategory:** why an Audit fact exists; closed code vocabulary owned by Operations.
- **RetentionClass:** accepted purpose/period family independent of a row's business action.
- **RetentionPolicyVersion:** immutable mapping from category to class and exact duration rule selected at append time.
- **Policy head:** category-scoped pointer to one immutable policy version. S10 seeds heads but exposes no runtime mutation.
- **Legacy expiry preservation:** migration adds classification without recalculating an existing row's stored expiry.

## Scope

### In Scope

- Audit category/class/version API and Operations persistence
- explicit existing action-to-category backfill with abort-on-unmapped precheck
- existing Audit row classification with exact expiry preservation
- new Audit append policy snapshot and fail-closed validation
- due-row chunk claim safe under concurrent workers
- exact Support/Operations/Privacy permission enum and DB check expansion
- existing offline bootstrap and authorization path support for new enum values
- PostgreSQL migration, boundary, rollback and revocation concurrency tests

### Non-goals

- Support/Delivery/LegalHold runtime endpoint, Controller, SupportCase or UI
- PII reveal implementation, retention deletion across other owner tables, object/index/backup deletion
- policy administration HTTP API or changing accepted retention numbers
- KMS/provider, browser credential/CORS/CSRF, Analytics schema, new production dependency
- migration implementation before explicit scheduling and lease acquisition

## Business Rules and Invariants

1. Every pre-S10 Audit row keeps its stored expiry; no row is shortened from the existing BR-30 five-year rule.
2. Existing financial/order/fulfillment/settlement/security-policy Audit categories map to a five-year class.
3. `PII_ACCESS` maps to the accepted two-year class only for newly created PII access facts after owning features exist;
   no existing row is guessed to be PII-only.
4. `AppendAuditRecordCommand.category` is required. Unknown action/category, missing policy head/version or policy shape
   rolls back the caller transaction; it never falls back to five years, two years or current wall-clock defaults.
5. The selected policy version ID, category, class and computed expiry are immutable Audit evidence.
6. Permission role/claim alone grants nothing. `requireActive()` locks the persistent row in the caller's transaction.
7. Permission revoke/regrant and action authorization serialize on the same grant row. A revoke committed first denies;
   an authorization transaction committed first may finish only inside that already-authorized local transaction.
8. Grant/Audit persistence is atomic. Audit failure leaves no permission change.
9. No application API mutates or deletes AuditRecord; only the internal bounded retention worker deletes due rows.

## Architecture and Transaction Boundaries

### Audit append transaction

`AuditRecordService.appendAll()` stays `Propagation.MANDATORY`. For each distinct command category it reads and locks the
category policy head, loads the exact immutable version, validates category/class/duration shape, computes expiry from the
command `occurredAt`, and inserts Audit rows with version/category/class/expiry. Policy resolution or Audit flush failure
rolls back the caller's business writes. Mixed-category batches snapshot each exact version; no partial list is returned.

S10 seeds policy heads but does not provide activation. A later policy-change plan must update a head under an exclusive
category lock in an audited transaction. The append shared/row lock versus activation exclusive lock is the future
linearization point; existing rows never change version or expiry.

### Permission transaction

`OperatorPermissionGrantTransaction.apply()` remains one local transaction: verified principal/input validation →
permission-scoped advisory lock → grant row transition → append Audit with `SECURITY_AND_PERMISSION` category → flush.
Any DB/Audit/validation failure returns non-success and rolls back. `requireActive()` remains `MANDATORY` and pessimistically
locks the active row so revoke cannot invalidate an already-running authorized transaction halfway through.

### Retention worker transaction

Replace select-then-batch-delete with one bounded claim/delete transaction that orders by
`(retention_expires_at, id)` and uses PostgreSQL row locking with `SKIP LOCKED`. Two workers delete disjoint due rows.
Empty claim is a successful zero result; query/delete failure is thrown, increments failure telemetry and leaves rows for
retry. Appends whose expiry is not due cannot be claimed.

## Alternatives Considered

### A. Normalized immutable policy version + head + Audit snapshot — selected

Provides version ownership, future policy changes and historical reproducibility. It requires one policy lookup per
distinct category in an append batch and a forward migration.

### B. Add only `AuditCategory` and hard-code durations in Kotlin — rejected

Cannot identify the immutable policy used, invites code/default drift and does not satisfy accepted PolicyVersion ownership.

### C. Recalculate every existing expiry from a new category mapping — rejected

An incorrect mapping could shorten financial evidence. Existing exact expiry preservation is safer and auditable.

### D. Split PII Audit into a new table now — rejected for S10

No PII reveal runtime model exists, so table ownership and cross-table query/delete behavior would be speculative.

## Failure Semantics

- Missing/unmapped legacy action aborts migration; it is not assigned a catch-all category.
- Missing/multiple policy head, missing version, invalid duration or class/category mismatch fails Audit append and caller.
- Overflow/date conversion failure is explicit dependency/setup failure, not a capped/default expiry.
- Audit insert/flush failure leaves no permission or business success.
- Permission DB failure is dependency unavailable, not access denied; missing/revoked permission is access denied.
- Concurrent worker lock/contention or delete failure leaves due rows and reports failure; it is not zero deleted.
- No local/in-memory policy or permission fallback exists.

## Data and Migration

After scheduling S10, create exactly one V-next migration chosen from latest main under an acquired ADR-072 lease. The
migration performs this order:

1. Preflight existing `operations_audit_record.action` distinct values against the explicit classification table in the
   migration. Abort if any value is unmapped.
2. Create `operations_retention_policy_version` with immutable version ID, category, class, duration basis/value,
   effective time, actor/evidence and constraints. Add an update/delete rejection trigger.
3. Create `operations_retention_policy_head(category PK, policy_version_id, version)` with FK including category.
4. Seed accepted initial classes/versions: financial five Seoul calendar years, Support Case three years, PII access two
   years, delivery contact 90 days, current location 24 hours and raw Provider webhook seven days. Only financial and PII
   Audit classes are eligible for `operations_audit_record` in S10; later owner Stage plans consume other classes.
5. Add nullable `audit_category`, `retention_class`, `retention_policy_version_id` columns to Audit rows.
6. Backfill every existing row through the explicit action classification to a five-year version while preserving the
   existing `retention_expires_at` byte-for-byte. Verify row counts, no nulls, FK shape and unchanged min/max/checksum
   evidence before setting columns NOT NULL.
7. Add FK/check constraints and `(retention_class, retention_expires_at, id)` worker index. Keep existing append-only unique
   and query indexes.
8. Alter permission column length if required, replace the closed vocabulary check with current nine plus the exact S10
   values below, and prove no existing grant becomes invalid.
9. Do not modify, renumber or checksum-repair V1~V38.

### Exact new permission vocabulary

- Case/query: `SUPPORT_CASE_READ`, `SUPPORT_CASE_WRITE`, `SUPPORT_CASE_ASSIGN`, `SUPPORT_SUBJECT_SEARCH`
- Verification/PII: `SUPPORT_VERIFICATION_MANAGE`, `SUPPORT_PII_REVEAL_REQUEST`,
  `SUPPORT_PII_REVEAL_APPROVE`, `SUPPORT_PII_REVEAL_BASIC`, `SUPPORT_PII_REVEAL_SENSITIVE`,
  `SUPPORT_BREAK_GLASS_REQUEST`
- Action/order/resolution: `SUPPORT_ACTION_REQUEST`, `SUPPORT_ACTION_APPROVE`, `SUPPORT_ACTION_EXECUTE`,
  `SUPPORT_ORDER_READ`, `SUPPORT_ORDER_CANCEL`, `SUPPORT_PICKUP_RESCHEDULE`, `SUPPORT_RESOLUTION_REQUEST`,
  `SUPPORT_RESOLUTION_APPROVE`, `SUPPORT_RESOLUTION_EXECUTE`
- Compensation/profile: `SUPPORT_COMPENSATION_REQUEST`, `SUPPORT_COMPENSATION_APPROVE`,
  `SUPPORT_COMPENSATION_EXECUTE`, `SUPPORT_PROFILE_R1_CHANGE`, `SUPPORT_PROFILE_R2_CHANGE`,
  `SUPPORT_PROFILE_R3_REQUEST`, `SUPPORT_PROFILE_R3_APPROVE`
- Delivery/Operations/Privacy: `SUPPORT_DELIVERY_READ`, `SUPPORT_DELIVERY_INCIDENT_WRITE`,
  `SUPPORT_DELIVERY_CHANGE`, `OPERATIONS_SUPPORT_INVESTIGATION`, `OPERATIONS_LEGAL_HOLD_MANAGE`,
  `OPERATIONS_RETENTION_MANAGE`, `PRIVACY_AUDIT_READ`

These grants are dormant foundations until an owning Stage adds an authorized use case. S10 does not map every value to
`PLATFORM_OPERATOR` or expose a grant HTTP endpoint. The verified offline bootstrap is the only creation/revocation path.

## API and Event Contracts

No Support/Delivery/LegalHold runtime or target endpoint is added. Public changes are Kotlin module APIs only:

- `AppendAuditRecordCommand` requires `AuditCategory`.
- Operations owns closed `AuditCategory`, `RetentionClass` and policy version read/resolve types.
- `OperatorPermission` adds the exact values above; `OperatorPermissionAuthorization` signature stays stable.

No integration event is added. Later Stages must not treat a dormant permission as implemented capability.

## Milestones

1. **Preflight and lease:** update branch from latest main, verify clean scoped worktree, open PR/task inventory and no
   explicit current holder, acquire/record lease, then select V-next after V38 or the then-current last migration.
2. **Domain/API contract:** add category/class/version types and required category to every Audit command call site; unit
   test closed mappings and five-year regression before persistence changes.
3. **Migration/persistence:** implement immutable policy/head schema, fail-closed legacy classification/backfill, Audit
   snapshot columns and permission vocabulary in one forward migration.
4. **Transaction slice:** resolve policy in mandatory Audit append; keep permission change + Audit atomic; implement
   concurrent retention chunk claim.
5. **Verification/documentation:** Testcontainers migration/boundary/revocation tests, full build/Modulith/docs, actual
   migration and lease evidence, then move S10 and update successor/orchestration metadata atomically.

## Required Tests

### Existing tests to extend

- `AuditRecordTest`: financial five-year leap/calendar regression; policy snapshot; missing/invalid policy rollback;
  sensitive summary and append-only behavior.
- `OperatorPermissionIntegrationTest`: every new enum accepted by DB/bootstrap, undeclared value rejected, grant/revoke/
  regrant, role/claim-only denial and Audit rollback.

### New test classes

- `AuditRetentionPolicyMigrationTest`: fresh PostgreSQL migration, explicit action coverage, unmapped-action abort fixture,
  existing expiry unchanged, NOT NULL/FK/check/immutable trigger/index and current permission check.
- `AuditRetentionPolicyIntegrationTest`: category-specific `-1ns/at/+1ns`, financial 5y versus PII 2y, missing head/version
  fail-closed, multi-category append atomicity and worker failure/retry.
- `AuditPermissionBoundaryConcurrencyTest`: two retention workers claim disjoint chunks; append versus due scan; permission
  authorization versus revoke/regrant; policy-head activation lock contract (test-only transaction until activation exists).

All persistence/migration/concurrency tests use PostgreSQL Testcontainers, injected `Clock` and deterministic IDs where
needed. H2/mock-only tests do not satisfy these gates.

## Validation Commands

- `./gradlew test --tests '*AuditRecordTest' --tests '*AuditRetentionPolicy*' --tests '*OperatorPermissionIntegrationTest' --tests '*AuditPermissionBoundaryConcurrencyTest'`
- `./gradlew test --tests '*ModularityTests'`
- `./gradlew spotlessCheck test`
- `./scripts/verify-docs.sh`
- `git diff --check`

Before completion also run:

- `rg -l 'AppendAuditRecordCommand\\(' src/main/kotlin`

Review the output against the explicit call-site inventory; no caller may remain unclassified. Structural validation uses
the repository's actual `*ModularityTests` class, not nonexistent generic architecture-test names.

### Baseline stabilization evidence (2026-08-10~11)

- `for attempt in {1..10}; do ./gradlew test --tests '*PaymentMethodControllerIntegrationTest' --rerun-tasks || exit $?; done`
  — exit 0; ten consecutive `BUILD SUCCESSFUL` runs.
- An initial 2026-08-11 `./gradlew spotlessCheck test --rerun-tasks` exited 1 before Testcontainers could start
  (608 tests completed; 405 failed) because `docker info` also exited 1 with no Docker Desktop socket. This is recorded
  as an unavailable validation dependency, not a passing run or a cursor-test result.
- `for attempt in {1..2}; do ./gradlew spotlessCheck test --rerun-tasks || exit $?; done` — exit 0; two consecutive
  `BUILD SUCCESSFUL` runs after Docker became available (7m 29s and 7m 3s).
- `./gradlew test --tests '*ModularityTests' --rerun-tasks` — exit 0, `BUILD SUCCESSFUL in 12s`.
- `./scripts/verify-docs.sh` — exit 0; target/runtime 34 paths/37 operations, 91 schemas; 33 policies, 91 ADRs,
  223 Markdown files and 35 ExecPlans validated.
- `git diff --check` — exit 0.

## Observability

Extend current Audit retention metrics only with closed `retention_class`/outcome labels after cardinality review. Record
deleted count, oldest due age, failure count and optionally lock/claim duration. Permission metrics use closed permission
and outcome. Actor, Case, target, policy evidence, correlation, reason and any PII are forbidden metric labels. Logs keep
stable class/outcome/count only and do not dump failed commands or policy evidence.

## Documentation Updates

- Business Policy/ADR-089 implementation evidence without changing accepted periods
- aggregate invariants, transaction boundaries, authorization matrix and support role matrix
- migration inventory, actual S10 Progress/Decision Log/Outcomes and program orchestration
- no target/runtime OpenAPI change because runtime Support endpoint is a non-goal
- replace planned operational text with an actual Audit/permission runbook only after concrete tables/metrics/commands exist

## Progress

- [x] Current Audit/permission code, schema, migrations and tests inventoried
- [x] Data model alternatives, backfill safety, exact permissions and transaction boundaries specified
- [x] S00 dependency completed; deterministic cursor tampering validation and full regression passed, readiness true
- [ ] Scheduling decision and migration lease acquisition
- [ ] Implementation not started

## Surprises & Discoveries

- Existing Audit writes span seven contexts and more than twenty call sites; adding a default category would hide missing
  classification, so compilation must force every caller to choose.
- Existing `OperatorPermissionAuthorization` already has the correct caller-local pessimistic lock needed for immediate
  revocation semantics.
- Current retention worker does not claim due rows with `SKIP LOCKED`; S10 must make concurrent worker behavior explicit.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-10 | Accepted input | existing financial Audit stays five Seoul calendar years | BR-30/SP-13 | Business Policy, ADR-089 |
| 2026-08-10 | Implementation plan | normalized immutable policy/head and Audit snapshot; preserve legacy expiry | accepted versioned retention model without shortening | this plan |
| 2026-08-10 | Implementation plan | required Audit category with no default | fail closed on unclassified callers | this plan |
| 2026-08-10 | Implementation plan | offline verified bootstrap remains the only grant mutation path | no premature Support admin endpoint | ADR-069, this plan |
| 2026-08-10 | Scheduling | Analytics is not a direct dependency | no schema/output consumption | ADR-072, rejected ADR-091 |
| 2026-08-11 | Validation gate | deterministic signature tampering helper replaces final Base64URL character mutation | padding-only final-character changes can decode to identical HMAC bytes | PaymentMethodControllerIntegrationTest |

## Outcomes & Retrospective

Not implemented. Current code still applies one five-year Audit policy and has nine permissions. No migration, enum,
runtime endpoint or test class named above has been created. `Implementation-Ready=true` means the next Goal has a
self-contained plan and the baseline validation passed; it is not permission to execute. Do not mark complete without
actual lease/migration evidence, fresh PostgreSQL migration tests and all validation results.

## Revision Notes

- 2026-08-10: replaced placeholder shell with current-code-based executable S10 plan and removed fake Analytics dependency.
- 2026-08-11: stabilized the PaymentMethod signed-cursor tampering test and recorded successful targeted/full regression;
  no migration lease, number reservation or implementation work was performed.
