# 부분 환불 allocation과 포인트 복원 foundation을 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/active/customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md`, `docs/exec-plans/completed/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

부분 환불이 line별 cash/point allocation을 한 번만 확정하고, 만료된 원 PointLot은 snapshot된
`PARTIAL_REFUND×POINTS` 정책에 따라 동일 issuer lineage의 보상 Lot으로 복원하게 만든다.

## Current State

- Refund는 총액만 보존하고 부분 환불을 차단한다.
- line allocation, point restoration allocation, coupon attribution과 remaining allocation read API가 없다.

## Definitions

- **Cash/point allocation:** 성공 Refund가 각 immutable OrderLine 몫에서 실제로 반환한 금액 원장.
- **Coupon attribution:** 복원 사실이 아닌 할인 귀속 감사 원장.

## Scope

### In Scope

- Refund line cash/point/coupon allocation schema와 deterministic partial-refund command
- policy version snapshot, point restoration/compensation, remaining-allocation read/lock API
- Payment/Loyalty owner 간 source-aware internal handoff

### Non-goals

- earned-point `RECOVERY`/pending offset, point-account read, immutable integration-event publication

## Business Rules and Invariants

- successful allocation 합은 line 원금과 Payment approved amount를 넘지 않는다.
- 부분 환불은 CouponIssuance/Reservation state를 바꾸거나 coupon restoration을 시작하지 않는다.
- 동일 source replay만 멱등이며 다른 payload는 conflict다.
- expired Lot 보상은 Plan 10 issuer snapshot과 Plan 11 policy version을 그대로 보존한다.

## Architecture and Transaction Boundaries

Payment는 Refund 요청에서 ordered allocation lock과 policy snapshot을 저장하고 Provider를 transaction
밖에서 호출한다. success result는 allocation/Payload source를 commit한다. Loyalty는 별도 local
transaction에서 restoration/compensation을 처리하며 Payment Aggregate를 직접 변경하지 않는다.

## Alternatives Considered

- 총 Refund amount에서 remaining을 역산: line별 반올림/복원 순서를 재현할 수 없어 제외한다.
- refund success transaction에서 Loyalty state를 직접 변경: Context boundary를 침범해 제외한다.

## Failure Semantics

policy snapshot/allocation 저장 실패는 Provider 호출 전 rollback/503이다. provider result 뒤 Loyalty
write failure는 0원 또는 성공으로 투영하지 않고 durable retry/manual-review로 남긴다.

## Data and Migration

Refund line allocation, point restoration, coupon attribution과 source/line upper-bound unique/CHECK를
Payment/Loyalty owner 경계에 맞춰 단독 migration한다. issuer schema와 policy/grant tables는 만들지 않는다.

## API and Event Contracts

ADR-061 `/payments/{paymentId}/refunds` 요청/상태 contract를 구현한다. internal source event는
복원 worker용이며 ADR-068 `PaymentRefundedV1` publication은 Plan 16이 소유한다.

## Milestones

1. allocation schema와 tie-out constraints를 구현한다.
2. deterministic request/result flow와 policy snapshot을 구현한다.
3. source-aware point restoration/compensation과 remaining read/lock API를 구현한다.

## Required Tests

- full/partial/replayed refund, rounding, concurrent line/approved upper bound
- policy change 전후 snapshot, expired boundary와 issuer lineage
- coupon non-restoration, later termination remaining allocation, Loyalty failure retry

## Validation Commands

```bash
./gradlew test --tests '*Refund*' --tests '*Allocation*' --tests '*Restoration*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

allocation tie-out/restoration disposition metric은 closed tags만 사용한다.

## Documentation Updates

ADR-014/036/061/063/068, OpenAPI/payment runbook과 Plan 13/16/40 evidence를 갱신한다.

## Progress

- [ ] allocation schema
- [ ] partial refund flow
- [ ] restoration and remaining read
- [ ] validation evidence

## Surprises & Discoveries

- 없음.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | allocation/restoration을 recovery·event publication과 분리 | Plan 15 의존 없이 부분 환불의 Payment/Loyalty 불변식을 검증 | ADR-063, ADR-065, ADR-068 |

## Outcomes & Retrospective

미구현 상태다. Plan 11의 five-head policy outcome은 verified completed input이다. Plan 10 issuer가 아직
active이므로 `Implementation-Ready=false`를 유지하고 issuer outcome 전에는 시작하지 않는다.

## Revision Notes

- 2026-08-01: 기존 Plan 10의 partial-refund slice를 분리했다.
