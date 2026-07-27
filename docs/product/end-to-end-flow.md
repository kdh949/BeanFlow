# End-to-End Transaction Flow

## 1. Store and menu discovery

1. 고객이 현재 위치와 검색 반경을 전달한다.
2. Discovery가 영업 중이고 픽업 가능한 매장을 거리순으로 조회한다.
3. 고객이 메뉴, 옵션, 가격, 판매 가능 상태와 픽업 슬롯을 조회한다.
4. 정밀 좌표는 요청 처리에만 사용하고 영구 저장하지 않는다.

Failure behavior:

- DB 또는 공간 조회 실패를 빈 매장 목록으로 바꾸지 않는다.
- 잘못된 좌표와 허용 반경은 명시적 validation error로 반환한다.

## 2. Order creation and reservation

1. Merchant에서 현재 메뉴·옵션·가격을 확인한다.
2. Ordering이 메뉴명, 옵션명과 단가를 `OrderLine`에 스냅샷으로 저장한다.
3. Fulfillment가 5분 lease의 픽업 슬롯 예약을 획득한다.
4. Inventory가 판매 단위 재고를 예약한다.
5. Promotion이 쿠폰을 검증·예약한다.
6. Loyalty가 포인트 사용분을 예약한다.
7. 쿠폰을 먼저 적용하고 남은 금액에 포인트를 적용한다.
8. Order를 `PENDING_PAYMENT`로 확정한다.

Invariants:

- 일부 예약만 성공한 주문을 생성하지 않는다.
- 주문 항목과 결제 예정 금액은 결제 시작 후 변경하지 않는다.
- 마지막 슬롯·재고·쿠폰 수량을 초과할 수 없다.

## 3. Payment approval

1. `Payment(READY)`와 Idempotency Record를 로컬 DB 트랜잭션에서 저장한다.
2. 커밋 후 DB 트랜잭션 밖에서 PG Adapter를 호출한다.
3. 승인 성공을 새 트랜잭션에서 기록한다.
4. `PaymentApproved` 사실을 발행한다.
5. 주문, 슬롯, 재고, 쿠폰과 포인트 예약을 확정한다.

Failure behavior:

- 동일 키·동일 payload는 기존 결과를 반환한다.
- 동일 키·다른 payload는 `409 Conflict`다.
- PG timeout 또는 응답 유실은 `UNKNOWN`이며 성공·실패로 단정하지 않는다.
- PG 성공 후 DB 기록 실패는 reconciliation 대상이다.
- 필수 PG 설정 누락 시 fake provider로 자동 전환하지 않는다.

## 4. Store acceptance and preparation

1. 결제 완료 주문은 `PAID`가 된다.
2. 매장은 3분 안에 주문을 수락하거나 거절한다.
3. 2분 시점에는 매장 운영 알림을 생성한다.
4. 3분 timeout이면 자동 거절과 전액 취소 보상을 시작한다.
5. 수락 후 `ACCEPTED → PREPARING → READY`로 전이한다.

Failure behavior:

- `ACCEPTED` 이후 단순 거절 명령은 허용하지 않는다.
- timeout job과 매장 수락이 동시에 실행돼도 terminal transition은 한 번만 성공해야 한다.
- 자동 환불 실패는 숨기지 않고 reconciliation 상태를 남긴다.

## 5. Ready notification and pickup

1. `OrderReady` 사실로 `NotificationDelivery`를 생성한다.
2. Provider 발송을 주문 상태 변경 트랜잭션과 분리한다.
3. 실패 시 1분, 5분, 30분 간격으로 재시도한다.
4. 총 네 번 실패하면 `MANUAL_REVIEW`로 전환한다.
5. 알림 실패와 무관하게 주문은 `READY`를 유지한다.
6. 상품 전달 후 매장 직원이 `COMPLETED`로 전환한다.

## 6. Point accrual

1. Loyalty가 중복되지 않은 `OrderCompleted` 사실을 처리한다.
2. 실결제액을 기준으로 PointLot과 적립 원장을 생성한다.
3. 발급 당시 만료일과 발급 주체를 고정한다.
4. 동일 주문 완료 사실로 두 번 적립할 수 없다.

## 7. Settlement

1. 완료 주문을 기준으로 주문별 `SettlementItem`을 만든다.
2. 실결제액, 수수료율 스냅샷, 쿠폰·포인트 부담과 환불을 기록한다.
3. `Asia/Seoul` 기준 전일 완료 주문을 매장별 일별 배치로 집계한다.
4. 확정된 Batch와 Item은 직접 수정하지 않는다.
5. 배치 재실행은 같은 원천 거래를 중복 생성하지 않는다.

## 8. Partial refund

1. 매장 또는 운영자가 품목 단위 부분 환불을 요청한다.
2. Payment가 누적 환불액이 승인액을 넘지 않는지 확인한다.
3. 주문 당시 항목별 배분 스냅샷을 사용한다.
4. 해당 항목의 현금 결제액을 환불하고 사용 포인트를 복원한다.
5. 쿠폰 할인액은 현금으로 환급하지 않는다.
6. 적립 포인트를 회수하고 부족액은 `POINT_RECOVERY_PENDING` 원장에 남긴다.
7. 정산 확정 전이면 원천 항목에 반영하고, 확정 후면 Adjustment를 생성한다.

## 9. Settlement dispute

1. 점주는 확정 다음 날부터 14일 안에 SettlementItem 이의제기를 생성한다.
2. 같은 Item에는 진행 중인 이의제기를 하나만 허용한다.
3. 대상 예상 조정액만 `HELD`로 관리하고 전체 Batch는 보류하지 않는다.
4. 운영자가 증빙과 계산 근거를 검토한다.
5. 승인 시 기존 Item을 수정하지 않고 SettlementAdjustment를 생성한다.
6. 판정 주체, 사유, 시각과 금액을 감사 로그에 기록한다.

## Cross-cutting guarantees

- correlation ID와 causation ID로 흐름을 연결한다.
- 외부 결과가 불명확하면 `UNKNOWN` 또는 `RECONCILING` 상태를 사용한다.
- 비동기 작업은 로그만 남기지 않고 영속 상태와 재처리 경로를 가진다.
- 모든 메시지 소비자는 중복 전달 가능성을 가정한다.
