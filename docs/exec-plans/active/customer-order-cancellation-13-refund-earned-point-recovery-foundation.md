# 환불 적립 포인트 회수 foundation을 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/active/customer-order-cancellation-12-partial-refund-allocation-and-restoration.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

성공 Refund에 대응하는 실제 차감은 `RECOVERY` transaction으로 남기고, 부족액만
PointRecoveryPending으로 보존하여 이후 적립에서 oldest-first로 정확히 한 번 상계한다.

## Current State

- `RECOVERY` enum/type CHECK와 PointRecoveryPending persistence가 없다.
- 환불 적립 포인트 회수와 이후 적립 상계의 durable owner flow가 없다.

## Definitions

- **RECOVERY:** 실제 Lot/Account 가용 잔액을 줄인 append-only debit.
- **PointRecoveryPending:** 아직 회수하지 못한 양수 잔액의 Loyalty Aggregate.

## Scope

### In Scope

- recovery/pending schema, Account summary tie-out, refund source consumer
- future accrual의 oldest-first offset과 source conflict/retry semantics

### Non-goals

- partial-refund allocation/restoration, point-account HTTP read, ADR-068 public producer publication

## Business Rules and Invariants

- available Account/Lot은 음수가 될 수 없다.
- PENDING은 positive remaining, SETTLED는 zero remaining이다.
- actual debit과 uncollected pending은 서로 대체하지 않는다.

## Architecture and Transaction Boundaries

Loyalty는 Account 뒤 Lot/pending 정렬 lock으로 transaction을 수행한다. Refund source consumer와 later
OrderCompleted accrual은 각각 자기 local transaction에 summary, ledger, pending state를 함께 저장한다.

## Alternatives Considered

- 부족액을 RECOVERY row 하나로 기록: 실제 debit과 미회수 의무를 섞으므로 제외한다.

## Failure Semantics

source conflict, summary mismatch, DB failure는 rollback/retry/manual-review이며 0 recovery나
negative balance fallback이 아니다.

## Data and Migration

PointAccount pending summary, PointRecoveryPending, PointTransaction `ACCRUAL`/`RECOVERY` type and
source constraints를 단독 migration한다.

## API and Event Contracts

public signed transaction amount와 `recoveryPendingKrw` projection semantics는 ADR-065를 따른다.
`PointsAccruedV1` publication은 Plan 16에서 활성화한다.

## Milestones

1. recovery/pending schema와 constraints를 구현한다.
2. refund recovery consumer를 구현한다.
3. later-accrual offset과 failure/retry tests를 구현한다.

## Required Tests

- full/partial recovery, residual pending and summary tie-out
- replay/conflict/concurrent refunds, non-negative balances
- gross accrual then oldest-first recovery, PENDING→SETTLED, rollback/retry

## Validation Commands

```bash
./gradlew test --tests '*PointRecovery*' --tests '*PointTransaction*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

recovery outcome/pending state metrics use closed vocabulary only.

## Documentation Updates

ADR-065/068, point ledger documentation and Plan 16/40 successor evidence를 갱신한다.

## Progress

- [ ] schema and constraints
- [ ] refund recovery
- [ ] later-accrual offset
- [ ] validation evidence

## Surprises & Discoveries

- 없음.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | recovery를 allocation/restoration에서 분리 | debt lifecycle과 lock/tie-out 검증을 독립시킨다 | ADR-065 |

## Outcomes & Retrospective

미구현 상태다. Plan 12의 successful refund source/allocation evidence를 직접 소비한다.

## Revision Notes

- 2026-08-01: 기존 Plan 10의 earned-point recovery slice를 분리했다.
