# Ubiquitous Language

| Term | Definition | Owner |
|---|---|---|
| Order | 고객이 특정 매장과 픽업 슬롯에 대해 확정한 가격·항목 스냅샷과 상태 | Ordering |
| OrderLine | 주문 당시 메뉴명, 옵션명, 단가, 수량과 혜택 배분을 보존하는 내부 Entity | Ordering |
| Reservation | 결제 전 제한 시간 동안 자원을 임시 점유한 상태 | 각 자원 Context |
| PickupSlot | 특정 매장의 시간 구간과 수용량 | Fulfillment |
| StockReservation | 주문을 위해 판매 단위 재고를 임시 또는 확정 점유한 기록 | Inventory |
| Campaign | 할인·쿠폰 발급 조건과 비용 부담을 정의한 정책 | Promotion |
| CouponIssuance | 특정 사용자에게 발급된 쿠폰의 예약·사용·복원 생명주기 | Promotion |
| PointAccount | 사용자와 LoyaltyProgram 조합의 가용 잔액 요약 | Loyalty |
| PointLot | 발급 주체, 잔여 금액과 만료일을 가진 포인트 발급분 | Loyalty |
| PointTransaction | 적립·사용·소멸·복원·조정을 기록한 원장 | Loyalty |
| Payment | 한 주문의 승인, 결과 불명과 환불 상태 | Payment |
| UNKNOWN | 외부 처리 결과를 성공 또는 실패로 확정할 수 없는 상태 | Payment 등 |
| Reconciliation | 외부 원본과 내부 상태를 비교하여 불명·누락·차이를 복구하는 과정 | Operations / Owner Context |
| SettlementItem | 주문 단위의 매출, 혜택 부담, 수수료와 환불 명세 | Settlement |
| SettlementBatch | 매장·기간 단위 SettlementItem 집계와 확정 상태 | Settlement |
| SettlementAdjustment | 확정 정산을 수정하지 않고 이후 차이를 보정하는 불변 원장 | Settlement |
| SettlementDispute | 점주의 정산 이의제기와 판정 Workflow | Dispute |
| NotificationDelivery | 이벤트·수신자·채널별 실제 발송 상태 | Notification |
| Idempotency | 같은 의도의 중복 요청 또는 이벤트가 부작용을 한 번만 만들게 하는 성질 | Cross-cutting |
| Outbox | 원본 DB 트랜잭션과 함께 발행할 이벤트를 영속화하는 패턴 | Cross-cutting |
| Audit Record | 금액·권한·상태의 수동 또는 중요 변경을 주체·사유와 함께 기록 | Operations |
