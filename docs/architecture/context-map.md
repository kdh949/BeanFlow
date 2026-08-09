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
Ordering ── Eventing contract ────────> owner module listeners

Payment ── approval/refund facts ─────> Ordering
Ordering ── OrderReady ───────────────> Notification
Ordering ── cancellation Delivery command in Tx C0/C1 ──> Notification
Ordering ── cancellation Refund/recovery snapshot command in Tx C1 ──> Payment
Ordering ── compensation Case/policy snapshot command in Tx C1 ──> Operations
Ordering ── OrderCompletedV2 ─────────> Loyalty / Settlement / Analytics
Payment ── PaymentRefundedV1 ─────────> Loyalty / Settlement / Analytics
Payment ── customer cancellation refund result events ──> Notification

Settlement ── confirmed Item views ───> Dispute
Dispute ── accepted adjustment command ──> Settlement

All transaction contexts ── failures/audit facts ──> Operations
All source contexts ── idempotent business facts ──> Analytics
```

## Relationship rules

| Upstream | Downstream | Data owner | Interaction | Consistency |
|---|---|---|---|---|
| Identity | API/Application Services | Identity | 인증 actor와 membership 조회 | 요청 시 동기 |
| Eventing | Ordering과 event consumers | 원본 Context | 중립적인 versioned event 계약 | producer/consumer compile-time 분리 |
| Merchant | Ordering | Merchant | 메뉴 구성·가격·sellable requirement와 applicable immutable settlement-terms 공개 동기 조회 | 주문 생성 시 현재 메뉴 값과 정확히 하나의 terms version 필요 |
| Merchant | Discovery | Merchant `StoreDiscoveryProfile`, Menu/MenuOption | public Query API의 동기 DTO projection | 검색·메뉴 응답은 current owner state를 사용 |
| Fulfillment | Discovery | Fulfillment PickupSlot | public Query API의 동기 잔여 capacity DTO projection | 슬롯 응답은 조회 시점 owner state이며 예약 보장이 아님 |
| Ordering | Fulfillment | Fulfillment | 예약 Application API | 주문 생성 트랜잭션 내 강한 일관성 |
| Ordering | Inventory | Inventory | 예약 Application API | 주문 생성 트랜잭션 내 강한 일관성 |
| Ordering | Promotion | Promotion | 검증·예약 API와 CouponReservation final burden-leg DTO | 주문 금액과 정산 입력 확정 전 필요 |
| Ordering | Loyalty | Loyalty | 포인트 예약 API와 allocation별 immutable issuer DTO | 주문 금액과 정산 입력 확정 전 필요 |
| Ordering | Payment | Ordering / Payment | Payment command와 Tx2 결과 적용 | 외부 호출과 DB tx 분리, 승인 내부 반영은 로컬 원자성 |
| Payment | Ordering | Payment | 승인·거절·불명·환불 fact | Tx2 후 후속 소비자용 after-commit, consumer idempotent |
| Ordering | Notification | Ordering fact or cancellation Delivery command | 일반 알림은 after-commit event; 취소 접수는 Tx C0/C1의 동기 Application API | provider 발송은 eventual |
| Ordering | Payment | Payment | 고객 취소 Refund `REQUESTED`와 cancellation recovery snapshot 생성 Application API | Tx C1 내 강한 일관성, Provider 호출은 밖 |
| Ordering | Operations | Operations | Tx C0의 target AuditRecord와 Tx C1의 OrderCompensationCase·step·benefit policy snapshot·target AuditRecord 생성 Application API | 취소 transaction 내 강한 일관성 |
| Ordering | Loyalty | Ordering fact | `OrderCompletedV2` immutable snapshot event | eventual |
| Ordering | Settlement | Ordering fact | `OrderCompletedV2` immutable snapshot event; 취소 제외 시 typed cancellation-evidence query | event는 eventual, 제외 판정 read는 consumer local transaction 안의 동기 조회 |
| Payment | Settlement | Payment fact | `PaymentRefundedV1` immutable refund/settlement effect와 typed Refund evidence query | event는 eventual, Item 생성 기준은 완료 주문 |
| Payment | Loyalty | Payment fact | `PaymentRefundedV1` 후 사용·적립 포인트 복원·회수 | eventual |
| Payment | Notification | Payment fact | `CustomerCancellationRefundSucceededV1`/`DelayedV1` 전용 결과 event | eventual, Delivery는 별도 transaction |
| Settlement | Dispute | Settlement / Dispute | confirmed Item·Batch view와 Adjustment command | Dispute가 held/workflow를 소유하고 판정 후 조정은 별도 Settlement transaction의 명시적 명령 |
| Transaction Contexts | Operations | 원본 Context | failure/audit fact와 reconciliation case | eventual, 원본 상태 보존 |
| Transaction contexts | Analytics | 원본 Context | idempotent event | eventual, 재집계 가능 |

Operations로 향하는 두 경로를 구분한다. 사후 관측인 failure/reconciliation case는
eventual이지만, 주문 종료 transaction이 만드는 보상 Case와 target AuditRecord는 원
transaction의 commit gate이므로 같은 로컬 transaction에서 확정한다. Ordering은 어느
경우에도 다른 Context의 Repository를 직접 호출하지 않고 공개 Application API만
사용한다.

빠른 재주문은 Ordering이 소유한 terminal source Order를 읽는 명령이며 별도 Reorder
Aggregate나 read model을 만들지 않는다. Ordering은 source의 `menuId`, 검증된 정규화 option ID
snapshot과 `quantity`만 기존 주문 생성 application boundary의 입력으로 변환한다. 이후
Merchant/Fulfillment/Inventory/Promotion/Loyalty 상호작용과 settlement/accrual snapshot 생성은
일반 주문 생성과 같은 현재 owner 계약 및 원자적 transaction을 사용한다. source Order lock은
복제할 snapshot과 소유권·상태를 확정하기 위한 것이며, 새 Order 생성 멱등성의 직렬화 root로
간주하지 않는다.

2026-08-03 Plan 20 outcome에서 위 Merchant/Promotion/Loyalty 동기 경계는 주문 생성 local
transaction 안의 `OrderSettlementInputSnapshot`으로 고정되고, guarded completion은 matching
Payment approval과 snapshot만 사용해 `OrderCompletedV2` outbox를 저장한다. Settlement는 별도
local transaction에서 최소 `OPEN` Batch와 immutable Item을 만든다. 고객 취소 Refund 제외는
Ordering/Payment의 public evidence API로 실제 terminal source를 읽고 Item 부재와 함께 검증한 뒤
 Audit을 저장한다. 두 consumer 모두 owner Repository나 current policy를 직접 조회하지 않는다.

2026-08-03 settlement lifecycle outcome에서 Settlement는 500건 keyset 계산, immutable Batch
확정, refund/dispute source Adjustment와 confirmed Item·Batch public view를 소유한다. Dispute는
OWNER filing/idempotency/held/재이의와 판정 상태를 소유하고 Settlement Repository를 직접 읽지
않는다. `ACCEPTED`는 Settlement public command가 `REQUIRES_NEW`로 Adjustment를 먼저 commit한
뒤 Dispute local transaction이 terminal 상태·Audit·event를 저장한다. 후속 저장 실패는
`UNDER_REVIEW`와 source-unique Operations ReprocessingCase로 관측되며 replay가 기존 Adjustment를
검증해 수렴한다.

## Data ownership

| Context | Owned write data | Published or public surface |
|---|---|---|
| Identity | actor, role, store membership | actor identity, membership check |
| Eventing | write data 없음 | versioned integration event 계약 |
| Merchant | Store, 1:1 `StoreDiscoveryProfile`, Menu, MenuConfiguration, business hours, `StoreSettlementTerms` fee-contract version | menu/price/status, 검증된 공개 매장명·위치 query, sellable requirement와 applicable settlement-terms lookup |
| Discovery | durable write data 없음; 사용자 정밀 좌표와 Merchant/Fulfillment 복제본을 저장하지 않음 | nearby store query, 매장 메뉴·픽업 슬롯 read와 request-only projection |
| Ordering | Order, 검증된 정규화 option ID를 포함한 OrderLine snapshot, `OrderSettlementInputSnapshot`, 주문 생성·재주문·매장 전이·고객 취소 명령 IdempotencyRecord, AcceptanceTimeoutWork | order facts, immutable settlement-completion input, customer/store order API |
| Fulfillment | PickupSlot, PickupReservation | reserve/confirm/release, release-after-termination API, 잔여 capacity slot query |
| Inventory | SellableStock, StockReservation | reserve/confirm/release, restore-after-termination API |
| Promotion | Campaign, CouponIssuance, CouponReservation, CompensationCouponTermsSnapshot | validate/reserve/use/restore API, immutable coupon burden leg lookup |
| Loyalty | PointAccount, PointLot, PointReservation/Allocation, PointTransaction, PointRecoveryPending, PointAdjustmentCommandIdempotency | reserve/use/release, issuer allocation lookup, accrual·refund recovery facts, audited point-adjustment command, pending summary/query |
| Payment | Payment, Refund와 line allocation, PaymentCancellationRecoverySnapshot, PaymentMethod, 결제 명령 IdempotencyRecord | approval/refund command and facts |
| Settlement | SettlementItem, SettlementBatch, SettlementAdjustment | settlement query, adjustment command |
| Dispute | SettlementDispute와 Held Amount | dispute workflow and decision fact |
| Notification | NotificationDelivery | delivery status/failure fact |
| Analytics | Analytics Read Model | named metric queries |
| Operations | ReprocessingCase, AuditRecord, OrderCompensationCase/Step, OrderCompensationBenefitPolicySnapshot, BenefitRestorationPolicyVersion/Head, OperatorPermissionGrant, RepairProposal | reconciliation/reprocessing commands, compensation case 생성·조회, 만료 혜택 정책 조회·변경, audited offline grant bootstrap, explicit operator permission evaluation, 누락 Refund 복구 제안·결정 |

## Translation boundaries

- Payment는 외부 PG SDK 타입을 도메인에 노출하지 않는다.
- Merchant는 정규화한 `menuId + optionIds` 구성을 Inventory가 소유한
  `sellableUnitId + quantityPerLineUnit` 요구량으로 번역한다. Inventory는 메뉴와
  옵션의 의미를 해석하지 않고 sellable unit 수량만 소유한다.
- Notification은 Provider 상태를 BeanFlow delivery 상태로 번역한다.
- Discovery는 Merchant 쓰기 Entity를 검색 편의로 직접 확장하거나 Repository를 직접 조회하지
  않는다. Merchant는 별도 `StoreDiscoveryProfile`을 소유하고 public Query API로 DTO projection을
  제공하며, MVP Discovery는 이를 영속 복제하거나 event로 동기화하지 않는다.
- 메뉴·픽업 슬롯 read도 같은 규칙을 따른다. Merchant가 메뉴·옵션을, Fulfillment가 슬롯 잔여
  capacity를 각각 public Query API의 DTO projection으로 제공하고 Discovery는 HTTP 계약과 응답
  투영만 소유한다. Store 존재 확인은 catalogue owner인 Merchant가 답하므로 Fulfillment는 Store
  식별자 의미를 해석하지 않는다. 목록 조회 편의를 위한 Aggregate 간 JPA 연관관계는 추가하지
  않는다.
- Analytics는 원본 거래 상태의 의미를 자체 지표 정의로 변환한다.
- Operations는 원본 Aggregate를 직접 수정하지 않고 owner Context의 승인된 명령을 호출한다.
  setup 무결성 scanner도 read-only cross-context projection만 사용하고 owner table을
  쓰지 않는다.
- Operations의 explicit operator permission은 JWT role/claim의 fallback이 아닌
  `OperatorPermissionGrant`를 source of truth로 한다. 다른 Context는 Operations public
  authorization API를 호출하며 grant Repository를 직접 조회하지 않는다.
- owner Context는 종료 event의 trigger를 자기 원장 어휘로 번역한다.
  `OrderCancelledV1 → CUSTOMER_CANCELLATION`, `OrderRejectedV1 → STORE_REJECTION`을
  명시적으로 매핑하고 source reference 문자열에서 추론하지 않는다.
