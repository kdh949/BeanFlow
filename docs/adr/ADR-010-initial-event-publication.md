# ADR-010: 초기 이벤트 발행 방식

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

모듈 간 후속 처리를 느슨하게 결합해야 하지만 초기부터 Kafka를 운영할 필요는 확인되지 않았다.

## Decision

모듈 내부는 Spring application event 또는 Spring Modulith event로 시작한다. 재시작 복구가 필요한 이벤트는 영속 publication/outbox를 사용한다. Kafka는 독립 소비자·replay·서비스 분리 요구가 생기면 재검토한다.

2026-07-30 store-order lifecycle amendment:

- 첫 event-driven Feature는 Spring Modulith 2.1 JPA Event Publication Registry와
  `@ApplicationModuleListener`를 사용한다.
- Flyway가 PostgreSQL publication schema를 생성하고 Hibernate는 `validate`만 수행한다.
- 실패 publication은 10초, 30초, 2분, 5분, 15분의 bounded schedule로 최대 다섯 번
  resubmit한 뒤 Operations `MANUAL_REVIEW` case로 전환한다.
- Kafka와 별도 broker는 추가하지 않는다.
- Ordering이 event 의미와 발행을 소유하되 Java/Kotlin 계약 타입은 독립
  `Eventing :: api` 모듈에 둔다. Ordering이 동기 예약 API로 의존하는 owner 모듈이
  Ordering의 package를 역참조해 Modulith cycle을 만드는 것을 방지한다.

2026-07-31 order compensation publication amendment:

- `OrderRejectedV1`과 `OrderCancelledV1`의 listener publication이 bounded retry를
  소진하면 실패 listener에 대응하는 단일 OrderCompensationStep만
  `MANUAL_REVIEW`와 `EVENT_PUBLICATION_RETRY_EXHAUSTED`로 전환한다.
- Case 전체 상태는 step 상태에서 파생해 `MANUAL_REVIEW`가 되지만 다른 owner
  publication과 step은 자동 처리를 계속한다.
- publication completion attempt는 owner business attempt가 아니므로 step의
  `attemptCount`에 합산하지 않는다.
- 이 amendment는 현재 코드가 `OrderRejectedV1` 하나의 publication 실패에서 모든
  미완료 rejection step을 `MANUAL_REVIEW`로 바꾸는 동작을 대체한다.
- 영속 publication event contract는 첫 운영 발행부터 version별로 동결한다. 필수
  필드·이름·타입·의미의 breaking change는 새 payload version과 event type으로
  이행한다.
- 구 listener target과 target-to-step mapping은 해당 version의 미완료 publication이
  0이고 승인된 rollback 기간이 끝날 때까지 제거하지 않는다.
- version 이중 발행은 기본값이 아니며 중복 부수효과와 종료 조건을 다루는 별도
  Accepted ADR이 필요하다.

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
- listener별 retry 소진이 해당 보상 step만 manual review로 전환하는 테스트
- 실패하지 않은 publication과 step이 계속 완료되는 테스트
- publication attempt와 owner attempt 분리 테스트
- 구 publication payload 역직렬화와 legacy listener target routing 테스트
- 미완료 publication이 있는 동안 구 listener mapping 제거 방지 테스트

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

여러 독립 소비자, 장기 replay 또는 분리 배포가 필요할 때

## Related Decisions

- [ADR-001](ADR-001-modular-monolith.md)
- [Event Catalog](../architecture/event-catalog.md)
- [ADR-019](ADR-019-notification-retry-and-manual-recovery.md)
- [ADR-023](ADR-023-analytics-refund-and-late-events.md)
