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
- Payment가 `UNKNOWN`이어도 5분 lease를 자동 연장하지 않는다.

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
- 5분 lease가 먼저 만료되면 Order와 모든 예약을 만료·해제한다.
- worker가 아직 처리하지 않았더라도 만료 Order 조회·결제 명령은 응답 전에 같은
  expiry transaction을 실행한다. 성공한 조회는 `EXPIRED`, 실패한 조회는 503이며
  stale `PENDING_PAYMENT`를 성공으로 반환하지 않는다.
- 만료 후 reconciliation에서 승인이 확인돼도 Order를 되살리지 않고 Provider
  void/refund recovery를 시작한다.
- void/refund 결과 불명 또는 실패는 `RECONCILING`/`MANUAL_REVIEW`와 운영 case로
  남기며 성공 환불로 표시하지 않는다.

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

## 5. Customer cancellation

1. 고객이 닫힌 reason code와 선택 상세 사유로 취소를 요청한다.
2. Application Service가 Order row lock 아래에서 소유권, 상태, 두 deadline과 멱등
   레코드를 확인한다.
3. `PENDING_PAYMENT` 취소는 Tx C0에서 Order `CANCELLED`, 네 예약 해제, target별
   AuditRecord, 취소 접수 NotificationDelivery와 최초 `200` 응답을 함께 커밋한다.
4. 미수락 `PAID` 취소는 Tx C1에서 Order `CANCELLED`, 보상 Case와 여섯 step, 두
   benefit policy snapshot, Payment cancellation recovery snapshot, 남은 현금이
   양수면 Refund `REQUESTED`, 접수 Delivery, target Audit, `OrderCancelledV1`과 네
   owner publication, 최초 `202` 응답을 함께 커밋한다.
5. 커밋 후 픽업 슬롯, 재고, 쿠폰, 포인트 owner listener가 각자 트랜잭션에서
   복원한다.
6. Refund worker와 delivery worker가 트랜잭션 밖에서 외부 Provider를 호출한다.
7. 현금 환불이 실제로 성공하거나 자동 처리가 끝나 지연이 확정되면 각각 한 번씩
   고객 후속 알림을 보낸다.

Invariants:

- 고객 직접 취소는 `PENDING_PAYMENT`와 `ACCEPTED` 이전 `PAID`에서만 허용한다.
  `ACCEPTED` 이후 취소는 API, 상태 전이와 운영자 우회 경로 어디에서도 허용하지
  않는다.
- 취소 가능 창은 `reservationExpiresAt`과 `acceptanceDeadlineAt`만으로 판정하고
  취소 전용 시각 컬럼이나 픽업 예정시각 cutoff를 두지 않는다.
- 고객 취소, 매장 수락과 3분 자동 거절은 같은 Order row에서 경쟁하며 하나만
  성공한다. 경계에서는 시간 기반 전이가 이긴다.
- Tx C0/C1의 필수 항목 중 하나라도 저장에 실패하면 전체를 롤백하고 `200`이나
  `202`를 반환하지 않는다.
- 자유 입력 상세 사유는 Order row 밖으로 나가지 않는다. 이벤트, 감사 기록, Provider
  요청, 로그와 모든 API 응답에 포함하지 않는다.

Failure behavior:

- `now >= reservationExpiresAt`인 결제 전 취소는 기존 만료를 먼저 커밋한 뒤
  `409 RESERVATION_EXPIRED`를 반환한다.
- `now >= acceptanceDeadlineAt`인 `PAID` 취소는 거절을 직접 확정하지 않고
  deduplicated `AcceptanceTimeoutWork`와 감사 기록을 커밋한 뒤
  `409 ORDER_STATE_CONFLICT`를 반환한다. 저장 실패는 `503`이다.
- 선행 Refund가 `REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`,
  `RECONCILING`, `MANUAL_REVIEW`이면 Order를 전이하기 전에
  `409 PAYMENT_REFUND_UNRESOLVED`를 반환한다. 성공한 선행 부분 환불은 취소를 막지
  않고 남은 현금과 미복원 혜택만 처리한다.
- Order `CANCELLED`는 환불·자원 복원·알림 성공을 뜻하지 않는다. 커밋 후 owner 실패는
  취소를 되돌리지 않고 해당 보상 step과 publication의 재시도·수동 검토로 남는다.
- 고객에게는 내부 재시도·불명·수동 검토 상태를 노출하지 않는다. 자동 처리 중에는
  `PROCESSING`, 자동 처리가 소진되면 `PROCESSING + REFUND_DELAYED`로 투영한다.
- `BENEFIT_ONLY` 취소는 Refund와 외부 호출 없이 PAYMENT 보상을 `NOT_REQUIRED`로
  확정하고 나머지 보상은 일반 `PAID` 취소와 동일하게 진행한다.
- 매장 수락 전 취소 환불에는 SettlementItem과 SettlementAdjustment를 만들지 않고
  source당 하나의 정산 제외 감사 기록으로 증적을 남긴다.

## 6. Ready notification and pickup

1. `OrderReady` 사실로 `NotificationDelivery`를 생성한다.
2. Provider 발송을 주문 상태 변경 트랜잭션과 분리한다.
3. 실패 시 1분, 5분, 30분 간격으로 재시도한다.
4. 총 네 번 실패하면 `MANUAL_REVIEW`로 전환한다.
5. 알림 실패와 무관하게 주문은 `READY`를 유지한다.
6. 상품 전달 후 매장 직원이 `COMPLETED`로 전환한다.

## 7. Point accrual

1. Loyalty가 중복되지 않은 `OrderCompleted` 사실을 처리한다.
2. 실결제액을 기준으로 PointLot과 적립 원장을 생성한다.
3. 발급 당시 만료일과 발급 주체를 고정한다.
4. 동일 주문 완료 사실로 두 번 적립할 수 없다.

## 8. Settlement

1. 완료 주문을 기준으로 주문별 `SettlementItem`을 만든다.
2. 실결제액, 수수료율 스냅샷, 쿠폰·포인트 부담과 환불을 기록한다.
3. `Asia/Seoul` 기준 전일 완료 주문을 매장별 일별 배치로 집계한다.
4. 확정된 Batch와 Item은 직접 수정하지 않는다.
5. 배치 재실행은 같은 원천 거래를 중복 생성하지 않는다.

## 9. Partial refund

1. 매장 또는 운영자가 품목 단위 부분 환불을 요청한다.
2. Payment가 누적 환불액이 승인액을 넘지 않는지 확인한다.
3. 주문 당시 항목별 배분 스냅샷을 사용한다.
4. 해당 항목의 현금 결제액을 환불하고 사용 포인트를 복원한다.
5. 쿠폰 할인액은 현금으로 환급하지 않는다.
6. 적립 포인트를 회수하고 부족액은 Loyalty의 `POINT_RECOVERY_PENDING` 원장에 남긴다.
7. 정산 확정 전이면 원천 항목에 반영하고, 확정 후면 Adjustment를 생성한다.

## 10. Settlement dispute

1. 점주는 자신이 소유한 매장의 정산 Batch를 선택하고 Batch별 cursor 목록에서
   SettlementItem과 `itemId`를 확인한다.
2. 확정 다음 날부터 14일 안에 해당 `itemId`로 SettlementItem 이의제기를 생성한다.
3. 같은 Item에는 진행 중인 이의제기를 하나만 허용한다.
4. 대상 예상 조정액만 `HELD`로 관리하고 전체 Batch는 보류하지 않는다.
5. 운영자가 증빙과 계산 근거를 검토한다.
6. 승인 시 기존 Item을 수정하지 않고 SettlementAdjustment를 생성한다.
7. 판정 주체, 사유, 시각과 금액을 감사 로그에 기록한다.

## Cross-cutting guarantees

- correlation ID와 causation ID로 흐름을 연결한다.
- 외부 결과가 불명확하면 `UNKNOWN` 또는 `RECONCILING` 상태를 사용한다.
- 비동기 작업은 로그만 남기지 않고 영속 상태와 재처리 경로를 가진다.
- 모든 메시지 소비자는 중복 전달 가능성을 가정한다.
- Order의 terminal 상태는 Payment refund, NotificationDelivery 또는 Analytics
  projection까지 성공했다는 뜻이 아니다.
- `PaymentApproved`만으로 SettlementItem을 만들지 않고 `OrderCompleted`를 정산
  원천으로 사용한다.
