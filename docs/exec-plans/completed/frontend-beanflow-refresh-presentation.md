# 고객·점주 핵심 화면의 격리형 presentation 재구현

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/customer-merchant-screen-contract-completion.md`
> **Completed-At:** `2026-08-27`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

첨부된 고객 모바일 시안 여섯 장과 점주 데스크톱 시안 두 장을 최종 시각 의도로 사용해 고객 홈,
매장 검색, 매장 메뉴, 장바구니, 결제, 주문 상세와 점주 주문 보드, 부분 환불을 새 presentation
namespace에서 재구현한다. 화면은 runtime OpenAPI와 기존 거래 상태 의미를 그대로 소비하며 기존
page-level JSX, CSS, className과 시각 계층을 새 구현에 가져오지 않는다.

완료 후 실제 route와 canonical Storybook은 `frontend/src/presentation/beanflow-refresh/**`의 같은
page source를 사용한다. 새 presentation의 legacy page import와 router의 legacy 대상 page import는
자동 검사로 차단한다. route 사용처가 사라진 대상 legacy JSX/CSS/story는 삭제하되 API client, 인증,
cart/quote/idempotency, polling과 formatter 같은 비시각 로직은 보존한다.

## Current State

- `origin/main`의 runtime OpenAPI에는 optional Store/Menu image, customer display, next pickup window,
  menu metadata, non-reserving order quote, immutable order pricing/lifecycle, board lifecycle/allowed action,
  merchant refund preview/orderContext와 notification summary/inbox가 있다.
- 기존 여덟 page는 `frontend/src/features/**`와 `frontend/src/pages/console/**`에서 하나의 전역
  `frontend/src/styles.css` 시각 언어를 공유한다.
- live Storybook MCP는 Foundations, Button, FeedbackState, StatusBadge와 대상 page state를 문서화한다.
  기존 page 문서는 상태 책임 확인에만 사용하고 시각 기준으로 사용하지 않는다.
- 현재 checkout은 detached `origin/main` `3e0eeab`이며 작업 시작 전 tracked/untracked 변경이 없다.

## Definitions

- **refresh presentation:** `frontend/src/presentation/beanflow-refresh/**`에 위치한 새 route page와
  route-specific composition이다.
- **legacy presentation:** 대상 화면을 현재 렌더링하는 기존 page JSX/story와 그 전역 page selector다.
- **neutral reusable logic:** API/generated type, auth/session, `useResource`, cart, quote/idempotency,
  board polling/model, formatting처럼 화면 배치와 시각 언어를 소유하지 않는 코드다.
- **canonical story:** 실제 route와 같은 refresh page source를 deterministic MSW data로 렌더링하는 story다.

## Scope

### In Scope

- `/app`, `/app/stores`, `/app/stores/:storeId`, `/app/cart`, `/app/checkout/:orderId`,
  `/app/orders/:orderReference`, `/store`, `/store/refunds/:storeId/:orderReference`
- refresh customer/store shells와 페이지별 presentation
- 필요한 최소 디자인 시스템 extension/new primitive와 Storybook documentation
- 요구된 정상·실패·권한·disabled·긴 한국어·반응형·거래 상태 stories
- route 전환, legacy target 제거, import-boundary 검사와 전체 검증
- source/implementation same-viewport 시각 비교와 `frontend/design-qa.md`

### Non-goals

- OpenAPI, backend, database, migration, Aggregate, transaction 또는 Provider 동작 변경
- 저장 카드, PG/세무 영수증, 실제 픽업 QR, 고객 이름/전화, VAT, 주문 채널, 검색 전체 건수,
  엄격한 facet, 보드 총 상품 수량
- fixture나 hardcoded success를 production route에 추가
- 대상 밖 auth, points, coupon wallet, favorite, notification inbox, settlement, dispute, operations,
  support page의 전면 redesign

## Business Rules and Invariants

- `orderingAvailable`과 `operatingStatus`를 독립 표시한다.
- `nextPickupWindow`와 PickupSlot만 사용하고 준비 시간을 추정하지 않는다.
- quote는 `guarantee=NONE`인 read-only 계산이며 final order가 fingerprint와 모든 owner state를 재검증한다.
- stale quote 확인 뒤 새 fingerprint와 새 `Idempotency-Key`를 사용한다.
- Checkout은 Toss Standard Payment Window만 안내하며 저장 카드 UI를 만들지 않는다.
- Customer order detail은 immutable pricing과 실제 lifecycle timestamp만 표시하고 거래 요약이라고 부른다.
- 픽업 번호는 text로 표시하며 스캔 가능한 QR 동작을 만들지 않는다.
- Board는 server `itemSummary`, lane, allowedActions, 3초 polling, ETag, overflow와 status conflict를 보존한다.
- Refund는 server-calculated preview, previewVersion, mandatory reason, Idempotency-Key, membership과
  UNKNOWN/RECONCILING/MANUAL_REVIEW를 보존하고 PII/VAT/provider/card/order channel을 표시하지 않는다.
- dependency failure를 empty, false, 0, placeholder success 또는 stale data로 바꾸지 않는다.

## Architecture and Transaction Boundaries

- 이 계획은 frontend read/interaction composition만 바꾸고 backend transaction은 바꾸지 않는다.
- refresh page는 API client와 generated schema에 직접 의존하거나 presentation-neutral hook/model을 소비한다.
- Storybook은 같은 page source를 route parameter, router decorator, deterministic MSW와 fixed clock으로 연다.
- 공통 semantic primitive는 design-system에 typed public API와 story/docs를 갖춘다. route-specific
  composition은 refresh namespace가 소유한다.
- 실제 route는 fixture asset과 story helper를 import하지 않는다.

## Alternatives Considered

- **기존 page JSX에 새 CSS만 적용:** legacy hierarchy와 className이 계속 새 디자인의 구조를 결정하므로 기각.
- **기존 전역 CSS를 전면 교체:** 대상 밖 화면의 의미와 review scope를 바꾸므로 기각.
- **시안 이미지를 배경으로 사용:** 접근성, 실제 interaction, 반응형과 계약 검증이 불가능하므로 기각.
- **새 presentation이 기존 page component를 감싸기:** legacy presentation import가 남아 격리 조건을 깨므로 기각.

## Failure Semantics

- API error는 성공/empty로 바꾸지 않고 `FeedbackState` 또는 상태별 명시 UI로 표시한다.
- notification summary failure는 no-unread dot과 구분한다.
- location permission denial은 검색 실패와 구분하고 query 검색 경로를 유지한다.
- quote stale, checkout expiry/preparation failure, board conflict/pending, refund stale/unresolved/unknown을
  각각 별도 상태로 표시한다.
- 이미지 absence만 제품 placeholder/영역 생략을 허용하고 expired URL/provider 장애를 새 fallback data로 바꾸지 않는다.

## Data and Migration

새 schema와 migration은 없다. runtime OpenAPI와 generated `frontend/src/api/schema.d.ts`를 그대로 사용한다.

## API and Event Contracts

- Customer discovery: `/me/orders`, `/me/store-recommendations`, `/stores/search`, `/stores/nearby`,
  `/stores/{storeId}`, `/stores/{storeId}/menus`, `/stores/{storeId}/pickup-slots`
- Ordering/payment: `/me/order-quotes`, `/orders`, `/orders/{orderId}`, payment attempt/config,
  `/me/orders/{orderReference}`
- Merchant: store membership, board/overflow/transitions, refund preview/execute
- Notification: `/me/notification-summary`
- 모든 request/response field는 generated `components["schemas"]` type으로 고정한다.

## Milestones

### Milestone 1 — Boundary and design-system foundation

- failing import-boundary check를 먼저 추가한다.
- semantic brand/cool-surface tokens와 Button brand variant를 story-first로 확장한다.
- BrandLockup, IconButton, SearchField, QuantityStepper의 canonical stories와 tests를 추가한다.

### Milestone 2 — Customer frame and discovery

- refresh Customer frame, Home, Search를 canonical stories에서 구현한다.
- success/loading/empty/error/permission/disabled/long/mobile과 notification summary failure를 검증한다.
- focused Storybook interaction/a11y를 통과시킨다.

### Milestone 3 — Menu, cart and checkout

- Store detail/menu, Cart, Checkout을 구현한다.
- option/quantity, slot, quote loading/stale/coupon/pickup unavailable, lease expiry와 payment preparation failure를 검증한다.

### Milestone 4 — Order detail

- pickup number, lifecycle, 거래 요약과 allowed action을 새 presentation으로 구현한다.
- paid/accepted/preparing/ready/completed/cancelled/permission/pending recovery 상태를 검증한다.

### Milestone 5 — Merchant board and refund

- refresh Store workspace, board lane/overflow/transition pending/conflict와 refund selection/recalculation/stale/
  unavailable/UNKNOWN/RECONCILING/MANUAL_REVIEW를 구현한다.

### Milestone 6 — Route cutover and legacy removal

- router가 refresh roots만 import하도록 전환한다.
- 사용처가 사라진 대상 legacy JSX/CSS/story를 제거한다.
- boundary check와 `rg`로 legacy import 0건을 증명한다.

### Milestone 7 — Visual QA and integrated validation

- source와 browser capture를 같은 viewport/state로 결합 비교해 P0/P1/P2를 반복 수정한다.
- 모든 필수 command와 full Storybook interaction/a11y를 실행하고 exact outcome을 기록한다.

## Required Tests

- import-boundary script의 forbidden/allowed fixture
- page interaction unit tests와 current API request shape assertions
- canonical Storybook stories의 interaction/a11y
- mobile 320/390/430, tablet 768, desktop 1280/1600 visual/keyboard checks
- quote stale key rotation, board conflict refresh, refund previewVersion/stale/unknown UI regression

## Validation Commands

```bash
cd frontend
npm run typecheck
npm run test:unit
npm run check:design
npm run build
npm run test:sites
npm run build-storybook
npm run test:storybook:docs
npm run check:presentation-boundary

# live Storybook MCP
get-changed-stories
preview-stories
run-story-tests(a11y=true)

cd ..
git diff --check
```

## Observability

새 production metric과 log는 추가하지 않는다. 기존 API error/correlation과 domain state만 안전한 UI copy로 투영한다.

## Documentation Updates

- 이 ExecPlan의 Progress, Surprises, Decision Log와 Outcomes를 구현 중 계속 갱신한다.
- 디자인 시스템 새 token/component는 Foundations/Autodocs에 사용 책임을 기록한다.
- 최종 `frontend/design-qa.md`에 source, capture, viewport, 상태, 비교 이력과 final result를 기록한다.

## Progress

- 2026-08-27: 목표 파일, 8개 source image, repository/frontend rules, business policy, related ADR,
  completed contract ExecPlan, runtime generated schema와 live Storybook documentation을 확인했다.
- 2026-08-27: backend/API/DB 추가 없이 frontend implementation-ready로 판정했다. 첨부 시안과 계약의
  저장 카드, 결과 건수/facet, QR, receipt, PII/VAT/channel 차이는 목표 파일의 대체 표현으로 닫혔다.
- 2026-08-27: 구현 전 10개 항목 보고를 완료했다. source edit, route cutover와 validation은 아직 시작 전이다.
- 2026-08-27: refresh design tokens, Button brand variant, BrandLockup/IconButton/SearchField/
  QuantityStepper와 canonical stories를 추가하고 live Storybook 문서·focused interaction/a11y를 통과했다.
- 2026-08-27: customer/store refresh shell과 8개 page를 새 presentation namespace에 구현했다. quote,
  idempotency, board polling/ETag/overflow/conflict, refund previewVersion/unresolved state는 기존 runtime 계약을 보존했다.
- 2026-08-27: 8개 실제 route를 refresh root로 전환하고 사용처가 사라진 legacy target JSX/CSS/story를 제거했다.
  presentation boundary unit/script와 design check 연계를 추가했다.
- 2026-08-27: source/prototype same-ratio combined comparison을 3회 수행했다. 누락된 story frame/CSS,
  store hero heading, semantic contrast를 수정했고 `frontend/design-qa.md`를 Passed로 기록했다.
- 2026-08-27: unit 173 tests, live Storybook full interaction/a11y, production/Sites/Storybook build,
  Storybook Docs 40 entries·15 stateful docs·50 surfaces와 presentation boundary를 통과했다.

## Surprises & Discoveries

- completed contract ExecPlan이 이미 8개 화면의 data gap을 닫았고 현재 main에 통합돼 있었다.
- Storybook에는 primitive가 Button, FeedbackState, StatusBadge 중심으로만 문서화돼 있어 refresh의 brand,
  icon action, search input, quantity interaction은 정식 extension/new responsibility가 필요하다.
- 기존 Customer/Console shell도 legacy visual language를 소유하므로 page만 바꾸지 않고 대상 route의
  refresh shell을 함께 격리해야 한다.
- page story를 refresh shell 없이 직접 렌더링하면 production route는 CSS를 얻어도 canonical screenshot은
  unstyled page가 된다. Storybook router decorator에 refresh customer/store surface를 정식으로 추가했다.
- legacy target story 삭제 후 `check-storybook-docs.mjs`가 이전 Docs ID와 marker를 계속 기대했다. 새 canonical
  Docs ID와 실제 explicit failure marker로 smoke contract를 함께 전환해야 했다.
- page-only a11y에서는 보이지 않던 muted/success/brand/danger text contrast가 full shell에서 검출됐다.
  semantic token을 AA 범위로 어둡게 조정해 시각 방향과 accessibility를 함께 유지했다.

## Decision Log

- 2026-08-27: source images를 정보 구조와 시각 계층 기준으로 사용하되 contract 밖 text/data는 목표 파일의
  명시적 대체 표현으로 교체한다.
- 2026-08-27: 새 namespace는 `frontend/src/presentation/beanflow-refresh`로 고정한다.
- 2026-08-27: detached main에서 commit/push/PR 없이 working-tree diff로만 구현한다.
- 2026-08-27: canonical page stories도 실제 route처럼 refresh shell을 거치게 하고 store story는 MSW-backed
  merchant session/membership을 해석하게 한다.
- 2026-08-27: legacy target를 import 금지 목록에만 남기지 않고 route 사용처가 없는 JSX/CSS/story는 삭제한다.
  non-target 주문 목록만 `CustomerOrdersPage.tsx`로 분리해 보존한다.
- 2026-08-27: checkout expiry는 disabled button만 두지 않고 명시적 만료 안내를 제공한다.

## Outcomes & Retrospective

8개 화면은 `frontend/src/presentation/beanflow-refresh/**`의 새 root와 customer/store frame으로 재구현됐다.
실제 route와 canonical stories가 같은 page source를 사용하고 automated boundary가 legacy target import와
legacy target CSS/file 재등장을 차단한다. Backend, OpenAPI, DB와 migration은 변경하지 않았다.

시각 구현은 source의 cool-white/navy/coral 언어와 mobile/desktop hierarchy를 유지하되 runtime contract에
없는 data를 제거했다. 결제 만료, notification/location failure, board overflow/conflict와 refund unresolved
states는 성공이나 empty로 위장하지 않는다. same-viewport comparison과 live Storybook full a11y 결과는
`frontend/design-qa.md`에 Passed로 기록했다.

검증 과정에서 canonical story frame 누락과 legacy Docs smoke ID가 발견돼 implementation과 검증 계약을 함께
수정했다. 최종 작업은 commit, push, PR 없이 working tree에만 남겼다.

## Revision Notes

- 2026-08-27: 초기 implementation-ready frontend redesign plan 작성.
- 2026-08-27: 구현, route cutover, legacy removal, combined visual QA와 최종 검증 결과로 completion update.
