# PointLot issuer provenance foundation을 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-00-contract-baseline.md`
> **Completed-At:** `—`

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

- [ ] issuer inventory/precheck
- [ ] schema and migration gate
- [ ] allocation DTO contract
- [ ] validation evidence

## Surprises & Discoveries

- 없음.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | issuer provenance을 독립 Plan 10으로 분리 | Plan 15가 필요한 유일한 Plan 10 output을 분명히 한다 | ADR-063, ADR-071 |

## Outcomes & Retrospective

미구현 상태다. verified issuer outcome 없이는 Plan 15나 부분 환불 보상을 시작하지 않는다.

## Revision Notes

- 2026-08-01: 기존 거대 Plan 10에서 issuer provenance만 분리했다.
- 2026-08-01: signed-cursor는 PointLot issuer migration의 phase input이 아니므로 direct dependency에서
  제거했다. 완료된 Plan 00만 직접 소비하며, migration-writer lease는 readiness가 아닌 시작 시점의 실행
  제약으로 적용한다.
