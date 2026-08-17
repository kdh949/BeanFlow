# CI 전체 테스트 게이트를 17분 안에 완료한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

BeanFlow의 backend PR은 267개 test class와 1,328개 test method를 실행하며, 최근
GitHub-hosted runner의 required CI가 약 26~28분 걸린다. 이 계획은 전체 backend test coverage를
유지하면서 변경 경로 분리, JVM 당 PostGIS server 공유, 실행 시간 기반 shard 배정으로
required `build` gate를 17분 안에 종료한다. Storybook interaction은 required로 유지하고
접근성 검사는 advisory로 분리해 알려진 위반을 후속 변경에서 처리할 수 있게 한다.

## Current State

- `origin/main` baseline: `32bbc6a864a86cc25081efc15986802d7466eb3f`.
- test inventory: 267 classes, `@SpringBootTest` 113 classes, direct PostgreSQLContainer 46 classes.
- CI는 `docs | full`만 구분해 backend-only 변경도 frontend/Storybook을 모두 실행한다.
- ruleset은 `build`만 required context로 지정하지만, 현재 `build` job은 test matrix 성공을
  집계하지 않는다.
- 3개 shard는 class count modulo로 배정하며 실행 시간을 반영하지 않는다.
- 성공한 run의 JUnit XML과 class timing을 보관하지 않는다.

## Definitions

- **required gate**: main ruleset이 요구하는 GitHub check context `build`.
- **critical path**: 첫 job의 `started_at`부터 required `build`의 `completed_at`까지.
- **direct container test**: test class가 `@Container PostgreSQLContainer`를 직접 소유하는 test.
- **LPT**: 실행 시간이 긴 class부터 현재 누적 weight가 가장 작은 shard에 배정하는
  Longest Processing Time first algorithm.

## Scope

### In Scope

- CI scope를 `docs | frontend | backend | full`로 분리
- required `build` aggregate gate
- JUnit XML과 class timing 증적
- JVM 당 PostGIS server 하나와 class 당 독립 database
- deterministic duration-weighted sharding, 필요 시 최대 6 runner

### Non-goals

- test 삭제, skip, affected-context만 선택 실행
- `maxParallelForks > 1`, larger/self-hosted runner
- `@DirtiesContext` 제거나 test layer 재설계
- production dependency, API, schema, Aggregate, transaction 변경
- H2, local DB, stale cache, fake/no-op fallback

## Business Rules and Invariants

- backend scope는 모든 compiled `*Test`, `*Tests`, `*Benchmark` class를 정확히 한 shard에 배정한다.
- test failure, Docker failure, DB create/drop failure, malformed timing evidence를 success로 바꾸지 않는다.
- PostgreSQL/PostGIS image는 `postgis/postgis:17-3.5`를 유지한다.
- 하나의 server process를 공유해도 database state는 test class별로 격리한다.
- push와 `workflow_dispatch`는 항상 `full`이다. unknown/malformed compare도 `full`이다.

## Architecture and Transaction Boundaries

Workflow는 `preflight`, `frontend`, `backend-build`, `test`, aggregate `build`로 나눈다. `frontend`는
interaction을 접근성 검사 없이 required로 실행한 뒤, 기본 `error` 접근성 검사를 advisory step으로
다시 실행한다. aggregate `build`는 `always()`로 실행하고 scope별 allowed result matrix를 검증한다.
test JVM은 하나의
manual-lifecycle PostGIS server를 소유하고 Spring context와 direct test가 같은 runtime을 쓴다.
production transaction과 runtime bean graph는 변경하지 않는다.

## Alternatives Considered

- test 삭제/targeted backend: 금융·동시성 회귀 범위를 줄여 기각.
- timeout만 증가: feedback latency를 해결하지 못해 기각.
- Gradle worker 병렬: worker별 container가 늘어 hosted runner를 정체시킬 수 있어 기각.
- Actions service database: local/Testcontainers parity와 class isolation을 없애 기각.
- runner만 증가: 고정 startup 비용을 먼저 줄인 뒤 3→6개 범위에서만 사용.

## Failure Semantics

- classifier 입력 누락, unknown status/path, empty compare는 `full`.
- required gate는 scope에 필요한 job의 `failure`, `cancelled`, `skipped`, empty result를 모두 실패로 처리한다.
- scope에 필요 없는 job의 `skipped`만 성공으로 허용한다.
- Storybook interaction failure는 required frontend failure다. a11y advisory step의 위반은 step outcome과
  artifact로 남기되 `continue-on-error`로 required frontend result를 실패시키지 않는다.
- timing XML이 없거나 malformed면 timing step을 실패시켜 증적 누락을 숨기지 않는다.
- shared server/database create/drop failure는 test failure로 전파하고 다른 DB로 대체하지 않는다.

## Data and Migration

Production migration은 없다. timing weight TSV는 test class FQCN과 median seconds만 담는다.

## API and Event Contracts

Public API/event 변경은 없다. 내부 CI contract는 scope enum, timing TSV, Gradle shard properties,
required `build` result다.

## Milestones

1. timing evidence helper와 artifact를 추가하고 동일 SHA baseline run 3회를 수집한다.
2. scope classifier와 required aggregate gate를 regression fixture로 구현한다.
3. shared PostGIS runtime을 추가하고 direct container test 46개를 이전한다.
4. candidate timing median으로 LPT shard를 구현한다.
5. 3개 shard부터 동일 SHA 3회를 측정하고 필요할 때 4, 5, 6개로 늘린다.
6. 17분 목표와 모든 validation을 통과하면 plan을 completed로 이동한다.

## Required Tests

- classifier docs/frontend/backend/full, mixed, rename/copy, unknown/malformed
- required gate의 scope별 success 및 failure/cancelled/skipped/empty matrix
- required Storybook interaction과 advisory a11y failure의 분리
- timing XML success, test failure, partial, empty, malformed
- shared server identity, database isolation, create/drop
- representative migration, Spring integration, concurrency
- `verifyCiTestShards`, backend full suite, build, docs verification

## Validation Commands

```bash
bash scripts/ci/test-ci-scripts.sh
./gradlew verifyCiTestShards --stacktrace --console=plain
./gradlew test --tests '*PostgresTestRuntimeTest' --stacktrace --console=plain
./gradlew test --tests '*CustomerOrderQueryMigrationTest' --tests '*ApplicationContextTests' --tests '*CreateOrderConcurrencyTest' --stacktrace --console=plain
./gradlew build -x test --stacktrace --console=plain
bash scripts/verify-docs.sh
git diff --check origin/main...HEAD
```

Docker가 있으면 local full `./gradlew test`를 실행한다. 없으면 Blocked로 기록하고
hosted full suite를 required evidence로 삼는다.

## Observability

- workflow summary: scope, compare, applicable jobs, test totals, top 20 slow classes, unmeasured classes
- 14-day artifact: raw JUnit XML, class timing TSV, Gradle log
- failure artifact: Docker info/container list와 Gradle reports
- ExecPlan: baseline/candidate critical path, shard duration, total runner-minute

## Documentation Updates

- `docs/decisions/minor-decisions.md`: MD-2026-032
- 이 ExecPlan의 Progress, Surprises, Decision Log, Outcomes
- 성공 시 active에서 completed로 이동

## Progress

- [x] (2026-08-17) 최신 `origin/main` `32bbc6a` 기준 clean worktree 준비
- [x] (2026-08-17) 현재 workflow, ruleset, test inventory와 최근 hosted run 시간 조사
- [x] (2026-08-17) timing evidence와 instrumentation-only baseline 3회 순차 수집
- [x] (2026-08-17) `docs | frontend | backend | full` scope와 required `build` aggregate gate 구현
- [x] (2026-08-17) Spring integration과 direct container test 46개를 JVM singleton PostGIS + class DB runtime으로 통합
- [x] (2026-08-18) 세 hosted success run의 class median과 p95 fallback을 사용하는 deterministic LPT shard 구현
- [x] (2026-08-18) 사용자가 hosted critical path 기준을 17분으로 개정하고 Storybook a11y를 advisory로 분리하기로 결정
- [ ] local/hosted validation과 17분 gate 완료

## Surprises & Discoveries

- main ruleset은 `build`만 required이지만 현재 `build` job은 test matrix와 독립이다.
- 이미 runner-level 3-way shard를 사용해도 최근 성공 run의 critical path는 26~28분이다.
- direct PostgreSQLContainer test class가 46개이며, 이 중 39개는 migration test다.
- instrumentation-only SHA `0ae00f9`의 첫 baseline run `32008419922`는 test matrix 전에
  `origin/main`에서 유입된 Storybook color-contrast 회귀로 실패했다. 동일 main SHA의 push run
  `32007692607`도 같은 단계에서 실패했으므로 계측 변경의 회귀가 아니다.
- scope/gate 분리까지 적용한 동일 SHA `1cf706a`의 baseline run `32008907327`, `32011485748`,
  `32013956298`은 세 test shard와 backend build가 모두 성공했다. workflow 전체 결론은 세 번 모두
  위와 같은 upstream frontend 회귀 때문에 실패했다.
- baseline critical path는 `26m00s`, `26m08s`, `26m36s`이고 median은 `26m08s`다. shard wall time은
  run별로 `[20m11s, 17m13s, 25m32s]`, `[15m41s, 15m42s, 25m36s]`,
  `[26m06s, 20m59s, 25m19s]`이며 total runner-minute는 각각 `66.83`, `60.57`, `75.85`다.
  각 run의 timing TSV는 동일한 기존 267개 class를 정확히 한 번씩 포함했다.
- class-specific Spring context가 cache에 남으면 Hikari connection과 database cleanup이 class 종료보다
  늦어진다. test class identity를 context key에 넣고 after-class listener가 context를 dirty-close한 뒤
  실제 database 부재까지 확인하도록 했다.
- shared runtime local full suite는 267개 기존 class와 runtime regression class 2개를 단일 PostGIS server에서
  모두 통과했지만 단일 JVM wall time은 `51m 5s`였다. hosted acceptance는 runner-level shard로 판단한다.
- 첫 hosted shared-runtime run `32016311338`의 shard 0은 DB/runtime 문제가 아니라 opaque cursor
  Base64/HMAC 문자열에 우연히 `1000`이 포함돼 기존 privacy assertion이 실패했다. payload의 민감 필드명
  부재를 검증하도록 `1286b9a`에서 결정화했고 대상 통합 테스트를 local에서 3회 연속 통과시켰다. 이 run은
  weight 근거에서 제외하고 새 SHA의 세 run을 다시 수집한다.
- shared-runtime SHA `1286b9a`의 성공한 backend test run `32037866754`, `32039618297`,
  `32041233952`는 모두 269개 class를 정확히 한 번 실행했고 failure/error가 0이었다. critical path는
  `28m39s`, `28m07s`, `28m31s`로 median `28m31s`, total runner-minute는 `83.13`, `81.97`,
  `76.07`로 median `81.97`이다. workflow 전체 결론은 upstream frontend 회귀 때문에 실패했다.
- 위 세 run의 class별 median을 `scripts/ci/test-class-weights.tsv`에 기록했다. LPT estimated class-time은
  3개 shard `23m38s`, 4개 `17m44s`, 5개 `14m11s`, 6개 `11m49s`이고 각 경우 269개 class가
  정확히 한 번 배정된다. hosted wall time은 계획대로 3개부터 순서대로 측정한다.
- LPT 3-shard SHA `5349b26`의 run `32048400652`, `32050473251`, `32052563076`은 모두 backend
  test 269개 class를 정확히 한 번 실행했고 failure/error가 0이었다. critical path는 `25m32s`,
  `26m41s`, `26m45s`로 median `26m41s`, total runner-minute는 `78.30`, `75.27`, `79.57`로
  median `78.30`이다. 목표를 넘겨 계획대로 4-shard로 증가한다.
- 같은 SHA의 run `32042924499`는 GitHub incident 중 action archive 다운로드가 429/503으로 세 번
  실패해 test 시작 전에 종료됐다. 공식 status가 Actions/API major outage와 archive download 약 50%
  오류율을 공지한 infrastructure failure라 측정 표본에서 제외하고 남은 job은 취소했다. Actions/API가
  operational이고 codeload가 HTTP 200으로 회복된 뒤 위 세 run을 새로 순차 실행했다.
- LPT 4-shard SHA `2bc6ad3`의 run `32055006148`은 backend test 269개 class를 정확히 한 번 실행했고
  failure/error가 0이었다. shard wall time은 `15m20s`, `17m43s`, `20m01s`, `18m42s`, critical path는
  `20m35s`, total runner-minute는 `75.68`이다. 목표를 넘겨 계획대로 5-shard로 증가한다.
- LPT 5-shard SHA `c39d035`의 run `32057022984`는 backend test 269개 class와 1,334개 test를 정확히
  한 번 실행했고 failure/error가 0이었다. shard wall time은 `17m09s`, `13m47s`, `17m21s`,
  `13m44s`, `12m37s`, critical path는 `17m55s`, total runner-minute는 `78.57`이다. 목표를 넘겨
  계획의 마지막 단계인 6-shard로 증가한다. workflow 전체 결론은 upstream frontend 회귀 때문에 실패했다.
- LPT 6-shard SHA `eaa072f`의 run `32058946545`는 backend test 269개 class와 1,334개 test를 정확히
  한 번 실행했고 failure/error가 0이었다. shard wall time은 `10m18s`, `14m26s`, `13m24s`,
  `13m31s`, `14m31s`, `14m38s`, critical path는 `15m15s`, total runner-minute는 `85.02`다.
  당시 15분 목표는 `15s` 넘었고 workflow 전체는 동일 upstream frontend 회귀로 실패했다. baseline median
  대비 critical path는 `26m08s`에서 `15m15s`로 `41.6%` 줄었지만, runner-minute는 `66.83`에서
  `85.02`로 `27.2%` 늘었다. 2026-08-18 사용자 결정으로 수용 기준은 17분으로 개정됐으며, 최종
  candidate SHA에서 세 run을 다시 측정한다.

## Decision Log

- 2026-08-17: backend change는 전체 test를 유지하고 선택 실행을 도입하지 않는다.
- 2026-08-17: 15분을 hard target으로 두고 runner는 측정 후 최대 6개까지 허용한다. 이 시간 기준은
  2026-08-18 결정으로 17분에 supersede됐다.
- 2026-08-17: required context 이름 `build`를 유지하고 aggregate semantics로 바꾼 ruleset write를 피한다.
- 2026-08-17: success artifact도 14일 보관하여 duration weight와 후속 회귀를 재현한다.
- 2026-08-17: upstream frontend failure가 backend 증적 수집까지 막지 않도록 frontend를 독립 job으로
  분리하되, `full` aggregate gate는 frontend failure를 그대로 실패로 유지한다.
- 2026-08-17: Spring context 하나가 datasource 하나를 소유하므로 class별 DB를 보장하기 위해 test-only
  context cache key와 after-class cleanup listener를 사용한다. production bean graph에는 관여하지 않는다.
- 2026-08-18: 계획상 최대인 6-shard에서 첫 candidate run이 15분을 넘었으므로 테스트 생략이나 추가 runner를
  도입하지 않는다. 동일 SHA 세 run 각각 15분 이내가 이미 불가능하고 upstream frontend gate도 실패하므로
  추가 hosted run과 Draft PR 생성을 중단하고 실제 증거와 함께 ACTIVE/blocked로 남겼다. 이후 사용자가
  17분 기준과 a11y advisory 분리를 승인해 이 차단 결정은 supersede됐다.
- 2026-08-18: 동일 candidate SHA 세 run 각각 17분 이내를 완료 기준으로 사용한다. Storybook interaction은
  required로 유지하고 a11y는 기본 `error` 검사를 advisory step에서 실행해 위반 신호를 보존하되 required
  `build`를 막지 않는다. 기존 색상 대비 수정은 후속 PR 범위다.

## Outcomes & Retrospective

전체 테스트 의미와 269개 class coverage를 유지하면서 baseline median `26m08s`를 6-shard candidate
`15m15s`까지 줄였다. 개정된 17분 기준의 동일 candidate SHA 세 run과 advisory a11y를 포함한 full workflow
성공 증거를 수집한 뒤 최종 결과를 작성한다.

## Revision Notes

- 2026-08-17: 최신 main 조사와 사용자가 확정한 coverage, 15분, 최대 6 runner, Draft PR 결정을 반영해 초안 작성.
- 2026-08-18: 사용자 승인에 따라 시간 기준을 17분으로 개정하고 Storybook a11y를 advisory로 분리하는 완료 조건을 반영.
