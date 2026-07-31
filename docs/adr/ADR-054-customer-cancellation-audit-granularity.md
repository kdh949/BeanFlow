# ADR-054: 고객 취소의 target별 append-only 감사

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

BR-30과 ADR-022는 terminal Order 상태, 금액, 슬롯, 재고, 쿠폰, 포인트와 수동
재처리의 전후 상태를 append-only AuditRecord로 보존하고, 주문 생성·만료에서는 변경
target마다 별도 record를 만들도록 정했다.

고객 취소 문서는 Tx C0/C1에 `AuditRecord`가 포함된다고만 표현해 record 수와 대상이
모호하다. 한 고객 취소는 Order와 여러 예약을 바꾸거나 Case, Refund, recovery
snapshot과 NotificationDelivery를 함께 만든다. 명령당 summary 한 건은 개별 target의
원자적 저장과 replay 중복을 검증하기 어렵다.

## Decision

### Common rules

- 고객 취소와 후속 보상은 상태가 바뀌거나 중요한 durable work가 생성된 business
  target마다 별도 AuditRecord를 append한다.
- 같은 cancellation의 record는 공통 `correlationId`와
  `order:{orderId}:customer-cancellation:{aggregateVersion}` source prefix로 묶는다.
- 중복 key는 `(action, targetType, targetId, sourceReference)`다.
- 원 고객 명령의 actor는 인증된 `CUSTOMER`, 후속 worker와 event consumer는
  `SYSTEM`, 수동 제안·승인·재처리는 실제 `PLATFORM_OPERATOR`다.
- 원 명령 reason은 닫힌 `cancellationReasonCode`다. 자유 입력
  `cancellation_detail`은 어떤 AuditRecord에도 복제하지 않는다.
- before/after summary는 상태 enum, 수량, KRW 금액, policy version과 trigger처럼
  변경 재현에 필요한 whitelist 필드만 가진다. customer ID, raw Provider reference,
  client Idempotency-Key, 알림 payload와 자유 입력 detail은 넣지 않는다.

### Tx C0: `PENDING_PAYMENT`

Tx C0은 실제 사용 여부에 따라 다음 target record를 함께 commit한다.

- `ORDER_CUSTOMER_CANCELLED` — Order
- `PICKUP_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION` — PickupReservation
- `STOCK_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION` — 변경된 각 StockReservation
- `COUPON_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION` — CouponReservation
- `POINT_RESERVATION_RELEASED_BY_CUSTOMER_CANCELLATION` — PointReservation
- `ORDER_CANCELLATION_ACCEPTED_DELIVERY_CREATED` — NotificationDelivery

사용하지 않아 target이 없는 coupon/points에는 가짜 AuditRecord를 만들지 않는다.

### Tx C1: 미수락 `PAID`

Tx C1은 다음 record를 함께 commit한다.

- `ORDER_CUSTOMER_CANCELLED` — Order
- `ORDER_COMPENSATION_CASE_CREATED` — OrderCompensationCase; 여섯 step과 두 benefit
  policy version ID를 after summary에 포함
- `PAYMENT_CANCELLATION_RECOVERY_SNAPSHOT_CREATED` —
  PaymentCancellationRecoverySnapshot
- 현금 요청액이 양수일 때 `CUSTOMER_CANCELLATION_REFUND_REQUESTED` — Refund
- `ORDER_CANCELLATION_ACCEPTED_DELIVERY_CREATED` — NotificationDelivery

`BENEFIT_ONLY`는 Refund Audit가 없고 snapshot after summary가 0/0/0과 null Refund
ID임을 기록한다.

Event publication과 cancellation IdempotencyRecord는 자체 registry/record로 내구
추적하므로 별도 Audit target을 만들지 않는다. 그러나 이들의 저장 실패는 계속 Tx
전체 rollback 조건이다.

### After-commit owner work

- Pickup, Stock, Coupon과 Points consumer는 실제 owner 상태·원장을 바꾸는 각 local
  transaction에서 owner target AuditRecord를 함께 저장한다.
- Refund worker는 외부 결과가 `SUCCEEDED`, `UNKNOWN`, `FAILED`,
  `MANUAL_REVIEW`로 의미 있게 전이할 때 Refund target AuditRecord를 함께 저장한다.
  단순 claim, lease 연장과 다음 retry scheduling은 Refund 원장·metric에 남기고 매
  attempt AuditRecord를 만들지 않는다.
- NotificationDelivery의 Provider attempt는 Delivery 원장에 남긴다. 기본·후속
  delivery 생성과 수동 재처리는 Audit 대상이지만 자동 retry마다 Audit를 만들지
  않는다.
- owner 상태 변경과 Audit 저장 중 하나라도 실패하면 해당 owner transaction을
  rollback하고 event publication 또는 worker retry 상태를 유지한다. 이미 확정된
  Order 취소는 되돌리지 않는다.

### Replay

- 같은 cancellation HTTP replay는 저장된 response만 반환하고 AuditRecord를 추가하지
  않는다.
- 같은 owner source의 event replay와 worker 재확인은 기존 target Audit를 재사용하고
  새 record를 만들지 않는다.
- 다른 target의 record 누락을 한 transaction summary 존재로 성공 취급하지 않는다.

## Alternatives Considered

### 고객 취소 명령당 한 건

- record 수와 index 비용이 작다.
- 여러 StockReservation과 owner work의 개별 전후 상태, 부분 누락과 중복을 구분하기
  어렵다.

### Order와 금융 target만

- 핵심 상태와 금액은 추적한다.
- 슬롯·재고·혜택·알림 work의 고객 취소 원인을 감사 원장에서 연결할 수 없다.

## Rationale

기존 주문 생성·만료 감사와 같은 granularity를 쓰면 lifecycle 전체에서 같은
중복·원자성 규칙을 적용할 수 있다. Event publication과 idempotency record처럼 이미
전용 내구 원장이 있는 기술 record는 중복 감사를 피하고 business target 변경에
집중한다.

## Consequences

- 다품목 주문은 StockReservation 수만큼 AuditRecord가 늘어난다.
- Tx C0/C1 commit gate에는 단일 record가 아니라 대상별 필수 record 집합이 포함된다.
- 운영 조회는 correlation/source로 한 cancellation의 record를 묶어야 한다.

## Failure Scenarios

- Order Audit 하나만 있고 예약 변경 Audit가 없으면 일부 owner 변경을 재현할 수 없다.
- 자유 입력 detail을 reason으로 복사하면 개인정보 최소화 경계를 위반한다.
- 자동 retry마다 Audit를 만들면 외부 장애 시 감사량이 폭증하고 business 변경과 시도
  기록이 섞인다.
- target ID 없이 cancellation summary만 저장하면 source replay의 개별 중복 제약을
  세울 수 없다.
- owner 변경 뒤 Audit 실패를 삼키면 상태와 감사가 갈린다.

## Verification

- 상태별 예상 target 집합과 실제 Audit 집합의 일치
- 각 target 변경과 Audit의 원자적 commit/rollback
- 다품목 stock target별 record
- replay의 Audit 수 불변
- detail·client key·Provider reference 부재
- worker 자동 attempt와 business 결과 Audit의 분리

## Required Tests

- PENDING_PAYMENT의 coupon/points 사용 여부 조합별 target 집합
- PAID 외부결제와 BENEFIT_ONLY의 Audit 차이
- StockReservation 여러 건의 target unique
- 각 Audit insert failure injection의 Tx C0/C1 전체 rollback
- owner consumer Audit 실패의 해당 publication retry
- Refund terminal/unknown 전이 Audit와 claim/retry Audit 부재
- 기본·후속 Notification 수동 재처리 Audit
- same-key HTTP/event replay의 record 수 불변
- summary whitelist와 금지 필드 contract

## Metrics

- `beanflow.audit.customer_cancellation.count{action,actor_type}`
- `beanflow.audit.customer_cancellation.failure.count{action}`

Target, Order, Customer와 Provider 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** 취소당 평균 AuditRecord 수

## Revisit Conditions

Audit 저장량이 측정된 운영 예산을 넘거나 tamper-evident 외부 감사 저장소가 도입될 때

## Related Decisions

- BR-14, BR-30
- [ADR-022](ADR-022-audit-record.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
- [ADR-044](ADR-044-cancellation-accepted-notification-durability.md)
- [ADR-053](ADR-053-two-person-setup-repair-approval.md)
