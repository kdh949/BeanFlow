# ADR-114: 공유 Spring 테스트 Context와 명시적 격리 예외

- **Status:** Accepted
- **Date:** 2026-08-19
- **Implementation owner:** [안전장치 중복 소유권 집중화](../exec-plans/active/safety-guardrail-ownership-consolidation.md)

## Context

BeanFlow의 모든 `@SpringBootTest`는 test class 이름을 Spring Context cache key에 넣고 class마다
별도 PostgreSQL database를 만든다. 이 방식은 테스트 사이의 상태 누출을 막지만, 같은 application
구성을 사용하는 테스트도 Context 시작, Flyway migration과 database drop을 반복한다. 다수의 테스트는
이미 `@BeforeEach`에서 fixture를 초기화하면서도 class 종료 시 Context를 다시 dirty 처리한다.

격리를 전부 제거하면 실제 commit 가시성, 별도 thread, `REQUIRES_NEW`, startup 또는 동시성 검증이
rollback 안에서 거짓으로 통과할 수 있다. 반대로 전역 truncate나 schema routing framework는 migration
seed와 transaction 의미를 별도로 복원해야 하므로 현재 문제보다 큰 테스트 인프라를 만든다.

## Decision

### 1. 모든 Spring 통합 테스트는 격리 방식을 명시한다

- `@BeanflowSharedDatabaseTest`는 test-managed transaction을 열고 test method 종료 시 rollback한다.
- `@BeanflowIsolatedSpringContext(reason)`은 class별 Context와 database를 유지한다.
- raw `@SpringBootTest`만 선언하고 두 marker 중 어느 것도 선언하지 않은 class는 구조 검증에서 실패한다.

### 2. 공유 대상

다음 조건을 모두 만족하면 공유한다.

- application 호출과 MockMvc 요청이 test thread에서 동기적으로 끝난다.
- 검증 대상 변경이 test transaction에 참여한다.
- 실제 commit 이후 다른 connection의 가시성이나 process restart를 검증하지 않는다.
- 가변 test double은 method 종료 뒤 deterministic하게 reset할 수 있다.

### 3. 격리 대상

다음 중 하나라도 해당하면 class별 Context/database를 유지하고 `reason`에 근거를 기록한다.

- 다른 thread 또는 connection을 사용하는 lock·race·concurrency 검증
- `REQUIRES_NEW` commit이나 transaction 종료 이후 상태를 검증
- application startup, bootstrap, fail-fast 또는 Context lifecycle 검증
- DDL, Flyway migration, database extension 또는 schema 변경
- reset 계약으로 복구할 수 없는 test-specific singleton 상태

### 4. test double 상태

공유 Context의 가변 fake는 test 전용 `ResettableTestDouble`을 구현한다. TestExecutionListener는 각
test method 뒤 해당 bean만 reset한다. payload, repository, audit 또는 fixture를 일반화하는 framework는
추가하지 않는다.

### 5. CI 실행 정책

Testcontainers는 Gradle worker 안에서 병렬화하지 않는다. `maxParallelForks = 1`, timing 기반 runner-level
LPT shard와 모든 compiled test class의 exact coverage 검증을 유지한다. Context 공유는 이 정책의 대체가
아니라 단일 shard 내부 반복 시작 비용을 줄이는 별도 변경이다.

## Alternatives Considered

- **모든 class 격리 유지:** 가장 단순하지만 중복 Context와 migration 비용을 계속 지불한다.
- **모든 table 전역 truncate:** migration이 만든 reference/seed row를 구분하고 복구해야 하므로 제외한다.
- **class별 schema routing DataSource:** transaction과 connection routing 인프라가 과도해 제외한다.
- **모든 테스트 공유:** 실제 commit·동시성·startup 경계를 훼손하므로 제외한다.

## Rationale

rollback은 PostgreSQL과 Spring transaction 의미를 그대로 사용하며 별도 cleanup DSL을 만들지 않는다.
예외를 marker와 reason으로 명시하면 빠른 기본 경로와 실제 환경 검증을 함께 유지할 수 있다.

## Consequences

- 기존 `@SpringBootTest`를 shared 또는 isolated로 분류해야 한다.
- 공유 Context의 test double은 reset 계약을 구현해야 한다.
- 격리 대상은 계속 class별 Context와 database 비용을 부담한다.
- 직접 `@DirtiesContext` 선언은 제거하고 격리 listener 한 곳이 lifecycle을 소유한다.

## Verification

- marker 누락·중복을 거부하는 구조 테스트
- 공유 테스트 반복·순서 변경 실행과 rollback 확인
- 격리 테스트의 database 생성·drop 확인
- stateful test double reset 확인
- `verifyCiTestShards` exact class coverage

## Metrics

동일 환경에서 전후 full-suite 시간, Spring Context 수와 PostgreSQL database 생성 횟수를 기록한다.
수치 개선율은 acceptance gate로 사용하지 않는다.

## Revisit Conditions

- MockMvc 또는 application 호출이 별도 server thread로 이동하는 경우
- 테스트가 병렬 실행되거나 runner 안에 두 개 이상의 Gradle fork를 도입하는 경우
- shared test에서 반복 가능한 상태 누출이 발견되는 경우

## Related Decisions

- [ADR-009](ADR-009-explicit-failure-semantics.md) — 실패를 성공으로 위장하지 않는 원칙
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md) — 검증형 실행과 Draft stack
- [Definition of Done](../testing/definition-of-done.md) — 실제 실행한 검증과 성능 증거
