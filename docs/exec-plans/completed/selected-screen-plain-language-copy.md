# 선택 화면의 문구를 쉬운 표현으로 개편한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/selected-screen-storybook-coverage.md`
> **Completed-At:** `2026-09-05`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객, 점주, 운영팀과 고객센터의 선택 화면에서 구현 용어와 어려운 업무 표현을 걷어 내고,
각 역할이 바로 이해할 수 있는 제목, 상태와 버튼 문구를 제공한다. 화면 구조, 데이터 흐름,
권한과 API 계약은 바꾸지 않으며 모든 변경 상태를 canonical Storybook에서 확인할 수 있어야 한다.

## Current State

- 선택 화면과 34개 Storybook story는 구현되어 있지만 `writer`, `Provider`, `immutable`,
  `workspace`, `Correlation ID` 같은 구현 또는 내부 용어가 일부 노출된다.
- 공통 `DomainStatusText`는 주문 중심 상태만 번역하고 점주·운영·상담 상태 일부를 원문으로 표시한다.
- 지급 파일, 수동 검토, 승인과 긴급 정보 열람의 안전 경계는 현재 문구에도 포함되어 있다.
- 현재 작업 트리의 다른 변경과 사용자 소유
  `docs/exec-plans/active/customer-merchant-screen-contract-completion.md`는 이 작업에서 수정하지 않는다.

## Definitions

- **쉬운 문구:** 사용자가 다음 행동과 현재 상태를 한 번에 이해할 수 있는 짧은 표현이다.
- **업무 용어:** 정산, 감사 기록, 추적 ID처럼 역할 수행에 꼭 필요한 용어다.
- **표시용 매핑:** 서버 코드와 DTO는 보존하고 화면에만 한글 이름을 보여 주는 변환이다.

## Scope

### In Scope

- 선택 고객·점주·운영·고객센터 화면의 제목, 설명, 상태와 버튼 문구
- `DomainStatusText`의 알려진 점주·운영·상담 상태 매핑
- 화면별 action, role, field, queue type의 표시용 매핑
- 연결 내비게이션과 Storybook assertion 및 문서 설명
- 대표 story 미리보기와 Storybook, 접근성, 문구, 타입, 디자인 경계, 빌드 검증

### Non-goals

- route, Props, DTO, callback, 서버 상태값, 공개 디자인 시스템 API 변경
- 새 컴포넌트, CSS, backend endpoint, migration 또는 Aggregate 상태 전이
- Business Policy와 ADR 변경
- 알 수 없는 새 코드를 임의의 한글 상태로 대체

## Business Rules and Invariants

- 알 수 없는 상태 코드는 원문을 유지하여 장애나 계약 변경을 숨기지 않는다.
- 환불, 쿠폰 복원, 승인, 재시도와 지급 완료를 실제 결과보다 강하게 표현하지 않는다.
- 지급 파일 생성은 실제 지급 완료가 아니라는 안내를 유지한다.
- 긴급 정보 열람은 승인, 단일 필드, 만료 시간과 사후 검토 의미를 유지한다.
- dependency failure와 계약 부재는 빈 목록이나 성공 문구로 바꾸지 않는다.

## Architecture and Transaction Boundaries

frontend presentation과 Storybook fixture/assertion만 변경한다. 새 transaction은 없으며 기존 typed
client, `SubmissionIntent`, 권한과 멱등성 계약을 그대로 사용한다. 공통 상태 이름은
`DomainStatusText`에서, 업무별 값은 각 화면의 표시 함수에서 변환한다.

## Alternatives Considered

- 모든 코드를 공통 전역 사전으로 변환: 재사용성은 높지만 서로 다른 Context에서 같은 코드의 의미를
  잘못 합칠 수 있어 기각한다.
- 화면마다 모든 상태를 직접 번역: Context는 분명하지만 공통 상태가 중복되어 기각한다.
- 공통 상태만 확장하고 업무 값은 화면에서 변환: 공개 API를 바꾸지 않고 의미 경계를 지켜 채택한다.

## Failure Semantics

- unknown code fallback은 원문 표시를 유지한다.
- `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`, `INCOMPLETE`는 성공으로 표현하지 않는다.
- mutation 실패 문구는 짧게 쓰되 성공으로 오인하거나 자동 재시도를 암시하지 않는다.
- 추적 ID와 감사 기록은 표시 이름만 바꾸고 실제 값과 감사 요구를 보존한다.

## Data and Migration

DB와 migration 변경 없음.

## API and Event Contracts

route, Props, DTO, callback, OpenAPI와 서버 enum을 변경하지 않는다. 표시용 문구만 바꾼다.

## Milestones

1. 기존 34개 화면 story와 공통 상태 story를 새 문구 assertion으로 먼저 갱신한다.
2. 고객과 점주 화면 문구 및 화면별 값을 변경한다.
3. 운영과 고객센터 화면 문구 및 화면별 값을 변경한다.
4. 내비게이션과 관련 단위 테스트 문구를 맞춘다.
5. Storybook 미리보기와 전체 검증 결과를 기록하고 계획을 완료한다.

## Required Tests

- 고객 환불·쿠폰 성공, 실패, 빈 상태와 계약 준비 상태 문구
- 점주 메뉴·재고·영업시간·혜택·매출·이의제기 문구와 상태 변환
- 운영 실패 업무·정산·감사·추적·캠페인·지급 파일 문구와 안전 안내
- 고객센터 탭, 승인·실행·재시도·정보 변경·긴급 열람 문구와 값 변환
- `DomainStatusText` 점주·운영·상담 상태 변환 및 알 수 없는 코드 보존
- 선택 story에서 불필요한 구현 용어와 영문 상태 코드가 보이지 않는지 확인

## Validation Commands

```bash
cd frontend && npm run check:product-copy
cd frontend && npm test
cd frontend && npm run typecheck
cd frontend && npm run check:design
cd frontend && npm run build-storybook
cd frontend && npm run build
cd frontend && npm run test:storybook:docs
```

Storybook MCP에서 변경 때마다 대상 `run-story-tests(a11y=true)`를 실행하고, 마지막에 전체 story와
대표 고객·점주·운영·고객센터 story 미리보기를 확인한다.

## Observability

새 telemetry 없음. 계약 준비와 실패 상태는 기존처럼 화면에서 명시적으로 보인다.

## Documentation Updates

- 이 ExecPlan의 Progress, Surprises & Discoveries, Decision Log와 Outcomes를 실제 결과로 갱신한다.
- Business Policy와 ADR은 변경하지 않는다.

## Progress

- [x] 2026-09-05: 정책, 실패 semantics, 디자인 시스템 governance와 Storybook 문서를 확인했다.
- [x] 2026-09-05: 대상 story assertion을 새 문구로 갱신하고 RED 상태를 확인했다.
- [x] 2026-09-05: 선택 화면과 공통 상태 매핑을 구현했다.
- [x] 2026-09-05: 대표 story 미리보기와 전체 검증을 완료했다.

## Surprises & Discoveries

- 공통 상태와 업무별 코드가 함께 노출되어 두 수준의 표시용 매핑이 필요하다.
- `Correlation ID`는 업무상 최초 설명에만 병기하고 이후에는 `추적 ID`로 표시해야 의미와 가독성을 함께 지킬 수 있었다.
- Storybook 변경 탐지는 누적 작업 트리 때문에 넓게 보고했지만 reverse dependency 조회와 전체 story 실행으로 실제 소비 경로를 확인했다.

## Decision Log

| 일자 | 결정 | 근거 |
| --- | --- | --- |
| 2026-09-05 | 기존 디자인 시스템 primitive는 재사용하고 새 컴포넌트와 CSS를 추가하지 않는다 | `frontend/AGENTS.md`, Storybook MCP 문서 |
| 2026-09-05 | 안정된 상태만 공통 매핑하고 업무별 값은 화면 내부에서 변환한다 | Context별 의미 충돌과 unknown fallback 보존 |

## Outcomes & Retrospective

- 고객·점주·운영·고객센터의 선택 화면 제목, 설명, 상태와 버튼을 역할별 쉬운 문구로 개편했다.
- `DomainStatusText`에 점주·운영·상담 상태를 추가하고 화면별 action, role, field, queue type은 각 Context에서 변환했다.
- 지급 파일이 실제 지급 완료가 아니라는 안내, 긴급 정보 열람의 승인·만료·사후 검토와 unknown code 원문 fallback을 보존했다.
- 선택 화면 36개와 공통 상태 7개, 총 43개 story가 focused interaction/a11y 검사를 통과했고 전체 231개 story도 Storybook MCP 검사에 통과했다.
- frontend unit 176개, presentation/copy tests, typecheck, design boundary, product copy, app/Storybook build와 61개 Docs smoke가 통과했다.
- route, Props, DTO, callback, OpenAPI, 공개 디자인 시스템 API, CSS, Business Policy와 ADR은 변경하지 않았다.

## Revision Notes

- 2026-09-05: 최초 작성.
- 2026-09-05: 문구 개편과 전체 검증 완료 후 completed로 이동.
