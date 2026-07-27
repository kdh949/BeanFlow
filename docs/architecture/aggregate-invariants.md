# Aggregate Responsibilities and Invariants

| Aggregate Root | Responsibility | Core invariants | Other aggregate references |
|---|---|---|---|
| Store | 영업·픽업 가능 상태 | 폐점·휴점 매장은 새 주문 불가 | IDs |
| Menu | 메뉴·옵션·가격·판매 상태 | 음수 가격 금지, 유효 옵션만 선택 | `storeId` |
| MenuConfiguration | 주문 가능한 메뉴·옵션 구성과 재고 요구량 | 정규화한 option ID 집합은 메뉴 안에서 유일하고 sellable unit별 필요 수량은 양수 | `menuId`, `sellableUnitId` |
| Order | 항목 스냅샷, 금액과 상태 | 결제 시작 후 항목·금액 불변, 허용 전이만 가능 | IDs |
| PickupSlot | 시간 구간과 수용량 | 예약+확정 수량 ≤ capacity | `storeId` |
| PickupReservation | 주문의 슬롯 점유 | 주문당 활성 예약 하나, 만료 후 확정 불가 | `orderId`, `slotId` |
| SellableStock | 판매 단위 수량 | 가용·예약·확정 수량 음수 금지 | `storeId`, `menuOptionId` |
| StockReservation | 주문별 재고 점유 | 주문·SKU별 중복 활성 예약 금지 | IDs |
| Campaign | 대상 품목 기반 정액·정률 할인 정책·수량·부담 | type별 금액 필드, rate `1..10000`, minimum/maximum, 대상 목록과 분담률 유효 | `storeId`, menu IDs |
| CouponIssuance | 발급 쿠폰 생명주기 | 동시에 두 주문에 사용 불가 | `memberId`, `campaignId`, `orderId` |
| PointAccount | 프로그램별 잔액 요약 | 가용 잔액 음수 금지 | IDs |
| PointLot | 발급분 available/reserved 잔액·만료 | 생성 시 만료 Lot 예약 금지, 가용·예약·잔여액 음수 금지 | IDs |
| PointReservation | 주문별 PointLot allocation과 lease | 주문당 active 예약 하나, allocation 합계=예약 총액, 예약 시 유효한 allocation은 주문 lease까지 확정 가능 | `orderId`, `pointAccountId`, `pointLotId` |
| Payment | 승인·불명·환불 | 동일 키 중복 승인 금지, 누적 환불 ≤ 승인액 | `orderId` |
| PaymentMethod | PG token reference와 표시 정보 | 원본 카드번호·CVC·전체 유효기간 금지, 사용자와 provider token 범위 중복 금지 | `memberId` |
| IdempotencyRecord | 명령 중복 실행 방지와 응답 재사용 | 같은 scope·key에 payload hash 하나, 처리 중/UNKNOWN은 정리 금지 | `actorId`, target ID |
| SettlementItem | 주문 단위 정산 명세 | 원천 거래·유형 중복 금지 | IDs |
| SettlementBatch | 기간·매장 집계·확정 | 확정 후 직접 수정 금지 | `storeId` |
| SettlementAdjustment | 확정 후 보정 | 대상·원인·금액·행위 주체 필수, 원천 사유별 중복 금지 | IDs |
| SettlementDispute | 이의제기 Workflow와 held 예상액 | Item당 진행 중 하나, 재이의는 이전 ID와 새 증빙 필수 | IDs |
| NotificationDelivery | 발송·재시도 | event+recipient+channel 중복 성공 금지 | IDs |
| ReprocessingCase | 운영 재처리 | 대상·사유·주체 필수, 중복 실행 방지 | IDs |
| AuditRecord | 중요 변경의 target별 감사 | 5년 보존 중 append-only, action/target/source 중복 금지, 필수 주체·사유·correlation, 민감정보 금지 | target IDs |

`Analytics Read Model`과 Discovery Query Model은 쓰기 Aggregate가 아니다. 원본 Context의
event ID와 payload version으로 멱등하게 갱신하는 projection이다.

## Aggregate size rules

- `OrderLine`은 Order 내부 Entity이며 별도 Repository를 만들지 않는다.
- `SettlementBatch`가 모든 Item을 JPA 컬렉션으로 소유하지 않는다.
- `PointAccount`가 모든 PointLot을 컬렉션으로 로딩하지 않는다.
- `PointReservation`은 필요한 allocation만 소유하며 PointLot은 ID로 참조한다.
- 대량 원장은 필요한 행만 쿼리·잠금하며 Account 또는 Batch 요약과 같은 트랜잭션에서 검증한다.
- Aggregate를 JPA 객체 그래프와 동일시하지 않는다.

## Database reinforcement candidates

| Owner / Repository candidate | Aggregate Root | DB reinforcement candidate | Concurrency control |
|---|---|---|---|
| `StoreRepository` | Store | store identity, valid status check | optimistic version |
| `MenuRepository` | Menu | non-negative integer KRW price, unique store/menu code | optimistic version |
| `MenuConfigurationRepository` | MenuConfiguration | unique menu/normalized-option-set, positive sellable requirement | optimistic version |
| `OrderRepository` | Order | order number unique, non-negative totals | optimistic version + guarded transition |
| `PickupSlotRepository` | PickupSlot | unique store/time range, non-negative capacity | conditional update or row lock |
| `PickupReservationRepository` | PickupReservation | active order reservation unique | unique/partial index + row lock |
| `SellableStockRepository` | SellableStock | unique store/sellable unit, non-negative quantities | conditional update or row lock |
| `StockReservationRepository` | StockReservation | active order/SKU unique | unique/partial index |
| `CampaignRepository` | Campaign | valid period/type/value/minimum/maximum/target/share ratio | optimistic version |
| `CouponIssuanceRepository` | CouponIssuance | one active reservation/use per issuance | unique/partial index + guarded transition |
| `PointAccountRepository` | PointAccount | unique member/program, non-negative available balance | row lock/version |
| `PointLotRepository` | PointLot | non-negative available/reserved/remaining, amount tie-out | ordered row lock |
| `PointReservationRepository` | PointReservation | active order reservation unique, allocation source unique | unique + guarded transition |
| `PointTransactionRepository` | PointTransaction | source order/refund/reference unique | unique source reference |
| `PaymentRepository` | Payment | provider transaction key and order/payment intent unique | unique + guarded transition |
| `PaymentMethodRepository` | PaymentMethod | member/provider/token reference unique | unique |
| `IdempotencyRecordRepository` | IdempotencyRecord | actor/operation/key unique | insert-first unique arbitration |
| `SettlementItemRepository` | SettlementItem | source order/type unique | unique source reference |
| `SettlementBatchRepository` | SettlementBatch | store/settlement date unique | unique + guarded transition |
| `SettlementAdjustmentRepository` | SettlementAdjustment | source reason/reference unique | unique source reference |
| `SettlementDisputeRepository` | SettlementDispute | one active dispute per Item, refile count ≤ 1 | partial unique + guarded transition |
| `NotificationDeliveryRepository` | NotificationDelivery | event/recipient/channel unique | unique + guarded attempt count |
| `ReprocessingCaseRepository` | ReprocessingCase | open case type/target unique where required | partial unique |
| `AuditRecordRepository` | AuditRecord | action/target/source unique, occurred-at/correlation/retention-expiry index | append-only permissions + retention worker role |

누적 환불액, PointLot 합계와 Settlement 합계처럼 여러 행의 합에 의존하는 불변식은
애플리케이션 사전 조회만으로 보호하지 않는다. owner summary row lock/version,
source-reference Unique Constraint와 같은 트랜잭션 최종 방어를 함께 사용한다.

주문 요청의 `optionIds`는 중복을 거부한 뒤 ID 오름차순으로 정규화하여
MenuConfiguration을 조회한다. 요청의 OrderLine 순서는 금액 배분 계약이므로
정규화하지 않는다. 여러 line이 같은 sellable unit을 요구하면 Ordering은
`quantityPerLineUnit * lineQuantity`를 overflow 없이 합산한 뒤 Inventory에 한 번
예약 요청한다.
