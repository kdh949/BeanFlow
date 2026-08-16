# BeanFlow Frontend Design-System Inventory

> Snapshot: 2026-08-15
> Scope: `frontend/` editable source, current router, Storybook index, generated pre-baseline snapshot, CI gates
> Canonicality: 이 문서는 inventory와 migration 상태를 기록한다. API source of truth는 typed TSX와
> Storybook docs이며 `_ds_bundle.js`와 `_ds_manifest.json`은 근거 자료일 뿐이다.

## 1. Canonical source

| Concern | Canonical source | Derived or non-canonical input |
|---|---|---|
| Token | `src/design-system/tokens/*.css` | `_ds_manifest.json` token metadata |
| Component | `src/design-system/components/**/*.tsx` | `_ds_bundle.js`, 존재하지 않는 manifest JSX 경로 |
| Component contract | typed props, JSDoc, canonical CSF stories | generated HTML cards |
| Page state | 실제 route component와 같은 source를 쓰는 `Pages/*` stories | screenshot, 수동 재현 |
| Validation | MCP story test, browser Docs smoke, executable repository scripts | 실행되지 않는 lint config |

Product와 Storybook은 `src/design-system/index.ts`를 통해 같은 `Button`, `ButtonLink`, `StatusBadge`,
`FeedbackState` source를 사용한다. `components/Ui.tsx`의 loading/empty/error wrapper도 이 source를
합성하며 별도 markup 구현을 갖지 않는다.

## 2. Token inventory

현재 token 파일에는 206개 선언과 201개 고유 이름이 있다. `motion.css`의 5개 중복 선언은
`prefers-reduced-motion`에서 같은 duration token을 `0ms`로 재정의하는 의도적 override다.

| File | Definitions | Unique names | Role |
|---|---:|---:|---|
| `colors.css` | 48 | 48 | palette와 brand color |
| `typography.css` | 33 | 33 | family, size, line-height, weight, tracking, numerals |
| `spacing.css` | 26 | 26 | spacing, gutters, layout and control dimensions |
| `radius.css` | 14 | 14 | radius와 border width |
| `elevation.css` | 10 | 10 | shadow와 focus ring |
| `motion.css` | 15 | 10 | duration, easing, press scale, reduced-motion override |
| `semantic.css` | 60 | 60 | surface, text, border, action, status, domain aliases |
| `fonts.css`, `base.css` | 0 | 0 | font loading과 document base rules |

Foundation 문서는 `Foundations/Overview`에서 중요한 이름·값·사용 규칙을 source text로 명시해 MCP가
runtime loop 없이 읽을 수 있게 한다.

## 3. Editable component inventory

| Layer | Component | Product consumers | Contract evidence |
|---|---|---|---|
| Core | `Button`, `ButtonLink` | Shell, customer, store, operations, router | typed props, JSDoc, 5 stories |
| Commerce | `StatusBadge` | customer orders/payment, store board, operations | server-state mapping, 5 stories |
| Feedback | `FeedbackState` | `LoadingState`, `EmptyState`, `ErrorState`, store board | semantic live region, 3 stories |

Live design-system CSS에는 위 세 TSX owner의 `bf-btn`, `bf-status`, `bf-feedback` family만 남긴다.
typed owner가 없는 selector family를 추가하면 `check:design`의 `orphan-component-style` 규칙이 실패한다.

## 4. Repeated product patterns

| Pattern | Current owner | Decision |
|---|---|---|
| page action | canonical `Button`/`ButtonLink` | `REUSE` |
| loading, empty, recoverable error | canonical `FeedbackState`를 `components/Ui.tsx`가 합성 | `COMPOSE` |
| transaction status | canonical `StatusBadge` | `REUSE` |
| card surface | product `.surface-card` composition | generated `Card` API는 `DEPRECATE`; 반복 API가 확정될 때 typed source로 `MIGRATE` |
| customer/store/operations chrome | `CustomerShell`, `ConsoleShell` | generated navigation API를 현재 shell에 `MERGE` |
| labeled form controls | route-local native elements | Plan 80/90에서 실제 validation contract가 확정될 때 `MIGRATE` |
| page heading | `PageTitle` | generated `SectionHeader`를 여기에 `MERGE` |

## 5. Generated manifest disposition

`_ds_manifest.json`의 32개 component 경로에는 실제 JSX source가 없다. 아래 분류는 수량을 맞추기 위한
복원이 아니라 현재 product 소비와 후속 plan의 책임을 기준으로 한다.

| Classification | Manifest entries | Result |
|---|---|---|
| `KEEP` | Button | typed `Button`/`ButtonLink`로 복원하고 product가 사용 |
| `MERGE` | Badge, OrderStatus, EmptyState, SectionHeader, TopBar, SideNav, TabBar | 각각 `StatusBadge`, `FeedbackState`, `PageTitle`, 실제 Shell로 단일화 |
| `MIGRATE` | StoreCard, MenuItem, PickupSlots, DataTable, OrderTicket, Card, IconButton, Input, Select, Checkbox, QuantityStepper, SearchField, Tabs, ListRow | 현재 route-local pattern을 유지하고 Plan 80/90 등에서 둘 이상의 실제 consumer와 API가 확인될 때 typed source로 승격 |
| `DEPRECATE` | BalanceCard, CouponCard, StatTile, Icon, Alert, Dialog, ProgressBar, Toast, Radio, Switch | 현재 product owner와 검증된 API가 없어 manifest 이름을 public contract로 사용하지 않음 |
| `DELETE` | bundle-only selector blocks, obsolete `Ui.stories.tsx`, disconnected `_adherence.oxlintrc.json` | live CSS와 Storybook/CI 입력에서 제거. snapshot bundle/manifest 자체는 provenance를 위해 보존 |

## 6. Route and state coverage

Router의 14개 실제 path/index entry와 두 layout component는 17개 story 파일에서 직접 열 수 있다.

| Route | Component | Direct story states |
|---|---|---|
| `/` | `RootRedirect` | role choice, customer/store/operations chrome |
| `/app` | `CustomerHomePage` | success, location-required, empty, recoverable error, loading |
| `/app/stores/:storeId` | `StoreCatalogPage` | menu/slot success, empty, recoverable error, loading |
| `/app/checkout/:orderId` | `CheckoutPage` | pending payment, recoverable error, loading |
| `/app/payments/:paymentId/success` | `PaymentSuccessPage` | approved, unknown/reconciling, dependency error |
| `/app/payments/:paymentId/fail` | `PaymentFailPage` | retryable failure, manual review |
| `/app/orders` | `CustomerOrdersPage` | active, past, empty, recoverable error, loading |
| `/app/orders/:orderReference` | `CustomerOrderDetailPage` | ready, recovery pending, cancelled, permission failure |
| `/app/help` | `CustomerHelpPage` | safe support guidance |
| `/store` | `StoreOrderBoardPage` | active, empty, permission failure |
| `/ops` | `OpsDashboardPage` | current dashboard |
| `/ops/refunds` | `OpsRefundPage` | full/partial form, success, unknown, recoverable error, loading |
| `/ops/orders` | `OpsOrderPage` | idle, success, loading, recoverable error, manual review |
| `*` | `NotFoundPage` | unknown route |

The customer and console layout components are additionally covered by the `RoleChoice` Docs entry. Async page
stories use deterministic MSW fixtures. Eight Autodocs files with multiple MSW states render each story in its own
iframe so one story's handler cannot overwrite another. Operations submit-result stories use static result presenters
in Docs and keep their real form submissions in `!autodocs` interaction stories, avoiding concurrent input races.

## 7. Storybook and MCP inventory

| Surface | Current result |
|---|---:|
| CSF story files | 17 |
| Stories | 62 |
| Static Docs entries | 19 |
| Multi-state MSW Docs | 8 |
| Browser-asserted stateful Docs | 10 |
| Browser-asserted Docs state surfaces | 40 |
| Router element components checked by guard | 16 |
| MCP component/page entries | 17 |
| MCP foundation docs | `Foundations/Overview` |

`Explorations/Workflow`은 static Storybook에서 template로 볼 수 있지만 `tags: ['!manifest']`로 MCP
component inventory에서 제외한다. 선택된 exploration만 `Patterns`나 `Pages`로 승격하고 나머지는 삭제한다.

## 8. Drift and remaining debt

- 삭제한 legacy CSS 뒤 live component style family는 typed owner가 있는 세 개뿐이다.
- repeated raw pixel baseline은 product layout의 15개 값 조합이다. 새 값이나 count 증가는 실패하고
  감소하면 baseline 갱신을 요구한다.
- `_ds_bundle.js`와 `_ds_manifest.json`은 여전히 historical migration input이다. Product import는 guard가 막는다.
- formerly blocked unsafe request 세 곳은 actor별 CSRF helper로 header를 보낸다. `npm run typecheck`은
  이제 오류 없이 통과하며, 알려진 오류를 허용하는 baseline은 없다.
- 브라우저 `Headers`는 한글 `X-Access-Reason` 값을 전송 전에 거부한다. Operations 통합 plan은 입력
  표현과 wire encoding을 명시적으로 결정해야 하며, story는 현재 전송 가능한 ASCII 사유를 사용한다.
- visual regression은 approved baseline과 credential이 없어 `Not configured`다.

## 9. Executable guardrails

| Gate | Coverage |
|---|---|
| `check:design` | token reference, raw color/font/shadow, inline style escape, generated import, taxonomy, route story, orphan CSS owner, MSW Docs isolation, raw-pixel ratchet |
| `test:unit` | shared utilities와 product component behavior |
| `test:storybook:ci` | every CSF interaction and a11y `error` in Chromium |
| `build-storybook` | manager/preview/static asset compilation |
| `test:storybook:docs` | all 19 Docs render; 10 stateful Docs의 40 state surfaces show their own marker |
| `typecheck` | generated runtime schema와 TypeScript 오류 0건 |
| CI | install, adherence, typecheck, unit, Storybook browser/a11y, static build, Docs smoke |

새 production dependency는 추가하지 않았다. TypeScript AST, Playwright, Storybook, MSW는 기존 lockfile의
dependency를 사용한다.

## 10. Next migration slices

1. Plan 80에서 customer Session/CSRF client와 customer P0 forms를 연결하며 실제 반복 control부터 typed
   component로 승격한다.
2. Plan 90에서 merchant refund/settlement UI와 UUID form을 교체하고 `X-Access-Reason` browser wire
   표현을 결정한다.
3. 중복 layout debt를 정리하고 사람이 critical page baseline을 승인한 뒤 Chromatic 또는 repository-owned
   screenshot assertion 중 하나를 별도 결정으로 활성화한다.
