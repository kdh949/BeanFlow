# PointLot issuer provenance foundation을 만든다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-00-contract-baseline.md`
> **Completed-At:** `2026-08-01`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

PointLot의 비용 귀속을 추측하지 않도록 immutable issuer snapshot과 verified legacy precheck를
먼저 완성한다. Plan 15의 정산 입력과 이후 부분 환불 보상은 이 결과만 읽는다.

## Current State

- `loyalty_point_lot`에는 BR-20 issuer type/reference snapshot이 없다.
- legacy Lot의 issuer source가 확인되지 않으면 PLATFORM default나 issuer 없는 보상 Lot으로
  계속할 수 없다.

## Definitions

- **Issuer snapshot:** `PLATFORM|BRAND|STORE`와 non-blank immutable issuer reference.
- **Verified precheck:** 모든 existing Lot을 확인 가능한 source에만 매핑하는 read-only release gate.

## Scope

### In Scope

- PointLot issuer precheck, forward migration, DB CHECK/NOT NULL/legacy verified backfill
- issuer snapshot을 포함한 PointReservation allocation application DTO
- Plan 15/12/13에 전달할 inventory·migration·contract-test evidence

### Non-goals

- 부분 환불, PointLot 보상, policy/grant, PointAccount 조회, financial event publication

## Business Rules and Invariants

- 새 PointLot은 issuer type/reference를 반드시 저장한다.
- legacy mapping이 하나라도 unresolvable이면 migration/endpoint activation을 중단한다.
- issuer/cost lineage를 추정하거나 PLATFORM default로 backfill하지 않는다.

## Architecture and Transaction Boundaries

precheck는 read-only release gate다. 통과 뒤 Loyalty migration이 issuer columns와 제약을
소유한다. allocation read boundary는 DTO만 노출하며 다른 Context가 PointLot Entity를 읽지 않는다.

## Alternatives Considered

- Plan 15 또는 부분 환불 계획이 issuer migration을 중복 소유: Flyway와 ownership 충돌 때문에 제외한다.
- legacy default issuer: financial lineage를 거짓으로 만들어 제외한다.

## Failure Semantics

unresolvable source, precheck 실행 실패 또는 migration validation 실패는 503/deployment gate
failure이며 fallback issuer나 부분 활성화를 허용하지 않는다.

## Data and Migration

latest-main migration writer lease 뒤 `issuer_type`과 `issuer_reference`를 추가한다. empty DB는
final NOT NULL/CHECK를 즉시 적용하고, existing DB는 verified mapping 전부를 backfill한 뒤에만
동일 제약을 적용한다.

## API and Event Contracts

PointReservation issuer-allocation DTO는 issuer type/reference와 final allocation KRW를 제공한다.
새 public HTTP/event contract는 만들지 않는다.

## Milestones

1. legacy issuer inventory와 precheck evidence를 작성한다.
2. PointLot schema와 verified backfill gate를 구현한다.
3. issuer-allocation DTO와 Plan 15 handoff contract test를 완료한다.

## Required Tests

- empty, verified, unresolvable legacy fixtures
- default/guessed backfill 부재
- issuer type/reference CHECK와 DTO projection contract
- migration failure가 endpoint activation을 막음

## Validation Commands

```bash
./gradlew test --tests '*PointLot*' --tests '*PointReservation*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

`beanflow.loyalty.issuer_precheck.count{outcome}`만 closed vocabulary로 기록한다.

## Documentation Updates

ADR-063/071/072, aggregate invariants, migration release evidence와 successor Outcomes를 갱신한다.

## Progress

- [x] implementation precheck audit — 2026-08-01
- [x] schema and migration gate — V14 fail-closed migration and startup gate verified with PostgreSQL Testcontainers
- [x] allocation DTO contract — immutable issuer snapshot and final allocation KRW cross the Loyalty boundary without a JPA entity
- [x] validation evidence — required focused, Modulith, clean build, docs, and diff validation recorded below

### Implementation precheck audit (2026-08-01)

- **Purpose:** make the BR-20 cost owner a non-null, immutable `PointLot` fact before
  Plan 12 can create expired-lot compensation and Plan 15 can materialize settlement
  input. Existing rows must not be classified from an account, customer, order, actor,
  or a `PLATFORM` default.
- **Invariants:** every new lot persists exactly one `PLATFORM|BRAND|STORE` issuer type
  and a trimmed non-blank issuer reference; the pair cannot be updated. A compensation
  lot copies both values from its original lot. A reservation allocation returned across
  the Loyalty boundary contains the lot ID, issuer type/reference, and final allocated
  KRW—not a JPA entity.
- **Affected ownership and files:** Loyalty owns `PointLotEntity`, its repositories,
  `PointReservationService`, `PointReservationOperations` DTOs, Loyalty Testcontainers
  tests, and the next Flyway migration. Ordering only consumes the existing reservation
  result and will not receive a JPA entity. Likely files are
  `PointReservationPersistence.kt`, `PointReservationService.kt`,
  `PointReservationOperations.kt`, `V14__add_point_lot_issuer_provenance.sql`, existing
  point fixtures, and a dedicated Flyway/PostgreSQL integration test.
- **Migration inventory and lease:** `main`/`origin/main` at `3e1c013` has V1–V13, so
  this lease holder uses V14. `git worktree list` found only clean `main`, signed-cursor,
  and completed Plan 11 worktrees; the current task inventory has no other running
  migration-writing Goal. No number reservation, duplicate DDL, checksum repair, or
  applied migration rewrite is permitted.
- **Legacy-source audit:** V2/V9 and all current Loyalty code/fixtures preserve account,
  amount, expiry, original-lot, and compensation source only. They contain no issuer
  type/reference or independently verifiable issuer mapping. The runtime has no
  `BEANFLOW_DB_*` configuration and the completed Plan 00 evidence confirms no non-local
  database. Therefore no actual non-empty legacy mapping may be asserted.
- **Backfill alternatives:** (1) default `PLATFORM`, (2) infer from customer/account/
  order/compensation fields, and (3) fail every non-empty database unless it supplies an
  exact, externally verified mapping relation. (1) and (2) are rejected by BR-20 and
  ADR-063/066. Choose (3): the V14 precheck is read-only and accepts a non-empty source
  only when a pre-existing `loyalty_point_lot_issuer_precheck` relation has one valid,
  source-evidenced mapping per existing lot and no extras. V14 does not create, populate,
  default, or guess that external evidence relation. With no rows it applies the final
  schema directly; with a missing, malformed, partial, blank, or invalid mapping it
  raises and rolls back.
- **Transaction/failure boundary:** Flyway runs the read-only precheck, verified update,
  final `NOT NULL`/`CHECK`, and issuer-immutability trigger in its migration transaction.
  Any precheck, update, constraint, or validation failure aborts migration and Spring
  startup; there is no local/in-memory issuer fallback or partially active endpoint.
  Reservation remains the local Loyalty transaction that locks PointAccount then ordered
  PointLots.
- **Validation plan:** PostgreSQL Testcontainers will cover empty final schema, verified
  external mapping, missing/partial/invalid mapping, no guessed/default backfill,
  `NOT NULL`/`CHECK`/immutability, issuer-aware DTO projection, and Boot activation
  failure after an unresolvable V1–V13 database. Then run the required PointLot/
  PointReservation tests, Modulith tests, clean build, docs verifier, and diff check.

## Surprises & Discoveries

- `PointLotEntity` has no issuer fields and the existing rejection-compensation path
  creates a new lot without cost lineage; both must change before it can be used for the
  later partial-refund flow.
- The sole current allocation DTO exposes only `(pointLotId, amountKrw)`, so Plan 15
  could otherwise read a JPA entity or re-query mutable state to derive issuer cost.
- Release evidence is clean/empty, but this execution environment has no configured
  non-local database. The result is not treated as proof that a future non-empty database
  is mappable; the V14 precheck remains fail-closed.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | issuer provenance을 독립 Plan 10으로 분리 | Plan 15가 필요한 유일한 Plan 10 output을 분명히 한다 | ADR-063, ADR-071 |
| 2026-08-01 | Implementation | non-empty legacy rows require an exact pre-existing, externally verified mapping relation; V14 owns neither its creation nor its values | repository/source audit found no mapping channel, so a default or inference would fabricate BR-20 financial lineage while an empty clean-cutover remains safe | BR-20, ADR-063, ADR-066, this audit |

## Outcomes & Retrospective

V14 adds `issuer_type` and `issuer_reference` to `loyalty_point_lot` with final
`NOT NULL`/CHECK constraints and a database trigger that rejects issuer snapshot changes.
New Lot persistence requires the immutable snapshot, and expired rejection compensation copies it
from the original Lot. `PointReservationAllocation` is the cross-context application DTO; it
contains `pointLotId`, `issuerType`, `issuerReference`, and `finalAllocationKrw` only.

The actual execution audit found no configured `BEANFLOW_DB_*` connection and no repository
legacy issuer source. That is not treated as a mapping assertion. Empty database migration is
therefore allowed; any non-empty V1–V13 database must provide the exact external
`loyalty_point_lot_issuer_precheck` evidence relation described in the precheck audit. Missing,
partial, unexpected, blank, invalid, or concurrently mutable evidence fails Flyway and prevents
Spring startup. No `PLATFORM` default or inferred issuer is present.

PostgreSQL Testcontainers verified empty, exact verified, missing, and invalid legacy fixtures;
the final constraints and trigger; DTO projection; compensation lineage; and activation failure.
The required Gradle focused tests, Modulith tests, and clean build passed. The documentation
verifier and `git diff --check` passed after the completion-path update.

## Revision Notes

- 2026-08-01: 기존 거대 Plan 10에서 issuer provenance만 분리했다.
- 2026-08-01: signed-cursor는 PointLot issuer migration의 phase input이 아니므로 direct dependency에서
  제거했다. 완료된 Plan 00만 직접 소비하며, migration-writer lease는 readiness가 아닌 시작 시점의 실행
  제약으로 적용한다.
- 2026-08-01: V14 and the issuer-aware allocation DTO completed; direct successors now consume this
  completed path and Plan 12/15 are implementation-ready.
