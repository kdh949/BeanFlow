# ADR-007: 결제 멱등성과 reconciliation

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

클라이언트 재시도, 응답 유실과 서버 장애로 같은 결제가 여러 번 요청될 수 있다.

## Decision

actor+operation+Idempotency-Key 범위, payload hash, Unique Constraint와 결과 저장을 사용한다. timeout은 UNKNOWN으로 보존하고 Provider 조회 reconciliation을 실행한다.

## Alternatives Considered

- 클라이언트 중복 방지만 사용
- paymentKey Unique만 사용
- 요청·응답 멱등 기록과 reconciliation

## Rationale

중복 승인과 결과 불명 상태를 모두 다룬다.

## Consequences

- 멱등 레코드 보존·정리와 운영 job이 추가된다.
- 상태 머신이 복잡해진다.

## Verification

- 같은 키 같은 payload
- 같은 키 다른 payload 409
- 동시 재시도
- UNKNOWN 복구

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

Provider의 idempotency 보장과 보존 정책이 확정될 때

## Related Decisions

- BR-25, BR-26
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
