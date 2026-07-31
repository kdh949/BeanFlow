# ADR-035: 결제 후 고객 취소의 202 내구 저장 경계

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

미수락 `PAID` 고객 취소는 Order를 즉시 `CANCELLED`로 확정하지만 외부 환불, 네 owner
자원 복원과 고객 알림은 뒤에 남는다. API는 이 차이를 `202`로 표현한다. 응답 시점에
어떤 작업이 내구 저장돼 있어야 하는지 정하지 않으면 취소는 성공했지만 보상 재개
근거가 없거나, 반대로 모든 owner 작업을 긴 transaction에 묶어 한 owner 장애가 고객
취소 자체를 막을 수 있다.

ADR-032는 고객 취소가 Order row lock과 멱등 응답 저장을 한 명령 transaction에서
commit하도록 정한다. ADR-010은 원 fact와 listener별 persistent publication을 같은
transaction에 저장하도록 요구한다. ADR-033은 고객 `CancellationRefundRecoverySummary`가 Refund
aggregate에서만 파생되고 `PAID` 취소는 Refund record를 commit한 뒤 `202`를
반환하도록 정한다. ADR-006은 외부 Provider 호출을 DB transaction 밖에 둔다.

## Decision

### `PENDING_PAYMENT`

- Order lock, 소유권·deadline·멱등성 검증, Order `CANCELLED`, 네 예약 해제,
  `ORDER_CANCELLATION_ACCEPTED` NotificationDelivery `PENDING`, AuditRecord와 최초
  `200` response를 담은 취소 멱등 레코드를 Tx C0 하나에서 commit한다.
- 취소 event, OrderCompensationCase, Refund와 event publication을 생성하지 않는다.
- 한 예약 owner 또는 Audit·멱등 응답 저장이 실패하면 Tx C0 전체를 rollback한다.

### 미수락 `PAID`

Tx C1은 다음 항목을 한 PostgreSQL 로컬 transaction에서 모두 commit한다.

1. Order row lock, 소유권·deadline·멱등성 검증
2. Order `CANCELLED`와 cancellation fields
3. `trigger = CUSTOMER_CANCELLATION`인 OrderCompensationCase와 여섯 step
4. Payment cancellation recovery snapshot
5. 남은 refundable cash가 양수이면 그 금액의 Refund `REQUESTED`
6. ADR-054의 변경·생성 target별 AuditRecord 집합
7. `ORDER_CANCELLATION_ACCEPTED` NotificationDelivery `PENDING`
8. `OrderCancelledV1`과 Pickup, Stock, Coupon, Points listener별 Spring Modulith
   persistent publication
9. 최초 `202` status/body를 담은 cancellation command idempotency record

- 위 항목 중 하나라도 저장에 실패하면 Tx C1 전체를 rollback하고 `202`를 반환하지
  않는다.
- 외부 Provider 호출과 픽업 슬롯·재고·쿠폰·포인트 복원은 Tx C1에 포함하지 않는다.
- 네 자원은 commit 후 `OrderCancelledV1` owner listener가 각각 별도 transaction에서
  처리한다. NotificationDelivery는 Tx C1에서 이미 저장하고 delivery worker가
  Provider 호출과 결과 기록을 별도로 수행한다.
- Refund worker는 Refund를 별도 claim transaction에서 claim하고 DB transaction
  밖에서 Provider를 호출한 뒤 결과 transaction으로 상태를 기록한다.
- `202`는 취소와 복구 가능한 후속 작업의 내구 저장을 뜻할 뿐 환불·복원·알림 성공을
  뜻하지 않는다.
- 같은 key·payload의 HTTP 멱등 재생은 저장된 최초 `202` body만 반환하며 Tx C1,
  event와 owner 작업을 다시 실행하지 않는다.

## Alternatives Considered

### 모든 로컬 owner 보상을 Tx C1에 포함

- `202` 시점에 네 자원이 즉시 반영된다. NotificationDelivery는 선택안과 무관하게
  ADR-044의 commit gate에 포함된다.
- 여러 owner row lock과 쓰기가 취소 transaction에 추가되고 한 owner DB 실패가 Order
  취소를 막는다. 비동기 step별 실패 격리와 persistent event의 역할도 대부분 중복
  확인으로 바뀐다.

### Order 취소 후 별도 준비 transaction을 거쳐 응답

- 원 Order transaction이 짧다.
- Order 취소 commit 뒤 Case, Refund, publication 또는 멱등 응답 준비가 실패하는
  중간 상태가 생긴다. ADR-032의 명령 transaction 모델을 깨거나 별도 bootstrap
  reconciliation과 새 상태가 필요하다.

### Refund도 after-commit Payment listener에서 생성

- 기존 거절 흐름과 producer 구조를 그대로 재사용할 수 있다.
- `202` body 생성 시 Refund가 없어 `CancellationRefundRecoverySummary`가 `NOT_REQUIRED`로
  보일 수 있다. 이는 Refund만 source of truth로 사용하는 ADR-033과 충돌한다.

## Rationale

Tx C1은 고객에게 성공을 말하기 전에 원본 취소, 최초 재생 응답과 모든 후속 복구
진입점을 잃지 않도록 보장하는 최소 묶음이다. 외부 호출과 독립 owner 전이는 분리해
lock 시간과 장애 전파를 제한한다. Refund만 동기 생성하는 비대칭은 고객 응답의
source of truth가 Refund라는 ADR-033의 기존 결정 때문에 필요하다.

## Consequences

- Ordering Application Service는 public Operations, Payment, Notification과 Audit
  API를 통해 같은 local transaction을 조정하고 다른 모듈 Repository를 직접
  호출하지 않는다.
- Payment는 Provider를 호출하지 않고 Refund `REQUESTED`를 만드는 public Application
  API를 제공해야 한다.
- 취소 응답의 `CancellationRefundRecoverySummary.state`는 Tx C1에서 생성한 고객 취소 Refund를
  읽어 `REQUESTED`를 반환하거나 취소 요청 현금액이 0이면 `NOT_REQUIRED`를 반환한다.
- ADR-039에 따라 `BENEFIT_ONLY`는 Refund 없이 recovery snapshot 0/0/0과 PAYMENT
  step `NOT_REQUIRED`를 Tx C1에 저장한다. 다른 다섯 step과 publication은 그대로
  생성하고 `202`를 반환한다.
- 선행 성공 부분 환불이 있으면 Tx C1은 Payment와 성공 refund allocation을 잠그고
  `approvedAmountKrw - succeededRefundAmountKrw`만 새 Refund로 요청한다.
- Tx C1은 Payment Context의 취소 recovery snapshot에 승인액, 취소 전 성공 환불액,
  이번 취소 요청액과 Refund ID를 함께 저장한다. 요청액이 양수인데 snapshot 또는
  Refund가 없으면 내부는 `SETUP_INCOMPLETE`이고 고객 조회는 ADR-050에 따라
  `PROCESSING + REFUND_DELAYED`다.
- Tx C1이 Refund를 이미 생성하므로 Payment는 `OrderCancelledV1`을 소비하지 않는다.
  PAYMENT step은 Refund worker가 실제 Refund 상태에 따라 직접 갱신한다.
- Refund source reference는
  `order:{orderId}:customer-cancellation:{aggregateVersion}:payment`다.
- 이 결정은 ADR-034의 초기 라우팅 snapshot에서 `paymentRequired`와 Payment
  publication을 제거하는 후속 amendment다.
- 네 listener publication과 NotificationDelivery 저장은 business success의 commit
  gate다. listener 실행과 Notification Provider 발송 성공은 commit gate가 아니다.
- Tx C1은 ADR-041의 `CUSTOMER_CANCELLATION × COUPON/POINTS` head를 고정 순서로
  잠그고 Case의 두 policy version FK row와 event의 두 전체 snapshot을 함께
  commit한다.

## Failure Scenarios

- Refund 저장이 실패하면 Order, Case, Audit, publication과 idempotency record가 모두
  rollback되고 API는 business success를 반환하지 않는다.
- event serialization 또는 publication insert가 실패해도 같은 전체 rollback을
  적용하며 in-memory event 전달을 성공 근거로 쓰지 않는다.
- commit은 성공하고 HTTP 응답이 유실되면 같은 key 재요청이 최초 `202` body를
  재생하고 event와 Refund를 다시 만들지 않는다.
- commit 후 owner listener가 실패하면 Order는 `CANCELLED`를 유지하고 publication과
  해당 compensation step이 retry 또는 `MANUAL_REVIEW`로 남는다.
- Provider timeout은 Refund `UNKNOWN` 또는 `RECONCILING`으로 남고 Order와 네 자원을
  되돌리지 않는다.
- Tx C1 전에 미확정 선행 Refund를 발견하면 `409 PAYMENT_REFUND_UNRESOLVED`로
  rollback하고 Order·Case·snapshot·Audit·publication·멱등 row를 남기지 않는다.
- NotificationDelivery insert 실패는 Tx C1을 rollback한다. commit 후 Provider
  실패는 NotificationDelivery와 CUSTOMER_NOTIFICATION step의 retry 상태로 남고
  취소를 롤백하지 않는다.

## Verification

- `202`가 보이면 Tx C1의 필수 항목이 모두 존재한다.
- Tx C1 각 저장 지점의 실패 주입에서 필수 항목이 하나도 남지 않는다.
- 외부 Provider와 네 자원 owner·Notification Provider 호출은 Tx C1에서 발생하지
  않는다.
- commit 후 owner 실패가 서로 독립적으로 관측·재시도된다.

## Required Tests

- `PENDING_PAYMENT` Tx C0의 Order·네 예약·Audit·멱등 응답 원자성
- `PENDING_PAYMENT` 취소의 Case·Refund·event publication 부재
- `PAID` Tx C1의 Order·Case/6 steps·Payment recovery snapshot·Refund·Audit·
  publication·멱등 응답 원자성
- Refund 저장 실패 전체 rollback
- Case 또는 step 저장 실패 전체 rollback
- Audit 저장 실패 전체 rollback
- event serialization·각 listener publication 저장 실패 전체 rollback
- Tx C1 내부 Provider·owner restoration·Notification Provider 호출 부재
- `202` body의 Refund `REQUESTED` 또는 `NOT_REQUIRED` 파생
- 선행 성공 부분 환불과 동시 고객 취소의 승인액 초과 방지
- `OrderCancelledV1` Payment publication 부재와 Refund worker의 PAYMENT step 갱신
- 응답 유실 후 같은 key 재생과 event·Refund·Case 수 불변
- commit 후 각 owner listener 실패의 Order `CANCELLED` 유지와 독립 retry

## Metrics

- `beanflow.order.customer_cancellation.transaction.count{phase,outcome}`
- `beanflow.order.customer_cancellation.transaction.duration{phase}`
- `beanflow.order.customer_cancellation.commit_gate.failure.count{component}`
- `beanflow.order.customer_cancellation.after_commit.lag{step}`

Order, Customer, Store ID, `Idempotency-Key`와 자유 입력 `detail`은 metric tag로
사용하지 않는다.

- **Not measured:** Tx C1 duration, lock wait와 owner별 after-commit lag

## Revisit Conditions

Tx C1 lock wait 또는 duration이 측정된 병목이 되거나, Context가 서로 다른 DB로
분리되거나, Refund creation 실패율 때문에 고객 취소 가용성이 제품 목표를 충족하지
못하거나, transactional publication 방식을 변경할 때

## Related Decisions

- BR-14
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-010](ADR-010-initial-event-publication.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-031](ADR-031-customer-cancellation-api-contract.md)
- [ADR-032](ADR-032-customer-cancellation-idempotency.md)
- [ADR-033](ADR-033-order-compensation-case-generalization.md)
- [ADR-034](ADR-034-customer-cancellation-event-contract.md)
- [ADR-039](ADR-039-benefit-only-cancellation-payment-step.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
