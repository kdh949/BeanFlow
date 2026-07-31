# ADR-047: 주문 보상 Case의 기본 고객 알림 step 범위

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-033은 매장 거절과 고객 취소가 PAYMENT, PICKUP, STOCK, COUPON, POINTS,
CUSTOMER_NOTIFICATION 여섯 보상 step을 공유하도록 정했다. ADR-044는 고객 취소의
CUSTOMER_NOTIFICATION step이 Tx C1에 저장한 취소 접수 Delivery 상태를 따르도록
정했고, ADR-045·ADR-046은 환불 성공과 지연 후속 알림을 추가했다.

후속 알림까지 한 step에 합치면 접수 알림 성공 뒤 Refund 결과를 기다리기 위해
`SUCCEEDED`를 다시 `PROCESSING`으로 바꾸거나, 지연 알림 뒤 수동 복구 성공 알림이라는
새 의무 때문에 완료된 step을 다시 열어야 한다. 이는 보상 step의 단조 상태 전이와
Case 완료 의미를 불안정하게 만든다.

## Decision

- `CUSTOMER_NOTIFICATION`은 주문 종료 직후의 **기본 고객 알림 한 건**만 추적한다.
  - `CUSTOMER_CANCELLATION`: `ORDER_CANCELLATION_ACCEPTED`
  - `STORE_REJECTION`: 기존 주문 거절 고객 알림
- 고객 취소 Tx C1은 접수 NotificationDelivery를 `PENDING`으로 저장하면서
  CUSTOMER_NOTIFICATION step을 `PROCESSING`으로 저장한다.
- 접수 Delivery의 상태가 step을 다음처럼 단조 갱신한다.

  | Delivery 결과 | CUSTOMER_NOTIFICATION step |
  |---|---|
  | `PENDING`, `PROCESSING` | `PROCESSING` |
  | `RETRY_SCHEDULED` | `RETRY_SCHEDULED` |
  | `SUCCEEDED` | `SUCCEEDED` |
  | `MANUAL_REVIEW` | `MANUAL_REVIEW` |

- 같은 logical source의 replay는 step 상태와 business attempt를 바꾸지 않는다.
  Provider 발송 시도만 실제 시도 때 증가한다.
- 환불 성공·지연 후속 알림 event, publication, Delivery와 Provider 결과는
  CUSTOMER_NOTIFICATION step을 생성·갱신·재개하지 않는다.
- 후속 event publication 소진은 `EVENT_PUBLICATION` ReprocessingCase,
  후속 Delivery 발송 소진은 `NOTIFICATION_DELIVERY` ReprocessingCase에서 각각
  추적한다.
- Case가 `SUCCEEDED`가 된 뒤 후속 알림이 실패해도 Case나 기본 알림 step을 이전
  상태로 되돌리지 않는다. Refund/PAYMENT step 상태는 실제 금융 결과 규칙을
  독립적으로 따른다.
- 운영 상세 조회는 Order terminal version을 기준으로 OrderCompensationCase,
  기본 알림 Delivery, 환불 후속 event publication, 후속 Delivery와 두 종류의
  ReprocessingCase를 함께 탐색할 수 있어야 한다. 이것은 상태를 합성한다는 뜻이
  아니라 연결된 독립 원장을 한 화면에 제시한다는 뜻이다.
- 공통 여섯-step enum과 `(case_id, step_type)` 유일성은 유지한다. 일곱 번째 환불
  알림 step을 추가하지 않는다.

## Alternatives Considered

### 모든 취소 알림을 한 step에 포함

- Case에서 고객 알림 전체를 한 상태로 볼 수 있다.
- 환불 지연 뒤 성공처럼 미래 의무가 추가돼 terminal step을 재개하거나 별도 child
  상태를 만들어야 한다.

### REFUND_NOTIFICATION step 추가

- 기본 알림과 환불 알림을 구분해 Case에 표시할 수 있다.
- 매장 거절과 공유하는 여섯-step 구조가 trigger별 가변 shape로 바뀌고, 지연 뒤 성공
  두 번째 알림 문제는 여전히 남는다.

## Rationale

보상 step은 주문 종료 직후 반드시 준비되는 owner 작업을 단조롭게 추적하는 데
적합하다. Refund 결과에서 나중에 생기는 통지는 이미 event publication과
NotificationDelivery라는 독립적인 내구 상태가 있으므로 이를 Case step에 다시
합성하지 않는 편이 실패 범위와 완료 의미를 정확히 유지한다.

## Consequences

- Case `SUCCEEDED`는 기본 고객 알림까지 완료됐다는 뜻이지 모든 미래 환불 통지가
  완료됐다는 뜻은 아니다.
- 운영 UI는 Case와 후속 알림 원장을 별도 badge/section으로 보여줘야 한다.
- 후속 알림 장애는 Case 수치가 아니라 event publication과 Notification
  ReprocessingCase 지표에 나타난다.

## Failure Scenarios

- 후속 알림이 기본 step을 다시 열면 Case terminal 상태가 비단조적으로 변한다.
- 후속 publication 소진을 기본 step 실패로 기록하면 실제 접수 알림 성공을
  `MANUAL_REVIEW`로 왜곡한다.
- Case만 조회하고 후속 원장을 숨기면 운영자가 누락된 성공·지연 알림을 발견하지
  못한다.
- 기본 Delivery와 후속 Delivery가 같은 logical source를 쓰면 한쪽이 unique 충돌로
  누락된다.

## Verification

- 고객 취소 접수 Delivery 상태와 기본 step의 단조 매핑
- 후속 success/delayed event·Delivery 처리 전후 기본 step 불변
- Case 완료 뒤 후속 publication·Delivery 실패의 Case 불변
- 후속 실패별 올바른 ReprocessingCase와 운영 조회 연결
- 여섯-step enum과 Case unique shape 유지

## Required Tests

- 접수 Delivery 성공·retry·manual review의 step 전이
- 같은 source replay의 상태·attempt 불변
- 환불 성공·지연 event 처리의 CUSTOMER_NOTIFICATION step write 0회
- Case 성공 후 후속 publication 다섯 번 소진
- 후속 Provider 네 번째 실패의 별도 Notification ReprocessingCase
- 기본·성공·지연 logical source 세 종류의 비충돌
- 운영 상세 projection의 독립 상태 연결

## Metrics

- `beanflow.order.compensation.step.count{trigger,type,state}`
- `beanflow.notification.cancellation_followup.manual_review.count{stage}`

Order와 Customer 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** Case 완료와 후속 환불 알림 완료 사이 시간

## Revisit Conditions

OrderCompensationCase가 고정 step enum 대신 의무별 child workflow를 지원하거나,
제품이 모든 고객 통지 완료를 하나의 SLA로 정의할 때

## Related Decisions

- BR-14, BR-27
- [ADR-019](ADR-019-notification-retry-and-manual-recovery.md)
- [ADR-033](ADR-033-order-compensation-case-generalization.md)
- [ADR-044](ADR-044-cancellation-accepted-notification-durability.md)
- [ADR-045](ADR-045-cancellation-refund-customer-notifications.md)
- [ADR-046](ADR-046-cancellation-refund-notification-events.md)
