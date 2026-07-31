# ADR-039: 0원 결제 취소의 PAYMENT 보상 표현

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-016은 최종 결제액 0원 주문도 `BENEFIT_ONLY Payment(APPROVED)`를 만들고 Order를
`PAID`로 확정한다. 고객이 매장 수락 전에 이 주문을 취소하면 사용 포인트·쿠폰과
확정 슬롯·재고는 복원해야 하지만 외부 현금 환불은 없다.

ADR-033은 매장 거절과 고객 취소가 PAYMENT를 포함한 공통 여섯 보상 step을 공유하도록
정한다. ADR-035와 ADR-036은 취소 요청 현금액이 0인 경우 Refund 없이
`PaymentRecoverySummary.state = NOT_REQUIRED`를 사용하도록 정한다. 공통 Case의
PAYMENT step을 생략할지, 0원 Refund를 만들지, 명시적으로 불필요 상태로 둘지
확정해야 한다.

## Decision

- `BENEFIT_ONLY`인 미수락 `PAID` 고객 취소도
  `CUSTOMER_CANCELLATION` OrderCompensationCase와 공통 여섯 step을 만든다.
- Tx C1은 PAYMENT step을 처음부터 `NOT_REQUIRED`로 저장한다.
- 0원 Refund row를 만들지 않고 Refund worker 또는 외부 Provider를 호출하지 않는다.
- Payment cancellation recovery snapshot은 다음 값을 저장한다.

  | 필드 | 값 |
  |---|---:|
  | `approvedAmountKrw` | 0 |
  | `succeededRefundAmountBeforeCancellationKrw` | 0 |
  | `cancellationRequestedRefundAmountKrw` | 0 |
  | `cancellationRefundId` | null |

- 고객 `PaymentRecoverySummary`는 `state = NOT_REQUIRED`, 네 금액을 모두 0으로
  반환하고 `noticeCode`는 반환하지 않는다.
- Order 취소, snapshot, PAYMENT `NOT_REQUIRED`, 나머지 다섯 step, AuditRecord,
  `ORDER_CANCELLATION_ACCEPTED` NotificationDelivery, `OrderCancelledV1`과 네 자원
  owner publication은 ADR-035의 Tx C1에 함께 commit한다.
- Pickup, Stock, Coupon, Points step은 event owner별 비동기 규칙을 따르고 Customer
  Notification step은 Tx C1에 저장한 delivery의 비동기 발송 결과를 따른다.
- `202 Accepted` 의미도 일반 `PAID` 취소와 같다. 현금 환불은 불필요하지만 나머지
  자원·혜택 복원과 알림은 아직 진행 중일 수 있다.

## Alternatives Considered

### 0원 Refund를 SUCCEEDED로 생성

- PAYMENT step을 `SUCCEEDED`로 통일할 수 있다.
- 외부 환불이 없는데 가짜 금융 원장이 생기고 Refund 수·성공액 지표를 오염시킨다.

### PAYMENT step 생략

- 불필요한 row 하나를 줄인다.
- trigger에 관계없이 같은 여섯 step을 조회한다는 ADR-033 계약을 깨고 운영 UI가
  step 부재와 저장 실패를 구분해야 한다.

### PAYMENT step을 PROCESSING으로 생성

- 일반 외부 결제 취소와 초기 상태가 같다.
- 처리할 worker가 없어 영구 고착되거나 별도 no-op consumer가 필요하다.

## Rationale

`NOT_REQUIRED`는 외부 작업이 없음을 명시하면서 공통 Case shape를 유지한다. 0원
Refund나 no-op worker 없이도 “현금 환불 없음”과 “다른 보상은 진행 중”을 각각
정확하게 표현한다.

## Consequences

- Case 초기화 API는 Payment type 또는 cancellation requested amount를 받아 PAYMENT
  step 초기 상태를 결정해야 한다.
- Tx C1 commit gate는 0원 경로에서도 recovery snapshot과 PAYMENT step을 요구하지만
  Refund FK는 요구하지 않는다.
- `NOT_REQUIRED` step은 business attempt count 0, `lastErrorCode = null`이어야 한다.
- Payment Context는 `BENEFIT_ONLY`에 Provider 설정이나 Refund repository를
  fallback으로 사용하지 않는다.

## Failure Scenarios

- PAYMENT를 `PROCESSING`으로 만들면 실행 주체가 없어 Case가 완료되지 않는다.
- Refund ID null을 setup 손상으로 오판하면 정상 0원 취소가 `SETUP_INCOMPLETE`로
  보인다.
- PAYMENT step을 생략하면 row 누락과 정상 불필요를 운영자가 구분할 수 없다.
- 0원 Refund를 성공액 합계에 포함하면 환불 건수와 성공률이 왜곡된다.
- 외부 결제 adapter를 호출하면 ADR-016의 Provider 무호출 불변식을 위반한다.

## Verification

- `BENEFIT_ONLY` 취소에 Refund와 Provider 호출이 없다.
- PAYMENT step은 Tx C1부터 `NOT_REQUIRED`이며 attempt 0이다.
- snapshot과 고객 요약의 네 금액은 모두 0이다.
- 나머지 다섯 step은 일반 `PAID` 취소와 같은 방식으로 진행된다.
- Tx C1 일부 저장 실패는 Order 취소까지 모두 rollback한다.

## Required Tests

- `BENEFIT_ONLY` 고객 취소의 PAYMENT `NOT_REQUIRED`
- Refund row 0건과 Provider 호출 0회
- recovery snapshot 0/0/0, null Refund ID CHECK
- 고객 `NOT_REQUIRED`, notice 부재와 네 0원 금액
- Coupon·Points·Pickup·Stock·Notification step 처리
- PAYMENT attempt 0과 error null
- 동일 key replay에서 Refund·Case·publication 수 불변
- snapshot 또는 PAYMENT step 저장 실패의 전체 Tx C1 rollback

## Metrics

- `beanflow.payment.cancellation.not_required.count{payment_type}`
- `beanflow.order.compensation.step.count{trigger,type,state}`

Order, Payment와 customer ID는 metric tag로 사용하지 않는다.

- **Not measured:** 고객 취소 중 `BENEFIT_ONLY` 비율

## Revisit Conditions

0원 주문에도 외부 결제 확인이 필요해지거나, 보상 Case가 required step만 저장하는
가변 shape로 개정될 때

## Related Decisions

- BR-11, BR-14
- [ADR-016](ADR-016-benefit-only-payment.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-033](ADR-033-order-compensation-case-generalization.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
