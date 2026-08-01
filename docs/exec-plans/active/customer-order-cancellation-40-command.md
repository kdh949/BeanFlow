# 고객 취소 command와 Tx C0/C1을 구현한다

> **Status:** `ACTIVE`
> **Depends-On:** `docs/exec-plans/active/customer-order-cancellation-10-partial-refund-allocation-foundation.md`, `docs/exec-plans/active/customer-order-cancellation-20-settlement-foundation.md`, `docs/exec-plans/active/customer-order-cancellation-30-order-compensation-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

인증된 고객이 자신의 `PENDING_PAYMENT` 또는 acceptance deadline 전 `PAID` 주문을
전체 취소하게 한다. C0는 네 예약 해제까지 동기 완결해 200, C1은 모든 내구 후속 작업
착수를 원자 저장해 202를 반환한다.

## Current State

- OpenAPI와 ADR-029~035/039/044/054/058/061/063/064는 command 계약과 선행
  Refund·policy·멱등성 기준을 확정했다.
- 현재 Order에는 결제 거절용 `cancelPendingPayment`만 있고 고객 취소 필드/guard가 없다.
- `OrderController`에는 cancellations mapping이 없다.
- 고객 취소 멱등 table, AcceptanceTimeoutWork와 cancellation Audit action이 없다.
- 선행 계획 10/20/30 완료가 필수다.

## Definitions

- **C0:** PENDING_PAYMENT Order, 네 예약 해제, Audit, accepted Delivery와 저장 응답.
- **C1:** PAID Order, Payment/snapshot/Refund, Case, 두 policy, Delivery, Audit,
  owner publications와 저장 응답.
- **CT:** deadline이 지난 PAID 요청이 timeout work와 Audit만 저장하는 transaction.

## Scope

### In Scope

- Order cancellation cause/reason/detail/cancelledAt와 DB CHECK
- 고객 ownership, reason validation과 deadline guard
- cancellation command idempotency와 최초 body 저장
- Tx C0/C1/CT, lock order와 target별 Audit
- C1 recovery snapshot, 필요한 Refund와 accepted NotificationDelivery 생성
- `OrderCancelledV1` 네 owner publication
- POST/GET customer projection mapper의 command-time snapshot 연결

### Non-goals

- Refund worker, owner consumer, notification result event와 setup repair 구현
- ACCEPTED 이후 취소
- 운영자/매장 customer cancellation
- endpoint를 recovery 미완성 상태로 production 활성화

## Business Rules and Invariants

- `CANCELLED`이면 cancelledAt/cause가 있고 CUSTOMER_REQUEST에는 reasonCode가 필수다.
- detail은 trim 후 빈 값이면 null, 존재하면 1~200자이며 제어문자를 거부한다.
- 같은 key/same canonical payload는 최초 200/202 body를 그대로 반환한다.
- canonical payload는 orderId/reasonCode/normalized detail이다.
- C1은 unresolved prior Refund가 있으면 전부 rollback하고 409다.
- BENEFIT_ONLY는 0원 snapshot, Refund 없음, PAYMENT NOT_REQUIRED다.

## Architecture and Transaction Boundaries

- C0 lock: Order → Pickup → sorted Stock → Coupon → Point.
- C1 lock: Order → Payment → sorted Refund/allocation → COUPON head → POINTS head.
- CT lock: Order → AcceptanceTimeoutWork.
- 외부 Payment/Notification Provider와 owner restoration은 command transaction에서
  호출하지 않는다.
- Controller는 cancellation Application Service만 호출한다.

## Alternatives Considered

- 모든 성공을 202: C0 실제 완결 범위를 숨겨 제외한다.
- C1에서 owner/Provider 동기 실행: lock과 외부 실패를 결합해 제외한다.
- 별도 Cancellation Aggregate: Order terminal invariant를 나눠 제외한다.

## Failure Semantics

- owner release 또는 Audit/Delivery 저장 실패는 C0 전체 rollback과 503이다.
- C1의 snapshot/Refund/Case/policy/publication/Delivery/Audit/response 저장 중 하나라도
  실패하면 전체 rollback하고 202를 반환하지 않는다.
- deadline CT 저장 실패는 503이며 work 없이 409를 반환하지 않는다.
- rollback된 command는 terminal idempotency record를 남기지 않는다.

## Data and Migration

이 계획이 ADR-029 Order 취소 네 필드·세 CHECK와 해당 clean-cutover precheck를 단독
소유한다. 같은 forward migration 계열에서 cancellation idempotency,
AcceptanceTimeoutWork, recovery snapshot과 필요한 source unique/index를 추가한다.
ADR-029 precheck는 legacy 후보 row가 0일 때만 통과하고 하나라도 있으면 값을 추측해
backfill하지 않고 migration을 실패시킨다. 번호와 나머지 legacy 전략은 00/10/30
결과에서 결정한다.

## API and Event Contracts

- POST `/api/v1/orders/{orderId}/cancellations`: C0 200, C1 202.
- success `orderState`는 항상 CANCELLED다.
- 타 고객은 GET과 같은 403, 비허용 상태 409, 만료 409, dependency 503이다.
- `OrderCancelledV1` payload는 reason/detail/customer/store/payment를 포함하지 않는다.
- business response에 replay indicator와 detail을 포함하지 않는다.

## Milestones

1. Order domain/DB cancellation invariant와 reason validation을 구현한다.
2. cancellation idempotency와 canonical payload를 구현한다.
3. C0의 네 owner release/Audit/Delivery를 원자화한다.
4. CT durable timeout work와 정확한 deadline 경계를 구현한다.
5. C1 snapshot/Refund/Case/policy/Delivery/publication commit gate를 구현한다.
6. Controller/OpenAPI contract와 production 비활성 release guard를 연결한다.

## Required Tests

- 허용/비허용 상태와 두 deadline -1ns/at/+1ns
- reason code, trim, 200자, control character와 detail 비노출
- C0 각 owner/Delivery/Audit fault rollback
- C1 각 필수 row/publication fault rollback과 Provider 미호출
- prior Refund 상태별 409/허용과 Order→Payment lock 경쟁
- BENEFIT_ONLY 0원 branch
- same/different key/payload/order와 100개 동시 replay
- acceptance/timeout/expiry 경쟁의 단일 terminal 상태
- ADR-029 migration precheck의 후보 0 통과와 legacy row 주입 시 실패
- Plan 30 완료 schema에서 Order 취소 필드 부재, Plan 40 migration 뒤 네 필드·세 CHECK 존재

## Validation Commands

```bash
./gradlew test --tests '*CustomerCancellation*' --tests '*AcceptanceTimeout*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

cancellation from_state/outcome/reason_code, C0/C1 latency, rollback target와 timeout work
lag를 닫힌 tag로 측정한다. detail/client key/ID는 tag와 log에 넣지 않는다.

## Documentation Updates

OpenAPI, state machine, transaction boundaries, authorization/error catalog, audit/runbook과
이 계획의 actual validation을 갱신한다.

## Progress

- [ ] Order invariant/schema
- [ ] idempotency
- [ ] C0
- [ ] CT
- [ ] C1
- [ ] API/contract
- [ ] 전체 검증

## Surprises & Discoveries

- 현재 `CANCELLED`는 결제 거절 경로에만 쓰이며 cause/reason 불변식이 없다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Accepted existing | C0 200, C1 202, 별도 Cancellation Aggregate 없음 | 실제 내구 완료 범위 반영 | ADR-029/031/035 |
| 2026-08-01 | Accepted | ADR-029 Order 취소 네 필드·세 CHECK와 precheck를 이 계획이 단독 소유 | schema와 실제 command mapping의 응집도 유지 | ADR-059, Plan 30 |

## Outcomes & Retrospective

미구현 상태다. 계획 50 완료 전 production success path를 활성화하지 않는다.

## Revision Notes

- 2026-07-31: readiness audit에서 최초 작성.
- 2026-08-01: ADR-029 migration 단일 소유권을 Plan 40으로 확정.
