# BeanFlow design-system inventory

> Status: canonical · Revised: 2026-08-27

신규 고객·점주 핵심 여덟 화면에서 검증한 cool white, navy, coral 언어가 전체 frontend의 유일한
디자인 기준이다. Espresso/caramel/crema token, `components/Ui`, `components/Shells`, generated snapshot,
filled success badge와 refresh 전용 primitive/frame은 삭제했으며 compatibility layer로 복원하지 않는다.

## 1. Canonical ownership

| Layer | Owner | Responsibility |
|---|---|---|
| Foundations | `src/design-system/tokens/*.css` | color, semantic color, typography, spacing, radius, elevation, motion, focus |
| Components | `src/design-system/components/**` | 한 가지 상호작용·표현 책임을 가진 typed primitive |
| Patterns | `src/design-system/patterns/**` | component를 조합한 반복 상태와 page structure |
| Shared presentation | `src/presentation/AppShells.tsx` | actor/session/API 상태를 읽어 canonical component와 layout을 조합하는 runtime shell |
| Product pages | `src/features/**`, `src/pages/**`, `src/presentation/beanflow-refresh/**` | OpenAPI와 domain state를 canonical public API로 연결 |

Product code는 `src/design-system/index.ts`를 통해서만 canonical component와 pattern을 import한다.
Feature가 token을 재정의하거나 병렬 button, badge, input, status, shell system을 만들지 않는다.

## 2. Tokens

| Family | Examples | Usage |
|---|---|---|
| Brand | `--coral-*`, `--ink-*`, `--slate-*` | coral primary action, navy hierarchy, cool neutral surfaces |
| Semantic | `--surface-*`, `--text-*`, `--border-*`, `--action-*`, `--state-*` | component와 page의 의미 기반 선택 |
| Type | `--fs-*`, `--lh-*`, `--fw-*`, `--ls-*` | Korean-first hierarchy; decorative uppercase micro label 금지 |
| Space/size | `--sp-*`, `--control-h-*`, `--tap-min`, viewport/layout tokens | mobile app와 dense workspace rhythm |
| Shape/depth | `--radius-*`, `--shadow-*` | restrained corner and cool navy elevation |
| Motion/focus | `--motion-*`, base focus ring | reduced-motion-safe interaction and keyboard visibility |

Raw color, font family, shadow, static inline style와 반복 pixel 증가는 `npm run check:design`이 막는다.

## 3. Components

| Family | API | Responsibility | Required states |
|---|---|---|---|
| Brand | `BrandLockup` | 제공된 cup asset과 wordmark의 일관된 link/static 표현 | static, home link |
| Action | `Button`, `ButtonLink` | brand, secondary, ghost, danger action | loading, disabled, long Korean |
| Commerce | `StatusText` | filled badge 없이 transaction state를 text-first로 표현 | ready, failed, unknown, manual review, unknown code |
| Feedback | `FeedbackState` | loading, empty, recoverable dependency failure | loading, empty, error |
| Form | `SearchField` | visible search affordance와 accessible clear action | empty, value, clear |
| Form | `QuantityStepper` | bounded decrement/increment | default, min/max, keyboard action |

모든 component는 typed props, JSDoc, Autodocs, `a11y.test = "error"`를 가진다. `className` 또는
`style` public escape hatch를 제공하지 않는다.

## 4. Patterns and runtime composition

| Pattern | Owner | Notes |
|---|---|---|
| `PageHeading` | design system | source에 없던 eyebrow 없이 title, description, action만 구성 |
| `LoadingState`, `EmptyState`, `ErrorState`, `SuccessMark` | design system | `ApiRequestError` 의미와 correlation reference 보존 |
| `CustomerShell` | shared presentation | 모든 `/app` route가 동일한 brand/header/tab chrome 사용 |
| `ConsoleShell` | shared presentation | `/store`, `/ops`, `/support`가 동일한 dense workspace chrome 사용 |
| `NotificationAction` | shared presentation | loading/read/unread/failure를 숨기지 않는 고객 header action |
| `RootRedirect` | shared presentation | actor workspace 선택 entry |

Shell은 session membership, notification API, logout failure 같은 runtime 책임 때문에 design-system
primitive가 아니다. 대신 내부 시각 요소와 token은 canonical system만 사용하고 독립 Storybook states로 검증한다.

## 5. Consumer coverage

- Customer: 신규 home/search/store/cart/checkout/order-detail과 기존 orders, payment, points, coupons,
  favorites, notifications, account/auth가 같은 customer shell과 canonical components를 사용한다.
- Store: 신규 order board/refund와 기존 settlements, disputes, region/auth가 같은 console shell을 사용한다.
- Operations: dashboard, order lookup, merchant accounts, policy와 OIDC gate가 같은 system을 사용한다.
- Support: masked search, Case, verification, data grant와 terminal state가 같은 system을 사용한다.
- Storybook: foundations, 모든 canonical component, shared shells, route loading/success/empty/error/permission/
  unknown/reconciling/manual-review state를 문서화한다.

## 6. Removed system and prohibited reintroduction

다음 source/API/token은 repository에 존재하면 실패다.

- `src/components/Ui.tsx`, `src/components/Shells.tsx`
- `src/design-system/_ds_bundle.js`, `src/design-system/_ds_manifest.json`
- `StatusBadge`, `RefreshPrimitives`, `RefreshFrames`
- espresso/caramel/crema token과 `.bf-btn`
- filled green success pill, decorative English eyebrow label
- product route의 Storybook fixture 또는 implicit fake/fallback data

`presentation-boundary.mjs`가 retired path/import를, `check-design-adherence.mjs`가 style/token/taxonomy drift를
검사한다. 삭제 기록은 governance와 DD-009에 남기되 삭제된 code를 migration input으로 보존하지 않는다.

## 7. Validation contract

| Gate | Coverage |
|---|---|
| `npm run check:design` | token reference, raw style, public escape, taxonomy, story/docs and CSS ownership, pixel ratchet |
| `npm run check:presentation-boundary` | refresh runtime boundary와 retired source/import absence |
| `npm run typecheck` | TypeScript와 generated runtime OpenAPI schema |
| `npm run test:unit` | shared utility와 product behavior |
| live Storybook MCP | documentation, interaction, and a11y for every indexed story |
| `npm run build-storybook` + `npm run test:storybook:docs` | static compilation and isolated state-marker smoke |
| `npm run build` + `npm run test:sites` | product bundle와 actor route surface |

실행하지 않은 검증은 `Not run`, sandbox/infra 차단은 `Blocked`로 구분한다. Static Storybook은 live MCP를
대체하지 않는다.
