# ADR-009: 실패를 숨기지 않는 의미론

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

암묵적 local fallback, 빈 값 반환과 예외 삼키기는 사용자가 실패를 늦게 발견하게 하고 데이터 정합성을 손상시킨다.

## Decision

필수 의존성은 fail-fast, 요청 실패는 명시적 오류·상태, 비동기 실패는 영속 retry/failed/manual 상태로 남긴다. fallback은 별도 ADR과 observability가 있을 때만 허용한다.

## Alternatives Considered

- best-effort와 silent fallback
- 모든 부수효과 실패 시 원본 거래 롤백
- 실패 유형별 명시적 의미론

## Rationale

정확성, 운영 탐지와 복구 가능성을 우선한다.

## Consequences

- 사용자에게 일시 오류가 더 명확히 노출될 수 있다.
- 상태·metric·운영 case 구현이 필요하다.

## Verification

- startup configuration test
- dependency failure integration test
- no-fallback review rules

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

명시적인 degraded product mode가 설계될 때

## Related Decisions

- [Failure Semantics](../architecture/failure-semantics.md)
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
- [ADR-019](ADR-019-notification-retry-and-manual-recovery.md)
