# ADR-011: PointLot과 포인트 원장

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

단일 balance만으로 만료 순서, 발급 주체, 부분 환불 복원과 비용 부담을 재현할 수 없다.

## Decision

발급분별 PointLot과 적립·사용·소멸·복원·조정 PointTransaction 원장을 사용한다. 사용은 만료가 빠른 Lot부터 한다.

주문 생성의 결제 전 포인트 예약은 다음을 따른다.

- Loyalty가 `PointReservation` Aggregate를 소유한다. Reservation은 `orderId`,
  `pointAccountId`, 총액, 상태, 주문 `reservationExpiresAt`과 source reference를
  가지고 하위 `PointReservationAllocation`이 PointLot별 금액을 고정한다.
- 주문 생성 시 `expiresAt > now`인 Lot만 `(expiresAt, pointLotId)` 순서로 잠그고
  allocation한다. 같은 Order의 active reservation은 하나뿐이다.
- PointAccount와 PointLot은 available과 reserved 요약 금액을 구분한다. 예약 시
  available을 줄이고 reserved를 늘리며 총합과 allocation을 같은 트랜잭션에서
  검증한다.
- 예약 시점에 유효했던 allocation은 주문 lease 끝까지 확정할 수 있다. 원 Lot의
  만료 시각이 lease 중간에 지나도 예약분을 자동 해제하거나 결제 승인을 거부하지
  않는다.
- Lot 만료 작업은 available 금액만 즉시 만료하고 active reserved 금액은 해당
  Order가 `USED` 또는 `RELEASED`로 종결될 때까지 보존한다.
- 결제 승인 시 reserved allocation을 USE PointTransaction으로 확정한다.
- 예약 해제 시 원 Lot이 아직 유효한 allocation은 available로 복원한다. 이미
  만료된 allocation은 available로 복원하지 않고 EXPIRATION PointTransaction으로
  확정한다. 한 Reservation 안에서 두 disposition이 섞일 수 있다.
- reservation, use, release와 expiration은 Order/source reference와 allocation
  reference의 Unique Constraint로 한 번만 반영한다.

이 clarification은 2026-07-28 주문 생성과 예약 lease Feature의 결정 게이트에서
확정했다.

## Alternatives Considered

- balance만 저장
- 원장만 저장하고 balance 매번 합산
- Lot+원장+검증 가능한 요약 balance

## Rationale

만료와 비용 추적을 지원하면서 조회 성능을 위한 요약을 유지한다.

## Consequences

- 사용 시 여러 Lot을 잠글 수 있다.
- 요약과 원장 reconciliation이 필요하다.
- PointLot 만료 worker는 active reservation을 삭제하지 않고 available과 reserved를
  구분해야 한다.
- 주문 해제 결과가 Lot 만료 전후에 따라 restore 또는 expiration으로 나뉜다.

## Verification

- 선소멸 사용
- 만료 경계
- lease 중 Lot 만료 후 결제 승인
- lease 중 Lot 만료 후 예약 해제 시 비복원
- 유효·만료 allocation이 섞인 예약 해제
- 같은 Order의 동시 포인트 예약
- 환불 복원·회수
- 원장과 balance tie-out

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

포인트 프로그램 통합·선물·양도 요구가 생길 때

## Related Decisions

- BR-10, BR-11, BR-12, BR-13, BR-20
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
