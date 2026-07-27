# ADR-011: PointLot과 포인트 원장

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

단일 balance만으로 만료 순서, 발급 주체, 부분 환불 복원과 비용 부담을 재현할 수 없다.

## Decision

발급분별 PointLot과 적립·사용·소멸·복원·조정 PointTransaction 원장을 사용한다. 사용은 만료가 빠른 Lot부터 한다.

## Alternatives Considered

- balance만 저장
- 원장만 저장하고 balance 매번 합산
- Lot+원장+검증 가능한 요약 balance

## Rationale

만료와 비용 추적을 지원하면서 조회 성능을 위한 요약을 유지한다.

## Consequences

- 사용 시 여러 Lot을 잠글 수 있다.
- 요약과 원장 reconciliation이 필요하다.

## Verification

- 선소멸 사용
- 만료 경계
- 환불 복원·회수
- 원장과 balance tie-out

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

포인트 프로그램 통합·선물·양도 요구가 생길 때

## Related Decisions

BR-10~BR-13, BR-20
