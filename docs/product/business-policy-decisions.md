# BeanFlow Business Policy Decisions

> **문서 목적:** BeanFlow MVP의 핵심 비즈니스 정책, 검증 요구와 재검토 조건을 구체적인 값으로 정의한다.
> **적용 범위:** BeanFlow MVP
> **결정 상태:** 별도 표기가 없는 한 아래 정책은 `Accepted for MVP`다.
> **원칙:** 각 정책은 현재 제품 범위와 운영 가정에 따른 결정이다. 실제 운영 데이터, 계약 또는 규제 요구가 달라지면 각 항목의 `Revisit Conditions`에 따라 재검토한다.

## 적용 규칙

1. 코드, API, 테스트와 다른 문서가 이 문서와 충돌하면 임의로 해석하지 않고 충돌을 보고한다.
2. 제품 동작을 바꾸는 정책은 구현 전에 이 문서를 갱신한다.
3. 구조적·장기적 영향이 큰 결정은 별도 ADR과 상호 링크한다.
4. 아직 측정하지 않은 성능 결과나 개선율을 정책 근거로 작성하지 않는다.
5. `Affected Contexts`, `Affected Aggregates`, `Required Tests`를 구현 범위와 검증 계획의 기준으로 사용한다.

---

# A. 공통 시간·금액 정책


## BR-01 시스템 기준 시간대

- **Status:** Accepted for MVP
- **Decision:** 모든 영업시간, 픽업 슬롯, 캠페인 기간, 포인트 만료, 정산 기준일과 배치 스케줄은 `Asia/Seoul`을 기준으로 계산한다. API와 DB의 시각 값은 timezone을 포함한 `Instant` 또는 offset이 명시된 형식으로 저장·전달하고, 사용자 표시 시 `Asia/Seoul`로 변환한다.
- **Rationale:** MVP 대상 매장이 국내에 한정되어 있어 매장별 timezone을 지원할 실익보다 구현·테스트 복잡도가 크다.
- **Affected Contexts:** Merchant, Fulfillment, Promotion, Loyalty, Settlement, Analytics
- **Affected Aggregates:** Store, PickupSlot, Campaign, PointLot, SettlementBatch
- **Required Tests:**
  - 자정 경계의 영업시간과 정산일 테스트
  - 캠페인 시작·종료 경계 테스트
  - 포인트 만료 시각 경계 테스트
  - 고정 `Clock`을 사용한 시간 의존 테스트
- **ADR Required:** No
- **Revisit Conditions:** 해외 매장 지원 또는 매장별 timezone 요구가 확정될 때

## BR-02 금액 단위와 반올림

- **Status:** Accepted for MVP
- **Decision:** 통화는 KRW만 지원하고 금액은 정수 원 단위로 저장한다. 정률 할인과 수수료 계산 중간값은 충분한 소수 정밀도로 계산하되, 최종 항목별 금액을 확정할 때 원 미만을 버림한다. 주문 총액·환불액·정산액은 확정된 항목 금액의 합으로 계산한다.
- **Settlement Input Amendment (2026-08-02):** 주문 정산 입력은 다음 정수 산식을
  canonical하게 사용한다. `feeKrw=floor(payableKrw*feeRateBps/10000)`,
  `storeCouponCostKrw=floor(couponDiscountKrw*storeShareBps/10000)`, platform coupon
  leg는 할인액의 나머지다. `benefitCostKrw=storeCouponCostKrw+storePointCostKrw`,
  `netSettlementKrw=subtotalKrw-feeKrw-benefitCostKrw`이며 모든 항과 최종 net은 음수가
  아니어야 한다. overflow나 tie-out 불일치는 반올림·0원으로 보정하지 않고 실패한다.
- **Rationale:** 부동소수점 오차를 방지하고 주문·환불·정산의 재현성을 확보한다.
- **Affected Contexts:** Ordering, Promotion, Loyalty, Payment, Settlement, Analytics
- **Affected Aggregates:** Order, Campaign, Payment, SettlementItem, SettlementAdjustment
- **Required Tests:**
  - 정률 할인에서 원 미만 금액 테스트
  - 여러 항목의 할인 배분 합계 일치 테스트
  - 부분 환불 후 승인 금액과 환불 합계 tie-out
  - 정산 수수료 합계 재현 테스트
- **ADR Required:** Yes — 금액 표현과 배분 정책 ADR에 포함
- **Revisit Conditions:** 외화 결제 또는 세금·회계상 다른 반올림 규칙이 필요할 때

---

# B. 주문·예약·재고·매장 수락 정책

## BR-03 결제 전 예약 lease

- **Status:** Accepted for MVP
- **Decision:** 주문 생성 후 픽업 슬롯, 판매 재고, 쿠폰, 포인트 예약은 5분간 유지한다. 5분 안에 결제가 승인되지 않으면 Payment가 `UNKNOWN`이더라도 주문을 `EXPIRED`로 전환하고 모든 예약 자원을 해제한다. 이후 reconciliation에서 Provider 승인이 확인돼도 만료 주문을 `PAID`로 되살리거나 예약을 다시 확정하지 않는다. Payment가 자동 void 또는 전액 환불을 시작하고, 외부 결과가 확정될 때까지 `RECONCILING` 또는 `MANUAL_REVIEW`와 운영 case를 남긴다.
- **Expiration:** 예약 만료 시각은 주문 생성 트랜잭션에서 고정하며, 연장 API는 MVP에서 제공하지 않는다.
- **Amendment (2026-07-28):** 결제 결과 불명 상태에서 자원이 무기한 점유되는 것을 막고 뒤늦은 승인 주문이 이미 해제된 자원을 다시 확정하지 않도록 만료 우선과 명시적 환불 복구를 확정했다.
- **Point Reservation Amendment (2026-07-28):** 주문 생성 시점에 유효한 PointLot에서 예약한 allocation은 주문 lease가 끝날 때까지 확정 가능성을 보장한다. lease 도중 원 PointLot 만료 시각이 지나도 예약분은 결제 승인에 사용할 수 있다. 예약을 해제할 때 이미 만료된 allocation은 가용 포인트로 복원하지 않고 만료 원장으로 처리한다.
- **Materialization Amendment (2026-07-28):** `now >= reservationExpiresAt`인 `PENDING_PAYMENT` Order의 조회·결제 명령은 worker를 기다리지 않고 먼저 Order 만료와 네 자원 해제를 같은 transaction으로 시도한다. 성공하면 `EXPIRED`를 반환하거나 만료 오류로 결제를 거부한다. 해제 실패 시 stale `PENDING_PAYMENT`나 부분 성공을 반환하지 않고 503으로 실패하며 worker 또는 다음 요청이 재시도한다.
- **Payment Decline Amendment (2026-07-29):** Provider가 승인을 명시적으로
  거절하면 Payment를 `FAILED`, Order를 `CANCELLED`로 전환하고 네 예약을 같은
  transaction에서 해제한다. 같은 Order에서 다른 결제수단으로 다시 승인하지 않고
  고객은 새 주문을 생성한다.
- **Rationale:** 결제 재시도를 허용하면서도 자원이 무한 점유되는 것을 방지한다.
- **Affected Contexts:** Ordering, Fulfillment, Inventory, Promotion, Loyalty, Payment
- **Affected Aggregates:** Order, PickupReservation, StockReservation, CouponIssuance, PointAccount
- **Required Tests:**
  - 5분 이전 결제 성공 시 예약 확정
  - PointLot 만료가 lease 중간에 있는 주문의 승인과 해제
  - 해제 시 이미 만료된 point allocation의 비복원·만료 원장
  - 5분 경계와 이후 결제 요청 거부
  - Payment `UNKNOWN`과 만료 작업의 동시 실행
  - 만료 후 뒤늦은 승인 확인 시 주문 비복구와 void/refund reconciliation
  - 만료 작업 재실행 시 중복 해제 방지
  - worker 전후 조회가 같은 `EXPIRED` representation을 반환
  - 조회 중 만료 해제 실패 시 503과 전체 rollback
  - 결제와 만료 작업의 동시 실행 테스트
- **ADR Required:** Yes — 예약 lease와 자원 확정 시점
- **Revisit Conditions:** 실제 결제 소요시간 p95, 결제 이탈률 또는 자원 점유율 측정 후 조정

## BR-04 재고 확정 시점

- **Status:** Accepted for MVP
- **Decision:** 주문 생성 시 재고를 임시 예약하고, 결제 승인 성공 시 확정 차감한다. 결제 실패·예약 만료·결제 전 취소 시 예약을 해제한다. 결제 승인 후 매장이 주문을 거절하면 확정 차감된 재고를 복원한다.
- **Payment Decline Clarification (2026-07-29):** 여기서 결제 실패는 Provider가
  부수효과 없음과 거절을 명시적으로 확정한 경우다. timeout과 응답 유실은 실패가
  아니라 `UNKNOWN`이며 lease 만료 전까지 예약을 유지한다.
- **Rationale:** 결제 완료 고객의 재고를 우선 보장하고 oversell을 방지한다.
- **Affected Contexts:** Ordering, Inventory, Payment, Fulfillment
- **Affected Aggregates:** SellableStock, StockReservation, Order, Payment
- **Required Tests:**
  - 마지막 재고에 대한 동시 예약 테스트
  - 결제 성공 시 한 번만 확정 차감
  - 결제 실패·만료·매장 거절 시 복원
  - 중복 이벤트로 인한 이중 차감·이중 복원 방지
- **ADR Required:** Yes
- **Revisit Conditions:** 매장 거절률이 높아 불필요한 재고 복원이 운영 문제로 확인될 때

## BR-05 픽업 슬롯 확정 시점

- **Status:** Accepted for MVP
- **Decision:** 주문 생성 시 슬롯을 임시 예약하고, 결제 승인 성공 시 확정한다. 결제 실패·예약 만료·결제 전 취소 시 해제한다. 결제 승인 후 매장이 주문을 거절하면 슬롯을 해제한다.
- **Payment Decline Clarification (2026-07-29):** 명시 거절은 슬롯 예약을
  `RELEASED`로 전환한다. `UNKNOWN`은 거절로 간주하지 않으며 정확한 lease
  deadline에서 기존 만료 정책을 적용한다.
- **Rationale:** 결제되지 않은 주문이 장시간 슬롯을 점유하지 않게 하면서 결제 중인 고객의 자리를 보호한다.
- **Affected Contexts:** Ordering, Fulfillment, Payment
- **Affected Aggregates:** PickupSlot, PickupReservation, Order, Payment
- **Required Tests:**
  - 마지막 슬롯에 대한 동시 예약
  - 예약 수와 확정 수의 capacity 초과 방지
  - 중복 승인·거절 이벤트의 멱등 처리
  - lease 만료와 결제 승인의 경쟁 조건
- **ADR Required:** Yes
- **Revisit Conditions:** 결제 승인 이후 매장 거절로 발생하는 슬롯 낭비가 유의미할 때

## BR-06 매장 수락 제한시간

- **Status:** Accepted for MVP
- **Decision:** 결제 승인 후 매장은 3분 안에 주문을 수락하거나 거절해야 한다. 2분이 지나면 매장 운영 알림을 생성하고, 3분이 지나도 응답이 없으면 주문을 자동 거절한다. 자동 거절 시 결제 전액 취소, 재고·슬롯 복원, 쿠폰·포인트 복원, 고객 알림을 수행한다.
- **Expired Benefit Restoration Amendment (2026-07-30):** 매장 거절 시 원 쿠폰 또는
  PointLot이 아직 유효하면 원 혜택으로 복원한다. 이미 만료됐으면 기본적으로 같은
  가치와 원 발급 reference를 보존한 새 CouponIssuance 또는 PointLot을 거절 시각부터
  30일 유효하게 발급한다. 플랫폼 운영자는 감사 가능한 정책 API로 새 발급 유효일수와
  `COMPENSATE_WITH_NEW_ISSUANCE` 또는 `PRESERVE_ORIGINAL_EXPIRY` mode를 변경할 수
  있다. 변경은 다음 거절부터 적용하고 거절 event에 policy version과 값을 snapshot한다.
  `PRESERVE_ORIGINAL_EXPIRY`에서는 복원 disposition을 원장에 남기되 이미 만료된
  금액을 사용 가능하게 되살리지 않는다.
- **Trigger×Benefit Policy Amendment (2026-07-31):** 만료 혜택 정책은
  주문 종료 범위에서 `STORE_REJECTION | CUSTOMER_CANCELLATION`과
  `COUPON | POINTS` 조합별 네 head로 분리한다. 기존 매장 거절 head의 설정은 거절
  coupon·points head가 각각 이어받는다. 각 변경은 전역 고유 ID의 append-only
  version을 추가하고 선택한 head만 CAS로 갱신한다. ADR-063이 부분 환불 포인트 전용
  `PARTIAL_REFUND × POINTS` head를 추가하므로 운영 정책 API의 현재 head는 총 다섯
  개다. `PARTIAL_REFUND × COUPON`은 허용하지 않는다.
- **Rationale:** 결제 후 무기한 대기하는 고객 경험을 방지하고 예외 흐름을 명확하게 만든다.
- **Affected Contexts:** Ordering, Fulfillment, Payment, Inventory, Promotion, Loyalty, Notification, Operations
- **Affected Aggregates:** Order, Payment, PickupReservation, StockReservation, CouponIssuance, PointAccount, NotificationDelivery
- **Required Tests:**
  - 3분 이전 수락·거절
  - 2분 경고와 3분 자동 거절
  - 자동 거절 작업 재실행 멱등성
  - 수락과 timeout 작업의 동시 실행
  - 자동 환불 실패 시 reconciliation
  - 거절 시 원 혜택 만료 전·경계·이후 복원
  - 정책 변경 직전·직후 거절과 event snapshot
- **ADR Required:** Yes — 매장 수락 timeout과 보상 흐름
- **Revisit Conditions:** 실제 매장 수락시간 분포와 자동 거절률을 측정한 뒤 조정

## BR-07 결제 후 매장 거절 허용 범위

- **Status:** Accepted for MVP
- **Decision:** 매장은 주문 상태가 `PAID`이고 아직 `ACCEPTED`가 아닐 때만 거절할 수 있다. `ACCEPTED` 이후에는 거절 명령을 허용하지 않고 별도의 주문 취소·환불 절차를 사용한다.
- **Rationale:** 매장 수락 이후 제조가 시작될 수 있으므로 단순 거절과 환불 책임을 분리한다.
- **Affected Contexts:** Ordering, Fulfillment, Payment, Inventory, Promotion, Loyalty
- **Affected Aggregates:** Order, Payment, StockReservation, PickupReservation
- **Required Tests:**
  - `PAID` 상태 거절 성공
  - `ACCEPTED` 이후 거절 409
  - 거절 후 모든 예약·혜택 복원
  - 거절 명령 중복 요청 멱등성
- **ADR Required:** No
- **Revisit Conditions:** 매장 운영상 수락 이후 즉시 취소가 별도 정책으로 필요할 때

## BR-14 고객 주문 취소 가능 상태

- **Status:** Accepted for MVP
- **Decision:** 고객은 주문이 `PENDING_PAYMENT` 또는 `PAID`이고 매장이 아직 `ACCEPTED`하지 않은 경우에만 직접 취소할 수 있다. `ACCEPTED` 이후 취소는 고객 직접 API로 허용하지 않고 운영자 또는 매장 취소·환불 절차로 처리한다.
- **Scope Confirmation Amendment (2026-07-31):** 고객 취소 구현 범위를 위 두 상태로
  확정한다. `PENDING_PAYMENT` 취소는 슬롯·재고·쿠폰·포인트 예약 해제만으로 완결하고
  외부 환불을 만들지 않는다. `PAID` 취소는 매장 거절과 동일한 owner 보상 대상
  (결제 환불, 슬롯·재고 복원, 쿠폰·포인트 복원, 고객 알림)을 갖는다. `ACCEPTED`
  이후 고객 취소는 이번 범위의 Non-goal이며, 제조 비용 부담 주체와 취소 수수료
  정책이 Accepted 되기 전에는 상태 전이로도 허용하지 않는다. 이 amendment는
  `store-order-lifecycle` ExecPlan이 고객 취소를 Non-goal로 둔 제약을 해제한다.
- **Implementation Scope Closure (2026-07-31):** 이번 MVP에는 확정된 취소 Tx,
  환불·혜택·자원 복원, 접수/환불 알림, 정산 제외, 감사, setup scanner·제한 복구·2인
  승인, idempotency retention과 timeout work를 포함한다. ACCEPTED 이후·운영자/매장
  취소, 고객 부분취소, 새 외부 Provider 온보딩, 실제 지급·PG 수수료, 새 분산 인프라,
  unsafe DB 복구, legacy compensation 이행과 전체 lifecycle 리팩터링은 Non-goal이다.
  In-scope 내구 항목을 fake/no-op/placeholder로 대체하고 200/202를 반환하지 않는다.
- **Cancellation Window Amendment (2026-07-31):** 취소 가능 여부는 Order 상태와 이미
  존재하는 두 deadline만으로 판정한다. 별도의 취소 제한시각 필드를 두지 않고 픽업
  예정시각도 판정에 사용하지 않는다. `PENDING_PAYMENT`은 BR-03의
  `reservationExpiresAt` 이전, `PAID`는 BR-06의 `acceptanceDeadlineAt` 이전까지
  취소할 수 있다. 따라서 결제 후 취소 창은 최대 3분이며 `Order`에 새 시각 컬럼을
  추가하지 않는다.
- **Contention Amendment (2026-07-31):** 고객 취소는 만료 worker, 매장 수락과 자동
  timeout 거절과 같은 Order row lock 위의 guarded transition으로 경쟁하며 분산락을
  사용하지 않는다. 하나만 성공하고 진 명령은 자원 수량을 바꾸지 않는다. 두 deadline
  경계에서는 시간 기반 전이가 이긴다. `now >= reservationExpiresAt`인
  `PENDING_PAYMENT` 취소 요청은 기존 만료 materialization을 먼저 커밋한 뒤
  `409 RESERVATION_EXPIRED`를 반환한다. `now >= acceptanceDeadlineAt`인 `PAID` 취소
  요청은 timeout 거절 전체를 직접 materialize하지 않지만 deduplicated
  `AcceptanceTimeoutWork`와 target Audit를 내구 저장한 뒤
  `409 ORDER_STATE_CONFLICT`를 반환한다. 저장 실패는 503이고, commit 후 즉시
  wakeup과 기존 periodic worker가 같은 timeout service로 거절을 확정한다. 이미
  `ACCEPTED`, `REJECTED`, `EXPIRED` 또는 다른 원인으로 `CANCELLED`가 확정된 주문의
  취소 요청도 `409 ORDER_STATE_CONFLICT`다.
- **Cancellation Reason Amendment (2026-07-31):** 고객 취소 요청은 닫힌 reason code를
  필수로 받는다. 허용 값은 `CHANGED_MIND`, `ORDER_MISTAKE`, `WAIT_TOO_LONG`,
  `PICKUP_TIME_CONFLICT`, `PAYMENT_ISSUE`, `OTHER` 여섯 가지다. 자유 입력 상세 사유는
  선택이며 `trim` 후 최대 200자이고 제어문자를 허용하지 않는다. 빈 문자열은 저장하지
  않고 부재로 정규화한다. reason code는 고객이 신고한 사유이고 취소 원인
  `cancellation_cause`는 시스템이 판정한 값이므로 두 축은 독립이다. reason code는
  `cancellation_cause`가 `CUSTOMER_REQUEST`인 취소에만 존재한다.
  `PAYMENT_ISSUE`는 결제수단을 바꾸고 싶거나 결제 금액을 잘못 선택했다고 고객이
  판단해 직접 취소한 경우를 뜻하며, Provider 승인 거절로 시스템이 취소한
  `cancellation_cause = PAYMENT_DECLINED`와 다른 사건이다.
  `Order` row에는 reason code와 허용된 detail을 저장한다. `AuditRecord`에는 정규화된
  reason code만 저장하고 detail은 복제하지 않는다. Refund 내부 기록과 외부 결제
  취소 요청에는 처리에 필요한 정규화된 reason만 사용하며 detail은 전달하지 않는다.
  `OrderCancelledV1` persistent payload에는 reason code와 detail을 모두 포함하지 않는다.
  애플리케이션 로그에도 reason code와 detail을 복제하지 않는다.
- **Authorization Amendment (2026-07-31):** 고객 취소는 주문을 소유한 고객만 실행할
  수 있다. 운영자와 매장 구성원의 고객 주문 취소 실행은 이번 범위의 Non-goal이며
  Authorization Matrix의 `Approved operation`은 후속 Feature로 남는다. 취소한 고객은
  자기 주문의 취소 결과와 환불 진행 요약 상태를 조회한다. 외부 결제가 없는
  `PENDING_PAYMENT` 취소는 환불 없음을 별도 상태로 표현하고 성공한 환불로 표시하지
  않는다. 보상 단계별 상태, 시도 횟수와 내부 오류 코드는 운영자만 조회한다. 매장
  구성원은 고객 취소 사실과 주문 상태만 조회하고 결제 환불 진행은 조회하지 않는다.
- **Event Taxonomy Amendment (2026-07-31):** 고객 요청 취소는 매장 거절
  `OrderRejectedV1`을 재사용하거나 공통 종료 event로 합치지 않고 별도
  `OrderCancelledV1` 사실로 발행한다. 공통 보상 Case와 owner별 보상 구조를 공유하더라도
  고객 취소와 매장 거절의 책임·actor·사유·알림 의미는 event type 수준에서 구분한다.
  `OrderCancelledV1`은 비동기 owner 보상이 필요한 미수락 `PAID` 고객 취소에서만
  발행한다. `PENDING_PAYMENT` 취소는 주문 명령 transaction 안의 네 예약 해제와
  `200` 응답으로 완결하며 취소 event, 주문 보상 Case와 event publication 복구를
  만들지 않는다.
  event의 기본 payload는 공통 envelope, `orderId`, `cancelledAt`,
  `couponRequired`, `pointsRequired`다. ADR-044로 Notification consumer가 제거된 뒤
  사용처가 없는 `customerId`, `storeId`, `reasonCode`는 ADR-055에 따라 persistent
  payload에서 제거한다.
  required flag는 취소 transaction에서 확정한 Order 금액 snapshot으로 산출하며
  consumer가 불필요한 owner 작업을 시작하지 않게 한다. actor, `cancellationCause`,
  취소 전 상태, 자유 입력 `detail`, 금액, 자원 ID와 Provider reference는 담지 않는다.
  `couponPolicy`와 `pointsPolicy`는 required flag와 무관하게 항상
  `policyVersionId`, mode, validity days 전체 snapshot을 담는다.
  envelope의 `correlationId`는 취소 HTTP 요청의 correlation을 전파하고 부재하면
  서버가 생성한다. `causationId`는
  `customer-cancellation-command:{cancellationCommandId}`이며 같은 transaction에서
  저장하는 내부 취소 멱등 레코드 UUID를 사용한다. client `Idempotency-Key`, customer
  ID와 자유 입력 `detail`을 event lineage나 log에 복제하지 않는다. publication
  재시도는 최초 envelope를 그대로 사용하고 새 lineage를 만들지 않는다.
  owner consumer의 중복 기준은 event ID가 아니라
  `order:{orderId}:customer-cancellation:{aggregateVersion}:{step}` source reference다.
  event consumer의 `step`은 `pickup`, `stock`, `coupon`, `points` 중 하나다.
  Tx C1이 직접 준비하는 Payment와 Notification 작업은 같은 형식의 `payment`,
  `notification` step을 사용하지만 event consumer는 아니다.
  같은 Order terminal version의 event가 새 event ID로 다시 생성돼도 owner 부수효과를
  새로 만들지 않으며 event ID는 추적 정보로만 사용한다. 외부 Provider 멱등키는
  결제 환불 정책에서 별도로 확정한다.
  consumer는 같은 source reference가 이미 적용됐거나 같은 owner work가 진행
  중이면 새 부수효과와 attempt를 만들지 않고 기존 결과를 반환한다. 아직 적용 가능한
  owner 상태면 한 번만 적용한다. 다른 source·trigger·version이 상태를 이미
  점유했거나 현재 상태가 event와 모순이면 성공으로 간주하거나 덮어쓰지 않고
  `COMPENSATION_SOURCE_CONFLICT`로 publication을 실패시킨다. 이 실패는 bounded
  retry와 `MANUAL_REVIEW`로 복구하며 Order의 `CANCELLED` 전이를 되돌리지 않는다.
  특정 listener의 publication 재시도가 소진되면 그 listener에 대응하는 단일
  보상 step만 `MANUAL_REVIEW`와 `EVENT_PUBLICATION_RETRY_EXHAUSTED`로 전환한다.
  Case 전체 상태는 step에서 파생해 `MANUAL_REVIEW`가 되지만 다른 owner publication과
  step은 계속 처리한다. publication completion attempt는 보상 step의 business
  `attemptCount`에 더하지 않는다. 이 규칙은 `OrderCancelledV1`과
  `OrderRejectedV1`에 동일하게 적용한다.
  V1 payload는 남은 정책 snapshot을 구현 전에 완성한 뒤 최초 운영 publication부터
  동결한다. 이후 필수 필드 제거·이름·타입·필드 의미 변경은 `OrderCancelledV2`로
  이행하고, 구 consumer가 무시할 수 있으며 역직렬화 기본값이 있는 선택 필드만 V1에
  추가할 수 있다. ADR-059 release gate가 모든 대상을 명시적으로 0으로 입증한
  clean-cutover 경로에서는 producer, consumer와 fixture를 같은 변경에서 전환하고
  legacy listener mapping, compatibility layer와 version 이중 발행을 만들지 않는다.
  하나라도 nonzero 또는 unknown인 forward-migration 경로에서만 기존 migration과 V1
  계약, 구 publication 역직렬화와 legacy listener-target-to-step mapping을 유지하며
  publication drain, rollback compatibility와 version/bridge 종료 조건을 별도
  ADR/ExecPlan으로 확정한다.
  Tx C1이 Refund `REQUESTED`를 이미 내구 저장하므로 Payment는 `OrderCancelledV1`
  consumer가 아니고 `paymentRequired`도 event payload에 포함하지 않는다. PAYMENT
  보상 step은 Refund worker가 직접 갱신한다. Refund source reference는
  `order:{orderId}:customer-cancellation:{aggregateVersion}:payment`를 사용한다.
  Provider 명시 거절로 발생한 `cancellation_cause = PAYMENT_DECLINED`의 event 계약은
  이 amendment의 범위가 아니다.
- **Paid Cancellation Transaction Amendment (2026-07-31):** 미수락 `PAID` 고객
  취소는 단일 로컬 transaction에서 Order `CANCELLED`와 원인 필드, 최초 `202`를 담은
  취소 멱등 레코드, `CUSTOMER_CANCELLATION` 주문 보상 Case와 여섯 step, 외부 결제
  금액이 있으면 Refund `REQUESTED`, `ORDER_CANCELLATION_ACCEPTED`
  NotificationDelivery `PENDING`, AuditRecord, `OrderCancelledV1`과 네 owner별
  영속 publication을 함께 commit한다. 한 항목이라도 저장에 실패하면 전체
  rollback하고 `202`를 반환하지 않는다. 외부 Provider 호출과 픽업 슬롯·재고·쿠폰·
  포인트 복원은 이 transaction에 넣지 않는다. 네 자원은 commit 후 owner listener가
  각자의 transaction에서 처리하고, Notification Provider는 delivery worker가
  transaction과 lock 밖에서 호출한다. `PENDING_PAYMENT` 취소도 Tx C0에서 같은
  template의 NotificationDelivery를 함께 저장하며 insert 실패 시 취소를 rollback한다.
  두 상태의 성공 응답은 알림 발송 성공이 아니라 delivery work의 내구 저장을 뜻한다.
- **Refund Follow-up Notification Amendment (2026-07-31):** 현금 환불액이 양수인
  고객 취소 Refund는 실제 `SUCCEEDED` 확정 시 성공 알림을 한 번 보내고, 자동
  REQUEST·LOOKUP 처리가 끝나 `FAILED` 또는 `MANUAL_REVIEW`가 되면 고객용 지연
  알림을 한 번 보낸다. 진행·재시도·불명·reconciliation 중에는 후속 알림을 보내지
  않는다. 지연 뒤 운영 복구로 성공하면 성공 알림을 추가로 보낸다. 각 알림은 Order
  terminal version과 종류별 logical source로 중복을 막는다. 지연 문구는 내부
  실패·수동 검토를 노출하지 않으며
  “환불 처리가 지연되고 있습니다. 불편을 드려 죄송합니다. 최대한 빠르게
  처리하겠습니다.”를 사용한다. `PENDING_PAYMENT`와 `BENEFIT_ONLY` 취소에는 현금
  환불 후속 알림이 없다.
- **Refund Notification Delivery Amendment (2026-07-31):** Payment는 고객 취소
  Refund의 실제 성공과 자동 처리 종료 지연을 각각
  `CustomerCancellationRefundSucceededV1`,
  `CustomerCancellationRefundDelayedV1` 영속 event로 기록한다. Refund 결과와
  Notification listener publication을 같은 transaction에 commit하고 Notification은
  별도 transaction에서 stable logical source의 Delivery를 만든다. 일반
  `PaymentRefunded`를 고객 취소 알림 근거로 재사용하거나 terminal Refund polling
  scanner를 두지 않는다. event publication 실패를 삼키지 않으며 외부 Provider가
  이미 성공한 뒤 local result transaction이 rollback되면 새 환불이 아니라 같은 key
  reconciliation으로 결과를 다시 확정한다.
- **Notification Step Scope Amendment (2026-07-31):** 공통 주문 보상 Case의
  CUSTOMER_NOTIFICATION step은 주문 종료 직후 기본 고객 알림만 추적한다. 고객 취소
  trigger에서는 `ORDER_CANCELLATION_ACCEPTED` Delivery의 상태를 단조롭게 반영한다.
  환불 성공·지연 후속 event, publication과 Delivery는 이 step을 다시 열거나
  갱신하지 않고 각각의 publication, Delivery와 ReprocessingCase에서 복구한다.
  따라서 Case 완료는 미래 환불 후속 알림 전체의 완료를 뜻하지 않는다.
- **Compensation Completion Notification Amendment (2026-07-31):** 고객 취소
  OrderCompensationCase 전체 성공, 슬롯·재고 복원 또는 쿠폰·포인트 복원 완료에는
  별도 고객 알림을 보내지 않는다. 고객 알림은 취소 접수와 현금 환불 성공·지연으로
  제한하고, 혜택 결과는 기존 보유 내역에 반영한다. Case 완료를 원인으로
  Notification event나 Delivery를 만들지 않는다.
- **Prior Partial Refund Amendment (2026-07-31):** 선행 성공 부분 환불이 있는
  미수락 `PAID` 주문도 고객 취소를 허용한다. 새 고객 취소 Refund의 현금 요청액은
  `approvedAmountKrw - succeededRefundAmountKrw`이며 성공 누적 환불과 새 요청의 합은
  승인액을 초과할 수 없다. 포인트 보상은 선행 부분 환불에서 이미 복원된 line
  allocation을 다시 복원하지 않고 아직 복원되지 않은 잔여 allocation만 대상으로
  한다. 부분 환불의 coupon allocation은 귀속 원장이며 원 쿠폰을 복원하지 않으므로
  고객 취소의 COUPON step은 원 CouponIssuance를 한 번만 복원한다. 선행 부분 환불이
  있다는 이유만으로 `409`를 반환하지 않는다. 현재 V10 Refund에는 line-level 현금·
  포인트 복원·coupon 귀속 allocation 원장이 없으므로 구현 전에 새 데이터 원천과
  중복 방지 제약을 확정해야 한다.
- **Payment Recovery Summary Amendment (2026-07-31):** 고객 summary의 state는 이번
  고객 취소 source의 Refund 한 건에서만 파생하고 선행 Refund 상태나 보상 PAYMENT
  step을 합성하지 않는다. summary는 최초 승인액, Tx C1 전에 성공한 환불액, 이번 고객
  취소 요청액과 조회 시점 남은 환불 가능액을 함께 반환한다. 앞의 세 금액은 Tx C1
  snapshot이고, 남은 환불 가능액은 최초 승인액에서 조회 시점까지 `SUCCEEDED`인 모든
  Refund 성공액만 뺀 현재 실제 잔액이다. 진행 중·불명·실패·수동 검토 Refund는 성공
  환불액에 포함하지 않는다. 취소 요청액이 0인 경우에만 `NOT_REQUIRED`다. 양수인데
  고객 취소 Refund 또는 필수 snapshot이 없으면 내부 `SETUP_INCOMPLETE`와 운영
  alert이며 고객에게는 `PROCESSING + REFUND_DELAYED`로 투영한다. snapshot이 없어
  검증할 수 없는 금액은 0이나 현재값으로 추정하지 않고 생략한다.
- **Setup Integrity Detection Amendment (2026-07-31):** 관련 고객·운영 조회,
  Refund worker와 settlement consumer가 setup 손상을 발견하면 즉시 source-unique
  `PAYMENT_CANCELLATION_SETUP` ReprocessingCase와 append-only AuditRecord를
  transaction으로 저장한다. 고객 접근이 없는 손상은 기본 1분, batch 100의
  violation-only scanner가 보완하고 같은 case로 수렴한다. 감지 저장이 실패하면
  조회는 503, worker/consumer는 retry로 남기며 로그만 남기고 성공하지 않는다.
- **Safe Setup Repair Amendment (2026-07-31):** PLATFORM_OPERATOR의
  application-level 복구는 immutable recovery snapshot이 완전하고 Refund row만
  누락된 경우로 제한한다. snapshot의 원 Refund ID·금액·source·Provider key로 row를
  복원하고 과거 호출 가능성 때문에 새 REQUEST가 아니라 같은 key LOOKUP부터
  시작한다. operator는 non-blank 사유만 입력하며 금융 값은 입력·수정하지 않는다.
  snapshot 누락, source·금액 불일치와 기존 Refund 충돌은
  `REPROCESSING_NOT_SAFE`로 차단하고 engineering remediation으로 남긴다.
- **Two-person Repair Approval Amendment (2026-07-31):** 누락 Refund 안전 복구는
  서로 다른 두 활성 PLATFORM_OPERATOR가 제안·승인해야 한다. 제안은 30분
  `[createdAt, expiresAt)` 동안 유효하고 승인 transaction이 Order→Payment 잠금
  아래 safe guard와 immutable fingerprint를 다시 검증한다. 만료·stale·self approval은
  실행 없이 명시적 409로 종결한다. 제안과 결정은 각각 non-blank 사유,
  Idempotency-Key와 append-only AuditRecord를 요구한다.
- **Unresolved Refund Contention Amendment (2026-07-31, RETRY_SCHEDULED
  clarified 2026-08-01):** 고객 취소 Tx C1은
  Order 다음 Payment와 Refund row를 잠근다. 선행 Refund 중 `REQUESTED`,
  `PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`가
  하나라도 있으면 Order
  전이와 취소 멱등 레코드 저장 전에 `409 PAYMENT_REFUND_UNRESOLVED`로 rollback한다.
  `RETRY_SCHEDULED`는 이 amendment보다 뒤에 확정된 Retryable Refund Failure
  Amendment가 도입한 상태이며, 같은 key REQUEST 재시도가 아직 due인 미확정
  구간이므로 차단 대상이다.
  `SUCCEEDED`만 취소 전 성공 환불액에 포함하고, Provider가 부수효과 없음을 명시한
  `FAILED`는 합계에서 제외한 채 고객 취소를 허용한다. 모든 Refund 생성 경로는 Order
  lock이 필요하면 `Order → Payment`, 아니면 Payment부터 잠그며 Payment를 잠근 뒤
  Order를 역순으로 잠그지 않는다. lock timeout과 DB 장애는 409가 아니라
  `503 DEPENDENCY_UNAVAILABLE`다. 차단된 같은 key 요청은 terminal 멱등 레코드를 남기지
  않아 Refund가 확정된 뒤 같은 key로 재실행할 수 있다.
- **Cancellation Refund Identity Amendment (2026-07-31):** 고객 취소 Refund의 내부
  `reason`은 `CUSTOMER_ORDER_CANCELLED`이고 고객 신고 사유는 별도
  `customer_reason_code`에 BR-14의 여섯 code 중 하나로 저장한다. source reference는
  `order:{orderId}:customer-cancellation:{aggregateVersion}:payment`, Provider
  idempotency key는
  `refund:customer-cancellation:{orderId}:{aggregateVersion}`다. 최초 Provider
  요청과 모든 lookup·reconciliation은 같은 key를 사용한다. event ID·event version,
  client `Idempotency-Key`, customer ID와 자유 입력 `detail`은 Refund 또는 Provider
  식별자·요청에 넣지 않는다. Provider 취소 요청에는 계약상 필요한 정규화된
  `customer_reason_code`만 전달한다. 애플리케이션 로그에는 `customer_reason_code`와
  자유 입력 `detail`을 모두 기록하지 않는다.
- **Cancellation Refund Reconciliation Budget Amendment (2026-07-31):** 고객 취소
  Refund 요청 결과가 불명확해지면 같은 Provider idempotency key로 10초, 30초, 2분,
  5분, 15분 뒤 최대 다섯 번 조회하고 REQUEST를 다시 보내지 않는다. 다섯 번째 조회도
  불명이거나 마지막 lookup claim이 결과 저장 전 종료되면 추가 요청·조회 없이
  `MANUAL_REVIEW`로 전환한다. 이 lookup 예산은 매장 거절 Refund에도 동일하게
  적용한다.
- **Retryable Refund Failure Amendment (2026-07-31):** Provider adapter가 이번
  호출의 부수효과 없음과 같은 key 재실행 안전을 모두 보장하고 코드에 고정된
  Provider별 allowlist에 raw code가 포함될 때만 명시 실패를 자동 재요청한다. 최초
  REQUEST 뒤 10초와 30초에 최대 두 번 같은 key로 재요청한다. 어느 요청이든 결과가
  불명확해지면 REQUEST를 영구 중단하고 별도 lookup 5회 예산으로 전환한다. 미등록
  code와 세 번째 retryable failure는 Refund `FAILED`, PAYMENT step과 Case
  `MANUAL_REVIEW`다.
- **Customer Refund Status Projection Amendment (2026-07-31):** 고객은 내부
  `PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`, `RECONCILING`을 모두
  `CancellationRefundRecoverySummary.state = PROCESSING`으로 본다. 내부 `FAILED`와
  `MANUAL_REVIEW`도 고객 state는 `PROCESSING`이지만
  `noticeCode = REFUND_DELAYED`를 함께 반환한다. 클라이언트는 이 code에 정보 아이콘과
  “환불 처리가 지연되고 있습니다. 불편을 드려 죄송합니다. 최대한 빠르게
  처리하겠습니다.”라는 locale별 안내를 연결한다. 고객에게 재시도 여부·attempt·실패
  code·수동 검토 상태를 노출하지 않고 운영자에게는 모두 제공한다.
- **Benefit-only Cancellation Amendment (2026-07-31):** `BENEFIT_ONLY`인 미수락
  `PAID` 주문도 일반 고객 취소와 같은 OrderCompensationCase와 여섯 step을 만든다.
  Tx C1에서 PAYMENT step을 attempt 0, error null의 `NOT_REQUIRED`로 저장하고,
  Refund와 Provider 호출은 만들지 않는다. recovery snapshot과 고객 요약의 네
  금액은 모두 0이고 `noticeCode`는 없다. 나머지 다섯 step은 일반 `PAID` 취소처럼
  처리하므로 응답은 `202`다.
- **Confirmed Resource Restoration Amendment (2026-07-31):** 매장 거절과 고객
  취소의 PickupReservation·StockReservation은 공통 terminal 상태
  `RELEASED_AFTER_TERMINATION`을 사용하고 별도 `restoration_trigger`로
  `STORE_REJECTION` 또는 `CUSTOMER_CANCELLATION`을 보존한다. 동일 source·trigger
  중복은 수량을 다시 바꾸지 않고, 다른 source 또는 trigger 충돌은 덮어쓰지 않고
  `COMPENSATION_SOURCE_CONFLICT`로 해당 owner step만 재시도·수동 검토한다.
- **Benefit Policy Scope Amendment (2026-07-31):** 고객 취소용 COUPON·POINTS 정책
  head는 각각 `PRESERVE_ORIGINAL_EXPIRY`로 시작하고 운영자가 이후 독립 변경할 수
  있다. 모든 보상 Case는 혜택 사용 여부와 관계없이 두 immutable policy version을
  `(case_id, benefit_type)` UNIQUE child FK로 snapshot한다. `OrderCancelledV1`과
  pre-release `OrderRejectedV1`은 두 전체 snapshot을 항상 담고 consumer는 현재
  head를 조회하지 않는다. 기존 `OrderRejectedV1`을 V2나 호환 계층 없이 제자리
  변경할 수 있는지는 ADR-059 release gate가 production 발행·외부 사용·보존
  publication과 rollback 대상이 모두 없음을 증거로 확인한 경우에만 성립한다. 하나라도
  존재하거나 확인할 수 없으면 기존 계약을 변경하지 않고 forward migration과
  compatibility를 다루는 별도 ADR/ExecPlan을 먼저 확정한다.
- **Benefit Restoration Ledger Amendment (2026-07-31):** 쿠폰·포인트 owner
  원장은 복원 결과와 trigger를 분리한다. CouponReservation은 `RESTORED`와 함께
  `ORIGINAL_RESTORED`, `COMPENSATION_ISSUED`, `SKIPPED_EXPIRED` disposition,
  trigger, policy version ID와 source를 저장한다. PointTransaction은 기존
  `RESTORE`, `COMPENSATION`, `RESTORE_SKIPPED_EXPIRED` type을 결과로 유지하고
  trigger·policy version ID를 별도 저장한다. 같은 source·trigger·policy만 멱등
  성공이며 다른 조합은 `COMPENSATION_SOURCE_CONFLICT`다.
- **Compensation Coupon Terms Amendment (2026-07-31):** 만료 쿠폰을 새 issuance로
  보상할 때 원 Campaign의 store, 할인 type/value, minimum/maximum과 대상 menu
  집합을 issuance 소유 immutable snapshot으로 복제한다. 보상 쿠폰은 원 Campaign의
  이후 inactive·설정 변경과 무관하게 snapshot으로 계산하되 현재 판매 불가 menu를
  주문 가능하게 만들거나 대상을 자동 확대하지 않는다. 일반 마케팅 issuance는
  계속 live Campaign active 검증을 따른다.
- **Rationale:** 제조 시작 이후 발생한 비용과 고객 편의를 구분하고 상태 전이를 단순화한다. `PAID` 창은 BR-06의 수락 deadline에 의해 최대 3분이므로 고객 이탈 경로를 제공하는 비용이 제한적이다.
- **Affected Contexts:** Ordering, Eventing, Payment, Fulfillment, Inventory, Promotion, Loyalty, Notification, Operations
- **Affected Aggregates:** Order, Payment, PickupReservation, StockReservation
- **Required Tests:**
  - 허용 상태별 취소
  - `ACCEPTED` 이후 고객 취소 거부
  - `PREPARING`, `READY`, `COMPLETED`, `EXPIRED`, `REJECTED`, `CANCELLED`의 고객 취소 거부
  - 결제 전·후 취소의 보상 차이
  - `PENDING_PAYMENT` 취소가 외부 환불을 만들지 않음
  - `reservationExpiresAt`, `acceptanceDeadlineAt` 직전 취소 성공
  - 두 deadline 정확 경계와 이후 취소 거부
  - 새 취소 전용 시각 컬럼 부재 검증
  - 취소와 매장 수락 동시 실행의 단일 최종 상태
  - 취소와 timeout 자동 거절 동시 실행의 단일 보상 적용
  - 취소와 lease 만료 동시 실행의 단일 자원 해제
  - 허용되지 않는 reason code 거부
  - reason code 누락 요청 거부
  - 상세 사유 200자 경계와 초과 거부
  - 제어문자 포함 상세 사유 거부
  - 공백만 있는 상세 사유의 부재 정규화
  - Order row의 reason code와 허용된 detail 저장
  - AuditRecord의 reason code 저장과 detail 부재
  - Refund 내부 기록·Provider 취소 요청의 필요한 정규화 reason과 detail 부재
  - `OrderCancelledV1` persistent payload와 애플리케이션 log의 reason code·detail 부재
  - `PAYMENT_ISSUE` 취소가 `CUSTOMER_REQUEST` cause로 기록됨
  - `PAYMENT_DECLINED` 취소에 reason code가 없음
  - 타 고객 주문 취소 거부와 조회·취소의 응답 코드 일치
  - 매장·운영자 role의 고객 취소 endpoint 호출 거부
  - 결제 전 취소 응답의 환불 없음 표현
  - 고객 응답에 보상 step 상세와 상세 사유 부재
  - 고객 취소가 `OrderCancelledV1`만 발행하고 `OrderRejectedV1`을 발행하지 않음
  - 매장 거절이 `OrderRejectedV1`만 발행하고 `OrderCancelledV1`을 발행하지 않음
  - `PAID` 고객 취소의 `OrderCancelledV1` 단일 발행
  - `PENDING_PAYMENT` 고객 취소의 취소 event·보상 Case·publication 부재
  - required flag가 Order 금액 snapshot과 일치하고 불필요한 owner 작업이 없음
  - event 직렬화 결과에 actor·detail·금액·자원 ID·Provider reference가 없음
  - 요청부터 event·owner work까지 correlation 유지와 raw `Idempotency-Key` 부재
  - publication 재시도에서 최초 correlation·causation 보존
  - 같은 Order version·같은 step의 동일/다른 event ID 중복에서 owner 부수효과 한 번
  - source reference의 trigger·version·step 구분과 다른 원인 reference 충돌 방지
  - 같은 source의 처리 중·완료 상태 재전달에서 attempt와 부수효과 불변
  - 다른 source·오래된 version·모순 상태의 비덮어쓰기와 manual review 전환
  - 한 listener 재시도 소진 시 해당 step만 manual review이고 다른 step은 계속 완료
  - publication completion attempt와 owner business attempt의 분리
  - release gate의 production·공유 schema/row, 완료·미완료 publication, 외부 consumer,
    rollback binary inventory와 unknown/nonzero 차단
  - **Clean-cutover path (release gate 전체 0):** production·공유 schema와 row 없음,
    완료·미완료 persistent publication 없음, 외부 consumer 없음, rollback 대상 binary
    없음, producer·consumer·fixture의 동일 변경 전환, legacy compatibility layer와
    version 이중 발행 없음
  - **Forward-migration path (release gate nonzero 또는 unknown):** 기존 migration과 V1
    계약 유지, 구 publication 역직렬화, legacy listener routing, publication drain,
    rollback compatibility, 별도 version 또는 compatibility bridge 검증
  - forward-migration의 breaking payload 변경이 기존 V1 제자리 변경 없이 새 version
    또는 compatibility bridge로 분리됨
  - `OrderCancelledV1`에 Payment publication과 `paymentRequired` 필드가 없음
  - Tx C1 Refund source reference와 Refund worker의 PAYMENT step 갱신
  - `PAID` 취소 `202` 시 필수 내구 묶음의 전부 존재 또는 전부 rollback
  - Refund `REQUESTED` 저장 실패와 publication·Audit 저장 실패의 전체 rollback
  - `202` 반환 시 외부 Provider 미호출과 네 자원·알림의 처리 중 상태 허용
  - commit 후 owner listener 실패에서 Order `CANCELLED` 유지와 step별 복구
  - 선행 성공 부분 환불 후 고객 취소의 남은 현금만 환불
  - 선행 부분 환불에서 이미 복원한 line 포인트의 이중 복원 부재
  - 부분 환불 coupon 귀속을 복원으로 오인하지 않고 전체 종료에서 원 쿠폰 한 번 복원
  - 성공 누적 환불액과 고객 취소 Refund 합계의 승인액 상한
  - summary state가 고객 취소 Refund만 따르고 선행 Refund state와 독립적임
  - 세 Tx C1 snapshot 금액과 조회 시점 실제 잔액 계산
  - 진행 중·불명·실패 Refund의 성공 환불액 제외
  - 필요한 Refund/snapshot 누락의 고객 지연 projection, 조건부 금액 생략과 운영
    `SETUP_INCOMPLETE` alert
  - 선행 Refund 상태별 취소 허용·`PAYMENT_REFUND_UNRESOLVED` 차단
  - 선행 `RETRY_SCHEDULED` Refund의 `PAYMENT_REFUND_UNRESOLVED` 차단
  - 선행 전액 환불로 요청액이 0인 취소의 `NOT_REQUIRED`와 양수 승인액·선행 성공액
  - 고객 `Order`의 취소 시각·원인·reason code 노출과 매장 표현의 reason code·환불
    진행 부재
  - 부분 환불과 고객 취소 동시 실행의 Order→Payment lock 순서
  - 409 rollback 뒤 같은 key 재시도와 terminal 멱등 레코드 부재
  - Payment lock timeout의 503과 Order·Refund 부분 상태 부재
  - 같은 Order terminal version의 단일 고객 취소 Refund·Provider 요청
  - Provider 요청·lookup 전 구간의 동일 idempotency key
  - Refund/Provider payload와 log의 자유 `detail`·client key·customer ID 부재
  - 중복 취소 멱등성
- **ADR Required:** Yes — 고객 취소 범위와 보상 경계
- **Revisit Conditions:** 매장별 취소 가능 시간이나 제조 단계별 수수료 정책을 도입할 때

## BR-15 부분 품목 취소 범위

- **Status:** Accepted for MVP
- **Decision:** 결제 전에는 주문 항목을 변경하지 않고 주문 전체를 취소한 뒤 새 주문을 생성한다. 결제 후에는 주문 항목 변경을 금지하고, 필요한 경우 품목 단위 부분 환불로 처리한다. 부분 환불은 매장 또는 운영자만 실행할 수 있다.
- **Customer Cancellation Composition Amendment (2026-07-31):** 미수락 `PAID`
  주문에 성공한 부분 환불이 있어도 고객 전체 취소를 허용한다. 전체 취소는 승인액에서
  이미 성공한 현금 환불을 뺀 잔액만 요청하고, 부분 환불이 이미 복원한 line 포인트
  allocation을 제외한 잔여 포인트만 복원한다. 부분 환불은 쿠폰을 복원하지 않으므로
  이후 주문 전체 종료 시 원 CouponIssuance를 기존 종료 정책에 따라 한 번만 복원한다.
  line별 성공 현금 환불·포인트 복원 원장과 coupon 귀속 원장이 이 합성을 재현 가능하게
  보호해야 한다.
- **Immutable Refund Event Disposition Amendment (2026-08-02):** 품목 부분 환불은 기존
  허용 상태인 `PAID`, `ACCEPTED`, `PREPARING`, `READY`, `COMPLETED`를 유지한다. 성공
  Refund result transaction은 immutable Order 상태·완료 source와 refund trigger를 함께
  판정해 `PaymentRefundedV1.completionDisposition`을 다음 세 값 중 하나로 고정한다.
  이미 완료된 Order의 환불은 `COMPLETED_ORDER`, 고객 취소·매장 거절처럼 완료 없이
  미수락 종료된 환불은 `PRE_ACCEPTANCE_CANCELLATION`, 그 밖의 완료 전 품목 환불은
  `PRE_COMPLETION_ORDER`다. `PRE_COMPLETION_ORDER`는 주문 생성 시점 settlement snapshot과
  성공 allocation으로 계산한 refund effect를 포함하지만 완료 시각·정산일·SettlementItem
  source는 포함하지 않는다. 후속 Settlement와 Analytics는 나중의 `OrderCompletedV2`와
  source-aware하게 결합하며 현재 Order나 정책을 재조회해 누락 값을 채우지 않는다.
- **Rationale:** 결제·쿠폰·포인트·정산 금액을 다시 계산하면서 주문 원본이 변하는 문제를 방지한다.
- **Affected Contexts:** Ordering, Payment, Promotion, Loyalty, Settlement
- **Affected Aggregates:** Order, Payment, SettlementAdjustment
- **Required Tests:**
  - 결제 시작 후 주문 항목 변경 거부
  - 품목 단위 부분 환불 금액 계산
  - 반복 부분 환불 누적액 검증
  - 부분 환불 후 고객 전체 취소의 현금·포인트·쿠폰 allocation tie-out
  - 완료 전 부분 환불의 `PRE_COMPLETION_ORDER`와 이후 완료 event의 out-of-order 수렴
  - 부분 환불에서 이미 복원된 line 포인트의 이중 복원 방지
  - 부분 환불 성공 시 CouponIssuance 상태 불변과 이후 전체 종료 시 한 번만 복원
  - 정산 전·후 부분 환불 처리 차이
- **ADR Required:** Yes — 주문 불변 스냅샷과 부분 환불
- **Revisit Conditions:** 고객 셀프 부분 취소나 매장 제조 전 수정 요구가 커질 때

---

# C. 쿠폰·포인트 정책

## BR-08 쿠폰과 포인트 적용 순서

- **Status:** Accepted for MVP
- **Decision:** 주문 원금에 쿠폰 할인을 먼저 적용한 뒤, 남은 금액에 포인트를 사용한다.
- **Amendment (2026-07-28):** 쿠폰 Campaign은 `FIXED_KRW` 또는 `RATE_BPS`이며 대상 메뉴가 제한되면 대상 OrderLine의 할인 전 합계만 minimum과 할인 계산에 사용한다. 정액 할인은 대상 합계를 넘지 않고, 정률은 basis point로 계산해 원 미만을 버린 뒤 선택적 최대 할인액을 적용한다. 대상 메뉴가 아닌 품목은 minimum을 채우거나 쿠폰 할인을 받지 않는다.
- **Rationale:** 쿠폰 정책의 최소 주문금액과 최대 할인액을 먼저 확정하고 포인트가 실제 남은 결제액을 차감하도록 한다.
- **Affected Contexts:** Ordering, Promotion, Loyalty, Settlement
- **Affected Aggregates:** Order, CouponIssuance, PointAccount, SettlementItem
- **Required Tests:**
  - 쿠폰 적용 후 포인트 한도 계산
  - 정액 할인과 대상 합계 상한
  - 정률 basis point 버림과 최대 할인 상한
  - 대상·비대상 품목 혼합 주문의 minimum 기준
  - 쿠폰 미적용·포인트만 적용
  - 0원 주문 경계
  - 환불 시 쿠폰·포인트 배분 재현
- **ADR Required:** Yes — 할인·혜택 계산 순서와 Campaign 계산 모델
- **Revisit Conditions:** 쿠폰 정책에서 포인트 사용 전 금액을 기준으로 해야 하는 요구가 생길 때

## BR-09 쿠폰 중복 사용

- **Status:** Accepted for MVP
- **Decision:** 한 주문에는 쿠폰을 최대 1개만 사용할 수 있다. 자동 기간 할인과 쿠폰의 중복 여부는 캠페인 정책에 명시하며, 기본값은 중복 불가다.
- **Rationale:** MVP에서 할인 조합 폭발과 설명하기 어려운 우선순위 문제를 방지한다.
- **Affected Contexts:** Promotion, Ordering, Settlement
- **Affected Aggregates:** Campaign, CouponIssuance, Order
- **Required Tests:**
  - 주문당 쿠폰 2개 적용 거부
  - 기간 할인과 쿠폰 중복 불가
  - 허용 정책이 명시된 경우의 조합
  - 동시 주문에서 같은 쿠폰 중복 사용 방지
- **ADR Required:** No
- **Revisit Conditions:** 세트 할인·다중 쿠폰·우선순위 엔진을 구현할 때

## BR-10 포인트 적립 기준 금액

- **Status:** Accepted for MVP
- **Decision:** 포인트는 쿠폰 할인과 포인트 사용을 모두 반영한 뒤 외부 결제수단 또는 혜택 전용 결제로 최종 확정된 실결제액을 기준으로 적립한다. 환불된 금액에는 포인트를 적립하지 않는다.
- **Immutable Ordinary Accrual Snapshot Amendment (2026-08-01):** 일반 적립에 적용할
  적립률·반올림·issuer type/reference·만료 규칙과 기간·품목/수량 단위 적립 배분 결과는
  주문 생성 transaction에서 하나의 immutable `OrderPointAccrualSnapshot`으로 확정한다.
  `OrderCompletedV1`은 적립을 시작하는 trigger일 뿐 payload를 늘리지 않으며, Loyalty는
  typed Ordering boundary를 통해 그 snapshot만 읽는다. 완료 시점에 현재 정책을 조회하거나
  snapshot 누락 시 0원 적립, 기본 issuer 또는 기본 만료를 사용하지 않는다.
  이 amendment는 결정 시점과 snapshot 구조를 확정한다. 실제 적립률·반올림, issuer
  type/reference와 만료 기준/기간은 아래 closed vocabulary를 따르는 Operations policy
  version data이며 소스 코드 default로 고정하지 않는다.
- **Global Default and Store Override Scope Amendment (2026-08-01):** 일반 적립 정책은
  필수 전역 기본 head와 선택적 매장별 override head를 append-only version으로 관리한다.
  Order 생성 transaction은 `storeId`에 해당하는 override가 있으면 그 current version을,
  없으면 전역 current version을 선택하고 선택한 version과 계산 결과를
  `OrderPointAccrualSnapshot`에 고정한다. 정책 변경은 이 선형화 시점 뒤에 생성되는
  Order에만 적용하며 이미 저장된 Order snapshot, 완료 적립, 부분 환불 회수와 pending을
  다시 계산하지 않는다. `BRAND`별 적용 scope와 매장·브랜드에서 issuer를 추론하는 규칙은
  이번 MVP 범위가 아니다. 각 version의 issuer type/reference는 그 version이 선택된 주문에
  그대로 적용되는 명시적 비용 주체다.
- **Store Override Inheritance Amendment (2026-08-01):** STORE policy head는
  `OVERRIDE` 또는 `INHERIT_GLOBAL` append-only version을 가리킨다. `OVERRIDE`는 완전한
  적립 정책 값을 가지며, `INHERIT_GLOBAL`은 정책 값을 복사하지 않고 미래 Order가 생성
  transaction 시점의 GLOBAL current를 선택하게 한다. head가 한 번도 없거나 current state가
  `INHERIT_GLOBAL`이면 같은 GLOBAL fallback을 사용한다. 전환 전 Order snapshot과 그 결과는
  변경하지 않는다. 최초 STORE head 생성과 head 부재 조회는 같은 store-scope lock으로
  직렬화하여 update가 commit된 뒤 시작한 Order가 이전 fallback을 선택하지 않게 한다.
  최초 전역 version 생성 경로는 후속 결정 전까지 pending이다.
- **Ordinary Accrual Policy Vocabulary Amendment (2026-08-01):** policy version의
  `accrualRateBps`는 `0..10_000`이다. `0`은 장애 fallback이 아니라 운영자가 명시적으로
  적립을 중지한 감사 가능 version이며 Order snapshot은 policy와 0 gross를 그대로 보존한다.
  gross는 `finalPayableKrw × accrualRateBps / 10_000`에 version이 선택한 `FLOOR` 또는
  `HALF_UP`을 정확히 한 번 적용해 계산한다. unit allocation의 별도 정수 remainder 규칙은
  이 gross 뒤에 적용한다.

  expiry rule은 `EXACT_DURATION_FROM_COMPLETION` 또는
  `SEOUL_CALENDAR_DAYS_FROM_COMPLETION`이고 `validityDays`는 `1..3650`이다. exact는
  `completedAt + validityDays × 24h`, 서울 달력일 rule은 완료 시각의 `Asia/Seoul`
  현지 날짜를 첫 유효일로 세어 `completedLocalDate + validityDays`의 00:00을 exclusive
  `expiresAt`으로 사용한다. issuer type은 기존 `PLATFORM|BRAND|STORE`, reference는 trim 뒤
  1..240자의 literal immutable 비용 주체다. issuer registry가 없으므로 존재를 추측 검증하거나
  scope·Order에서 자동 보정하지 않는다.
- **Initial Global Policy Bootstrap Amendment (2026-08-01):** 최초 GLOBAL policy는
  migration seed나 HTTP 운영자 API로 만들지 않는다. controlled deployment job이 기존 operator
  permission bootstrap과 같은 verified short-lived OIDC workload identity를 사용해 별도 offline
  command를 실행하고, closed vocabulary 안의 완전한 rate·rounding·issuer·expiry 값, 변경 사유,
  immutable evidence reference와 correlation ID를 전달한다. command는 최초 GLOBAL version,
  GLOBAL head와 AuditRecord를 하나의 transaction에 정확히 한 번 저장한다. 이미 GLOBAL head가
  있으면 성공 replay나 overwrite가 아니라 state conflict다. 정상 HTTP 애플리케이션은 Flyway 뒤
  GLOBAL head/version의 존재·scope·완전성을 startup gate로 검증하고 누락·불일치·DB 장애 시
  시작을 실패시킨다. offline bootstrap context와 명시적 test profile만 최초 생성 전 이 gate를
  우회할 수 있으며 운영 profile의 default policy나 자동 seed는 금지한다.
- **Forward-only Initial Activation Amendment (2026-08-01):** policy/snapshot migration 전에
  이미 존재한 Order에는 최초 GLOBAL version을 소급 적용하지 않는다. migration은 각 기존 Order에
  `LEGACY_NOT_APPLICABLE` 일반 적립 source를 명시적으로 기록하고 rate·issuer·expiry·unit 적립액을
  backfill하지 않는다. 이 Order의 기존 완료, 부분 환불, point recovery 결과는 그대로이며 이후에도
  일반 `ACCRUAL`이나 그 credit에 대한 `RECOVERY`를 새로 만들지 않는다. 새 애플리케이션에서 생성되는
  모든 Order는 같은 생성 transaction에 `SNAPSHOTTED` source와 완전한 policy/unit snapshot을 가져야
  한다. `LEGACY_NOT_APPLICABLE` marker가 없는 snapshot 누락은 legacy로 추측하지 않고 손상으로
  실패·재처리한다.
- **Ordinary Accrual Allocation and Recovery Timing Amendment (2026-08-01):** 계산된
  `grossAccrualAmountKrw`는 현금 결제 몫이 양수인 모든 OrderLine conceptual unit에
  `cashPayableKrw` 비례로 배분한다. 각 unit 몫은 먼저 정수 나눗셈으로 버리고, 남은 1원은
  `cashPayableKrw`가 큰 unit부터, 같으면 `lineSequence`, `unitPosition` 오름차순으로
  하나씩 준다. unit 합계는 gross와 정확히 같아야 하며 실결제액이 0이면 gross와 모든
  unit amount는 0이다.

  Payment의 immutable `refundSucceededAt`과 Ordering의 immutable `completedAt`을 비교한다.
  `refundSucceededAt <= completedAt`이면 해당 성공 Refund unit은
  `EXCLUDED_BEFORE_ACCRUAL`로 보존하고 완료 적립의 gross에서 제외한다. 같은 시각에는
  Refund가 우선한다. `refundSucceededAt > completedAt`인 unit만 실제 `RECOVERY`와
  부족액 `PointRecoveryPending` 대상이다. 아직 완료되지 않은 주문의 성공 Refund는
  Payment-owned durable eligibility work로 대기하며 Account/Lot/pending을 바꾸지 않는다.
  Order가 완료 없이 terminal이면 work는 `NOT_APPLICABLE`로 끝나고 recovery debt를 만들지
  않는다. 이 work와 결과 source는 replay·out-of-order delivery에도 동일 결과를 내야 한다.
- **Audited Manual Adjustment Amendment (2026-08-01):** 확인된 포인트 원장 불일치는
  활성 `PLATFORM_OPERATOR`의 명시적 권한, non-blank 사유와 증빙 아래 signed
  `ADJUSTMENT`로만 보정한다. 양수 보정은 operator가 입력한
  `PLATFORM|BRAND|STORE` issuer snapshot과 미래 만료일을 가진 새 PointLot을 만들며,
  음수 보정은 미예약 available PointLot만 선소멸 순서로 줄인다. 어느 보정도
  PointRecoveryPending을 상계하지 않고, Account/Lot/원장/Audit/멱등 응답은 하나의
  로컬 transaction에서 함께 저장한다.
- **Rationale:** 고객이 실제로 지불한 가치와 적립 비용을 일치시킨다.
- **Affected Contexts:** Loyalty, Ordering, Payment, Settlement
- **Affected Aggregates:** PointAccount, PointLot, PointTransaction, Order, Payment, AuditRecord
- **Required Tests:**
  - 쿠폰 사용 주문 적립액
  - 포인트 사용 주문 적립액
  - 0원 주문 적립 0
  - 주문 생성 뒤 정책 변경에도 snapshot 적립 결과가 변하지 않음
  - snapshot의 unit별 적립 배분과 성공 부분 환불의 회수 대상 tie-out
  - 완료 전·완료 후·동시 Refund 성공의 accrual 제외/RECOVERY/pending 경계
  - 부분 환불에 따른 적립 포인트 회수
  - 감사형 양수·음수 `ADJUSTMENT`의 issuer/만료, Lot/Account/Audit tie-out
- **ADR Required:** Yes — 포인트 Lot·적립·환불 복원 정책
- **Revisit Conditions:** 매장 또는 브랜드가 할인 전 금액 적립 정책을 요구할 때

## BR-11 포인트 사용 한도와 0원 주문

- **Status:** Accepted for MVP
- **Decision:** 포인트는 쿠폰 적용 후 남은 결제 예정액 전부까지 사용할 수 있다. 포인트로 전액 결제되어 최종 결제액이 0원이면 외부 PG를 호출하지 않고 `BENEFIT_ONLY` 결제 기록을 생성하여 주문을 결제 완료로 처리한다.
- **Amendment (2026-07-28):** 0원 주문은 주문 생성 Feature에 포함한다. 주문 생성 로컬 트랜잭션 안에서 임시 예약을 획득한 뒤 `BENEFIT_ONLY Payment(APPROVED)`를 생성하고 슬롯·재고·쿠폰·포인트 예약을 확정하며 Order를 `PAID`로 커밋한다. 이 주문에는 active 결제 전 lease가 남지 않고 외부 PG 호출도 발생하지 않는다.
- **Customer Cancellation Amendment (2026-07-31):** 매장 수락 전 고객 취소는
  Refund 없이 PAYMENT 보상 step을 `NOT_REQUIRED`로 확정한다. Provider를 호출하지
  않고 포인트·쿠폰·슬롯·재고와 알림 보상은 일반 `PAID` 취소와 동일하게 처리한다.
- **Rationale:** 포인트 전액 사용을 지원하면서도 0원 결제를 외부 PG에 전송하는 불필요한 의존을 제거한다.
- **Affected Contexts:** Ordering, Loyalty, Payment
- **Affected Aggregates:** Order, PointAccount, Payment
- **Required Tests:**
  - 포인트 전액 결제
  - 결제 예정액 초과 포인트 사용 거부
  - `BENEFIT_ONLY` 중복 처리 방지
  - 0원 주문 생성 시 Order·Payment·예약 확정의 원자성
  - 취소·환불 시 포인트 전액 복원
- **ADR Required:** Yes
- **Revisit Conditions:** PG 최소 승인 금액이나 포인트 사용 비율 제한이 비즈니스 정책으로 도입될 때

## BR-12 부분 환불 시 쿠폰·포인트 배분

- **Status:** Accepted for MVP
- **Decision:** 주문 확정 시 쿠폰 할인액, 사용 포인트, 현금 결제액을 주문 항목별로 배분해 스냅샷으로 저장한다. 배분은 각 항목의 할인 전 금액 비율을 기준으로 하고, 원 미만 버림 후 남는 차액은 금액이 큰 항목, 동일하면 주문 항목 순서가 빠른 항목부터 1원씩 배분한다. 품목 부분 환불 시 해당 항목에 저장된 현금 결제액을 환불하고, 사용 포인트를 복원하며, 쿠폰 할인액은 현금으로 환급하지 않는다.
- **Sequential Allocation Amendment (2026-07-28):** 대상 메뉴가 제한된 쿠폰은 대상 OrderLine의 할인 전 금액만 기준으로 대상 품목에 배분한다. 포인트는 쿠폰 적용 뒤 각 OrderLine에 남은 금액 비율로 모든 품목에 배분하고, 현금 결제액은 품목별 `gross - coupon - points` 잔액이다. 각 배분 단계에서 원 미만을 버린 뒤 남는 차액은 해당 단계 기준 금액이 큰 품목, 동일하면 주문 항목 순서가 빠른 품목부터 1원씩 배분한다. 이 amendment가 앞 문장의 공통 할인 전 금액 기준보다 우선한다.
- **Partial Refund Coupon Clarification (2026-08-01):** 품목 부분 환불은 원
  CouponIssuance를 복원하거나 새 보상 CouponIssuance를 발급하지 않는다. 쿠폰은
  `USED` 상태를 유지하고 Promotion 복원 작업도 시작하지 않는다. 환불 line의 coupon
  allocation은 환불·정산 tie-out과 감사에 사용하는 귀속 원장이지 복원 성공액이
  아니다. 따라서 선행 부분 환불의 coupon allocation을 이후 주문 전체 종료의 쿠폰
  복원 대상에서 차감하지 않는다. 주문 전체가 고객 취소 또는 매장 거절로 종료될 때만
  기존 종료 정책과 source-aware 제약으로 원 쿠폰을 최대 한 번 복원한다.
- **Expired Point Restoration Amendment (2026-08-01):** 부분 환불 포인트 복원은
  전용 `PARTIAL_REFUND × POINTS` policy head와 Refund 요청 transaction에 고정한
  immutable version을 사용한다. 초기 mode는 `COMPENSATE_WITH_NEW_ISSUANCE`, 유효일수는
  30일이다. 원 PointLot이 환불 성공 시각에 유효하면 원 lot으로 복원하고 이미
  만료됐으면 같은 가치와 original lot·issuer/cost lineage를 보존한 새 PointLot을 환불
  성공 시각부터 30일 유효하게 발급한다. 운영자는 이후 새 version으로 mode와 유효일수를
  변경할 수 있으며 변경 전 Refund에는 소급하지 않는다. 부분 환불은 PointReservation
  전체 상태를 변경하지 않고 allocation별 원장만 기록하며 후속 주문 전체 종료는 이미
  복원된 allocation을 제외한 잔여 포인트만 처리한다. `PARTIAL_REFUND × COUPON` policy
  head는 만들지 않는다.
- **Within-Line Quantity Allocation Amendment (2026-08-01):** 한 OrderLine의 일부
  수량을 여러 Refund로 나눌 때 conceptual unit position은 원 주문 안에서 `0`부터
  `quantity - 1`까지 고정한다. 각 unit gross는 immutable unit price다. line coupon
  allocation은 unit gross 비율로 나누고 원 미만을 버린 뒤 남는 1원은 앞 unit부터
  배분한다. points는 unit별 `gross - coupon` 잔액 비율로 나누며, 버림 뒤 remainder는
  그 잔액이 큰 unit, 같으면 앞 unit부터 배분한다. cash는 unit별
  `gross - coupon - points` 잔액이다. 따라서 각 unit과 line의 세 allocation이 모두
  tie-out한다. 부분 환불은 아직 성공 환불되지 않은 가장 앞 unit position부터 요청
  수량만큼 소비하고, omitted line list의 full refund는 모든 line의 남은 unit을
  line sequence 순서로 소비한다. 실패·불명 Refund는 unit을 소비하지 않으며 성공
  result transaction만 소비 원장을 확정한다.
- **Rationale:** 환불 시점에 정책을 다시 계산하지 않고 주문 당시 결과를 재현하기 위함이다.
- **Affected Contexts:** Ordering, Promotion, Loyalty, Payment, Settlement
- **Affected Aggregates:** Order, OrderLine, Payment, PointAccount, SettlementAdjustment
- **Required Tests:**
  - 여러 품목의 할인·포인트 배분 합계 일치
  - 대상·비대상 혼합 주문에서 비대상 품목의 쿠폰 배분 0
  - 쿠폰 적용 후 품목별 잔액 기준 포인트 배분
  - 나머지 1원 배분의 결정성
  - 같은 품목 반복 환불 방지
  - 환불 후 승인액·포인트·정산액 tie-out
  - 부분 환불 성공 시 CouponIssuance 상태 불변과 Promotion 복원 호출 부재
  - 선행 부분 환불 후 주문 전체 종료 시 원 쿠폰의 단일 복원
  - 부분 환불 시 PointLot 만료 -1ns/at/+1ns의 원 lot 복원·30일 보상 lot 분기
  - 정책 변경 전후 Refund의 version snapshot 재현과 중복 보상 lot 부재
  - 부분 환불 뒤 PointReservation USED 유지와 후속 전체 종료의 잔여 포인트만 복원
  - 같은 line의 여러 수량 분할 순서에서 unit별 coupon→points→cash tie-out과 앞 unit
    remainder 소비 결정성
- **ADR Required:** Yes — 부분 환불 배분 정책
- **Revisit Conditions:** 쿠폰별 환급 가능 정책 또는 묶음 상품 환불 정책이 도입될 때

## BR-13 환불 주문의 적립 포인트 회수

- **Status:** Accepted for MVP
- **Decision:** 환불 금액에 대응하는 적립 포인트를 먼저 미사용 PointLot에서 실제
  `RECOVERY` PointTransaction으로 회수한다. 이미 사용되어 전부 회수할 수 없으면
  포인트 잔액을 음수로 만들지 않고 부족액을 Loyalty의
  `PointRecoveryPending(PENDING)` 원장 항목으로 기록한다. 이후 발생하는 포인트
  적립은 부족액 상계에 우선 사용하며 상계분도 `RECOVERY` PointTransaction으로
  기록한다. 정산 금액 보정이 필요하면 별도 SettlementAdjustment가 원천 refund
  reference를 사용하며, 포인트 회수 대기 잔액 자체를 소유하지 않는다.
- **Gross Accrual Ledger Clarification (2026-08-02):** 이후 적립으로 pending을 상계할 때
  Order snapshot의 gross 전액을 `ACCRUAL` PointTransaction과 새 PointLot으로 먼저
  기록하고, 같은 Loyalty transaction에서 oldest-first pending에 적용한 금액을 해당 새
  Lot의 `RECOVERY` PointTransaction으로 기록한다. 따라서 gross 적립과 상계 debit은 모두
  append-only 원장에 남고, transaction 종료 시 PointLot과 PointAccount의 available에는
  상계 후 net 금액만 남는다. net 금액만 `ACCRUAL`로 기록하거나 상계분을 원장 없이
  pending에서 직접 차감하지 않는다.
- **Rationale:** 환불을 막지 않으면서 포인트 비용의 정합성을 유지하고 음수 잔액을 피한다.
- **Affected Contexts:** Loyalty, Payment, Settlement, Operations
- **Affected Aggregates:** PointAccount, PointLot, PointTransaction, PointRecoveryPending
- **Required Tests:**
  - 미사용 적립 포인트 전액 회수
  - 일부 사용 후 부족액 기록
  - 이후 적립 시 우선 상계
  - 중복 환불 이벤트로 이중 회수 방지
  - 실제 `RECOVERY` debit과 `PointRecoveryPending` 부족액을 혼동하지 않는 계정 tie-out
- **ADR Required:** Yes
- **Revisit Conditions:** 포인트 부채를 사용자에게 청구하거나 환불을 제한하는 별도 정책이 필요할 때

---

# D. 정산·이의제기 정책

## BR-16 정산 기준일

- **Status:** Accepted for MVP
- **Decision:** 주문이 `COMPLETED`된 날짜를 정산 귀속일로 사용한다. 결제 승인만 되고 픽업 완료되지 않은 주문은 정산 대상에 포함하지 않는다.
- **Pre-acceptance Customer Cancellation Amendment (2026-07-31):** 매장 수락 전
  고객 취소와 그 성공 Refund에는 SettlementItem과 SettlementAdjustment를 만들지
  않는다. Settlement consumer는 Order `CUSTOMER_REQUEST` 취소, 고객 취소 Refund
  `SUCCEEDED`, source와 SettlementItem 부재가 모두 일치할 때
  `NOT_APPLICABLE`로 멱등 완료하고 source당 하나의 append-only
  `SETTLEMENT_REFUND_EXCLUDED` AuditRecord를 남긴다. Audit 저장 전에는 event
  publication을 완료하지 않는다. 운영 조회는 Order·Refund·Audit 세 원천을 조합해
  “주문 미완료로 정산 제외”를 표시하며 0원 Adjustment나 별도 제외 원장을 만들지
  않는다.
- **Pre-completion Refund Amendment (2026-08-02):** 완료 전 품목 환불은 미수락 종료와
  다르므로 `NOT_APPLICABLE`로 종결하지 않는다. `PaymentRefundedV1`의 immutable refund
  effect를 source-aware pending input으로 보존하고, Order가 나중에 `COMPLETED`되면 그
  완료일의 SettlementItem에 정확히 한 번 반영한다. Order가 완료 없이 terminal이 되면
  해당 pending input은 0원 Adjustment로 바꾸지 않고 terminal source와 일치하는 명시적
  exclusion/reconciliation 경로로 종결한다. consumer 구현과 저장 모델은 Plan 20 소유다.
- **Rationale:** 매장이 실제로 상품을 인도한 거래를 정산 대상으로 삼는다.
- **Affected Contexts:** Ordering, Fulfillment, Settlement, Analytics
- **Affected Aggregates:** Order, SettlementItem, SettlementBatch
- **Required Tests:**
  - 결제일과 완료일이 다른 주문의 귀속
  - 완료되지 않은 주문 제외
  - 자정 경계의 완료 주문
  - 중복 완료 이벤트의 정산 항목 중복 방지
  - 완료 전 부분 환불과 완료 event의 순서가 바뀌어도 동일한 정산 결과
- **ADR Required:** Yes — 정산 기준과 조정 원장
- **Revisit Conditions:** 실제 PG 매입일 또는 영업일 기준 정산이 필요할 때

## BR-17 정산 주기

- **Status:** Accepted for MVP
- **Decision:** 매장별 내부 정산 명세를 일별로 계산한다. 배치는 전일 `00:00:00`부터 `23:59:59.999...`까지 `Asia/Seoul` 기준 완료된 주문을 대상으로 한다. 실제 계좌 지급은 MVP 범위에서 제외한다.
- **Rationale:** 재실행과 장애 복구가 쉬운 작은 배치 단위로 정산 정확성을 검증한다.
- **Affected Contexts:** Settlement, Ordering, Analytics
- **Affected Aggregates:** SettlementBatch, SettlementItem
- **Required Tests:**
  - 동일 매장·일자 배치 재실행
  - 배치 중단 후 재시작
  - 자정 경계 포함·제외
  - 여러 매장 병렬 처리
- **ADR Required:** Yes
- **Revisit Conditions:** 실제 지급 주기나 대량 데이터 처리시간이 별도 주기를 요구할 때

## BR-18 정산 수수료 계산 기준

- **Status:** Accepted for MVP
- **Decision:** 플랫폼 수수료는 쿠폰과 포인트를 반영한 최종 실결제액을 기준으로 계산한다. 수수료율은 매장 계약 스냅샷을 주문 또는 SettlementItem에 저장하며, 정산 시 현재 계약 값을 다시 조회하지 않는다.
- **Order Snapshot Amendment (2026-08-02):** canonical 저장 위치는 Order당 정확히 하나인
  immutable `OrderSettlementInputSnapshot`이다. `feeBaseKrw=Order.payableKrw`이고 Payment
  승인 금액도 이 값과 같아야 한다. Merchant의 applicable `StoreSettlementTerms` version ID,
  source와 fee rate를 주문 생성 transaction에서 고정하며, 적용 version이 없거나 둘 이상이면
  default rate 없이 `SETTLEMENT_INPUT_UNAVAILABLE`로 전체 생성 transaction을 rollback한다.
- **Rationale:** 거래 당시 계약과 실제 결제액을 기준으로 재현 가능한 정산을 만든다.
- **Affected Contexts:** Settlement, Payment, Ordering, Merchant
- **Affected Aggregates:** SettlementItem, Order, Payment
- **Required Tests:**
  - 계약 변경 전후 주문의 다른 수수료율
  - 부분 환불에 따른 수수료 조정
  - 원 단위 반올림
  - 정산 재실행 결과 동일성
- **ADR Required:** Yes
- **Revisit Conditions:** 최소 수수료, 정액 수수료 또는 PG 비용 분리 정책이 도입될 때

## BR-19 쿠폰 비용 부담 주체

- **Status:** Accepted for MVP
- **Decision:** 쿠폰 비용 부담 주체는 캠페인 생성 시 `PLATFORM`, `STORE`, `SHARED` 중 하나로 명시한다. `SHARED`인 경우 플랫폼과 매장의 부담 비율 합계는 100%여야 하며 주문 확정 시 부담액을 스냅샷으로 저장한다.
- **Compensation Coupon Amendment (2026-07-31):** 만료된 원 쿠폰을 대체하는 보상
  CouponIssuance는 원 issuance의 Campaign 비용 부담 주체와 platform/store basis
  point 비율을 immutable snapshot으로 그대로 승계한다. 원 Campaign 종료·변경이나
  현재 계약을 다시 조회하지 않는다. 보상 발급만으로 정산 원장을 만들지 않고 미래
  완료 주문에서 실제 사용될 때 그 주문의 SettlementItem에 비용을 반영한다. 미사용·
  만료에는 비용이 없고, snapshot 누락은 플랫폼 부담이나 현재 값으로 fallback하지
  않고 명시적 복원 실패로 처리한다.
- **Reservation Leg Amendment (2026-08-02):** Campaign의 burden source와 version을
  CouponReservation에 복사하고 final discount를 platform/store 정수 leg로 확정한다.
  `PLATFORM=(10000,0)`, `STORE=(0,10000)`, `SHARED`는 두 share의 합이 10000이어야 하며
  store leg는 BR-02의 floor, platform leg는 나머지다. active legacy Campaign 또는 이미
  존재하는 reservation의 verified burden source가 없으면 migration을 중단한다. 보상
  CouponIssuance에 승계된 burden snapshot이 없으면 예약도 같은 fail-closed 규칙을 따른다.
- **Rationale:** 할인액이 누구의 정산액에서 차감되는지 명확히 하고 과거 캠페인의 재현성을 확보한다.
- **Affected Contexts:** Promotion, Ordering, Settlement
- **Affected Aggregates:** Campaign, Order, SettlementItem
- **Required Tests:**
  - 부담 주체별 정산 금액
  - 분담 비율 합계 검증
  - 캠페인 변경 후 과거 주문 재현
  - 부분 환불 시 부담액 조정
- **ADR Required:** Yes
- **Revisit Conditions:** 브랜드 단위 또는 외부 제휴사 부담 주체가 추가될 때

## BR-20 포인트 비용 부담 주체

- **Status:** Accepted for MVP
- **Decision:** 포인트 비용은 포인트 프로그램의 발급 주체가 부담한다. 발급 주체는 `PLATFORM`, `BRAND`, `STORE` 중 하나이며, 사용 시 PointLot별 발급 주체를 기준으로 비용을 배분한다.
- **Manual Adjustment Issuer Amendment (2026-08-01):** 양수 수동
  `ADJUSTMENT`는 호출자가 issuer type과 immutable reference를 반드시 입력해 새
  PointLot에 snapshot으로 저장한다. actor, customer 또는 기존 Lot에서 issuer를
  추론하거나 PLATFORM으로 대체하지 않는다. 이 Lot이 이후 사용될 때만 snapshot의
  발급 주체가 비용 배분 입력이 되며, adjustment command 자체는 SettlementItem이나
  SettlementAdjustment를 만들지 않는다.
- **Order Allocation Amendment (2026-08-02):** 주문 생성에서 PointReservation의 immutable
  allocation 합은 사용 포인트와 정확히 같아야 한다. 그중 `issuerType=STORE`이고
  `issuerReference`가 해당 Order의 `storeId` canonical UUID와 정확히 일치하는 금액만
  `pointCostKrw`에 포함한다. `PLATFORM`, `BRAND`는 해당 매장 비용에서 제외하고 다른 매장
  reference·누락 issuer·allocation 불일치는 cross-store 정책이나 0원으로 추정하지 않는다.
- **Rationale:** 서로 다른 발급 주체의 포인트를 섞어 사용할 때 비용 책임을 추적하기 위함이다.
- **Affected Contexts:** Loyalty, Settlement, Merchant, Operations
- **Affected Aggregates:** LoyaltyProgram, PointAccount, PointLot, PointTransaction,
  PointAdjustmentCommandIdempotency, SettlementItem, AuditRecord
- **Required Tests:**
  - 서로 다른 발급 주체 PointLot 혼합 사용
  - 선소멸 우선 사용과 비용 배분
  - 환불 시 원 발급 주체 복원
  - 원장 합계와 정산 비용 tie-out
  - 수동 양수 adjustment의 입력 issuer snapshot 비용 귀속과 default issuer 부재
- **ADR Required:** Yes
- **Revisit Conditions:** 매장 간 상호 사용이나 브랜드 통합 포인트가 도입될 때

## BR-21 확정 정산 후 음수 조정

- **Status:** Accepted for MVP
- **Decision:** 확정 정산 이후 환불·이의제기 조정으로 매장 정산액이 음수가 되면 확정 배치를 수정하지 않고 음수 `SettlementAdjustment`를 생성한다. 음수 잔액은 다음 정산 배치로 이월하여 상계한다.
- **Rationale:** 확정 정산의 감사 가능성을 유지하면서 사후 환불을 반영한다.
- **Affected Contexts:** Settlement, Payment, Dispute
- **Affected Aggregates:** SettlementAdjustment, SettlementBatch, SettlementItem
- **Required Tests:**
  - 확정 후 전액·부분 환불
  - 다음 배치 이월 상계
  - 조정 중복 생성 방지
  - 이월 후에도 음수인 경우 연속 이월
- **ADR Required:** Yes — 정산 조정 원장
- **Revisit Conditions:** 실제 청구, 보증금 또는 별도 채권 관리가 필요할 때

## BR-22 정산 이의제기 가능 기간

- **Status:** Accepted for MVP
- **Decision:** 점주는 SettlementItem이 포함된 정산 배치가 확정된 `Asia/Seoul` 날짜를 D라고 할 때 `[D+1 00:00, D+15 00:00)` 범위에서 이의제기를 제출할 수 있다. 즉 다음 날부터 14개 달력 날짜를 포함하고 D+15 시작 시각부터는 거부한다.
- **Clarification (2026-07-28):** “다음 날부터 14일 이내”의 inclusive/exclusive 경계를 달력 날짜와 half-open interval로 명확히 했다.
- **Rationale:** 검토 가능한 기간을 제공하면서 무기한 분쟁 가능성을 제한한다.
- **Affected Contexts:** Settlement, Dispute, Notification
- **Affected Aggregates:** SettlementBatch, SettlementItem, SettlementDispute
- **Required Tests:**
  - 14일 이내 접수
  - 14일 경계와 이후 거부
  - 시간대 경계
  - 미확정 정산 항목에 대한 이의제기 거부
- **ADR Required:** No
- **Revisit Conditions:** 실제 계약 또는 법적 보존·이의 기간이 확정될 때

## BR-23 이의제기 중 정산 보류 범위

- **Status:** Accepted for MVP
- **Decision:** 이의제기가 접수되어도 정산 배치 전체를 보류하지 않는다. 분쟁 대상 조정 예상액만 별도의 `HELD` 금액으로 관리하고, 나머지 정산액은 정상 확정 상태를 유지한다.
- **Rationale:** 한 항목의 분쟁이 매장 전체 정산을 차단하지 않게 한다.
- **Affected Contexts:** Settlement, Dispute
- **Affected Aggregates:** SettlementDispute, SettlementAdjustment, SettlementBatch
- **Required Tests:**
  - 일부 항목 이의제기와 비분쟁 금액 분리
  - 승인·거절 시 held 금액 해제
  - 같은 항목 중복 hold 방지
  - 여러 이의제기의 합계 검증
- **ADR Required:** Yes
- **Revisit Conditions:** 실제 지급 시스템에서 hold 처리 방식이 달라질 때

## BR-24 재이의제기

- **Status:** Accepted for MVP
- **Decision:** 하나의 SettlementItem에는 진행 중인 이의제기를 하나만 허용한다. 종결된 이의제기는 새로운 증빙이 있을 때 1회에 한해 재이의제기를 허용하며 이전 이의제기 ID를 참조해야 한다.
- **Rationale:** 중복 업무를 제한하면서 새로운 증빙에 대한 예외 경로를 제공한다.
- **Affected Contexts:** Dispute, Settlement, Operations
- **Affected Aggregates:** SettlementDispute, SettlementItem
- **Required Tests:**
  - 진행 중 중복 접수 거부
  - 새 증빙 없는 재접수 거부
  - 1회 재이의제기 성공
  - 두 번째 재이의제기 거부
- **ADR Required:** No
- **Revisit Conditions:** 외부 중재나 다단계 심사 절차가 도입될 때

---

# E. 멱등성·외부 연동·운영 정책

## BR-25 Idempotency-Key 범위와 동작

- **Status:** Accepted for MVP
- **Decision:** 멱등성 키의 유효 범위는 `actorId + API operation + Idempotency-Key`다. 서버는 정규화한 요청 payload hash와 처리 상태·응답을 저장한다. 동일 범위의 같은 키와 같은 payload는 기존 결과를 반환하고, 같은 키에 다른 payload가 들어오면 `409 Conflict`를 반환한다.
- **Order Creation Amendment (2026-07-28):** 주문 생성의 같은 key·같은 payload 재요청은 저장된 최초 HTTP status와 body를 그대로 반환한다. 아직 `PROCESSING`이면 새 실행이나 202 성공 표현 없이 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`와 `Retry-After`를 반환한다. 확정된 실패도 최초 4xx/503을 저장·재생하며 다시 실행하려면 새 key를 사용한다.
- **Payment Reconciliation Amendment (2026-07-29):** 결제 승인 결과가
  `UNKNOWN`이면 새 승인을 보내지 않고 Provider 상태를 10초, 30초, 2분, 5분,
  15분 시점에 최대 다섯 번 조회한다. 계속 불명이면 `MANUAL_REVIEW`와 단일
  ReprocessingCase를 남기고 자동 조회를 중단한다. 같은 key·payload 재요청은
  새 부수효과 없이 Payment의 현재 202/200/422 결과를 반환한다.
- **Customer Cancellation Amendment (2026-07-31):** 고객 취소 명령은 `PROCESSING`
  사전등록 없이 명령 트랜잭션 하나에서 Order row lock, 멱등 레코드 조회, 취소 실행과
  최초 응답 저장을 함께 커밋한다. 취소 트랜잭션에 외부 호출이 없어 결과 불명 구간이
  없으므로 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 사용하지 않고, 동시 같은 key 요청은
  Order row lock으로 직렬화되어 나중 요청이 저장된 최초 응답을 재생한다. canonical
  payload는 `orderId`, `reasonCode`, 정규화한 `detail`이며 `orderId`를 포함하므로 같은
  key를 다른 주문에 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`다. 롤백된 요청은 레코드를
  남기지 않아 확정 실패를 재생하지 않으며, 중복 취소는 Order 상태 guard가 막는다.
- **Store Command Scope Amendment (2026-07-31):** 매장 주문 상태 전이 명령의 canonical
  payload는 `orderId`, `targetState`, 정규화한 `reason` 세 값이다. 기존 구현은
  `targetState`와 `reason`만 해싱하고 멱등 레코드의 `order_id`를 비교하지 않아, 같은
  매장 구성원이 같은 key를 다른 주문에 재사용하면 첫 주문의 응답이 재생되고 두 번째
  주문은 전이되지 않았다. 이는 이 정책이 정한 "키 재사용으로 다른 거래가 실행되는 것을
  막는다"를 충족하지 못하는 구현 결함이므로 개정한다. 레코드를 찾은 뒤 `order_id`가
  다르면 payload hash 불일치와 동일하게 `409 IDEMPOTENCY_KEY_REUSED`로 거부한다.
  canonical payload 구성이 바뀌면 저장된 구 레코드가 새 hash와 일치할 수 없으므로
  `operation` 값을 함께 승격한다. 이 개정의 `operation`은
  `STORE_ORDER_TRANSITION_V2`이며 구 `STORE_ORDER_TRANSITION` 레코드는 더 이상
  조회되지 않고 BR-26 보존 기간 뒤 정리된다. 완료 ExecPlan `store-order-lifecycle`의
  멱등성 서술보다 이 amendment가 우선하며 해당 완료 문서는 수정하지 않는다.
- **Replay Indicator Amendment (2026-07-31):** business response에는
  `replayed` 필드를 두지 않는다. 고객 취소, 매장 전이와 주문 생성 등 terminal
  command는 같은 key·payload에 저장된 최초 status/body를 그대로 반환하고 replay
  여부는 IdempotencyRecord, metric과 structured log에서만 관측한다. 외부 결과가
  non-terminal `UNKNOWN`인 Payment 승인·환불은 새 Provider 호출 없이 현재 durable
  representation을 반환하는 기존 예외를 유지하되 replay 표시를 추가하지 않는다.
- **Risk-based Model Selection Amendment (2026-08-01):** 사전등록 모델은 기존
  직렬화 Aggregate root가 없거나, 최초 terminal 응답 저장 전에 외부 Provider 결과가
  불명확해지는 명령에 사용한다. 기존 lockable root가 경쟁을 직렬화하고 모든 local
  write와 최초 응답을 한 transaction에서 commit하며 외부 호출이 그 안에 없으면 새
  Aggregate를 만들어도 명령 트랜잭션 모델을 사용한다. 고객 취소 C1과 감사형 point
  adjustment는 이 조건을 충족한다. 상세 기준과 기존 명령 분류는 ADR-064를 따른다.
- **Rationale:** 사용자의 재시도는 허용하되 키 재사용으로 다른 거래가 실행되는 것을 막는다. 명령이 특정 Aggregate를 대상으로 하면 그 식별자를 canonical payload에 포함해 교차 대상 키 재사용을 거부한다.
- **Affected Contexts:** Ordering, Payment, Loyalty, Settlement, Operations
- **Affected Aggregates:** IdempotencyRecord, Order, Payment, PointAccount, SettlementAdjustment
- **Required Tests:**
  - 같은 키·같은 payload 재요청
  - 같은 키·다른 payload 409
  - 처리 중 요청의 동시 재시도
  - 주문 생성 최초 201·4xx·503 response 재생
  - 주문 생성 PROCESSING의 409와 Retry-After
  - 실패·UNKNOWN 상태 재요청
  - 고객 취소 같은 key·같은 payload의 200·202 response 재생과 부수효과 부재
  - 고객 취소 같은 key·다른 주문의 409와 첫 주문 응답 미재생
  - 고객 취소 동시 같은 key 요청의 단일 실행
  - 고객 취소 롤백 후 같은 key 재시도의 재실행
  - 매장 전이 같은 key·다른 주문의 409와 첫 주문 응답 미재생
  - 매장 전이 같은 key·같은 주문·같은 payload의 최초 응답 재생
  - 감사형 포인트 조정 같은 key·같은 payload의 최초 201 재생과 다른 account/payload 409
  - `operation` 승격 후 구 `STORE_ORDER_TRANSITION` 레코드 미조회
- **ADR Required:** Yes — 결제 멱등성과 reconciliation
- **Revisit Conditions:** 다중 채널 또는 외부 파트너가 자체 멱등성 범위를 요구할 때

## BR-26 멱등성 데이터 보존 기간

- **Status:** Accepted for MVP
- **Decision:** 멱등성 레코드는 거래가 terminal 상태가 된 시점부터 90일 동안 보존한다. 진행 중이거나 `UNKNOWN` 상태인 거래의 레코드는 정리하지 않는다. 정리 작업은 chunk 단위로 실행하고 재실행 가능해야 한다.
- **Customer Cancellation Amendment (2026-07-31):** 고객 취소 멱등 레코드는 저장
  시점에 이미 terminal이므로 보존 기준 시각은 `created_at`이다.
  `retention_expires_at = created_at + 90일`을 컬럼으로 materialize하고
  `(retention_expires_at, id)` 순서의 chunk 정리 worker가 삭제한다. 정리 worker는 이번
  범위에 포함한다.
- **Ordering Retention Worker Amendment (2026-07-31):** 하나의 Ordering worker가
  고객 취소와 매장 전이 멱등 table을 기본 1시간마다 각각 최대 100건의 독립
  transaction으로 정리한다. store 기존 row도 `created_at + 90일`로
  `retention_expires_at`을 backfill하고 같은 keyset index를 사용한다. 구
  `STORE_ORDER_TRANSITION` operation은 V2에서 조회되지 않아도 row별 90일 전에는
  삭제하지 않는다.
- **Loyalty Point Adjustment Amendment (2026-08-01):** 감사형 포인트 조정은
  `loyalty_point_adjustment_command_idempotency`에 terminal `201` response와
  `retention_expires_at = created_at + 90일`을 함께 저장한다. Loyalty-owned worker가
  기본 1시간마다 최대 100개 due row를 `(retention_expires_at, id)` keyset 순서의 독립
  transaction에서 정리한다. 실패는 0건 성공으로 기록하지 않고 다음 tick에 재시도하며,
  Ordering worker가 Loyalty table을 함께 정리하지 않는다.
- **Rationale:** 14일 이의제기와 일반적인 환불·운영 조사 기간보다 충분히 길게 재시도 결과를 보존한다.
- **Affected Contexts:** Ordering, Payment, Loyalty, Operations
- **Affected Aggregates:** IdempotencyRecord, Payment, PointAdjustmentCommandIdempotency
- **Required Tests:**
  - terminal 상태 90일 이전 보존
  - 90일 이후 정리
  - 진행 중·UNKNOWN 제외
  - 정리 배치 중단·재실행
  - 고객 취소 멱등 레코드의 `retention_expires_at` 경계 전후 정리
  - store 기존 row의 createdAt+90일 backfill과 table별 cleanup 실패 격리
  - Loyalty adjustment terminal 201 row의 90일 경계, chunk 100과 cleanup 중단·재실행
- **ADR Required:** Yes
- **Revisit Conditions:** 실제 환불·분쟁 보존 기간, 저장 비용 또는 개인정보 정책이 확정될 때

## BR-27 알림 재시도와 수동 복구

- **Status:** Accepted for MVP
- **Decision:** 알림 발송은 최초 시도 후 실패 시 1분, 5분, 30분 간격으로 최대 3회 추가 재시도한다. 총 4회 실패하면 `MANUAL_REVIEW` 상태로 전환하고 운영자가 동일 delivery idempotency key로 안전하게 재처리할 수 있게 한다. 사용자 알림 실패는 주문 상태를 롤백하지 않는다.
- **Rationale:** 일시적 Provider 장애를 자동 복구하되 무한 재시도를 방지한다.
- **Affected Contexts:** Notification, Ordering, Operations
- **Affected Aggregates:** NotificationDelivery, ReprocessingCase
- **Required Tests:**
  - 각 retry schedule
  - Provider timeout 후 성공
  - ACK 유실 후 중복 발송 방지
  - 4회 실패 후 수동 재처리
  - 주문 상태와 알림 상태의 독립성
- **ADR Required:** Yes — 알림 재시도와 수동 복구
- **Revisit Conditions:** 실제 Provider의 rate limit, 비용, SLA가 확정될 때

## BR-28 사용자 위치정보 보존

- **Status:** Accepted for MVP
- **Decision:** 가까운 매장 검색에 전달된 정밀 위·경도는 요청 처리 중에만 사용하고 DB에 영구 저장하지 않는다. 애플리케이션 로그와 추적 데이터에도 원본 좌표를 남기지 않는다. 분석이 필요하면 선택된 storeId와 반경 구간 같은 비식별·비정밀 정보만 기록한다.
- **Rationale:** 검색 기능에 불필요한 정밀 위치 보존을 피하고 개인정보 노출 위험을 줄인다.
- **Affected Contexts:** Discovery, Analytics, Operations
- **Affected Aggregates:** 없음 — Discovery Query Model
- **Required Tests:**
  - 로그에 좌표가 출력되지 않는지 검증
  - 검색 정확성 경계 테스트
  - 권한 없이 위치 데이터 조회가 불가능함을 검증
- **ADR Required:** Yes — PostGIS와 위치정보 보존
- **Revisit Conditions:** 위치 기반 개인화에 대한 명시적 동의와 보존 정책이 도입될 때

## BR-29 결제수단 저장 정보

- **Status:** Accepted for MVP
- **Decision:** BeanFlow는 원본 카드번호, CVC, 전체 유효기간을 저장하지 않는다. PG가 발급한 payment method token reference, provider, 사용자 식별자, 표시용 별칭, 카드 브랜드, 마지막 4자리만 저장한다.
- **Rationale:** 민감 결제정보의 저장 책임을 피하고 PG tokenization 경계를 명확히 한다.
- **Affected Contexts:** Payment, Identity
- **Affected Aggregates:** PaymentMethod
- **Required Tests:**
  - 다른 사용자의 결제수단 사용 거부
  - 민감 필드가 Entity·로그·API에 존재하지 않는지 검증
  - 폐기된 token 사용 거부
  - Provider token 중복 등록 정책
- **ADR Required:** Yes — 결제수단 tokenization과 저장 금지 데이터
- **Revisit Conditions:** 실제 PG sandbox 계약과 인증 범위가 확정될 때

## BR-30 감사 로그 대상

- **Status:** Accepted for MVP
- **Decision:** 금액, 포인트, 재고, 픽업 슬롯, 주문 terminal 상태, 정산, 이의제기 판정, 권한 변경과 수동 재처리는 감사 로그를 남긴다. 감사 로그에는 actorId, actorType, action, targetType, targetId, occurredAt, reason, before summary, after summary, correlationId를 포함한다. 감사 로그는 일반 비즈니스 Entity와 분리하고 애플리케이션 API로 수정·삭제하지 않는다.
- **Order Lease Amendment (2026-07-28):** 주문 생성·BENEFIT_ONLY 승인·예약·확정·만료·해제는 변경된 Aggregate target마다 별도 AuditRecord를 남기고 같은 correlationId와 source reference로 묶는다. 고객 주문 생성은 Customer actor와 표준 reason code, 시간에 의한 만료는 SYSTEM actor와 `LEASE_DEADLINE_REACHED`를 사용한다. 자유 입력 reason은 수동·운영자 명령에서만 필수다.
- **Retention Amendment (2026-07-28):** AuditRecord는 `occurredAt`을 `Asia/Seoul`로 변환한 같은 현지 시각의 5주년까지 보존하고 그 시각부터 retention worker의 삭제 대상이 된다. 윤년은 달력 `plusYears(5)` 규칙을 따른다. 애플리케이션 API 삭제는 계속 금지하고 내부 worker만 chunk 단위로 재실행 가능하게 삭제한다.
- **Customer Cancellation Granularity Amendment (2026-07-31):** 고객 취소와 후속
  보상은 상태가 바뀌거나 중요한 durable work가 생성된 business target마다 별도
  AuditRecord를 남기고 공통 correlation과 cancellation source로 묶는다. Tx C0는
  Order·실제 네 예약 해제·접수 Delivery, Tx C1은
  Order·CompensationCase·Payment recovery snapshot·필요한 Refund·접수 Delivery를
  기록한다. 후속 owner는 실제 owner 상태 변경 transaction에서 target Audit를
  commit한다. event publication과 IdempotencyRecord는 자체 내구 원장을 사용하고,
  자동 claim/retry attempt마다 Audit를 만들지 않는다. 자유 입력 cancellation
  detail은 감사에 복제하지 않는다.
- **Audited Point Adjustment Amendment (2026-08-01):** 감사형 포인트 조정은
  `POINT_ADJUSTMENT_APPLIED` AuditRecord를 PointAccount target에 append한다. active
  `PLATFORM_OPERATOR`의 non-blank reason과 evidence reference, signed requested effect,
  before/after Account summary, 생성·차감 Lot ID, 양수 issuer/expiry snapshot만
  whitelist summary에 남기고 raw Idempotency-Key와 불필요한 개인정보는 남기지 않는다.
  Audit source는 adjustment command source와 하나이며, Audit 저장 실패는 Lot/Account/
  원장/멱등 응답 성공으로 대체하지 않고 같은 local transaction을 rollback한다.
- **Rationale:** 금전성·운영성 변경의 책임과 재현 가능성을 확보한다.
- **Affected Contexts:** 전체 거래 Context, Operations
- **Affected Aggregates:** AuditRecord
- **Required Tests:**
  - 필수 사유 없는 수동 변경 거부
  - 정상 변경과 감사 로그 원자적 기록
  - 주문 생성·만료의 target별 record와 correlation
  - 조회가 materialize한 만료의 SYSTEM actor/reason
  - 감사 로그 수정·삭제 API 부재
  - 서울 달력 5주년 직전·경계·이후 cleanup
  - 2월 29일 occurredAt의 5주년 경계
  - cleanup 중단·재실행과 due 이전 record 보존
  - 민감 정보 마스킹
  - 감사형 포인트 조정의 target action/source unique, whitelist summary와 원자적 rollback
- **ADR Required:** Yes
- **Revisit Conditions:** 별도 감사 저장소, 계약·규제 보존 기간 또는 legal hold 요구가 생길 때

---

# F. 분석·재집계 정책

## BR-31 환불의 매출 지표 반영 기준

- **Status:** Accepted for MVP
- **Decision:** 운영 대시보드의 환불액은 환불 발생일 기준으로 집계한다. 주문 수익성 분석에서는 원 주문 완료일 기준으로 환불을 재귀속한 보정 지표를 별도로 제공한다. 두 지표는 이름과 정의를 명확히 구분한다.
- **Rationale:** 당일 현금 흐름과 원 주문의 최종 수익성을 동시에 설명하기 위함이다.
- **Affected Contexts:** Analytics, Payment, Settlement
- **Affected Aggregates:** Analytics Read Model
- **Required Tests:**
  - 과거 주문의 당일 환불
  - 발생일 지표와 원 주문일 보정 지표 차이
  - 부분 환불 누적
  - 정산 조정과 분석 지표 일치
- **ADR Required:** Yes — 매출 지표 정의
- **Revisit Conditions:** 회계 기준 또는 점주 화면 요구가 달라질 때

## BR-32 늦게 도착한 이벤트와 재집계

- **Status:** Accepted for MVP
- **Decision:** Analytics는 이벤트 발생일 기준 7일의 수정 가능 기간을 둔다. 7일 이내 늦게 도착하거나 재처리된 이벤트는 해당 일자의 Read Model을 멱등하게 갱신하고 야간 재집계 대상에 포함한다. 7일을 초과한 이벤트는 자동으로 과거 지표를 수정하지 않고 `BACKFILL_REQUIRED` 운영 건을 생성하여 승인 후 재집계한다.
- **Rationale:** 일반적인 지연 이벤트는 자동 보정하면서 오래된 데이터의 대규모 변경을 통제한다.
- **Affected Contexts:** Analytics, Operations, Ordering, Payment, Settlement
- **Affected Aggregates:** Analytics Read Model, ReprocessingCase
- **Required Tests:**
  - 7일 이내 late event 반영
  - 중복 이벤트 멱등 처리
  - 7일 초과 backfill case 생성
  - 재집계 중단·재실행 결과 동일성
- **ADR Required:** Yes — 매출 Read Model 갱신과 late event
- **Revisit Conditions:** 실제 이벤트 지연 분포와 재집계 비용을 측정한 뒤 window 조정

---

# 정책 간 의존성과 우선 적용 순서

1. `BR-01`, `BR-02`를 모든 시간·금액 Value Object의 기준으로 사용한다.
2. 주문 생성은 `BR-03`, `BR-04`, `BR-05`, `BR-08`, `BR-09`, `BR-11`을 함께 적용한다.
3. 결제 후 매장 처리에는 `BR-06`, `BR-07`, `BR-14`를 적용한다.
4. 부분 환불에는 `BR-12`, `BR-13`, `BR-15`, `BR-18`, `BR-21`을 함께 적용한다.
5. 정산 배치에는 `BR-16`~`BR-21`을 적용한다.
6. 이의제기에는 `BR-22`~`BR-24`를 적용한다.
7. 모든 재시도 가능 명령은 `BR-25`, `BR-26`을 따른다.
8. 외부 알림과 운영 복구는 `BR-27`, `BR-30`을 따른다.
9. 위치 검색은 `BR-28`, 결제수단은 `BR-29`를 따른다.
10. 분석 Read Model은 `BR-31`, `BR-32`를 따른다.

---

# 별도 ADR이 필요한 정책 목록

| Topic | Related ADR |
|---|---|
| 금액 표현·반올림·항목별 배분 | [ADR-014](../adr/ADR-014-money-allocation-and-partial-refund.md) |
| 예약 lease와 재고·슬롯 확정 시점 | [ADR-005](../adr/ADR-005-reservation-transaction-strategy.md), [ADR-013](../adr/ADR-013-payment-unknown-reservation-expiry.md) |
| 매장 수락 timeout과 보상 흐름 | [ADR-015](../adr/ADR-015-store-acceptance-timeout-compensation.md) |
| 주문 가격·할인·포인트 스냅샷 | [ADR-004](../adr/ADR-004-order-price-snapshot.md), [ADR-014](../adr/ADR-014-money-allocation-and-partial-refund.md) |
| 쿠폰 Campaign 계산 모델 | [ADR-024](../adr/ADR-024-coupon-calculation-model.md) |
| 0원 혜택 결제 | [ADR-016](../adr/ADR-016-benefit-only-payment.md) |
| 부분 환불 배분 | [ADR-014](../adr/ADR-014-money-allocation-and-partial-refund.md) |
| PointLot·포인트 회수·복원·감사형 조정 | [ADR-011](../adr/ADR-011-point-lot-ledger.md), [ADR-014](../adr/ADR-014-money-allocation-and-partial-refund.md), [ADR-065](../adr/ADR-065-refund-earned-point-recovery-ledger.md), [ADR-066](../adr/ADR-066-audited-loyalty-point-adjustment.md), [ADR-068](../adr/ADR-068-immutable-integration-event-snapshots.md), [ADR-069](../adr/ADR-069-operator-permission-grants-and-audited-policy-read.md), [ADR-071](../adr/ADR-071-settlement-input-snapshot-foundation.md) |
| 정산 기준일·주기·수수료 기준 | [ADR-017](../adr/ADR-017-settlement-calculation-and-cost-allocation.md), [ADR-067](../adr/ADR-067-settlement-batch-creation-and-schema-ownership.md), [ADR-068](../adr/ADR-068-immutable-integration-event-snapshots.md), [ADR-071](../adr/ADR-071-settlement-input-snapshot-foundation.md) |
| 쿠폰·포인트 비용 부담 | [ADR-017](../adr/ADR-017-settlement-calculation-and-cost-allocation.md), [ADR-071](../adr/ADR-071-settlement-input-snapshot-foundation.md) |
| 확정 정산 Adjustment와 음수 이월 | [ADR-008](../adr/ADR-008-settlement-adjustment-ledger.md), [ADR-017](../adr/ADR-017-settlement-calculation-and-cost-allocation.md) |
| 이의제기 hold 정책 | [ADR-018](../adr/ADR-018-settlement-dispute-hold-and-refile.md) |
| 멱등성 키와 보존 기간 | [ADR-007](../adr/ADR-007-payment-idempotency-reconciliation.md), [ADR-025](../adr/ADR-025-order-creation-idempotency-transaction.md) |
| 알림 retry·수동 복구 | [ADR-019](../adr/ADR-019-notification-retry-and-manual-recovery.md) |
| 위치정보 최소 보존 | [ADR-020](../adr/ADR-020-nearby-location-privacy.md), [ADR-070](../adr/ADR-070-signed-cursor-and-pagination-contract.md) |
| 결제수단 tokenization | [ADR-021](../adr/ADR-021-payment-method-tokenization.md) |
| 감사 로그 | [ADR-022](../adr/ADR-022-audit-record.md) |
| 매출 지표와 late event 재집계 | [ADR-023](../adr/ADR-023-analytics-refund-and-late-events.md), [ADR-068](../adr/ADR-068-immutable-integration-event-snapshots.md) |

---

# 구현 전 검증 체크리스트

- [ ] 각 정책이 `business-policy-decisions.md`에 동일한 값으로 반영됐는가
- [ ] Aggregate 불변식 문서가 정책과 충돌하지 않는가
- [ ] 상태 머신에 취소·거절·환불·timeout 전이가 반영됐는가
- [ ] OpenAPI 오류 코드와 409 멱등성 충돌이 정의됐는가
- [ ] DB Unique Constraint 후보가 식별됐는가
- [ ] 모든 시간 의존 테스트에 고정 `Clock` 사용 계획이 있는가
- [ ] 금액 배분과 환불 tie-out 테스트가 정의됐는가
- [ ] 결제·알림·정산 장애 복구 시나리오가 정의됐는가
- [ ] 실제 측정하지 않은 성능 결과가 문서에 포함되지 않았는가
- [ ] 모든 정책에 Revisit Conditions가 존재하는가
