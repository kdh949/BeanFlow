# ADR-036: 선행 부분 환불 후 고객 취소의 잔액 환불

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-014는 성공 누적 환불액이 승인액을 넘지 않도록 하고 품목 부분 환불은 주문 당시
line allocation을 사용하도록 정한다. BR-15는 결제 후 부분 환불을 매장 또는 운영자
명령으로 허용하지만 대상 Order 상태를 제한하지 않는다. 따라서 고객 취소가 허용되는
미수락 `PAID` Order에도 선행 성공 부분 환불이 존재할 수 있다.

현재 거절 전용 `RejectionRefundService`는 `succeededRefundAmountKrw != 0`이면 자동
환불을 거부하고 승인액 전체만 요청한다. 반면 일반 환불 OpenAPI는 full refund를 남은
환불 가능 금액으로 정의한다. 어느 의미를 고객 취소에 적용할지와 이미 복원된 line
혜택을 어떻게 중복 방지할지 결정해야 한다.

## Decision

- 선행 성공 부분 환불이 있다는 이유만으로 고객 취소를 거부하지 않는다.
- 새 고객 취소 Refund의 현금 요청액은
  `approvedAmountKrw - succeededRefundAmountKrw`다.
- 성공 누적 환불액과 새 고객 취소 Refund의 성공 금액 합은 승인액을 초과할 수 없다.
- 고객 취소의 쿠폰·포인트 보상은 선행 부분 환불에서 이미 복원된 line allocation을
  제외하고 아직 복원되지 않은 잔여 allocation만 대상으로 한다.
- Tx C1은 Order를 취소하고 Refund를 만들기 전에 Payment와 성공 refund allocation을
  잠가 남은 금액을 계산한다.
- 현재 V10 `payment_refund`에는 line별 현금 환불·포인트 복원·쿠폰 처리 allocation이
  없으므로 구현 전에 line-level 성공 원장과 Unique/Check 제약을 추가해야 한다.
- `PaymentRecoverySummary.state`는 이번 고객 취소 source의 Refund 한 건에서
  파생하되 ADR-038의 고객용 상태·notice projection을 적용한다. 선행 Refund 상태와
  보상 PAYMENT step을 합성하지 않는다.
- Payment Context는 Tx C1에서 주문당 하나의
  `payment_cancellation_recovery_snapshot`을 저장한다. snapshot은 승인액, 취소 전
  성공 환불액, 이번 취소 요청액과 고객 취소 Refund ID를 보존한다.
- summary 금액은 다음 기준을 사용한다.

  | 필드 | 기준 |
  |---|---|
  | `approvedAmountKrw` | Payment의 최초 승인 금액, Tx C1 snapshot |
  | `succeededRefundAmountBeforeCancellationKrw` | Tx C1 lock 아래 이미 `SUCCEEDED`인 Refund 성공액 합계 |
  | `cancellationRequestedRefundAmountKrw` | 위 두 금액의 차이, Tx C1 snapshot |
  | `remainingRefundableAmountKrw` | 조회 시점 `approvedAmountKrw - 모든 SUCCEEDED Refund 성공액 합계` |

- `REQUESTED`, `PROCESSING`, `UNKNOWN`, `RECONCILING`, `FAILED`,
  `MANUAL_REVIEW` Refund는 성공 환불액 합계와
  `succeededRefundAmountBeforeCancellationKrw`에 포함하지 않고, 성공 전까지
  `remainingRefundableAmountKrw`를 줄이지 않는다.
- 취소 요청액이 0인 경우에만 summary state는 `NOT_REQUIRED`다. 요청액이 양수인데
  고객 취소 Refund 또는 필수 snapshot이 없으면 내부 `SETUP_INCOMPLETE`와 운영
  alert다. ADR-050에 따라 고객 summary는 `PROCESSING + REFUND_DELAYED`이고 검증할 수
  없는 snapshot 금액은 생략한다.
- 선행 Refund가 `REQUESTED`, `PROCESSING`, `UNKNOWN`, `RECONCILING`,
  `MANUAL_REVIEW` 중 하나이면 Order를 전이하기 전에
  `409 PAYMENT_REFUND_UNRESOLVED`로 고객 취소를 거부한다.
- `FAILED`는 Provider 부수효과 없음이 명시된 실패이므로 성공 합계에서 제외하고 고객
  취소를 허용한다. `SUCCEEDED`만 취소 전 성공 환불액에 포함한다.
- 모든 Refund 생성 경로는 Payment row lock으로 직렬화한다. Order lock도 필요하면
  전역 순서는 `Order → Payment → 정렬된 Refund allocation`이며 Payment를 잠근 뒤
  Order를 역순으로 잠그지 않는다.
- Payment lock timeout과 DB 실패는 `503 DEPENDENCY_UNAVAILABLE`이며
  `PAYMENT_REFUND_UNRESOLVED`로 변환하지 않는다.
- unresolved 409는 취소 멱등 레코드를 남기지 않는다. 선행 Refund가 terminal이 된 뒤
  같은 key 재요청은 Tx C1을 다시 실행한다.
- 고객 취소 Refund의 내부 `reason`은 `CUSTOMER_ORDER_CANCELLED`이고,
  `customer_reason_code`는 BR-14의 닫힌 여섯 code 중 요청 값을 저장한다.
- Refund source reference는
  `order:{orderId}:customer-cancellation:{aggregateVersion}:payment`다.
- Provider idempotency key는
  `refund:customer-cancellation:{orderId}:{aggregateVersion}`다. 최초 요청과 모든
  lookup·reconciliation에서 같은 key를 사용한다.
- ADR-037에 따라 Provider 작업은 최초 요청 1회와 결과 불명 시 조회 최대 5회다.
  조회는 10초, 30초, 2분, 5분, 15분 간격으로 수행하며 최초 요청을 포함한
  `attempt_count` 상한은 6이다.
- event ID·event version, client `Idempotency-Key`, customer ID와 자유 입력
  `detail`은 Refund 또는 Provider key, Provider 요청과 log에 넣지 않는다. Provider
  reason에는 `customer_reason_code`만 전달한다.

## Alternatives Considered

### 선행 성공 부분 환불이 있으면 고객 취소 거부

- 현재 거절 환불 구현과 같고 line-level 합성 데이터가 필요 없다.
- 부분 환불을 받은 고객이 남은 주문을 취소하지 못하고 운영자 개입이 필요하다.

### Order 취소 후 자동 환불 없이 manual review

- 고객 취소 의사를 보존하고 복잡한 금액 계산을 운영자에게 넘긴다.
- 환불 가능액이 확정되지 않은 상태에서 Order와 자원 복원은 진행돼 자동 처리와 수동
  처리의 경계가 갈리고 `202`의 복구 가능 의미가 약해진다.

### 미확정 Refund 요청액을 잔액에서 잠정 차감

- Provider 초과 환불 요청을 피하면서 고객 취소를 진행할 수 있다.
- 선행 Refund가 실패하면 고객 취소 Refund가 부족해지고 별도 top-up Refund와 고객
  summary 합성 상태가 필요하다.

### Order 취소 후 PAYMENT manual review

- 고객 취소 의사는 즉시 보존된다.
- 현금 결과가 불명확한 채 혜택·자원 복원은 진행돼 금전과 owner 보상의 정합성 경계가
  갈리고 자동 복구가 중단된다.

## Rationale

부분 환불이 이미 성공했다는 사실은 승인액 중 일부가 반환됐다는 뜻이지 남은 주문을
취소할 수 없다는 뜻은 아니다. 승인액 상한과 line별 복원 원장을 함께 보호하면 남은
현금과 혜택만 정확히 정리할 수 있다. 단순 잔액 계산만 하고 혜택 원장을 두지 않으면
현금은 맞아도 포인트·쿠폰이 이중 복원될 수 있으므로 두 요구를 하나의 결정으로
묶는다.

## Consequences

- Payment Context에 한 Payment의 여러 Refund와 line allocation을 조회·잠그는 public
  Application API가 필요하다.
- Refund 또는 별도 refund allocation table이 line별 cash, restored points와 coupon
  disposition의 성공 사실을 보존해야 한다.
- 기존 `uq_payment_rejection_refund` 같은 reason별 단일 index만으로는 일반 부분
  환불과 고객 취소 합성을 보호할 수 없다.
- ADR-033의 단일 Refund 원천 규칙은 여러 Refund가 공존하는 경우를 다루도록 이번
  고객 취소 Refund와 금액 snapshot 계약으로 개정하고, 고객 표시는 ADR-038의
  projection을 적용한다.
- `payment_cancellation_recovery_snapshot`은 `order_id`와 `payment_id`를 UNIQUE로
  보호하고, 세 snapshot 금액의 비음수·상한·합계 tie-out을 CHECK로 강제한다.
- 요청액이 양수이면 `cancellation_refund_id`가 필수이고, 0이면 NULL이어야 한다.
- `remainingRefundableAmountKrw`는 저장하지 않고 조회 시 성공 Refund 합계에서
  계산한다.
- `payment_refund`는 `reason = CUSTOMER_ORDER_CANCELLED`일 때
  `customer_reason_code`가 필수이고 그 외 reason에서는 NULL이도록 CHECK를 둔다.
- `(payment_id) WHERE reason = 'CUSTOMER_ORDER_CANCELLED'` partial unique index가 한
  Payment의 고객 취소 Refund를 하나로 제한한다.

## Failure Scenarios

- Payment를 잠그지 않고 잔액을 계산하면 부분 환불과 고객 취소가 동시에 승인액을
  초과해 요청될 수 있다.
- 성공 현금만 합산하고 `UNKNOWN` Refund를 제외하면 Provider에서 이미 성공했을 수
  있는 금액을 다시 요청할 수 있다.
- line-level 복원 원장이 없으면 부분 환불에서 복원한 포인트를 전체 취소가 다시
  복원할 수 있다.
- 남은 금액 계산과 Refund insert 사이에 다른 Refund가 commit되면 stale 잔액이
  저장될 수 있다.
- 요청액이 양수인데 Refund/snapshot 누락을 `NOT_REQUIRED`로 반환하면 commit gate
  손상이 성공처럼 보인다.
- 진행 중·불명·실패 Refund를 성공 합계에 포함하면 실제 Provider 환불 전 잔액을
  과소 표시한다.
- `UNKNOWN` 또는 `RECONCILING` 요청액을 무시하고 새 Refund를 만들면 Provider에서
  이미 성공한 금액과 합쳐 승인액을 초과할 수 있다.
- Payment lock 없이 Refund를 조회한 뒤 취소하면 조회 직후 새 부분 환불이 들어와
  stale snapshot과 이중 Provider 요청이 생길 수 있다.
- Provider key를 event ID로 만들면 같은 terminal Order fact가 새 event ID로
  재생성될 때 중복 Refund 요청이 가능하다.
- 고객 자유 `detail`을 Provider 또는 Refund에 복제하면 BR-14의 데이터 최소화 경계를
  위반한다.

## Verification

- 성공 부분 환불 뒤 고객 취소 Refund가 정확한 현금 잔액만 요청한다.
- 모든 성공 Refund 합계가 승인액을 넘지 않는다.
- 이미 복원된 line 혜택은 고객 취소에서 다시 복원되지 않는다.
- 부분 환불과 고객 취소 경쟁이 Payment lock과 DB 제약으로 직렬화된다.
- summary state가 고객 취소 Refund와 일치하고 선행 Refund state와 독립적이다.
- snapshot 세 금액과 동적 잔액이 각 기준 시점을 지킨다.

## Required Tests

- 성공 부분 환불 1건 후 남은 현금 고객 취소 Refund
- 여러 성공 부분 환불 후 남은 현금 계산
- 부분 환불과 고객 취소 동시 실행의 승인액 상한
- 부분 환불 직후 고객 취소 lock ordering과 deadlock 회귀
- line별 현금 allocation 합계와 Payment 누적 성공 환불액 일치
- 부분 환불에서 복원된 포인트·쿠폰 allocation의 이중 복원 부재
- 잔여 line 혜택만 복원되고 원 주문 snapshot과 tie-out
- DB Check/Unique 위반의 전체 Tx C1 rollback
- snapshot 승인액 = 취소 전 성공액 + 취소 요청액 CHECK
- 요청액 양수의 Refund FK 필수와 요청액 0의 NULL CHECK
- 취소 Refund 진행 상태별 현재 실제 잔액과 성공 시 0 전이
- 선행 FAILED·UNKNOWN Refund가 state와 성공 금액에 합성되지 않음
- 필요한 Refund/snapshot 누락의 고객 지연 projection과 운영 setup alert
- 선행 Refund 각 state별 고객 취소 허용·차단 matrix
- unresolved 409 전에 Order·Case·snapshot·Refund·Audit·publication·멱등 row 부재
- 선행 Refund terminal 전후 같은 key 재시도
- Order→Payment→Refund allocation lock order와 역순 lock 부재
- Payment lock timeout의 503과 전체 rollback
- reason·customer reason code 존재 조건 CHECK
- 같은 Payment의 고객 취소 Refund partial unique
- source reference와 Provider key의 Order terminal version 일치
- REQUEST 후 UNKNOWN·RECONCILING lookup까지 Provider key 불변
- 최초 REQUEST 1회와 LOOKUP 5회의 attempt 상한·전체 delay 도달
- event ID가 다른 같은 Order version 재처리의 Provider 요청 한 번
- Refund row·Provider payload·structured log에 자유 `detail`, client key와 customer
  ID 부재

## Metrics

- `beanflow.payment.cancellation_refund.remaining_amount`
- `beanflow.payment.cancellation_refund.prior_successful_refund.count`
- `beanflow.payment.refund.approved_amount_guard.conflict.count`
- `beanflow.benefit.restoration.duplicate_prevented.count{benefit_type}`

Payment, Order, Customer ID와 Provider reference는 metric tag로 사용하지 않는다.

- **Not measured:** 고객 취소 전 부분 환불 빈도와 잔여 금액 분포

## Revisit Conditions

부분 환불을 미수락 `PAID` 상태에서 금지하는 별도 정책이 Accepted 되거나, Provider가
복수 환불을 지원하지 않거나, line-level refund ledger의 운영 비용이 실제 사용 빈도에
비해 과도하다고 측정될 때

## Related Decisions

- BR-14, BR-15
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-033](ADR-033-order-compensation-case-generalization.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
- [ADR-037](ADR-037-customer-cancellation-refund-reconciliation-budget.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
