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
- **Rationale:** 결제 후 무기한 대기하는 고객 경험을 방지하고 예외 흐름을 명확하게 만든다.
- **Affected Contexts:** Ordering, Fulfillment, Payment, Inventory, Promotion, Loyalty, Notification, Operations
- **Affected Aggregates:** Order, Payment, PickupReservation, StockReservation, CouponIssuance, PointAccount, NotificationDelivery
- **Required Tests:**
  - 3분 이전 수락·거절
  - 2분 경고와 3분 자동 거절
  - 자동 거절 작업 재실행 멱등성
  - 수락과 timeout 작업의 동시 실행
  - 자동 환불 실패 시 reconciliation
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
- **Rationale:** 제조 시작 이후 발생한 비용과 고객 편의를 구분하고 상태 전이를 단순화한다.
- **Affected Contexts:** Ordering, Payment, Fulfillment, Inventory, Promotion, Loyalty
- **Affected Aggregates:** Order, Payment, PickupReservation, StockReservation
- **Required Tests:**
  - 허용 상태별 취소
  - `ACCEPTED` 이후 고객 취소 거부
  - 결제 전·후 취소의 보상 차이
  - 중복 취소 멱등성
- **ADR Required:** No
- **Revisit Conditions:** 매장별 취소 가능 시간이나 제조 단계별 수수료 정책을 도입할 때

## BR-15 부분 품목 취소 범위

- **Status:** Accepted for MVP
- **Decision:** 결제 전에는 주문 항목을 변경하지 않고 주문 전체를 취소한 뒤 새 주문을 생성한다. 결제 후에는 주문 항목 변경을 금지하고, 필요한 경우 품목 단위 부분 환불로 처리한다. 부분 환불은 매장 또는 운영자만 실행할 수 있다.
- **Rationale:** 결제·쿠폰·포인트·정산 금액을 다시 계산하면서 주문 원본이 변하는 문제를 방지한다.
- **Affected Contexts:** Ordering, Payment, Promotion, Loyalty, Settlement
- **Affected Aggregates:** Order, Payment, SettlementAdjustment
- **Required Tests:**
  - 결제 시작 후 주문 항목 변경 거부
  - 품목 단위 부분 환불 금액 계산
  - 반복 부분 환불 누적액 검증
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
- **Rationale:** 고객이 실제로 지불한 가치와 적립 비용을 일치시킨다.
- **Affected Contexts:** Loyalty, Ordering, Payment, Settlement
- **Affected Aggregates:** PointAccount, PointLot, Order, Payment
- **Required Tests:**
  - 쿠폰 사용 주문 적립액
  - 포인트 사용 주문 적립액
  - 0원 주문 적립 0
  - 부분 환불에 따른 적립 포인트 회수
- **ADR Required:** Yes — 포인트 Lot·적립·환불 복원 정책
- **Revisit Conditions:** 매장 또는 브랜드가 할인 전 금액 적립 정책을 요구할 때

## BR-11 포인트 사용 한도와 0원 주문

- **Status:** Accepted for MVP
- **Decision:** 포인트는 쿠폰 적용 후 남은 결제 예정액 전부까지 사용할 수 있다. 포인트로 전액 결제되어 최종 결제액이 0원이면 외부 PG를 호출하지 않고 `BENEFIT_ONLY` 결제 기록을 생성하여 주문을 결제 완료로 처리한다.
- **Amendment (2026-07-28):** 0원 주문은 주문 생성 Feature에 포함한다. 주문 생성 로컬 트랜잭션 안에서 임시 예약을 획득한 뒤 `BENEFIT_ONLY Payment(APPROVED)`를 생성하고 슬롯·재고·쿠폰·포인트 예약을 확정하며 Order를 `PAID`로 커밋한다. 이 주문에는 active 결제 전 lease가 남지 않고 외부 PG 호출도 발생하지 않는다.
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
- **Rationale:** 환불 시점에 정책을 다시 계산하지 않고 주문 당시 결과를 재현하기 위함이다.
- **Affected Contexts:** Ordering, Promotion, Loyalty, Payment, Settlement
- **Affected Aggregates:** Order, OrderLine, Payment, PointAccount, SettlementAdjustment
- **Required Tests:**
  - 여러 품목의 할인·포인트 배분 합계 일치
  - 나머지 1원 배분의 결정성
  - 같은 품목 반복 환불 방지
  - 환불 후 승인액·포인트·정산액 tie-out
- **ADR Required:** Yes — 부분 환불 배분 정책
- **Revisit Conditions:** 쿠폰별 환급 가능 정책 또는 묶음 상품 환불 정책이 도입될 때

## BR-13 환불 주문의 적립 포인트 회수

- **Status:** Accepted for MVP
- **Decision:** 환불 금액에 대응하는 적립 포인트를 먼저 미사용 PointLot에서 회수한다. 이미 사용되어 전부 회수할 수 없으면 포인트 잔액을 음수로 만들지 않고 부족액을 Loyalty의 `POINT_RECOVERY_PENDING` 원장 항목으로 기록한다. 이후 발생하는 포인트 적립은 부족액 상계에 우선 사용한다. 정산 금액 보정이 필요하면 별도 SettlementAdjustment가 원천 refund reference를 사용하며, 포인트 회수 대기 잔액 자체를 소유하지 않는다.
- **Rationale:** 환불을 막지 않으면서 포인트 비용의 정합성을 유지하고 음수 잔액을 피한다.
- **Affected Contexts:** Loyalty, Payment, Settlement, Operations
- **Affected Aggregates:** PointAccount, PointLot, PointTransaction
- **Required Tests:**
  - 미사용 적립 포인트 전액 회수
  - 일부 사용 후 부족액 기록
  - 이후 적립 시 우선 상계
  - 중복 환불 이벤트로 이중 회수 방지
- **ADR Required:** Yes
- **Revisit Conditions:** 포인트 부채를 사용자에게 청구하거나 환불을 제한하는 별도 정책이 필요할 때

---

# D. 정산·이의제기 정책

## BR-16 정산 기준일

- **Status:** Accepted for MVP
- **Decision:** 주문이 `COMPLETED`된 날짜를 정산 귀속일로 사용한다. 결제 승인만 되고 픽업 완료되지 않은 주문은 정산 대상에 포함하지 않는다.
- **Rationale:** 매장이 실제로 상품을 인도한 거래를 정산 대상으로 삼는다.
- **Affected Contexts:** Ordering, Fulfillment, Settlement, Analytics
- **Affected Aggregates:** Order, SettlementItem, SettlementBatch
- **Required Tests:**
  - 결제일과 완료일이 다른 주문의 귀속
  - 완료되지 않은 주문 제외
  - 자정 경계의 완료 주문
  - 중복 완료 이벤트의 정산 항목 중복 방지
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
- **Rationale:** 서로 다른 발급 주체의 포인트를 섞어 사용할 때 비용 책임을 추적하기 위함이다.
- **Affected Contexts:** Loyalty, Settlement, Merchant
- **Affected Aggregates:** LoyaltyProgram, PointLot, SettlementItem
- **Required Tests:**
  - 서로 다른 발급 주체 PointLot 혼합 사용
  - 선소멸 우선 사용과 비용 배분
  - 환불 시 원 발급 주체 복원
  - 원장 합계와 정산 비용 tie-out
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
- **Rationale:** 사용자의 재시도는 허용하되 키 재사용으로 다른 거래가 실행되는 것을 막는다.
- **Affected Contexts:** Ordering, Payment, Settlement, Operations
- **Affected Aggregates:** IdempotencyRecord, Order, Payment, SettlementAdjustment
- **Required Tests:**
  - 같은 키·같은 payload 재요청
  - 같은 키·다른 payload 409
  - 처리 중 요청의 동시 재시도
  - 주문 생성 최초 201·4xx·503 response 재생
  - 주문 생성 PROCESSING의 409와 Retry-After
  - 실패·UNKNOWN 상태 재요청
- **ADR Required:** Yes — 결제 멱등성과 reconciliation
- **Revisit Conditions:** 다중 채널 또는 외부 파트너가 자체 멱등성 범위를 요구할 때

## BR-26 멱등성 데이터 보존 기간

- **Status:** Accepted for MVP
- **Decision:** 멱등성 레코드는 거래가 terminal 상태가 된 시점부터 90일 동안 보존한다. 진행 중이거나 `UNKNOWN` 상태인 거래의 레코드는 정리하지 않는다. 정리 작업은 chunk 단위로 실행하고 재실행 가능해야 한다.
- **Rationale:** 14일 이의제기와 일반적인 환불·운영 조사 기간보다 충분히 길게 재시도 결과를 보존한다.
- **Affected Contexts:** Ordering, Payment, Operations
- **Affected Aggregates:** IdempotencyRecord, Payment
- **Required Tests:**
  - terminal 상태 90일 이전 보존
  - 90일 이후 정리
  - 진행 중·UNKNOWN 제외
  - 정리 배치 중단·재실행
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
| PointLot·포인트 회수·복원 | [ADR-011](../adr/ADR-011-point-lot-ledger.md), [ADR-014](../adr/ADR-014-money-allocation-and-partial-refund.md) |
| 정산 기준일·주기·수수료 기준 | [ADR-017](../adr/ADR-017-settlement-calculation-and-cost-allocation.md) |
| 쿠폰·포인트 비용 부담 | [ADR-017](../adr/ADR-017-settlement-calculation-and-cost-allocation.md) |
| 확정 정산 Adjustment와 음수 이월 | [ADR-008](../adr/ADR-008-settlement-adjustment-ledger.md), [ADR-017](../adr/ADR-017-settlement-calculation-and-cost-allocation.md) |
| 이의제기 hold 정책 | [ADR-018](../adr/ADR-018-settlement-dispute-hold-and-refile.md) |
| 멱등성 키와 보존 기간 | [ADR-007](../adr/ADR-007-payment-idempotency-reconciliation.md), [ADR-025](../adr/ADR-025-order-creation-idempotency-transaction.md) |
| 알림 retry·수동 복구 | [ADR-019](../adr/ADR-019-notification-retry-and-manual-recovery.md) |
| 위치정보 최소 보존 | [ADR-020](../adr/ADR-020-nearby-location-privacy.md) |
| 결제수단 tokenization | [ADR-021](../adr/ADR-021-payment-method-tokenization.md) |
| 감사 로그 | [ADR-022](../adr/ADR-022-audit-record.md) |
| 매출 지표와 late event 재집계 | [ADR-023](../adr/ADR-023-analytics-refund-and-late-events.md) |

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
