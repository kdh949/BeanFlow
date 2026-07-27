# ADR-024: 대상 품목 합계 기반 쿠폰 계산 모델

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-08과 BR-09는 쿠폰을 포인트보다 먼저 적용하고 주문당 한 장만 허용하지만,
Campaign이 정액·정률 할인을 어떻게 표현하고 대상 메뉴가 제한된 쿠폰의 최소금액과
할인 기준을 어디에 둘지는 정하지 않았다. 이 값이 없으면 같은 CouponIssuance로
Order의 할인 snapshot을 재현할 수 없다.

## Decision

- MVP Coupon Campaign의 할인 유형은 `FIXED_KRW`와 `RATE_BPS`다.
- `FIXED_KRW`는 양의 정수 원 `fixedAmountKrw`를 가진다.
- `RATE_BPS`는 `1..10000` 범위의 `rateBps`와 선택적인 양의 정수 원
  `maximumDiscountKrw`를 가진다.
- Campaign은 선택적인 non-negative `minimumEligibleSubtotalKrw`와 적용 가능한
  menu ID 집합을 가진다. 대상 집합을 생략하면 해당 store의 모든 주문 line이
  대상이고 빈 집합은 유효하지 않은 Campaign이다.
- eligible subtotal은 대상 menu ID를 가진 OrderLine의 할인 전
  `unitPriceKrw * quantity` 합계다.
- 최소 주문금액 충족 여부와 할인 계산은 모두 eligible subtotal만 사용한다.
  비대상 line은 쿠폰 사용 조건을 충족시키거나 쿠폰 할인을 받지 않는다.
- 정액 할인은 `min(fixedAmountKrw, eligibleSubtotalKrw)`다.
- 정률 할인은
  `floor(eligibleSubtotalKrw * rateBps / 10000)`를 계산한 뒤
  `maximumDiscountKrw`가 있으면 그 값으로 상한을 둔다.
- 계산 결과가 0원이거나 minimum을 충족하지 못하면
  `COUPON_NOT_AVAILABLE`로 주문 생성을 거부하고 부분 예약을 남기지 않는다.
- 한 주문에는 CouponIssuance 한 개만 적용한다. 자동 기간 할인은 이번 Feature의
  계산 범위가 아니며, 향후 도입 시 Campaign의 stacking 정책과 적용 순서를
  Business Policy로 먼저 확정한다. 기본 stacking 값은 BR-09에 따라 false다.
- discount type/value, minimum, maximum, 대상 범위, 비용 부담 주체와 분담률은
  주문 생성 시 Order의 혜택 snapshot에 고정한다.

## Alternatives Considered

### 대상 품목 합계로 최소금액과 할인 계산

- 비대상 품목이 쿠폰을 활성화하거나 할인받는 것을 막는다.
- 대상 범위가 좁은 쿠폰은 사용 문턱이 더 높을 수 있다.

### 전체 주문 합계로 최소금액, 대상 품목 합계로 할인 계산

- 고객이 최소금액을 충족하기 쉽다.
- 비대상 품목이 대상 쿠폰을 활성화해 Campaign 의도를 설명하기 어려울 수 있다.

### 모든 Campaign을 정액 할인으로 제한

- 구현은 단순하다.
- BR-02가 요구하는 정률 할인 경계와 향후 Campaign 표현을 별도로 다시 설계해야 한다.

## Rationale

쿠폰 조건과 할인 대상을 같은 금액 기준으로 맞추면 Campaign 설명과 OrderLine
snapshot이 일치하고, 비대상 품목으로 최소금액만 채우는 예외를 만들지 않는다.
정수 KRW와 basis point는 부동소수점 없이 재현 가능한 계산을 제공한다.

## Consequences

- Promotion은 Campaign과 CouponIssuance를 잠근 뒤 같은 계산 규칙을 재검증해야 한다.
- Ordering은 Promotion이 반환한 할인 총액과 Campaign snapshot을 ADR-014 규칙으로
  OrderLine에 결정적으로 배분한다.
- Campaign schema는 type별 필드 조합, rate 범위, 대상 목록과 비용 분담률을
  CHECK 또는 Aggregate invariant로 검증해야 한다.
- 자동 기간 할인은 별도 정책이 정해질 때까지 주문 생성 계산에 포함하지 않는다.

## Verification

- 정액 할인이 eligible subtotal보다 큰 경우 subtotal로 제한
- 1bp, 9999bp와 10000bp 정률 계산 및 원 미만 버림
- 정률 최대 할인 상한
- 대상·비대상 line 혼합 주문의 minimum과 할인 기준
- minimum 미충족과 할인 0원 시 `COUPON_NOT_AVAILABLE`
- Campaign 변경 후 과거 Order snapshot 재현

## Metrics

- **Not measured:** 쿠폰 유형별 사용률과 평균 할인액

## Revisit Conditions

자동 기간 할인, 복수 쿠폰, 장바구니 전체 조건, 수량 단계 할인 또는 외부 제휴
Campaign이 도입될 때

## Related Decisions

- BR-02, BR-08, BR-09, BR-12, BR-19
- [ADR-004](ADR-004-order-price-snapshot.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
