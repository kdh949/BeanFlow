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
- **Pickup Business Date Amendment (2026-08-12):** 매장 일일 픽업 순번의
  `pickupBusinessDate`는 주문의 픽업 슬롯 `startsAt` Instant를 `Asia/Seoul` 날짜로 변환한 값이다.
  주문 생성 시 이 날짜를 snapshot하고 이후 슬롯·영업시간 변경으로 다시 계산하지 않는다. 매장별
  마감 시각이나 주문 생성일을 대신 사용하지 않는다. 슬롯 시작 시각이 없거나 해석할 수 없으면 주문
  생성 또는 migration을 실패시키며 현재 날짜·주문 생성일로 대체하지 않는다.
- **Rationale:** MVP 대상 매장이 국내에 한정되어 있어 매장별 timezone을 지원할 실익보다 구현·테스트 복잡도가 크다.
- **Affected Contexts:** Merchant, Fulfillment, Promotion, Loyalty, Settlement, Analytics
- **Affected Aggregates:** Store, PickupSlot, Campaign, PointLot, SettlementBatch
- **Required Tests:**
  - 자정 경계의 영업시간과 정산일 테스트
  - 자정 전후 픽업 슬롯의 `pickupBusinessDate`와 주문 생성일 불일치 테스트
  - 캠페인 시작·종료 경계 테스트
  - 포인트 만료 시각 경계 테스트
  - 고정 `Clock`을 사용한 시간 의존 테스트
- **ADR Required:** Yes for pickup numbering — [ADR-097](../adr/ADR-097-store-pickup-number.md)
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
- **Customer List Materialization Amendment (2026-08-12):** 고객 주문 목록도 조회에 해당한다.
  목록은 customer scope와 signed cursor로 먼저 고정한 **한 페이지의 candidate ID window** 안에서
  만료 시각이 지난 `PENDING_PAYMENT`를 찾고, 해당 Order와 네 예약 자원을 하나의 쓰기
  transaction에서 모두 만료·해제한 뒤 같은 candidate window를 Projection으로 다시 읽는다. 하나라도
  해제하지 못하면 전체 materialization transaction을 rollback하고 stale 목록 대신 `503`을 반환한다.
  활성 상태 필터에서 만료 Order가 제외되면 페이지가 page size보다 짧거나 비어도 scan boundary 기반
  `nextCursor`를 반환할 수 있다. 페이지를 채우려고 다음 window까지 암묵적으로 쓰지 않는다.
- **Payment Decline Amendment (2026-07-29):** Provider가 승인을 명시적으로
  거절하면 Payment를 `FAILED`, Order를 `CANCELLED`로 전환하고 네 예약을 같은
  transaction에서 해제한다. 같은 Order에서 다른 결제수단으로 다시 승인하지 않고
  고객은 새 주문을 생성한다.
- **Pickup Start Effective-Lease Amendment (2026-08-09):** 픽업 주문의 예약 만료 시각은
  고정 5분 시각과 `pickupSlot.startsAt` 중 더 이른 시각이다. 이 effective lease를 슬롯·재고·쿠폰·포인트
  예약과 `Order.reservationExpiresAt`에 같은 값으로 고정한다. 결제 결과를 반영할 때도
  `now >= reservationExpiresAt`이면 먼저 만료를 materialize하며 슬롯을 확정하지 않는다. 이 시각 뒤의
  Provider 승인 또는 `UNKNOWN` lookup approval은 주문·예약을 되살리지 않고 기존 late-approval
  void/refund reconciliation으로 보낸다.
- **Fast Reorder Result Amendment (2026-08-09):** 빠른 재주문 성공은 재검증 가능한 draft나 quote를
  별도 생성하는 것이 아니라 기존 주문 생성 경계를 통해 즉시 새 `Order`를 생성한다. 새 Order와 필요한
  예약·snapshot·멱등 응답이 모두 commit된 뒤에만 `201 Created`를 반환한다. `Reorder`를 별도
  Aggregate로 만들지 않는다. 따라서 고객은 성공 응답 전에 현재 가격을 별도로 승인하는 단계를 갖지 않는다.
- **Fast Reorder Source Amendment (2026-08-09):** source Order에서는 `menuId`, ID 오름차순으로
  정규화된 `optionIds`, `quantity`만 새 주문 입력으로 복사한다. 과거 또는 현재의 note를 복사하지 않으며
  빠른 재주문을 위해 새 note 계약을 도입하지 않는다. 검증된 option ID snapshot이 없는 기존 OrderLine은
  옵션 이름, 현재 메뉴 또는 sellable requirement로 추론하지 않고 재주문 불가로 명시적으로 실패한다.
- **Fast Reorder Price-Change Amendment (2026-08-09):** 재주문은 현재 Merchant 가격으로 새 Order를
  생성하며 가격 변경 자체를 실패로 만들지 않는다. 성공 응답은 혜택 적용 전 가격을 source와 current로
  비교한 line별 변경 목록과 두 subtotal을 필수로 제공한다. 변경 목록은 source line 순서이고 실제 단가가
  달라진 line만 포함한다. 쿠폰·포인트·결제 차이는 가격 변경으로 표시하지 않는다.
- **Fast Reorder Source-State Amendment (2026-08-09):** source Order가 `COMPLETED`, `CANCELLED`,
  `REJECTED`, `EXPIRED` 중 하나인 terminal 상태일 때만 빠른 재주문을 허용한다. `PENDING_PAYMENT`,
  `PAID`, `ACCEPTED`, `PREPARING`, `READY`는 진행 중 주문의 우발적 중복을 막기 위해 거부한다.
  terminal 상태는 원 주문의 가격·혜택·결제·환불 결과를 새 주문에 승계한다는 뜻이 아니며 모든 현재
  주문 가능 조건을 다시 검증한다.
- **Fast Reorder Revalidation Amendment (2026-08-09):** request는 새 `pickupSlotId`, 선택적
  `couponIssuanceId`와 명시적 `pointsToUseKrw`를 받는다. 과거 메뉴·옵션 이름과 가격, coupon·point
  allocation, PaymentMethod·Payment·Refund, pickup slot·reservation, 적립·정산 snapshot, 상태와
  deadline을 복사하지 않는다. 현재 Merchant 이름·가격·판매 상태, Fulfillment slot, Inventory stock,
  명시적으로 선택한 Coupon과 points를 기존 주문 생성 경계에서 다시 quote·reserve한다. payment method는
  복사하거나 이 request에서 승인하지 않고 외부 결제가 필요하면 BR-33의 일회성
  `POST /orders/{orderId}/payment-attempts`로 Toss 결제창을 준비한다. 한 source line이라도
  삭제·판매 중지·구성 불가이면 source line 순서의 stable item
  reason을 포함한 전체 `409`이며 부분 Order나 unavailable item 자동 삭제는 없다.
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
  - 고객 목록 candidate window의 만료 Order가 반환 전에 모두 `EXPIRED`로 물질화되는지 검증
  - 활성 목록에서 만료 Order가 빠져 빈 페이지가 되어도 scan boundary cursor가 다음 주문을 건너뛰지 않는지 검증
  - 결제와 만료 작업의 동시 실행 테스트
  - Provider 응답 또는 `UNKNOWN` lookup이 슬롯 시작 경계를 넘을 때 주문 비복구·예약 비확정과 reconciliation
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
- **Slot Reservation Window Amendment (2026-08-08):** 픽업 슬롯은 시작 전까지만 예약할 수 있다.
  주입된 `Clock` 기준 `startsAt > now`인 슬롯만 예약을 수락하고, 이미 시작했거나 끝난 슬롯은
  슬롯 row lock 안에서 `ORDER_STATE_CONFLICT`로 거절하며 예약 수·확정 수를 바꾸지 않는다.
  창이 열려 있을 때 수락된 예약의 같은 source 재시도는 창이 닫힌 뒤에도 저장된 예약을 그대로
  반환한다. `GET /stores/{storeId}/pickup-slots`의 조회 창은 이 예약 가능 창과 정확히 같아,
  목록에 있는 슬롯은 그 시점에 예약 가능한 슬롯이다. `acceptingOrders && pickupEnabled`가 아닌
  매장은 슬롯이 존재해도 빈 목록을 반환한다. 그 매장의 슬롯은 주문 생성에서 모두 거절되므로
  예약 가능한 슬롯이 없기 때문이며, 매장이 존재하는 한 응답은 200이고 404가 아니다. 메뉴 조회는
  바뀌지 않는다. 슬롯 목록은 지금부터 7일 뒤까지만 반환한다. 행 수 상한이 아니라 시간 창이므로
  창 안의 결과는 잘리지 않는다. 매장별 준비 lead time은 도입하지 않으며,
  매장은 슬롯 시작 시각 자체로 준비 시간을 표현한다. 이 개정은
  [MD-2026-010](../decisions/minor-decisions.md)을 대체한다.
- **Confirmation and Catalogue Bound Amendment (2026-08-09):** 슬롯 시작 전 수락된 예약도
  결제 확정 시점에 effective lease 안에 있어야 한다. 즉 `now >= startsAt`이면 확정 대신 BR-03의
  만료·late-approval reconciliation을 적용한다. 7일 horizon만으로는 Store별 row 수를 제한하지 못하므로,
  슬롯 목록은 1,000개 published bound를 추가한다. repository는 1,001행을 읽고 overflow면 잘린 200을
  반환하지 않고 `DEPENDENCY_UNAVAILABLE`(503)으로 실패한다. V35의 owner-scoped composite index와
  실행계획 검증은 이 공개 bound에 필요한 DB 작업 경계다.
- **Rationale:** 결제되지 않은 주문이 장시간 슬롯을 점유하지 않게 하면서 결제 중인 고객의 자리를 보호한다.
- **Affected Contexts:** Ordering, Fulfillment, Payment, Discovery
- **Affected Aggregates:** PickupSlot, PickupReservation, Order, Payment
- **Required Tests:**
  - 마지막 슬롯에 대한 동시 예약
  - 예약 수와 확정 수의 capacity 초과 방지
  - 중복 승인·거절 이벤트의 멱등 처리
  - lease 만료와 결제 승인의 경쟁 조건
  - `startsAt == now` 경계와 이미 시작·종료한 슬롯의 예약 거절
  - 거절 시 예약 수·확정 수·예약 row 불변
  - 창이 닫힌 뒤의 같은 source 재시도가 기존 예약을 반환
  - 조회 창과 예약 가능 창의 일치
  - pickup 불가 매장의 슬롯 목록이 빈 목록 200이고 404가 아님
  - 7일 horizon 직전·직후 슬롯의 포함·제외
  - 1,001개 슬롯 overflow의 503과 partial 목록 부재
  - 슬롯 시작 경계와 결제 승인·`UNKNOWN` late approval의 경쟁
- **ADR Required:** Yes — [ADR-076](../adr/ADR-076-store-catalog-read-contract.md)
- **Revisit Conditions:** 결제 승인 이후 매장 거절로 발생하는 슬롯 낭비가 유의미할 때, 또는 매장별 준비 lead time이 데이터 모델에 도입될 때

## BR-06 매장 수락 제한시간

- **Status:** Accepted for MVP
- **Decision:** 결제 승인 후 매장은 3분 안에 주문을 수락하거나 거절해야 한다. 2분이 지나면 매장 운영 알림을 생성하고, 3분이 지나도 응답이 없으면 주문을 자동 거절한다. 자동 거절 시 결제 전액 취소, 재고·슬롯 복원, 쿠폰·포인트 복원, 고객 알림을 수행한다.
- **Store Board Visibility Amendment (2026-08-12):** 수락 제한시간은 픽업 영업일과 무관하므로 점주
  실행 주문보드는 오늘 주문만으로 제한하지 않는다. 해당 매장의 모든 `PAID`, `ACCEPTED`,
  `PREPARING`, `READY`는 보드 또는 오래된 작업 큐에서 접근 가능해야 한다. `PENDING_PAYMENT`와 종료 상태는
  포함하지 않는다. API lane `PENDING_ACCEPTANCE`는 Domain의 `PAID`를 표시하는 이름일 뿐 새 Order 상태가
  아니다. `PENDING_ACCEPTANCE` lane은 `(acceptanceDeadlineAt, id)` 오름차순, 나머지 lane은
  `(pickupWindowStartSnapshot, id)` 오름차순이다. 날짜 탐색 UI가 있어도 처리할 `PAID` 주문을 숨기지
  않는다.
- **Bounded Board and Overflow Queue Amendment (2026-08-14):** 3초 conditional polling의 기본 보드는
  lane마다 앞선 50건만 반환한다. 51건 이상인 lane은 정확한 `overflowCount`와 해당 매장·lane에 서명된
  cursor를 함께 반환한다. UI는 이 상태를 빈 lane이나 완료로 표현하지 않고, 사용자가 요청할 때만
  `GET /stores/{storeId}/orders/overflow`의 같은 lane keyset page(최대 50건)를 표시한다. 따라서
  오래된 실행 주문은 polling payload에서만 분리될 뿐, 삭제·종료·권한 상실로 해석되지 않는다. cursor가
  만료·변조되면 400으로 거부하고 새 보드 snapshot을 받아야 하며, overflow queue는 3초 polling 대상이
  아니다.
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
  - 오늘 이후 픽업 `PAID` 주문도 보드에 즉시 노출되고 수락 deadline 순으로 정렬
  - `PENDING_PAYMENT`와 종료 상태가 보드에 노출되지 않는지 검증
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
- **Operator Refund Reconciliation Amendment (2026-08-03):** 환불 지연 알림이 확정된
  고객 취소 Refund가 `FAILED` 또는 `MANUAL_REVIEW`이면 활성
  `CUSTOMER_CANCELLATION_REFUND_RECONCILE` grant를 가진 PLATFORM_OPERATOR 한 명이
  사유와 Idempotency-Key를 제출해 기존 Provider key의 LOOKUP 한 번을 예약할 수 있다.
  운영자는 환불 금액, Provider key/reference와 성공 결과를 입력할 수 없고 새 REQUEST도
  만들 수 없다. Order terminal version, recovery snapshot, Refund source와 금액 tie-out을
  transaction에서 재검증하고 PAYMENT step을 `UNKNOWN`으로 다시 연다. 권한, 멱등 레코드,
  Refund/step 전이와 append-only Audit 중 하나라도 실패하면 전부 rollback한다. LOOKUP이
  실제 `SUCCEEDED`를 반환할 때만 성공 원장과 성공 event·별도 logical notification을
  확정하며 실패·불명은 다시 terminal 지연 상태로 수렴한다.
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
  로컬 transaction에서 함께 저장한다. signed amount는 `-9,223,372,036,854,775,807`부터
  `9,223,372,036,854,775,807`까지에서 0을 제외하며, 사유는 trim 뒤 1..160자다. 증빙
  reference는 1..20개이고 각 항목은 trim 뒤 1..500자다. 이 경계는 Audit 원자 저장 계약과
  장기 보존 payload 크기를 command 진입 전에 제한한다.
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

### BR-16~BR-24 Implementation Evidence (2026-08-03)

- V28~V30과 Settlement/Dispute Application Service가 store/date Batch 하나,
  `OPEN → CALCULATED → CONFIRMED`, immutable Item/Adjustment, source unique 사후 조정,
  음수 carry-forward와 active Item dispute 하나를 DB 제약과 guarded transition으로 보호한다.
- Batch는 500건 keyset chunk로 Item snapshot만 합산하며 이전 confirmed Batch의 음수 잔액과
  calculation 시각까지 생성된 Adjustment를 다음 Batch에서 한 번 소비한다. 환불·이의 판정은
  확정 Batch를 수정하지 않고 `REFUND_SUCCEEDED` 또는 `DISPUTE_ACCEPTED` Adjustment를 만든다.
- OWNER filing은 확정 Batch의 서울 날짜 기준 `[D+1 00:00, D+15 00:00)`를 적용한다.
  진행 중 중복은 Item당 하나로 수렴하고, 종결 직후 ID와 이전 배열에 없던 evidence reference를
  모두 제시한 경우에만 새 Aggregate로 한 번 재이의할 수 있다.
- `ACCEPTED` 판정은 별도 Settlement transaction의 Adjustment commit을 먼저 확인한 뒤 Dispute를
  terminal로 만든다. handoff 뒤 Dispute transaction이 실패하면 `UNDER_REVIEW`와
  `SETTLEMENT_DISPUTE_DECISION` ReprocessingCase가 남고 같은 source retry가 정확히 수렴한다.

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
- **Public Registration Boundary Amendment (2026-08-09):** 고객용 결제수단 등록은
  Provider 결제창이 발급한 일회성 `authKey`를 받는 방식으로 한정한다. BeanFlow의 고객용
  API는 카드번호, 유효기간, 생년월일, 카드 비밀번호와 CVC를 입력 필드로 받지 않는다.
  서버는 `authKey`를 registration Port로 전달해 opaque token을 발급받으며, 휴대폰
  본인인증 등 Provider 결제창의 소유 확인 절차를 우회하지 않는다. 카드 원문을 받는 API
  방식은 MVP 공개 계약과 운영 코드의 Non-goal이다. 자동 sandbox 검증에 필요한 합성
  입력은 고객용 API와 분리된 테스트 전용 계약에서만 다루며, 그 profile gate와 입력 수명은
  ADR-079가 별도로 확정한다.
- **MVP Provider Scope Amendment (2026-08-09):** 결제수단 lifecycle의 제품 외부
  provider는 `TOSS_PAYMENTS` 하나다. 고객 request는 provider routing을 선택하지 않으며 서버가
  이 고정 provider의 registration/deactivation Port를 사용한다. scripted local adapter는
  명시적 local/test capability일 뿐 고객에게 선택·표시되는 제품 provider가 아니고 운영
  fallback으로 활성화하지 않는다. 두 번째 외부 provider나 provider routing은 별도 Business
  Policy·ADR·migration·failure contract 없이 추가하지 않는다.
  신규 public registration은 Application Service에서 다른 provider를 거부한다. 기존 test/local
  provider row는 migration이 값을 rewrite하지 않지만 제품 provider 또는 lifecycle 목록 대상이
  아니다.
- **Display Metadata Amendment (2026-08-09):** 카드 표시용 저장·응답 필드는
  `displayAlias`, `cardBrand`, `lastFour`로 닫는다. 고객 등록 request는 `authKey`와 trim 뒤
  1..80자이고 control character가 없는 `displayAlias`만 받는다. `cardBrand`와 `lastFour`는
  Provider의 검증된 발급 결과만 원천으로 사용하며 각각 trim 뒤 1..40자와 숫자 4자리다.
  Provider 결과가 누락·형식 불일치이면 빈 값, `UNKNOWN`, token 파싱 또는 고객 입력으로
  보정해 등록 성공을 만들지 않는다.

  expiry month/year는 함께 전체 유효기간을 구성하므로 저장·응답하지 않는다. 카드번호,
  CVC, 생년월일, 카드 비밀번호, token reference와 provider customer reference도 어떤 공개
  schema에도 넣지 않는다. alias·brand·last4는 identity·인가·unique 조건이 아니며 중복
  표시값을 허용한다.
- **Customer Default Method Amendment (2026-08-09):** 고객은 자신의 `ACTIVE`
  PaymentMethod 중 최대 하나를 default로 지정할 수 있다. default는 클라이언트 표시·선택
  선호이며 identity·인가 근거나 승인 대상 자동 결정 규칙이 아니다. 결제 승인 request는
  계속 `paymentMethodId`를 명시해야 하고 서버는 누락을 default로 보충하지 않는다.

  DB는 `is_default`와 customer별 active default 최대 하나를 partial unique/check로 보호하고,
  registration·default 변경·deactivation은 같은 customer-scope lock으로 직렬화한다. default
  결제수단의 deactivation Tx D1은 `is_default=false`를 함께 commit하지만 다른 결제수단을
  자동 승격하지 않는다. 목록은 default를 먼저, 나머지는 `createdAt DESC,
  paymentMethodId DESC`로 정렬한다. 새 PaymentMethod는 항상 `isDefault=false`다. 고객은
  `Idempotency-Key`가 필요한 `PUT /api/v1/payment-methods/{paymentMethodId}/default`로만
  ACTIVE 결제수단을 default로 지정한다. request body와 별도 clear command는 두지 않는다.
  같은 key·같은 target replay는 최초 200을 재생하고 다시 default를 바꾸지 않으며, 같은 key를
  다른 target에 쓰면 `409 IDEMPOTENCY_KEY_REUSED`다.
- **Provider Reference Amendment (2026-08-09):** provider가 승인을 위해 token reference와
  함께 요구하는 non-sensitive 참조 값은 위 목록에 더해 저장할 수 있다. 다음을 모두
  만족해야 한다.
  - 원본 카드번호, CVC, 전체 유효기간 중 어느 것도 담지 않는다. 저장 금지 목록은 그대로다.
  - provider가 유추 가능한 값을 금지하면 사용자 식별자, 이메일, 전화번호에서 파생하지 않은
    무작위 값으로 생성한다.
  - API 응답, 로그, trace, metric tag와 AuditRecord에 노출하지 않는다.
  - 결제수단 identity가 아니다. member/provider/token reference unique 제약을 대체하거나
    확장하지 않으며, 객체 수준 인가의 근거로 사용하지 않는다.
  - provider가 실제로 요구하는 값만 저장하고 선제적으로 만들지 않는다.

  최초 대상은 토스페이먼츠 자동결제의 `customerKey`다. 이 값 없이는 발급된 빌링키로 승인
  자체가 불가능하므로, 저장하지 않으면 sandbox 검증 경로가 존재할 수 없다.
  저장 컬럼은 `payment_method.provider_customer_reference varchar(200)`다. 기존 row와 참조 값을
  요구하지 않는 provider를 위해 물리 컬럼은 nullable이지만, DB CHECK와 Application Service는
  `provider = TOSS_PAYMENTS`일 때 trim 뒤 non-blank를 필수로 하고 다른 provider에는 null만
  허용한다. 이 DB branch는 legacy local/test row의 무손실 migration을 위한 것이며 신규 제품
  provider 허용을 뜻하지 않는다. 다른 provider가 이 컬럼을 쓰려면 별도 결정과 migration으로
  허용 목록을 넓힌다.
  이 컬럼에는 unique index를 추가하지 않고 기존
  `(customer_id, provider, token_reference)` unique를 유지한다.

  값은 PaymentMethod registration Application Service의 CSPRNG factory가 등록 시도당 한 번
  생성한다. 외부 발급 호출 전에 registration 멱등 원장에 고정하고, 성공 시 같은 값을
  PaymentMethod에 복사한다. retry마다 재생성하거나 adapter가 임의 default를 만들지 않는다.
- **Test Card Amendment (2026-08-09):** PG sandbox에서 결제수단 token을 자동 발급받는
  통합테스트는 `src/test`의 test harness가 Toss registration adapter를 직접 호출한다. 공개·내부
  HTTP endpoint와 운영 Application Service는 만들지 않는다. harness는 `toss-sandbox` profile,
  `prod` 부재, `test_sk_` secret 확인과 별도 synthetic-issuance enable 조건이 모두 참일 때만
  실행한다. 조건이 맞지 않으면 실제 Provider를 호출하지 않고 명시적으로 실패한다.

  Provider가 카드 API 발급에 요구하는 합성 카드번호·유효기간·생년월일·카드 비밀번호는
  실행 시점 메모리에서 만들고 전송만 허용한다. CVC는 생성하거나 전송하지 않는다. 합성 값은
  소스·fixture·설정·seed에 기록하지 않고 발급 응답 직후 폐기하며 Entity·로그·trace·metric·
  AuditRecord·API schema에 남기지 않는다. 실제 사람의 카드·신원 값을 입력하거나 수집하지
  않는다. 이 예외는 고객용 `authKey` 등록 계약을 넓히지 않는다.
- **Provider Deactivation Amendment (2026-08-09):** 고객의 결제수단 DELETE는 local row를
  숨기는 데서 끝나지 않고 Provider token 폐기까지 요청한다. 소유권·상태·멱등성을 검증한
  로컬 transaction에서 `DEACTIVATION_REQUESTED`와 복구 work를 먼저 저장하고, 그 commit
  시점부터 새 결제 선택을 차단한다. Provider deactivation Port는 transaction 밖에서 호출한다.
  확인된 성공만 soft terminal `DEACTIVATED`로 전이한다. timeout, 응답 유실, 결과 저장 실패는
  성공·확정 실패나 `ACTIVE`로 추정하지 않고 `DEACTIVATION_UNKNOWN` 또는 `RECONCILING`으로
  보존한다. bounded 복구가 소진되면 `MANUAL_REVIEW`로 전환한다. row와 token reference는
  진행 중 복구·감사에 필요하므로 이 경로에서 hard delete하지 않는다.
- **External Deactivation Notification Amendment (2026-08-09):** Provider가 보낸 검증된
  token 폐기 알림은 provider-neutral inbox에 먼저 멱등 수락한 뒤 단일 PaymentMethod를 잠가
  `DEACTIVATED`로 단조 전이한다. `ACTIVE`, deactivation 진행·불명·수동 검토 상태에서 모두
  적용할 수 있지만 이미 시작된 Payment fact를 소급 변경하거나 결제수단을 재활성화하지 않는다.
  토스의 최초 event type은 `BILLING_DELETED`다.

  transport 인증·서명 검증 실패는 business event로 수락하지 않는다. 검증된 알림이 정확히
  하나의 PaymentMethod에 매핑되지 않으면 token 소유자를 추정하거나 임의 row를 폐기하지 않고
  inbox를 `MANUAL_REVIEW`로 보존한다. raw payload, token reference와 provider customer
  reference는 로그·trace·metric tag·AuditRecord에 남기지 않는다. 같은 delivery 안에서 inbox
  수락과 단일 mapping 또는 manual-review 결과가 각각 내구 commit된 뒤에만 2xx를 응답한다.
  어느 DB 저장이라도 실패하면 non-2xx로 재전송을 유도하며, raw token은 W2까지 메모리에서만
  유지하고 replay가 다시 제공한 값으로 non-terminal inbox를 처리한다. provider-neutral inbox·
  상태 전이와 migration은 결제수단 lifecycle이, 토스 transport·인증과 mapping은 ADR-078
  ExecPlan이 소유한다.
- **In-flight Payment Amendment (2026-08-09):** Payment 승인 Tx1과 PaymentMethod
  deactivation Tx D1은 같은 PaymentMethod row lock으로 경쟁한다. Tx1이 먼저 commit하면
  Provider, token reference와 provider customer reference의 내부 전용 immutable
  `PaymentProviderRequestSnapshot`을 Payment·멱등 레코드와 함께 저장한다. 이후 승인 요청,
  lookup과 late-approval recovery는 current PaymentMethod 상태·값을 다시 읽지 않고 이 snapshot만
  사용한다. snapshot은 API·로그·trace·metric tag·Audit에 노출하지 않는다.

  Tx1 뒤 deactivation이 commit돼도 이미 시작된 Payment fact와 Order lease·late-approval 정책은
  소급 변경하지 않고 기존 승인·reconciliation을 계속한다. deactivation은 이후 새 Payment
  준비만 차단한다. D1이 먼저 commit하면 Payment 준비는 Provider 호출과 Payment 생성 없이
  거부한다. deactivation이 진행 Payment를 취소하거나 Provider approval과 병합하지 않는다.
  snapshot 누락·불일치는 current PaymentMethod fallback 없이 명시적 setup failure다.
- **Duplicate Token Binding Amendment (2026-08-09):** registration result의 provider
  token은 비가역 token fingerprint 기반 transaction advisory lock으로 직렬화한 뒤 기존 binding을
  검사한다. 기존 `ACTIVE` PaymentMethod의 owner, provider, token reference, provider customer
  reference, display alias, card brand와 last4가 모두 같을 때만 기존 resource로 멱등 수렴한다.
  새 command는 기존 resource를 `200`으로 반환한다. `isDefault` 같은 lifecycle 선호는 비교 대상이
  아니며 기존 값을 변경하지 않는다.

  다른 owner, provider customer reference 또는 표시 metadata가 다르거나 기존 row가
  deactivation 진행·불명·terminal·수동 검토이면 token이 같아도 overwrite·alias 갱신·재활성화하지
  않고 등록 결과를 conflict/manual review로 보존한다. 기존
  `(customer_id, provider, token_reference)` unique는 유지하며 provider customer reference를
  identity에 넣지 않는다. migration은 provider+token이 여러 owner에 걸친 기존 row를 발견하면
  임의 병합하지 않고 중단한다.
- **Registration Idempotency Amendment (2026-08-09):** 고객 등록 POST는
  `actorId + REGISTER_PAYMENT_METHOD_V1 + Idempotency-Key` scope의 사전등록 모델을 사용한다.
  canonical payload는 fixed provider, raw 값을 저장하지 않은 `authKey` SHA-256과 정규화한
  `displayAlias`다. 같은 key·같은 payload는 새 Provider 호출 없이 최초 terminal response 또는
  현재 non-terminal representation을 반환하고, 다른 payload는
  `409 IDEMPOTENCY_KEY_REUSED`다. customer/provider/authKey hash unique는 다른 key로 같은
  authKey를 재사용해도 Provider 호출 전에 거부한다.

  Tx R1은 intended PaymentMethod ID, CSPRNG provider customer reference, payload hash와
  registration work를 먼저 commit한다. claim transaction 뒤 Provider registration은 DB
  transaction 밖에서 claim당 한 번 호출한다. timeout·응답 유실·parsing failure와 claim 뒤
  process loss는 `REGISTRATION_UNKNOWN`이며 일회성 authKey를 다시 보내지 않는다. 해당
  Provider에 lookup 계약이 없으면 추가 Provider 상호작용 없이 `MANUAL_REVIEW`로 종결한다.
  성공·token·표시 metadata를 추정하지 않고 raw authKey·hash·provider reference를 응답·로그·
  trace·metric tag·Audit에 남기지 않는다. terminal 등록 원장은 90일, UNKNOWN·MANUAL_REVIEW는
  운영 해소 전까지 정리하지 않는다.
- **Deactivation Idempotency Amendment (2026-08-09):** 고객 폐기는
  `actorId + DEACTIVATE_PAYMENT_METHOD_V1 + Idempotency-Key` scope로 중재한다. canonical
  payload는 소유권 검증 뒤의 `paymentMethodId` 하나다. 같은 key·target은 새 상태 전이와
  Provider 호출 없이 최초 terminal response 또는 현재 non-terminal representation을 반환하고,
  같은 key·다른 target은 `409 IDEMPOTENCY_KEY_REUSED`다.

  Tx D1은 `DEACTIVATION_REQUESTED`, default 해제, deactivation work와 멱등 상태를 commit한다.
  짧은 claim transaction 뒤 Provider DELETE는 DB transaction 밖에서 한 번만 호출한다. claim
  전 process loss는 같은 logical operation이 claim할 수 있지만, claim 뒤 timeout·응답 유실·
  parsing failure·process loss와 Provider 성공 뒤 result 저장 실패에서는 DELETE를 자동 재호출하지
  않는다. 토스가 DELETE 멱등키나 결과 조회를 보장하지 않으므로 성공·not-found를 추정하지 않고
  `BILLING_DELETED`를 기다린다. 최초 `DEACTIVATION_UNKNOWN` 판정 시각부터 96시간 안에
  검증된 알림이 없으면 `MANUAL_REVIEW`로 전환하며 `ACTIVE`로 되돌리지 않는다. 이 창은 토스의
  공식 최대 webhook 재전송 창 약 3일 19시간에 5시간 여유를 더한다. terminal 원장은 90일,
  UNKNOWN·MANUAL_REVIEW는 운영 해소 전까지 정리하지 않는다.
- **Provider Port Outcome Amendment (2026-08-09):** registration Port는 닫힌
  `Issued`, `RejectedWithoutEffect`, `Unknown`, `Misconfigured` 결과를, deactivation Port는
  `Deactivated`, `RejectedWithoutEffect`, `Unknown`, `Misconfigured` 결과를 반환한다.
  `Issued`만 opaque token과 검증된 brand·last4를 포함하고 `Deactivated`는 Provider의 확인된
  성공만 나타낸다. `RejectedWithoutEffect`는 adapter contract test로 side effect 부재가
  입증된 allowlist code에만 허용한다.

  timeout·연결 실패·응답 유실·5xx·파싱 실패·필수 성공 필드 누락과 allowlist 밖 code는
  `Unknown`이다. credential·인증·계약·필수 설정 결함은 고객 거절로 축소하지 않고
  `Misconfigured`로 운영에 노출한다. 예외는 프로그래밍 결함과 호출 계약 위반에만 사용하고
  정상적인 Provider 실패 분류를 예외로 숨기지 않는다. 공개 API는 raw Provider code/message,
  token과 provider reference를 응답하지 않는다.

  registration `Misconfigured`가 side effect 부재를 확인한 경우만 설정 수정 뒤 같은 key가 새
  claim으로 authKey를 다시 보낼 수 있다. `Unknown`에서는 금지한다. deactivation
  `RejectedWithoutEffect`와 `Misconfigured`는 이미 D1이 commit된 command를 `MANUAL_REVIEW`로
  보내며 DELETE를 다시 보내지 않는다. 고객에게는 202 pending/delayed로 투영한다.
- **Provider Adapter Activation Amendment (2026-08-09):** registration/deactivation의
  scripted adapter는 `(local | test) & !toss-sandbox & !prod` 조건에서만 명시적으로 활성화한다. Toss sandbox
  adapter는 `toss-sandbox`, `!prod`, `test_sk_` secret 조건에서만 활성화한다. 합성 카드 발급은
  이 조건에 별도 enable flag까지 더한 `src/test` harness 전용이다.

  `prod`에는 이 결정으로 lifecycle Provider adapter를 제공하지 않는다. lifecycle Controller와
  Application Service가 활성인데 해당 Port 구현이 없거나, adapter가 둘 이상이거나, sandbox와
  scripted 조건이 겹치거나, `live_sk_`가 들어오면 startup을 실패시킨다. Bean 부재나 Provider
  실패를 `@ConditionalOnMissingBean` scripted/fake/no-op adapter로 자동 대체하지 않는다. local
  scripted 결과는 테스트 capability일 뿐 제품 Provider 선택·API provider 값·운영 fallback이
  아니다.
- **Rationale:** 민감 결제정보의 저장 책임을 피하고 PG tokenization 경계를 명확히 한다.
- **Affected Contexts:** Payment, Identity
- **Affected Aggregates:** PaymentMethod
- **Required Tests:**
  - 다른 사용자의 결제수단 사용 거부
  - 민감 필드가 Entity·로그·API에 존재하지 않는지 검증
  - 고객용 등록 schema가 `authKey` 외의 카드번호·유효기간·생년월일·카드 비밀번호·CVC를
    받지 않으며 unknown field를 거부함
  - 공개·운영 profile에 카드 원문 기반 등록 endpoint와 request DTO가 없음
  - 고객 request로 임의 provider를 선택할 수 없고 제품 row가 `TOSS_PAYMENTS`로 고정됨
  - 운영 profile에서 scripted local adapter 선택과 Provider 미설정의 startup failure
  - alias 길이·control character, brand 길이와 last4 숫자 4자리 경계
  - Provider 표시 결과 누락·불일치에서 placeholder·token 파싱·고객값 fallback 없는 명시적 실패
  - Entity·OpenAPI·response에 expiry month/year와 내부 token/provider customer reference 부재
  - customer별 active default 0..1, 동시 등록·default 변경·deactivation의 unique 수렴
  - default 폐기 시 원자적 해제와 다른 결제수단 자동 승격 부재
  - 결제 승인 request의 paymentMethodId 누락을 default로 보충하지 않음
  - default PUT의 same-key replay·cross-target conflict와 오래된 retry의 현재 default 비변경
  - test harness가 네 gate 중 하나라도 불충족하면 합성 값 생성과 Provider 호출 없이 실패함
  - 합성 카드번호·유효기간·생년월일·카드 비밀번호가 source·fixture·설정·seed·DB·관측
    데이터에 없고 발급 뒤 참조가 남지 않음
  - 공개·내부 HTTP route에서 synthetic issuance를 호출할 수 없음
  - DELETE 의도 commit 뒤 Provider 호출 전·중 신규 결제 선택 거부
  - Provider 폐기 성공, timeout·응답 유실과 Provider 성공 뒤 DB result 저장 실패의 상태 수렴
  - `DEACTIVATION_UNKNOWN/RECONCILING/MANUAL_REVIEW`를 성공이나 `ACTIVE`로 투영하지 않음
  - 검증된 `BILLING_DELETED` 중복·지연·순서 역전의 단일 `DEACTIVATED` 전이
  - 서명 실패·W1/W2 DB 실패·0건/다건 mapping에서 무변경과 명시적 실패·수동 검토,
    W2 terminal commit 전 2xx 부재
  - webhook 처리에서 raw payload·token·provider customer reference 비노출
  - Payment Tx1·deactivation Tx D1 동시 실행의 먼저 commit한 경계와 단일 snapshot
  - deactivation 뒤 기존 APPROVING/UNKNOWN/RECONCILING Payment의 같은 snapshot 수렴
  - snapshot 누락·불일치에서 current PaymentMethod fallback·새 승인 부재
  - 같은 token의 exact ACTIVE binding 재처리와 다른 owner/reference/metadata/state 충돌
  - token fingerprint lock 아래 동시 registration의 단일 row와 overwrite·재활성화 부재
  - same-key/same-payload 등록 replay, cross-payload·cross-key same-authKey conflict
  - Provider 호출 claim 전·후 process loss와 timeout에서 authKey 재전송·성공 추정 부재
  - lookup 미지원 unknown의 Provider 추가 호출 없는 MANUAL_REVIEW와 retention guard
  - 폐기 same-key replay·cross-target conflict와 claim 뒤 Provider DELETE 재호출 부재
  - 폐기 timeout·응답 유실 뒤 96시간 내 `BILLING_DELETED` 수렴 또는 기한 만료 MANUAL_REVIEW
  - registration/deactivation Port 닫힌 결과 전 행과 미등록 code의 Unknown fail-closed
  - 설정·인증 결함의 고객 거절 변환 부재와 raw Provider message 비노출
  - local/test, toss-sandbox, prod profile 조합의 단일 adapter 또는 fail-start
  - Port 부재·다중 bean·live key에서 scripted/fake/no-op 자동 fallback 부재
  - 폐기된 token 사용 거부
  - Provider token 중복 등록 정책
  - provider 참조 값이 API 응답·로그·metric tag·AuditRecord에 없음
  - provider 참조 값의 무작위 생성과 사용자 식별자·이메일·전화번호 비파생
  - `TOSS_PAYMENTS` row의 참조 값 필수, 다른 provider row의 null 강제와 legacy backfill 불필요
  - 등록 retry·timeout 전후 같은 registration 시도의 provider 참조 값 불변
  - provider 참조 값 일치가 다른 member의 결제수단 사용을 허용하지 않음
  - 참조 값을 요구하는 provider에서 값이 없으면 Provider 호출 없이 명시적 실패
- **ADR Required:** Yes — 결제수단 tokenization과 저장 금지 데이터, [ADR-079](../adr/ADR-079-payment-method-token-management.md)
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
  `PLATFORM_OPERATOR`의 1..160자 reason과 1..20개의 evidence reference, signed requested effect,
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

# G. 고객 결제 제품 정책

## BR-33 고객 일회성 결제창과 서버 승인

- **Status:** Accepted for MVP
- **Decision:** 고객의 1원 이상 선주문은 Toss Web SDK V2 Standard Payment Window의
  one-time `CARD` 통합결제창으로 인증한다. 서버가 Order snapshot에서 amount(KRW),
  providerOrderId, opaque customerKey와 orderName을 생성·고정하고 브라우저는 그 값을
  그대로 사용한다. success callback의 paymentKey/orderId/amount는 서버가 Payment 시도와
  exact match를 확인한 뒤 `/v1/payments/confirm`으로 승인한다. fail callback은 승인 API를
  호출하지 않는다. 기존 PaymentMethod는 등록·조회·폐기 lifecycle만 유지하며 checkout
  인증 소스, 기본 선택 또는 fallback이 아니다. billing, Payment Widget, BrandPay,
  가상계좌와 지급대행은 MVP 결제 범위가 아니다.
- **Failure Policy:** Provider confirm을 시작한 뒤 timeout·응답 유실·5xx·파싱 실패 또는
  로컬 결과 commit 실패가 발생하면 성공/거절로 추정하거나 새 key로 승인하지 않는다.
  같은 Payment의 stable key와 paymentKey/providerOrderId 조회로 수렴하고 유한 예산 뒤에도
  불명확하면 `MANUAL_REVIEW`다.
- **Rationale:** 비구독 주문을 자동결제 계약에서 분리하고, 고객 브라우저 인증과 서버의
  금액·주문 검증 및 기존 Payment reconciliation을 함께 유지하기 위함이다.
- **Affected Contexts:** Ordering, Payment, Settlement, Loyalty, Operations, Customer Web
- **Affected Aggregates:** Order, Payment, Refund, SettlementAdjustment, PointReservation
- **Required Tests:**
  - prepare replay와 Order당 동시 Payment 시도
  - callback amount/orderId/paymentKey 위변조·교차 owner·replay
  - fail callback의 Provider confirm 0회
  - confirm timeout/응답 유실/Provider 성공 뒤 local commit 실패와 조회 수렴
  - 고객 전액 취소와 점주·운영자 부분 환불의 금액·정산·포인트 tie-out
  - one-time 경로의 PaymentMethod 조회/Port 호출 0회
- **ADR Required:** Yes — [ADR-080](../adr/ADR-080-toss-v2-one-time-payment-window.md)
- **Revisit Conditions:** 결제수단 UI 제품, Payment Widget/BrandPay, verified webhook 또는
  다중 Provider routing 요구가 확정될 때

## BR-34 고객·점주 로그인 식별자와 계정 존재 응답

- **Status:** Accepted for MVP
- **Decision:** P0 고객과 점주 로그인 식별자는 이메일·전화번호가 아닌 **사용자명 ID**다. 고객은
  가입할 때 정하고 점주는 운영자가 발급한다. 두 actor는 서로 분리된 namespace를 사용하므로 같은
  canonical 문자열이 고객 계정과 점주 계정에 각각 존재할 수 있다. 두 actor 모두 canonical 값은
  ASCII 소문자 5~32자이며 첫·끝 문자는 영문 소문자 또는 숫자, 중간에는 영문 소문자·숫자·점(`.`)·
  밑줄(`_`)·하이픈(`-`)만 허용한다. 입력은 앞뒤 공백을 제거하고 ASCII 대문자를 소문자로 바꾼 뒤
  검증·저장한다. Unicode 호환 정규화나 비슷한 글자 추론은 하지 않는다.
- **Registration Response:** 이미 사용 중인 사용자명이면 `409 LOGIN_ID_UNAVAILABLE`을 반환한다.
  사용자명 점유 여부는 외부 이메일·전화번호의 보유 사실을 드러내지 않으므로, 가입 성공과 중복을
  같은 응답으로 숨겨 사용자가 생성 실패를 알 수 없게 만들지 않는다. 별도 availability endpoint는
  P0에 두지 않는다.
- **Login Response:** 존재하지 않는 사용자명, 잘못된 비밀번호와 잠금 상태는 모두 동일한
  `401 AUTHENTICATION_FAILED` 응답을 사용한다. 응답 시간 차이를 줄이기 위해 계정이 없어도 같은
  password hash 검증 비용을 사용한다.
- **Recovery Scope:** P0 CustomerAccount와 MerchantAccount에는 검증되지 않은 이메일·전화번호를
  로그인 키나 복구 수단으로 저장하지 않는다. 비밀번호 재설정은 검증된 전달 채널과 복구 정책을
  도입하는 후속 범위다.
- **Rationale:** 외부 Provider 없이 P0를 구현하면서 타인의 이메일 선점과 검증되지 않은 연락처 기반
  계정 복구를 피하고, 동시에 가입 실패를 사용자가 바로 수정할 수 있게 한다.
- **Affected Contexts:** Identity, Customer Web, Merchant Console, Operations, Support
- **Affected Aggregates:** CustomerAccount, MerchantAccount
- **Required Tests:**
  - 대소문자만 다른 사용자명의 중복과 동시 가입에서 한 건만 성공
  - 점주 계정 발급에서 고객과 같은 canonical 규칙을 적용하고 actor별 namespace를 분리
  - 허용 문자·길이·경계 문자와 공백 정규화 계약
  - 없는 사용자명·잘못된 비밀번호·잠금 상태의 동일 응답과 유사한 hash 검증 비용
  - 검증되지 않은 이메일·전화번호가 CustomerAccount와 Session에 저장되지 않는지 검증
- **Implementation Evidence (2026-08-13):** `productization-30`은 고객 namespace의 canonical
  사용자명 Unique Constraint, 고정 Argon2id dummy PHC 검증과 동일한 `401 AUTHENTICATION_FAILED`
  응답을 구현했다. 대소문자·공백 canonical 동시 가입은 CustomerAccount와 PointAccount 각 한 건으로
  수렴하고, 가입 중복만 `409 LOGIN_ID_UNAVAILABLE`을 반환한다. 점주 namespace 구현은
  `productization-40` 소유다.
- **ADR Required:** Yes — [ADR-092](../adr/ADR-092-hybrid-authentication.md)
- **Revisit Conditions:** 검증된 이메일·휴대전화 로그인, 비밀번호 재설정 또는 계정 복구 요구가
  확정될 때

## BR-35 고객·점주 비밀번호와 로그인 제한

- **Status:** Accepted initial policy
- **Password Input:** 고객·점주 비밀번호는 15~128 Unicode code point, UTF-8 최대 512 byte다.
  공백과 Unicode를 허용하고 대소문자·숫자·특수문자 조합 규칙을 요구하지 않는다. 앞뒤 공백 제거,
  Unicode 정규화 또는 조용한 자르기를 하지 않고 사용자가 입력한 byte sequence를 그대로 검증한다.
  사용자명과 동일한 비밀번호 및 versioned local common-password blocklist의 exact match는 거부한다.
  인증된 self-service 비밀번호 변경은 새 값이 현재 값과 같은 byte sequence이면 거부한다. 이 거부는
  account state, credentialVersion, Session과 AuditRecord를 바꾸거나 새 Session을 발급하지 않는다.
- **Password Storage:** Argon2id PHC 문자열로만 저장한다. 초기 파라미터는 memory 19 MiB,
  iterations 2, parallelism 1이며 각 hash는 고유 salt를 사용한다. 구현 환경에서 같은 조건으로 검증
  지연을 측정하고 1초 이상이면 파라미터를 조용히 낮추지 않고 문서·정책을 재검토한다. 지원되지 않는
  알고리즘이나 잘못된 파라미터는 기동 실패다. 평문·복호화 가능한 값·별도 salt 컬럼은 저장하지 않는다.
- **Account Limit:** actor 종류별 사용자명에서 15분 관찰 창 안에 5번째 인증 실패가 발생하면 해당
  계정의 `lockedUntil`을 15분 뒤로 설정한다. 잠금 transaction에서 `credentialVersion`을 증가시켜
  기존 Session을 무효화한다. CustomerAccount는 잠금 동안 `LOCKED` 상태를 사용하고, 잠금 시각이 지난
  다음 정상 로그인에서 `ACTIVE`로 복귀한다. MerchantAccount는 `INITIAL_PASSWORD` / `ACTIVE` /
  `EXPIRED` lifecycle을 바꾸지 않고 `lockedUntil`을 시간 제한 overlay로 적용한다. 따라서 만료 뒤
  정상 로그인은 잠기기 전 lifecycle을 그대로 사용한다. 어느 경우에도 이전 Session의 version은
  되살리지 않는다. 성공 로그인은 그 계정의 실패 창만 초기화한다. 운영자 조기 해제는
  `lockedUntil`만 지우며 AuditRecord를 함께 커밋한다.
- **IP Limit:** actor 종류별 source IP에서 15분 안에 실패 30건이면 그 IP의 해당 로그인 endpoint를
  15분 차단하고 `429 AUTHENTICATION_RATE_LIMITED`와 `Retry-After`를 반환한다. 성공 로그인 하나로 IP
  실패 창을 초기화하지 않는다. 신뢰 proxy 목록 밖의 forwarding header는 IP 판정에 사용하지 않는다.
  direct peer가 configured trusted proxy CIDR 안에 있고 forwarding header가 있으면 전체
  `X-Forwarded-For` chain을 오른쪽부터 검사해 trusted proxy hop을 모두 제거하고, 처음 만나는 untrusted
  literal address를 source IP로 사용한다. header가 없으면 direct peer를 사용한다. trusted peer가 보낸
  비어 있거나 malformed chain은 `400 INVALID_REQUEST`로 거부하며, 모든 hop이 trusted이면 direct peer로
  보수적으로 귀결한다. trusted proxy deployment는 관측한 peer를 chain 끝에 append하거나 header 전체를
  overwrite해야 하며, application은 leftmost 값을 단독으로 신뢰하지 않는다.
- **Merchant Temporary Password:** 점주 임시 비밀번호는 발급 시각부터 24시간 유효하다. 운영자는
  만료 전후 재발급할 수 있으며 재발급 transaction은 새 Hash·만료 시각·`credentialVersion`과
  `AuditRecord`를 함께 커밋한다.
- **Attempt Data:** 사용자명과 IP 원문은 attempt table에 저장하지 않는다. actor 종류, scope 종류와
  별도 설정한 keyed HMAC-SHA-256만 저장한다. HMAC key가 없거나 유효하지 않으면 기동 실패다. attempt
  row는 마지막 갱신 24시간 뒤 `(updated_at, id)` 순서의 bounded worker가 삭제한다. 정리 실패는 인증을
  우회하지 않으며 retry·metric·log로 남긴다.
- **Rationale:** MFA와 비밀번호 재설정이 없는 P0에서 짧은 비밀번호와 무제한 추측을 허용하지 않되,
  영구 잠금으로 공격자가 계정을 계속 사용할 수 없게 만드는 DoS를 피한다.
- **Affected Contexts:** Identity, Customer Web, Merchant Console, Operations
- **Affected Aggregates:** CustomerAccount, MerchantAccount, LoginAttempt
- **Required Tests:**
  - 15분 창의 4회/5회 경계, 15분 잠금 전·경계·후와 동시 실패
  - 계정 잠금 뒤 기존 Session의 `credentialVersion` 불일치 401
  - `INITIAL_PASSWORD`와 `ACTIVE` 점주가 잠금 만료 뒤 각각 원래 lifecycle로 로그인
  - IP 29회/30회 경계, actor 종류 분리, 신뢰하지 않는 forwarding header 거부
  - attacker-supplied prefix가 있는 forwarding chain, 다중 trusted proxy hop, IPv4/IPv6 혼합,
    trusted peer의 malformed chain과 untrusted direct peer의 spoofed forwarding header
  - 성공 로그인 후 계정 창 초기화와 IP 창 비초기화
  - 임시 비밀번호 발급 후 24시간 -1ns/at/+1ns와 재발급 감사 원자성
  - Unicode·공백·15/128 code point·512 byte 경계와 비밀번호 비정규화
  - `INITIAL_PASSWORD`와 `ACTIVE`에서 현재·새 비밀번호가 같은 경우의 정책 거부와 account state,
    credentialVersion, 기존 Session, AuditRecord, 새 Session 무변경
  - attempt 원문 비저장, HMAC key 누락 기동 실패와 24시간 보존 worker 재실행
- **Implementation Evidence (2026-08-13):** 고객 계정은 Argon2id
  `m=19456,t=2,p=1`, actor/scope별 HMAC attempt row, 5회 계정 잠금·30회 IP 차단, 정확한 15분 경계와
  24시간 bounded retention을 사용한다. 동시 실패는 PostgreSQL row lock으로 count를 보존하고,
  자격증명 snapshot이 검증 중 바뀌면 attempt와 Session 없이 401로 rollback한다. retention·Session
  저장 실패는 예외를 다시 던지고 인증 fallback을 만들지 않는다. 점주 temporary-password lifecycle은
  `productization-40` 소유다.
- **Implementation Evidence (2026-08-14):** trusted peer의 complete `X-Forwarded-For` chain은
  right-to-left로 parse한다. attacker prefix, multiple trusted hops, IPv4/IPv6 mixed chain,
  malformed chain과 untrusted direct peer의 spoofed header를 unit regression으로 검증했다.
- **Implementation Evidence (2026-08-14):** Merchant self-change에서 같은 current/new password는
  `PASSWORD_POLICY_VIOLATION`으로 거부한다. INITIAL_PASSWORD와 ACTIVE 모두 state, credentialVersion,
  기존 Session, AuditRecord와 새 Session이 바뀌지 않음을 PostgreSQL HTTP 통합 테스트로 검증했다.
- **ADR Required:** Yes — [ADR-093](../adr/ADR-093-merchant-credential-lifecycle.md),
  [ADR-094](../adr/ADR-094-browser-session-security.md)
- **Revisit Conditions:** MFA 또는 복구 채널 도입, credential-stuffing 관측치, hash 지연·메모리 측정,
  로그인 지원 문의가 초기 정책 조정을 요구할 때

## BR-36 고객·점주 브라우저 Session 수명과 동시 한도

- **Status:** Accepted initial policy
- **Customer Session:** 마지막 접근 후 7일 idle timeout, 인증 시각 후 30일 absolute timeout, 계정당
  최대 5개 Session이다.
- **Merchant Session:** 마지막 접근 후 30분 idle timeout, 인증 시각 후 12시간 absolute timeout,
  계정당 최대 3개 Session이다. `INITIAL_PASSWORD` Session도 같은 수명을 쓰지만 BR-35와 ADR-093의
  password change gate를 적용한다.
- **Boundary:** `now >= idleDeadline` 또는 `now >= absoluteDeadline`이면 만료다. 요청 중 last access를
  갱신해도 absolute deadline은 이동하지 않는다. 만료 Session은 401이며 새 Session이나 익명 actor로
  자동 교체하지 않는다.
- **Concurrent Login:** 한도에서 새 로그인이 성공하면 `(authenticatedAt, sessionId)` 오름차순으로
  가장 오래된 Session부터 필요한 수만큼 폐기한다. 같은 계정 로그인은 account row lock으로
  직렬화하고, 기존 Session 폐기와 새 Session 저장은 같은 PostgreSQL transaction에 참여시킨다.
  어느 하나라도 실패하면 transaction을 rollback하고 새 로그인을 503으로 실패시킨다. 상한을 조용히
  초과하거나 in-memory registry로 대체하지 않는다.
- **Credential Change:** BR-35의 `credentialVersion` 증가가 수명·동시 한도보다 우선한다. version이
  다른 Session은 행이 남아 있어도 즉시 401이며 동시 Session 수 계산에서도 활성으로 보지 않는다.
- **Rationale:** 고객은 일상 주문에서 반복 로그인을 줄이고, 금전·매장 운영 기능을 가진 점주는 근무
  단위로 더 짧게 제한한다.
- **Affected Contexts:** Identity, Customer Web, Merchant Console
- **Affected Aggregates:** CustomerAccount, MerchantAccount, BrowserSession
- **Required Tests:**
  - 고객 7일/30일, 점주 30분/12시간의 -1ns/at/+1ns 경계
  - idle 갱신 후에도 absolute deadline이 이동하지 않는지 검증
  - 고객 6번째·점주 4번째 로그인에서 가장 오래된 Session만 401
  - 같은 계정 동시 로그인에서 상한 초과가 없는 PostgreSQL 동시성 테스트
  - 기존 Session 삭제·새 Session 저장 각각의 장애가 전체 rollback과 503을 만드는지 검증
- **ADR Required:** Yes — [ADR-094](../adr/ADR-094-browser-session-security.md)
- **Revisit Conditions:** 실제 재로그인률, 활성 Session 수, 탈취 사고, Session DB 지연과 사용자 지원
  데이터를 측정한 뒤 조정

## BR-37 고객 알림함 보존

- **Status:** Accepted initial policy
- **Decision:** `TRANSACTIONAL`과 `MARKETING` NotificationInboxItem을 모두 생성 시각부터 90일
  보존한다. 저장할 때 `retentionExpiresAt = createdAt + 90일`을 고정하며 읽음 처리, 수신 설정 변경,
  외부 Delivery 재시도는 기한을 연장하지 않는다.
- **Deletion:** Notification-owned worker가 기본 1시간마다 최대 100개 due row를
  `(retention_expires_at, id)` keyset 순서로 짧은 transaction에서 삭제한다. `now >=
  retentionExpiresAt`이 due 경계다. 실패는 retry·backlog metric·구조화 log로 남기고 빈 목록이나
  삭제 성공으로 표현하지 않는다.
- **Scope:** InboxItem은 거래 원장이나 감사 증거가 아니다. 항목 삭제 뒤에도 주문·환불의 보존과 조회
  정책은 바뀌지 않는다. NotificationDelivery 운영 데이터의 보존 기간도 이 정책으로 추론하지 않는다.
- **Rationale:** 알림함은 주문 내역을 대체하지 않는 편의 Projection이므로 분류별 기간을 나누지 않고
  저장 최소화와 단순한 삭제 경계를 택한다.
- **Affected Contexts:** Notification, Customer Web
- **Affected Aggregates:** NotificationInboxItem
- **Required Tests:**
  - 생성 후 90일 -1ns/at/+1ns 삭제 경계
  - 읽음·수신 설정·Delivery retry 뒤 retention 기한 불변
  - 한 번에 100개 bounded keyset 삭제와 worker 재실행
  - 삭제 실패 후 항목 보존·backlog 관측과 주문/환불 데이터 비삭제
- **ADR Required:** Yes — [ADR-089](../adr/ADR-089-purpose-based-retention-legal-hold-and-deletion.md),
  [ADR-104](../adr/ADR-104-notification-inbox.md)
- **Revisit Conditions:** 알림함 사용률, 저장량, 고객 문의 또는 법률 검토가 다른 기간을 요구할 때

## BR-38 매장 부분 환불 실행 권한

- **Status:** Accepted for P0
- **Decision:** `ACTIVE`한 `OWNER`와 `STAFF` StoreMembership은 자신이 속한 매장의 주문에 대해
  품목 부분 환불 preview와 실행을 요청할 수 있다. 역할만으로는 충분하지 않으며, 요청 시점의
  membership과 Order의 `storeId`가 일치해야 한다. P0에서는 STAFF별 환불 금액 상한이나 점주 사전
  승인을 두지 않는다.
- **Command Contract:** 브라우저는 `paymentId`나 `orderLineId` UUID를 제출하지 않는다. 매장 범위
  `orderReference`와 화면에 반환된 line reference·수량만 보내며 서버가 결제, 기존 성공·불명 환불,
  금액 배분과 허용 수량을 다시 계산한다. 실행에는 8~128자의 `Idempotency-Key`와 trim 뒤 1~500자의
  사유가 필수다. preview 결과는 승인 금액이 아니며 실행 transaction이 같은 불변식을 다시 검증한다.
- **Failure Policy:** membership이 없거나 `REVOKED`이면 `403`, 주문·결제·환불 상태가 preview 이후
  달라졌으면 `409 REFUND_PREVIEW_STALE`로 실패한다. Provider 결과가 불명확하면 기존 Refund를
  `UNKNOWN` 또는 reconciliation 상태로 남기며 새 Refund나 Provider 취소를 자동 생성하지 않는다.
  membership 조회, Refund·원장 저장 또는 Audit 저장 실패는 성공으로 응답하지 않는다.
- **Audit:** preview 조회 자체는 금전 변경 Audit 대상이 아니지만 환불 실행은 actor, membership role,
  store, order, refund, 사유, 멱등 요청과 결과를 기존 금전성 Audit 계약에 남긴다. 자유 입력 사유를
  metric tag로 사용하지 않는다.
- **Rationale:** 실제 매장에서는 직원도 주문 문제를 즉시 해결해야 하므로 현재 코드의 OWNER·STAFF
  권한을 유지하되, 내부 UUID 입력과 역할만 확인하는 객체 수준 인가 누락을 제거한다.
- **Affected Contexts:** Identity, Ordering, Payment, Loyalty, Settlement, Merchant Console, Audit
- **Affected Aggregates:** StoreMembership, Order, Payment, Refund, PointAccount, SettlementItem
- **Required Tests:**
  - 같은 매장의 ACTIVE OWNER·STAFF preview와 실행 성공
  - 다른 매장, REVOKED membership, operation에 허용되지 않은 role과 `INITIAL_PASSWORD` 계정의 403
  - 다른 주문의 line reference, 초과 수량, preview 이후 선행 환불의 409
  - 같은 Idempotency-Key replay와 다른 payload 재사용의 부작용 0회
  - Provider timeout·응답 유실에서 중복 Provider 환불 0회와 reconciliation 수렴
  - Audit 저장 실패 시 Refund 실행 미확정과 503
- **ADR Required:** Yes — [ADR-108](../adr/ADR-108-merchant-partial-refund-preview.md)
- **Revisit Conditions:** STAFF 오조작·환불률 측정, 역할 세분화, 금액 상한, step-up 인증 또는 2인 승인
  요구가 생길 때

## BR-39 운영자 P0 조회 권한 분리

- **Status:** Accepted for P0
- **Decision:** Keycloak의 `PLATFORM_OPERATOR` 역할은 coarse gate로만 사용한다. 실패 작업 목록·상세는
  `REPROCESSING_CASE_READ`, 정산 대사 목록·상세는 `SETTLEMENT_RECONCILIATION_READ`, 감사 로그
  목록·상세는 `AUDIT_RECORD_READ`라는 서로 다른 active `OperatorPermissionGrant`를 요구한다. 한
  permission이나 역할로 다른 조회를 허용하지 않고, 어떤 값도 기본 grant로 seed하지 않는다.
- **Audit Read:** 감사 로그 조회는 trim 뒤 1~200자이고 control character가 없는
  `X-Access-Reason`을 필수로 받는다. permission 확인, 조회 Projection과 `AUDIT_RECORD_READ` 접근
  Audit 저장은 같은 local transaction에서 완료되어야만 응답한다. 접근 Audit에는 자유 입력 filter나
  원본 before/after payload를 복제하지 않는다. 실패 큐와 정산 대사 조회는 P0에서 매 요청 접근 Audit을
  추가하지 않지만 권한 거부와 조회 장애 metric·구조화 log를 남긴다.
- **Command Separation:** 세 permission은 읽기 전용이다. 재처리 실행, 정산 조정, 환불,
  permission 변경 또는 Audit 원본 수정 권한을 부여하지 않는다. 각 명령은 기존의 전용 permission,
  사유, 멱등성·승인 정책을 계속 요구한다.
- **Failure Policy:** role 또는 active grant가 없으면 `403`, grant·Audit·Projection 저장소 조회가
  실패하면 `503`이다. role, JWT claim, 다른 grant, cache 또는 빈 목록으로 대체하지 않는다.
- **Rationale:** 운영 화면 하나를 열기 위해 금융·보안 이력 전체를 함께 노출하지 않고, 직무별 최소
  권한과 즉시 revoke 경계를 유지하기 위함이다.
- **Affected Contexts:** Operations, Settlement, Payment, Notification, Security
- **Affected Aggregates:** OperatorPermissionGrant, ReprocessingCase, SettlementBatch, AuditRecord
- **Required Tests:**
  - 세 permission의 모든 교차 조합에서 해당 조회만 허용되는지 검증
  - `PLATFORM_OPERATOR` 역할만 있거나 revoked grant인 경우 403
  - grant 조회 장애가 빈 page나 role fallback이 아닌 503인지 검증
  - 감사 조회 reason 누락·공백·201자·control character 거부
  - 감사 Projection 또는 접근 Audit 저장 실패 시 응답 body 미반환과 전체 rollback
  - 읽기 permission으로 재처리·정산 조정·permission lifecycle 명령이 실행되지 않는지 검증
- **ADR Required:** Yes — [ADR-069](../adr/ADR-069-operator-permission-grants-and-audited-policy-read.md)
- **Revisit Conditions:** 직무 체계 변경, 대량 조회에 따른 접근 Audit 부하, 별도 SIEM·감사 저장소 또는
  정산 운영자 전용 인증 주체가 도입될 때

## BR-40 홈 최근 주문 매장 선정

- **Status:** Accepted for P0
- **Decision:** 고객의 최근 주문 매장은 조회 시점의 Order 상태가 `PAID`, `ACCEPTED`, `PREPARING`,
  `READY`, `COMPLETED`인 주문만 대상으로 한다. `PENDING_PAYMENT`, `EXPIRED`, `CANCELLED` 주문은
  제외한다. 매장별 `max(order.createdAt)`을 `lastOrderedAt`으로 사용해 한 번만 반환하고,
  `(lastOrderedAt DESC, storeId ASC)`로 결정적으로 정렬한다.
- **Recommendation Merge:** 홈 추천은 즐겨찾기, 최근 주문 매장, 좌표가 있을 때 nearby 순으로
  중복을 제거한다. 좌표가 없으면 즐겨찾기와 최근 주문 매장까지만 반환한다. 앞 단계에 포함된 매장을
  다음 단계가 다시 추가하지 않으며 첫 번째 근거를 응답의 recommendation reason으로 사용한다.
- **Recommendation Coordinates:** 추천 요청의 `latitude`와 `longitude`는 함께 제공하거나 둘 다
  생략한다. 좌표 쌍이 있고 `radiusMeters`를 생략하면 nearby 단계는 기본 반경 `3,000m`를 사용한다.
  반경은 좌표 쌍과 함께만 허용하며 `1..10,000m` 밖의 값, 좌표 하나만 제공한 요청, 반경만 제공한
  요청은 `400`이다. 좌표가 모두 없으면 nearby 단계를 실행하지 않는다.
- **Boundary:** Ordering은 customer scope로 eligible store ID와 `lastOrderedAt`만 Projection하여
  Discovery에 제공하고, Discovery가 현재 노출 가능한 매장 정보를 hydrate한다. 존재하지 않거나 현재
  노출 불가능한 매장은 결과에서 제외하되 주문 스냅샷이나 상태를 수정하지 않는다.
- **Failure Policy:** 최근 주문 Projection이나 매장 hydrate 실패를 빈 recent 목록 또는 거리순 결과로
  대체하지 않고 홈 추천 요청을 `503`으로 실패시킨다. 고객 주문 이력을 Discovery table로 복제하지 않는다.
- **Rationale:** 실제 결제·운영 흐름에 들어간 매장은 편의 추천에 반영하면서 결제 전 포기, 만료,
  취소·거절 경험을 자동 재추천하지 않기 위함이다.
- **Affected Contexts:** Ordering, Discovery, Customer Web
- **Affected Aggregates:** Order; 추천은 Query Projection이며 새 Aggregate가 아니다.
- **Required Tests:**
  - 상태별 포함·제외와 상태 변경 후 다음 조회 반영
  - 같은 매장의 여러 주문이 최신 `createdAt` 한 건으로 합쳐지는지 검증
  - 동률 `lastOrderedAt`의 `storeId` tie-breaker
  - 즐겨찾기·최근·nearby 중복 제거, 좌표 없는 순서와 좌표 쌍의 3,000m 기본 반경
  - 좌표 하나만 제공하거나 반경만 제공한 요청의 400
  - Ordering 또는 Discovery 조회 장애의 503과 빈 결과 fallback 부재
- **ADR Required:** Yes — [ADR-103](../adr/ADR-103-store-search-strategy.md)
- **Revisit Conditions:** 추천 제외/숨김 기능, 취소 사유별 재노출, 고객 행동 데이터 기반 ranking 또는
  별도 추천 Read Model이 필요해질 때

## BR-41 운영자 웹 Keycloak 로그인

- **Status:** Accepted for P0
- **Decision:** 운영자 웹은 Keycloak public client의 Authorization Code Flow와 PKCE `S256`으로
  로그인한다. 브라우저는 승인된 same-origin callback에서 code, state와 nonce를 검증한 뒤 token을
  교환하고, access token을 JavaScript 메모리에만 보관하여 기존 Operations Resource Server에 Bearer로
  전송한다. Implicit Flow, Resource Owner Password, client secret이 있는 SPA와 토큰 직접 입력 UI는
  허용하지 않는다.
- **Browser Storage:** access token, ID token과 refresh token을 `localStorage`, `sessionStorage`,
  IndexedDB 또는 일반 Cookie에 저장하지 않는다. redirect 왕복에 필요한 state, nonce, code verifier와
  검증된 same-origin return path만 일회성 `sessionStorage`에 둘 수 있으며 callback 성공·실패 뒤 즉시
  삭제한다. P0는 `offline_access`를 요청하거나 refresh token을 지속 저장하지 않는다.
- **Runtime Configuration:** Public endpoint는 검증된 issuer, public client ID, callback path와 scope만
  반환하며 secret이나 관리 endpoint를 반환하지 않는다. issuer/client/callback 설정이 누락·불일치하면
  애플리케이션 기동 또는 로그인 시작을 명시적으로 실패시킨다. Keycloak callback allowlist는 정확한
  production origin/path만 허용하고 wildcard origin을 사용하지 않는다.
- **Expiry and Logout:** access token 만료 또는 401이면 메모리 credential을 지우고 보호 화면을
  중단한 뒤 Keycloak SSO 재인증으로 보낸다. 이전 API를 익명·stale cache로 계속 사용하지 않는다.
  로그아웃은 로컬 메모리·일회성 state를 지우고 Keycloak의 검증된 end-session 흐름으로 이동한다.
- **Rationale:** 운영자 신원과 기존 JWT Resource Server·permission grant 경계를 유지하면서 사람이
  토큰을 복사하지 않는 표준 브라우저 로그인 흐름을 제공하기 위함이다.
- **Affected Contexts:** Operations Console, Security, Operations, Support
- **Affected Aggregates:** 없음. Keycloak 신원과 OperatorPermissionGrant는 기존 소유권을 유지한다.
- **Required Tests:**
  - state·nonce 불일치, code 재사용, callback error와 open redirect 거부
  - PKCE `S256` 사용과 client secret·implicit/password flow 부재
  - callback 성공·실패 후 일회성 storage 삭제와 token 영구 storage 0건
  - token issuer·audience·expiry 검증 및 만료 뒤 보호 화면·API 중단
  - Keycloak logout 뒤 이전 token 재사용 거부와 Token Editor 부재
  - runtime config 누락·callback mismatch가 fake config나 local token fallback이 아닌 명시적 실패
- **ADR Required:** Yes — [ADR-092](../adr/ADR-092-hybrid-authentication.md)
- **Revisit Conditions:** backend-for-frontend, 운영자 Session, native client, refresh token rotation 또는
  조직 SSO 정책 변경이 요구될 때

## BR-42 고객 가입과 PointAccount 원자 생성

- **Status:** Accepted for P0
- **Decision:** 고객 가입은 `CustomerAccount`와 available/reserved/recovery-pending이 모두 0인
  `PointAccount`를 같은 PostgreSQL transaction에서 정확히 한 번 생성한다. Identity Application
  Service가 가입 경계를 조정하고 Loyalty의 public provisioning port가 새 customer ID를 받아
  `MANDATORY` transaction에 참여한다. 두 Aggregate는 ID로만 연결하며 JPA 객체 연관관계나 cascade를
  만들지 않는다.
- **Failure Policy:** PointAccount 저장, Unique Constraint 또는 Loyalty dependency가 실패하면
  CustomerAccount 생성도 rollback하고 가입 요청을 503으로 실패시킨다. CustomerAccount만 있고
  PointAccount가 없는 상태를 잔액 0, in-memory 계정 또는 GET의 lazy-create로 대체하지 않는다. 그런
  무결성 손상은 `/me/points`를 503으로 실패시키고 운영 진단 대상으로 남긴다.
- **Existing Data:** P0 CustomerAccount schema는 신규이므로 최초 migration에는 제품 고객 backfill이
  없다. 이후 account import 또는 별도 migration이 생기면 PointAccount coverage preflight와 같은
  transaction의 provisioning을 필수로 한다. Support profile을 CustomerAccount로 추론하지 않는다.
- **Rationale:** 가입 직후 포인트 화면의 0원이 실제 원장 상태가 되게 하고, 첫 조회가 쓰기를 수행하거나
  첫 적립 전 계정 없음이라는 별도 제품 상태를 만들지 않기 위함이다.
- **Affected Contexts:** Identity, Loyalty, Customer Web
- **Affected Aggregates:** CustomerAccount, PointAccount
- **Required Tests:**
  - 가입 성공 후 두 Aggregate가 한 건씩 존재하고 PointAccount 세 잔액이 0인지 검증
  - 같은 사용자명 동시 가입에서 CustomerAccount와 PointAccount가 각각 한 건인지 검증
  - Loyalty 저장·flush 장애가 CustomerAccount까지 rollback하고 503인지 검증
  - CustomerAccount만 있는 손상 fixture의 `/me/points`가 0/404/lazy-create가 아닌 503인지 검증
  - Application/Domain에 cross-Context JPA 연관관계가 없는지 ArchUnit·Modulith 검증
- **Implementation Evidence (2026-08-13):** `productization-30`은 Identity transaction에서 Loyalty
  `CustomerPointAccountProvisioningOperations`의 `MANDATORY` 구현을 호출하고 두 Aggregate를 ID로만
  연결한다. 성공·동시 canonical 가입, Loyalty write/flush 실패와 선행 PointAccount Unique 충돌에서
  각각 exactly-one 또는 전체 rollback·503을 PostgreSQL로 검증했다. actor-scoped `/me/points`와
  손상 fixture의 `POINT_ACCOUNT_INTEGRITY_FAILURE` projection은
  `productization-80-customer-web-p0-integration` 소유이며 이 구현 결과에 포함하지 않는다.
- **ADR Required:** Yes — [ADR-109](../adr/ADR-109-customer-point-account-provisioning.md)
- **Revisit Conditions:** 외부 Identity import, 고객 탈퇴·익명화, Loyalty 서비스 분리 또는 가입과 계정
  provisioning의 비동기화가 필요해질 때

## BR-43 운영 실패 큐의 소유 Context 연합 조회

- **Status:** Accepted for P0
- **Decision:** P0 운영 실패 큐는 `PAYMENT`, `NOTIFICATION`, `SETTLEMENT` 세 유형으로 분리한다.
  Payment와 Notification은 자기 table의 상태·실제 시도 횟수·발생/갱신 시각·correlation ID를 public
  DTO Projection port로 반환한다. Operations는 자신이 이미 소유한 `operations_reprocessing_case`를
  closed case type으로 조회해 각 유형에 합친다. 정산 P0 실패의 명시적 source는
  `SETTLEMENT_LATE_ITEM`, `SETTLEMENT_ADJUSTMENT`, `SETTLEMENT_DISPUTE` ReprocessingCase이며 존재하지
  않는 Settlement failure ledger를 새로 만들지 않는다.
- **Source Map:** PAYMENT는 Payment attempt/reconciliation과 `PAYMENT_RECONCILIATION`,
  `PAYMENT_CANCELLATION_SETUP` case, NOTIFICATION은 NotificationDelivery와
  `NOTIFICATION_DELIVERY` case, SETTLEMENT는 위 세 `SETTLEMENT_*` case다. `EVENT_PUBLICATION`과
  `ACCEPTANCE_TIMEOUT_WORK`는 P0 세 tab에 억지로 넣지 않고 P1이다. `operations_reprocessing_case`는
  owner 상태의 mirror가 아니라 수동 복구가 필요한 명시적 case source로만 사용한다.
- **Pagination:** 각 유형은 독립된 signed cursor와 정렬을 사용한다. 전 유형을 하나의 시간순 page와
  cursor로 합치지 않는다. summary와 exact correlation ID 검색만 세 port를 호출해 조합하며, 한 port가
  실패하면 부분 결과·stale cache·0건으로 대체하지 않고 전체 요청을 503으로 실패시킨다.
- **Normalized Contract:** 각 item은 opaque `workReference`, 원본 `sourceState`, 화면용
  `attentionState`(`RETRY_SCHEDULED | FAILED | UNKNOWN | RECONCILING | MANUAL_REVIEW`), 실제
  `attemptCount` 또는 `attemptCountAvailable=false`, occurredAt, updatedAt, correlationId와 서버 계산
  `allowedActions`를 반환한다. 원본에 시도 횟수가 없으면 0으로 꾸미지 않는다.
- **Command Boundary:** 연합 조회는 읽기 전용이다. `allowedActions`는 이미 존재하고 현재 권한으로
  실행 가능한 전용 command를 설명할 뿐, 목록 조회가 새 재처리 권한을 부여하거나 source 상태를
  직접 수정하지 않는다. 실행은 기존 stable key·reason·permission·reconciliation 정책을 적용한다.
- **Authorization:** 목록·상세·summary·correlation 검색은 BR-39의 active
  `REPROCESSING_CASE_READ`를 요구한다. 다른 read grant나 `PLATFORM_OPERATOR` 역할만으로 허용하지 않는다.
- **Rationale:** 운영 화면 편의를 위해 금융·전달·정산 실패의 source of truth를 복제하지 않고, 각
  Context의 실제 상태와 장애 의미를 그대로 표시하기 위함이다.
- **Affected Contexts:** Operations, Payment, Notification, Settlement
- **Affected Aggregates:** Payment, NotificationDelivery, ReprocessingCase, SettlementBatch;
  목록은 Query Projection이며 새 Aggregate가 아니다.
- **Required Tests:**
  - 유형별 상태 mapping과 실제 attempt count/availability 계약
  - 각 cursor의 type·filter binding과 page 누락·중복 부재
  - summary/correlation search에서 한 port 장애가 부분 결과가 아닌 503인지 검증
  - source map 밖 case가 P0 tab에 섞이지 않고 원본 상태 변경이 다음 조회에 반영되는지 검증
  - Payment/Notification 상태와 같은 source의 case가 중복 카드가 아니라 하나의 work item으로
    결정적으로 병합되는지 검증
  - read permission으로 command가 실행되지 않고 allowedActions가 실제 command 인가와 일치하는지 검증
  - provider key·원본 payload·PII가 Projection에 노출되지 않는지 검증
- **ADR Required:** Yes — [ADR-110](../adr/ADR-110-federated-operations-failure-queues.md)
- **Revisit Conditions:** 전 유형 단일 SLA 정렬이 필요하거나 port fan-out이 측정된 병목이 되고,
  event-backed Projection의 rebuild·lag·backfill 운영 비용을 감수할 요구가 생길 때

## BR-44 운영자 감사 로그 조회 기간

- **Status:** Accepted for P0
- **Decision:** 운영자 감사 로그 목록은 `to` 미지정 시 요청 시각, `from` 미지정 시 `to - 30일`을
  사용한다. 한 요청의 `from` 이상 `to` 이하 기간은 최대 90일이며 `from > to` 또는 90일 초과는
  400으로 거부한다. 5년 보존 기간 안에서는 과거 조회 시작일에 별도 제품 상한을 두지 않는다.
- **Pagination:** `(occurredAt DESC, auditRecordId DESC)` keyset cursor를 사용한다. signed cursor에는
  actor가 지정하거나 서버가 확정한 `from`, `to`, category, action, correlationId와 endpoint를 함께
  묶는다. 다음 page에서 필터나 기간이 달라지면 400으로 실패시키며 새 조회로 시작해야 한다.
- **Authorization and Audit:** active `AUDIT_RECORD_READ`, `PLATFORM_OPERATOR`와 유효한
  `X-Access-Reason`을 모두 요구한다. 조회 Projection과 `AUDIT_RECORD_READ` 접근 Audit append를 같은
  local transaction에 묶고 Audit 저장 실패 시 body를 반환하지 않는다. 조회 사유 원문은 log, metric
  tag 또는 응답에 복제하지 않는다.
- **Data Exposure:** 목록과 상세는 credential, secret, raw Provider payload와 불필요한 PII를 반환하지
  않는다. before/after summary는 저장된 sanitized 값만 반환하며 UI가 임의 JSON을 실행하거나 HTML로
  해석하지 않는다.
- **Rationale:** 기본 운영 화면의 비용을 예측 가능하게 하면서도 사고·분쟁 조사를 위해 5년 보존분을
  최대 90일 단위로 탐색할 수 있게 한다.
- **Affected Contexts:** Operations, Operations Console
- **Affected Aggregate / Read Model:** append-only AuditRecord, AuditRecordQueryProjection
- **Required Tests:**
  - 생략한 기간이 요청 시각 기준 30일로 고정되는지 검증
  - 정확히 90일 허용, 90일 초과와 역전 기간 400 검증
  - cursor의 기간·필터 변조와 다른 endpoint 재사용 400 검증
  - 5년 보존 범위의 과거 90일 window 조회와 due purge 경계 회귀 검증
  - 권한·사유·접근 Audit 원자성과 query/Audit 저장 장애의 503 검증
  - Projection과 브라우저 렌더링에 secret·raw payload·불필요한 PII가 없는지 검증
- **ADR Required:** Existing [ADR-022](../adr/ADR-022-audit-record.md) amendment
- **Revisit Conditions:** 측정된 query p95·DB 부하, 별도 감사 저장소, legal hold, 조사 업무의 더 긴 단일
  window 요구 또는 export 전용 비동기 workflow가 필요할 때

## BR-45 운영자 정산 대사 조회 기간

- **Status:** Accepted for P0
- **Decision:** 운영자 정산 대사 목록은 `to` 미지정 시 `Asia/Seoul`의 현재 정산일,
  `from` 미지정 시 `to - 29일`을 사용해 양 끝을 포함한 30개 정산일을 조회한다. 한 요청의
  `from..to`는 양 끝을 포함해 최대 90개 정산일이며 역전 또는 90일 초과는 400으로 거부한다.
  보존된 과거 정산일 조회 자체에는 별도 제품 상한을 두지 않는다.
- **Pagination:** `(settlementDate DESC, settlementBatchId DESC)` keyset cursor에 서버가 확정한
  from/to, storeId, state와 endpoint를 서명한다. 다음 page에서 filter를 바꾸면 400으로 실패시키고
  새 조회를 시작한다.
- **Incomplete Semantics:** OPEN batch나 필수 source가 아직 terminal이 아니면 대사 결과는
  `INCOMPLETE`다. 미완료 금액을 0으로 대체하거나 일관된 정산으로 표시하지 않는다.
- **Rationale:** 일별 batch를 월간 기본 화면과 최대 분기 단위 조사로 다루면서 단일 전역 query의
  비용과 응답 범위를 제한한다.
- **Affected Contexts:** Settlement, Operations, Operations Console
- **Affected Aggregate / Read Model:** SettlementBatch, SettlementItem, SettlementAdjustment,
  SettlementDispute, SettlementReconciliationProjection
- **Required Tests:**
  - 생략한 기간이 서울 정산일 기준 양 끝 포함 30일인지 검증
  - 정확히 90개 정산일 허용, 91일·역전 기간 400 검증
  - store/state/date filter와 cursor binding, page 누락·중복 부재
  - 과거 90일 window와 자정·윤년 경계
  - OPEN·late source가 0원 `CONSISTENT`가 아닌 `INCOMPLETE`인지 검증
- **ADR Required:** No — 기존 [ADR-017](../adr/ADR-017-settlement-calculation-and-cost-allocation.md)과
  [ADR-070](../adr/ADR-070-signed-cursor-and-pagination-contract.md)을 따르는 P0 read 계약
- **Revisit Conditions:** 실제 조회 p95·DB 부하, 분기 이상 단일 조사, 비동기 export 또는 별도
  reconciliation 저장 모델이 요구될 때

## BR-46 운영자 웹의 점주 계정 발급과 임시 비밀번호 1회 표시

- **Status:** Accepted for P0
- **Authorization:** 점주 계정 발급·임시 비밀번호 초기화·잠금 조기 해제·관리 조회는 Keycloak
  `PLATFORM_OPERATOR`와 active `MERCHANT_CREDENTIAL_MANAGE` grant를 모두 요구한다. 명령은 1~200자의
  control-character 없는 reason과 `Idempotency-Key`를 요구하며, role이나 다른 grant로 대체하지 않는다.
- **Create Boundary:** 발급 요청은 canonical login ID, display name, 존재하는 `storeId`와
  `OWNER | STAFF` initial membership role을 받는다. MerchantAccount, 최초 ACTIVE StoreMembership,
  같은 LOGIN_ID의 과거 attempt 삭제와 AuditRecord를 한 PostgreSQL transaction에 commit한다. 하나라도
  실패하면 전부 rollback하며 membership 없는 계정이나 계정 없는 membership을 성공으로 남기지 않는다.
- **Temporary Password:** 서버 CSPRNG로 24 random byte를 생성하고 padding 없는 Base64URL 32자로
  인코딩한다. BR-35를 통과한 값만 Argon2id로 hash해 저장한다. 평문은 발급 또는 초기화가 처음 성공한
  HTTP 응답의 `temporaryPassword` 필드에만 한 번 포함한다. 조회 endpoint, DB, AuditRecord, log,
  metric, tracing, error payload와 frontend storage에는 남기지 않는다. 응답은 `Cache-Control: no-store`다.
- **Lost Response:** 발급·초기화 mutation을 브라우저에서 자동 retry하지 않는다. 같은
  `Idempotency-Key` replay는 부수효과를 다시 만들거나 비밀번호를 재현하지 않고
  `409 TEMPORARY_PASSWORD_NOT_REPLAYABLE`과 대상 계정 reference만 반환한다. 잠금 조기 해제는 secret이
  없으므로 같은 payload replay에 204를 재생한다. 다른 payload의 key 재사용은 모두
  `409 IDEMPOTENCY_KEY_REUSED`다. 운영자는 exact login ID로 생성 여부를 확인하고 새 idempotency key의
  초기화 명령으로 새 임시 비밀번호를 한 번 발급한다. 초기화 응답을 잃어도 같은 절차로 다시 초기화하며,
  매 초기화는 이전 Hash를 무효화하고 `credentialVersion`을 증가시키고 AuditRecord를 남긴다.
- **Idempotency Retention:** terminal command row에는 secret·Hash·response body를 저장하지 않고 90일
  보존한다. 기본 1시간마다 최대 100개 due row를 `(retentionExpiresAt, id)` 순서로 삭제하며 실패는
  retry·metric·log로 남기고 명령 검사를 우회하지 않는다.
- **Frontend Boundary:** Operations UI는 성공 응답의 임시 비밀번호를 현재 화면의 memory state에만
  보관하고 복사 여부와 무관하게 route 이동·새로고침·닫기에서 제거한다. 다시 보기 기능은 없고,
  사라진 비밀번호는 초기화로만 대체한다.
- **Rationale:** 검증된 이메일·SMS Provider가 없는 P0에서도 웹 운영 흐름을 완결하되, 임시 비밀번호를
  나중에 복호화·조회할 수 있는 장기 secret으로 만들지 않기 위함이다.
- **Affected Contexts:** Identity, Merchant, Operations, Operations Console
- **Affected Aggregates:** MerchantAccount, StoreMembership, LoginAttempt, OperatorPermissionGrant,
  AuditRecord, CommandIdempotency
- **Required Tests:**
  - account·membership·attempt 삭제·Audit 원자성과 store/membership role 검증
  - CSPRNG 24-byte/Base64URL 형식, Hash만 persistence, response 외 평문 비노출
  - permission·reason·idempotency 누락/폐기와 Audit 장애의 fail-closed
  - 동일 key 같은/다른 payload replay에서 부수효과 0회와 secret 비재현
  - 응답 유실 뒤 exact login ID 확인 → 새 key reset으로 수렴하고 이전 비밀번호 실패
  - frontend storage·URL·log·error report에 temporaryPassword가 없고 route 이탈 뒤 재표시되지 않음
- **ADR Required:** Existing [ADR-093](../adr/ADR-093-merchant-credential-lifecycle.md) amendment,
  [ADR-069](../adr/ADR-069-operator-permission-grants-and-audited-policy-read.md) permission amendment
- **Revisit Conditions:** 검증된 전달 Provider, 이중 승인, 별도 credential administrator 역할 또는
  Hardware-backed secret delivery가 필요할 때

---

## BR-47 매장 통합 검색의 대상·매칭·정렬과 브랜드·지역 소유

- **Status:** Accepted for P0
- **Search Surface:** `GET /api/v1/stores/search`는 매장명, 브랜드명, 지역명(시도·시군구·읍면동·리),
  판매 중 메뉴명을 하나의 `query`로 검색한다. 결과는 항상 매장 단위로 집계하며 매장이 여러 term에
  걸려도 한 번만 반환한다. 검색은 인증된 고객 세션을 요구하고 AuditRecord와 도메인 이벤트를
  만들지 않는다.
- **Cross-store Scope:** 메뉴명 검색은 특정 매장으로 범위를 좁히지 않고 조건을 만족하는 **모든
  매장의 메뉴**를 대상으로 한다. 좌표와 `radiusMeters`를 함께 주면 그 반경 안의 전 매장이 대상이며
  반경 상한은 `10000`m다. 매장 단위 카탈로그 조회(`GET /stores/{storeId}/menus`, ADR-076)와는 목적과
  계약이 다르며 서로를 대체하지 않는다. 결과 단위는 메뉴가 아니라 **매장**이고, 가격 비교형 메뉴
  목록은 제공하지 않는다.
- **Saved Address:** "설정 주소지 인근 검색"의 주소지는 client storage에만 두고 매 요청 좌표를
  전송한다. 서버는 고객의 주소·좌표를 저장하지 않으며 이 요구를 위해 계정 스키마나 공개 API 계약을
  바꾸지 않는다. 정밀 좌표 보존 금지는 `BR-28`과 ADR-020 그대로다.
- **Query Grammar:** `query`는 trim하고 연속 whitespace를 한 칸으로 축약한 뒤 2~50 Unicode code
  point여야 한다. 공백으로 분리한 토큰은 최대 5개다. `%`, `_`, `\`는 wildcard가 아닌 literal이다.
  위반은 400이며 오류 메시지에 검색어 원문·좌표·cursor를 넣지 않는다. 서버가 임의로 nearby나 추천
  결과로 대체하지 않는다.
- **Matching:** 토큰별로 case-insensitive literal substring을 먼저 적용하고, substring으로 걸리지
  않은 토큰에만 `pg_trgm` 유사도 매칭을 임계값 `0.3`으로 추가 적용한다. 모든 토큰이 해당 매장의
  term 중 적어도 하나에 매칭돼야 결과에 포함된다(AND). 한 토큰이 여러 term에 걸리면 가중치가 가장
  높은 것만 센다. 자동완성은 제공하지 않는다.
- **Ranking:** term 가중치는 `STORE_NAME 1.00`, `BRAND_NAME 0.90`, `REGION_* 0.80`,
  `MENU_NAME 0.70`이고 substring 매칭은 유사도 `1.0`으로 취급한다. 관련도는 토큰별 최고 가중
  유사도의 평균이며 응답에 노출하지 않는다. `sort=relevance`(기본)는
  `(relevanceRank, distanceMicrometers, storeId)`, `sort=distance`는 `(distanceMicrometers, storeId)`
  순이다. `sort=distance`는 좌표가 필수이며 좌표 없이 요청하면 400이다.
- **Location:** 좌표는 선택이다. latitude와 longitude는 둘 다 주거나 둘 다 생략하고, 좌표 없이
  radius만 주면 400이다. 좌표를 주면 `radiusMeters`가 필수이며 `1..10000`이다. 좌표가 없으면
  `distanceMeters`를 응답에서 생략한다. 정밀 좌표 보존 금지는 `BR-28`을 그대로 따른다.
- **Filters:** `pickupAvailable=true`는 7일 안에 실제 예약 가능한 슬롯이 있는 매장만 남긴다.
  `openOnly=true`는 `acceptingOrders && pickupEnabled`만 요구한다. 두 필터는 독립이고 동시 지정은
  AND다. 둘 다 미지정이 기본이며 그때 닫힌 매장도 결과에 포함하고 상태는 `open`,
  `pickupAvailable` 플래그로 표시한다.
- **Response:** 항목은 매장 표시 정보, 브랜드(있을 때), 지역, `matchReason` term 종류 집합,
  좌표가 있을 때 `distanceMeters`, `open`, `pickupAvailable`, 매장당 최대 3개의 `matchedMenus`를
  포함한다. `matchedMenus`는 `(가중 유사도 DESC, 메뉴명 ASC, 메뉴ID ASC)` 순이며 매칭 메뉴가 없으면
  빈 배열이고 매장이 결과에서 빠지지 않는다. 페이지는 `BR-28`·ADR-070대로 기본 20·최대 50이다.
- **Brand Ownership:** 브랜드 생성·수정·보관과 매장의 브랜드 지정·해제는 `PLATFORM_OPERATOR`만
  수행한다. 활성 브랜드의 정규화 이름은 유일하고 중복은 409다. 매장의 브랜드는 없을 수 있다.
  브랜드명 변경은 소속 매장의 검색 색인을 같은 transaction에서 갱신하며 소속 매장이 1000개를
  넘으면 `409 BRAND_FANOUT_LIMIT_EXCEEDED`로 거절한다.
- **Region Ownership:** 매장의 지역 지정은 해당 매장의 `STORE_OWNER`만 수행하고 `STORE_STAFF`는
  할 수 없다. 지역은 폐지되지 않은 법정동 코드 중에서 선택하며 자유 텍스트 입력을 허용하지 않는다.
  모든 매장 프로필은 유효한 지역 코드를 가져야 하고 이는 DB `NOT NULL`로 강제한다.
  어휘는 시도·시군구·읍면동·리 4계층이다. 리에 지정된 매장은 상위 읍·면 이름과 리 이름 **양쪽으로**
  검색된다. 전국에 중복되는 리 이름은 좌표·반경 필터로 걸러지며 별도 식별자를 요구하지 않는다.
- **Index Freshness:** 검색 색인은 브랜드·지역 명령과 같은 transaction에서 갱신한다. 색인 갱신
  실패는 원 명령 전체를 rollback하며 데이터만 반영되고 색인이 누락된 상태를 만들지 않는다.
  매장·메뉴는 현재 쓰기 API가 없어 시드·직접 DML로 바뀌므로 운영자 재색인 명령으로만 색인에
  반영된다. 이 한계는 색인 커버리지 gauge와 runbook으로 관측 가능하게 남기며 stale 결과를
  최신처럼 제공하지 않는다.
- **Index Rebuild Command:** `POST /api/v1/operations/search-index/rebuild`는
  `PLATFORM_OPERATOR` 역할과 활성 `STORE_BRAND_MANAGE` grant, `Idempotency-Key`, 1..200자 reason을
  요구한다. 같은 actor·key·정규화 reason은 저장한 결과를 재생하고, 다른 reason은
  `409 IDEMPOTENCY_KEY_REUSED`다. 진행 중인 같은 명령은 새 재색인을 시작하지 않고
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`다. 재색인은 매장 단위 transaction으로 계속 진행하며
  결과는 성공·skip 수와 실패 매장 ID를 반환한다. 실패 ID가 있으면 `complete=false`로 명시해
  부분 처리를 완전 성공으로 표현하지 않는다.
- **Failure Policy:** `pg_trgm` extension 부재, 색인 조회 실패, Merchant·Fulfillment 장애는 모두
  503이다. 빈 목록, 캐시된 결과 또는 순차 검색 fallback으로 대체하지 않는다. 결과 0건은 정상
  200이며 장애와 구분한다.
- **Privacy:** 검색어, 토큰, 정밀 좌표를 DB, AuditRecord, application log, metric tag, trace
  attribute 또는 이벤트에 저장하지 않는다. 색인 테이블에 저장하는 것은 매장의 공개 속성이며
  사용자의 검색어가 아니다.
- **Rationale:** 이름을 알고 검색한 사용자에게 0건을 돌려주는 것은 고장으로 읽힌다. 부분 일치의
  예측 가능성을 유지하면서 오타만 구제하고, 닫힌 매장도 상태 표시와 함께 노출한다. 브랜드는 여러
  매장이 공유하는 자원이라 운영자가, 주소는 매장 하나의 사실이라 매장주가 관리하는 것이 정확하다.
- **Affected Contexts:** Discovery, Merchant, Fulfillment, Operations, Identity
- **Affected Aggregates:** Brand, Store, StoreDiscoveryProfile, Menu, PickupSlot, AuditRecord
- **Required Tests:**
  - 매장명·브랜드명·지역명·메뉴명 단일 토큰 검색과 매장 단위 dedupe
  - `"강남 스타벅스"` 다중 토큰 AND와 한 토큰만 맞는 매장의 제외
  - substring 우선 경로가 개정 전과 동일한 결과를 내고 유사도 경로가 0건만 구제하는지
  - 유사도 임계값 `0.3` 경계의 매칭·비매칭
  - 가중치 순서(동일 유사도에서 매장명 매칭이 메뉴명 매칭보다 상위)
  - `sort=distance` 좌표 누락 400, 좌표 없는 `sort=relevance` 정상 동작과 `distanceMeters` 생략
  - `pickupAvailable`·`openOnly` 단독·동시 지정 결과 차이
  - 관련도 동점 다수 상황을 포함한 cursor page 순회의 누락·중복 부재
  - 다른 `sort`·필터·좌표로 재사용한 cursor의 400과 만료 cursor 400
  - `matchedMenus` 최대 3개, 동점 4개 이상일 때 정렬 결정성, 매칭 없는 매장의 빈 배열
  - 브랜드 정규화 이름 동시 등록의 단일 성공과 409
  - 브랜드명 변경의 색인 fan-out 원자성과 1000개 초과 409
  - `STORE_STAFF`·타 매장 소유자의 지역 변경 403, `STORE_OWNER`의 브랜드 생성 403
  - 지역 미입력 매장이 남아 있을 때 커버리지 migration 실패
  - `pg_trgm` 부재·색인 조회 실패의 503과 빈 목록 미대체
  - 재색인 명령의 role·grant·reason·Idempotency-Key, 같은 key replay와 진행 중 중복 거부
  - 재색인 부분 실패의 실패 매장 ID·`complete=false`, 나머지 매장의 계속 처리와 AuditRecord
  - 검색어·좌표가 DB, log, metric tag, event에 남지 않음
- **ADR Required:** [ADR-103](../adr/ADR-103-store-search-strategy.md) 2026-08-15 Amendment,
  [ADR-112](../adr/ADR-112-store-brand-and-administrative-region.md),
  [ADR-070](../adr/ADR-070-signed-cursor-and-pagination-contract.md) 정렬 tuple 등록
- **Revisit Conditions:** `pg_trgm` 조회가 목표 지연을 만족하지 못한다고 측정될 때, 자동완성이
  실제 요구가 될 때, 상권 별칭 검색이 필요할 때, 브랜드 fan-out 상한에 도달할 때, 매장·메뉴 쓰기
  API가 생겨 색인 동기 갱신을 흡수할 수 있을 때

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
10. 고객 일회성 결제는 `BR-33`을 따르고 PaymentMethod lifecycle과 분리한다.
11. 분석 Read Model은 `BR-31`, `BR-32`를 따른다.
12. 고객·점주 사용자명과 고객 가입·로그인 응답은 `BR-34`를 따른다.
13. 매장 통합 검색은 `BR-47`을 따르고 위치 보존은 `BR-28`, 추천·최근 매장은 `BR-40`을 따른다.
13. 고객·점주 비밀번호와 로그인 제한은 `BR-35`를 따른다.
14. 고객·점주 Session 수명과 동시 한도는 `BR-36`을 따른다.
15. 고객 알림함 보존은 `BR-37`을 따른다.
16. 매장 부분 환불 권한과 사용자 계약은 `BR-38`을 따르고 금액 계산에는 `BR-12`, `BR-13`,
    `BR-15`, `BR-18`, `BR-21`을 함께 적용한다.
17. P0 운영 조회는 `BR-39`의 세 explicit permission을 서로 대체하지 않고 적용한다.
18. 홈의 최근 주문 매장과 추천 병합은 `BR-40`을 따른다.
19. 운영자 웹 로그인과 token 저장 경계는 `BR-41`을 따른다.
20. 고객 가입은 `BR-42`에 따라 0원 PointAccount까지 원자 생성한다.
21. 운영 실패 큐는 `BR-43`의 유형별 source-owned Projection을 사용한다.
22. 운영자 감사 로그 조회는 `BR-44`의 30일 기본·90일 요청 상한과 접근 Audit를 적용한다.
23. 운영자 정산 대사 목록은 `BR-45`의 서울 정산일 기준 30일 기본·90일 요청 상한을 적용한다.
24. 점주 계정 발급·초기화는 `BR-46`의 최초 membership 원자 생성과 임시 비밀번호 1회 표시를 따른다.

---

# 별도 ADR이 필요한 정책 목록

| Topic | Related ADR |
|---|---|
| 금액 표현·반올림·항목별 배분 | [ADR-014](../adr/ADR-014-money-allocation-and-partial-refund.md) |
| 예약 lease와 재고·슬롯 확정 시점 | [ADR-005](../adr/ADR-005-reservation-transaction-strategy.md), [ADR-013](../adr/ADR-013-payment-unknown-reservation-expiry.md) |
| 매장 카탈로그 조회 계약과 픽업 슬롯 예약 창 | [ADR-076](../adr/ADR-076-store-catalog-read-contract.md) |
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
| 고객 일회성 결제창·callback·승인 | [ADR-080](../adr/ADR-080-toss-v2-one-time-payment-window.md) |
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

---

# Support Policy Decision Register (2026-08-10)

이 register의 수치와 기간은 별도 표기가 없으면 **Initial policy / Assumption**이다.
개인정보·전자상거래·위치정보 관련 항목은 **Legal review required before production**이며
법규 준수 완료를 뜻하지 않는다.

| ID | Status | Decision | Primary record |
|---|---|---|---|
| SP-01 | Accepted | lightweight hybrid SupportCase와 Support/Operations 책임 분리 | [overview](support-overview.md), ADR-081 |
| SP-02 | Accepted | masked-by-default, reason/field/time-bound reveal와 Audit-before-reveal | [verification](support-verification-policy.md), ADR-082 |
| SP-03 | Accepted | UNVERIFIED/BASIC/ENHANCED; BREAK_GLASS는 별도 path | [verification](support-verification-policy.md), ADR-082 |
| SP-04 | Accepted | server risk decision은 ALLOWED/APPROVAL_REQUIRED/DENIED, unknown 조합 DENIED | [action](support-action-policy.md), ADR-084 |
| SP-05 | Accepted | lifecycle-aware direct order change와 post-acceptance resolution 분리 | [order](support-order-change-policy.md), ADR-085 |
| SP-06 | Accepted | immutable versioned compensation; refund/restoration/correction/goodwill 분리 | [compensation](support-compensation-policy.md), ADR-086 |
| SP-07 | Initial assumption | LOW ≤3,000원, MEDIUM ≤10,000원, HIGH ≤30,000원, 그 초과/무근거는 exceptional이라는 initial band | [compensation](support-compensation-policy.md) |
| SP-08 | Accepted | R0-R4 field class와 purpose-specific profile workflow | [profile](support-profile-change-policy.md), ADR-087 |
| SP-09 | Accepted | R3는 distinct requester, Support Manager, Operations reviewer와 agent execution | [profile](support-profile-change-policy.md), ADR-084 |
| SP-10 | Accepted | exceptional compensation은 Operations 조사 후 agent에게 반환 | [compensation](support-compensation-policy.md), ADR-084 |
| SP-11 | Accepted | canonical DeliveryFulfillment + Provider adapter/inbox/reconciliation, silent failover 금지 | [delivery](delivery-support-policy.md), ADR-088 |
| SP-12 | Accepted initial policy | transaction 5y, Case 3y, PII Audit 2y, contact 90d, location 24h, raw webhook 7d의 Initial retention | [retention](support-retention-policy.md), ADR-089 |
| SP-13 | Accepted | financial Audit 5y는 PII Audit 2y로 대체하지 않음; active PII와 legal-minimum record 분리 | [retention](support-retention-policy.md), ADR-089 |
| SP-14 | Accepted scope / Proposed boundary | Support Console은 최종 scope다. 별도 app, 기존 app 통합, server-rendered boundary는 credential/CORS/CSRF/trust/deployment 결정 전 Proposed다. | ADR-090 |
| SP-15 | Accepted | 자체 rider/call-center/rules-engine/premature Elasticsearch와 bulk export는 비목표 | [non-goals](non-goals.md) |
| SP-16 | Accepted initial policy | S20 Case 상태는 `OPEN`, `IN_PROGRESS`, `WAITING`, `RESOLVED`, `CLOSED`이고 Aggregate 전이 matrix는 SupportCase Policy에 고정한다. S20은 reopen endpoint를 노출하지 않으며 `OTHER`는 구조화된 상세 사유를 요구한다. S20에는 `DataAccessGrant`가 없고, S40 Grant 도입 시 terminal Case의 active Grant 철회와 terminal Case에서의 Grant 활성화·reveal 차단을 같은 Case 경계에서 구현한다. | [case](support-case-policy.md) |
| SP-17 | Accepted initial policy | S30은 Identity 고객, Merchant 매장, Delivery 외부 courier 최소 프로필을 owner-local Vault Transit 암호문·별도 versioned HMAC blind index로 보관하고 Support에는 masked DTO만 반환한다. 지원 계약은 exact phone/email을 POST body로만 받고 query parameter를 거부한다. 결과 최대 20, PostgreSQL clock 기준 actor당 fixed 5분 30회, rate row 24시간 보존과 기본 100행 bounded cleanup, 구조화 사유와 PII-free Audit을 요구한다. fixed-window 경계 burst는 initial-policy limitation이다. | [protected search](support-protected-search-policy.md), ADR-083 |
| SP-18 | Accepted initial policy | S40 VerificationSession 15분, challenge 5분, invalid proof 5회와 Case+Subject 30분 lockout을 사용한다. BASIC은 등록 채널 1개, ENHANCED는 서로 다른 등록 채널 2개다. display-name BASIC Grant는 10분/3회, phone/email/provider-reference SENSITIVE Grant는 ENHANCED+distinct approval+5분/1회다. BREAK_GLASS는 별도 one-field 2분/1회 path이며 distinct 사전승인, durable security notification과 mandatory 사후검토를 요구한다. Provider가 secret을 소유하고 Audit-committed reveal attempt는 downstream 실패에도 budget을 소비한다. | [verification](support-verification-policy.md), ADR-106 |
| SP-19 | Accepted initial policy | S70 ACCEPTED pre-preparation의 versioned store delegation은 store+action+`support-order-change-policy/2026-08-12/v1`에 고정한다. 취소는 발급 후 10분 동안 성공 1회, pickup reschedule은 30분 동안 성공 3회다. `now >= expiresAt`이면 만료이며 동일 idempotent replay는 추가 소비하지 않고 owner direct change가 commit된 실행만 1회를 소비한다. 건별 confirmation은 exact request/revision/action payload digest/target version과 그 request expiry에 고정한다. 둘 다 store actor가 STORE 비용 책임을 명시 수락해야 하며 책임 미확정 또는 PLATFORM 귀속 건은 direct change가 아니라 post-acceptance resolution로 보낸다. | [order](support-order-change-policy.md), ADR-085 |
| SP-20 | Accepted initial policy | S80 PostAcceptanceResolutionCase는 S60의 승인된 exact `POST_ACCEPTANCE_RESOLUTION` revision을 유일한 승인 source로 소비하며 별도 approval workflow를 만들지 않는다. Case state는 `PLANNED/EXECUTING/PARTIALLY_RESOLVED/RECONCILING/RESOLVED/MANUAL_REVIEW`, owner step state는 `PENDING/PROCESSING/RETRY_SCHEDULED/SUCCEEDED/NOT_REQUIRED/UNKNOWN/RECONCILING/MANUAL_REVIEW/BLOCKED`다. `UNDETERMINED`에서도 승인된 고객 현금 환불과 원혜택 복구는 진행할 수 있지만 비용 귀속·Settlement adjustment는 `BLOCKED`/manual review로 남기며 Store/Platform fallback을 금지한다. | [post-acceptance resolution](support-post-acceptance-resolution-policy.md), ADR-085 |
| SP-21 | Accepted initial policy | S90 goodwill v1은 LOW/MEDIUM/HIGH/EXCEPTIONAL 3,000/10,000/30,000원 band와 고객 30일 30,000원, 주문·사건 30일 30,000원, 상담원 1일 100,000원, 매장 1일 300,000원의 실행 hard cap을 사용한다. 동일 incident terminal benefit은 1회다. LOW는 BASIC+agent, MEDIUM은 BASIC+Support Manager, HIGH/EXCEPTIONAL은 ENHANCED+Operations 조사 후 distinct agent execution이다. Point SHARED는 별도 funding legs, Coupon은 immutable fixed template와 redemption-time settlement snapshot을 사용한다. `UNDETERMINED`는 조사 가능하지만 exact 책임 revision 없이 발급하지 않고 어떤 cost-owner fallback도 허용하지 않는다. | [compensation](support-compensation-policy.md), ADR-086 |
| SP-22 | Accepted initial policy | S100은 고객·매장·Delivery 외부 courier의 R0-R4를 목적별 typed workflow로만 변경한다. R0 ID/version/system fact는 직접 변경하지 않는다. R1은 표시명·매장 공개 정보로 BASIC+`SUPPORT_PROFILE_R1_CHANGE`, R2는 고객 실명 오탈자·매장 운영 연락·courier relay 연락으로 ENHANCED+`SUPPORT_PROFILE_R2_CHANGE`, R3는 고객 로그인/복구 전화·매장 대표자/opaque 정산계좌 reference·courier provider identity/opaque 지급 reference로 ENHANCED+Support Manager→Operations exact-revision 승인 후 assigned agent가 실행한다. R4 password/OTP/MFA/token/key는 어떤 schema에도 raw secret을 받지 않고, R3 승인에 귀속된 reset/re-registration intent만 owner가 생성한다. 고객 primary-phone 변경은 현재 등록 채널에 묶인 ENHANCED session이 필수라 새 전화번호만으로는 승인·실행할 수 없다. Support는 raw payload 대신 canonical SHA-256 digest만 저장하고 실행자가 같은 typed payload를 다시 제출한다. Owner는 old/new encrypted history를 소유하고 가능한 구·신 채널에 모두 알리며, notification 실패는 변경을 rollback하지 않고 retry/manual-review로 남긴다. 자체 Rider workforce model은 만들지 않는다. | [profile](support-profile-change-policy.md), ADR-087 |

## Support implementation gates

- ADR-083의 Vault Transit provider/auth/key separation/rotation/fail-closed 결정과 SP-17의 최소 owner profile,
  normalization/masking/rate/Audit initial policy가 2026-08-11 Accepted되어 S30 detailed plan authoring gate를
  충족했다. 실제 runtime release는 S30 구현·검증과 production Vault provisioning evidence 전에는 허용하지 않는다.
- S10은 V39에서 Audit category/class/version snapshot, financial 5년과 PII access 2년 policy, persistent
  Support permission vocabulary를 구현했다. Case/content/delivery/LegalHold deletion runtime은 구현하지 않았다.
- 이후 migration-writing plan은 실행 시 ADR-072의 실제 lease evidence를 새로 획득해야 한다. S10 완료는
  successor의 migration lease를 예약하지 않는다.
- S40은 SP-18/ADR-106으로 challenge Provider와 terminal-Case Grant transaction gate를 해소하고 V42/runtime/full
  validation을 완료했다. Production enablement는 challenge/security-notification provider provisioning 없이는
  startup guard가 fail-closed한다.
- S70은 SP-19/ADR-085로 ACCEPTED confirmation/delegation expiry·budget·책임 gate를 해소했다. Runtime
  release는 V45 owner command, concurrency/Audit/API validation과 S70 completion 전에는 허용하지 않는다.
- S100의 R3 owner model과 initial field mapping은 SP-22/ADR-087로 확정됐다. Runtime release는 V48 owner write/history,
  exact approval consumption, old/new notification, OpenAPI와 failure/security test가 완료되기 전에는 허용하지 않는다.
- Delivery fulfillment/provider automation과 retention automation은 threat model, owner model, OpenAPI와 failure tests가
  확정되기 전 노출하지 않는다.
