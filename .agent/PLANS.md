# BeanFlow ExecPlan Standard

ExecPlan은 복잡한 Feature 또는 시스템 변경을 다른 대화나 기억 없이 구현할 수 있게 하는 실행 가능한 설계 문서다.

## 사용 조건

다음 중 하나에 해당하면 ExecPlan을 작성하거나 기존 계획을 갱신한다.

- 둘 이상의 Bounded Context를 변경
- DB 스키마 또는 데이터 마이그레이션 변경
- 외부 Provider 연동
- 멱등성, 동시성, 재시도 또는 reconciliation 도입
- 공개 API 또는 이벤트 계약 변경
- 중요한 성능 최적화
- 여러 단계에 걸친 리팩터링
- 구현과 검증이 한 세션을 넘을 가능성이 큼

## 비협상 요구사항

- ExecPlan은 현재 파일만 읽어도 작업을 재개할 수 있을 만큼 self-contained해야 한다.
- 문서는 작업 진행, 발견, 결정에 맞춰 계속 갱신하는 living document다.
- 목적은 코드 파일 생성이 아니라 관찰 가능한 동작의 완성이다.
- 제품 용어와 기술 용어를 처음 등장할 때 정의한다.
- 측정하지 않은 결과를 쓰지 않는다.
- 질문으로 결정된 중요 사항은 Decision Log와 ADR 또는 Business Policy에 함께 반영한다.
- 실패 경로와 운영 복구를 정상 경로와 같은 수준으로 작성한다.

## 필수 구조

```markdown
# <행동 중심 제목>

> **Status:** `ACTIVE` 또는 `COMPLETED`
> **Kind:** `IMPLEMENTATION` 또는 `ORCHESTRATION`
> **Implementation-Ready:** `true` 또는 `false`
> **Writes-Migration:** `true` 또는 `false`
> **Depends-On:** `docs/exec-plans/.../plan.md`, ... 또는 `—`
> **Completed-At:** `YYYY-MM-DD` 또는 `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

## Current State

## Definitions

## Scope

### In Scope
### Non-goals

## Business Rules and Invariants

## Architecture and Transaction Boundaries

## Alternatives Considered

## Failure Semantics

## Data and Migration

## API and Event Contracts

## Milestones

## Required Tests

## Validation Commands

## Observability

## Documentation Updates

## Progress

## Surprises & Discoveries

## Decision Log

## Outcomes & Retrospective

## Revision Notes
```

## Canonical execution metadata

모든 ExecPlan은 제목 바로 아래에 위 여섯 metadata line을 둔다. 이 여섯 줄과 파일의
`active`/`completed` 디렉터리는 실행 재개와 자동 검증에서 함께 사용하는 canonical
execution source다.

- `active/` 파일은 `Status: ACTIVE`, `Completed-At: —`를 사용한다.
- `completed/` 파일은 `Status: COMPLETED`, 실제 완료일의 ISO-8601 date를 사용한다.
- `Kind: IMPLEMENTATION`은 code/schema/API 작업 후보이고 `Kind: ORCHESTRATION`은 dependency,
  release gate와 evidence만 관리한다. orchestration plan은 `Implementation-Ready: false`,
  `Writes-Migration: false`여야 하며 자동 implementation Goal로 선택하지 않는다.
- `Implementation-Ready: true`은 active implementation plan의 direct dependency가 모두
  completed path의 actual Outcomes/validation evidence로 충족됐고, 아직 사용자 결정을 추측하지
  않아도 됨을 뜻한다. 나머지는 false다. completed plan은 true/false가 historical metadata일 뿐
  자동 후보가 아니다.
- `Writes-Migration: true`은 Flyway migration을 만들거나 변경할 수 있음을 뜻한다. 해당 plan은
  ADR-072 migration-writer lane 없이는 시작하지 않는다. common code-only plan은 false다.
- `Depends-On`에는 현재 파일 기준이 아닌 repository-relative ExecPlan path만 적는다.
  **현재 phase를 시작하기 전에 완료되어야 하는 direct input**만 적고, branch base를 맞추기
  위한 ancestor는 반복하지 않는다. current plan이 여러 independent producer/contract outcome을
  직접 소비할 때만 여러 path를 적는다. 직접 선행 계획이 없으면 `—`를 사용한다.
- dependency graph에는 self-reference와 cycle이 없어야 한다. completed plan은 completed
  plan만 직접 dependency로 둘 수 있다.
- `scripts/verify-docs.sh`는 모든 ExecPlan의 metadata, 경로 존재 여부, status-directory
  일치와 dependency cycle을 검증한다. checkbox나 Progress 문단은 canonical metadata를
  대체하지 않는다.

### Unattended execution and completion move

- 자동 실행기는 `ACTIVE + IMPLEMENTATION + Implementation-Ready=true`만 candidate로 선택한다.
  candidate branch는 기본적으로 최신 `main`에서 만들며 active plan head 또는 여러 sibling을 통합한
  base를 추측하지 않는다. 단, Accepted ADR이 exact plan 순서, baseline, predecessor branch,
  completion 의미, migration-writer lease와 final PR topology gate를 모두 고정한 bounded Draft stack은
  verified predecessor head에서 child를 시작할 수 있다.
- `Writes-Migration=true` candidate는 repository-wide migration-writer lease가 있을 때만 시작한다.
  lease holder는 branch 생성 뒤 최신 main의 마지막 Flyway 번호를 읽어 새 번호를 고르고, PR merge가
  끝나기 전 다른 migration writer를 시작하지 않는다. 번호 reservation manifest나 checksum repair로
  병렬 DDL을 보정하지 않는다.
- 미완성 required path를 feature flag/profile로 2xx 성공처럼 노출하지 않는다. Plan 40처럼 후속
  recovery가 필요한 path는 ADR-072의 Draft stack/release PR 규칙을 따른다.
- Plan 40→50 Draft stack은 하나의 migration-writer lease를 공유한다. Plan 40의 verified
  completion commit은 parent Draft branch에서 자신의 `active → completed` 이동과 Plan 50의
  dependency path/ready 갱신을 함께 기록하고, Plan 50은 그 head에서만 시작한다. final child PR이
  main에 merge될 때까지 unrelated schema writer를 시작하지 않는다.
- 제품화 Stack A는 [ADR-111](../docs/adr/ADR-111-productization-stack-a-draft-release.md)에 한해
  Plan 00→10→20→30→40→50→60을 직렬 Draft stack으로 실행한다. 각 child는 직전 plan의 verified
  completion head만 parent로 사용한다. Plan 00 Draft PR은 `main`을 base로 하고 Plan 10~60 Draft PR은
  정확히 직전 plan branch를 base로 한다. Support 구현·완료 commit이 Plan 00의 ancestor이고 필수 파일이
  존재하면 Plan 00을 provisional baseline으로 사용하며, 두 commit의 `origin/main` 비조상 관계와 이후
  `origin/main` 이동은 `SUPPORT_INTEGRATION_PENDING` 관측값이지 중단 또는 restack 사유가 아니다.
  Plan 10 직전부터 Plan 60 Draft PR 생성과 최종 topology validation 완료까지 하나의 migration-writer
  lease를 유지한다. exact predecessor/head 불일치, 다른 migration writer, required validation 실패는
  중단한다. base만 잘못된 기존 Draft PR은 head를 바꾸지 않고 정정할 수 있다. Stack A는 정확히 일곱
  Draft PR만 유지하며 combined release branch/PR을 만들지 않는다. stack 내부 `COMPLETED`는 exact
  predecessor 위에서 required validation을 통과했다는 뜻이며 merge 또는 deployment 완료를 뜻하지 않는다.
- plan completion commit은 `(1) active → completed 이동과 status/date 변경`, `(2) 모든 direct
  successor의 dependency path 갱신`, `(3) 이제 모든 direct dependency가 completed인 successor의
  `Implementation-Ready=true` 갱신`, `(4) dependency graph/document validation`을 함께 수행한다.
  path 갱신을 다음 commit으로 미루지 않는다.

## 작성 원칙

- 경로와 이름은 가능한 한 구체적으로 적는다.
- “적절히 구현한다”, “필요한 테스트를 한다” 같은 모호한 표현을 피한다.
- 구현 도중 계획과 현실이 달라지면 계획을 먼저 갱신한다.
- 사용자에게 다음 단계를 다시 물으며 멈추지 않는다. 단, 제품 동작·아키텍처·보안·정합성에 중요한 미결정 사항은 `AGENTS.md`의 질문 절차를 따른다.
- 실험이나 proof of concept가 필요하면 제거 조건과 최종 구현 반영 여부를 명시한다.
