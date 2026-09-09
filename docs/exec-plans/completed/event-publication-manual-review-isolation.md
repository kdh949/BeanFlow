# 이벤트 자동 복구와 수동 검토 분리

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-09-09`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

재시도가 끝난 publication을 주기마다 다시 처리 대상으로 읽고 MANUAL_REVIEW log/counter를
반복 생성하는 동작을 제거한다. 수동 검토 backlog가 자동 복구 batch를 점유하지 않게 한다.

## Current State

`EventPublicationRecoveryWorker`의 ResubmissionOptions filter가 Operations case 생성,
보상 step 변경, counter와 log를 수행한다. Modulith 2.1은 filter보다 먼저 batch limit을
적용한다. 기존 등록 service는 source가 중복이어도 UUID만 반환하고, worker는 매번 전환으로
계수한다. `operations_reprocessing_case`에는 source unique constraint가 이미 있다.

## Definitions

- publication: event와 특정 listener 사이의 영속 전달 기록.
- 자동 복구: ADR-010의 bounded schedule에 따른 failed publication 재실행.
- 인계: EVENT_PUBLICATION 운영 case 생성과 해당 compensation step의 수동 검토 전환.

## Scope

### In Scope

자동 복구 query scope/adapter, bounded 인계, 원자적 등록, 전환 log, 별도 backlog gauge,
PostgreSQL 통합 회귀 검증, ADR-010 및 운영 설명.

### Non-goals

환불/정산 도메인 변경, 배포, 기존 운영 데이터 수정, 공개 수동 replay API, 새 broker/schema.

## Business Rules and Invariants

ADR-010의 10초/30초/2분/5분/15분 재시도를 유지한다. manual case가 있는 publication은
자동 실행하지 않는다. 미해결 이벤트를 완료로 위장하지 않는다. source당 운영 case는 하나이며
publication attempt를 보상 business attempt로 세지 않는다. 예약 Analytics는 보존한다.

## Architecture and Transaction Boundaries

Ordering worker가 인계 service와 자동 복구 scope를 조정한다. query repository는
publication 및 Operations case의 read projection을 읽는다. Operations는 case 쓰기를 소유한다.
인계 transaction은 publication row lock, case 등록, 매핑된 step 변경을 함께 commit한다.
commit 후에만 전환 telemetry를 남긴다. 자동 query용 repository decorator는 나머지
Modulith SPI를 기존 JPA 구현에 위임해 dispatch/claim/completion 경계를 유지한다.

## Alternatives Considered

메모리 Set이나 로그 throttling은 재시작과 다중 worker의 동일 인계를 보장하지 않는다.
predicate만 수정하면 batch starvation을 고치지 못한다. 별도 broker/queue schema는 기존
publication과 운영 case가 가진 상태를 중복하므로 추가하지 않는다.

## Failure Semantics

case/step 실패 시 인계 전체 rollback. 동시 tick은 row lock과 unique key로 중복 인계를
방지한다. query/deserialize 실패를 빈 결과로 바꾸지 않는다. scope는 finally로 해제한다.
로그 수집 exactly-once가 아닌 durable case를 기준으로 운영 상태를 판정한다.

2026-09-10 보완: 개별 인계 예외는 publication ID와 함께 수집하고 나머지 인계와 자동
재시도를 진행한다. backlog 지표 갱신 뒤 실패를 다시 던지며, 자동 재시도/지표 조회까지
실패하면 인계 예외도 suppressed exception으로 보존한다. 기존 인계 transaction과 DB 상태는
유지하고 별도 큐·스케줄러·스키마를 추가하지 않는다.

## Data and Migration

스키마와 기존 row 변경 없음. 기존 MANUAL_REVIEW case는 첫 query부터 자동 복구에서 제외된다.

## API and Event Contracts

Operations 내부 등록 API는 case ID와 새 전환 여부를 반환한다. 공개 HTTP/이벤트 계약은 유지한다.

## Milestones

1. ADR/계획 기록.
2. 조회 범위와 SPI adapter, 원자적 인계 및 worker telemetry 수정.
3. 반복/동시/rollback/backlog/정상 재시도 통합 검증.
4. 문서와 최종 diff 검증.

## Required Tests

- repeated tick과 새 worker에서 case, counter, transition log 증가 없음.
- 동시 인계 source당 case 하나 및 보상 business attempt 불변.
- case/step 인계 실패 시 rollback 후 복구 가능.
- batch보다 많은 수동 검토/예약/미도래 publication 뒤의 due event 재실행.
- 기존 정상 retry, exact listener, unknown target, reserved analytics 유지.
- 전체/자동/수동 지표 구분 및 scope 예외 복원.
- 인계가 계속 실패해도 같은 tick의 due publication은 재시도하고, 실패 case/step은 rollback됨.
- 여러 인계 실패와 자동 재시도/지표 실패가 함께 발생해도 원인과 publication ID가 보존됨.

## Validation Commands

`./gradlew test --tests '*EventPublicationRecoveryWorkerTest' --tests '*EventPublicationRecoveryIntegrationTest'
--tests '*EventPublicationRetryScheduleTest'`

`./gradlew spotlessCheck`; `bash scripts/verify-docs.sh`

추가 검증: `--tests '*CompensationPublicationTargetRegistryTest' --tests '*ModularityTests'`,
`--tests '*CustomerCancellationRefundExclusionIntegrationTest' --tests '*SettlementRefundAdjustmentIntegrationTest'
--tests '*OrderCompensationEventContractTest' --tests '*SpringTestIsolationArchitectureTest'`, `bootJar`.

## Observability

기존 전체 pending/oldest/max-attempt gauge 유지. 자동 복구 pending, manual-review pending/oldest
gauge 추가. 인계 commit 시 한 번만 exhaustion counter 증가. publicationId/eventId/listenerId/
correlationId/reason을 전환 log에 명시하고 UUID metric tag는 추가하지 않는다.

## Documentation Updates

ADR-010 amendment, 실행 결과와 운영 지표 의미를 기록한다.

## Progress

- [x] 기존 코드, Modulith 2.1 SPI와 ADR-010 확인.
- [x] 구현.
- [x] 테스트 35건, Spotless, 문서/OpenAPI, diff 검증.
- [x] 2026-09-10 인계 실패 격리 보완과 관련 테스트 39건, Spotless, bootJar, 문서 검증.

## Surprises & Discoveries

Modulith 2.1의 `processFailedPublications`는 DB batch 조회 후 predicate를 호출하므로 predicate
수정만으로는 수동 검토 backlog 격리가 불가능하다.

Kotlin interface delegation은 Java default method를 자동 전달하지 않는다. 초기 통합 검증에서
실패 상태 저장이 누락되는 현상을 확인했고, repository adapter의 state transition/count/query
default method를 기존 JPA repository에 명시적으로 위임한다.

## Decision Log

- 2026-09-09: 사용자 요청에 따라 자동 복구/수동 검토 분리 구현. 기존 JPA registry를
  위임하는 호출 범위 한정 query adapter와 기존 Operations source unique key를 사용한다.
- 2026-09-10: 개별 인계 실패가 독립 이벤트의 자동 재시도를 막지 않도록 batch 내 실패를
  수집하고 처리 종료 시 다시 노출한다. 단순 순서 교체는 뒤쪽 인계의 중단을 해결하지 못하므로
  채택하지 않는다. 기존 데이터 cutover와 실행계획 검증은 이번 보완 범위가 아니다.

## Outcomes & Retrospective

핵심 검증 Passed: EventPublicationRecoveryIntegrationTest 9건,
EventPublicationRetryScheduleTest 4건, CompensationPublicationTargetRegistryTest 3건,
ModularityTests 1건. 반복 tick 180회, 375건의 blocked backlog, 동시 인계,
인계 transaction rollback, 기존 explicit replay 보존을 포함한다.

기본 Gradle cache의 sandbox 쓰기 제한 때문에 `/private/tmp`의 별도 Gradle user home과
기존 dependency cache 복사본으로 검증했다. 기존 runtime이 제공하는 Modulith core SPI를
컴파일할 수 있도록 `compileOnly` 선언을 추가했으며 새 runtime artifact는 추가하지 않았다.
추가 영향 검증 Passed: CustomerCancellationRefundExclusionIntegrationTest 8건,
SettlementRefundAdjustmentIntegrationTest 8건, OrderCompensationEventContractTest 2건.
합계 35건의 테스트가 통과했다. `spotlessCheck`, `scripts/verify-docs.sh` 및
`git diff --check`도 통과했다. 전체 테스트 suite와 배포·운영 데이터 재처리는 Not run.
실제 운영 환경의 처리량/지연 개선은 측정하지 않았다.

2026-09-10 인계 실패 격리 보완: 수정 전 PostgreSQL 통합 테스트에서 인계 예외 때문에
첫 tick의 due publication이 진행하지 못하는 실패를 재현했다. 수정 후 같은 테스트는
두 tick의 인계 rollback과 독립 publication의 재시도·완료를 검증하여 Passed다.
여러 인계 오류와 자동 재시도/지표 조회 오류를 보존하는 worker 단위 테스트 3건도 Passed다.
기존 영향 테스트와 SpringTestIsolationArchitectureTest를 포함해 9개 class, 39건이
통과했으며 실패·오류·Skipped는 0건이다. `spotlessCheck`, `bootJar`, 문서/OpenAPI 검증과
`git diff --check`도 Passed다. 전체 backend suite, 배포, 운영 DB 점검과 실행계획 측정은 Not run.

## Revision Notes

- 2026-09-09: 최초 작성.
- 2026-09-09: 구현 및 관련 검증 완료, completed로 이동.
- 2026-09-10: 인계 실패 격리, 원인 보존과 관련 회귀 검증 완료.
