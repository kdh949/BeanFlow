# S10 Audit retention classification과 Support permission foundation을 구현한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s00-documentation-contracts.md`
> **Completed-At:** `2026-08-11`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 2026-08-11T03:28:12+09:00에 최신 local/origin `main` `ad07ff3`에서
`feature/s10-retention-audit-permission` branch를 만들고, open PR 0개와 다른 active BeanFlow migration task
부재를 확인한 뒤 repository-wide migration-writer lease를 획득했다. 최신 Flyway는 V38이며 S10은 V39를
사용했다. 구현과 fresh PostgreSQL migration, category boundary, permission/worker concurrency, full suite와
문서 검증을 완료해 lease를 release한다. 후속 S20에는 lease나 Flyway 번호가 승계되지 않는다.

## Purpose / Big Picture

현재 모든 `AuditRecord`에 적용되는 서울 달력 5년 retention을 약화하지 않으면서, Operations-owned
`AuditCategory`, `RetentionClass`, immutable `RetentionPolicyVersion` foundation을 추가한다. 동시에 후속
Support Stage가 JWT role fallback 없이 사용할 closed `OperatorPermission` vocabulary를 기존 persistent
grant/revoke/regrant 경계에 추가한다.

완료 후 기존 Audit row는 원래 `retention_expires_at`을 그대로 유지하고 명시적 category/class/policy version을
가진다. 신규 Audit command는 category를 필수로 제공하며 Operations가 current immutable policy version을
같은 transaction에서 snapshot한다. S10은 SupportCase, reveal, action, API 또는 retention deletion automation을
구현하지 않는다.

## Initial State

### Initial code and schema

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
- **RetentionPolicyVersion:** immutable mapping from category to class and exact duration rule selected at append time;
  `PRESERVE_STORED_EXPIRY` is a legacy-classification rule and is never an append snapshot.
- **Retention provenance:** `APPEND_SNAPSHOT` records a new application append; `LEGACY_MIGRATION_CLASSIFICATION`
  records V39 classification without claiming historical policy selection; `DATABASE_COMPATIBILITY_SNAPSHOT` records a
  temporary V38 binary insert classified by the V39 DB bridge.
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
5. The selected policy version ID, category, class, provenance and computed expiry are immutable Audit evidence.
   Only `APPEND_SNAPSHOT` is evidence of the append-time policy decision; legacy expiry is never recomputed.
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

획득한 ADR-072 lease 아래 V39 하나를 추가했고, migration은 다음 순서로 구현됐다.

1. Preflight existing `operations_audit_record.action` distinct values against the explicit classification table in the
   migration. Abort if any value is unmapped.
2. Create `operations_retention_policy_version` with immutable version ID, category, class, duration basis/value,
   effective time, actor/evidence and constraints. Add an update/delete rejection trigger.
3. Create `operations_retention_policy_head(category PK, policy_version_id, version)` with FK including category.
4. Seed accepted current classes/versions: financial five Seoul calendar years, Support Case three years, PII access two
   years, delivery contact 90 days, current location 24 hours and raw Provider webhook seven days. Seed separate financial
   `PRESERVE_STORED_EXPIRY` versions only for V39 legacy classification; they are not policy heads. Only financial and PII
   Audit classes are eligible for `operations_audit_record` in S10; later owner Stage plans consume other classes.
5. Add nullable `audit_category`, `retention_class`, `retention_policy_version_id`, `retention_provenance` columns to Audit
   rows. A `BEFORE INSERT` DB bridge fills all four only for a V38-style all-null insert with a known action/current head;
   partial, unknown or invalid inputs fail closed.
6. Backfill every existing row through the explicit action classification to the legacy `PRESERVE_STORED_EXPIRY` version,
   preserving existing `retention_expires_at` byte-for-byte and marking `LEGACY_MIGRATION_CLASSIFICATION`. Verify row
   counts, no nulls and unchanged min/max/checksum evidence.
7. Add new-row-enforced FK/check constraints as `NOT VALID`; defer physical `NOT NULL`, constraint validation and DB bridge
   population removal to a separately leased contract migration after V38 fleet drain and representative lock measurement.
   Retain V4 `(retention_expires_at, id)`, whose column order matches the worker query, without claiming planner selection;
   do not add a class-leading index that cannot serve its purge order.
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
  sensitive key plus raw email/phone/address summary value and raw-PII reason rejection; append-only behavior.
- `OperatorPermissionIntegrationTest`: every new enum accepted by DB/bootstrap, undeclared value rejected, grant/revoke/
  regrant, role/claim-only denial and Audit rollback.

### New test classes

- `AuditRetentionPolicyMigrationTest`: fresh PostgreSQL migration, explicit action coverage, unmapped-action abort fixture,
  legacy preserve-expiry provenance, V38-style compatibility insert, existing expiry unchanged, FK/check/immutable trigger
  and current permission check.
- `AuditRetentionPolicyIntegrationTest`: PostgreSQL timestamp precision에 맞춘 category별 `-1µs/at/+1µs`, financial 5y versus PII 2y, missing head/version
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

### S10 implementation evidence (2026-08-11)

- contract-first `AuditRecordContractTest`는 최초 `AuditCategory` class 부재로 exit 1이었고 API 추가 뒤 exit 0.
- 초기 `AuditRecordTest`는 V39 column 부재로 exit 1, 첫 boundary run은 PostgreSQL/JDBC timestamp의 nanosecond
  반올림 때문에 exit 1이었다. boundary를 database precision인 1 microsecond로 고정한 뒤 targeted suite가
  exit 0이었다.
- offline permission CLI targeted run은 retention policy bootstrap context 부재로 exit 5였다. standalone
  Entity/Repository scan과 policy service import를 추가한 뒤 exit 0이었다.
- fresh migration fixture는 `JdbcTemplate`의 `Instant` parameter inference 실패로 exit 1이었다. 명시적
  `Timestamp` binding으로 수정한 뒤 exit 0이었다.
- `./gradlew test --tests '*AuditRecordTest' --tests '*AuditRetentionPolicy*' --tests
  '*OperatorPermissionIntegrationTest' --tests '*AuditPermissionBoundaryConcurrencyTest'` — exit 0,
  `BUILD SUCCESSFUL in 32s`.
- `./gradlew spotlessCheck test` — exit 0, `BUILD SUCCESSFUL in 7m 20s`. standalone bootstrap context shutdown에서
  기존 JPA event-publication cleanup warning 하나가 있었으나 test/build failure는 없었다.
- `./gradlew test --tests '*ModularityTests' --rerun-tasks` — exit 0, `BUILD SUCCESSFUL in 11s`.
- 마지막 non-null assertion 정리 뒤 sandbox run은 Gradle wrapper lock 접근 거부로 exit 1이었다. 동일한
  `./gradlew spotlessCheck test --tests '*AuditRecordTest' --tests '*AuditRetentionPolicy*' --tests
  '*OperatorPermissionIntegrationTest' --tests '*AuditPermissionBoundaryConcurrencyTest' --tests
  '*ModularityTests' --rerun-tasks`를 승인된 Gradle cache 접근으로 재실행해 exit 0,
  `BUILD SUCCESSFUL in 44s`를 확인했다.
- 여섯 Audit category별 독립 `-1µs/at` boundary fixture를 추가한 뒤
  `./gradlew spotlessApply test --tests '*AuditRecordTest' --tests '*AuditRetentionPolicy*' --tests
  '*OperatorPermissionIntegrationTest' --tests '*AuditPermissionBoundaryConcurrencyTest' --tests
  '*ModularityTests' --rerun-tasks` — exit 0, `BUILD SUCCESSFUL in 42s`.
- 최종 `./gradlew spotlessCheck test --rerun-tasks` — exit 0, `BUILD SUCCESSFUL in 8m 24s`. standalone
  bootstrap context 종료 시 `DefaultJpaEventPublication` unknown-entity cleanup WARN 1건과 한 test context의
  unfinished publication INFO가 있었지만 test/build failure는 없었다.
- completion move 직후 첫 `./scripts/verify-docs.sh`는 `Completed-At` timestamp 형식 때문에 exit 1이었다.
  canonical ISO date `2026-08-11`로 수정한 뒤 재실행해 exit 0: target/runtime 34 paths/37 operations,
  91 schemas, 33 business policies, 91 ADRs, 225 Markdown files와 36 ExecPlans를 검증했다.
- 최종 `git diff --check` — exit 0, output 없음.
- 최종 `./gradlew spotlessCheck` — exit 0, `BUILD SUCCESSFUL in 454ms`.
- 최종 `rg -l 'AppendAuditRecordCommand\\(' src/main/kotlin | sort | wc -l` — exit 0, 25 files
  (contract definition 1 + classified production callers 24). Migration status는 untracked V39 하나뿐이며
  inventory tail은 V35~V39다.

### PR #51 review remediation validation (2026-08-11)

- `./gradlew test --tests io.github.kdh949.beanflow.operations.internal.AuditRecordTest --tests
  io.github.kdh949.beanflow.operations.internal.AuditRetentionPolicyMigrationTest` — initial exit 1 as intended:
  raw PII summary/reason, legacy provenance and V38 compatibility regression tests failed against the reviewed code.
- After implementation, the same command — exit 0, `BUILD SUCCESSFUL in 23s`.
- The first full-suite rerun exposed three `PointAdjustmentIntegrationTest` regressions: the initial card pattern
  falsely classified a monetary `Long`/numeric UUID fragment. It was replaced with whole-value digit normalization plus
  Luhn validation; raw card value `4242 4242 4242 4242` remains a failing Audit input.
- `./gradlew spotlessApply test --tests io.github.kdh949.beanflow.operations.internal.AuditRecordTest --tests
  io.github.kdh949.beanflow.loyalty.internal.PointAdjustmentIntegrationTest` — exit 0,
  `BUILD SUCCESSFUL in 18s`.
- `./gradlew test --tests '*AuditRecordTest' --tests '*AuditRetentionPolicy*' --tests
  '*OperatorPermissionIntegrationTest' --tests '*AuditPermissionBoundaryConcurrencyTest' --tests
  io.github.kdh949.beanflow.loyalty.internal.PointAdjustmentIntegrationTest` — exit 0; eight XML results contained no
  `failure` or `error` node.
- `./gradlew spotlessCheck test --rerun-tasks` — completed after full Testcontainers suite; all 140 XML results contained
  no `failure` or `error` node. Follow-up `./gradlew spotlessCheck test` — exit 0,
  `BUILD SUCCESSFUL in 770ms` (all 9 actionable tasks up-to-date).
- `./gradlew test --tests '*ModularityTests'` — exit 0, `BUILD SUCCESSFUL in 3s`.
- `./scripts/verify-docs.sh` — exit 0: target/runtime 34 paths/37 operations, 91 schemas, 33 business policies, 91 ADRs,
  225 Markdown files and 36 ExecPlans validated.
- `git diff --check` — exit 0, output 없음. Representative production-like `EXPLAIN (ANALYZE, BUFFERS)` and V39
  duration/lock-wait measurement are **Not run**; they remain a mandatory evidence gate for the separate contract
  migration and are not claimed here.

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
- [x] Scheduling decision and migration lease acquisition — 2026-08-11T03:28:12+09:00, branch
  `feature/s10-retention-audit-permission`, base `ad07ff3`, latest Flyway V38, selected V39
- [x] Required Audit category/class/version API and every production caller classification implemented
- [x] V39 immutable policy/head, provenance-distinguished legacy expiry-preserving backfill, fail-closed compatibility
  bridge and 42 permissions implemented
- [x] fail-closed append, concurrent worker claim and permission grant/revoke boundaries implemented
- [x] required PostgreSQL, full suite, Modulith, formatting and documentation validation completed
- [x] S20 direct successor plan and orchestration/readiness metadata updated; S10 migration lease released

## Surprises & Discoveries

- Existing Audit writes span seven contexts and more than twenty call sites; adding a default category would hide missing
  classification, so compilation must force every caller to choose.
- Existing `OperatorPermissionAuthorization` already has the correct caller-local pessimistic lock needed for immediate
  revocation semantics.
- Current retention worker does not claim due rows with `SKIP LOCKED`; S10 must make concurrent worker behavior explicit.
- one shared `PaymentResultTransaction` action family contained both payment facts and reservation confirm/release facts;
  action/category mapping therefore keeps payment facts financial and classifies reservation facts as order/fulfillment.
- PostgreSQL `timestamptz` and JDBC preserve microseconds rather than arbitrary nanoseconds in these tests; the exact
  retention boundary fixture uses one microsecond on each side.
- standalone permission bootstrap now depends on retention policy persistence because its Audit append must resolve an
  immutable policy version. Its minimal application context must scan/import that dependency explicitly.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-10 | Accepted input | existing financial Audit stays five Seoul calendar years | BR-30/SP-13 | Business Policy, ADR-089 |
| 2026-08-10 | Implementation plan | normalized immutable policy/head and Audit snapshot; preserve legacy expiry | accepted versioned retention model without shortening | this plan |
| 2026-08-10 | Implementation plan | required Audit category with no default | fail closed on unclassified callers | this plan |
| 2026-08-10 | Implementation plan | offline verified bootstrap remains the only grant mutation path | no premature Support admin endpoint | ADR-069, this plan |
| 2026-08-10 | Scheduling | Analytics is not a direct dependency | no schema/output consumption | ADR-072, rejected ADR-091 |
| 2026-08-11 | Validation gate | deterministic signature tampering helper replaces final Base64URL character mutation | padding-only final-character changes can decode to identical HMAC bytes | PaymentMethodControllerIntegrationTest |
| 2026-08-11 | Migration lease | S10 acquired the repository-wide writer lease and selected V39 | local/origin main `ad07ff3`; open PR 0; current S10 is the only active BeanFlow task; other worktrees/tasks have no acquisition record | ADR-072, this plan |
| 2026-08-11 | Migration safety | persistent immutable action/category mapping with abort-on-unmapped preflight | a default category would hide new callers and could weaken evidence retention | V39, this plan |
| 2026-08-11 | Classification | payment results remain financial; reservation confirmations/releases are order/fulfillment | one transaction emits facts with different business purposes | `PaymentResultTransaction`, V39 |
| 2026-08-11 | Boundary fixture | use ±1 microsecond around PostgreSQL due time | database/JDBC precision rounds nanosecond-only differences | integration tests |
| 2026-08-11 | Completion | release S10 lease and activate only the S20 detailed plan | V39/full validation complete; successor must acquire its own lease | completed S10, active S20 |
| 2026-08-11 | Review remediation | preserve-expiry version/provenance, DB compatibility bridge and raw-PII value/reason rejection | historical policy evidence and rolling V38 coexistence must not weaken retention or privacy invariants | PR #51 review remediation, V39, this plan |

## Outcomes & Retrospective

V39 preserves every legacy Audit expiry with a dedicated `PRESERVE_STORED_EXPIRY` policy and
`LEGACY_MIGRATION_CLASSIFICATION`, so a policy created by V39 is not represented as a historical append decision. New
records snapshot required category/class/immutable policy version/provenance and keep financial/order/settlement/security/
policy Audit at five Seoul calendar years while PII access Audit uses two. Summary values and free-text reasons that match
raw email/phone/address/payment-card patterns fail before persistence. The DB compatibility bridge supports only all-null
V38-style inserts during rollout; contract hardening is intentionally deferred to a separately leased migration after
fleet drain and measured lock behavior. Audit/policy failure rolls back the privileged caller, and concurrent retention
workers claim disjoint due rows with `SKIP LOCKED`. Operations now recognizes 42 persistent permissions (existing 9 plus
S10 33) with the existing verified offline grant/revoke/regrant and row-lock authorization semantics.

No SupportCase, search, PII reveal, action, Delivery, LegalHold, multi-component deletion or Support runtime OpenAPI was
added. Initial non-Audit owner policy versions are seeded foundations only. Full validation passed, the S10 migration
lease is released, and the direct S20 successor plan is active but not implementation-ready until exact Case policy is
Accepted; it has no lease or reserved migration number.

## Revision Notes

- 2026-08-10: replaced placeholder shell with current-code-based executable S10 plan and removed fake Analytics dependency.
- 2026-08-11: stabilized the PaymentMethod signed-cursor tampering test and recorded successful targeted/full regression;
  no migration lease, number reservation or implementation work was performed.
- 2026-08-11: acquired the S10 migration-writer lease from current main and selected V39 after execution-time inventory.
- 2026-08-11: implemented V39 Audit retention classification and 42-permission foundation, completed PostgreSQL/full/
  structure/document validation, released the lease and authored the S20 direct successor from actual outcomes.
- 2026-08-11: PR #51 review remediation revalidated the V39 writer lease at `2026-08-11T05:34:24+0900`: PR #51 on
  `feature/s10-retention-audit-permission` was the only open PR; the four other local worktrees were separate feature or
  detached branches with no S10 lease record. No direct successor readiness changed: S20 remains
  `Implementation-Ready: false` and does not inherit V39's future contract migration. The remediation adds raw-PII
  value/reason fail-closed tests, legacy provenance, a V38 compatibility bridge, and explicit permission/concurrency
  test intent; its final validation evidence is recorded below before merge.
