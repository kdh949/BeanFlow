# 안전장치의 중복 소유권을 테스트와 계약 검증 경계로 집중한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 다섯 개의 선형 Draft PR 동안 `Progress`,
`Surprises & Discoveries`, `Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신한다.

## Purpose / Big Picture

BeanFlow가 보호하는 멱등성, transaction, database constraint, 인가, 감사와 명시적 실패 의미는
유지하면서 같은 보장을 여러 test class와 스크립트가 형식까지 반복 검증하는 비용을 줄인다.
완료 후 Spring 통합 테스트는 shared rollback 또는 명시적 class isolation 중 하나를 선언하고,
migration·문서·OpenAPI 계약은 적은 수의 semantic validator가 소유한다. 세 Support Application
Service와 Store Order Board는 기존 public 진입점을 유지한 채 유스케이스와 표현 책임으로 나뉜다.

## Current State

기준선 commit은 `94f6787628cc5f166fee5e3df5d24934a6926b9c`다.

- `@SpringBootTest` source 115개가 class 이름을 Context cache key에 넣어 class별 Context/database를 만든다.
- 직접 `@DirtiesContext`를 선언한 source 57개는 global listener의 class 종료 cleanup과 중복된다.
- `scripts/ci/test-class-weights.tsv`에 timing이 있는 Spring test 114개의 합은 약 2,658.825초다.
- Flyway migration은 V1~V64이고 `*MigrationTest.kt`는 39개다. 해당 timing 합은 약 1,414.646초다.
- `scripts/verify-docs.sh`는 2,356줄이며 embedded Python이 문서, ExecPlan과 OpenAPI를 함께 검증한다.
- 기능별 `*OpenApiContractTest`는 17개이고 runtime parity test는 62개 `@MockitoBean`을 선언한다.
- Support Application Service 상위 세 파일은 각각 1,462, 1,279, 1,249줄이다.
- `StoreOrderBoard.tsx`는 473줄에서 API, polling, reconciliation, command와 rendering을 함께 수행한다.
- CI는 `maxParallelForks = 1`, timing 기반 6개 runner shard와 `verifyCiTestShards` exact coverage를 사용한다.

## Definitions

- **Shared test:** 하나의 cached Spring Context/database를 사용하고 test-managed transaction rollback으로
  method 사이의 persistent state를 격리하는 테스트다.
- **Isolated test:** 실제 commit, 별도 thread, startup 또는 schema lifecycle 때문에 class 전용
  Spring Context/database를 사용하는 테스트다.
- **Semantic contract:** 문장, 들여쓰기나 파일 배치가 아니라 path, method, field, constraint와 failure
  state 같은 동작 의미를 검증하는 계약이다.
- **Vertical PR:** 하나의 안전장치 소유권을 구현·테스트·문서와 함께 전달하는 stack 단계다.

## Scope

### In Scope

- Spring test shared/isolated classification, rollback과 test-double reset
- 부분 환불 대형 통합 테스트 책임 분리
- Flyway fresh smoke, 현재 schema invariant와 실제 upgrade/backfill 검증 분리
- 문서 validator 모듈화와 historical exact-text hard gate 제거
- OpenAPI semantic contract와 runtime mapping parity 단일화
- SupportActionRequest, SupportCompensation, SupportProfileChange 유스케이스 분리
- Store Order Board data hook, pure model, presentation component 분리
- 다섯 개 선형 Draft PR, 원자 커밋과 실제 검증 evidence

### Non-goals

- production DB schema 또는 Flyway migration 변경
- HTTP/OpenAPI wire shape, event, business state 또는 failure code 변경
- idempotency, audit나 persistence의 범용 framework 도입
- Testcontainers worker 병렬화, CI shard 제거 또는 자동 merge
- Store Order Board 시각 redesign이나 새 design token/component system

## Business Rules and Invariants

- Refund, Support action, compensation과 profile change의 same-key/same-payload replay는 기존 결과에
  수렴하고 changed payload는 기존 row를 바꾸지 않고 conflict로 실패한다.
- Audit와 owner state는 기존 local transaction에서 함께 commit 또는 rollback한다.
- 외부 Provider 결과 불명은 success/final failure로 추정하지 않는다.
- 실제 transaction, database constraint, authorization, outbox와 Testcontainers 검증을 제거하지 않는다.
- test 또는 문서 표현을 합치는 과정에서 DB/API/business source of truth를 새로 만들지 않는다.

## Architecture and Transaction Boundaries

- shared Spring test는 `@Transactional` test context에 참여하며 method 뒤 rollback한다.
- commit visibility, concurrency, `REQUIRES_NEW`, startup·DDL·migration test는 isolated marker를 사용한다.
- production Support facade는 기존 controller와 cross-context 주입 지점을 보존한다. 내부 use-case handler로
  위임하되 기존 `REQUIRED`, `MANDATORY`, `REQUIRES_NEW` 경계와 external call 위치를 유지한다.
- frontend hook은 server state와 command를 소유하고 presentation component는 props/callback만 소비한다.

## Alternatives Considered

- global truncate/schema routing: seed와 transaction 복구 framework가 필요해 제외했다.
- migration schema 전체 snapshot: 모든 column 배치를 새 brittle contract로 만들기 때문에 제외했다.
- OpenAPI Kotlin/Python validator 동시 유지: duplicate semantic ownership이므로 제외했다.
- 세 Support 서비스를 하나의 generic command engine으로 통합: domain payload와 failure rule을 숨겨 제외했다.
- 다섯 영역을 한 PR로 제출: review와 rollback 경계가 없어 선형 Draft stack을 선택했다.

## Failure Semantics

- shared test에서 state leak가 발견되면 isolated로 조용히 우회하지 않는다. 원인을 reset, transaction 또는
  실제 isolation requirement로 분류하고 reason을 기록한다.
- migration assertion은 대응 위치가 없으면 삭제하지 않는다.
- validator가 dependency나 OpenAPI reference를 읽지 못하면 hard failure이며 skip/success로 바꾸지 않는다.
- 서비스 분리 중 external failure를 catch/no-op 또는 local fallback으로 바꾸지 않는다.
- Storybook MCP가 없으면 PR 5 UI 구현과 완료를 `BLOCKED`로 보고한다.

## Data and Migration

production migration은 만들거나 변경하지 않는다. migration test는 fresh database와 targeted previous
version database를 계속 사용한다. applied checksum을 수정하거나 schema fixture로 실제 Flyway 실행을
대체하지 않는다.

## API and Event Contracts

target/runtime OpenAPI, generated frontend schema, controller route와 event payload는 바꾸지 않는다.
semantic validator는 path/method/operationId, security/idempotency parameter, response, required field,
enum과 explicit failure state만 중앙에서 검증한다. Runtime parity는 실제 Spring MVC mapping과 operation
inventory만 비교한다.

## Milestones

1. **PR 1 `feature/test-context-simplification`:** test isolation ADR/marker, rollback/reset, direct
   `@DirtiesContext` 제거와 PartialRefund test 분리.
2. **PR 2 `feature/migration-test-consolidation`:** PR 1 head 기반 fresh smoke, context invariant와
   upgrade/backfill assertion inventory 집중화.
3. **PR 3 `feature/docs-openapi-validation-simplification`:** PR 2 head 기반 Python validator 모듈화,
   historical prose gate와 feature string tests 제거, runtime parity 단순화.
4. **PR 4 `feature/support-use-case-split`:** PR 3 head 기반 SHA-256 helper와 세 Support facade 내부
   use-case 분리.
5. **PR 5 `feature/store-order-board-split`:** PR 4 head 기반 Storybook-first hook/model/presentation 분리,
   전체 stack 검증과 plan completion.

## Required Tests

- marker 누락/중복, shared rollback, isolated database drop와 ResettableTestDouble 반복 실행
- PartialRefund allocation/replay, point restoration, HTTP/authorization, outbox rollback, commit/concurrency
- Flyway fresh migrate/validate, financial/security constraints, actual upgrade/backfill/gate/restart
- docs links, ExecPlan metadata/status/dependency cycle, policy ID uniqueness, OpenAPI validation/reference
- runtime MVC/OpenAPI operation parity와 semantic feature contract
- Support command hash/replay, audit rollback, approval separation, provider failure/retry/manual review
- Store board sorting/reconcile, ETag/304, visibility polling, 403/409, idempotency rotation, overflow cursor
- Storybook loading/success/empty/error/permission/conflict/overflow/busy interaction와 a11y

## Validation Commands

각 backend PR:

- `./gradlew spotlessCheck`
- `./gradlew verifyCiTestShards`
- `./gradlew test`
- `./gradlew build -x test`
- `bash scripts/ci/test-ci-scripts.sh`
- `bash scripts/verify-docs.sh`
- `git diff --check <base>...HEAD`

PR 5 추가:

- `npm run typecheck`
- `npm run test:unit`
- `npm run check:design`
- `npm run build-storybook`
- `npm run test:storybook:docs`
- `npm run build`
- `npm run test:sites`
- Storybook MCP `get-changed-stories`, `preview-stories`, `run-story-tests(a11y=true)`

## Observability

production metric/log는 바뀌지 않는다. test evidence로 full-suite 시간, Spring Context cache 통계,
PostgreSQL database 생성 횟수와 class timing을 같은 환경에서 전후 기록한다. 측정 결과는 gate가 아니며
비교 조건이 다르면 성능 개선을 주장하지 않는다.

## Documentation Updates

- ADR-114와 ADR index
- test strategy/Definition of Done의 shared/isolated 기준
- docs validator의 canonical current-document/link source
- 이 ExecPlan의 단계별 Progress, discovery, decision과 actual validation
- PR별 dependency, rollback, non-goal과 remote CI evidence

## Progress

- [x] 2026-08-19: `origin/main`과 기준선 commit, test/migration/validator/service/frontend inventory 확인
- [x] 2026-08-19: 다섯 개 선형 Draft PR과 수치 비강제 성능 evidence를 사용자 결정으로 고정
- [x] 2026-08-19: 115개 Spring test를 shared 11개, isolated 104개로 분류하고 raw marker와 직접
  `@DirtiesContext`를 제거
- [ ] PR 1 부분 환불 test 분리와 전체 순서·반복 검증
- [ ] PR 2 migration assertion inventory와 중앙 검증
- [ ] PR 3 docs/OpenAPI validator 단일화
- [ ] PR 4 세 Support Application Service 분리
- [ ] PR 5 Store Order Board 분리, 전체 검증과 plan completion

## Surprises & Discoveries

- 2026-08-19: 115개 Spring test 중 대부분은 `@BeforeEach` fixture cleanup을 이미 갖지만 global
  customizer가 class 단위 Context reuse를 무조건 차단한다.
- 2026-08-19: 현재 CI workflow는 여섯 runner shard를 사용하고 Gradle 기본 assignment count는 세 개다.
  remote workflow가 명시한 count가 실제 실행 source이므로 둘을 추측해 합치지 않는다.
- 2026-08-19: 주문 생성·재주문 테스트를 test transaction으로 감싸면 `REQUIRES_NEW` 멱등 등록이
  미커밋 fixture를 기다렸다. 해당 클래스는 교착을 숨기지 않고 isolated로 유지했다.
- 2026-08-19: DB failure trigger가 같은 test transaction을 abort하는 테스트는 예외를 assertion한 뒤에도
  후속 SQL을 실행할 수 없다. DDL·강제 실패 테스트는 isolated 대상이다.
- 2026-08-19: 동일 구성을 가진 운영 Controller shared 테스트는 한 번 시작한 Context/database를 재사용한
  상태에서 순서 묶음 실행을 통과했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-19 | Accepted | shared rollback을 기본, 실제 commit/lifecycle만 explicit isolation | 안전장치 보존과 Context reuse 양립 | ADR-114 |
| 2026-08-19 | Accepted | 다섯 개 선형 Draft PR | 독립 review·rollback과 final integrated head 제공 | 사용자 결정 |
| 2026-08-19 | Accepted | 성능 개선율을 acceptance gate로 두지 않고 actual evidence만 보고 | 구조 정확성이 우선이며 환경 차이 과장 방지 | 사용자 결정 |
| 2026-08-19 | Accepted | Support 상위 세 서비스만 이번 분리 범위로 고정 | Support 전체 재구성 방지 | 사용자 결정 |
| 2026-08-19 | Accepted | CI LPT shard와 serial Testcontainers 유지 | coverage·container 안정성 안전장치 보존 | ADR-114 |

## Outcomes & Retrospective

아직 구현 중이다. 완료 시 각 PR URL, commit range, local/remote validation, 전후 측정과 남은 isolated
test 사유를 기록한다. Draft 생성이나 stack 내부 `COMPLETED`는 merge/release를 뜻하지 않는다.

## Revision Notes

- 2026-08-19: 사용자 승인 계획, 현재 main inventory와 ADR-114를 반영해 최초 작성.
