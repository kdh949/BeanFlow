# ADR-005: 초기 예약의 로컬 트랜잭션

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

주문 생성은 슬롯, 재고, 쿠폰과 포인트 예약이 모두 성공해야 한다. 초기에는 같은 PostgreSQL과 배포 단위를 사용한다.

## Decision

공개 Application API를 통해 여러 모듈 예약을 하나의 로컬 DB 트랜잭션에서 조정한다. 일부 실패 시 전체를 롤백한다.

## Alternatives Considered

- 로컬 트랜잭션
- Context별 트랜잭션과 보상 Saga
- 외부 lock/queue 기반 예약

## Rationale

강한 일관성과 구현·운영 비용의 균형이 현재 조건에 적합하다.

## Consequences

- 잠금 범위와 트랜잭션 시간이 커질 수 있다.
- 물리 서비스 분리 시 재설계가 필요하다.

## Verification

- 일부 예약 실패 전체 롤백
- 마지막 자원 동시성 테스트
- Lock Wait 측정

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

트랜잭션 지연·Lock Wait가 병목이거나 서비스 분리가 필요할 때

## Related Decisions

- BR-03, BR-04, BR-05
- [ADR-001](ADR-001-modular-monolith.md)
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
