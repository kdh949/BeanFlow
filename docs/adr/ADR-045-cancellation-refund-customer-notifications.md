# ADR-045: 고객 취소 환불의 성공·지연 후속 알림

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-044는 `PENDING_PAYMENT`와 `PAID` 고객 취소가 확정되면
`ORDER_CANCELLATION_ACCEPTED` 접수 알림을 내구 저장하도록 정했다. 접수 알림은 환불
완료를 뜻하지 않는다. 현금 환불은 접수 뒤 외부 Provider와 reconciliation을 거쳐
성공하거나 자동 처리가 끝난 지연 상태가 될 수 있으므로, 고객이 주문 화면을 계속
조회하지 않아도 중요한 결과를 알 수 있는 정책이 필요하다.

내부 Refund의 `FAILED`, `MANUAL_REVIEW`, 오류 code와 retry 횟수는 ADR-038에 따라
고객에게 그대로 노출하지 않는다.

## Decision

- 현금 환불액이 양수인 `PAID` 고객 취소에는 접수 알림 외에 다음 두 고객 알림을
  사용한다.
  - `CUSTOMER_CANCELLATION_REFUND_SUCCEEDED`: 이 고객 취소 source의 Refund가
    `SUCCEEDED`로 확정됐을 때 한 번
  - `CUSTOMER_CANCELLATION_REFUND_DELAYED`: 자동 REQUEST·LOOKUP 처리가 끝나 내부
    Refund 또는 PAYMENT step이 `FAILED`나 `MANUAL_REVIEW`에 도달했을 때 한 번
- 진행 중인 `REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`,
  `RECONCILING` 전이에는 후속 알림을 보내지 않는다.
- `PENDING_PAYMENT`와 `BENEFIT_ONLY` 취소에는 현금 Refund가 없으므로 두 후속 알림을
  만들지 않는다.
- `FAILED` 뒤 `MANUAL_REVIEW`로 전이하거나 같은 terminal 상태를 재처리해도 지연
  알림은 하나다. 논리 source는
  `order:{orderId}:customer-cancellation:{aggregateVersion}:refund-delayed`다.
- 지연 알림 뒤 운영 복구로 Refund가 `SUCCEEDED`가 되면 성공 알림을 별도로 한 번
  보낸다. 성공 source는
  `order:{orderId}:customer-cancellation:{aggregateVersion}:refund-succeeded`다.
- 알림의 취소 source는 Refund의
  `order:{orderId}:customer-cancellation:{aggregateVersion}:payment`와 정확히
  연결돼야 한다. 다른 부분 환불이나 매장 거절 Refund 상태로 고객 취소 후속 알림을
  만들지 않는다.
- 지연 알림은 환불 실패나 수동 검토를 확정적으로 표현하지 않는다. 고객 문구는
  “환불 처리가 지연되고 있습니다. 불편을 드려 죄송합니다. 최대한 빠르게
  처리하겠습니다.”를 사용한다.
- 성공 알림은 실제 `SUCCEEDED` 원장만 근거로 하며 요청 접수, Provider 응답 수신 또는
  reconciliation 시작을 성공으로 표현하지 않는다.
- payload에는 `orderId`, 환불 성공 시각 또는 지연 판정 시각, 표시할 환불액과 최소
  locale 정보만 둔다. client Idempotency-Key, 자유 입력 `detail`, Payment·Refund·
  Provider 식별자, 내부 상태·오류 code·retry 횟수는 넣지 않는다.
- Notification Provider 발송 실패는 Refund나 Order 상태를 되돌리지 않고 BR-27의
  delivery retry와 `MANUAL_REVIEW`로 남긴다.
- OrderCompensationCase 전체 `SUCCEEDED`, 슬롯·재고 복원 완료와 쿠폰·포인트 복원
  완료에는 별도 고객 알림을 보내지 않는다. Notification용
  `OrderCompensationCompleted` event나 Delivery를 만들지 않는다.
- 고객은 쿠폰·포인트 결과를 기존 보유 내역에서 확인하고, 보상 step 상세는 운영자
  전용으로 유지한다.
- 후속 delivery는 ADR-046의 영속 결과 event 경계로 만들고, ADR-047에 따라 공통
  CUSTOMER_NOTIFICATION 보상 step에는 합성하지 않는다.

## Alternatives Considered

### 성공 알림만

- 완료된 환불만 확실하게 전달해 알림 수가 적다.
- 자동 복구가 끝난 장기 지연을 고객이 주문 화면을 열기 전까지 알 수 없다.

### 접수 알림만

- template와 상태 전이 규칙이 가장 단순하다.
- 접수와 실제 금전 결과 사이의 중요한 차이를 고객이 능동 조회해야만 알 수 있다.

### 모든 retry·unknown 전이에 알림

- 처리 상태를 가장 촘촘하게 전달한다.
- 상태 진동과 재처리마다 알림이 늘고 내부 복구 semantics가 고객에게 노출된다.

## Rationale

실제 금전 결과인 성공과 자동 처리로 해소되지 않은 지연만 알리면 고객에게 필요한
행동 정보를 제공하면서 내부 상태 전이와 재시도 소음을 숨길 수 있다. 성공·지연을
서로 다른 안정적인 logical source로 저장하면 지연 뒤 성공도 표현하면서 각 알림의
중복은 막을 수 있다.

## Consequences

- Notification에는 접수 외에 성공·지연 template 두 개가 추가된다.
- Refund 결과 처리 또는 그 내구 event 소비자는 고객 취소 source와 terminal 전이를
  판별해야 한다.
- 지연 후 성공에는 두 알림이 순서대로 존재할 수 있다.
- 알림 실패는 금전 원장과 분리되며 운영자는 Refund와 NotificationDelivery를 각각
  복구한다.
- 보상 Case 완료가 추가 고객 알림 의무를 만들지 않으므로 Case와 Notification 사이의
  순환 완료 조건이 없다.

## Failure Scenarios

- 다른 부분 환불의 성공을 고객 취소 성공으로 오인하면 잘못된 완료 알림을 보낸다.
- `UNKNOWN` 진입 때 지연 알림을 보내면 이후 자동 reconciliation 성공 전 불필요한
  불안을 준다.
- `FAILED`와 `MANUAL_REVIEW` 각각 새 지연 delivery를 만들면 같은 사건을 중복
  통지한다.
- 지연 뒤 성공 알림을 막으면 고객은 이미 해결된 환불을 계속 지연으로 인식한다.
- Notification 실패를 Refund 실패로 바꾸면 실제 금전 결과와 고객 전달 결과가
  뒤섞인다.

## Verification

- 고객 취소 Refund `SUCCEEDED`당 성공 delivery 한 건
- 자동 처리 종료 후 지연 source당 지연 delivery 한 건
- 진행·불명·재시도 상태의 후속 delivery 0건
- 지연 뒤 성공 시 서로 다른 두 logical delivery
- 다른 Refund reason/source, `PENDING_PAYMENT`, `BENEFIT_ONLY`의 후속 delivery 0건
- payload와 log에 금지 필드 부재
- Notification Provider 실패에도 Refund와 Order terminal 상태 불변

## Required Tests

- 직접 성공과 LOOKUP reconciliation 성공의 성공 알림
- retryable failure 소진과 lookup 소진의 지연 알림
- `FAILED → MANUAL_REVIEW` 및 replay의 지연 알림 중복 방지
- 지연 뒤 수동 복구 성공의 성공 알림
- 다른 부분 환불·매장 거절 환불의 고객 취소 template 부재
- 0원 취소 두 경로의 후속 알림 부재
- template payload 최소화와 고객 문구 계약
- delivery worker 네 번째 실패의 Notification 수동 검토와 Refund 불변

## Metrics

- `beanflow.notification.cancellation_refund.count{template,outcome}`
- `beanflow.notification.cancellation_refund.lag{template}`

Order, Customer, Payment, Refund와 Provider 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** 알림 열람률과 알림 뒤 문의 감소율

## Revisit Conditions

고객이 notification preference를 직접 관리하거나 법적 고지 채널·기한, 혜택 복원
완료 알림 요구가 별도 정책으로 확정될 때

## Related Decisions

- BR-14, BR-27
- [ADR-019](ADR-019-notification-retry-and-manual-recovery.md)
- [ADR-033](ADR-033-order-compensation-case-generalization.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
- [ADR-044](ADR-044-cancellation-accepted-notification-durability.md)
