# Event Catalog

## Event envelope

모든 영속 이벤트는 최소한 다음 필드를 가진다.

- `eventId`
- `eventType`
- `aggregateId`
- `aggregateVersion`
- `occurredAt`
- `payloadVersion`
- `correlationId`
- `causationId`

## Events

| Event | Producer | Consumers | Duplicate handling | Source of truth |
|---|---|---|---|---|
| OrderPlaced | Ordering | internal orchestration | event ID / order version | Order |
| OrderExpired | Ordering | Fulfillment, Inventory, Promotion, Loyalty | order ID + terminal version | Order |
| OrderCancelled | Ordering | Payment, Fulfillment, Inventory, Promotion, Loyalty | order ID + terminal version | Order |
| PaymentApproved | Payment | Ordering, Analytics | payment ID + version | Payment |
| PaymentApprovalUnknown | Payment | Operations | reconciliation case unique | Payment |
| PaymentApprovalReconciled | Payment | Ordering, Operations, Analytics | provider transaction unique | Payment |
| OrderPaid | Ordering | Fulfillment, Inventory, Promotion, Loyalty | order version per consumer | Order |
| OrderRejectedV1 | Ordering | Payment, Fulfillment, Inventory, Promotion, Loyalty, Notification, Operations | event ID + owner source reference | Order |
| StoreAcceptanceWarningRequestedV1 | Ordering | Notification | order/deadline unique | Order |
| OrderAcceptedV1 | Ordering | Analytics | order version | Order |
| OrderReadyV1 | Ordering | Notification | event+recipient+logical channel unique | Order |
| OrderCompletedV1 | Ordering | Loyalty, Settlement, Analytics | source order unique per consumer | Order |
| PaymentRefundUnknown | Payment | Operations | refund/provider request unique | Refund |
| PaymentRefunded | Payment | Ordering, Loyalty, Settlement, Analytics | refund ID unique | Refund |
| PointsAccrued | Loyalty | Analytics | source order unique | PointTransaction |
| PointsRestored | Loyalty | Analytics | refund/reference unique | PointTransaction |
| PointRecoveryPendingRecorded | Loyalty | Operations, Analytics | refund/reference unique | PointTransaction |
| SettlementItemCreated | Settlement | Analytics | source transaction unique | SettlementItem |
| SettlementBatchConfirmed | Settlement | Dispute/Notification | batch version | SettlementBatch |
| SettlementAdjustmentCreated | Settlement | Analytics | adjustment ID | SettlementAdjustment |
| SettlementDisputeFiled | Dispute | Operations | dispute ID | SettlementDispute |
| SettlementDisputeDecided | Dispute | Settlement, Notification, Operations | dispute ID + terminal version | SettlementDispute |
| NotificationFailed | Notification | Operations | delivery ID | NotificationDelivery |
| AnalyticsBackfillRequired | Analytics | Operations | source event/day unique | ReprocessingCase |

## Delivery principles

- 초기 모듈 내부 전달은 Spring application event 또는 Spring Modulith event를 사용한다.
- 금액·재고·슬롯·쿠폰·포인트·정산·알림·Analytics projection을 변경하는 cross-module
  event는 원본 트랜잭션과 함께 영속 publication을 기록한다.
- 단순한 동일 요청 내부 orchestration은 동기 Application API를 사용할 수 있지만,
  이미 확정된 사실의 후속 처리를 in-memory event만으로 완료했다고 간주하지 않는다.
- 영속 전달과 재시작 복구는 Spring Modulith JPA Event Publication Registry를
  사용한다. listener별 publication은 원 fact transaction에 함께 저장되고
  `@ApplicationModuleListener` consumer가 성공한 뒤 완료 처리한다.
- 미완료 publication은 10초, 30초, 2분, 5분, 15분 간격으로 최대 다섯 번
  재발행한다. 이후 Operations case를 `MANUAL_REVIEW`로 남긴다.
- Kafka는 독립 소비자, replay, 분리 배포 요구가 확인되기 전에는 필수가 아니다.
- 메시지 broker의 exactly-once 표현에 의존하지 않고 소비자 부작용을 멱등하게 만든다.
- Settlement는 `PaymentApproved`만으로 SettlementItem을 만들지 않는다. Item 생성
  기준은 BR-16의 `OrderCompleted`이고, payment/refund fact는 금액 입력과 조정에 사용한다.
- event consumer 실패는 publication을 완료 처리하지 않으며 retry count, last failure와
  manual recovery 경로를 보존한다.
