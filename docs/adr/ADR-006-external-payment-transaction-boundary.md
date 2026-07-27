# ADR-006: 외부 PG 호출과 DB 트랜잭션 분리

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

외부 Provider latency 동안 DB connection과 lock을 유지하면 pool 고갈과 긴 트랜잭션이 발생한다.

## Decision

Payment READY를 커밋하고 PG를 호출한 뒤 별도 트랜잭션에서 결과를 기록한다.

## Alternatives Considered

- 외부 호출을 DB 트랜잭션 내부에서 실행
- 트랜잭션 분리와 reconciliation
- 완전 비동기 승인

## Rationale

DB 자원을 보호하면서 외부 성공·내부 기록 실패를 명시적으로 복구한다.

## Consequences

- 중간 UNKNOWN 상태와 reconciliation이 필요하다.
- 단일 ACID 트랜잭션처럼 보이지 않는다.

## Verification

- Provider timeout
- PG 성공 후 DB write 실패
- Hikari pending/active 측정

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

Provider가 원자적 callback 또는 다른 보장 방식을 제공할 때

## Related Decisions

ADR-007, ADR-009
