# ADR-043: 종료 Campaign과 독립적인 보상 쿠폰 조건 snapshot

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

만료 혜택 정책이 `COMPENSATE_WITH_NEW_ISSUANCE`이면 Promotion은 원 CouponIssuance와
같은 Campaign의 새 보상 쿠폰을 발급한다. 현재 쿠폰 예약은 live Campaign을 조회하고
`campaign.active`를 요구한다. 원 쿠폰 만료 시점에 Campaign이 종료·비활성화됐다면
30일 보상 issuance를 만들어도 즉시 사용할 수 없다.

보상 쿠폰은 종료 사건 시점에 확정한 고객 보상이다. 이후 Campaign lifecycle이나
마케팅 설정 변경에 따라 사용 가능성·할인 계산이 바뀌면 append-only policy snapshot과
재현성 목적이 깨진다.

## Decision

- 만료 원 쿠폰을 새 issuance로 보상할 때, 보상 CouponIssuance가 자체 immutable
  `CouponTermsSnapshot`을 소유한다.
- snapshot은 원 Campaign의 다음 사용 조건을 발급 transaction에서 복제한다.

  - original Campaign ID
  - store ID
  - discount type
  - fixed amount 또는 rate basis points
  - minimum eligible subtotal
  - maximum discount
  - all-menus-eligible flag
  - all-menus가 아니면 eligible menu ID 집합
  - ADR-049의 `costBearer`, `platformShareBps`, `storeShareBps`

- snapshot은 CouponIssuance 생성 후 수정하지 않는다. 대상 menu ID는 issuance별
  child row와 `(coupon_issuance_id, menu_id)` UNIQUE로 보존한다.
- 보상 issuance 예약은 live Campaign의 `active`, 기간 또는 이후 변경 값을 조회하지
  않고 issuance snapshot만 사용한다.
- store와 menu의 현재 주문 가능성은 일반 주문 생성 검증을 그대로 따른다. snapshot에
  포함됐다는 이유로 삭제·판매 중지 menu를 주문 가능하게 만들지 않는다.
- snapshot 조건을 만족하는 현재 판매 menu가 없으면 쿠폰은 유효기간 안에 적용되지
  않을 수 있다. 자동으로 대상 범위를 전체 menu로 넓히거나 포인트로 전환하지 않는다.
- 보상 issuance의 만료시각은 종료 시각에 snapshot policy의
  `compensationValidityDays`를 calendar day가 아닌 기존 24시간 단위 duration으로
  더한 값이다. timezone 정책을 calendar-day 만료로 바꾸려면 별도 결정이 필요하다.
- 원 issuance ID, restoration source, trigger와 policy version ID는 ADR-042대로
  함께 저장한다.
- 일반 마케팅 CouponIssuance는 기존 live Campaign 검증을 유지한다. 자체 terms
  snapshot 사용은 system compensation issuance에만 적용한다.
- 보상 발급 자체에는 정산 원장을 만들지 않고, 미래 완료 주문에서 실제 사용되면
  ADR-049의 원 Campaign 비용 부담 snapshot으로 정산한다.

## Alternatives Considered

### live Campaign active를 계속 요구

- 현재 예약 경로를 그대로 재사용한다.
- 종료 Campaign의 보상 쿠폰이 발급 즉시 사용 불가해 보상 성공을 위장한다.

### Campaign 종료 시 COUPON step manual review

- 사용할 수 없는 쿠폰 자동 발급을 막는다.
- 기본 매장 거절 보상이 Campaign lifecycle에 따라 자주 운영자 작업으로 전환된다.

### 할인 가치를 포인트로 전환

- 메뉴 대상이 사라져도 사용할 수 있다.
- 정액·정률 쿠폰의 미래 가치를 하나의 KRW 포인트 값으로 산정해야 하고 비용 부담과
  Loyalty 원장이 새 정책으로 확대된다.

### 원 대상이 없으면 store 전체 menu로 확장

- 고객이 보상 쿠폰을 사용할 가능성이 높다.
- 원 Campaign보다 넓은 할인 책임과 비용을 만들며 고객마다 보상 가치가 달라진다.

## Rationale

보상 issuance는 마케팅 Campaign의 미래 활성 상태가 아니라 이미 확정된 혜택 복원
결과다. 계산과 대상 조건을 issuance에 고정하면 Campaign 변경 뒤에도 같은 할인
규칙을 재현하면서 주문 가능한 실제 menu 검증은 유지할 수 있다.

## Consequences

- CouponIssuance에 compensation terms snapshot과 eligible-menu child table이
  추가된다.
- 쿠폰 계산기는 live Campaign과 issuance snapshot을 같은 canonical terms
  interface로 평가해야 한다.
- Campaign 삭제 정책은 보상 issuance가 원 ID와 snapshot을 보존할 수 있도록
  hard delete를 피하거나 참조 보존 규칙을 가져야 한다.
- `COMPENSATION_ISSUED`는 새 issuance와 terms snapshot, 대상 child row가 한 owner
  transaction에서 전부 commit돼야 한다.
- 대상 menu가 모두 판매 중지된 경우 자동 대체가 없다는 한계가 고객 지원과 metric에
  드러나야 한다.

## Failure Scenarios

- snapshot 일부만 저장되면 쿠폰 계산이 live Campaign으로 fallback하거나 사용할 수
  없는 issuance가 된다.
- compensation issuance에 live `campaign.active`를 검사하면 종료 Campaign 보상이
  실패한다.
- 일반 issuance까지 snapshot 경로로 우회하면 비활성 Campaign 쿠폰이 잘못 사용된다.
- eligible menu child 중복·누락은 원 Campaign과 다른 할인 대상을 만든다.
- 대상 menu가 없을 때 store 전체로 자동 확장하면 승인되지 않은 비용이 발생한다.

## Verification

- Campaign 비활성화·변경 뒤에도 보상 쿠폰이 원 조건으로 계산된다.
- 일반 issuance는 비활성 Campaign에서 계속 거부된다.
- snapshot과 eligible menu 집합이 issuance 생성과 원자적으로 저장된다.
- 대상 menu의 현재 판매 가능성 검증은 우회되지 않는다.

## Required Tests

- FIXED_KRW·RATE_BPS terms snapshot과 type별 CHECK
- minimum·maximum·all-menus snapshot 재현
- 대상 menu child unique와 원 Campaign 집합 tie-out
- Campaign inactive 뒤 compensation coupon 사용
- Campaign 계산 값 변경 뒤 과거 compensation coupon 결과 불변
- 일반 issuance의 inactive Campaign 거부 회귀
- snapshot/eligible row 저장 실패의 issuance 전체 rollback
- 판매 중지·삭제 대상 menu 주문 거부와 자동 범위 확장 부재
- duplicate owner event의 보상 issuance·terms 한 건
- source/trigger/policy mismatch의 기존 snapshot 보존

## Metrics

- `beanflow.coupon.compensation.issuance.count{trigger,outcome}`
- `beanflow.coupon.compensation.redemption.count{trigger,outcome}`
- `beanflow.coupon.compensation.no_sellable_target.count{trigger}`

Campaign, issuance, menu, store, Order와 customer ID는 metric tag로 사용하지 않는다.

- **Not measured:** 보상 쿠폰 사용률과 대상 menu 부재 기간

## Revisit Conditions

보상 쿠폰의 대상 menu가 사라지는 사례가 유의미하거나, Campaign hard delete,
platform-wide 대체 쿠폰 또는 포인트 전환 정책이 필요해질 때

## Related Decisions

- BR-09, BR-14
- [ADR-004](ADR-004-order-price-snapshot.md)
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-024](ADR-024-coupon-calculation-model.md)
- [ADR-028](ADR-028-expired-benefit-restoration-policy.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
- [ADR-042](ADR-042-benefit-restoration-ledger-metadata.md)
- [ADR-049](ADR-049-compensation-coupon-cost-attribution.md)
