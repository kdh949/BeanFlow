# Immutable refund와 Loyalty event producer를 만든다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-12-partial-refund-allocation-and-restoration.md`, `docs/exec-plans/completed/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md`, `docs/exec-plans/completed/customer-order-cancellation-15-settlement-input-snapshot-foundation.md`
> **Completed-At:** `2026-08-02`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

부분 환불/포인트 복원·회수의 확정 사실과 Plan 15 immutable settlement input을 함께 사용해
`PaymentRefundedV1`, `PointsAccruedV1`, `PointsRestoredV1` producer를 정확한 result transaction에
고정한다. 이 계획이 Plan 10–15의 기존 소유권 순환을 끊는다. `OrderCompletedV2`의 Ordering completion
producer/cutover와 Settlement consumer는 Plan 20 소유다.

## Current State

- `PaymentRefundedV1`, `PointsAccruedV1`, `PointsRestoredV1` exact Kotlin/JSON 계약과 producer가
  구현됐다. owner result와 existing `event_publication` target row는 같은 local transaction에서
  commit하거나 함께 rollback한다.
- Payment producer는 Plan 12 immutable Refund allocation과 Plan 15 V20 snapshot만 사용해 누적
  signed settlement effect를 계산한다. `OrderCompletedV2` producer/outbox와 consumer는 여전히
  Plan 20 소유다.
- Loyalty producer는 completed Plan 13의 immutable accrual/recovery owner result를 사용하며
  recovery/pending lifecycle을 재구현하지 않는다.

## Definitions

- **Settlement refund effect:** completed-order Refund가 immutable allocation+snapshot으로 계산한 signed delta.
- **Logical source:** replay/conflict 판정에 쓰는 stable producer source.

## Scope

### In Scope

- ADR-068 exact payload Kotlin/event catalog/producers
- Payment and Loyalty result transaction의 persistent publication/source/hash conflict tests
- completed/pre-completion/pre-acceptance disposition과 snapshot-missing failure gate

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

ADR-068의 field/version/logical source가 canonical이다. `COMPLETED_ORDER`와
`PRE_COMPLETION_ORDER`에 settlement effect를 넣고, 완료 필드는 `COMPLETED_ORDER`에만 넣는다.
pre-acceptance cancellation은 effect 없는 `NOT_APPLICABLE` branch를 보존한다.
이 plan은 refund/Loyalty result event만 생산하며 Order completion event를 생산하거나 그 outbox를
저장하지 않는다.

## Milestones

1. event type/payload/source contract를 구현한다.
2. Payment/Loyalty result transaction publication을 구현한다.
3. snapshot/allocation tie-out, replay/conflict, failure contract tests를 완료한다.

## Required Tests

- completed vs pre-completion vs pre-acceptance payload branch and signed cumulative settlement effect
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

- [x] 2026-08-02 implementation preflight and refund disposition decision
  - 기존 부분 환불 허용 상태와 두 값뿐인 ADR-068 disposition의 충돌을 확인했다.
  - 최초 producer 활성화 전 `PRE_COMPLETION_ORDER`를 추가하고, 완료 전 effect와 완료 후
    metadata를 분리하며 누적 floor 차분 산식을 BR-15/16과 ADR-068에 기록했다.
- [x] 2026-08-02 exact event contract
  - 세 V1 Kotlin DTO, envelope/payload validator와 exact JSON fixture를 추가했다.
- [x] 2026-08-02 Payment/Loyalty publications
  - Refund `SUCCEEDED`, `ACCRUAL` ledger, 각 restoration result owner transaction에 persistent
    publication을 결합했다. Provider와 downstream consumer 호출은 transaction 밖에 남겼다.
- [x] 2026-08-02 failure/replay tests
  - 세 refund disposition, 누적 floor 차분, immutable terms 변경, exact replay/conflict와
    snapshot/allocation/outbox rollback을 PostgreSQL 통합 테스트로 검증했다.
- [x] 2026-08-02 validation evidence
  - 지정 focused suite, event contract/Modulith suite, clean build 243 tests와 문서 검증을 통과했다.

## Surprises & Discoveries

- `settlementRefundEffect` is the former Plan 10↔15 ownership cycle.
- 2026-08-01: Plan 12 now provides immutable successful Refund allocation and restoration facts without
  publishing `PaymentRefundedV1`/`PointsRestoredV1`. This preserves Plan 16's single producer ownership;
  at that checkpoint Plan 13 and Plan 15 remained active blockers.
- 2026-08-02: Plan 13 now provides completed immutable accrual/recovery result receipts and owner
  transaction evidence without publishing `PointsAccruedV1`. Plan 15 is the only remaining direct blocker.
- 2026-08-02: Plan 15 completed V18–V20 owner inputs, snapshot hash/tie-out and the V2 factory fixture
  without activating an event producer. All Plan 16 direct dependencies are now completed.
- 2026-08-02: 새 schema 없이 기존 Spring Modulith `event_publication` table과 row semantics를
  재사용할 수 있었다. 현재 build의 starter가 core registry type을 compile API로 노출하지 않아
  Eventing-owned JDBC publication boundary가 active owner transaction 안에서 target row를 저장한다.
- 2026-08-03: Plan 50 consumer e2e에서 Modulith 2.1의 bounded resubmission은 `FAILED` row만
  선택하는 반면 direct JDBC producer가 listener를 호출하지 않고 `PUBLISHED`를 기록해 전달되지
  않음을 확인했다. 최초 row를 `FAILED`/attempt 0으로 바로잡아 owner transaction 원자성은
  유지하면서 실제 recovery worker 전달을 활성화했다.
- 2026-08-02: Settlement/Analytics consumer는 후속 Plan 소유이므로 그 target row는 현재 배포에서
  미완료 publication으로 남는다. 기존 lifecycle test는 이 producer-only target을 로컬 listener
  완료 대기에서 분리했고, target persistence 자체는 전용 producer test에서 검증한다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | event producer를 Plan 15 뒤 Plan 16으로 이동 | immutable snapshot을 사용하면서 allocation/recovery와 settlement input을 순환 없이 소비 | ADR-068, ADR-071 |
| 2026-08-01 | Accepted | Plan 16 producer 범위는 refund/Loyalty event로 한정 | V2 completion producer/cutover를 Plan 20의 Ordering transaction에 단일 소유시킴 | ADR-068 |
| 2026-08-02 | Accepted | 완료 전 품목 Refund를 `PRE_COMPLETION_ORDER`로 분리하고 effect는 누적 allocation 차분으로 계산 | 기존 허용 상태를 유지하면서 미수락 종료를 오분류하지 않고 여러 Refund의 remainder를 보존 | BR-15, BR-16, ADR-068 |

## Outcomes & Retrospective

세 immutable financial event와 producer checkpoint를 완료했다. Payment result orchestration은
Order를 먼저 잠그고 immutable settlement snapshot을 읽은 뒤 Payment의 Refund/result/allocation과
`PaymentRefundedV1` target publication을 원자적으로 저장한다. 완료 주문, 완료 전 품목 환불,
미수락 종료를 각각 `COMPLETED_ORDER`, `PRE_COMPLETION_ORDER`,
`PRE_ACCEPTANCE_CANCELLATION`으로 보존하며 completed/pre-completion effect는 누적 allocation
floor 차분으로 계산한다.

Loyalty는 gross `ACCRUAL` PointTransaction/result와 `PointsAccruedV1`, 각 복원
PointTransaction/restoration result와 `PointsRestoredV1`을 각각 같은 owner transaction에서 저장한다.
exact source/version/payload replay는 기존 owner result와 publication 한 벌로 수렴하고 changed payload는
덮어쓰지 않고 conflict로 실패한다. snapshot/allocation/source/publication persistence failure는 owner
result transaction을 rollback하며, 이미 성공한 외부 Refund 뒤 Loyalty restoration 실패는 Payment work의
`RETRY_SCHEDULED`/bounded retry/`MANUAL_REVIEW` 의미를 유지한다.

- `./gradlew test --tests '*PaymentRefunded*' --tests '*PointsAccrued*' --tests '*PointsRestored*'`:
  **Passed**.
- `./gradlew test --tests '*EventContract*' --tests '*ModularityTests'`: **Passed**.
- `./gradlew clean build`: **Passed**, 243 tests, 0 failures/errors/skips; Spotless 포함.
- `bash scripts/verify-docs.sh`와 `git diff --check`: **Passed**.
- 새 Flyway migration, production dependency, consumer/listener, V2 completion producer는 추가하지 않았다.

## Revision Notes

- 2026-08-01: Plan 10의 financial event producer scope를 분리해 Plan 15 semantic dependency를 제거했다.
- 2026-08-01: `OrderCompletedV2` ownership을 Plan 20에 명시적으로 남기고 Plan 16의 refund/Loyalty
  producer boundary를 고정했다.
- 2026-08-01: Plan 12 completion path를 반영하고 Plan 13/15가 남은 direct blockers임을 명시했다.
- 2026-08-02: Plan 13 V17/result receipt와 205-test outcome을 completed dependency로 반영하고
  Plan 15만 remaining blocker로 남겼다.
- 2026-08-02: Plan 15 V18–V20/229-test outcome을 completed dependency로 반영하고
  implementation-ready로 전환했다.
- 2026-08-02: 세 producer와 failure/replay validation을 완료하고 completed path로 이동했다.
  Plan 20 dependency를 completed로 갱신해 implementation-ready로 전환하고, Analytics는 남은 direct
  producer dependencies 때문에 blocked 상태를 유지했다.
