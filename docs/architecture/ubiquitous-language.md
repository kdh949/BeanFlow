# Ubiquitous Language

| Term | Definition | Owner |
|---|---|---|
| Order | 고객이 특정 매장과 픽업 슬롯에 대해 확정한 가격·항목 스냅샷과 상태 | Ordering |
| Store Order | 별도 Aggregate가 아니라 매장 관점에서 조회·처리하는 Order의 API 표현 | Ordering |
| OrderLine | 주문 당시 메뉴명, 옵션명, 단가, 수량과 혜택 배분을 보존하는 내부 Entity | Ordering |
| Fast Reorder | 소유 고객의 terminal source Order에서 메뉴 ID·정규화 option ID·수량만 가져와 현재 조건을 재검증하고 새 Order를 즉시 생성하는 Ordering 명령. 별도 Aggregate나 draft가 아니다. | Ordering |
| MenuConfiguration | 정규화한 메뉴·옵션 조합을 가격 snapshot 원천과 sellable unit별 필요 수량에 연결하는 Merchant 소유 구성 | Merchant |
| Sellable Unit | Inventory가 수량을 소유하는 최소 재고 식별자. 메뉴·옵션 의미는 Merchant의 MenuConfiguration이 번역한다. | Inventory |
| StoreDiscoveryProfile | Store와 1:1인 Merchant 소유 검색 profile. 검증된 공개 매장명과 `geography(Point,4326)` 위치를 가지며 Store 쓰기 Entity와 분리된다. | Merchant |
| Precise query coordinate | 한 nearby 요청의 검증과 read query 동안에만 사용하고 어떤 durable record에도 남기지 않는 고객 위·경도 | Discovery |
| Pickup-capable store | `open`과 `pickupAvailable`이 모두 true인 매장. `open = acceptingOrders`, `pickupAvailable = acceptingOrders && pickupEnabled`로 현재 owner state만 투영한다. | Merchant |
| Distance micrometer | `floor(ST_Distance(location, queryPoint) * 1_000_000)`인 정렬·cursor 전용 거리 값. 응답의 `distanceMeters`는 이 값을 1,000,000으로 나눈 표시값이며 keyset 값으로 재사용하지 않는다. | Discovery |
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
| PointTransaction | 적립·사용·소멸·복원·조정과 실제 환불 적립 포인트 차감을 기록하는 append-only 원장. 공개 `amountKrw`는 잔액 signed effect다. | Loyalty |
| RECOVERY | 환불에 대응해 실제 가용 PointLot과 PointAccount에서 차감한 PointTransaction type. 미회수 부족액 자체를 뜻하지 않는다. | Loyalty |
| Point Recovery Pending | 환불 대상 적립 포인트를 전부 회수하지 못했을 때 Loyalty가 보유하는 상계 대기 잔액 Aggregate. `PENDING`은 양수 잔액, `SETTLED`는 0이다. | Loyalty |
| Point Adjustment | explicit `POINT_ADJUSTMENT` permission을 가진 Platform Operator의 reason/evidence에 근거한 signed `ADJUSTMENT` correction. CREDIT은 입력 issuer/expiry의 새 Lot, DEBIT은 available Lot 차감으로 표현한다. | Loyalty |
| Adjustment Command Source | 조정 command 하나의 immutable identity. Audit/outbox는 command source를, Lot별 PointTransaction은 command source와 Lot을 묶은 opaque child source를 사용한다. | Loyalty |
| Point Adjustment Command Idempotency | actor·operation·key scope의 terminal point-adjustment response를 90일 보존하는 Loyalty record. account/hash가 같은 요청만 최초 201을 재생한다. | Loyalty |
| Balance Effect | PointTransaction 저장 magnitude와 별도로 balance에 미치는 `CREDIT`, `DEBIT`, `NONE` 방향을 보존하는 값. | Loyalty |
| Payment | 한 주문의 승인, 결과 불명과 환불 상태 | Payment |
| Benefit-only Payment | 최종 결제액이 0원일 때 외부 PG 호출 없이 생성하는 `BENEFIT_ONLY` 유형의 Payment | Payment |
| UNKNOWN | 외부 처리 결과를 성공 또는 실패로 확정할 수 없는 상태 | Payment 등 |
| Reconciliation | 외부 원본과 내부 상태를 비교하여 불명·누락·차이를 복구하는 과정 | Operations / Owner Context |
| SettlementItem | 주문 단위의 매출, 혜택 부담, 수수료와 환불 명세 | Settlement |
| SettlementBatch | 매장·서울 완료일 단위 SettlementItem 귀속과 이후 집계·확정 상태. Item 생성 시 최소 `OPEN` Batch가 먼저 존재한다. | Settlement |
| SettlementAdjustment | 확정 정산을 수정하지 않고 이후 차이를 보정하는 불변 원장 | Settlement |
| SettlementDispute | 점주의 정산 이의제기와 판정 Workflow | Dispute |
| Held Amount | 이의제기 대상 예상 조정액. 확정 Batch를 변경하지 않고 SettlementDispute가 참조하며 판정 결과에 따라 Adjustment로 확정되거나 해제된다. | Dispute |
| NotificationDelivery | 이벤트·수신자·채널별 실제 발송 상태 | Notification |
| IdempotencyRecord | `actorId + operation + key` 범위의 payload hash, 처리 상태와 재사용 응답을 보존하는 기록 | 호출 대상 Context |
| PaymentMethod | 원본 카드정보가 아닌 PG token lifecycle, 고객 표시 metadata와 default 선호를 소유하는 Aggregate. provider/customer reference는 내부 전용이며 인가 근거가 아니다. | Payment |
| PaymentProviderRequestSnapshot | Payment 시작 시 PaymentMethod에서 고정한 immutable Provider 요청 입력. 이후 method 폐기와 무관하게 기존 Payment만 수렴시킨다. | Payment |
| ProviderNotificationInbox | 인증을 통과한 Provider lifecycle 알림을 notification ID로 멱등 수락하고 단일 PaymentMethod mapping 결과를 보존하는 원장 | Payment |
| Idempotency | 같은 의도의 중복 요청 또는 이벤트가 부작용을 한 번만 만들게 하는 성질 | Cross-cutting |
| Outbox | 원본 DB 트랜잭션과 함께 발행할 이벤트를 영속화하는 패턴 | Cross-cutting |
| AuditRecord | 금액·권한·상태의 수동 또는 중요 변경을 주체·사유와 함께 기록하는 append-only 기록 | Operations |
| Operator Permission Grant | 활성 Platform Operator가 특정 privileged operation을 실행·조회할 수 있게 하는 Operations-owned explicit grant. JWT role 또는 claim의 fallback이 아니다. | Operations |
| Verified Release Principal | controlled deployment job의 단기 OIDC workload identity를 required issuer·audience·allowed subject로 검증한 bootstrap 주체. application JWT, role 또는 static secret으로 대체하지 않는다. | Operations / Delivery |
| ReprocessingCase | 자동 재시도 범위를 벗어난 실패 또는 승인된 backfill을 추적하는 운영 case | Operations |
| SupportCase | 하나의 문의·사건과 상담 진행 상태를 추적하는 Aggregate | Support |
| VerificationSession | 특정 Case·Subject·Purpose에 묶인 등록 채널 통제 확인 결과 | Support |
| DataAccessGrant | 상담원·Case·Subject·필드·사유·만료에 귀속한 원문 열람 권한 | Support |
| PostAcceptanceResolutionCase | 제조·준비·완료 사실을 되돌리지 않는 환불·보상·정산 해결 Case | Support |
| DeliveryFulfillment | Provider와 독립적인 표준 배달 생명주기 | Delivery |
| LegalHold | 사건·범주에 한정되고 재검토·만료되는 삭제 유예 | Operations |
| Analytics Read Model | 원본 거래 사실을 지표 정의에 따라 멱등 집계한 조회 전용 모델 | Analytics |
| Terminal State | 해당 Aggregate에서 더 이상 정상 상태 전이가 없는 상태. 다른 Aggregate의 후속 처리 완료를 의미하지 않는다. | Context별 |
