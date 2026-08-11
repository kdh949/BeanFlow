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
Plan 00~60의 검증을 한 흐름으로 계속하므로 범위가 명시된 추가 예외가 필요하다.

2026-08-12 실행 중 Support PR의 두 검증 commit은 Plan 00에 이미 포함됐지만 `origin/main`에는 아직
통합되지 않았음이 확인됐다. Support PR은 사용자가 별도로 merge하기로 했으며, Stack A가 이를 기다리거나
`main` 변화에 맞춰 restack하면 이미 검증한 predecessor tree와 migration 번호가 불필요하게 흔들린다.
따라서 최초의 recorded root와 combined release PR 정책을 아래의 provisional Plan 00 baseline과 정확히
일곱 개의 Draft PR 정책으로 개정한다.

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

### Provisional baseline과 branch/PR 체인

Plan 00의 verified completion head를 Stack A의 **provisional baseline**으로 기록한다. 다음 두 Support
commit이 그 head의 ancestor이고 필요한 Support 파일과 migration이 exact predecessor tree에 존재해야 한다.

- 구현: `35d662d0deb5808c0df12b3ae822d9ec128aa28e`
- 완료: `ae9fa0b9c97a75134131106a1818f04315611860`

두 commit이 `origin/main`의 ancestor가 아니거나 Support PR이 열려 있어도
`SUPPORT_INTEGRATION_PENDING`으로 기록하고 Stack A를 중단하지 않는다. observed `origin/main` SHA는
증거로 남기지만 이후 변화도 중단·restack·force-push 사유가 아니다. 구현 branch와 Draft PR base는
다음으로 고정한다.

| Plan | Branch | Draft PR base |
|---|---|---|
| 00 | `feature/productization-00-contract` | `main` |
| 10 | `feature/productization-10-order-reference` | Plan 00 branch |
| 20 | `feature/productization-20-auth-foundation` | Plan 10 branch |
| 30 | `feature/productization-30-customer-account` | Plan 20 branch |
| 40 | `feature/productization-40-merchant-account` | Plan 30 branch |
| 50 | `feature/productization-50-customer-orders` | Plan 40 branch |
| 60 | `feature/productization-60-store-order-board` | Plan 50 branch |

Plan 10~60 branch는 직전 plan의 verified completion commit에서만 만든다. 일곱 PR은 모두 Draft이고
automation은 merge 또는 deploy를 수행하지 않는다. 같은 plan branch가 이미 있으면 local·remote·PR
head가 expected predecessor tree와 정확히 일치할 때만 재사용한다. remote head가 local 예상 SHA와
다르면 force-push하지 않고 중단한다. PR head는 맞고 base만 틀린 경우에는 head를 바꾸지 않고 표의
base로 정정한다.

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
merge, production deployment, Support 통합 또는 프로그램 완료를 뜻하지 않는다.

### Migration-writer lease

Plan 00은 migration을 쓰지 않는다. Plan 10을 시작하기 직전에 repository-wide migration-writer lease를
획득하고 Plan 60 Draft PR 생성과 최종 topology validation이 끝날 때까지 같은 lease를 유지한다.

- Plan 10은 exact predecessor tree의 combined migration inventory에서 마지막 번호 다음을 선택한다.
- Plan 20~60은 직전 stack head의 combined inventory에서 다음 번호를 선택한다.
- lease가 살아 있는 동안 unrelated schema-writing plan을 시작하지 않는다.
- 번호 예약 manifest, duplicate DDL, checksum repair, 이미 작성한 migration 재번호화로 경쟁을
  보정하지 않는다.

### Final seven-PR topology

Plan 60 검증과 completion commit 뒤 새 release branch나 combined PR을 만들지 않는다. 최종 상태는 위
표의 정확히 일곱 개 open Draft PR이다. `feature/productization-plans`를 head로 하는 PR,
`feature/productization-stack-a-release`, Plan 70+ branch/PR 또는 여러 plan을 합친 추가 PR은 금지한다.
각 PR의 head SHA가 local·remote와 일치하고 base가 표와 일치하며, Plan 60 head에서 전체 required
validation이 통과한 뒤 lease를 해제한다. automation은 어떤 PR도 merge하거나 닫지 않는다.

### Mandatory stop conditions

다음 중 하나라도 발생하면 현재 성공 상태를 과장하거나 다음 plan으로 넘어가지 않고 Goal을 일시
정지한다.

- 다른 migration writer 발견 또는 lease 소유권 불명
- required test, build, OpenAPI parity나 문서 graph 검증 실패
- exact predecessor/head SHA 불일치, base-only 정정으로 해소할 수 없는 branch/PR 충돌
- 중요한 제품·보안·정합성 결정을 추측해야 함
- migration 충돌, 적용 여부 불명 또는 외부 Provider 결과 `UNKNOWN`
- credential, approval 또는 GitHub 권한 부족

## Alternatives Considered

### Plan마다 latest main에 merge한 뒤 다음 plan 실행

ADR-072의 기본 경로이고 branch 수명이 짧다. 그러나 이번 실행이 원하는 exact predecessor 기반의
plan별 Draft review를 제공하지 않는다.

### Plan 00~60을 하나의 branch와 PR로 구현

branch 관리가 단순하지만 plan별 diff와 검증 checkpoint가 사라지고 실패 시 되돌릴 단위가 너무 커진다.

### Plan 60 뒤 combined release PR 생성

main 대상 단일 통합 diff를 만들 수 있지만 일곱 번째가 아닌 여덟 번째 PR이 되고, Support 통합 시점을
Stack A 완료 조건과 다시 결합한다. 사용자가 Support merge를 별도로 수행하기로 했으므로 채택하지 않는다.

### 모든 ready plan을 병렬 stack으로 실행

Plan 10~60의 migration 번호와 schema baseline이 경쟁하고, GitHub PR의 단일 base 제약 때문에 자동
통합 기준을 안전하게 정할 수 없다.

## Consequences

- Plan별 Draft PR로 review 가능한 diff와 검증 checkpoint를 유지한다.
- stack tip에서 dependency와 completion evidence가 연속되므로 하나의 Goal이 다음 plan을 결정적으로
  선택할 수 있다.
- Plan 10 시작부터 Plan 60 최종 검증까지 repository-wide migration writer lane을 독점한다.
- Support와 Stack A의 통합 순서는 GitHub merge 단계에 남으며 Stack A 검증은 provisional baseline에서
  계속된다.
- `origin/main` drift가 자동 진행을 중단시키지 않으므로 각 PR의 exact predecessor/head 검증이 더 중요하다.
- Stack A 완료는 P0 Core 중간 통합점이며 전체 제품화 프로그램 완료가 아니다.

## Verification

- 각 iteration 시작 시 provisional baseline, exact predecessor SHA와 clean worktree를 확인한다.
- Checkpoint 1에서 두 Support commit의 Plan 00 ancestry, `origin/main` ancestry와 필수 파일/migration을
  확인하고 비통합은 `SUPPORT_INTEGRATION_PENDING`으로 기록한다.
- `bash scripts/verify-docs.sh`로 active/completed path, metadata와 dependency graph를 검증한다.
- 각 plan의 required test와 validation 결과를 해당 ExecPlan과 orchestration Progress에 기록한다.
- Draft PR의 base/head와 local/remote commit SHA가 일치하는지 확인한다.
- Plan 60 head에서 전체 build, OpenAPI parity, 구조 테스트, 문서 검증과 정확한 seven-PR topology를
  다시 확인한 뒤 lease를 해제한다.

## Metrics

- plan별 validation 재실행 횟수와 실패 원인
- Support 통합 보류 기간과 ancestry 관측 결과
- migration-writer lease 보유 시간
- plan별 Draft PR review 대기 시간
- plan별 PR diff 크기와 base/head mismatch 수

## Revisit Conditions

- Stack A가 5영업일 이상 지속되거나 unrelated migration을 차단할 때
- exact predecessor 충돌 때문에 두 번 이상 진행이 중단될 때
- 일곱 PR 중 하나의 diff가 review 가능한 범위를 넘을 때
- Plan 60 이전에 독립 배포 가능한 안정적 수직 흐름이 확인될 때

## Related Decisions

- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
- [ADR-092](ADR-092-hybrid-authentication.md)
- [ADR-096](ADR-096-public-order-reference.md)
- [ADR-099](ADR-099-customer-order-read-model.md)
- [ADR-100](ADR-100-store-order-board-read-model.md)
