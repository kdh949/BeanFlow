# ADR-004: 주문 가격·메뉴·옵션 스냅샷

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

메뉴명과 가격이 변경돼도 과거 주문·환불·정산 결과는 변하면 안 된다.

## Decision

OrderLine에 menuId와 함께 주문 당시 메뉴명, 옵션명, 단가, 수량과 혜택 배분을 저장한다.

2026-08-09 fast-reorder amendment:

- 새 OrderLine은 표시·거래 재현용 옵션명과 별도로 선택된 option ID를 ID 오름차순의
  중복 없는 immutable snapshot으로 보존한다. 옵션 없는 선택은 빈 집합이고 snapshot
  부재와 구분한다.
- 기존 OrderLine에 검증된 option ID snapshot이 없으면 이름, 현재 Merchant 상태 또는
  sellable requirement에서 option ID를 추론하거나 backfill하지 않는다. 해당 line을
  source로 한 빠른 재주문은 명시적으로 실패한다.
- note는 현재 주문 생성·OrderLine 계약에 없으며 빠른 재주문 source snapshot에
  추가하거나 복사하지 않는다.

## Alternatives Considered

- 조회 시 현재 Menu를 조인
- 가격만 스냅샷
- 주문 표시·계산에 필요한 전체 스냅샷

## Rationale

과거 거래 재현성과 외부 Aggregate 독립성을 보장한다.

## Consequences

- 중복 데이터가 증가한다.
- 스냅샷 schema evolution이 필요하다.
- 기존 row의 option ID snapshot 부재를 표현하고 새 row에는 정규화 snapshot을 쓰는
  migration과 저장 경계가 필요하다.

## Verification

- 메뉴 변경 후 과거 주문 조회·환불 테스트
- 금액 tie-out
- option ID 정렬·중복 거부와 옵션 없는 빈 snapshot
- legacy snapshot 부재의 재주문 실패와 이름·현재값 추론 부재

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

규제·감사 요구로 더 많은 속성을 보존해야 할 때

## Related Decisions

- BR-02, BR-08, BR-12, BR-15
- [ADR-003](ADR-003-aggregate-reference-by-id.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
