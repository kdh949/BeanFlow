# 공통 Order compensation foundation을 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md`, `docs/exec-plans/completed/customer-order-cancellation-20-settlement-foundation.md`
> **Completed-At:** `—`

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
- Plan 11은 ADR-063에 따라 최종 다섯 policy head/version 저장소와 운영 API를 먼저
  구현한다. 이 계획은 그중 종료용 네 head를 소비한다.
- Plan 11은 종료용 네 policy head의 direct source이고 Plan 20은 ADR-072 migration-writer lane의
  direct phase predecessor다. Plan 30은 두 completion evidence 뒤 latest-main baseline에서 policy
  head를 소비하며, independent parallel migration branch를 만들지 않는다.
- Plan 20은 V21의 최소 Order 취소 evidence와 Settlement Batch/Item, V2 completion consumer,
  고객 취소 제외 Audit을 270-test build로 완료했다. 두 direct dependency가 모두 completed이므로
  이 계획은 ADR-072 migration-writer lease를 얻은 뒤 시작할 수 있다.
- 00 plan에서 모든 non-local 환경·artifact가 0으로 확인돼 ADR-059 gate가 통과했다.
  이 계획은 clean-cutover migration/event 전략을 입력으로 사용할 수 있다.

## Definitions

- **Trigger:** `STORE_REJECTION` 또는 `CUSTOMER_CANCELLATION`.
- **Owner source:** Order terminal version과 step으로 만든 안정적 중복 기준.
- **Policy head:** trigger×benefitType의 현재 immutable policy version.
- **Step-specific exhaustion:** 실패 listener의 step만 수동 검토로 바꾸는 규칙.
- **Stable listener target:** `@ApplicationModuleListener(id = ...)`에 선언하고 중앙 registry가
  event type·step에 연결하는 versioned persistence contract.

## Scope

### In Scope

- OrderCompensationCase/Step, trigger와 두 policy child snapshot
- 기존 종료용 네 policy head를 Case의 두 policy child snapshot에 연결
- Pickup/Stock 공통 termination release와 source/trigger conflict
- Coupon/Points disposition, policy metadata와 보상 coupon terms/cost snapshot
- `OrderRejectedV1` 목표 shape와 네/기존 owner consumer migration
- listener별 publication exhaustion과 store API 공통 compensation projection

### Non-goals

- 고객 취소 HTTP command와 Refund 생성
- Plan 20이 소유한 ADR-029 cause/cancelledAt 컬럼·CHECK와 migration precheck의 재구현
- policy head/version table, seed와 운영 목록/PATCH API migration
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

Plan 11/20 completion and ADR-072 migration-writer lease 뒤 latest main에서 시작한다. 00 plan이 clean cutover를 명시적으로 통과했으므로 ADR-059 shape를 사용한다. producer,
consumer와 fixture를 같은 변경에서 전환하고 legacy compatibility layer와 version 이중
발행은 추가하지 않는다. 재확인 결과가 nonzero/unknown이면 새 Accepted
forward-migration ADR/ExecPlan의 rename/backfill/publication drain/compatibility 순서를
따른다. 어떤 경우에도 적용된 migration checksum을 repair해 혼합 schema를 만들지 않는다.

ADR-059의 replaced migration mechanics 표는 실행 방식을 열거하며 이 계획의 소유
목록이 아니다. 이 계획은 ADR-033 compensation table, ADR-040 owner terminal 상태와
ADR-042 복원 metadata의 세 migration만 최종 shape 직접 생성으로 작성한다. ADR-029의
`cancelled_at`·`cancellation_cause`, terminal CHECK와 precheck는 completed Plan 20 schema를
그대로 소비하고, reason/detail과 고객 취소 command는 Plan 40이 소유한다. 각 ADR의 backfill 규칙과 precheck 실패 조건은 문서에 남아 있고
gate가 무효화될 때의 계약이므로 삭제하거나 무시하지 않는다.

Policy version/head table, 다섯 seed와 운영 API는 Plan 11의 단일 소유다. 이 계획은
같은 migration을 만들지 않고
`(STORE_REJECTION | CUSTOMER_CANCELLATION) × (COUPON | POINTS)` 종료용 네 head를
조회해 Case child FK snapshot을 저장한다.

precheck는 clean-cutover 경로에서도 생략하지 않고 구현한다. 각 migration은 대상
legacy row 수를 먼저 세고, 0이면 통과, 하나라도 있으면 backfill을 추측 실행하지 않고
실패한다. 이것이 배포 시점에 gate 무효화를 감지하는 유일한 자동 장치다.

## API and Event Contracts

- 매장 response는 축약 `StoreCompensationSummary`(trigger·state·updatedAt),
  운영자 response는 여섯 step을 담은 `CompensationSummary`를 감싼
  `OperatorCompensationView`를 쓴다. 매장 응답에는 step 배열, `attemptCount`,
  `lastErrorCode`, `caseId`와 policy version이 없다.
- business response에 `replayed`를 넣지 않는다.
- 고객 취소 event는 네 owner만 소비하며 Payment/Notification은 소비하지 않는다.
- V1 변경 가능 여부와 payload version은 00 gate 결과를 따른다.

중앙 `CompensationPublicationTargetRegistry`는 아래 exact mapping만 허용한다.

| Event type | Stable listener ID | Compensation step |
|---|---|---|
| `OrderRejectedV1` | `beanflow.order-compensation.order-rejected.payment.v1` | `PAYMENT` |
| `OrderRejectedV1` | `beanflow.order-compensation.order-rejected.pickup.v1` | `PICKUP` |
| `OrderRejectedV1` | `beanflow.order-compensation.order-rejected.stock.v1` | `STOCK` |
| `OrderRejectedV1` | `beanflow.order-compensation.order-rejected.coupon.v1` | `COUPON` |
| `OrderRejectedV1` | `beanflow.order-compensation.order-rejected.points.v1` | `POINTS` |
| `OrderRejectedV1` | `beanflow.order-compensation.order-rejected.customer-notification.v1` | `CUSTOMER_NOTIFICATION` |
| `OrderCancelledV1` | `beanflow.order-compensation.order-cancelled.pickup.v1` | `PICKUP` |
| `OrderCancelledV1` | `beanflow.order-compensation.order-cancelled.stock.v1` | `STOCK` |
| `OrderCancelledV1` | `beanflow.order-compensation.order-cancelled.coupon.v1` | `COUPON` |
| `OrderCancelledV1` | `beanflow.order-compensation.order-cancelled.points.v1` | `POINTS` |

각 listener는 표의 ID를 annotation에 명시한다. registry duplicate는 startup을 실패시키고,
exhausted publication의 `(eventType, listenerId)`가 없으면 `PUBLICATION_TARGET_UNMAPPED`로
운영 case를 열되 step을 추측하거나 Case 전체를 변경하지 않는다. 기존 default listener ID가
남은 publication이 하나라도 있거나 inventory가 unknown이면 stable ID cutover를 수행하지 않고
ADR-059 forward-migration 경로를 먼저 확정한다. stable ID/version은 해당 event type의 incomplete
publication이 0이고 rollback 기간이 끝나기 전까지 제거하지 않는다.

## Milestones

1. 00 clean-cutover 결과와 Plan 11 policy/Plan 20 lane completion evidence를 모두 검증한다.
2. 공통 Case/step/trigger/two-policy domain과 schema를 구현한다.
3. Plan 11의 종료용 네 policy head를 Case child snapshot에 연결한다.
4. Pickup/Stock과 Coupon/Points owner 복원을 공통 계약으로 전환한다.
5. store rejection producer/consumer와 API를 회귀 없이 전환한다.
6. listener별 publication exhaustion과 recovery를 구현한다.

## Required Tests

- trigger 두 값, 여섯 step, policy child 정확히 두 개
- 같은/different source·trigger·version 중복
- store rejection 기존 정상·timeout·refund·notification 회귀
- 매장 응답의 step 배열·attemptCount·lastErrorCode·caseId·policy version 부재
- 매장 응답의 trigger·case state·updatedAt 존재와 운영자 응답의 여섯 step 존재
- 단일 listener exhaustion 시 해당 step만 manual review
- 다른 publication 계속 완료와 attempt 분리
- annotation listener ID·registry 표·실제 publication target 집합의 일치
- duplicate registry startup failure와 unknown target의 step 불변/운영 case
- store idempotency hash에 orderId 포함, V2 operation, replay body 불변
- migration strategy별 empty/existing fixture
- ADR-033/040/042 세 migration의 legacy row precheck가 후보 0에서 통과
- 세 migration 각각에 legacy row를 주입한 fixture에서 backfill 추측 없이 실패
- empty database full migration 뒤 최종 shape의 CHECK·FK·unique 전수 검증
- policy head/version/API migration 중복 부재와 Plan 11 schema 소비

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
- [ ] termination policy snapshot integration
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
| 2026-08-01 | Superseded 2026-08-03 | ADR-029 Order 취소 migration은 Plan 40이 단독 소유 | ADR-048 consumer가 Plan 40 전 실제 cause 증거를 요구해 최소 기반을 분리 | ADR-059, Plan 40 |
| 2026-08-01 | Accepted | 공통 five-head policy 기반은 Plan 11이 단독 구현하고 이 계획은 종료용 네 head를 소비 | 부분 환불 선행조건과 migration 중복 방지 | ADR-063, Plan 11 |
| 2026-08-01 | Superseded | Plan 30은 Plan 10 branch 위에서만 시작하고 Plan 20과 병렬 실행한다 | PR base/parallel Flyway migration을 무인 실행할 수 없음 | prior master DAG |
| 2026-08-01 | Accepted | Plan 30은 Plan 11 policy output과 Plan 20 lane completion 뒤 latest main baseline에서 시작 | policy head direct input을 보존하면서 migration writer lane을 단일화 | ADR-063, ADR-072 |
| 2026-08-01 | Accepted | versioned listener ID와 중앙 event-target-step registry를 사용 | 실패 target 하나만 정확히 수동 검토하고 method rename과 영속 계약을 분리 | ADR-010, ADR-034 |
| 2026-08-03 | Accepted | Plan 20의 cause/cancelledAt와 Settlement completion evidence를 소비하고 Plan 30 migration lane을 준비 상태로 전환 | 정산 제외 증거와 compensation schema 소유권을 분리하고 단일 writer 순서를 유지 | ADR-029, ADR-067, ADR-072 |

## Outcomes & Retrospective

미구현이다. 00 fact gate, Plan 11 policy output과 Plan 20 Settlement completion evidence가 모두
verified completed라 `Implementation-Ready=true`다. 구현은 latest-main baseline에서 ADR-072
migration-writer lease를 얻은 뒤 시작하며, Plan 20 schema를 재구현하지 않는다.

## Revision Notes

- 2026-07-31: readiness audit에서 최초 작성.
- 2026-07-31: owner release evidence로 00 gate가 통과해 clean-cutover 전략을 확정.
- 2026-08-01: policy head/version/API 구현 소유권을 Plan 11로 이동.
- 2026-08-01: **Superseded** Plan 10→30 직접 의존성을 명시하고 Plan 20을 sequential blocker에서
  제거했다. 이후 ADR-072가 Plan 20 lane input을 복원했다.
- 2026-08-01: ADR-072에 따라 prior parallel branch rule을 supersede하고 Plan 20 completion 뒤
  latest-main migration lane으로 변경했다.
- 2026-08-03: completed Plan 20 dependency와 최소 Order 취소 evidence ownership을 반영하고
  모든 direct dependency가 완료돼 implementation-ready로 전환했다.
