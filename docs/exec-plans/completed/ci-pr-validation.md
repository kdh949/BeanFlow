# Make PR validation fast and trustworthy

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-08-01`

Status: Complete

이 ExecPlan은 `.agent/PLANS.md`를 따른다. Progress, Surprises & Discoveries,
Decision Log와 Outcomes & Retrospective를 작업 중 계속 갱신한다.

## Purpose / Big Picture

모든 Pull Request에서 required `build` check는 계속 생성하되 문서와 목표 OpenAPI 계약만
바뀐 경우 Java, Gradle과 Docker를 준비하지 않고 정적 검증으로 merge 가능 상태에
도달한다. 코드나 DB, build 설정, CI 자체가 바뀌면 기존 전체 PostgreSQL Testcontainers
suite를 실행한다. 어떤 경로에서도 실패한 검증을 성공으로 보고하지 않는다.

## Current State

`.github/workflows/ci.yml`의 단일 `build` job은 모든 PR, `main` push와 수동 실행에서
`./gradlew clean build`, 문서 검증, whitespace 검증을 순차 실행한다. PR #21의 job은
2분 15초였고 Gradle step은 2분 3초, 문서 검증은 약 0.2초였다.

세 검증 명령은 `command | tee log`인데 pipeline failure 전파가 없다. PR #21과 merge
후 `main` run에서 `EventPublicationRecoveryIntegrationTest`가 실패해 Gradle이 nonzero로
끝났지만 `tee`가 zero를 반환해 required check는 success였다. `git diff --check`도 비교
revision 없이 깨끗한 checkout에서 실행되어 PR diff를 검사하지 않았다.

## Definitions

- **docs scope:** 변경된 모든 경로가 문서, 목표 OpenAPI, ExecPlan, 저장소 안내 문서 또는
  PR template allowlist에 포함되는 PR
- **full scope:** production/test code, migration, build/wrapper, workflow, CI script,
  repository metadata, unknown path 또는 분류 실패를 포함하는 변경
- **fail-closed:** 알 수 없거나 검사할 수 없는 상태를 빠른 경로로 추측하지 않고 전체
  build 또는 명시적 CI 실패로 처리하는 정책
- **required build:** main ruleset이 merge 전에 success를 요구하는 GitHub check context
  `build`

## Scope

### In Scope

- 검증 command의 exit status 보존과 failure artifact 활성화
- PR base/head에 대한 정확한 변경 분류와 whitespace 검사
- docs scope에서 Java, Gradle, Docker 단계 생략
- OpenAPI 3.1 정적 validator와 BeanFlow 계약 검증 fail-fast
- 정적 OpenAPI assertion을 DB 통합 테스트에서 문서 검증기로 이동
- event publication retry 통합 테스트의 비동기 관찰 race 제거
- 분류기, log capture helper와 negative validation 테스트

### Non-goals

- `main` push의 full build 정책 변경
- CodeQL default setup 변경
- product API, OpenAPI 내용, DB schema 또는 production transaction 변경
- Gradle remote build cache와 테스트 결과 cache 도입
- commit, push 또는 PR 생성

## Business Rules and Invariants

제품 Business Policy는 변경하지 않는다. event publication retry는 최초 실패 1회와 다섯
번의 resubmission 뒤 `completion_attempts = 6`, 동일 correlation의
`EVENT_PUBLICATION` manual-review case 1건, NotificationDelivery 1건을 보존한다.

CI 불변식은 다음과 같다.

- required `build` job은 docs scope에서도 실행 결과를 보고한다.
- 문서 검증, OpenAPI 검증, whitespace 또는 Gradle 중 하나라도 실패하면 job은 실패한다.
- 분류되지 않은 경로, 빈 diff와 base/head 해석 실패는 full scope다.
- rename과 copy는 source path와 destination path를 모두 평가한다.
- validator나 parser 부재를 정적 검증 성공으로 간주하지 않는다.

## Architecture and Transaction Boundaries

단일 GitHub Actions `build` job 안에서 checkout 후 PR scope를 계산한다. 문서/OpenAPI와
whitespace 검증은 항상 실행하고, Java/Gradle setup과 full build step만 scope output에
조건을 둔다. required check 이름이 바뀌지 않으므로 GitHub ruleset 변경은 없다.

production transaction 경계는 변경하지 않는다. event publication 테스트는 기존
transactional publish와 Modulith asynchronous listener completion을 관찰하되 listener
호출과 DB attempt 갱신이 모두 끝난 뒤 다음 retry를 시작한다.

## Alternatives Considered

- workflow `paths-ignore`: required workflow가 생성되지 않아 Pending merge block이 생길 수
  있어 제외한다.
- 모든 PR 전체 build: 안전하지만 문서 PR의 현재 지연을 유지하므로 제외한다.
- OpenAPI 변경에서 targeted Gradle test: Kotlin test compilation과 Docker context 비용이
  남고 목표 계약은 runtime 구현 완료를 뜻하지 않으므로 제외한다.
- validation output을 tee하지 않음: exit status는 단순하지만 failure evidence 요구를
  약화하므로 pipefail helper를 선택한다.

## Failure Semantics

CI helper는 실행한 명령의 nonzero status를 그대로 반환한다. 문서 validator dependency
설치 실패, YAML parse 오류, OpenAPI schema 오류와 local contract 오류는 모두 required
check failure다. fallback parser나 부분 검증 성공은 없다.

변경 분류 입력이 malformed이거나 compare revision이 존재하지 않으면 full scope를
출력한다. git diff 자체가 실패하면 workflow step이 실패하며 empty success로 바꾸지
않는다. full build 실패 시 Docker diagnostics와 build/test report를 artifact로 남긴다.

## Data and Migration

DB schema와 migration 변경은 없다. CI가 생성하는 `.ci-artifacts/`와 테스트 report만
일시적 runner artifact이며 저장소에 commit하지 않는다.

## API and Event Contracts

제품 HTTP/event 계약은 변경하지 않는다. `openapi/beanflow-v1.yaml`은 기존 내용 그대로
OpenAPI 3.1 validator와 BeanFlow local assertions의 입력이 된다.

CI 내부 interface는 다음과 같다.

- `scripts/ci/classify-changes.sh <base-sha> <head-sha>`: stdout에 `docs` 또는 `full`
- `scripts/ci/run-and-capture.sh <log-path> <command> [args...]`: command output을 log와
  stdout에 쓰고 command status를 반환
- workflow step output `scope`: `docs` 또는 `full`

## Milestones

### Milestone 1: Record the decision and add testable CI primitives

ExecPlan과 Minor Decision을 기록하고 log capture helper, change classifier와 shell regression
tests를 추가한다.

Observable result: 성공/실패 status 보존과 docs/full/rename/unknown 분류를 로컬에서
반복 검증할 수 있다.

### Milestone 2: Make OpenAPI validation self-contained

Python과 validator 버전을 고정하고 `verify-docs.sh`에서 parser 부재를 실패시킨다. 표준
OpenAPI 3.1 validation과 기존 Kotlin 정적 assertion을 Python 검증으로 옮긴다.

Observable result: valid spec은 통과하고 malformed YAML, broken ref, missing required
contract는 Gradle 없이 실패한다.

### Milestone 3: Route PR validation by risk

workflow에서 PR base/head를 classifier에 전달하고 docs scope에서 Gradle setup/build를
생략한다. push와 manual run은 full scope다. whitespace는 실제 compare range를 검사한다.

Observable result: required job 이름은 `build`로 유지되고 docs scope log에는 Gradle과
Docker 실행이 없으며 full scope는 전체 build를 실행한다.

### Milestone 4: Stabilize event publication recovery evidence

listener call completion과 `completion_attempts` persistence를 각각 기다린 뒤 다음 fake-clock
advance와 resubmission을 수행한다. retry budget과 manual-review assertions는 유지한다.

Observable result: 특정 integration test가 연속 10회 통과하고 전체 build에서도 같은
불변식이 유지된다.

### Milestone 5: Validate and close

shell tests, 문서/OpenAPI positive/negative tests, diff check, targeted test와 full build를
실행한다. 실제 결과와 실행하지 못한 검증을 기록한다.

## Required Tests

- capture helper success status, arbitrary failure status와 log content
- Markdown-only, OpenAPI-only, mixed docs, Kotlin, SQL, Gradle, workflow, CI script, unknown,
  source-code-to-docs rename 분류
- valid OpenAPI 3.1, malformed YAML, broken local ref, missing BeanFlow required contract
- PR/push/manual event scope 선택과 compare range
- event publication success-after-retry와 retry-exhaustion invariants
- 전체 Gradle build와 document verification

## Validation Commands

```bash
bash scripts/ci/test-ci-scripts.sh
python3 -m pip install -r scripts/ci/requirements-docs.txt
bash scripts/verify-docs.sh
git diff --check origin/main HEAD
./gradlew test --tests 'io.github.kdh949.beanflow.ordering.internal.EventPublicationRecoveryIntegrationTest'
./gradlew build --stacktrace --console=plain
```

Targeted test는 10회, full build는 3회 반복한다. Docker/Testcontainers provider가 없으면
실패를 숨기지 않고 `Not run` 또는 정확한 환경 실패로 기록한다.

## Observability

job summary에 scope, compare base/head, changed path count와 Gradle 실행 여부를 기록한다.
failure artifact에는 `.ci-artifacts/`, Gradle reports/results와 full scope에서만 수집한
Docker diagnostics를 7일 보존한다. 배포 후 첫 10개 PR에서 scope와 elapsed time을
관찰한다.

## Documentation Updates

- `docs/decisions/minor-decisions.md`: change-aware required check 결정
- 이 ExecPlan: 진행, 발견, 결과와 미실행 검증
- 필요 시 README의 로컬 CI parity 명령에서 `clean` 제거와 validator setup 추가

## Progress

- [x] (2026-08-01) Current workflow, ruleset와 PR #21 step timing 조사
- [x] (2026-08-01) tee pipeline가 Gradle test failure를 success로 위장한 증거 확인
- [x] (2026-08-01) MD-2026-002와 이 ExecPlan 작성
- [x] (2026-08-01) CI helper, classifier와 shell regression test 구현
- [x] (2026-08-01) pinned OpenAPI validator와 BeanFlow 정적 계약 검증 강화
- [x] (2026-08-01) required `build` job의 docs/full risk routing 구현
- [x] (2026-08-01) event publication integration test의 listener/DB 상태 관찰 race 제거
- [x] (2026-08-01) 로컬 required validation 실행과 결과 기록

## Surprises & Discoveries

- PR #21과 직후 main build는 각각 114 tests 중 같은 event publication recovery test가
  실패했지만 pipeline exit status 유실 때문에 success였다.
- 현재 `git diff --check`는 compare range가 없어 clean checkout에서 실질적인 검증을
  하지 않았다.
- PR #21에서 document verification은 약 0.2초였으며 OpenAPI YAML/local checks는
  이미 실행됐지만 dependency가 없으면 조용히 skip하는 branch가 있었다.
- Spring Modulith resubmission은 `completion_attempts`를 증가시킨 뒤 async listener가 끝나기
  전에 호출자에게 반환할 수 있다. 다음 retry 전에 listener call count, attempt count와
  incomplete publication의 `FAILED` 상태를 함께 기다려야 결정적이다.
- `openapi-spec-validator`의 깨진 local ref는 `OpenAPIValidationError`가 아니라
  `referencing.exceptions.Unresolvable`로 전파될 수 있어 이를 명시적으로 실패 진단에
  포함했다.
- `OrderControllerContractTest`는 main의 기존 ktlint 형식과 현재 formatter가 달라 파일을
  수정하면 Spotless가 전체 파일을 정규화한다. whitespace를 제외한 동작 변경은 OpenAPI
  파일 문자열 assertion과 전용 import 제거뿐이다.
- 변경되지 않은 source에서 새 문서 파일을 복사한 경우도 이전 경로를 평가하려면
  `--find-copies`가 아니라 `--find-copies-harder`가 필요하다.

## Decision Log

- 2026-08-01: required check context `build`를 유지하고 workflow-level path filter를 쓰지
  않는다. GitHub ruleset 변경과 Pending check 위험을 피한다.
- 2026-08-01: OpenAPI는 목표 계약이므로 docs scope에서 pinned static validator와 local
  assertions로 검증한다. runtime HTTP behavior는 code 변경의 full build에서 검증한다.
- 2026-08-01: push와 manual run은 항상 full scope, unknown PR change도 full scope다.
- 2026-08-01: test result cache는 정확성 trade-off 측정 전 도입하지 않는다.
- 2026-08-01: OpenAPI 파일만 읽는 Kotlin assertion은 정적 문서 검증기로 이동하고 실제
  HTTP, authorization와 DB 계약 테스트는 유지한다.

## Outcomes & Retrospective

required context `build`를 유지한 채 PR compare를 docs/full로 분류한다. 모든 PR에서 문서,
OpenAPI와 정확한 base/head whitespace 검증을 실행하고, full scope에서만 Java, Gradle과
Testcontainers 전체 build를 실행한다. `run-and-capture.sh`는 tee log와 원 명령 exit status를
모두 보존한다. full build의 불필요한 `clean`은 workflow에서 제거했다.

OpenAPI validation dependency는 Python 3.14,
`openapi-spec-validator==0.9.0`, `PyYAML==6.0.3`으로 고정했다. dependency 부재, malformed
YAML, broken ref, required path 누락과 BeanFlow create-order 계약 훼손이 모두 nonzero로
실패했다. 정상 spec은 19 paths와 59 schemas를 검증했다.

로컬 검증 결과는 다음과 같다.

- CI helper/classifier regression: 통과. Markdown-only, OpenAPI-only, mixed docs, Kotlin,
  migration, Gradle, workflow, CI script, unknown, code-to-docs copy/rename, empty/missing
  compare를 포함한다. helper는 의도적 status 23을 그대로 반환했다.
- PR #21 base/head fixture: `docs`로 분류했고 workflow 조건상 Java/Gradle/Docker step은
  실행되지 않는다.
- document/OpenAPI positive 및 네 negative fixture: 모두 기대대로 통과/실패했다.
- dependency absent fail-fast, workflow YAML/routing assertion, shell syntax, Spotless와
  `git diff --check origin/main`: 통과했다.
- `EventPublicationRecoveryIntegrationTest`: `cleanTest`로 10회 연속 통과했다. retry attempt
  6회, manual-review case 1건과 notification 1건 assertion은 유지한다.
- 전체 `cleanTest build --stacktrace --console=plain`: 최종 작업 트리에서 3회 연속 통과
  (약 64초, 59초, 32초). 각 회차에서 test task를 다시 실행했다.

GitHub-hosted runner의 docs-only required check 5회 30초 목표, 의도적 실패 PR의 red check와
artifact, 코드 PR의 기존 기준선 대비 시간, 배포 후 첫 10개 PR 관찰은 commit/push/PR을
생성하지 않았으므로 `Not run`이다. 원격 적용 후 job summary와 artifact로 확인해야 한다.
따라서 측정 전 성능 향상 수치는 주장하지 않는다.

## Revision Notes

- 2026-08-01: 초기 ExecPlan 작성. 조사된 timing, false-success failure와 확정된 fast-path
  정책을 반영했다.
- 2026-08-01: 구현 결과, async publication race의 원인, OpenAPI ref 예외 처리, 최종
  validation과 원격에서만 가능한 미실행 acceptance 항목을 기록하고 Complete로 변경했다.
