# Immutable refund와 Loyalty event producer를 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-12-partial-refund-allocation-and-restoration.md`, `docs/exec-plans/completed/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md`, `docs/exec-plans/active/customer-order-cancellation-15-settlement-input-snapshot-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

부분 환불/포인트 복원·회수의 확정 사실과 Plan 15 immutable settlement input을 함께 사용해
`PaymentRefundedV1`, `PointsAccruedV1`, `PointsRestoredV1` producer를 정확한 result transaction에
고정한다. 이 계획이 Plan 10–15의 기존 소유권 순환을 끊는다. `OrderCompletedV2`의 Ordering completion
producer/cutover와 Settlement consumer는 Plan 20 소유다.

## Current State

- Refund/Point result에 durable immutable financial event producer와 contract tests가 없다.
- `PaymentRefundedV1.settlementRefundEffect`는 Plan 15 snapshot 없이는 계산할 수 없다.
- completed Plan 13 V17은 immutable Loyalty accrual/recovery result receipt와 exact
  completion/refund source/version/hash를 제공한다. Plan 16은 이 owner fact를 publication source로
  소비하며 recovery/pending을 재구현하지 않는다.

## Definitions

- **Settlement refund effect:** completed-order Refund가 immutable allocation+snapshot으로 계산한 signed delta.
- **Logical source:** replay/conflict 판정에 쓰는 stable producer source.

## Scope

### In Scope

- ADR-068 exact payload Kotlin/event catalog/producers
- Payment and Loyalty result transaction의 persistent publication/source/hash conflict tests
- completed/pre-acceptance disposition과 snapshot-missing failure gate

### Non-goals

- allocation, restoration, recovery, snapshot materialization, SettlementItem consumer, Analytics projection
- `OrderCompletedV2`, `OrderCompletedV1 -> V2` cutover, Ordering completion outbox producer activation
  또는 Settlement `OrderCompletedV2` consumer

## Business Rules and Invariants

- PaymentRefunded effect는 immutable allocation과 OrderSettlementInputSnapshot만 사용한다.
- required snapshot/source/publication이 없으면 result fact를 success event로 발행하지 않는다.
- same source+version의 changed payload는 overwrite하지 않고 manual-review path로 남긴다.

## Architecture and Transaction Boundaries

Payment Refund `SUCCEEDED` result transaction과 Loyalty accrual/restoration result transaction은 각 owner
fact와 corresponding persistent publication을 atomically save한다. Provider/consumer calls는 transaction 밖이다.

## Alternatives Considered

- Plan 12에서 event producer를 구현: Plan 15 output을 요구해 semantic cycle이 생기므로 제외한다.
- consumer live read: historical settlement/analytics result를 바꾸므로 제외한다.

## Failure Semantics

snapshot/allocation/source/outbox save failure는 publication success가 아니며 retry/reconciliation 또는
manual review다. consumer failure는 producer business fact를 rollback하지 않는다.

## Data and Migration

새 Flyway migration은 만들지 않는다. existing persistent publication infrastructure와 predecessor
schema를 소비하며, new schema need 발견 시 별도 writer plan/ADR를 먼저 만든다.

## API and Event Contracts

ADR-068의 field/version/logical source가 canonical이다. `COMPLETED_ORDER`에만 settlement effect를
넣고 pre-acceptance cancellation은 `NOT_APPLICABLE` branch를 보존한다.
이 plan은 refund/Loyalty result event만 생산하며 Order completion event를 생산하거나 그 outbox를
저장하지 않는다.

## Milestones

1. event type/payload/source contract를 구현한다.
2. Payment/Loyalty result transaction publication을 구현한다.
3. snapshot/allocation tie-out, replay/conflict, failure contract tests를 완료한다.

## Required Tests

- completed vs pre-acceptance payload branch and signed settlement effect
- missing snapshot/allocation/source/outbox rollback
- same source replay and changed-payload conflict
- delayed event after policy/terms change reproduces original values

## Validation Commands

```bash
./gradlew test --tests '*PaymentRefunded*' --tests '*PointsAccrued*' --tests '*PointsRestored*'
./gradlew test --tests '*EventContract*' --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

producer outcome/conflict metrics use event type/version/outcome only.

## Documentation Updates

ADR-068/071/072, event catalog, Plan 20/analytics successor evidence를 갱신한다.

## Progress

- [ ] exact event contract
- [ ] Payment/Loyalty publications
- [ ] failure/replay tests
- [ ] validation evidence

## Surprises & Discoveries

- `settlementRefundEffect` is the former Plan 10↔15 ownership cycle.
- 2026-08-01: Plan 12 now provides immutable successful Refund allocation and restoration facts without
  publishing `PaymentRefundedV1`/`PointsRestoredV1`. This preserves Plan 16's single producer ownership;
  at that checkpoint Plan 13 and Plan 15 remained active blockers.
- 2026-08-02: Plan 13 now provides completed immutable accrual/recovery result receipts and owner
  transaction evidence without publishing `PointsAccruedV1`. Plan 15 is the only remaining direct blocker.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | event producer를 Plan 15 뒤 Plan 16으로 이동 | immutable snapshot을 사용하면서 allocation/recovery와 settlement input을 순환 없이 소비 | ADR-068, ADR-071 |
| 2026-08-01 | Accepted | Plan 16 producer 범위는 refund/Loyalty event로 한정 | V2 completion producer/cutover를 Plan 20의 Ordering transaction에 단일 소유시킴 | ADR-068 |

## Outcomes & Retrospective

미구현 상태다. Plan 12와 Plan 13 validation evidence는 completed path에 있고 Plan 13은
`PointsAccruedV1` publication을 이 계획에 남겼다. Plan 15가 active인 유일한 direct blocker이므로
`Implementation-Ready=false`를 유지하며 해당 outcome이 completed path에 있을 때만 시작한다.

## Revision Notes

- 2026-08-01: Plan 10의 financial event producer scope를 분리해 Plan 15 semantic dependency를 제거했다.
- 2026-08-01: `OrderCompletedV2` ownership을 Plan 20에 명시적으로 남기고 Plan 16의 refund/Loyalty
  producer boundary를 고정했다.
- 2026-08-01: Plan 12 completion path를 반영하고 Plan 13/15가 남은 direct blockers임을 명시했다.
- 2026-08-02: Plan 13 V17/result receipt와 205-test outcome을 completed dependency로 반영하고
  Plan 15만 remaining blocker로 남겼다.
