# 일반 포인트 적립 정책과 주문 snapshot foundation을 만든다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md`, `docs/exec-plans/completed/customer-order-cancellation-12-partial-refund-allocation-and-restoration.md`
> **Completed-At:** `2026-08-01`

이 ExecPlan은 `.agent/PLANS.md`를 따르며 승인된
[`ordinary-point-accrual-policy-management` spec](../../specs/ordinary-point-accrual-policy-management.md)을
구현한다.

## Purpose / Big Picture

Platform Operator가 일반 포인트 적립 정책을 GLOBAL 기본값과 STORE override별 append-only
version으로 조회·변경하게 한다. 정책 write와 Order 생성의 commit 순서로 미래 적용 경계를
선형화하고, 각 신규 Order는 계산 결과를 immutable snapshot으로 저장한다. rollout 이전 Order는
명시적 `LEGACY_NOT_APPLICABLE` source로 남겨 최초 정책을 소급하지 않는다.

이 plan이 완료되면 Plan 13은 live policy 또는 pricing 재계산 없이 typed Ordering boundary에서
snapshot을 읽어 `ACCRUAL`, refund `RECOVERY`와 pending offset만 구현할 수 있다.

## Current State

- Operations에는 expired-benefit policy의 immutable version/head, CAS, idempotency, audited read/write와
  permission bootstrap 선례가 있다.
- permission DB vocabulary는 V13의 네 값으로 닫혀 있고 일반 적립 read/write grant는 없다.
- 일반 적립 policy table, owner boundary, API, initial GLOBAL version과 startup precheck가 없다.
- Ordering Order 생성에는 immutable pricing line이 있지만 ordinary-accrual source/header/unit snapshot이
  없다.
- Merchant에는 Store가 있지만 Store 존재 확인만 위한 public boundary가 없다. Brand Aggregate와
  issuer registry는 없다.
- latest committed migration은 현재 V15다. 실제 번호는 ADR-072 lease를 얻고 latest main을 다시
  확인한 뒤 정한다.
- BR-10/20과 ADR-069/073/074가 scope, lifecycle, vocabulary, bootstrap, legacy activation과 failure
  semantics를 확정했다.

## Definitions

- **GLOBAL policy:** 모든 Store의 필수 기본 ordinary-accrual policy.
- **STORE override:** 한 `storeId`에만 적용되는 explicit policy.
- **INHERIT_GLOBAL:** STORE history를 보존하면서 future Order selection을 current GLOBAL로 되돌리는
  value-free version state.
- **Policy selection linearization:** Order가 STORE scope lock과 head를 읽어 immutable version을
  선택하는 local transaction 경계.
- **LEGACY_NOT_APPLICABLE:** migration 이전 Order가 최초 policy를 소급 받지 않음을 증명하는 source.
- **SNAPSHOTTED:** 신규 Order의 complete immutable policy/calculation source.

## Scope

### In Scope

- Operations ordinary-accrual version/head Aggregate, persistence constraints와 immutable trigger
- GLOBAL/STORE selector, scope locks, expected-version write와 HTTP idempotency
- 별도 `POINT_ACCRUAL_POLICY_READ|WRITE` permission vocabulary와 enforcement
- audited current/global/store-head/history query, signed cursor pagination
- GLOBAL/STORE write API와 `INHERIT_GLOBAL` transition
- verified OIDC initial GLOBAL offline bootstrap, Gradle task, runbook와 normal startup precheck
- Merchant Store existence public boundary for operator store-scoped API
- Ordering legacy source backfill, new Order policy/unit snapshot, calculator와 typed read boundary
- target OpenAPI, API/security/transaction docs와 focused/full validation

### Non-goals

- 실제 frontend/operator UI project
- Plan 13의 Loyalty `ACCRUAL`, `RECOVERY`, PointRecoveryPending과 Payment eligibility work
- `PointsAccruedV1` publication, point-account HTTP read와 Settlement consumption
- BRAND applicability scope, Store→Brand relation, issuer registry/lookup
- scheduled policy activation, bulk Store updates, delete/rollback-to-old-ID endpoint
- legacy Order에 policy, points 또는 recovery를 소급 backfill

## Business Rules and Invariants

- GLOBAL head는 bootstrap 뒤 정확히 하나이며 완전한 GLOBAL `OVERRIDE` version을 가리킨다.
- STORE no-head 또는 `INHERIT_GLOBAL`은 current GLOBAL을 선택한다. `OVERRIDE`만 자체 값을 사용한다.
- version은 append-only이고 head는 같은 scope의 newly inserted version으로만 이동한다.
- bps는 0..10000, rounding은 FLOOR/HALF_UP, expiry는 completion 기준 exact duration 또는
  Asia/Seoul calendar days 1..3650, issuer는 PLATFORM/BRAND/STORE와 literal 1..240 reference다.
- gross는 overflow 없이 한 번 반올림하고 unit allocation 합은 gross와 같다.
- policy update 뒤 이미 저장된 Order snapshot과 legacy marker를 수정하지 않는다.
- 모든 기존 Order는 migration에서 정확히 한 LEGACY source, 모든 신규 Order는 create transaction에서
  정확히 한 complete SNAPSHOTTED source를 가진다.
- missing policy/source/snapshot, grant/Audit/store dependency failure는 default, cached, zero 또는 legacy
  fallback이 아니다.

## Architecture and Transaction Boundaries

### Operations owner model

Operations public API는 immutable selection DTO, audited query/write command만 노출한다. Entity와 head
repository는 internal이다. Controller는 Application Service만 호출한다.

Selector lock order:

```text
STORE scope advisory lock
-> STORE head FOR UPDATE when present
-> GLOBAL head FOR UPDATE only for no-head or INHERIT_GLOBAL
-> immutable version read
```

Write lock order:

```text
active POINT_ACCRUAL_POLICY_WRITE grant row
-> actor + Idempotency-Key advisory lock
-> STORE scope advisory lock when applicable
-> scope head FOR UPDATE when present
-> immutable version INSERT
-> head INSERT or expected-version update
-> AuditRecord flush
-> commit
```

Reads lock the active READ grant, query projection and append one access Audit in the same Operations
transaction. Store-specific operator calls validate Store existence through Merchant public API after
authorization and before policy mutation. Store lookup failure is 404/503, not a phantom policy success.

### Bootstrap and startup

`ordinary-accrual-policy-bootstrap` is a separate non-web Spring context. It applies Flyway, verifies the same
short-lived OIDC workload trust used by permission bootstrap, and inserts initial GLOBAL version/head/Audit in
one transaction. Normal application startup runs a post-Flyway integrity precheck and fails if GLOBAL is absent,
incomplete or unreadable. The bootstrap context and explicit test profile exclude only this first-initialization
gate, not identity, Flyway, constraints or Audit.

### Ordering snapshot

Order creation computes pricing and completes required owner reservations, then calls the selector inside the
existing local transaction. It calculates and persists accrual source/header/units before idempotent response
completion. Order, reservations, benefit-only confirmation, Audit, snapshot and stored response all commit or
rollback together.

The typed Ordering boundary returns only immutable source state and a fully validated snapshot. Plan 13 consumer
can distinguish LEGACY terminal not-applicable from corrupted/missing SNAPSHOTTED data without reading current
policy or Order pricing.

## Alternatives Considered

- Policy/API/snapshot을 Plan 13 recovery와 한 plan에 구현: migration과 review blast radius가 크고 사용
  가능한 owner boundary와 Loyalty debt ledger의 완료 증거가 섞이므로 분리한다.
- migration에 initial GLOBAL 값 seed: 제품/환경 값을 source constant로 만들고 verified provenance가
  없어 제외한다.
- policy API가 first GLOBAL을 생성: 초기화 전 정상 server와 Order failure window를 만들어 제외한다.
- 기존 Order에 initial policy backfill: 이후 Order에만 적용한다는 요구와 재현성을 깨므로 제외한다.
- optional STORE row 부재를 lock하지 않음: first override와 Order lookup이 commit 순서를 어길 수 있어
  store-scope advisory lock을 사용한다.

## Failure Semantics

- bootstrap invalid input/identity/already initialized/dependency failure는 closed non-zero result와 no partial
  write다.
- normal startup의 GLOBAL missing/mismatch/DB failure는 startup failure다.
- API missing/revoked grant는 403, validation은 400, Store 없음은 404, stale expected/idempotency conflict는
  409, owner/DB/Audit failure는 503이다.
- read Audit 실패는 body를 반환하지 않고 write Audit 실패는 version/head를 rollback한다.
- Order selector/calculator/snapshot write failure는 전체 Order create를 rollback하고 503으로 실패한다.
- 신규 snapshot 누락을 LEGACY로 추측하지 않고, legacy source에 current policy를 합성하지 않는다.

## Data and Migration

ADR-072 migration-writer lease를 얻은 뒤 latest main의 마지막 Flyway 번호 다음 하나의 forward migration을
선택한다. applied V13 permission migration과 기존 migration은 수정하지 않는다.

Migration은 다음을 원자적으로 정의한다.

- Operations policy version sequence/table, GLOBAL/STORE head와 scope FK
- scope/state별 conditional field CHECK, HTTP idempotency unique와 immutable mutation trigger
- operator permission closed CHECK에 POINT_ACCRUAL_POLICY_READ/WRITE 추가
- Ordering 1:1 accrual source/header와 conceptual-unit snapshot table/constraints
- migration 시점 모든 existing Order의 LEGACY_NOT_APPLICABLE source insert와 cardinality precheck

GLOBAL policy/head와 permission grant는 migration에서 seed하지 않는다. rollback migration이나 checksum
repair를 만들지 않는다.

## API and Event Contracts

Target OpenAPI에 다음 backend paths를 추가한다.

- `GET /operations/policies/ordinary-point-accrual/global`
- `GET /operations/policies/ordinary-point-accrual/global/versions`
- `GET /operations/policies/ordinary-point-accrual/stores`
- `GET /operations/policies/ordinary-point-accrual/stores/{storeId}`
- `GET /operations/policies/ordinary-point-accrual/stores/{storeId}/versions`
- `PATCH /operations/policies/ordinary-point-accrual/global`
- `PATCH /operations/policies/ordinary-point-accrual/stores/{storeId}`

GET은 READ grant, X-Access-Reason과 Audit commit gate, list/history는 signed cursor default 20/max 100을
사용한다. PATCH는 WRITE grant, Idempotency-Key, reason과 expected current semantics를 사용한다.
GLOBAL response와 Store effective response/history는 conditional policy values와 actor/effective metadata를
노출하고 internal hash/idempotency/evidence는 제외한다.

당시 source 계약 파일은 deployment evidence 전에는 변경하지 않는다. public integration event는
추가하거나 변경하지 않는다.

## Milestones and Implementation Order

### Milestone 1 — execution gate and pure contracts

- migration-writer lease, latest main/migration과 dirty-worktree ownership을 확인한다.
- pure policy value/calculator, scope/state DTO와 exact canonical hashing tests를 먼저 만든다.
- spec/ADR와 불일치가 발견되면 code 전에 docs를 갱신한다.

Checkpoint: pure tests demonstrate all rate/rounding/expiry/unit allocation boundaries without persistence.

### Milestone 2 — schema, persistence, bootstrap and startup gate

- one forward migration과 JPA repositories/entities를 구현한다.
- verified offline bootstrap, Gradle task, terminal output와 runbook을 구현한다.
- normal server precheck와 explicit test setup을 구현한다.

Checkpoint: PostgreSQL tests prove immutability, constraints, legacy marker backfill, bootstrap atomicity and
startup failures.

### Milestone 3 — owner services, permission and Store validation

- permission enum/DB vocabulary, Merchant Store boundary와 Operations selector를 구현한다.
- current/history query, signed cursor, expected-version/idempotent write와 Audit를 구현한다.
- absence/create and GLOBAL/STORE concurrency tests를 구현한다.

Checkpoint: application tests prove exact selection/version behavior and all permission/Audit failure paths.

### Milestone 4 — operator HTTP/OpenAPI vertical slice

- Controller/request/response mapping과 seven target paths를 구현한다.
- MockMvc/REST Docs/OpenAPI contract tests와 authorization matrix/API docs를 갱신한다.

Checkpoint: target contract and runtime behavior agree; 당시 deployment 표기 OpenAPI remains unchanged.

### Milestone 5 — Ordering snapshot integration

- Order create calculator/source/header/unit persistence와 typed read boundary를 구현한다.
- snapshot failure rollback, policy/order race, benefit-only/external-payable and immutable replay tests를
  구현한다.

Checkpoint: policy change never alters prior snapshot and every committed new Order has one complete source.

### Milestone 6 — repository validation and handoff

- focused, security, Testcontainers, Modulith and full build를 실행한다.
- docs/OpenAPI verifier와 diff를 검토하고 secret/personal/generated residue를 제거한다.
- plan을 completed로 이동하고 Plan 13 dependency/readiness를 actual outcome evidence로 갱신한다.

Checkpoint: all required evidence is recorded; unrun checks are explicitly `Not run`.

### Parallelism

Milestone 1 pure calculator tests와 API schema drafting은 독립적일 수 있지만 schema vocabulary를 공유한다.
Milestone 2 migration/persistence가 owner service의 실제 contract를 고정하므로 Milestone 3~5는 순차
통합한다. Snapshot and API tests may be written alongside their production slice, but migrations, permission
vocabulary and module boundary changes have one owner. No separate migration writer may run concurrently.

## Risks and Mitigations

- **Deadlock/incorrect precedence:** fixed lock order and PostgreSQL concurrency tests.
- **Optional head absence race:** one store-scope advisory key with shared selector/exclusive writer modes.
- **Arithmetic overflow/rounding drift:** pure quotient/remainder or exact wide arithmetic with boundary tests.
- **Startup/bootstrap chicken-and-egg:** isolated non-web bootstrap context applies Flyway and skips only the
  normal GLOBAL precheck.
- **Test context breakage:** explicit test-only complete policy setup; no production profile default.
- **Cross-module coupling:** Merchant existence and Operations selection through narrow public APIs; no Entity
  association or Controller→Repository access.
- **Audit/idempotency data leak:** canonical hash and whitelist summaries; output/log/metric negative assertions.
- **Plan 13 scope collision:** this plan owns policy and snapshot; Plan 13 consumes completed boundary only.

## Required Tests

- pure bps/rounding/expiry/overflow/unit allocation boundary matrix
- policy DB conditional checks, immutable version, scope FK and head cardinality
- legacy migration cardinality and no policy/value backfill
- bootstrap identity/input/first/repeat/DB/Audit/startup gate outcomes
- permission role/grant/revoke/dependency and read/write Audit atomicity
- global/store current/history pagination and cursor scope/filter mismatch
- first/update/reaffirm/inherit/replay/stale/different-payload Store/Global commands
- nonexistent Store and Merchant dependency failure
- first STORE write versus no-head Order selection concurrency
- GLOBAL update versus inherited Store Order selection concurrency
- new Order snapshot atomicity, rollback, hash/source conflict and immutability
- old snapshot unchanged after global/store/inheritance policy changes
- benefit-only and external-payable Order paths
- OpenAPI target/runtime split, REST Docs, Modulith and full regression

## Validation Commands

```bash
./gradlew test --tests '*PointAccrualPolicy*' --tests '*OrderPointAccrualSnapshot*'
./gradlew test --tests '*OperatorPermission*' --tests '*PointAccrualPolicyBootstrap*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

- `beanflow.operations.point_accrual_policy.read.count{endpoint,outcome}`
- `beanflow.operations.point_accrual_policy.change.count{scope,state,outcome}`
- `beanflow.operations.point_accrual_policy.bootstrap.count{outcome}`
- `beanflow.order.point_accrual_snapshot.count{source_state,outcome}`

IDs, rate, validity, issuer reference, actor, reason, token/evidence and idempotency key are not metric tags.

## Documentation Updates

- BR-10/20, ADR-069/073/074 and ADR index
- authorization matrix, transaction boundaries, API conventions and policy traceability
- target OpenAPI only and local contract verifier expectations
- ordinary policy bootstrap runbook
- Plan 13 dependency/current state/readiness and orchestration graph

## Implementation Tasks

아래 task는 dependency 순서다. 각 task는 production/test/docs를 합쳐 최대 다섯 파일을 직접 변경한다.
예상 파일을 합쳐야 할 때는 책임이 같은 경우에만 합치고, 여섯 번째 파일이 필요하면 다음 task로
분리한 뒤 이 목록을 먼저 갱신한다.

- [x] **Task 1: pure policy vocabulary와 계산기**
  - Acceptance: scope/state/rate/rounding/issuer/expiry immutable values가 invalid combination을
    생성할 수 없고 gross/expiry/unit allocation이 BR-10 경계에서 정확하다.
  - Verify: `./gradlew test --tests '*PointAccrualPolicyCalculatorTest'`
  - Files:
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/api/OrdinaryPointAccrualPolicyOperations.kt`
    - `src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderPointAccrualCalculator.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderPointAccrualCalculatorTest.kt`

- [x] **Task 2: forward migration과 PostgreSQL constraint evidence**
  - Acceptance: version/head/source/snapshot schema, permission vocabulary, immutable trigger와 모든 existing
    Order legacy marker가 one forward migration에 있고 GLOBAL/grant seed는 없다.
  - Verify: `./gradlew test --tests '*OrdinaryPointAccrualPolicyMigrationTest'`
  - Files:
    - `src/main/resources/db/migration/V<next>__create_ordinary_point_accrual_policy_and_snapshot.sql`
    - `src/test/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyMigrationTest.kt`

- [x] **Task 3: Operations policy persistence와 immutable mapping**
  - Acceptance: version/head JPA mapping이 conditional DB shape와 일치하고 scope-key head lock, history
    keyset query와 actor/idempotency lookup을 제공한다.
  - Verify: `./gradlew test --tests '*OrdinaryPointAccrualPolicyPersistenceTest'`
  - Files:
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyPersistence.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyPersistenceTest.kt`

- [x] **Task 4: verified initial offline bootstrap**
  - Acceptance: valid workload identity만 first GLOBAL version/head/Audit를 commit하고 repeat/invalid/DB/Audit
    failure가 exact non-zero result다.
  - Verify: `./gradlew test --tests '*OrdinaryPointAccrualPolicyBootstrapTest'`
  - Files:
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyBootstrapCli.kt`
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyBootstrapService.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyBootstrapTest.kt`
    - `build.gradle.kts`

- [x] **Task 4b: normal startup precheck와 explicit test policy setup**
  - Acceptance: normal server는 missing/mismatch GLOBAL에서 시작 실패하고 Testcontainers context만 명시적
    complete test policy를 precheck 전에 설치한다. production profile default는 없다.
  - Verify: `./gradlew test --tests '*OrdinaryPointAccrualPolicyPrecheckTest' --tests '*OrdinaryPointAccrualPolicyPersistenceTest'`
  - Files:
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyPrecheck.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyPrecheckTest.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/TestcontainersConfiguration.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyPersistenceTest.kt`

- [x] **Task 5: permission vocabulary와 Merchant Store validation boundary**
  - Acceptance: online read/write가 별도 grant를 사용하고 Store-specific call은 authoritative Store existence를
    public boundary로 검증한다. Operations→Merchant dependency 외 새 module leak가 없다.
  - Verify: `./gradlew test --tests '*StorePolicyScope*' --tests '*OperatorPermission*' --tests '*ModularityTests'`
  - Files:
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/api/OperatorPermissionOperations.kt`
    - `src/main/kotlin/io/github/kdh949/beanflow/merchant/api/StorePolicyScopeOperations.kt`
    - `src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/JpaStorePolicyScopeService.kt`
    - `src/main/java/io/github/kdh949/beanflow/operations/package-info.java`
    - `src/test/kotlin/io/github/kdh949/beanflow/merchant/internal/StorePolicyScopeIntegrationTest.kt`

- [x] **Task 6: selector와 idempotent GLOBAL/STORE write service**
  - Acceptance: no-head/override/inherit selection, first head, expected-version CAS, same/different payload replay와
    fixed lock order가 exact result를 내며 Audit failure는 rollback한다.
  - Verify: `./gradlew test --tests '*OrdinaryPointAccrualPolicyServiceTest' --tests '*OrdinaryPointAccrualPolicyConcurrencyTest'`
  - Files:
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyService.kt`
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/internal/OperatorSecurityMetrics.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyServiceTest.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyConcurrencyTest.kt`

- [x] **Task 7: audited current/head/history query와 signed cursor**
  - Acceptance: five read shapes가 READ grant/reason/Audit commit gate를 공유하고 cursor가 endpoint,
    scope/filter/order를 bind하며 default 20/max 100을 지킨다.
  - Verify: `./gradlew test --tests '*OrdinaryPointAccrualPolicyQueryTest'`
  - Files:
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/api/OrdinaryPointAccrualPolicyQueryOperations.kt`
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyQueryService.kt`
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyQueryPersistence.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyQueryTest.kt`

- [x] **Task 8: operator HTTP vertical slice와 target OpenAPI**
  - Acceptance: seven paths, request oneOf/conditional response, role/grant/reason/idempotency/error contracts가
    runtime과 target OpenAPI에서 일치하고 당시 deployment 표기 OpenAPI는 바뀌지 않는다.
  - Verify:
    - `./gradlew test --tests '*OrdinaryPointAccrualPolicyControllerTest'`
    - `bash scripts/verify-docs.sh`
  - Files:
    - `src/main/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyController.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/operations/internal/OrdinaryPointAccrualPolicyControllerTest.kt`
    - `openapi/beanflow-v1.yaml`
    - `docs/api/api-conventions.md`
    - `docs/api/error-catalog.md`

- [x] **Task 9: Ordering snapshot persistence와 typed read boundary**
  - Acceptance: LEGACY와 complete SNAPSHOTTED를 구분하고 immutable header/unit mapping, canonical hash와
    validated typed read를 제공한다. missing/incomplete source를 legacy로 위장하지 않는다.
  - Verify: `./gradlew test --tests '*OrderPointAccrualSnapshotPersistenceTest' --tests '*OrderPointAccrualSnapshotServiceTest'`
  - Files:
    - `src/main/kotlin/io/github/kdh949/beanflow/ordering/api/OrderPointAccrualSnapshotOperations.kt`
    - `src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderPointAccrualSnapshotPersistence.kt`
    - `src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderPointAccrualSnapshotService.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderPointAccrualSnapshotPersistenceTest.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderPointAccrualSnapshotServiceTest.kt`

- [x] **Task 10: Order creation atomic snapshot integration**
  - Acceptance: benefit-only/external-payable Order 모두 effective policy와 complete unit snapshot을 같은
    create transaction에 저장하며 policy/snapshot failure는 Order와 모든 owner effect를 rollback한다.
    policy 변경 뒤 기존 typed snapshot 결과는 동일하다.
  - Verify: `./gradlew test --tests '*OrderCreationPointAccrualSnapshotIntegrationTest' --tests '*OrderCreationIntegrationTest'`
  - Files:
    - `src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderCreationTransaction.kt`
    - `src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderSnapshotAssembler.kt`
    - `src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderCreationPointAccrualSnapshotIntegrationTest.kt`

- [x] **Task 11: deployment/security/architecture documentation**
  - Acceptance: bootstrap inputs/sequence, permission list, transaction locks, policy trace와 end-to-end behavior가
    implemented contract와 일치하며 개인 문맥이나 배포되지 않은 endpoint 성공 주장이 없다.
  - Verify:
    - `bash scripts/verify-docs.sh`
    - `git diff --check`
  - Files:
    - `docs/operations/ordinary-point-accrual-policy-bootstrap-runbook.md`
    - `docs/operations/operator-permission-bootstrap-runbook.md`
    - `docs/architecture/transaction-boundaries.md`
    - `docs/product/end-to-end-flow.md`
    - `docs/security/authorization-matrix.md`

- [x] **Task 12: full validation, diff review와 Plan 13 handoff**
  - Acceptance: focused/PostgreSQL/Modulith/full build evidence가 기록되고 plan이 completed로 이동하며
    Plan 13 dependency는 completed path로 바뀌고 actual outcome이 충분할 때만 ready가 된다.
  - Verify: plan의 전체 Validation Commands와 `git diff --stat`, `git diff --check`
  - Files:
    - `docs/exec-plans/active/ordinary-point-accrual-policy-management.md` → completed path
    - `docs/exec-plans/completed/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md`
    - `docs/exec-plans/completed/customer-order-cancellation-and-recovery.md`
    - `docs/quality/customer-order-cancellation-readiness.md`
    - `scripts/verify-docs.sh`

## Progress

- [x] repository/policy/ADR/OpenAPI/permission inspection
- [x] product decisions and approved specification
- [x] 2026-08-01 ExecPlan review
- [x] 2026-08-01 implementation task review
- [x] migration lease and latest-main gate
- [x] pure policy contracts/calculator
- [x] schema/persistence/bootstrap/precheck
- [x] owner services/permission/store validation
- [x] operator API/history/OpenAPI
- [x] Ordering snapshot and typed boundary
- [x] focused/full validation and Plan 13 handoff

## Surprises & Discoveries

- 2026-08-01: repository에는 frontend가 없고 기존 “운영자 페이지” 구현 선례도 backend audited API다.
- 2026-08-01: Merchant에는 Brand relation과 issuer registry가 없어 BRAND applicability/issuer lookup을
  현재 feature에 추가할 수 없다.
- 2026-08-01: optional STORE head 부재는 row lock만으로 직렬화할 수 없어 advisory scope lock이 필요하다.
- 2026-08-01: 최초 policy를 migration seed하지 않으면 정상 startup 전 verified non-web bootstrap이
  Flyway와 initial insert를 수행해야 한다.
- 2026-08-01: rollout 이전 Order 결과를 보존하려면 current policy backfill이 아니라 명시적 legacy
  source marker가 필요하다.
- 2026-08-01: latest main과 origin/main이 V15의 같은 commit임을 확인하고 이 feature가 V16의 단일
  migration-writer lease를 소유한다. 다른 worktree의 dirty 문서는 이 작업 범위 밖이다.
- 2026-08-01: pure calculator focused test 7개가 FLOOR/HALF_UP, Long 경계, conceptual-unit tie-out와 두
  completion expiry 규칙을 통과했다.
- 2026-08-01: V16 Testcontainers test 4개가 legacy marker exact backfill, no policy/grant seed,
  version/head/source/snapshot constraints와 permission vocabulary를 통과했다.
- 2026-08-01: V16의 신규 Order snapshot 의무로 기존 부분환불 direct-SQL fixture 12개가 처음에는
  실패했다. fixture도 production calculator/selector/snapshot service를 사용해 complete source를 같은
  transaction에 저장하도록 고친 뒤 관련 회귀와 전체 suite가 통과했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | GLOBAL 기본 + STORE override 우선순위 | one fallback과 Store 차등 정책을 함께 제공 | BR-10, ADR-074 |
| 2026-08-01 | Accepted | append-only INHERIT_GLOBAL | history를 삭제하지 않고 future selection만 복귀 | BR-10, ADR-074 |
| 2026-08-01 | Accepted | closed bps/rounding/issuer/expiry vocabulary | DB/API/snapshot의 동일한 재현 계약 | BR-10, ADR-074 |
| 2026-08-01 | Accepted | verified offline initial bootstrap | default seed와 초기화 전 server 성공 방지 | BR-10, ADR-069, ADR-074 |
| 2026-08-01 | Accepted | legacy marker only, no policy backfill | 기존 Order/result 불변 | BR-10, ADR-073, ADR-074 |
| 2026-08-01 | Approved | feature specification | 구현 전에 API/data/failure/test boundary 고정 | approved spec |
| 2026-08-01 | Approved | milestone implementation plan | policy/snapshot predecessor의 순서·checkpoint·risk 고정 | 이 ExecPlan |
| 2026-08-01 | Approved | 12 implementation tasks | 최대 5개 파일과 task별 acceptance/verification 고정 | 이 ExecPlan |

## Outcomes & Retrospective

V16은 immutable GLOBAL/STORE policy version/head, legacy source marker와 신규 Order의 complete
source/header/unit snapshot을 추가했다. verified OIDC offline bootstrap만 최초 GLOBAL을 만들며 정상
server는 complete GLOBAL head가 없으면 시작 실패한다. 운영자 API 일곱 개는 READ/WRITE grant,
reason/idempotency, Store validation, Audit와 signed cursor contract를 구현하고 target OpenAPI에만
기록했다.

Order 생성은 external-payable과 benefit-only 모두 Operations selector와 exact calculator 결과를 같은
transaction에 저장한다. 정책 또는 snapshot 실패는 Order와 모든 owner effect를 rollback한다. 정책
변경 뒤 기존 typed snapshot이 동일하고 새 주문만 새 version을 사용하는 통합 증거가 있다.

검증 결과는 다음과 같다.

- pure/application/PostgreSQL/API/security/concurrency focused suites: **Passed**.
- `./gradlew clean build`: **Passed**, 193 tests, 0 failures/errors/skips; Spotless와 Modulith 포함.
- `bash scripts/verify-docs.sh`: **Passed**, target 26 paths, deployed 8 paths, 73 schemas,
  32 policies, 74 ADRs, 139 Markdown files, 24 ExecPlans.
- `git diff --check`: **Passed**.

따라서 Plan 13은 live policy 재조회 없이 completed typed Ordering boundary를 소비할 수 있다.

## Revision Notes

- 2026-08-01: approved specification에서 별도 policy/snapshot predecessor plan을 만들고 Plan 13 recovery와
  ownership을 분리했다.
- 2026-08-01: V16, 운영자 policy API, verified bootstrap, startup gate와 Order snapshot 통합을 완료하고
  full validation 뒤 completed로 이동했다.
