# ADR-085: 주문 생명주기별 Support 변경과 post-acceptance resolution

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

기존 customer cancellation은 pre-acceptance 범위이며 상담원이 고객을 가장하거나 PREPARING 이후 상태를 rollback하면 actor 의미와 제조 사실·정산이 손상된다.

## Decision

Support는 별도 typed commands를 사용한다. PENDING_PAYMENT/PAID pre-acceptance는 owner cancellation/release/refund 흐름을 재사용하고 reschedule은 new-slot-first atomic swap이다. ACCEPTED는 매장 동의 또는 versioned delegation과 실행시 state check가 필요하다. PREPARING/READY/COMPLETED는 Order를 되돌리지 않고 `PostAcceptanceResolutionCase`로 refund/benefit/settlement adjustment를 조정한다. Partial result와 UNKNOWN/RECONCILING을 명시하고 unknown cost owner fallback을 금지한다.

## Alternatives Considered

- 기존 customer endpoint impersonation: actor/audit/permission 오류로 기각.
- 모든 상태 direct cancel: lifecycle fact 손상으로 기각.
- 별도 지원용 Order 복제: owner divergence로 기각.

## Rationale

기존 cancellation/refund/settlement 불변식을 재사용하면서 사후 해결을 독립 추적한다.

## Consequences

Ordering/Fulfillment public commands와 resolution orchestration이 필요하다. ACCEPTED의 구체 시간/횟수는 Initial Assumption이며 versioned policy가 소유한다.

## Verification

State matrix, ACCEPTED↔PREPARING race, new-slot failure old-slot retained, cumulative refund, partial/unknown resolution tests.

## Metrics

State별 decision/outcome, slot conflict, resolution partial/unknown duration.

## Revisit Conditions

매장 delegation 실제 데이터와 책임분쟁 패턴이 초기 assumptions를 벗어날 때.

## Related Decisions

ADR-029~040, ADR-048, ADR-061.
