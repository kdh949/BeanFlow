# ADR-010: 초기 이벤트 발행 방식

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

모듈 간 후속 처리를 느슨하게 결합해야 하지만 초기부터 Kafka를 운영할 필요는 확인되지 않았다.

## Decision

모듈 내부는 Spring application event 또는 Spring Modulith event로 시작한다. 재시작 복구가 필요한 이벤트는 영속 publication/outbox를 사용한다. Kafka는 독립 소비자·replay·서비스 분리 요구가 생기면 재검토한다.

## Alternatives Considered

- 동기 호출만 사용
- 초기 Kafka
- Modulith event와 필요 지점의 영속 publication

## Rationale

정합성 문제를 다루면서 운영 복잡도를 단계적으로 도입한다.

## Consequences

- broker 기반 확장성과 격리를 초기에는 얻지 못한다.
- 이벤트 소비자 멱등성을 여전히 구현해야 한다.

## Verification

- publication failure/restart test
- duplicate consumer test
- module event tests

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

여러 독립 소비자, 장기 replay 또는 분리 배포가 필요할 때

## Related Decisions

ADR-001, Event Catalog
