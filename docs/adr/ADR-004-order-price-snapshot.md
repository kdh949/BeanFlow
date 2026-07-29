# ADR-004: 주문 가격·메뉴·옵션 스냅샷

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

메뉴명과 가격이 변경돼도 과거 주문·환불·정산 결과는 변하면 안 된다.

## Decision

OrderLine에 menuId와 함께 주문 당시 메뉴명, 옵션명, 단가, 수량과 혜택 배분을 저장한다.

## Alternatives Considered

- 조회 시 현재 Menu를 조인
- 가격만 스냅샷
- 주문 표시·계산에 필요한 전체 스냅샷

## Rationale

과거 거래 재현성과 외부 Aggregate 독립성을 보장한다.

## Consequences

- 중복 데이터가 증가한다.
- 스냅샷 schema evolution이 필요하다.

## Verification

- 메뉴 변경 후 과거 주문 조회·환불 테스트
- 금액 tie-out

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

규제·감사 요구로 더 많은 속성을 보존해야 할 때

## Related Decisions

- BR-02, BR-08, BR-12, BR-15
- [ADR-003](ADR-003-aggregate-reference-by-id.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
