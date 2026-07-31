# ADR-046: 고객 취소 환불 후속 알림의 영속 결과 이벤트 경계

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-045는 현금 환불이 있는 고객 취소에 성공·지연 후속 알림을 각각 한 번 보내도록
정했다. Refund는 Payment Context가 소유하고 NotificationDelivery는 Notification
Context가 소유한다. 외부 Provider 결과를 기록하는 Payment transaction이
NotificationDelivery까지 직접 저장하면 알림 저장 장애가 금융 결과 기록을
rollback시킨다. 반대로 commit 뒤 in-memory event만 보내면 프로세스 종료 시 알림
작업을 영구히 잃을 수 있다.

기존 `PaymentRefunded`는 주문·포인트·정산·분석을 위한 일반 환불 사실이다. 고객 취소
알림의 지연 의미와 고객 책임 취소 source를 이 범용 event의 optional field와 consumer
분기로 확장하면 서로 다른 책임이 결합된다.

## Decision

- Payment는 고객 취소 Refund의 결과에만 다음 별도 domain event를 생산한다.
  - `CustomerCancellationRefundSucceededV1`
  - `CustomerCancellationRefundDelayedV1`
- `CustomerCancellationRefundSucceededV1`은 해당 Refund가 처음 `SUCCEEDED`로
  확정되는 result transaction에서 저장한다.
- `CustomerCancellationRefundDelayedV1`은 자동 REQUEST·LOOKUP 처리가 끝나 Refund
  또는 PAYMENT step이 처음 `FAILED`나 `MANUAL_REVIEW`로 확정되는 result transaction에서
  저장한다.
- event와 Notification listener용 Spring Modulith persistent publication은 Refund
  terminal 상태, Payment recovery 상태와 PAYMENT step 갱신과 같은 local transaction에
  commit한다. event serialization 또는 publication insert 실패는 해당 result
  transaction을 rollback한다.
- 외부 Refund Provider 호출은 위 transaction 밖에서 유지한다. Provider가 이미
  성공했는데 result transaction이 rollback되면 새 환불 REQUEST를 만들지 않고 같은
  Provider key 조회와 기존 reconciliation 경로로 결과를 다시 확정한다.
- Notification은 두 event의 유일한 consumer이며 각각 ADR-045의 stable logical
  source로 NotificationDelivery를 별도 transaction에서 만든다.
- listener는 같은 logical source의 Delivery가 진행 또는 완료 중이면 새 delivery나
  Provider attempt를 만들지 않고 기존 결과로 멱등 성공한다. 다른 payload가 같은
  source를 점유하면 성공으로 덮지 않고 `NOTIFICATION_SOURCE_CONFLICT`로 publication을
  실패시킨다.
- event payload는 공통 envelope와 다음 최소 snapshot을 가진다.

  | 필드 | 의미 |
  |---|---|
  | `orderId` | 주문과 고객 알림 조회 기준 |
  | `customerId` | 수신자 routing snapshot |
  | `orderAggregateVersion` | 고객 취소 logical source version |
  | `refundAmountKrw` | template에 표시할 이번 고객 취소 환불액 |
  | `outcomeAt` | 성공 또는 지연 확정 시각 |

- event type이 결과와 고객 취소 의미를 표현하므로 Refund ID, Payment ID, Provider
  reference, reason code, 내부 Refund 상태·오류 code·attempt, client
  Idempotency-Key와 자유 입력 `detail`은 payload에 넣지 않는다.
- event의 `correlationId`는 원 고객 취소 correlation을 보존한다.
  `causationId`는 해당 Refund result transition의 내부 operation ID를 사용하며
  Provider reference나 client key를 사용하지 않는다.
- 성공 시 기존 `PaymentRefunded`가 필요한 다른 consumer를 위해 함께 생산될 수
  있지만, Notification은 고객 취소 후속 알림을 만들 때 일반 `PaymentRefunded`를
  소비하거나 reason을 추론하지 않는다.
- DB polling scanner로 terminal Refund를 재탐색하는 보조 경로를 만들지 않는다.
- publication retry와 소진은 ADR-010의 공통 규칙을 따르며, 소진 시
  `EVENT_PUBLICATION` ReprocessingCase에 실제 event type과 Notification target을
  남긴다. 이 실패를 Refund 실패로 바꾸거나 Order를 되돌리지 않는다.
- ADR-047에 따라 이 후속 알림 publication과 Delivery는 공통 주문 보상 Case의
  CUSTOMER_NOTIFICATION step을 갱신하지 않는다.

## Alternatives Considered

### Payment result transaction에서 Delivery 직접 저장

- Refund terminal 상태와 알림 work가 한 transaction에 존재한다.
- Notification schema/API 장애가 외부 금융 결과의 로컬 확정을 막고 Payment와
  Notification의 쓰기 결합이 커진다.

### Refund terminal row를 주기적으로 scan

- Payment result transaction에 event publication이 없다.
- 누락 없는 watermark, 재스캔 범위, leader election과 중복 방지라는 별도 운영
  체계가 필요하고 알림 지연이 scan 주기에 종속된다.

### 일반 `PaymentRefunded` 재사용

- 성공 event 하나를 줄일 수 있다.
- 지연에는 별도 event가 여전히 필요하고 Notification이 환불 reason/source를
  해석해야 하며 범용 event payload가 알림 routing 요구로 확장된다.

## Rationale

Payment가 자신이 확정한 결과 fact를 원 transaction과 함께 영속화하고 Notification이
독립적으로 Delivery를 만들면 금융 원장 소유권과 알림 실패 격리를 함께 유지할 수
있다. 고객 취소 전용 타입은 다른 환불을 잘못 알리는 분기를 제거하고 payload를 작게
고정한다.

## Consequences

- Eventing API와 catalog에 두 V1 타입이 추가된다.
- Refund 결과 transaction은 결과 event와 Notification publication insert를
  commit gate로 갖는다.
- Notification listener 장애는 Refund terminal 결과를 되돌리지 않고 publication
  retry로 격리된다.
- 성공 고객 취소 Refund는 일반 `PaymentRefunded`와 전용 성공 event를 같은
  transaction에서 생산할 수 있다.

## Failure Scenarios

- result commit 뒤 in-memory event만 보내면 프로세스 종료 시 고객 알림이 누락된다.
- Provider 성공 뒤 event publication insert가 실패했다고 새 Refund REQUEST를
  실행하면 이중 환불 위험이 있다.
- 일반 Refund를 customer cancellation로 잘못 분류하면 다른 환불의 수신자에게 잘못된
  template을 보낸다.
- listener가 event ID만 중복 기준으로 쓰면 같은 결과를 새 event ID로 재구성했을 때
  Delivery가 중복된다.
- 성공·지연을 하나의 logical source로 합치면 지연 뒤 성공 알림 중 하나가 막힌다.

## Verification

- Refund terminal 결과와 대응 event publication의 원자적 commit/rollback
- Provider 호출과 event result transaction의 분리
- 전용 source Refund 외 두 event 0건
- event별 NotificationDelivery 단일 생성과 payload conflict 실패
- 지연 뒤 성공의 두 event·두 Delivery
- 일반 `PaymentRefunded`만으로 후속 알림이 만들어지지 않음
- publication 소진 뒤 Refund·Order 상태 불변과 ReprocessingCase 존재

## Required Tests

- 직접 성공, REQUEST retry 성공과 LOOKUP 성공의 성공 event
- retryable failure 소진과 LOOKUP 소진의 지연 event
- 같은 terminal transition replay의 event/publication 중복 방지
- result 저장·event serialization·publication insert 실패 주입
- Provider 성공 후 DB rollback과 같은-key reconciliation
- listener crash 전후 delivery 중복 방지
- event payload 금지 필드와 lineage 계약
- 다른 Refund reason/source의 전용 event 부재
- publication 다섯 번 소진과 수동 재처리

## Metrics

- `beanflow.event.customer_cancellation_refund.count{event_type}`
- `beanflow.event.customer_cancellation_refund.publication_lag{event_type}`
- `beanflow.event.customer_cancellation_refund.publication_manual_review.count{event_type}`

Order, Customer, Payment, Refund와 Provider 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** event 방식과 직접 저장 방식의 처리량 비교

## Revisit Conditions

Payment와 Notification이 별도 database 또는 배포 단위로 분리돼 Spring Modulith
publication을 사용할 수 없게 될 때

## Related Decisions

- BR-14, BR-27
- [ADR-010](ADR-010-initial-event-publication.md)
- [ADR-019](ADR-019-notification-retry-and-manual-recovery.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
- [ADR-045](ADR-045-cancellation-refund-customer-notifications.md)
