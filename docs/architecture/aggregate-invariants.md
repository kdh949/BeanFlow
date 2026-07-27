# Aggregate Responsibilities and Invariants

| Aggregate Root | Responsibility | Core invariants | Other aggregate references |
|---|---|---|---|
| Store | 영업·픽업 가능 상태 | 폐점·휴점 매장은 새 주문 불가 | IDs |
| Menu | 메뉴·옵션·가격·판매 상태 | 음수 가격 금지, 유효 옵션만 선택 | `storeId` |
| Order | 항목 스냅샷, 금액과 상태 | 결제 시작 후 항목·금액 불변, 허용 전이만 가능 | IDs |
| PickupSlot | 시간 구간과 수용량 | 예약+확정 수량 ≤ capacity | `storeId` |
| PickupReservation | 주문의 슬롯 점유 | 주문당 활성 예약 하나, 만료 후 확정 불가 | `orderId`, `slotId` |
| SellableStock | 판매 단위 수량 | 가용·예약·확정 수량 음수 금지 | `storeId`, `menuOptionId` |
| StockReservation | 주문별 재고 점유 | 주문·SKU별 중복 활성 예약 금지 | IDs |
| Campaign | 할인 정책·수량·부담 | 기간·수량·분담률 유효 | IDs |
| CouponIssuance | 발급 쿠폰 생명주기 | 동시에 두 주문에 사용 불가 | `memberId`, `campaignId`, `orderId` |
| PointAccount | 프로그램별 잔액 요약 | 가용 잔액 음수 금지 | IDs |
| PointLot | 발급분 잔액·만료 | 만료 Lot 사용 금지, 잔여액 음수 금지 | IDs |
| Payment | 승인·불명·환불 | 동일 키 중복 승인 금지, 누적 환불 ≤ 승인액 | `orderId` |
| SettlementItem | 주문 단위 정산 명세 | 원천 거래·유형 중복 금지 | IDs |
| SettlementBatch | 기간·매장 집계·확정 | 확정 후 직접 수정 금지 | `storeId` |
| SettlementAdjustment | 확정 후 보정 | 대상·원인·금액·승인자 필수 | IDs |
| SettlementDispute | 이의제기 Workflow | Item당 진행 중 하나 | IDs |
| NotificationDelivery | 발송·재시도 | event+recipient+channel 중복 성공 금지 | IDs |
| ReprocessingCase | 운영 재처리 | 대상·사유·주체 필수, 중복 실행 방지 | IDs |

## Aggregate size rules

- `OrderLine`은 Order 내부 Entity이며 별도 Repository를 만들지 않는다.
- `SettlementBatch`가 모든 Item을 JPA 컬렉션으로 소유하지 않는다.
- `PointAccount`가 모든 PointLot을 컬렉션으로 로딩하지 않는다.
- 대량 원장은 필요한 행만 쿼리·잠금하며 Account 또는 Batch 요약과 같은 트랜잭션에서 검증한다.
- Aggregate를 JPA 객체 그래프와 동일시하지 않는다.

## Database reinforcement candidates

- payment idempotency scope unique
- payment provider key unique
- active coupon reservation unique
- source order point accrual unique
- source transaction settlement item unique
- active dispute partial unique
- notification delivery idempotency unique
- non-negative quantity check constraints
- refund sum application invariant plus locking/version control
