# ADR-008: 확정 정산 조정 원장

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

확정 정산 뒤 환불·이의제기가 발생할 수 있다. 과거 결과를 수정하면 감사와 재현이 어렵다.

## Decision

확정 Batch와 Item은 불변으로 유지하고 이후 변경은 SettlementAdjustment로 기록하여 다음 정산에 상계한다.

## Alternatives Considered

- 과거 정산 재계산·덮어쓰기
- 전체 배치 취소 후 재생성
- 불변 원장과 Adjustment

## Rationale

감사 가능성, 재처리와 과거 시점 재현성을 확보한다.

## Consequences

- 현재 유효 금액 계산에 원장 합산이 필요하다.
- 음수 이월 정책이 필요하다.

## Verification

- 확정 후 부분·전액 환불
- 중복 Adjustment 방지
- 다음 배치 상계 tie-out

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

실제 지급·채권 관리와 외부 회계 요구가 도입될 때

## Related Decisions

- BR-21, BR-23
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-018](ADR-018-settlement-dispute-hold-and-refile.md)
