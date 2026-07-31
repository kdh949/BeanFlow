# ADR-014: 정수 KRW 배분과 품목 부분 환불

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-02, BR-08, BR-12와 BR-15는 주문 당시 금액을 정수 KRW로 재현하고 품목 부분
환불에서 정책을 다시 계산하지 않도록 요구한다.

## Decision

- 금액 저장과 API 계약은 정수 KRW를 사용한다.
- 정률 계산의 최종 항목 금액에서 원 미만을 버린다.
- 쿠폰, 사용 포인트와 현금 결제액을 할인 전 항목 금액 비율로 OrderLine에 배분한다.
- 버림 후 잔여 원은 금액이 큰 항목, 동률이면 주문 항목 순서가 빠른 항목부터 배분한다.
- 부분 환불은 저장된 현금 배분액을 환불하고 사용 포인트를 복원한다. 쿠폰 할인액은
  현금으로 환급하지 않는다.
- **Partial refund coupon clarification (2026-08-01):** 부분 환불의 coupon
  allocation은 환불·정산 tie-out과 감사용 귀속 원장이다. 부분 환불 성공은 원
  CouponIssuance를 복원하거나 보상 CouponIssuance를 만들지 않고 Promotion owner
  복원 작업도 시작하지 않는다. 원 쿠폰은 주문 전체가 고객 취소 또는 매장 거절로
  종료될 때 기존 종료 정책에 따라 최대 한 번 복원한다.
- 성공한 누적 환불액은 승인액을 초과할 수 없으며 refund source reference로 중복을
  방지한다.

주문 생성과 예약 lease Feature의 대상 메뉴 쿠폰에는 다음 순차 배분 clarification을
우선 적용한다.

- Coupon은 eligible OrderLine의 할인 전 금액만 기준으로 eligible line에 배분한다.
  비대상 line의 coupon allocation은 0이다.
- Points는 coupon allocation 뒤 각 line에 남은 금액을 기준으로 모든 line에
  배분한다.
- Cash는 각 line의 `gross - coupon - points`이며 별도 비율 배분으로 다시 계산하지
  않는다.
- 각 비율 배분 단계의 원 미만 버림과 잔여 원은 그 단계의 기준 금액이 큰 line,
  동률이면 line sequence가 작은 line부터 적용한다.
- 이 순서로 각 line의 benefit이 gross를 초과하지 않고 Order 합계와 정확히 tie-out한다.

이 clarification은 2026-07-28 구현 전 정책 대조에서 BR-08/ADR-024의 대상 제한과
기존 공통 배분 문장이 충돌해 확정했다.

## Alternatives Considered

- 환불 시 현재 정책 재계산
- 각 항목에서 독립 반올림
- 주문 시 결정적 배분 스냅샷

## Rationale

결정적 스냅샷은 메뉴·캠페인 변경 이후에도 주문, 환불과 정산 합계를 같은 값으로
재현한다.

## Consequences

- OrderLine에 금액 배분 필드가 추가된다.
- 배분 순서가 계약의 일부가 되므로 변경 시 migration 또는 새 정책 version이 필요하다.

## Verification

- 원 미만이 발생하는 다품목 정률 할인
- 대상·비대상 혼합 주문의 coupon 대상 제한
- coupon 적용 후 잔액 기준 point 배분
- 잔여 1원이 여러 개 생기는 동률 항목
- 반복 품목 환불과 누적 환불 상한
- 부분 환불 성공 시 CouponIssuance 상태 불변과 Promotion 복원 호출 부재
- 선행 부분 환불 뒤 주문 전체 종료 시 원 쿠폰의 단일 복원
- 현금, 포인트, 쿠폰, 환불, 정산 tie-out

## Metrics

- **Not measured:** 저장 공간과 계산 비용
- 정확성 검증은 성능 수치가 아니라 항목·주문·결제·정산 합계 일치로 판단한다.

## Revisit Conditions

외화, 세금, 환급 가능한 쿠폰 또는 묶음 상품 환불 정책이 도입될 때

## Related Decisions

- BR-02, BR-08, BR-09, BR-12, BR-13, BR-15
- [ADR-004](ADR-004-order-price-snapshot.md)
- [ADR-011](ADR-011-point-lot-ledger.md)
