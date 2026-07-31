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
| OrderCancelledV1 | Ordering | Fulfillment, Inventory, Promotion, Loyalty | order ID + terminal version + owner step source reference | Order |
| PaymentApproved | Payment | Ordering, Analytics | payment ID + version | Payment |
| PaymentApprovalUnknown | Payment | Operations | reconciliation case unique | Payment |
| PaymentApprovalReconciled | Payment | Ordering, Operations, Analytics | provider transaction unique | Payment |
| OrderPaid | Ordering | Fulfillment, Inventory, Promotion, Loyalty | order version per consumer | Order |
| OrderRejectedV1 | Ordering | Payment, Fulfillment, Inventory, Promotion, Loyalty, Notification | event ID + owner source reference | Order |
| StoreAcceptanceWarningRequestedV1 | Ordering | Notification | order/deadline unique | Order |
| OrderAcceptedV1 | Ordering | Analytics | order version | Order |
| OrderReadyV1 | Ordering | Notification | event+recipient+logical channel unique | Order |
| OrderCompletedV1 | Ordering | Loyalty, Settlement, Analytics | source order unique per consumer | Order |
| PaymentRefundUnknown | Payment | Operations | refund/provider request unique | Refund |
| PaymentRefunded | Payment | Ordering, Loyalty, Settlement, Analytics | refund ID/source unique; Settlement의 미완료 고객 취소는 Audit 후 NOT_APPLICABLE | Refund |
| CustomerCancellationRefundSucceededV1 | Payment | Notification | order ID + customer cancellation terminal version + refund-succeeded | Refund |
| CustomerCancellationRefundDelayedV1 | Payment | Notification | order ID + customer cancellation terminal version + refund-delayed | Refund |
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

`OrderCancelledV1`은 미수락 `PAID` 고객 취소의 비동기 owner 보상 fact다.
`PENDING_PAYMENT` 고객 취소는 네 예약 해제를 주문 명령 transaction에서 완결하므로
이 event를 발행하지 않는다.

`CustomerCancellationRefundSucceededV1`과
`CustomerCancellationRefundDelayedV1`은 고객 취소 Refund의 실제 terminal 결과만
표현한다. payload는 공통 envelope, `orderId`, `customerId`,
`orderAggregateVersion`, `refundAmountKrw`, `outcomeAt`이다. Refund·Payment·Provider
식별자, 내부 상태·오류·attempt, reason, client key와 자유 입력 detail은 포함하지
않는다. Notification은 일반 `PaymentRefunded`를 고객 취소 알림 근거로 사용하지
않는다.

OrderCompensationCase 전체 완료는 고객 Notification event를 생산하지 않는다.
슬롯·재고·쿠폰·포인트 복원 완료도 개별 고객 알림 event로 확장하지 않는다.

### `OrderCancelledV1` base payload

- 공통 `EventEnvelope`
- `orderId`
- `cancelledAt`
- `couponRequired`
- `pointsRequired`
- `couponPolicy { policyVersionId, mode, compensationValidityDays }`
- `pointsPolicy { policyVersionId, mode, compensationValidityDays }`

`couponRequired`는 `couponDiscountKrw > 0`, `pointsRequired`는
`pointsAppliedKrw > 0`인 취소 시점 Order snapshot에서 산출한다. actor,
`cancellationCause`, 취소 전 상태, `paymentRequired`, 자유 입력 `detail`, 금액, 자원
ID, Provider reference, `customerId`, `storeId`와 `reasonCode`는 payload에 포함하지
않는다. 두 policy snapshot은 required
flag와 관계없이 항상 존재하고 Case가 참조하는 immutable version과 일치한다.
consumer는 현재 policy head를 조회하지 않는다.

`OrderRejectedV1`도 ADR-041에 따라 같은 `couponPolicy/pointsPolicy` shape를 목표로
한다. 다만 기존 단일 `policyVersion/policyMode/policyValidityDays`를 제자리에서
제거하는 것은 ADR-059 release gate가 보존할 publication·외부 consumer·rollback
대상이 모두 없음을 입증한 경우에만 허용한다. gate가 실패하면 V1을 변경하지 않고
forward migration과 compatibility 계획을 먼저 확정한다. 최초 production publication
이후에는 두 V1 계약을 동결한다.

`correlationId`는 취소 HTTP 요청의 값을 전파하고 부재하면 서버가 생성한다.
`causationId`는 `customer-cancellation-command:{cancellationCommandId}`이며
`cancellationCommandId`는 고객 취소 멱등 레코드의 내부 UUID다. client
`Idempotency-Key`와 customer ID를 lineage에 넣지 않는다. publication 재시도는 최초
envelope를 그대로 재사용한다.

Owner source reference는
`order:{orderId}:customer-cancellation:{aggregateVersion}:{step}`이다. event
consumer step은 `pickup`, `stock`, `coupon`, `points` 네 개다.
Tx C1이 생성하는 Refund와 NotificationDelivery는 같은 형식의 `payment`,
`notification` step을 사용하지만 Payment와 Notification은 이 event의 consumer가
아니다. 같은 Order version을 표현하는 event는 event ID가 달라도 owner work를 새로
만들지 않는다. event ID는 publication과 추적에 사용하고 owner 부수효과의 유일한
중복 기준으로 사용하지 않는다.

Pickup과 Stock consumer는 `OrderCancelledV1`을
`restorationTrigger = CUSTOMER_CANCELLATION`으로 매핑하고 공통
`RELEASED_AFTER_TERMINATION` 전이를 호출한다. `OrderRejectedV1`은
`STORE_REJECTION`으로 매핑한다. trigger를 source 문자열에서 추론하지 않는다.

Coupon과 Points consumer는 event의 해당 benefit policy snapshot을 owner 복원
metadata에 저장한다. 결과 type/disposition과 별도로 source, trigger,
`policyVersionId`가 모두 일치해야 중복 delivery로 인정한다.

Consumer는 source-aware하게 수렴한다. 같은 source의 owner work가 진행 중이거나
완료됐으면 새 work·attempt 없이 기존 상태를 반환하고, 아직 적용 가능한 상태면 한 번
적용한다. 다른 source·trigger·version이 이미 적용됐거나 owner 상태가 event와
모순이면 덮어쓰거나 성공으로 간주하지 않고 `COMPENSATION_SOURCE_CONFLICT`로
publication을 실패시킨다. Order의 `CANCELLED` 전이는 되돌리지 않으며 기존 bounded
publication retry와 `MANUAL_REVIEW` 절차를 따른다.

Listener별 publication 재시도가 소진되면 해당 listener에 대응하는 단일 보상 step만
`MANUAL_REVIEW`와 `EVENT_PUBLICATION_RETRY_EXHAUSTED`를 기록한다. Case state는
step에서 파생하므로 `MANUAL_REVIEW`가 되지만 다른 owner publication은 계속 처리한다.
publication completion attempt는 owner business attempt가 아니므로 보상 step의
`attemptCount`에 합산하지 않는다. 이 규칙은 `OrderCancelledV1`과
`OrderRejectedV1`에 동일하게 적용한다.

`OrderCancelledV1`은 구현 전 최종 payload를 완성하고 최초 운영 publication부터
동결한다. 필수 필드 제거, 이름·타입·의미 변경은 `OrderCancelledV2`로 이행한다. 구
consumer가 무시할 수 있고 역직렬화 기본값이 있는 선택 필드만 V1에 추가할 수 있다.
V1 listener와 legacy target-to-step mapping은 미완료 V1 publication이 없고 승인된
rollback 기간이 끝날 때까지 유지한다. 이중 발행은 별도 Accepted ADR이 있을 때만
사용한다.

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
