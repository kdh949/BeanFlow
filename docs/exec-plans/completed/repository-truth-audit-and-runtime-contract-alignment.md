# 저장소 사실과 Runtime 계약을 정합화한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-08-06`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

현재 `main`의 Controller mapping, OpenAPI, README, 아키텍처 입구 문서, ExecPlan과 품질
증거가 같은 source 사실을 가리키게 한다. production Kotlin 동작과 Flyway schema는 바꾸지
않는다. 현재 source에 구현된 HTTP operation은 `runtime` 계약으로 명명하고 Spring MVC의
실제 `RequestMappingHandlerMapping`과 operation 집합이 정확히 일치하도록 자동 검증한다.

## Current State

- 감사 기준은 `main`의 `f51db9e`이며 시작 시 작업 트리는 깨끗했다.
- 감사 전 `/api/v1` Controller mapping은 24 operations이지만 당시 source 계약은 14
  operations만 기록했다.
- 누락 operation은 일반 포인트 적립 정책 7개와 Settlement/Dispute 3개다.
- non-local deployment 증거는 없고 기존 파일 description도 repository source contract라고
  정의하므로 `deployed` 명칭은 실제 역할과 맞지 않는다.
- README, customer-cancellation master/readiness와 Analytics active plan에 완료된 구현을
  미완료로 기술하는 문장이 남아 있다.

## Definitions

- **Target contract:** Accepted 정책과 계획이 지향하는 `openapi/beanflow-v1.yaml`.
- **Runtime contract:** 현재 source의 public Controller mapping과 검증된 request/response shape를
  나타내는 `openapi/beanflow-v1-runtime.yaml`.
- **Operation parity:** 정규화한 `(path, HTTP method)` 집합이 Spring MVC와 runtime OpenAPI에서
  정확히 같은 상태다.

## Scope

### In Scope

- source 계약을 runtime 계약으로 rename하고 모든 repository link와 status를 갱신
- `RequestMappingHandlerMapping` 기반 operation parity test 추가
- README와 stale active/completed ExecPlan, quality/readiness graph 정정
- ADR 10개의 decision summary와 코드·migration·test 기반 capability map 작성
- docs verifier에서 manual controller allowlist 제거와 target/runtime OpenAPI 검증 유지

### Non-goals

- production Kotlin, Flyway migration, endpoint, event, table 또는 제품 정책 변경
- non-local 배포·운영·SLA 주장
- Analytics dependency 구조 분할 또는 Analytics 구현

## Business Rules and Invariants

- BR-14 C0/C1 commit gate와 recovery 상태, BR-15 부분 환불 allocation, BR-16~24의
  immutable Settlement/Adjustment/Dispute 원장을 변경하지 않는다.
- runtime contract는 구현되지 않은 Discovery와 PointAccount read operation을 포함하지 않는다.
- target contract는 runtime보다 앞선 Accepted 계약을 계속 보존한다.
- 실패를 빈 응답·0·fallback으로 바꾸지 않는다.

## Architecture and Transaction Boundaries

production transaction boundary는 변경하지 않는다. parity test는 Spring test context에서 실제
MVC handler metadata를 읽고 `/api/v1`만 선택한다. Actuator, error handler, framework mapping,
HEAD와 OPTIONS는 비교 대상에서 제외한다. runtime OpenAPI를 읽는 과정은 외부 network 또는
production dependency를 사용하지 않는다.

## Alternatives Considered

- `deployed` 이름과 manual allowlist 유지: 배포 증거를 오표현하고 두 번째 operation source를
  남기므로 제외한다.
- build-time OpenAPI generator 도입: request/response shape 생성 규칙과 dependency를 새로
  도입하므로 이번 사실 감사 범위를 넘는다.
- runtime 명칭과 HandlerMapping parity: source mapping을 자동 inventory로 사용하면서 명시적
  계약 shape를 보존하므로 선택한다.

## Failure Semantics

- Controller와 runtime operation이 한쪽에만 있으면 parity test가 missing/unexpected set을 출력하고
  실패한다.
- malformed runtime path/method 구조는 빈 operation 집합으로 통과하지 않고 parsing failure다.
- docs verifier는 target/runtime OpenAPI 3.1 validation과 reference 검증을 계속 fail-closed로 수행한다.

## Data and Migration

DB schema와 data migration 변경은 없다. 최종 diff에서 `src/main/**/*.kt`와
`src/main/resources/db/migration/*.sql` 변경이 0인지 확인한다.

## API and Event Contracts

- target: `x-beanflow-contract-status: target`
- runtime: `x-beanflow-contract-status: runtime`
- runtime operation은 `/api/v1` Controller operation과 정확히 같고 target operation의 부분집합이다.
- event payload와 producer/consumer contract는 변경하지 않는다.

## Milestones

1. HEAD와 기준 문서, ADR, Controller/Security, OpenAPI와 verifier를 감사한다.
2. runtime rename, 누락 operation과 HandlerMapping parity test를 구현한다.
3. README, customer-cancellation/Analytics plan과 quality graph를 현재 사실로 정정한다.
4. decision summary와 capability map을 코드·migration·test evidence로 작성한다.
5. focused test, clean build, docs verification과 최종 diff audit를 완료한다.

## Required Tests

- Spring MVC와 runtime OpenAPI의 normalized operation set exact equality
- target/runtime OpenAPI 3.1과 external/local reference validation
- Spring Modulith 구조 검증
- 전체 clean build와 문서 link/metadata graph 검증
- production Kotlin/SQL diff 0

## Validation Commands

```bash
./gradlew test --tests '*RuntimeOpenApi*' --tests '*OpenApi*' --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

runtime metric은 추가하지 않는다. test failure의 missing/unexpected operation set과 docs verifier
출력이 감사 증거다.

## Documentation Updates

- `README.md`, `docs/index.md`, API conventions와 Minor Decision
- customer-cancellation master/readiness, Analytics와 관련 completed plan의 stale 사실
- `docs/architecture/decision-summary.md`, `docs/architecture/capability-map.md`
- target/runtime OpenAPI와 `scripts/verify-docs.sh`

## Progress

- [x] 2026-08-06 HEAD/working-tree baseline 기록
- [x] 2026-08-06 기준 문서, ADR, active/completed plan, Controller/Security/OpenAPI 감사
- [x] runtime rename과 parity test
- [x] README/ExecPlan/quality graph 정합화
- [x] decision summary/capability map
- [x] focused/full validation과 final diff audit

## Surprises & Discoveries

- 기존 docs verifier의 14-operation manual allowlist가 통과하면서 실제 24 Controller operation 누락을
  숨겼다.
- 기존 `deployed` spec description은 이미 “repository source의 current public controller”라고
  정의해 파일명과 내용의 역할이 서로 달랐다.
- customer-cancellation master의 유일한 미완료 항목인 PointAccount read는 command/recovery의
  dependency가 아닌 독립 support-read slice다.
- 기존 PointAdjustment HTTP 경계 테스트가 고정 `2026-08-04` 시각을 실제 runtime Clock과
  섞어 2026-08-06부터 유효 Lot을 만료 Lot으로 만들었다. 해당 금액 경계 테스트만 injected
  Clock 기준 미래 만료를 사용하도록 바꿨다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-06 | Applied | source contract를 `runtime`으로 명명 | non-local deployment를 주장하지 않고 실제 역할을 표현 | Minor Decision |
| 2026-08-06 | Applied | operation inventory는 HandlerMapping parity test가 소유 | manual allowlist drift 제거 | 이 ExecPlan |
| 2026-08-06 | Applied existing | Analytics dependency path는 유지하고 완료 사실만 정정 | dependency split은 별도 후속 범위 | Goal non-goal |

## Outcomes & Retrospective

Target OpenAPI는 27 paths/29 operations, Runtime OpenAPI는 22 paths/24 operations으로
정합화됐다. MVC slice parity test가 모든 `/api/v1` mapping을 자동 inventory하고 두 operation
집합을 exact 비교하므로 docs verifier의 14-operation 수동 allowlist를 제거했다.

고객 취소 master는 command/recovery 완료로 이동했고 PointAccount read는 독립 Active plan으로
남겼다. Analytics와 Nearby plan의 completed dependency/readiness 문구를 현재 source에 맞췄으며,
핵심 ADR summary와 코드·migration·test 기반 capability map을 추가했다.

검증 결과:

- focused Runtime OpenAPI/OpenAPI/Modularity suite: 2 tests, failures/errors/skips 0, 7초
- 최초 `clean build`: 423 tests 중 1 failure, 3분 4초. 기존 PointAdjustment test의
  날짜 의존 만료 fixture가 원인이었다.
- 실패 test 단독 재검증: 1 test, failures/errors/skips 0, 42초
- 수정 후 `clean build`: 423 tests, failures/errors/skips 0, 2분 31초
- docs verification: target 27 paths/29 operations, runtime 22 paths/24 operations,
  32 policies, 75 ADRs, 149 Markdown files, 25 ExecPlans
- production Kotlin과 Flyway SQL diff: 0

실제 non-local deployment, 운영 traffic, SLA, load/latency와 Provider 장애 주입은 실행하거나
측정하지 않았다.

## Revision Notes

- 2026-08-06: repository truth audit를 self-contained 실행 계획으로 기록했다.
- 2026-08-06: runtime parity, 문서·plan 정합화, ADR summary/capability map과 전체 validation을
  완료해 completed로 이동했다.
