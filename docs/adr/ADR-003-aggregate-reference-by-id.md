# ADR-003: Aggregate 간 ID 참조

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

JPA 객체 연관관계를 Context 전반에 연결하면 로딩 범위, Cascade와 트랜잭션 경계가 불명확해진다.

## Decision

다른 Aggregate는 식별자로 참조한다. 같은 Aggregate 내부에서 생명주기를 공유하는 Entity에만 객체 연관관계를 적극 사용한다.

## Alternatives Considered

- 모든 FK를 JPA 연관관계로 매핑
- ID만 저장하고 FK도 두지 않음
- ID 참조와 필요한 DB FK

## Rationale

Aggregate 경계, SQL 예측 가능성과 향후 서비스 분리를 지원한다.

## Consequences

- 조회 조합을 Query Repository에서 명시해야 한다.
- 도메인 탐색 편의가 줄어든다.

## Verification

- JPA mapping review
- ArchUnit 내부 접근 검사
- SQL 수 테스트

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

같은 트랜잭션·생명주기를 지속적으로 공유한다는 증거가 생길 때

## Related Decisions

ADR-002
