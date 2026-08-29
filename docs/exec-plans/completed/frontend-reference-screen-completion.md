# 고객·스토어·운영 참고 화면 전환 완료

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/frontend-refresh-design-system-migration.md`, `docs/exec-plans/completed/frontend-workspace-shell-foundation-support-chrome.md`
> **Completed-At:** `2026-08-29`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

첨부된 고객 9개, 스토어 5개, 운영 4개 참고 화면을 BeanFlow의 canonical navy/coral presentation으로
구현한다. 참고 이미지의 화면 캡처를 재사용하지 않고, 토큰·컴포넌트·패턴·페이지 순으로 쪼개어
Storybook과 실제 route가 같은 typed React source를 조합하도록 전환한다. 이전 주황색 presentation은
활성 route와 canonical story의 import graph에서 완전히 분리하고, 참조가 사라진 코드와 스타일만 삭제한다.

## Source Catalogue and Route Mapping

| Reference | Runtime route | Canonical Storybook page |
|---|---|---|
| 고객 결제 결과 | `/app/payments/:paymentId/success` | `Pages/Customer/PaymentSuccess` |
| 고객 결제 실패 | `/app/payments/:paymentId/fail` | `Pages/Customer/PaymentFailure` |
| 고객 도움말 | `/app/help` | `Pages/Customer/Help` |
| 고객 로그인 | `/app/login` | `Pages/Customer/SignIn` |
| 고객 주문 내역 | `/app/orders` | `Pages/Customer/Orders` |
| 고객 즐겨찾기 | `/app/favorites` | `Pages/Customer/FavoriteStores` |
| 고객 쿠폰 | `/app/coupons` | `Pages/Customer/CouponWallet` |
| 고객 회원가입 | `/app/signup` | `Pages/Customer/SignIn` |
| 고객 포인트 | `/app/points` | `Pages/Customer/Points` |
| 점주 로그인 | `/store/login` | `Pages/Store/SignIn` |
| 스토어 영업 지역 | `/store/region` | `Pages/Store/Region` |
| 스토어 정산 내역 | `/store/settlements` | `Pages/Store/Settlements` |
| 스토어 이의제기 | `/store/disputes` | `Pages/Store/Disputes` |
| 최초 비밀번호 변경 | `/store/password` | `Pages/Store/SignIn` |
| 운영 대시보드 | `/ops` | `Pages/Operations/Dashboard` |
| 운영 보상 조회 | `/ops/orders` | `Pages/Operations/CompensationLookup` |
| 점주 계정 관리 | `/ops/merchant-accounts` | `Pages/Operations/MerchantAccounts` |
| 운영 정책 관리 | `/ops/policies` | `Pages/Operations/PolicyManagement` |

스토어 참고 이미지의 sidebar/topbar는 콘텐츠 참고 대상에서 제외한다. `/store`는
`MerchantWorkspaceShell`, `/support`는 `SupportWorkspaceShell`만 chrome을 소유한다. 운영 화면은 기존
operations route와 auth semantics를 유지하되 동일한 `WorkspaceFrame` geometry를 사용하도록 확장한다.

## Current State

앞선 전환에서 customer shell, 8개 핵심 거래 화면, merchant workspace shell과 support workspace shell을
canonical presentation으로 옮겼다. 이번 18개 route는 canonical control을 사용하지만 대부분 feature 파일이
data loading, failure mapping과 오래된 page markup을 함께 소유한다. Storybook도 같은 feature component를 열기
때문에 route/story의 동작 출처는 같지만 presentation root와 legacy import boundary가 분명하지 않다.

## Reuse Classification

| Need | Decision | Owner / action |
|---|---|---|
| color, type, spacing, radius, elevation | `REUSE` | existing semantic tokens |
| button, field, selection, tabs, feedback | `REUSE` | canonical design-system components |
| customer header/bottom navigation | `REUSE` | `CustomerShell` |
| store sidebar/topbar | `REUSE` | `MerchantWorkspaceShell` only |
| support sidebar/topbar | `REUSE` | `SupportWorkspaceShell` only |
| workspace geometry | `REUSE` | `WorkspaceFrame` |
| page title, status, summaries, filter bars | `COMPOSE` | shared presentation patterns from canonical controls |
| customer result/auth/list layouts | `NEW` | smallest reference-screen presentation views |
| operations chrome | `EXTEND` | operations-owned shell composed on `WorkspaceFrame`; no store/support chrome reuse |
| page controller and remote state | `COMPOSE` | feature controller passes presentation-safe props to new views |

## Contract Gap Matrix

| Reference request | Contract evidence | Runtime decision |
|---|---|---|
| 도움말의 주문/문의 코드 통합 검색과 채팅·메일·전화 metadata | OpenAPI 없음 | 검색/연락 CTA를 만들지 않고 안전한 결제·환불 안내만 제공 |
| 포인트 QR 사용 | OpenAPI 없음 | QR 또는 사용 성공 흐름을 만들지 않음 |
| 운영 대시보드 KPI와 최근 처리 집계 | aggregate API 없음 | route shortcut과 명시적 업무 안내만 제공; 가짜 수치 금지 |
| 점주 계정 목록·필터 | exact lookup/create/reset/unlock만 존재 | exact lookup workspace 유지; 가짜 목록 금지 |
| 보상 목록과 공개 주문번호 검색 | exact Order UUID compensation query만 존재 | exact lookup 유지; 가짜 목록 금지 |
| 정산 전체 KPI | paginated batch list에 global totals 없음 | 현재 page를 전체 합계로 표시하지 않음 |
| 즐겨찾기 거리·영업시간 | `CustomerStore`가 보장하는 필드만 사용 | 없는 값 추측 금지 |
| 점주 self-service 비밀번호 재설정 | OpenAPI 없음 | reset 링크/성공 동작을 만들지 않음 |

이 gap은 frontend placeholder로 메우지 않는다. 제품 기능으로 필요하면 별도 Business Policy/OpenAPI/Backend
계약 변경이 선행되어야 한다.

## Business Rules and Invariants

- 결제 success URL은 `APPROVED`를 만들지 않는다. `PENDING`, `UNKNOWN`, `MANUAL_REVIEW`, `DECLINED`를
  각각 명시하고 중복 결제를 유도하지 않는다.
- 고객 등록 성공 후 자동 로그인 실패를 회원가입 실패로 되돌리지 않는다.
- 포인트 계정 integrity failure를 0P로 표시하지 않는다.
- 쿠폰은 선택된 store와 server applicability를 기준으로 하며, 검색어를 command 값으로 재사용하지 않는다.
- settlement/dispute는 ACTIVE OWNER scope를 유지한다.
- 운영 계정 조회는 감사 사유와 exact identifier를 유지하고 일회용 비밀번호를 route memory 밖에 저장하지 않는다.
- dependency failure를 empty, zero, stale 또는 success로 바꾸지 않는다.

## Architecture and Transaction Boundaries

새 presentation view는 기존 canonical root인 `src/presentation/beanflow-refresh/` 아래에서 props와 callback만 받는다. 기존 feature
controller가 OpenAPI client, request generation, CSRF, session, mutation intent와 failure mapping을 계속 소유한다.
route와 Storybook은 동일한 controller/view 조합을 사용한다. Backend transaction, Aggregate, DB와 event 경계는
변경하지 않는다.

## Affected Modules and Likely Files

- `frontend/src/presentation/beanflow-refresh/**`
- 18개 target feature/page controller와 story
- `frontend/src/presentation/AppShells.tsx`의 operations chrome
- `frontend/src/design-system/**`의 필요한 shared pattern과 token documentation
- `frontend/src/router.tsx`, `frontend/scripts/presentation-boundary.mjs`
- `frontend/docs/design-system-inventory.md`, `frontend/docs/design-decisions.md`, `frontend/design-qa.md`

## Alternatives Considered

- 기존 page CSS만 덮어쓰기: markup/API ownership과 legacy presentation이 계속 섞여 기각한다.
- reference별 완전 독립 page 복제: Storybook과 route가 갈라지고 control/shell이 중복되어 기각한다.
- 참고 이미지의 미지원 기능까지 fixture로 구현: runtime 계약을 오해하게 하므로 기각한다.
- presentation-safe view + 기존 controller 조합: 동작 불변식을 유지하면서 legacy visual import를 끊을 수 있어 선택한다.

예상 부작용은 feature controller의 JSX가 얇아지고 CSS ownership이 presentation root로 이동하는 것이다. 테스트의
접근 가능한 이름과 상태 의미는 유지하되 레이아웃 selector는 새 owner로 갱신한다.

## Failure Semantics

기존 `ErrorState`, `FeedbackState`, `StatusText`와 request error presentation을 재사용한다. failure는 재시도 가능성,
문의 코드와 transaction ambiguity를 보존한다. Storybook fixture는 story 환경에서만 사용하고 production import
graph에는 들어가지 않는다.

## Data, API, Events and Migration

DB, migration, OpenAPI, API client와 event 계약 변경 없음. 이번 단계에서 계약이 없는 기능은 명시적으로 non-goal이다.

## Milestones

1. source image, route, OpenAPI와 live Storybook catalog를 고정하고 gap/classification을 기록한다.
2. 고객 모바일 9개 화면을 state-first story와 같은 presentation source로 전환한다.
3. 스토어 5개 화면을 canonical merchant chrome의 content slot에 전환한다.
4. 운영 4개 화면과 operations-owned workspace chrome을 전환한다.
5. active route/story legacy presentation import를 0으로 만들고 무참조 legacy만 삭제한다.
6. 동일 viewport reference/prototype 비교, responsive/interaction/a11y/full gate를 완료한다.

## Required Tests

- target story의 loading, success, empty, recoverable failure와 transaction ambiguous states
- login/signup validation, auth privacy와 registered-then-login-failed
- order tab/date filters, coupon selection, favorite mutation, pagination
- merchant password/region/settlement/dispute owner semantics
- operations exact lookup, audit reason, command conflict와 partial policy job
- store/support/operations chrome owner boundary와 legacy import count 0
- keyboard focus, accessible name, status announcement, 320px customer와 desktop workspace responsive behavior

## Validation Commands

`frontend/`에서 다음을 실행한다.

- `npm run typecheck`
- `npm test`
- `npm run check:design`
- `npm run build-storybook`
- `npm run test:storybook:docs`
- `npm run build`
- `npm run test:sites`
- Storybook MCP changed/affected `preview-stories`, focused/full `run-story-tests(a11y=true)`
- repository root `git diff --check`

Visual QA는 각 참고 이미지와 동일 viewport의 구현 캡처를 하나의 comparison image로 합쳐 차이를 검토한다.
Chromatic/Playwright baseline이 없으면 visual regression은 `Not configured`로 보고한다.

## Documentation and ADR Plan

기존 accepted palette와 chrome ownership을 바꾸지 않으므로 새 ADR은 만들지 않는다. inventory, design decision,
foundation overview, design QA와 presentation boundary만 갱신한다. 미지원 기능이 실제 제품 scope로 승격되면 별도
Business Policy/OpenAPI/ADR 결정을 선행한다.

## Progress

- [x] 18개 source/route/story mapping과 contract gap 작성
- [x] live Storybook inventory, candidate documentation과 story instructions 확인
- [x] 고객 모바일 9개 화면 전환
- [x] 스토어 5개 화면 전환
- [x] 운영 4개 화면 전환
- [x] active route/story legacy import 0과 automated presentation boundary
- [x] visual/full validation과 완료 기록

## Surprises & Discoveries

- 참고 화면은 시각적으로 완성되어 있지만 검색, QR, aggregate KPI와 list 계약 일부는 runtime contract에 없다.
- merchant/support chrome lock은 이미 automated boundary에 포함되어 있어 content 전환 중에도 우선순위를 강제할 수 있다.
- 운영 화면도 별도 chrome owner가 필요했다. `OperationsWorkspaceShell`을 `WorkspaceFrame` 위에 구성하고 merchant/support navigation import를 금지했다.
- 초기 전체 Storybook 실행에서 매장 선택 전의 임시 empty state를 보는 settlement story race가 드러났다. store 해석 전에는 neutral loading을 유지하도록 수정한 뒤 focused와 전체 접근성 실행이 모두 통과했다.

## Decision Log

- 2026-08-29: 첨부 이미지는 visual hierarchy와 content arrangement의 source이며 runtime capability source가 아니다.
- 2026-08-29: unsupported reference capability를 mock runtime이나 synthetic success로 만들지 않는다.
- 2026-08-29: controller는 계약과 failure semantics를, 새 presentation root는 editable visual composition을 소유한다.

## Outcomes & Retrospective

18개 target route와 canonical story가 동일한 typed controller/view source를 사용한다. 고객 화면은
`CustomerReferencePage`, 스토어와 운영 화면은 `WorkspaceReferencePage`로 반복 구조를 모았고, Store/Support/Operations
chrome은 각각 전용 shell이 소유하면서 `WorkspaceFrame` foundation만 공유한다. presentation boundary는 active
route/story의 legacy visual import와 다른 영역 chrome import를 차단한다.
현재 boundary 출력은 18개 화면의 active route/story와 refresh source를 직접 검사하고 legacy presentation import 0을 보고한다.

계약이 없는 검색, QR, aggregate KPI와 list 기능은 구현하지 않았다. 이 때문에 일부 참고 이미지와 content density는
다르지만 fake runtime, synthetic success와 placeholder route는 없다. 단위·경계·copy·typecheck·design governance·build·
Storybook docs/sites와 live accessibility run이 통과했으며, 18개 동일 viewport comparison을 `frontend/design-qa.md`에
기록했다. 자동 pixel baseline은 구성되어 있지 않다.

## Revision Notes

- 2026-08-29: 최초 작성.
- 2026-08-29: 18개 화면 전환, operations chrome ownership, presentation boundary와 visual/full validation 완료.
