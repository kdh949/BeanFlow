# 공통 워크스페이스 프레임과 고객지원 chrome 구축

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/frontend-merchant-workspace-shell.md`
> **Completed-At:** `2026-08-29`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

스토어와 고객지원 화면이 동일한 workspace geometry를 재사용하되 서로의 sidebar/topbar를 덮어쓰지
않도록 공통 frame을 design-system pattern으로 분리한다. 고객지원 `/support` route에는 사용자가 지정한
sidebar를 유일한 chrome으로 적용하고, 이후 고객지원 화면은 content만 제공한다.

## Current State

`MerchantWorkspaceShell`은 `/store`의 sidebar/topbar를 단독 소유하지만 grid, sidebar width, topbar height,
content padding까지 자신의 CSS에서 직접 소유한다. `/support`는 operations와 오래된 `.bfr-store-*` shell을
공유해 지정된 navigation 구조와 독립적인 chrome 소유권이 없다.

## Definitions

- **Workspace frame**: sidebar, topbar와 content slot의 geometry만 소유하는 application-neutral pattern.
- **Surface chrome owner**: route·actor·menu 의미를 소유하는 store 또는 support presentation component.
- **Unavailable destination**: 참고 이미지에는 있지만 현재 router에 element가 없는 메뉴.

## Scope

### In Scope

- typed `WorkspaceFrame` pattern과 standard/wide/compact width foundation token
- 기존 `MerchantWorkspaceShell`의 공통 frame 소비
- `SupportSidebar`, `SupportTopbar`, `SupportWorkspaceShell`과 canonical Storybook states
- 실제 `/support` route의 고객지원 shell 전환
- store/support chrome 단일 소유권 boundary guard
- foundation, inventory, governance와 design decision 문서 갱신

### Non-goals

- 이미지에 보이는 미구현 고객지원 메뉴의 route/API 구현
- 고객지원 알림 수·매장 선택·사용자 프로필 API 신설
- operations console 재설계
- backend, OpenAPI, DB, 인증 또는 지원 정책 변경

## Business Rules and Invariants

- `/store`는 `MerchantWorkspaceShell`, `/support`는 `SupportWorkspaceShell`만 chrome을 소유한다.
- route가 없는 고객지원 destination은 링크나 성공 동작을 만들지 않는다.
- operations OIDC 상태와 logout 실패를 예시 사용자나 성공 상태로 대체하지 않는다.
- 고객지원 page의 PII, Case, verification과 compensation 계약은 변경하지 않는다.

## Architecture and Transaction Boundaries

`WorkspaceFrame`은 ReactNode slot과 layout variant만 받는 design-system pattern이다. Store/support의 route,
session, actor와 menu mapping은 각각 `src/presentation/*-workspace/`가 소유한다. `AppShells.tsx`가 실제 auth
상태를 presentation-safe props로 변환한다. 서버 transaction과 Aggregate 경계는 변경하지 않는다.

## Alternatives Considered

- 하나의 data-driven 범용 sidebar에 store/support 메뉴를 주입: surface별 소유권과 reference 우선순위가
  흐려지고 잘못된 chrome 재사용이 쉬워 기각한다.
- support가 `MerchantWorkspaceShell`을 재사용: store 전용 메뉴와 actor semantics를 support에 결합하므로 기각한다.
- 기존 `.bfr-store-*` support branch만 시각 수정: 공통 geometry가 foundation으로 승격되지 않고 legacy shell
  공유를 유지하므로 기각한다.

## Failure Semantics

OIDC loading/unauthenticated/unavailable 상태는 그대로 설명하고 예시 상담원으로 fallback하지 않는다.
로그아웃 실패는 alert로 유지한다. Storybook MCP 또는 browser 검증 실패는 static build 성공으로 대체하지 않는다.

## Data and Migration

DB와 migration 변경 없음.

## API and Event Contracts

기존 operations auth session과 `/support` API만 재사용한다. OpenAPI/event 변경 없음.

## Milestones

1. live Storybook inventory와 관련 shell/page documentation을 확인한다.
2. `WorkspaceFrame`과 foundation token/story를 추가하고 merchant shell을 조합한다.
3. 고객지원 전용 chrome story와 interaction을 구현한다.
4. 실제 `/support` route와 boundary guard를 전환한다.
5. focused/full Storybook, browser visual QA와 frontend gate를 완료한다.

## Required Tests

- workspace width variant와 content slot rendering
- 고객지원 대기열 active state와 미지원 destination의 disabled semantics
- 고객지원 actor menu/logout failure interaction
- merchant collapse/topbar 회귀
- `AppShells`가 store/support의 canonical shell을 각각 조합하는 boundary test
- changed/affected Storybook interaction과 accessibility

## Validation Commands

`frontend/`에서 다음을 실행한다.

- `npm run typecheck`
- `npm test`
- `npm run check:design`
- `npm run build-storybook`
- `npm run test:storybook:docs`
- `npm run build`
- `npm run test:sites`
- Storybook MCP `get-changed-stories`, `preview-stories`, focused/full `run-story-tests(a11y=true)`
- repository root `git diff --check`

## Observability

운영 metric/log 변경 없음. Chrome ownership 위반은 design guard가 file과 reason을 출력한다.

## Documentation Updates

Foundation Overview, design-system inventory/governance와 frontend design decision에 공통 frame과 surface별
chrome owner를 기록한다.

## Progress

- [x] visual source, route와 Storybook contract 확인
- [x] 공통 workspace frame과 token 구현
- [x] 고객지원 chrome story 구현
- [x] 실제 route와 boundary 연결
- [x] visual/full validation과 완료 기록

## Surprises & Discoveries

- `/support`는 operations와 같은 `.bfr-store-*` shell을 소비하고 있어 sidebar 의미와 CSS ownership이 섞여 있다.
- operations auth state에는 표시 이름과 팀 metadata가 없으므로 runtime은 예시 `김사랑님`을 추측할 수 없다.
- Storybook preview의 전역 `body` 최소 너비 320px가 260px sidebar reference에 scroll bar를 만들었다. reference story가 존재할 때만 해당 preview constraint를 제거해 production layout에는 영향을 주지 않았다.

## Decision Log

- 2026-08-29: geometry는 application-neutral `WorkspaceFrame`, 메뉴·actor 의미는 surface별 presentation owner가 소유한다.
- 2026-08-29: 첨부 이미지는 support sidebar의 유일한 visual source이며 기존 store chrome은 변경하지 않는다.

## Outcomes & Retrospective

`WorkspaceFrame`이 standard, wide, compact sidebar와 공통 topbar geometry를 소유하고 store/support chrome은
각자의 presentation root에서 menu, actor와 route 의미를 독립적으로 소유한다. `/store`는 기존
`MerchantWorkspaceShell`, `/support`는 `SupportWorkspaceShell`을 실제 `ConsoleShell`에서 조합한다. route가 없는
고객지원 destination은 disabled semantics만 제공한다.

Storybook MCP 전체 interaction/a11y 검증은 live index의 206개 story를 모두 통과했다. `npm run typecheck`, 24개 unit file의
176개 test, 13개 presentation boundary test, 11개 product-copy test, design adherence, Storybook production build,
58개 docs entry smoke, application build, 4개 Sites worker test와 repository docs/OpenAPI 검증이 모두 통과했다.
시각 검증은 sidebar `260 × 991`와 full workspace `1600 × 1000`에서 완료했고 console warning/error는 없었다.

공통 frame을 의미 없는 범용 sidebar로 확장하지 않고 geometry에만 한정한 점이 핵심이다. 이 경계를 boundary
script와 문서에 함께 고정해 이후 화면이 다른 surface chrome을 우발적으로 import할 가능성을 줄였다.

## Revision Notes

- 2026-08-29: 최초 작성.
- 2026-08-29: 공통 frame, support chrome, 실제 route, browser/Storybook/full gate 검증을 완료하고 completed로 이동.
