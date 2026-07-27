# Context Map

`Ordering`이 Order와 접수·제조·픽업 상태를 소유한다. `Fulfillment`는 PickupSlot과
PickupReservation을 소유하며 Order 상태를 복제하지 않는다. API의 `store-orders`는
매장 관점의 Order 표현이지 별도 Aggregate가 아니다.

```text
Identity ── actorId, membership ───────────────────────────────┐
                                                              v
Merchant ── store/menu/price/business hours/sellable requirements ──> Ordering / Discovery

Ordering ── reserve/confirm/release ──> Fulfillment / Inventory
Ordering ── validate/reserve/use ─────> Promotion
Ordering ── reserve/use/restore ──────> Loyalty
Ordering ── approval command ─────────> Payment

Payment ── approval/refund facts ─────> Ordering
Ordering ── OrderReady ───────────────> Notification
Ordering ── OrderCompleted ───────────> Loyalty / Settlement / Analytics
Payment ── refund facts ──────────────> Loyalty / Settlement / Analytics

Settlement ── confirmed views ────────> Dispute
Dispute ── accepted adjustment command ──> Settlement

All transaction contexts ── failures/audit facts ──> Operations
All source contexts ── idempotent business facts ──> Analytics
```

## Relationship rules

| Upstream | Downstream | Data owner | Interaction | Consistency |
|---|---|---|---|---|
| Identity | API/Application Services | Identity | 인증 actor와 membership 조회 | 요청 시 동기 |
| Merchant | Ordering | Merchant | 메뉴 구성·가격·sellable requirement 공개 동기 조회 | 주문 생성 시 현재 값 필요 |
| Merchant | Discovery | Merchant / Discovery Read Model | 동기 조회 또는 영속 projection | 검색 응답은 source freshness를 명시 |
| Ordering | Fulfillment | Fulfillment | 예약 Application API | 주문 생성 트랜잭션 내 강한 일관성 |
| Ordering | Inventory | Inventory | 예약 Application API | 주문 생성 트랜잭션 내 강한 일관성 |
| Ordering | Promotion | Promotion | 검증·예약 API | 주문 금액 확정 전 필요 |
| Ordering | Loyalty | Loyalty | 포인트 예약 API | 주문 금액 확정 전 필요 |
| Ordering | Payment | Ordering / Payment | Payment command, fact event | 외부 호출과 DB tx 분리 |
| Payment | Ordering | Payment | 승인·거절·불명·환불 fact | after-commit, consumer idempotent |
| Ordering | Notification | Ordering fact | after-commit event | eventual |
| Ordering | Loyalty | Ordering fact | `OrderCompleted` idempotent event | eventual |
| Ordering | Settlement | Ordering fact | `OrderCompleted` idempotent event | eventual |
| Payment | Settlement | Payment fact | 환불·승인 금액 입력 | eventual, Item 생성 기준은 완료 주문 |
| Payment | Loyalty | Payment fact | 환불 후 사용·적립 포인트 복원·회수 | eventual |
| Settlement | Dispute | Settlement | 조회 API와 adjustment command | 판정 후 조정은 명시적 명령 |
| Transaction Contexts | Operations | 원본 Context | failure/audit fact와 reconciliation case | eventual, 원본 상태 보존 |
| Transaction contexts | Analytics | 원본 Context | idempotent event | eventual, 재집계 가능 |

## Data ownership

| Context | Owned write data | Published or public surface |
|---|---|---|
| Identity | actor, role, store membership | actor identity, membership check |
| Merchant | Store, Menu, MenuConfiguration, business hours, fee contract | menu/price/status와 sellable requirement lookup |
| Discovery | 위치 검색용 Read Model만 소유; 사용자 정밀 좌표는 저장하지 않음 | nearby store query |
| Ordering | Order, OrderLine, 주문 명령 IdempotencyRecord | order facts, customer/store order API |
| Fulfillment | PickupSlot, PickupReservation | reserve/confirm/release API |
| Inventory | SellableStock, StockReservation | reserve/confirm/release API |
| Promotion | Campaign, CouponIssuance | validate/reserve/use/restore API |
| Loyalty | PointAccount, PointLot, PointReservation/Allocation, PointTransaction, PointRecoveryPending | reserve/use/release, accrual facts |
| Payment | Payment, Refund, PaymentMethod, 결제 명령 IdempotencyRecord | approval/refund command and facts |
| Settlement | SettlementItem, SettlementBatch, SettlementAdjustment | settlement query, adjustment command |
| Dispute | SettlementDispute와 Held Amount | dispute workflow and decision fact |
| Notification | NotificationDelivery | delivery status/failure fact |
| Analytics | Analytics Read Model | named metric queries |
| Operations | ReprocessingCase, AuditRecord | reconciliation/reprocessing commands |

## Translation boundaries

- Payment는 외부 PG SDK 타입을 도메인에 노출하지 않는다.
- Merchant는 정규화한 `menuId + optionIds` 구성을 Inventory가 소유한
  `sellableUnitId + quantityPerLineUnit` 요구량으로 번역한다. Inventory는 메뉴와
  옵션의 의미를 해석하지 않고 sellable unit 수량만 소유한다.
- Notification은 Provider 상태를 BeanFlow delivery 상태로 번역한다.
- Discovery는 Merchant 쓰기 Entity를 검색 편의로 직접 확장하지 않는다.
- Analytics는 원본 거래 상태의 의미를 자체 지표 정의로 변환한다.
- Operations는 원본 Aggregate를 직접 수정하지 않고 owner Context의 승인된 명령을 호출한다.
