# ADR-049: 보상 쿠폰의 원 Campaign 비용 부담 승계

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-041은 고객 취소나 매장 거절에서 원 쿠폰이 만료됐으면 정책에 따라 새 보상
CouponIssuance를 발급할 수 있게 했고, ADR-043은 Campaign이 종료·변경돼도 보상
쿠폰이 사용할 할인·대상 조건 snapshot을 소유하도록 정했다. 그러나 보상 쿠폰이 미래
주문에서 실제 사용될 때 할인 비용을 플랫폼과 매장 중 누가 부담하는지는 보류했다.

BR-19는 일반 쿠폰의 `PLATFORM`, `STORE`, `SHARED` 부담 주체와 분담률을 거래 당시
snapshot으로 정산한다. 보상 쿠폰만 현재 Campaign이나 일률적인 플랫폼 부담을
사용하면 원 혜택과 경제적 의미가 달라지고 과거 결과를 재현할 수 없다.

## Decision

- `COMPENSATE_WITH_NEW_ISSUANCE`로 발급하는 CouponIssuance는 원 CouponIssuance가
  보존한 Campaign 비용 부담 snapshot을 그대로 승계한다.
- immutable terms snapshot에 다음 비용 필드를 포함한다.

  | 필드 | 제약 |
  |---|---|
  | `costBearer` | `PLATFORM`, `STORE`, `SHARED` |
  | `platformShareBps` | 0..10000 |
  | `storeShareBps` | 0..10000 |

- 두 비율 합은 항상 10000이다. `PLATFORM`은 10000/0, `STORE`는 0/10000,
  `SHARED`는 양쪽이 모두 양수여야 한다.
- Store 부담의 대상은 ADR-043 terms snapshot의 원 `storeId`다. 현재 Campaign
  owner나 현재 매장 계약으로 다시 판정하지 않는다.
- Promotion owner는 보상 issuance를 만들 때 원 issuance의 할인 조건과 비용 부담
  snapshot을 한 transaction에서 복사한다. 일부 필드만 복사하거나 live Campaign의
  현재 값을 섞지 않는다.
- 원 issuance에 완전한 비용 snapshot이 없으면 `PLATFORM`이나 현재 Campaign 값으로
  fallback하지 않는다. COUPON owner transaction을
  `COMPENSATION_COUPON_COST_SNAPSHOT_MISSING`으로 실패시켜 publication retry와 해당
  step `MANUAL_REVIEW`로 보낸다.
- 보상 CouponIssuance 생성 자체에는 SettlementItem이나 SettlementAdjustment를
  만들지 않는다.
- 보상 쿠폰이 미래 주문에서 실제 사용되고 그 Order가 `COMPLETED`될 때, 그 미래
  Order/SettlementItem은 보상 issuance의 비용 snapshot으로 쿠폰 할인 비용을
  배분한다.
- 보상 쿠폰이 사용되지 않거나 만료되면 쿠폰 할인 정산 비용은 발생하지 않는다.
- 미래 주문의 부분·전액 환불은 그 주문에 저장된 같은 비용 snapshot과 line
  allocation으로 BR-18·BR-19의 일반 SettlementAdjustment 규칙을 따른다.
- ADR-048에 따라 취소된 원 Order에는 이 보상 쿠폰 비용을 이유로
  SettlementItem이나 Adjustment를 만들지 않는다.
- 원 Campaign 종료, 비활성화, 비용 비율 변경과 매장 계약 변경은 이미 발급된 보상
  issuance에 소급하지 않는다.

## Alternatives Considered

### 플랫폼 전액 부담

- 종료 Campaign과 매장 계약을 조회하지 않아도 된다.
- 원래 매장 또는 shared 비용이었던 혜택이 고객 취소만으로 플랫폼 비용으로 이동한다.

### 미래 사용 시 현재 Campaign/계약 조회

- 최신 사업 조건을 반영한다.
- Campaign이 종료됐거나 삭제되면 계산할 수 없고 같은 source 보상 쿠폰의 비용이 사용
  시점에 따라 달라진다.

### 발급 시 즉시 비용 인식

- 보상 책임을 취소 시점에 바로 표시할 수 있다.
- 실제 사용되지 않을 수 있는 할인액을 정산하고 미래 주문의 실제 사용과 중복된다.

## Rationale

보상 쿠폰은 고객이 잃은 원 쿠폰의 대체물이므로 할인 조건뿐 아니라 비용 책임도
승계해야 한다. 비용은 실제 상품 인도와 쿠폰 사용이 확정된 미래 완료 주문에서만
인식해야 BR-16의 완료일 정산 원칙과도 일치한다.

## Consequences

- CouponIssuance의 immutable Campaign terms snapshot에 비용 필드가 추가된다.
- 미래 Order와 SettlementItem 생성은 Campaign이 아니라 사용 CouponIssuance의
  snapshot을 입력으로 사용해야 한다.
- 보상 쿠폰 복원 consumer는 비용 snapshot 완전성을 검증한다.

## Failure Scenarios

- 누락 snapshot을 플랫폼 부담으로 대체하면 비용이 조용히 플랫폼으로 이동한다.
- live Campaign을 조회하면 종료 Campaign 때문에 사용 가능한 보상 쿠폰의 정산만
  실패할 수 있다.
- 발급과 사용 때 모두 비용을 기록하면 이중 정산된다.
- 원 취소 Order에 Adjustment를 만들면 ADR-048의 미완료 거래 정산 제외를 위반한다.
- SHARED 비율 합이 10000이 아니면 할인액 일부가 사라지거나 중복 부담된다.

## Verification

- 보상 issuance와 원 issuance 비용 snapshot의 정확한 일치
- Campaign 종료·변경 뒤에도 동일한 미래 정산 결과
- 미사용·만료 보상 쿠폰의 정산 원장 0건
- 미래 완료 주문에서만 비용 인식
- 원 고객 취소 Order의 Item·Adjustment 0건
- 비용 snapshot 누락·비율 오류의 명시적 owner 실패

## Required Tests

- PLATFORM, STORE, SHARED별 snapshot 복사와 CHECK
- SHARED 1원 잔여 배분의 BR-02 결정성
- Campaign 비활성화·비율 변경 뒤 보상 쿠폰 사용
- 원 store와 다른 store 사용 거부
- 미사용 만료와 복원 재전달의 원장·issuance 수 불변
- 미래 주문 완료의 SettlementItem 비용 tie-out
- 미래 주문 부분 환불의 비용 Adjustment
- 비용 snapshot 누락의 fallback 부재와 COUPON step manual review

## Metrics

- `beanflow.promotion.compensation_coupon.count{trigger,cost_bearer,outcome}`
- `beanflow.settlement.compensation_coupon.discount_krw{cost_bearer}`

Campaign, CouponIssuance, Order, Store와 Customer 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** 보상 쿠폰 실제 사용률

## Revisit Conditions

고객 책임 취소에 별도 비용 전가 정책이 생기거나 외부 제휴사 비용 부담 주체가 추가될
때

## Related Decisions

- BR-02, BR-14, BR-16, BR-18, BR-19
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
- [ADR-043](ADR-043-compensation-coupon-terms-snapshot.md)
- [ADR-048](ADR-048-preacceptance-cancellation-settlement-exclusion.md)
