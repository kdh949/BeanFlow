# Ubiquitous Language

| Term | Definition | Owner |
|---|---|---|
| Order | 고객이 특정 매장과 픽업 슬롯에 대해 확정한 가격·항목 스냅샷과 상태 | Ordering |
| Store Order | 별도 Aggregate가 아니라 매장 관점에서 조회·처리하는 Order의 API 표현 | Ordering |
| OrderLine | 주문 당시 메뉴명, 옵션명, 단가, 수량과 혜택 배분을 보존하는 내부 Entity | Ordering |
| MenuConfiguration | 정규화한 메뉴·옵션 조합을 가격 snapshot 원천과 sellable unit별 필요 수량에 연결하는 Merchant 소유 구성 | Merchant |
| Sellable Unit | Inventory가 수량을 소유하는 최소 재고 식별자. 메뉴·옵션 의미는 Merchant의 MenuConfiguration이 번역한다. | Inventory |
| Order Cancellation | 허용된 Order 상태에서 이후 이행을 중단하는 명령과 그 결과. 결제 승인 후에는 Payment 환불 또는 승인취소가 별도 상태로 추적된다. | Ordering |
| Store Rejection | `PAID`이고 아직 `ACCEPTED`되지 않은 Order를 매장이 거절하는 전이 | Ordering |
| Payment Confirmation | 고객이 결제수단 승인을 요청하고 BeanFlow가 그 결과를 Payment에 확정하거나 `UNKNOWN`으로 보존하는 API 명령 | Payment |
| Reservation | 결제 전 제한 시간 동안 자원을 임시 점유한 상태 | 각 자원 Context |
| PickupSlot | 특정 매장의 시간 구간과 수용량 | Fulfillment |
| StockReservation | 주문을 위해 판매 단위 재고를 임시 또는 확정 점유한 기록 | Inventory |
| Campaign | 할인·쿠폰 발급 조건과 비용 부담을 정의한 정책 | Promotion |
| CouponIssuance | 특정 사용자에게 발급된 쿠폰의 예약·사용·복원 생명주기 | Promotion |
| PointAccount | 사용자와 LoyaltyProgram 조합의 가용 잔액 요약 | Loyalty |
| PointLot | 발급 주체, 잔여 금액과 만료일을 가진 포인트 발급분 | Loyalty |
| PointReservation | 주문 lease 동안 PointLot별 포인트 allocation을 점유하고 사용 또는 해제 결과를 추적하는 기록 | Loyalty |
| PointReservationAllocation | PointReservation이 특정 PointLot에서 점유한 금액과 release disposition을 고정한 하위 Entity | Loyalty |
| PointTransaction | 적립·사용·소멸·복원·조정을 기록한 원장 | Loyalty |
| Point Recovery Pending | 환불 대상 적립 포인트를 전부 회수하지 못했을 때 Loyalty가 보유하는 상계 대기 금액과 상태 | Loyalty |
| Payment | 한 주문의 승인, 결과 불명과 환불 상태 | Payment |
| Benefit-only Payment | 최종 결제액이 0원일 때 외부 PG 호출 없이 생성하는 `BENEFIT_ONLY` 유형의 Payment | Payment |
| UNKNOWN | 외부 처리 결과를 성공 또는 실패로 확정할 수 없는 상태 | Payment 등 |
| Reconciliation | 외부 원본과 내부 상태를 비교하여 불명·누락·차이를 복구하는 과정 | Operations / Owner Context |
| SettlementItem | 주문 단위의 매출, 혜택 부담, 수수료와 환불 명세 | Settlement |
| SettlementBatch | 매장·기간 단위 SettlementItem 집계와 확정 상태 | Settlement |
| SettlementAdjustment | 확정 정산을 수정하지 않고 이후 차이를 보정하는 불변 원장 | Settlement |
| SettlementDispute | 점주의 정산 이의제기와 판정 Workflow | Dispute |
| Held Amount | 이의제기 대상 예상 조정액. 확정 Batch를 변경하지 않고 SettlementDispute가 참조하며 판정 결과에 따라 Adjustment로 확정되거나 해제된다. | Dispute |
| NotificationDelivery | 이벤트·수신자·채널별 실제 발송 상태 | Notification |
| IdempotencyRecord | `actorId + operation + key` 범위의 payload hash, 처리 상태와 재사용 응답을 보존하는 기록 | 호출 대상 Context |
| PaymentMethod | 원본 카드정보가 아닌 PG token reference와 표시용 비민감 메타데이터 | Payment |
| Idempotency | 같은 의도의 중복 요청 또는 이벤트가 부작용을 한 번만 만들게 하는 성질 | Cross-cutting |
| Outbox | 원본 DB 트랜잭션과 함께 발행할 이벤트를 영속화하는 패턴 | Cross-cutting |
| AuditRecord | 금액·권한·상태의 수동 또는 중요 변경을 주체·사유와 함께 기록하는 append-only 기록 | Operations |
| ReprocessingCase | 자동 재시도 범위를 벗어난 실패 또는 승인된 backfill을 추적하는 운영 case | Operations |
| Analytics Read Model | 원본 거래 사실을 지표 정의에 따라 멱등 집계한 조회 전용 모델 | Analytics |
| Terminal State | 해당 Aggregate에서 더 이상 정상 상태 전이가 없는 상태. 다른 Aggregate의 후속 처리 완료를 의미하지 않는다. | Context별 |
