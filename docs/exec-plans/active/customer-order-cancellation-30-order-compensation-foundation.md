# 공통 Order compensation foundation을 만든다

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

거절 전용 compensation을 `STORE_REJECTION`과 `CUSTOMER_CANCELLATION`이 공유하는
OrderCompensationCase, 여섯 step, source-aware owner 복원과 trigger×benefit 정책으로
일반화한다. 기존 store rejection 동작을 보존하면서 고객 취소 command가 내구 후속
작업을 열 수 있는 foundation을 제공한다.

## Current State

- V8~V11과 코드는 `RejectionCompensation*`, rejection table/state/method 이름을 쓴다.
- benefit policy는 singleton head 하나와 Case의 단일 policy snapshot이다.
- `OrderRejectedV1`은 customer/store/actor/reason과 단일 policy를 담는다.
- publication retry 소진은 모든 미완료 step을 MANUAL_REVIEW로 바꾼다.
- ADR-033/034/040~043/055/059는 목표 공통 모델을 정의한다.
- 00 plan에서 모든 non-local 환경·artifact가 0으로 확인돼 ADR-059 gate가 통과했다.
  이 계획은 clean-cutover migration/event 전략을 입력으로 사용할 수 있다.

## Definitions

- **Trigger:** `STORE_REJECTION` 또는 `CUSTOMER_CANCELLATION`.
- **Owner source:** Order terminal version과 step으로 만든 안정적 중복 기준.
- **Policy head:** trigger×benefitType의 현재 immutable policy version.
- **Step-specific exhaustion:** 실패 listener의 step만 수동 검토로 바꾸는 규칙.

## Scope

### In Scope

- OrderCompensationCase/Step, trigger와 두 policy child snapshot
- trigger×COUPON/POINTS 네 policy head와 운영 API
- Pickup/Stock 공통 termination release와 source/trigger conflict
- Coupon/Points disposition, policy metadata와 보상 coupon terms/cost snapshot
- `OrderRejectedV1` 목표 shape와 네/기존 owner consumer migration
- listener별 publication exhaustion과 store API 공통 compensation projection

### Non-goals

- 고객 취소 HTTP command와 Refund 생성
- release gate를 우회한 V8/V1 변경
- legacy 호환 전략의 임의 선택
- 새 broker 또는 분산 lock

## Business Rules and Invariants

- Order terminal version당 trigger/source가 일관된 Case 하나만 존재한다.
- Case는 PAYMENT/PICKUP/STOCK/COUPON/POINTS/CUSTOMER_NOTIFICATION 여섯 step을 가진다.
- Case는 혜택 사용 여부와 무관하게 COUPON/POINTS policy snapshot 두 개를 가진다.
- 같은 source+trigger+policy만 멱등 성공이고 다른 조합은 conflict다.
- publication exhaustion은 해당 owner step만 MANUAL_REVIEW로 바꾼다.
- 성공/NOT_REQUIRED step은 다시 열지 않는다.

## Architecture and Transaction Boundaries

- store rejection transaction은 Order 전이, Case/policy snapshot, Audit, event
  publication과 멱등 응답을 함께 commit한다.
- owner listener는 자기 Aggregate와 allocation만 짧게 잠근다.
- 외부 Refund/Notification Provider 호출은 이 transaction 밖이다.
- 공통 module API는 ID와 명시적 command/view만 노출한다.

## Alternatives Considered

- rejection 구조 복제: retry와 운영 의미가 두 벌이 되어 제외한다.
- trigger 없는 공통 table: 원인·정책·source 충돌을 구분하지 못해 제외한다.
- gate 실패에도 clean cutover: 데이터·publication 손상 위험으로 금지한다.

## Failure Semantics

- 구현·배포 전 gate 재확인에서 PASS가 무효화되면 migration/event 전환을 중단하고
  forward-migration ADR/ExecPlan을 먼저 확정한다.
- owner source conflict는 덮어쓰지 않고 bounded retry 후 해당 step manual review다.
- policy head/version 누락은 시작 또는 transaction 실패이며 default 정책으로 대체하지 않는다.

## Data and Migration

00 plan이 clean cutover를 명시적으로 통과했으므로 ADR-059 shape를 사용한다. producer,
consumer와 fixture를 같은 변경에서 전환하고 legacy compatibility layer와 version 이중
발행은 추가하지 않는다. 재확인 결과가 nonzero/unknown이면 새 Accepted
forward-migration ADR/ExecPlan의 rename/backfill/publication drain/compatibility 순서를
따른다. 어떤 경우에도 적용된 migration checksum을 repair해 혼합 schema를 만들지 않는다.

## API and Event Contracts

- Store/Operations response는 `CompensationSummary`/`OperatorCompensationView`를 쓴다.
- business response에 `replayed`를 넣지 않는다.
- 고객 취소 event는 네 owner만 소비하며 Payment/Notification은 소비하지 않는다.
- V1 변경 가능 여부와 payload version은 00 gate 결과를 따른다.

## Milestones

1. 00 결과에 맞는 migration/event compatibility 전략을 확정한다.
2. 공통 Case/step/trigger/two-policy domain과 schema를 구현한다.
3. 네 policy head와 운영 API를 구현한다.
4. Pickup/Stock과 Coupon/Points owner 복원을 공통 계약으로 전환한다.
5. store rejection producer/consumer와 API를 회귀 없이 전환한다.
6. listener별 publication exhaustion과 recovery를 구현한다.

## Required Tests

- trigger 두 값, 여섯 step, policy child 정확히 두 개
- 같은/different source·trigger·version 중복
- store rejection 기존 정상·timeout·refund·notification 회귀
- 단일 listener exhaustion 시 해당 step만 manual review
- 다른 publication 계속 완료와 attempt 분리
- store idempotency hash에 orderId 포함, V2 operation, replay body 불변
- migration strategy별 empty/existing fixture

## Validation Commands

```bash
./gradlew test --tests '*Compensation*' --tests '*StoreOrder*'
./gradlew test --tests '*EventPublication*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

trigger, step, state, outcome, age와 publication exhaustion을 닫힌 tag로 측정한다.

## Documentation Updates

ADR-033/034/040~043/055/059, event catalog, runbook, OpenAPI와 release evidence를
실제 migration 전략에 맞게 갱신한다.

## Progress

- [x] 2026-07-31 migration/event strategy gate — ADR-059 clean cutover
- [ ] common case/schema
- [ ] policy heads
- [ ] owner restoration
- [ ] store rejection regression
- [ ] publication recovery
- [ ] 전체 검증

## Surprises & Discoveries

- 현재 code는 단일 publication 실패에서 모든 미완료 step을 수동 검토로 바꾼다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Accepted existing | trigger-aware 공통 Case와 두 benefit snapshot | 거절/취소 공통 불변식 | ADR-033/041 |
| 2026-07-31 | Blocked | migration 전략은 00 fact gate 뒤 확정 | 운영 상태 추측 금지 | ADR-059 |
| 2026-07-31 | Unblocked | ADR-059 clean-cutover 전략 사용 | 운영 상태 evidence에서 모든 외부 항목 0 | release-gate evidence |

## Outcomes & Retrospective

미구현이다. 00 fact gate가 통과해 foundation 구현을 시작할 수 있지만, 이 계획 자체의
domain/schema/owner migration과 검증은 아직 완료되지 않았다.

## Revision Notes

- 2026-07-31: readiness audit에서 최초 작성.
- 2026-07-31: owner release evidence로 00 gate가 통과해 clean-cutover 전략을 확정.
