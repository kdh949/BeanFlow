# ADR-111: 제품화 Stack A의 검증형 Draft PR 체인

- **Status:** Accepted
- **Date:** 2026-08-12
- **Implementation owner:** documentation and delivery workflow

## Context

제품화 Plan 00~60은 디자인 계약, 공개 주문번호, Session 인증, 고객·점주 계정, 고객 주문 목록과
점주 주문보드를 순서대로 연결한다. Plan 10~60은 모두 Flyway migration을 쓸 수 있어 서로 다른
latest-main branch에서 병렬 실행하면 migration 번호와 schema baseline이 경쟁한다.

[ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)는 일반 candidate를 최신 `main`에서
시작하고 Plan 40→50만 Draft stack 예외로 허용한다. 이번 실행은 Plan별 review diff를 유지하면서
Plan 00~60의 검증을 한 흐름으로 계속하고, 마지막에 전체 변경을 하나의 release PR로 검증하려 하므로
범위가 명시된 추가 예외가 필요하다.

## Decision

### Exact scope와 순서

**제품화 Stack A**는 다음 implementation ExecPlan만 고정 순서로 실행한다.

```text
productization-00-design-capability-contract
→ productization-10-public-order-reference
→ productization-20-authentication-foundation
→ productization-30-customer-account-and-login
→ productization-40-merchant-account-and-initial-password
→ productization-50-customer-order-read-model
→ productization-60-store-order-board
```

Plan 70, 80, 90과 100은 이 stack에 포함하지 않는다. dependency graph상 여러 plan이 ready여도
executor는 위 순서에서 아직 완료되지 않은 첫 plan 하나만 선택한다. orchestration plan은 실행 후보가
아니다.

### Stack root와 branch/PR 체인

Goal 시작 전에 clean하고 push된 `feature/productization-plans` commit을 **stack root**로 기록한다.
기록 값은 branch 이름뿐 아니라 commit SHA와 당시 `origin/main` SHA를 포함한다. 구현 branch와 Draft PR
base는 다음으로 고정한다.

| Plan | Branch | Draft PR base |
|---|---|---|
| 00 | `feature/productization-00-contract` | recorded stack root branch |
| 10 | `feature/productization-10-order-reference` | Plan 00 branch |
| 20 | `feature/productization-20-auth-foundation` | Plan 10 branch |
| 30 | `feature/productization-30-customer-account` | Plan 20 branch |
| 40 | `feature/productization-40-merchant-account` | Plan 30 branch |
| 50 | `feature/productization-50-customer-orders` | Plan 40 branch |
| 60 | `feature/productization-60-store-order-board` | Plan 50 branch |

각 branch는 직전 plan의 verified completion commit에서만 만든다. 중간 PR은 모두 Draft이고 merge,
deploy 또는 base 변경을 자동으로 수행하지 않는다. 같은 plan branch가 이미 있거나 remote head가 local
예상 SHA와 다르면 재사용·force-push하지 않고 중단한다.

### Stack 내부 completion

stack branch에서 plan을 `COMPLETED`로 이동할 수 있는 조건은 다음과 같다.

1. 해당 ExecPlan의 Required Tests와 Validation Commands가 실제로 통과했다.
2. `Progress`, `Surprises & Discoveries`, `Decision Log`, `Outcomes & Retrospective`가 실제 결과와
   실행한 검증을 기록한다.
3. 같은 atomic completion commit에서 active→completed 이동, status/date 변경, 모든 direct
   successor path 갱신, 새로 ready가 된 successor의 `Implementation-Ready=true`와 문서 graph 검증을
   함께 수행한다.
4. completion commit의 parent가 이 ADR에 지정된 exact predecessor head다.
5. commit과 Draft PR head가 remote에서 동일한 SHA로 확인된다.

Stack A branch의 `COMPLETED`는 **기록된 predecessor 위에서 구현과 검증을 마쳤다**는 뜻이다. `main`
merge, production deployment 또는 프로그램 완료를 뜻하지 않는다. `main`의 candidate graph는 final
release가 merge되기 전까지 이 stack 내부 completion을 보지 않는다.

### Migration-writer lease

Plan 00은 migration을 쓰지 않는다. Plan 10을 시작하기 직전에 repository-wide migration-writer lease를
획득하고 Stack A release가 merge되거나 stack이 명시적으로 폐기될 때까지 같은 lease를 유지한다.

- Plan 10은 recorded stack root와 recorded `origin/main`이 일치하는지 확인한 뒤 combined migration
  inventory의 마지막 번호 다음을 선택한다.
- Plan 20~60은 직전 stack head의 combined inventory에서 다음 번호를 선택한다.
- lease가 살아 있는 동안 unrelated schema-writing plan을 시작하지 않는다.
- 번호 예약 manifest, duplicate DDL, checksum repair, 이미 작성한 migration 재번호화로 경쟁을
  보정하지 않는다.

### Final combined release PR

Plan 60 검증과 completion commit 뒤 `feature/productization-stack-a-release` branch를 Plan 60 head에
만든다. recorded stack root commit이 현재 `origin/main`의 ancestor이고 full validation을 현재 main
대비로 다시 통과한 경우에만 이 release branch에서 `main` 대상 combined Draft PR을 생성한다.

root가 아직 main에 포함되지 않았거나 main이 recorded SHA에서 움직였다면 combined PR을 추측해 만들지
않고 중단한다. 사용자 승인 아래 restack/revalidation을 마친 뒤에만 release PR을 생성한다. 중간 Draft
PR은 combined release merge 전까지 닫거나 삭제하지 않으며 release merge 뒤 superseded로 정리한다.
automation은 어떤 PR도 merge하지 않는다.

### Mandatory stop conditions

다음 중 하나라도 발생하면 현재 성공 상태를 과장하거나 다음 plan으로 넘어가지 않고 Goal을 일시
정지한다.

- recorded stack root 또는 `origin/main` SHA 변경
- 다른 migration writer 발견 또는 lease 소유권 불명
- required test, build, OpenAPI parity나 문서 graph 검증 실패
- predecessor/base/head SHA 불일치, 기존 branch/PR 충돌 또는 restack 필요
- 중요한 제품·보안·정합성 결정을 추측해야 함
- migration 충돌, 적용 여부 불명 또는 외부 Provider 결과 `UNKNOWN`
- credential, approval 또는 GitHub 권한 부족

## Alternatives Considered

### Plan마다 latest main에 merge한 뒤 다음 plan 실행

ADR-072의 기본 경로이고 branch 수명이 짧다. 그러나 이번 실행이 원하는 plan별 Draft review와 마지막
combined release gate를 제공하지 않는다.

### Plan 00~60을 하나의 branch와 PR로 구현

branch 관리가 단순하지만 plan별 diff와 검증 checkpoint가 사라지고 실패 시 되돌릴 단위가 너무 커진다.

### 모든 ready plan을 병렬 stack으로 실행

Plan 10~60의 migration 번호와 schema baseline이 경쟁하고, GitHub PR의 단일 base 제약 때문에 자동
통합 기준을 안전하게 정할 수 없다.

## Consequences

- Plan별 Draft PR로 review 가능한 diff와 검증 checkpoint를 유지한다.
- stack tip에서 dependency와 completion evidence가 연속되므로 하나의 Goal이 다음 plan을 결정적으로
  선택할 수 있다.
- Plan 10 이후 final release까지 repository-wide migration writer lane을 독점한다.
- main이 바뀌면 자동 진행보다 restack/revalidation을 우선하므로 장기 stack의 중단 가능성이 높다.
- Plan 00~60 전체를 포함하는 final release PR은 크므로 중간 Draft PR review가 사실상 필수다.
- Stack A 완료는 P0 Core 중간 통합점이며 전체 제품화 프로그램 완료가 아니다.

## Verification

- 각 iteration 시작 시 recorded root/main/predecessor SHA와 clean worktree를 확인한다.
- `bash scripts/verify-docs.sh`로 active/completed path, metadata와 dependency graph를 검증한다.
- 각 plan의 required test와 validation 결과를 해당 ExecPlan과 orchestration Progress에 기록한다.
- Draft PR의 base/head와 local/remote commit SHA가 일치하는지 확인한다.
- final combined release PR 전 current main 대비 전체 build, OpenAPI parity, 구조 테스트와 문서 검증을
  다시 실행한다.

## Metrics

- plan별 validation 재실행 횟수와 실패 원인
- stack root/main drift로 중단한 횟수
- migration-writer lease 보유 시간
- plan별 Draft PR review 대기 시간
- final release diff 크기와 merge conflict 수

## Revisit Conditions

- Stack A가 5영업일 이상 지속되거나 unrelated migration을 차단할 때
- main drift 때문에 두 번 이상 restack이 필요할 때
- final release diff가 review 가능한 범위를 넘을 때
- Plan 60 이전에 독립 배포 가능한 안정적 수직 흐름이 확인될 때

## Related Decisions

- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
- [ADR-092](ADR-092-hybrid-authentication.md)
- [ADR-096](ADR-096-public-order-reference.md)
- [ADR-099](ADR-099-customer-order-read-model.md)
- [ADR-100](ADR-100-store-order-board-read-model.md)
