# ADR-044: 고객 취소 접수 알림의 내구 저장 경계

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

기존 고객 취소 설계는 `PAID` 취소의 `OrderCancelledV1`을 Notification이 소비해
NotificationDelivery를 만들고, `PENDING_PAYMENT` 취소에는 event를 발행하지 않는다.
따라서 PAID 고객만 앱 알림을 받고 PENDING_PAYMENT 고객은 HTTP 응답으로만 취소를
확인한다.

두 상태 모두 같은 고객 명령인데 접수 알림 유무가 결제 상태에 따라 달라지는 것은
설명하기 어렵다. PENDING_PAYMENT에 알림 event를 새로 발행하면 “동기 해제로 완결하고
event publication 복구를 만들지 않는다”는 ADR-029·ADR-034 경계와 충돌한다.

## Decision

- 고객 취소 Application Service는 `PENDING_PAYMENT`와 `PAID` 모두 취소 transaction
  안에서 `ORDER_CANCELLATION_ACCEPTED` NotificationDelivery를 `PENDING`으로 직접
  저장한다.
- Ordering은 Notification의 public Application API를 호출하고 Notification
  Repository를 직접 사용하지 않는다. 이 API는 기존 local transaction 참여를
  요구한다.
- 외부 Notification Provider 호출은 DB transaction과 lock 밖의 기존 delivery
  worker가 수행한다.
- delivery insert가 실패하면 Order 취소, 자원 변경, 취소 멱등 응답, Case·Refund·
  Audit·event를 포함한 해당 transaction 전체를 rollback하고 `200` 또는 `202`를
  반환하지 않는다.
- commit 후 Provider 발송 실패는 Order 취소를 되돌리지 않는다. 기존 1분, 5분,
  30분 재시도와 네 번째 실패 `MANUAL_REVIEW` 규칙을 사용한다.
- `PENDING_PAYMENT` 취소는 Order와 네 예약 해제, 멱등 응답, AuditRecord와
  NotificationDelivery를 Tx C0에 commit한 뒤 `200`을 반환한다. Notification 발송
  성공까지 기다리지 않는다.
- `PAID` 취소는 Tx C1의 공통 여섯 step 중 CUSTOMER_NOTIFICATION을
  `PROCESSING`으로 만들고 같은 transaction에서 delivery를 저장한다. delivery 실제
  상태가 step을 `SUCCEEDED`, `RETRY_SCHEDULED` 또는 `MANUAL_REVIEW`로 갱신한다.
- `OrderCancelledV1`은 더 이상 Notification consumer를 갖지 않는다. owner
  publication은 Pickup, Stock, Coupon, Points 네 개다.
- NotificationDelivery의 logical source는
  `order:{orderId}:customer-cancellation:{aggregateVersion}:accepted-notification`,
  Provider idempotency key는
  `notification:customer-cancellation-accepted:{orderId}:{aggregateVersion}`다.
- `(order_id, template, recipient_type, recipient_id, logical_channel)` 또는 동등한
  logical source UNIQUE로 HTTP replay와 event 재생에서 한 delivery만 보장한다.
- delivery payload는 `orderId`, `cancelledAt`, template rendering에 필요한 최소
  locale 정보만 담는다. 자유 입력 `detail`, client Idempotency-Key, Refund/Payment
  식별자와 내부 failure 상태는 넣지 않는다.
- 접수 알림은 취소가 확정됐다는 뜻이며 환불·혜택·자원 복원 완료를 뜻하지 않는다.

## Alternatives Considered

### PAID만 event listener로 알림

- 기존 Tx C1과 event consumer 경계를 유지한다.
- PENDING_PAYMENT 고객만 접수 앱 알림을 받지 못하고 publication insert와 listener
  실패 사이에 알림 work 생성이 지연된다.

### 두 상태 모두 별도 cancellation notification event

- Notification 결합을 after-commit으로 유지한다.
- PENDING_PAYMENT 취소도 event publication recovery를 가져 동기 완결 경계가
  불필요하게 확장된다.

### 취소 접수 알림 없음

- 취소 transaction과 Notification 가용성이 결합되지 않는다.
- 고객은 앱 화면을 떠난 뒤 취소 확정을 재확인할 수단이 줄어든다.

## Rationale

Delivery row 저장은 외부 발송이 아니라 내구 work 생성이므로 local transaction에
포함해도 Provider latency를 lock 안으로 가져오지 않는다. 두 취소 경로가 같은
접수 알림을 보장하고, PENDING_PAYMENT에 새 event·Case를 만들지 않는 장점이 있다.

## Consequences

- ADR-035의 “NotificationDelivery 생성은 Tx C1 밖” 결정은 접수 알림에 한해
  개정된다. Provider 호출은 계속 밖에 있다.
- ADR-034의 OrderCancelledV1 consumer와 Tx C1 publication 수는 5개에서 4개로
  줄어든다.
- Notification storage 장애가 고객 취소 가용성에 포함된다.
- PENDING_PAYMENT `200`은 알림 발송 완료가 아니라 취소와 delivery work 저장 완료를
  뜻한다.
- CUSTOMER_NOTIFICATION step은 event publication이 아니라 Tx C1 delivery의 상태로
  갱신된다.
- ADR-047에 따라 이 step은 접수 Delivery만 추적하며 환불 성공·지연 후속 알림은
  step을 다시 열거나 갱신하지 않는다.

## Failure Scenarios

- delivery를 commit 뒤 best-effort로 만들면 성공 취소에 알림 work가 영구 누락될 수
  있다.
- Provider를 취소 transaction에서 호출하면 Order row lock과 DB connection을 외부
  latency 동안 점유한다.
- PAID에서 event listener도 delivery를 만들면 접수 알림이 중복된다.
- PENDING_PAYMENT delivery 실패를 무시하고 200을 반환하면 두 상태의 내구 보장이
  달라진다.
- 접수 template가 환불 완료로 표현되면 `202` 의미를 위반한다.

## Verification

- 두 허용 상태 모두 취소 commit과 delivery row가 함께 존재한다.
- delivery 저장 실패는 취소 전체를 rollback한다.
- Provider failure는 Order를 되돌리지 않고 delivery retry 상태로 남는다.
- PAID에서 CUSTOMER_NOTIFICATION step이 delivery 상태를 따른다.
- OrderCancelledV1 publication target에 Notification이 없다.

## Required Tests

- PENDING_PAYMENT 취소의 delivery와 200 commit
- PAID 취소의 delivery·CUSTOMER_NOTIFICATION PROCESSING과 202 commit
- 두 경로 delivery insert 실패의 전체 rollback
- transaction 안 Provider 호출 0회
- 같은 cancellation key replay와 다른 event ID에서 delivery 한 건
- template/source/provider key의 aggregate version 안정성
- 1분·5분·30분 retry와 네 번째 manual review
- PAID delivery 성공·retry·manual 상태의 step 동기화
- PENDING delivery 실패 시 Case 생성 부재와 Notification reprocessing case
- payload·log의 detail/client key/payment identifier 부재
- OrderCancelledV1 Notification listener·publication 부재

## Metrics

- `beanflow.notification.delivery.count{template,outcome}`
- `beanflow.notification.cancellation_accepted.commit_failure.count{order_state}`
- `beanflow.notification.cancellation_accepted.lag{order_state}`

Order, customer, recipient, Provider reference와 idempotency key는 metric tag로 사용하지
않는다.

- **Not measured:** 취소 접수 알림 열람률과 PENDING/PAID별 발송 지연

## Revisit Conditions

Notification storage가 취소 가용성의 주요 실패 원인으로 측정되거나, transactional
outbox로 Delivery 생성을 원 transaction과 분리하면서 같은 내구 보장을 제공할 때

## Related Decisions

- BR-14, BR-27
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-019](ADR-019-notification-retry-and-manual-recovery.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-034](ADR-034-customer-cancellation-event-contract.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
