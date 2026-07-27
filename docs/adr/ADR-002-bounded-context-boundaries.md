# ADR-002: Bounded Context 후보와 모듈 경계

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

동일한 주문 생명주기 안에서도 가격, 결제, 포인트와 정산은 데이터 소유권과 일관성 요구가 다르다.

## Decision

Identity, Merchant, Discovery, Ordering, Fulfillment, Inventory, Promotion, Loyalty, Payment, Settlement, Dispute, Notification, Analytics, Operations를 논리적 Context 후보로 둔다. MVP 물리 모듈 수는 구현 복잡도에 맞춰 합칠 수 있으나 소유권은 유지한다.

## Alternatives Considered

- 주문 중심 거대 도메인
- 모든 후보를 별도 서비스
- 논리적 Context와 물리 모듈을 분리

## Rationale

용어와 트랜잭션 책임을 분리하면서 과도한 배포 경계를 피한다.

## Consequences

- 모듈 간 Application API와 이벤트 계약이 필요하다.
- 작은 Context는 초기 물리 모듈에서 합쳐질 수 있다.

## Verification

- Context Map
- 모듈 의존성 테스트
- 데이터 소유권 리뷰

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

용어 충돌, 독립 팀 또는 배포 요구가 확인될 때

## Related Decisions

ADR-001, ADR-003
