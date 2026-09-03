# 머천트 공통 워크스페이스 셸 구축

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/frontend-refresh-design-system-migration.md`
> **Completed-At:** `2026-08-29`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

사용자가 지정한 사이드바와 탑바 이미지를 머천트 화면의 고정된 공통 chrome으로 구현한다. 완료 후
Storybook과 실제 `/store` route는 동일한 `MerchantWorkspaceShell`을 사용하고, 이후 머천트 페이지는
콘텐츠만 제공한다. 다른 화면 시안에 포함된 sidebar/topbar는 이 셸을 대체하지 않는다.

## Current State

`src/presentation/AppShells.tsx`의 `ConsoleShell`은 store, operations, support를 하나의 JSX와
`.bfr-store-*` selector로 렌더링한다. Store Story도 이 공용 shell을 그대로 보여주므로 머천트 전용
navigation 구조와 topbar 소유권이 없다. 현재 제공되는 store route는 주문 보드, 부분 환불, 정산,
이의제기, 운영 지역이다.

## Definitions

- **Merchant chrome**: store 콘텐츠 밖의 고정 sidebar와 topbar.
- **Supported destination**: 현재 router에 실제 element가 연결된 store path.
- **Unavailable destination**: 참고 이미지에는 있지만 현재 route/API가 없는 메뉴. 가짜 링크로 만들지 않는다.

## Scope

### In Scope

- `MerchantSidebar`, `MerchantTopbar`, `MerchantWorkspaceShell` presentation component
- 이미지 기준 menu grouping, active state, store identity, actor summary와 collapse interaction
- Storybook canonical component/state/interaction story
- 실제 store `ConsoleShell`의 새 shell 조합
- 다른 store chrome 재도입을 막는 frontend design decision과 boundary test

### Non-goals

- 주문 내역, 매장 정보, 운영 시간, 직원 관리, 고객 문의, 매출 리포트 route/API 구현
- merchant notification/help/account menu 기능 추가
- operations/support shell 재설계
- backend, OpenAPI, DB 또는 인증 정책 변경

## Business Rules and Invariants

- 현재 router에 없는 메뉴는 링크나 성공 동작을 만들지 않는다.
- OWNER 전용 정산·이의제기는 현재 membership 판정을 유지한다.
- store/session 조회 실패를 임의의 시청점, A-142 또는 점장 데이터로 대체하지 않는다.
- logout 실패는 인증된 상태를 유지하고 기존 alert를 표시한다.

## Architecture and Transaction Boundaries

순수 visual component는 `src/presentation/merchant-workspace/`에 둔다. `AppShells.tsx`가 session과
membership API 상태를 presentation-safe text/flags로 변환해 shell에 전달한다. Product page는 `Outlet`
content만 제공한다. 서버 transaction, Aggregate와 API 계약은 바뀌지 않는다.

## Alternatives Considered

- 기존 `ConsoleShell`에 store 전용 조건을 계속 추가: operations/support와 visual ownership이 섞여 기각한다.
- design-system에 menu route와 actor 상태를 포함: application dependency를 canonical visual layer에 넣게 되어 기각한다.
- 모든 참고 메뉴에 placeholder route 추가: 확인되지 않은 기능을 구현한 것처럼 보이므로 기각한다.

## Failure Semantics

Membership 조회 loading/failure는 확인 중/확인 불가 문구로 표시하고 예시 store로 fallback하지 않는다.
지원되지 않는 메뉴는 disabled semantic으로 표시한다. Storybook MCP/browser 검증 실패는 static build 성공으로
대체하지 않고 `Blocked` 또는 `Failed`로 기록한다.

## Data and Migration

DB와 migration 변경 없음.

## API and Event Contracts

기존 `GET /merchant/me`와 `GET /merchant/me/stores`만 재사용한다. OpenAPI/event 변경 없음.

## Milestones

1. frontend minor decision과 Storybook 사용 지침을 확인한다.
2. 순수 merchant chrome component와 canonical stories를 구현한다.
3. 실제 store shell container를 새 component로 전환한다.
4. shell ownership boundary 회귀 테스트를 추가한다.
5. focused/full Storybook 및 frontend gate를 실행한다.

## Required Tests

- 이의제기와 주문 보드 active navigation
- 지원되지 않는 메뉴가 링크가 아닌 disabled item인지 검증
- menu collapse/expand interaction과 `aria-expanded`
- store/actor/reference의 긴 한글 content
- store route가 canonical `MerchantWorkspaceShell`을 소비하고 병렬 chrome을 만들지 않는 boundary test

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

운영 metric/log 변경 없음. Boundary violation은 테스트에서 file과 reason으로 실패한다.

## Documentation Updates

`frontend/docs/design-decisions.md`에 머천트 chrome의 독점 source와 다른 시안의 우선순위를 기록한다.

## Progress

- [x] 사용자 visual source와 범위 확정
- [x] Storybook MCP 복구, inventory와 component docs 조회
- [x] frontend decision 기록
- [x] canonical merchant shell 구현
- [x] 실제 store shell 전환
- [x] 검증과 완료 기록

## Surprises & Discoveries

- `ConsoleShell`은 store뿐 아니라 operations/support도 같은 `.bfr-store-*` selector를 사용한다.
- Store identity API에는 시안의 `A-142` 같은 별도 store code가 없으므로 runtime에서 이를 추측할 수 없다.
- Live Storybook과 standalone Storybook browser test를 동시에 실행하면 addon-vitest port가 충돌한다. Live server를 종료한 뒤 standalone gate를 실행하고 handoff 전에 server를 다시 열었다.
- macOS sandbox의 Chromium launch는 `MachPortRendezvous ... Permission denied (1100)`으로 막혔고 동일 명령을 허용된 외부 실행으로 재검증했다.

## Decision Log

- 2026-08-29: 지정된 두 이미지만 머천트 chrome의 visual source로 사용한다.
- 2026-08-29: 현재 route가 없는 메뉴는 동일 정보 구조 안에 disabled item으로 표시하고 가짜 route를 만들지 않는다.
- 2026-08-29: product/session dependency 때문에 shell은 design-system이 아니라 shared presentation이 소유한다.

## Outcomes & Retrospective

`MerchantSidebar`, `MerchantTopbar`, `MerchantWorkspaceShell`을 단일 shared-presentation owner로 추가하고
실제 `ConsoleShell kind="store"`를 전환했다. 현재 route가 없는 메뉴는 disabled 상태이며 OWNER route 판정,
membership 조회 실패, logout 실패 의미는 유지한다. Boundary check가 owner 밖 selector와 store shell 조합 누락을
차단한다.

Storybook 7개 focused state와 전체 199개 story interaction/a11y가 통과했다. Unit 176개, presentation/copy
guard, typecheck, design adherence, Storybook static/docs, product build, Sites worker, docs/OpenAPI 검증도 통과했다.
1600×1000 browser capture를 두 reference crop과 함께 비교한 뒤 navigation density, icon, collapse affordance를
보정했고 최종 design QA를 `passed`로 기록했다.

## Revision Notes

- 2026-08-29: 최초 작성.
- 2026-08-29: 구현, route 조합, boundary, visual QA와 전체 검증 완료.
