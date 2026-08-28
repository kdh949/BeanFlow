# PR 124 디자인 시스템 리뷰 보정

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/frontend-page-heading-copy-removal.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

PR #124에서 축소한 `PageHeading` 계약이 product CSS나 refresh 전용 alias로 다시 분기되지 않게 한다. 부분 환불 화면의 주문 식별자는 제목 주변의 작은 보조 문구가 아니라 명시적인 주문 데이터로 표시한다.

## Current State

`PageHeading`은 design-system pattern이지만 `src/presentation/beanflow-refresh/refresh.css`가 `.bf-page-heading`과 하위 `h1`을 여러 문맥에서 덮어쓴다. `RefreshPageHeading`은 별도 동작 없이 canonical pattern을 전달한다. 환불 대상 주문의 `orderReference`는 `context-label`로 `h2` 바로 위에 놓인다. 현재 boundary guard는 native control과 일부 전역 selector만 검사하므로 PageHeading override를 탐지하지 못한다.

## Definitions

- **Canonical pattern**: `src/design-system/`이 시각·접근성 계약을 소유하는 재사용 UI.
- **Reach-in**: product CSS가 canonical class selector와 그 자손을 직접 선택해 내부 스타일을 바꾸는 행위.

## Scope

### In Scope

- refresh CSS의 모든 `.bf-page-heading` reach-in 제거
- refresh 화면에서 `PageHeading` 직접 사용
- 부분 환불 주문 번호를 주문 정보 `<dl>`로 이동
- design-system 밖의 PageHeading selector override를 차단하는 boundary guard와 회귀 테스트
- 관련 Storybook preview·interaction·a11y와 frontend 품질 게이트

### Non-goals

- `PageHeading` variant 또는 새 token 추가
- 다른 `context-label` 사용처의 전면 재설계
- API, 주문/환불 상태, 가격 계산, 멱등성 변경

## Business Rules and Invariants

- 주문 번호, 주문 상태, 시각, 픽업 시간, 결제 방식과 결제 금액은 계속 표시한다.
- 환불 요청·재계산·stale·unknown·reconciling·manual-review 동작을 바꾸지 않는다.
- design-system은 product 상태나 API 오류를 소유하지 않는다.

## Architecture and Transaction Boundaries

변경은 presentation과 정적 boundary 검사에만 한정된다. backend transaction, Aggregate, API 경계는 변하지 않는다. Product pages는 `src/design-system/index.ts`의 `PageHeading`을 직접 사용한다.

## Alternatives Considered

- customer/merchant별 `size` variant 추가: 현재 별도 크기 요구가 계약화되지 않아 제외한다.
- `RefreshPageHeading`에 `PageHeadingProps`만 재사용: 타입 drift는 줄지만 중복 entrypoint가 남아 제외한다.
- 주문 번호를 header 우측에 표시: 상태와 경쟁하므로 의미가 분명한 `<dl>` 항목을 선택한다.

## Failure Semantics

Runtime failure semantics 변화는 없다. Storybook MCP 또는 browser 검증이 실패하면 static build 성공으로 대체하지 않고 별도 `Blocked` 또는 `Failed`로 기록한다.

## Data and Migration

데이터와 migration 변경 없음.

## API and Event Contracts

OpenAPI와 event contract 변경 없음.

## Milestones

1. PageHeading reach-in을 재현하는 boundary 테스트를 먼저 실패시킨다.
2. canonical selector guard를 추가하고 테스트를 통과시킨다.
3. CSS reach-in과 alias를 제거하고 주문 번호를 `<dl>`로 이동한다.
4. focused Storybook 검증과 전체 frontend gate를 통과한다.
5. 커밋과 push 뒤 review thread마다 검증 근거를 답글로 남기고 해결한다.

## Required Tests

- product CSS의 `.bf-page-heading` 및 자손 override 거부
- design-system 내부 PageHeading CSS 허용
- 부분 환불 Story에서 `주문 번호`와 실제 reference 표시
- canonical PageHeading, 장바구니, 주문 보드, 부분 환불 Story interaction/a11y

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

운영 관측성 변경 없음. Boundary violation은 CI에서 file·selector와 함께 실패한다.

## Documentation Updates

이 ExecPlan에 실제 검증 결과와 review thread 처리 결과를 기록한다. 정책·ADR·inventory 변경은 필요 없다.

## Progress

- [x] 리뷰 사실성·범위·우선순위 평가
- [x] BeanFlow Storybook MCP 복구 및 canonical 문서 조회
- [x] 실패하는 boundary 회귀 테스트
- [x] 구현
- [x] 검증
- [ ] push와 review resolution

## Surprises & Discoveries

- 로컬 6006 포트는 다른 worktree의 Deskseed Storybook이 사용 중이어서 해당 프로세스를 보존하고 BeanFlow를 6007에서 실행했다.
- 샌드박스 안의 Playwright Chromium은 macOS Mach port 권한 오류로 시작하지 못했다. BeanFlow Storybook을 허용된 샌드박스 밖 프로세스로 재기동한 뒤 focused와 full Story 테스트가 통과했다.

## Decision Log

- 2026-08-29: 세 리뷰를 모두 반영하되 새 variant 없이 canonical PageHeading을 그대로 사용한다.
- 2026-08-29: 주문 번호는 `<dl>`의 명시적인 `주문 번호` 항목으로 표시한다.

## Outcomes & Retrospective

- Product CSS의 canonical `PageHeading` reach-in 8개를 제거하고, 같은 형태의 재도입을 `canonical-pattern-css` boundary violation으로 차단했다.
- 의미 없는 `RefreshPageHeading` alias를 제거하고 장바구니, 주문 보드, 부분 환불 화면이 canonical component를 직접 사용한다.
- 주문 번호를 부분 환불 대상 주문의 명시적인 `<dl>` 항목으로 이동했다.
- `npm run typecheck`, `npm test`, `npm run check:design`, `npm run build-storybook`, `npm run test:storybook:docs`, `npm run build`, `npm run test:sites`, docs 검증과 `git diff --check`가 통과했다.
- Storybook MCP focused 4개 Story와 전체 Story의 interaction/a11y 검사가 통과했다.

## Revision Notes

- 2026-08-29: PR #124 review remediation 계획 최초 작성.
