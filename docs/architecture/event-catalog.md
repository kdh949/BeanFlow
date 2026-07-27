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
| PaymentApproved | Payment | Ordering, Settlement | payment ID + version | Payment |
| PaymentApprovalUnknown | Payment | Operations | reconciliation case unique | Payment |
| PaymentApprovalReconciled | Payment | Ordering, Settlement | provider transaction unique | Payment |
| OrderPaid | Ordering | Fulfillment | order version | Order |
| OrderAccepted | Ordering | Inventory/Notification as needed | order version | Order |
| OrderReady | Ordering | Notification | event+recipient+channel unique | Order |
| OrderCompleted | Ordering | Loyalty, Settlement, Analytics | source order unique per consumer | Order |
| PaymentRefunded | Payment | Ordering, Loyalty, Settlement | refund ID unique | Payment |
| PointsAccrued | Loyalty | Analytics | source order unique | PointTransaction |
| PointsRestored | Loyalty | Analytics | refund/reference unique | PointTransaction |
| SettlementItemCreated | Settlement | Analytics | source transaction unique | SettlementItem |
| SettlementBatchConfirmed | Settlement | Dispute/Notification | batch version | SettlementBatch |
| SettlementAdjustmentCreated | Settlement | Analytics | adjustment ID | SettlementAdjustment |
| SettlementDisputeFiled | Dispute | Operations | dispute ID | SettlementDispute |
| NotificationFailed | Notification | Operations | delivery ID | NotificationDelivery |

## Delivery principles

- 초기 모듈 내부 전달은 Spring application event 또는 Spring Modulith event를 사용한다.
- 영속 전달과 재시작 복구가 필요한 흐름은 Outbox 또는 Spring Modulith Event Publication Registry를 검토한다.
- Kafka는 독립 소비자, replay, 분리 배포 요구가 확인되기 전에는 필수가 아니다.
- 메시지 broker의 exactly-once 표현에 의존하지 않고 소비자 부작용을 멱등하게 만든다.
