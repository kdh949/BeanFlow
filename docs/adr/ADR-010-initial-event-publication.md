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

2026-08-01 listener-target mapping amendment:

- compensation listener는 `@ApplicationModuleListener(id = ...)`에 versioned stable listener
  ID를 명시한다. Spring의 기본 fully-qualified method signature나 bean/method rename을
  영속 routing 계약으로 사용하지 않는다.
- Ordering의 중앙 registry는 `(eventType, listenerId)`를 정확히 하나의
  `OrderCompensationStep`에 매핑한다. registry의 duplicate key/ID는 startup failure이며,
  retry exhaustion 시 unknown target은 어떤 step도 추측해 변경하지 않고 publication을
  incomplete로 유지한 채 `PUBLICATION_TARGET_UNMAPPED` 운영 case로 fail closed한다.
- `OrderRejectedV1`은 PAYMENT, PICKUP, COUPON, POINTS,
  CUSTOMER_NOTIFICATION 다섯 stable target을, `OrderCancelledV1`은 PICKUP,
  COUPON, POINTS 세 stable target만 가진다. 각 exact ID는 Plan 30 Event Contract 표가
  canonical이다.
- contract test는 실제 application listener ID 집합과 registry 표가 일치하는지, 하나의
  target retry exhaustion이 해당 step 하나만 변경하는지 검증한다. target rename/version
  변경은 producer·listener·registry·fixture·미완료 publication drain을 함께 다루는 Event
  Contract 변경이다.

2026-09-09 automatic recovery isolation amendment:

- 자동 복구는 Operations `EVENT_PUBLICATION` case가 존재하는 publication을 DB 조회에서
  제외한다. 예약 Analytics target, 재시도 미도래, 재시도 소진 건도 재실행 batch limit 전에
  제외한다. 소진 건은 별도 bounded 조회로 수동 검토에 인계한다.
- 인계는 publication row lock 아래 case 등록과 매핑된 단일 보상 step 전환을 같은
  transaction에 수행한다. case 등록은 기존 `(case_type, owner_reference)` unique key로
  멱등 처리한다. 이미 인계된 건은 다시 step을 변경하거나 전환 counter/log를 남기지 않는다.
- publication payload, attempt history, 실패 상태와 completion date는 인계 때 변경하지 않는다.
  case나 step 저장 실패는 함께 rollback하며 다음 tick에 인계를 다시 시도한다.
- 전환 log와 counter는 인계 transaction commit 후에만 기록한다. DB case가 durable evidence이며
  process 종료와 log 수집 사이의 exactly-once delivery를 약속하지 않는다.
- 기존 전체 미완료 지표는 유지하고 자동 복구 대기, 수동 검토 미해결, 수동 검토 최고 대기 시간을
  별도 gauge로 제공한다. 식별자는 구조화 log field에만 넣고 metric tag로 사용하지 않는다.
- Spring Modulith 2.1의 repository SPI를 위임하는 adapter는 자동 복구 호출 범위에서만
  candidate query를 교체한다. 발행, claim, listener 실행, 완료, 명시적 replay 동작은 기존
  registry에 위임한다. scope는 호출 종료와 예외 시 모두 해제한다.
- 이 변경은 수동 replay 권한/API를 추가하지 않는다. 미해결 case는 명시적 운영 복구가
  완료될 때까지 보존하며 자동으로 다시 열거나 해결됐다고 표시하지 않는다.

2026-09-10 handoff failure isolation amendment:

- 개별 publication 인계 실패는 해당 transaction을 rollback하고, 같은 batch의 나머지
  인계와 자동 재시도 실행을 중단하지 않는다. 실패 publication은 기존 실패 상태와
  attempt를 유지하며 다음 tick에 인계를 다시 시도한다.
- worker는 publication ID와 원인을 포함한 인계 예외를 모아 자동 재시도와 backlog 지표
  갱신 뒤 다시 던진다. 실패 tick을 성공으로 반환하거나 실패 건의 인계 성공 log/counter를 기록하지 않는다.
- 자동 재시도 또는 지표 조회도 실패하면 그 예외에 인계 실패를 suppressed exception으로
  보존해 함께 노출한다. JVM Error는 복구 가능한 건별 실패로 취급하지 않는다.

## Alternatives Considered

- 동기 호출만 사용
- 초기 Kafka
- Modulith event와 필요 지점의 영속 publication

## Rationale

정합성 문제를 다루면서 운영 복잡도를 단계적으로 도입한다.

## Consequences

- broker 기반 확장성과 격리를 초기에는 얻지 못한다.
- 이벤트 소비자 멱등성을 여전히 구현해야 한다.
- stable listener ID는 persistent contract이므로 단순 class/method rename과 별도로 관리해야 한다.

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

## 2026-09-10 명시적 운영 복구 API

ADR-124에 따라 전용 권한·사유·멱등 키와 현재 Case version으로 원본 publication 한 건의 추가 시도를 예약한다.
기존 자동 후보 제외는 유지하며 명시적 RUNNING 요청 원장과 baseline attempts가 일치하는 경우만 별도 후보로
선택한다. 기존 registry가 실제 claim/resubmit/completion을 처리하며 payload·listener·누적 attempts를 초기화하지
않는다. 완료와 재실패를 Case에 반영하고 실행 결과가 불명확한 경우 RUNNING을 유지한다.
