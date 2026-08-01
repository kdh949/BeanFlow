# Spec: 일반 포인트 적립 정책 version 관리와 주문 snapshot

> **Status:** APPROVED — 2026-08-01
> **Date:** 2026-08-01
> **Decision sources:** BR-10, BR-20, ADR-069, ADR-073, ADR-074

## Assumptions

1. 이 저장소에는 frontend가 없으므로 “운영자 페이지” 범위는 페이지가 소비할 backend API와
   target OpenAPI까지다. 실제 UI 구현은 포함하지 않는다.
2. 정책 변경은 예약 발효가 아니라 write transaction commit 즉시 효력이 생긴다. Order 생성과
   policy write는 DB lock으로 선형화한다.
3. current policy와 append-only version history를 모두 운영자 API로 조회할 수 있어야 한다.
4. Store 존재 여부는 새 Merchant typed boundary로 검증하지만 issuer reference는 authoritative
   registry가 없으므로 literal 값으로만 검증한다.
5. migration 이전 Order에는 policy를 소급하지 않고 `LEGACY_NOT_APPLICABLE` source만 기록한다.
6. 이번 feature는 Operations policy, offline bootstrap, Ordering snapshot까지 구현한다. 실제
   `ACCRUAL`, refund `RECOVERY`와 PointRecoveryPending 처리는 후속 Plan 13 ledger slice가 이
   snapshot을 소비해 구현한다.

## Objective

Platform Operator가 일반 포인트 적립률, 반올림, 비용 부담 issuer와 만료 정책을 GLOBAL 기본값과
STORE override 단위의 immutable version으로 조회·변경할 수 있게 한다. 새 Order는 생성 transaction에서
정확히 한 effective version과 계산 결과를 snapshot한다. 이후 policy가 바뀌어도 이미 생성된 Order,
완료 적립, 부분 환불 회수와 pending 결과를 다시 계산하지 않는다.

성공한 구현은 다음 사용자 결과를 제공한다.

- 운영자는 GLOBAL current, 명시적 STORE head, resolved Store policy와 각 scope history를 조회한다.
- 운영자는 GLOBAL version을 갱신하고 STORE override를 만들거나 변경하며
  `INHERIT_GLOBAL`로 되돌린다.
- 변경 transaction 뒤에 선형화된 Order만 새 version을 사용한다.
- 기존 Order snapshot과 rollout 이전 Order 결과는 변하지 않는다.
- 권한, 감사, 멱등성 또는 policy persistence 실패가 성공·default·0원 적립으로 보이지 않는다.

## Tech Stack

- Kotlin 2.3.21, Java 21
- Spring Boot 4.1.0, Spring MVC, Method Security
- Spring Data JPA, PostgreSQL, Flyway
- Spring Modulith
- Spring REST Docs and target/deployed OpenAPI split
- JUnit 5, AssertJ, MockMvc, PostgreSQL Testcontainers
- existing `SignedCursorCodec` for version/head pagination

새 production dependency는 추가하지 않는다.

## Commands

```bash
# focused domain/application/repository/API tests
./gradlew test --tests '*PointAccrualPolicy*' --tests '*OrderPointAccrualSnapshot*'

# security and bootstrap regression
./gradlew test --tests '*OperatorPermission*' --tests '*PointAccrualPolicyBootstrap*'

# module boundaries
./gradlew test --tests '*ModularityTests'

# full validation
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check

# controlled deployment bootstrap; required env is documented in its runbook
./gradlew ordinary-accrual-policy-bootstrap
```

## Project Structure

```text
src/main/kotlin/io/github/kdh949/beanflow/operations/api/
  OrdinaryPointAccrualPolicyOperations.kt   immutable owner boundary and commands

src/main/kotlin/io/github/kdh949/beanflow/operations/internal/
  OrdinaryPointAccrualPolicyService.kt      authorization, selection and version writes
  OrdinaryPointAccrualPolicyPersistence.kt  immutable versions, heads and repositories
  OrdinaryPointAccrualPolicyController.kt   audited operator HTTP endpoints
  OrdinaryPointAccrualPolicyBootstrapCli.kt verified one-time GLOBAL initializer
  OrdinaryPointAccrualPolicyPrecheck.kt     normal-server startup integrity gate

src/main/kotlin/io/github/kdh949/beanflow/merchant/api/
  StorePolicyScopeOperations.kt             Store existence boundary

src/main/kotlin/io/github/kdh949/beanflow/ordering/api/
  OrderPointAccrualSnapshotOperations.kt    immutable snapshot read boundary for Plan 13

src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/
  OrderPointAccrualSnapshot*.kt             calculator, persistence and typed read service
  OrderCreationTransaction.kt               policy selection and atomic snapshot write

src/main/resources/db/migration/
  V<next>__create_ordinary_point_accrual_policy_and_snapshot.sql

src/test/kotlin/io/github/kdh949/beanflow/{operations,ordering}/internal/
  *PointAccrualPolicy*Test.kt
  *OrderPointAccrualSnapshot*Test.kt

openapi/beanflow-v1.yaml                     target contract
openapi/beanflow-v1-deployed.yaml            unchanged until deployment evidence
docs/operations/                              bootstrap runbook
docs/adr/, docs/product/, docs/architecture/ decision and contract records
```

Exact filenames may be combined when one cohesive file stays readable. Production classes remain under their
owning module and cross-module calls use only public `api` packages.

## Domain Model and Invariants

### Policy scope

- scope is `(scopeType, scopeId)`.
- `scopeType` is `GLOBAL|STORE`.
- GLOBAL uses one documented stable internal scope UUID; STORE uses `storeId` and must not use that sentinel.
- exactly one GLOBAL head is required after bootstrap.
- STORE head is optional. No head and current `INHERIT_GLOBAL` both resolve to current GLOBAL, but only the
  latter has an explicit Store transition history.
- `BRAND` is an issuer type, not a policy applicability scope.

### Version state and fields

`OVERRIDE` versions require all of:

- `accrualRateBps`: integer `0..10_000`
- `roundingMode`: `FLOOR|HALF_UP`
- `issuerType`: `PLATFORM|BRAND|STORE`
- `issuerReference`: trim result length `1..240`, control characters forbidden
- `expiryRule`: `EXACT_DURATION_FROM_COMPLETION|SEOUL_CALENDAR_DAYS_FROM_COMPLETION`
- `validityDays`: integer `1..3650`

GLOBAL versions are always `OVERRIDE`. STORE versions may be `OVERRIDE|INHERIT_GLOBAL`.
`INHERIT_GLOBAL` forbids all six policy value fields. Version rows also store scope, effective time,
actor type/reference, normalized reason, idempotency metadata for HTTP writes and a canonical payload hash.

Version rows are immutable at DB level. A head changes only through expected-version CAS to a newly inserted
version with the same scope. A head never points to a version from another scope.

### Calculation

For `numerator = finalPayableKrw × accrualRateBps`:

- `FLOOR`: `numerator / 10_000`
- `HALF_UP`: nearest integer, exact half rounds upward

The implementation must avoid `Long` multiplication overflow. The resulting `grossAccrualAmountKrw` is
allocated to conceptual units using BR-10/ADR-073 cash-proportional floor and deterministic remainder order.
The unit sum equals gross. Zero payable or zero bps produces a durable snapshot with zero gross and no positive
unit allocation; it is not a missing-policy fallback.

At completion:

- exact expiry is `completedAt + validityDays × 24h`;
- Seoul calendar expiry is the Instant for
  `completedAt.atZone(Asia/Seoul).toLocalDate().plusDays(validityDays)` at 00:00 Asia/Seoul;
- `expiresAt` is exclusive.

### Order accrual source

Every Order has exactly one immutable source state:

- `LEGACY_NOT_APPLICABLE`: migration-created for every pre-feature Order; no policy/unit fields.
- `SNAPSHOTTED`: created atomically with every new Order; complete policy and unit snapshot required.

The migration never copies the initial GLOBAL version into legacy Orders. A missing source or incomplete
`SNAPSHOTTED` source is corruption, not an implied legacy/default state.

The snapshot contains at least policy version ID, scope/source, all policy values, canonical hash, gross,
order payable, creation source/version and per-unit `orderLineId`, `lineSequence`, `unitPosition`,
`cashPayableKrw`, `accruedAmountKrw`. Header and units are immutable and tie out by DB/application checks.

## Data and Migration

Under the ADR-072 migration-writer lease, choose the next Flyway number from latest main immediately before
implementation. Do not modify V13 or reserve a number in advance.

The forward migration creates:

- `operations_point_accrual_policy_version` and its sequence
- `operations_point_accrual_policy_head`
- conditional CHECKs for scope/state/value completeness
- `(idempotency_actor_id, idempotency_key)` unique handling for HTTP-created versions
- composite head→version scope FK and immutable version update/delete trigger
- new permission CHECK vocabulary containing `POINT_ACCRUAL_POLICY_READ|WRITE`
- Ordering accrual source/snapshot header and unit tables with 1:1 source, unit uniqueness and tie-out inputs
- `LEGACY_NOT_APPLICABLE` source rows for every Order present at migration time

The migration creates no GLOBAL policy version/head and no default grant. The offline bootstrap application
applies Flyway, validates its workload identity, then creates initial GLOBAL version/head/Audit. The normal HTTP
application starts only after that command succeeds.

Test-only setup may insert a complete explicit GLOBAL test policy before normal context verification. It must be
restricted to a test profile and must not activate in a production profile.

## Public API Contract

All paths are under `/operations/policies/ordinary-point-accrual` in target OpenAPI. Controllers require
`PLATFORM_OPERATOR`; Application Services require the exact DB grant.

### Reads

- `GET /global`: current GLOBAL version.
- `GET /global/versions?cursor&limit`: GLOBAL history, newest `policyVersionId` first.
- `GET /stores?state&cursor&limit`: explicit STORE heads only, including `INHERIT_GLOBAL`.
- `GET /stores/{storeId}`: explicit Store head if present plus the effective resolved policy and
  `STORE_OVERRIDE|GLOBAL_INHERITED|GLOBAL_NO_OVERRIDE` selection source.
- `GET /stores/{storeId}/versions?cursor&limit`: explicit Store history.

Every read requires `X-Access-Reason`, normalized to 1..200 non-control characters, and active
`POINT_ACCRUAL_POLICY_READ`. Read projection and one target AuditRecord commit together. Pagination uses the
existing signed cursor, default 20/max 100, bound to endpoint, scope/filter and descending version/head key.

Store-specific reads return 404 for a missing Merchant Store and 503 if Store existence cannot be verified.
The STORE-head list returns opaque Store IDs without joining Merchant names.

### Writes

- `PATCH /global`: append a new complete GLOBAL `OVERRIDE` version.
- `PATCH /stores/{storeId}`: append a complete STORE `OVERRIDE` or value-free `INHERIT_GLOBAL` version.

Writes require active `POINT_ACCRUAL_POLICY_WRITE`, `Idempotency-Key` length 8..128, body reason length
1..500, and expected current version semantics:

- GLOBAL always requires `expectedPolicyVersionId`.
- first STORE version requires the expected field to be absent.
- an existing STORE head requires its exact `expectedPolicyVersionId`.

A first `INHERIT_GLOBAL` version is allowed to record explicit governance. A fresh key and correct expected
version may append the same values/state again when the supplied reason intentionally reaffirms the policy.

Idempotency scope is actor + operation + key. Same canonical payload replays the original version response
without a new version/Audit; different payload or path with the same key returns `409 IDEMPOTENCY_KEY_REUSED`.
Stale/incorrect expected state returns the existing `409 ORDER_STATE_CONFLICT` policy-version contract.

Write lock order is:

```text
active POINT_ACCRUAL_POLICY_WRITE grant row
-> actor + Idempotency-Key advisory lock
-> STORE scope advisory lock when applicable
-> existing scope head FOR UPDATE when present
-> GLOBAL head FOR UPDATE when updating GLOBAL
-> immutable version INSERT
-> head INSERT or expected-version update
-> AuditRecord flush
-> commit
```

Merchant Store existence is checked after coarse/explicit authorization and before creating a STORE version.
Missing Store is 404; Merchant dependency failure is 503. There is no issuer-reference lookup fallback.

The response exposes policy version, scope/state, conditional policy values, effective time, actor
type/reference and normalized reason. It never exposes idempotency key/hash or raw bootstrap evidence.

### OpenAPI publication

- Add the paths, schemas, parameters and permission/failure descriptions to `beanflow-v1.yaml`.
- Do not add them to `beanflow-v1-deployed.yaml` until deployment evidence exists.
- Update API conventions, authorization matrix and error descriptions without predicting Plan 13 ledger APIs.

## Offline Bootstrap Contract

`./gradlew ordinary-accrual-policy-bootstrap` runs a non-web Spring context isolated from the normal startup
precheck. It reuses `OidcWorkloadIdentityVerifier` and requires complete policy values plus reason, immutable
evidence reference and correlation ID. Raw token is accepted only through a read-only mounted file.

Terminal results and exits:

| Result | Exit | Meaning |
|---|---:|---|
| `APPLIED` | 0 | initial GLOBAL version/head/Audit committed |
| `INVALID_INPUT` | 2 | policy or command input invalid |
| `IDENTITY_VERIFICATION_FAILED` | 3 | workload identity/trust validation failed |
| `POLICY_ALREADY_INITIALIZED` | 4 | GLOBAL head already exists |
| `DEPENDENCY_UNAVAILABLE` | 5 | DB, lock, version/head or Audit commit failed |

Only `APPLIED` is success. Output is a closed result containing no policy values, reason, evidence body,
token/path or issuer reference. The runbook specifies environment names, release sequence and read-only
verification.

## Transaction and Concurrency Boundaries

### Order creation

After price calculation and successful required owner reservations, Ordering calls the Operations typed selector
inside the existing local Order transaction. Selection takes the STORE scope shared advisory lock, reads an
existing STORE head under a shared lock, and reads GLOBAL under a shared lock only when resolving fallback.
Policy writes use the same scope key exclusively. It returns an immutable value, not an Entity.

Ordering calculates and saves source/header/units before completing the existing idempotent response. Order,
reservations, Audit, benefit-only confirmation, snapshot and response all commit or rollback together. Policy
selection or snapshot failure returns 503 and cannot commit a usable Order without a snapshot.

### Policy change versus Order creation

- if Order selection commits first, it keeps the previous version;
- if policy write commits first, the Order uses the new version;
- a rolled-back write never takes effect;
- first STORE head creation is linearized with absence lookup by the shared STORE advisory lock;
- an `INHERIT_GLOBAL` Store locks GLOBAL before selecting its current version.

No policy write updates Ordering snapshot tables or Loyalty ledger tables.

## Failure Semantics

- Missing/malformed GLOBAL head at normal startup: application startup failure.
- Policy read/write grant missing or revoked: 403.
- Permission, policy, Store or Audit persistence unavailable: 503; no cached/default response.
- Invalid headers/body/enums/ranges: 400; no version/Audit.
- Missing Store: 404; no version/Audit.
- Stale head or unexpected create/update state: 409; no version/Audit.
- Idempotency key reused with another payload: 409; no version/Audit.
- Order selector/snapshot failure: whole Order creation rollback and 503.
- Legacy marker: explicit terminal not-applicable, not an error or current-policy lookup.
- New Order missing/inconsistent snapshot: retry/manual review source; never inferred legacy or 0 bps.

Exceptions must not be caught and replaced with success, empty history, stale head, in-memory policy or no-op.

## Audit and Observability

Audit actions use closed names for bootstrap, read, GLOBAL change, STORE override change and inheritance
transition. Reads have stable target IDs per collection/scope. Writes record version/state and whitelisted
before/after policy summaries; raw `Idempotency-Key`, access reason in logs, token, evidence body and issuer
reference are not metric tags.

Metrics use closed low-cardinality tags only, for example:

- `beanflow.operations.point_accrual_policy.read.count{endpoint,outcome}`
- `beanflow.operations.point_accrual_policy.change.count{scope,state,outcome}`
- `beanflow.operations.point_accrual_policy.bootstrap.count{outcome}`
- `beanflow.order.point_accrual_snapshot.count{source_state,outcome}`

Store/order/customer IDs, rate, validity, issuer reference, actor and raw reasons are forbidden metric tags.

## Code Style

Use immutable public boundary values, explicit enum vocabulary and Application Service transaction orchestration.
Controllers do not call repositories.

```kotlin
data class OrdinaryPointAccrualPolicySnapshot(
    val policyVersionId: Long,
    val scope: OrdinaryPointAccrualPolicyScope,
    val accrualRateBps: Int,
    val roundingMode: PointAccrualRoundingMode,
    val issuer: PointAccrualIssuer,
    val expiry: PointAccrualExpiry,
)

interface OrdinaryPointAccrualPolicyOperations {
    fun selectForOrder(storeId: UUID): OrdinaryPointAccrualPolicySnapshot
}
```

- Kotlin formatter and existing naming/import conventions apply.
- Business calculations are pure functions with exact boundary tests.
- Persistence entities stay internal and are never returned by HTTP or module APIs.
- Canonical payload/hash encoding has deterministic field order and normalized strings.

## Testing Strategy

### Pure domain tests

- bps 0/1/10_000 boundaries and multiplication overflow safety
- FLOOR/HALF_UP below/at/above half
- exact and Seoul calendar expiry around local midnight
- gross/unit tie-out, equal-cash tie breakers and zero gross

### PostgreSQL Testcontainers

- migration existing-Order marker cardinality and no policy backfill
- version immutability trigger and conditional scope/state/value CHECKs
- exactly one GLOBAL head, scope FK and idempotency uniqueness
- first STORE head races with Order lookup
- GLOBAL/STORE update races and `INHERIT_GLOBAL` versus GLOBAL update
- startup precheck missing/mismatch/DB failure

### Application/API/security tests

- role-only, wrong permission, revoked grant and grant lookup failure
- audited GETs, signed cursor scope/filter mismatch and Audit rollback
- GLOBAL/STORE create/update/replay/stale/different-payload behavior
- Store missing/dependency failure and no issuer lookup
- bootstrap invalid input/identity, applied, repeat and Audit rollback
- target OpenAPI and REST Docs alignment

### Ordering integration tests

- no override, active override, explicit inheritance and policy change after Order creation
- snapshot persistence failure rolls back Order and all owner effects
- immutable source/header/unit rows and typed boundary hash/version conflict
- legacy marker is not backfilled and new snapshot absence cannot masquerade as legacy
- benefit-only and external-payable Order snapshot paths

### Repository-wide validation

- Spring Modulith verifies allowed public dependencies.
- existing benefit policy and permission bootstrap tests remain green.
- full build, documentation/OpenAPI verifier and `git diff --check` pass.

## Boundaries

### Always

- update Business Policy/ADR/spec before changing a decided behavior;
- use ADR-072 migration-writer lease and latest-main Flyway numbering;
- preserve append-only versions, Audit and existing Order source markers;
- keep online writes idempotent and expected-version guarded;
- run focused, modularity and full validation and report exact results.

### Ask first

- adding BRAND applicability scope, scheduled effective time or bulk writes;
- changing the closed bps/rounding/expiry/issuer vocabulary;
- validating issuer reference through a new registry;
- adding a production dependency or actual frontend application;
- backfilling policy/accrual into `LEGACY_NOT_APPLICABLE` Orders.

### Never

- edit an applied migration, use migration seed/default GLOBAL values or default grants;
- infer Store/Brand issuer from scope or Order;
- recompute old Order snapshots from current policy;
- treat missing snapshot as zero bps or legacy without its durable marker;
- expose JPA entities, secrets, raw evidence or idempotency material;
- commit, push or create a PR without explicit user authorization.

## Success Criteria

- A verified offline command is the only way to create the initial GLOBAL head, and normal startup fails without
  a complete one.
- Platform Operators with the exact grants can auditably read current/history and append GLOBAL/STORE versions;
  all unauthorized, invalid, stale, duplicate-conflict and dependency paths return the documented result with no
  partial writes.
- Store override and inheritance resolve deterministically, including concurrent first override and global update.
- Every new Order atomically stores one complete immutable snapshot using the version selected at its creation
  linearization point.
- Later policy changes do not mutate or alter the typed result of prior snapshots.
- Migration-existing Orders have only `LEGACY_NOT_APPLICABLE` and receive no retroactive policy, accrual or
  recovery input.
- Relevant focused tests, Testcontainers tests, Modulith tests, full build, OpenAPI/docs verification and diff
  checks pass with exact evidence.
- After this feature completes, Plan 13 can consume the policy/snapshot outcome without another product decision;
  its readiness is re-evaluated from actual completed evidence.

## Open Questions

None. Any change to the assumptions or boundaries reopens SPECIFY before implementation.
