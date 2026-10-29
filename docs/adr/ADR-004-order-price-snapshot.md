# ADR-004: 주문 가격·메뉴·옵션 스냅샷

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

메뉴명과 가격이 변경돼도 과거 주문·환불·정산 결과는 변하면 안 된다.

## Decision

OrderLine에 menuId와 함께 주문 당시 메뉴명, 옵션명, 단가, 수량과 혜택 배분을 저장한다.

2026-08-09 빠른 재주문 변경 사항:

- OrderLine에는 표시용 옵션명과 함께 선택한 option ID를 중복 없이 ID 오름차순으로 저장한다.
  옵션을 선택하지 않은 경우에는 빈 집합으로 저장해, 과거 주문에 option ID 기록이 없는 경우와 구분한다.
- 과거 주문에 option ID가 없다면 빠른 재주문을 차단한다. 옵션명이나 현재 메뉴 정보로 ID를 임의 생성하지 않는다.
- 현재 OrderLine에 없는 note는 빠른 재주문의 입력 데이터에도 포함하지 않는다.

## Alternatives Considered

- 조회 시 현재 Menu를 조인
- 가격만 스냅샷
- 주문 표시·계산에 필요한 전체 스냅샷

## Rationale

주문 당시 가격과 옵션을 OrderLine만으로 조회할 수 있어, 이후 메뉴 정보가 변경돼도 과거 거래 금액을 재계산할 수 있다.

## Consequences

- 중복 데이터가 증가한다.
- 스냅샷 필드가 바뀌면 스키마 변경 작업이 필요하다.
- 과거 주문의 option ID 미기록 상태를 유지하면서 신규 주문에는 정렬된 option ID를 저장하도록 migration이 필요하다.

## Verification

- 메뉴 변경 후 과거 주문 조회·환불 테스트
- 금액 tie-out
- option ID 정렬·중복 거부와 옵션 없는 빈 snapshot
- option ID가 없는 과거 주문의 재주문 차단

## Revisit Conditions

규제·감사 요구로 더 많은 속성을 보존해야 할 때

## Related Decisions

- BR-02, BR-08, BR-12, BR-15
- [ADR-003](ADR-003-aggregate-reference-by-id.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
