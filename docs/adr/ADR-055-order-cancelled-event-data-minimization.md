# ADR-055: OrderCancelledV1의 미사용 고객·매장·사유 필드 제거

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-034의 초기 `OrderCancelledV1` payload에는 `customerId`, `storeId`와
`reasonCode`가 포함됐다. 당시 Notification도 event consumer였고 고객·매장 routing
식별자가 필요할 수 있었다. ADR-044는 취소 접수 NotificationDelivery를 Tx C1에서
직접 저장하도록 바꾸고 Notification consumer를 제거했다.

남은 Fulfillment, Inventory, Promotion과 Loyalty consumer는 각자 Order ID로 연결된
예약·원장을 소유하며 source reference의 Order terminal version으로 중복을 판정한다.
고객 취소 사유도 owner 복원 결과를 바꾸지 않는다. 따라서 세 필드는 현재 consumer의
결정 입력이 아니며 persistent publication에 불필요한 고객·매장·행동 사유를
복제한다.

두 termination V1은 아직 production 발행, 외부 소비와 보존 publication이 없는
pre-release 상태다.

## Decision

- `OrderCancelledV1`에서 `customerId`, `storeId`, `reasonCode`를 제거한다.
- 최종 base payload는 다음과 같다.
  - 공통 event envelope
  - `orderId`
  - `cancelledAt`
  - `couponRequired`
  - `pointsRequired`
  - `couponPolicy { policyVersionId, mode, compensationValidityDays }`
  - `pointsPolicy { policyVersionId, mode, compensationValidityDays }`
- customer는 event envelope의 aggregate, correlation 또는 causation 식별자로도
  사용하지 않는다. `aggregateId`는 Order ID다.
- 네 consumer는 `orderId`로 자기 Context의 reservation/ledger를 찾는다.
  - Fulfillment: PickupReservation
  - Inventory: StockReservation
  - Promotion: CouponReservation/Issuance
  - Loyalty: PointReservation/allocation
- owner record가 없거나 source·trigger·version이 모순이면 현재 Ordering의 고객·매장
  정보나 기본값을 조회해 추정하지 않고 기존 `COMPENSATION_SOURCE_CONFLICT`로
  publication을 실패시킨다.
- required flag와 benefit policy snapshot은 계속 event에서만 사용하며 consumer가
  현재 Order 금액이나 policy head를 다시 조회하지 않는다.
- `cancelledAt`은 compensation validity 계산의 원 fact 시각이므로 유지한다.
- 고객 reason code는 Order와 허용된 AuditRecord/Refund 내부 필드에만 남고 event,
  publication JSON과 consumer log에는 복제하지 않는다.
- pre-release V1을 제자리 수정하고 V2, compatibility DTO와 이중 발행을 만들지 않는다.
  producer, 네 consumer, fixture, contract test와 Event Catalog를 한 변경에서
  갱신한다.
- 배포 전 `OrderCancelledV1` 미완료·완료 publication 0건과 외부 consumer 0개를
  재확인한다. 하나라도 존재하면 이 pre-release 결정을 적용하지 않고 별도 version
  이행 ADR을 만든다.
- 최초 production publication 이후에는 ADR-034의 V1 동결 규칙을 적용한다.

## Alternatives Considered

### 세 필드 유지

- 향후 consumer가 추가될 때 routing 조회를 줄일 수 있다.
- 현재 목적 없이 개인정보와 업무 식별자를 장기 publication 저장소에 복제한다.

### storeId만 유지

- 매장 단위 routing·관측이 쉽다.
- 네 owner가 이미 order-linked record를 소유하고 있어 처리에 필요하지 않으며 metric
  tag로도 사용하지 않는다.

## Rationale

event payload는 현재 소비자가 fact를 재현하는 데 필요한 snapshot만 가져야 한다.
Notification routing이 event 밖으로 이동한 뒤 남은 세 필드는 필요성이 사라졌고,
pre-release 시점에 제거하면 호환성 비용 없이 데이터 보존 범위를 줄일 수 있다.

## Consequences

- consumer DTO와 fixture에서 세 필드가 사라진다.
- owner consumer는 다른 Context identity 조회가 아니라 자기 order-linked 원장을
  신뢰해야 한다.
- 고객·매장별 event 분석은 publication payload가 아니라 권한 있는 read model에서
  수행한다.

## Failure Scenarios

- owner record 부재를 Ordering 조회로 보완하면 지연 시점 현재값이 취소 snapshot을
  대체한다.
- production publication이 있는데 필드를 제거하면 구 payload/consumer 호환이
  깨진다.
- reason code를 structured log로 다시 넣으면 event에서 제거한 보존 범위가 로그로
  우회된다.
- required flag나 policy snapshot까지 제거하면 owner가 현재 Order/policy를 다시
  계산하게 된다.

## Verification

- 최종 event JSON의 정확한 허용 필드 집합
- 네 consumer의 order-linked owner lookup과 타 Context identity 조회 0회
- owner record 부재·모순의 명시적 conflict
- publication·log에 customer/store/reason 부재
- 배포 전 V1 publication/external consumer 0 gate

## Required Tests

- producer serialization golden contract
- 네 consumer fixture compile/contract 갱신
- payload 추가 필드 거부 또는 absence assertion
- customerId/storeId/reasonCode의 publication JSON·structured log 부재
- owner reservation 없음, 다른 trigger/version의 conflict
- false required flag와 policy snapshot 동작 회귀
- pre-release deployment gate 실패 주입

## Metrics

- `beanflow.order.termination.event.routing_error.count{event_type,consumer}`

Order, Store, Customer와 reason code는 metric tag로 사용하지 않는다.

- **Not measured:** 제거 전후 publication payload byte 크기

## Revisit Conditions

새 독립 consumer가 실제로 고객·매장 routing snapshot을 요구할 때 새 event/version
계약으로 검토한다.

## Related Decisions

- BR-14, BR-30
- [ADR-010](ADR-010-initial-event-publication.md)
- [ADR-034](ADR-034-customer-cancellation-event-contract.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
- [ADR-044](ADR-044-cancellation-accepted-notification-durability.md)
