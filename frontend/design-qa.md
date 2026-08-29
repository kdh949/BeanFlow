# BeanFlow refresh presentation design QA

## Comparison target

- Source visual truth: `/Users/donghyunkim/.codex/attachments/44db4d0a-3103-4acf-a4fb-963d33265c8b/image-1.png` through `image-8.png`.
- Browser-rendered implementation: canonical `Pages/Refresh/**` Storybook stories served at `http://127.0.0.1:6006` and captured with the user-selected in-app browser.
- State: cart with two items and a current quote; checkout pending payment; search results; orderable store; order ready for pickup; home with active order and recommendations; selectable refund items; active three-lane order board.
- CSS viewport: browser `1231 × 692`; customer app content `390 × 692`; merchant comparison content `1106 × 692`.
- Density normalization: browser capture was `1230 × 692` pixels. Customer sources were `941 × 1672` and normalized to `390 × 692`; merchant sources were `1586 × 992` and normalized to `1106 × 692`. The centered implementation regions were cropped to those same pixel dimensions before comparison.

## Evidence

### Full-view combined comparisons

- Customer six-screen contact sheet: `/private/tmp/beanflow-refresh-qa/final/customer-comparisons.png`
- Merchant two-screen contact sheet: `/private/tmp/beanflow-refresh-qa/final/merchant-comparisons.png`
- Per-screen combined comparisons:
  - `/private/tmp/beanflow-refresh-qa/final/cart-comparison.png`
  - `/private/tmp/beanflow-refresh-qa/final/checkout-comparison.png`
  - `/private/tmp/beanflow-refresh-qa/final/search-comparison.png`
  - `/private/tmp/beanflow-refresh-qa/final/store-detail-comparison.png`
  - `/private/tmp/beanflow-refresh-qa/final/order-detail-comparison.png`
  - `/private/tmp/beanflow-refresh-qa/final/home-comparison.png`
  - `/private/tmp/beanflow-refresh-qa/final/refund-comparison.png`
  - `/private/tmp/beanflow-refresh-qa/final/board-comparison.png`
- Implementation captures: `/private/tmp/beanflow-refresh-qa/final/*-full.jpg` and normalized `/private/tmp/beanflow-refresh-qa/final/*-current.jpg`.

### Focused combined comparisons

- Home search, active order, and store rows: `/private/tmp/beanflow-refresh-qa/final/home-active-order-focus.png`
- Refund table, steppers, and side summary: `/private/tmp/beanflow-refresh-qa/final/refund-workspace-focus.png`
- Board lane headers, card density, and actions: `/private/tmp/beanflow-refresh-qa/final/board-lanes-focus.png`

These focused crops were required because typography, icon alignment, compact controls, and dense merchant tables were not readable enough in the eight-screen contact sheets.

## Findings

No actionable P0, P1, or P2 differences remain in the matched states.

- Fonts and typography: the implementation uses the isolated refresh font stack with the source hierarchy: compact Korean labels, strong pickup/order numbers, and denser merchant text. Headings no longer inherit the oversized legacy page scale; no eyebrow labels remain.
- Spacing and layout rhythm: customer screens preserve the source's compact mobile stack and fixed bottom navigation/action areas. Merchant screens use the source's light sidebar, three board lanes, refund table plus side summary, and bottom action form without clipping persistent controls.
- Colors and tokens: all target screens use the canonical `--coral-*`, navy/ink, slate, and semantic action/status tokens. Bright coral remains the visual accent; accessible dark coral is reserved for small text and filled actions. The old green success pill and dark legacy merchant shell are absent. Semantic unresolved/failure states remain textually explicit instead of being reduced to color.
- Image and asset fidelity: menu imagery uses supplied deterministic catalog assets; the branch-provided BeanFlow logo SVG is used as an asset; interface icons use Lucide. No source screenshot crop, CSS drawing, emoji, handcrafted inline SVG, or fake QR is used.
- Copy and content: visible copy follows the reference intent while honoring the runtime OpenAPI. Saved-card UI, QR scanning, customer PII, VAT, POS/web channel, result totals, and inferred item counts are not invented. Storybook-only values are deterministic fixture data and do not enter product routes.
- Icons and controls: search, navigation, store, pickup, notification, quantity, order lifecycle, and board actions use one icon family and remain semantic HTML controls with labels.
- Responsiveness and accessibility: the canonical customer viewport has no horizontal clipping or hidden persistent actions; merchant grids remain readable at the matched desktop crop. Focused Storybook interaction and accessibility tests pass for all eight canonical states.

Acceptable P3/expected differences:

- Exact store names, prices, times, and status copy differ where the reference contains values not supplied by the current runtime contract. The implementation keeps the same information grouping and density without fabricating those values.
- The reference raster's anti-aliasing and font rendering differ slightly from the live browser capture; wrapping and hierarchy are equivalent at the normalized viewport.

## Comparison history

1. Initial combined comparison — `/private/tmp/beanflow-refresh-qa/current/customer-comparisons.png`, `/private/tmp/beanflow-refresh-qa/current/merchant-comparisons.png`
   - P1: oversized customer headings and cards materially reduced above-the-fold content.
   - P1: customer home retained source-absent English eyebrow copy and a green success pill.
   - P1: merchant pages used a dark legacy sidebar and sparse, oversized content.
   - Fix: removed source-absent labels/pill, introduced compact refresh-local sizing, light merchant shell, dense lanes and tables, and actual library icons.
2. Second combined comparison — `/private/tmp/beanflow-refresh-qa/current2/customer-comparisons.png`, `/private/tmp/beanflow-refresh-qa/current2/merchant-comparisons.png`
   - P1: cart omitted coupon, points, and final price from the matched first viewport.
   - P1: order detail combined summary, pickup number, timeline, menu, and price into fewer oversized regions than the source.
   - P1: refund lines and summary were stacked rather than using the source's desktop workspace split.
   - Fix: reordered cart blocks, corrected the quantity-stepper layout, split order-detail cards, and introduced refund table/summary columns with a sticky action form.
3. Post-fix comparison — `/private/tmp/beanflow-refresh-qa/current3/customer-comparisons.png`, `/private/tmp/beanflow-refresh-qa/current3/merchant-comparisons.png`
   - P2: deterministic search, recommendation, menu, and board data did not fill the same visible density as the source.
   - Fix: expanded Storybook-only contract-valid fixtures and further compacted cart controls without changing product-route fallbacks.
4. Final combined comparison — `/private/tmp/beanflow-refresh-qa/final/customer-comparisons.png`, `/private/tmp/beanflow-refresh-qa/final/merchant-comparisons.png`
   - Post-fix evidence shows the matched information order, above-the-fold density, primary accent roles, fixed controls, real assets, and state hierarchy. No actionable P0/P1/P2 issue remains.
5. Canonical design-system migration follow-up
   - Same-viewport home evidence: `/private/tmp/beanflow-refresh-qa/final/home-design-system-current.png`, `/private/tmp/beanflow-refresh-qa/final/home-design-system-comparison.png`, `/private/tmp/beanflow-refresh-qa/final/home-design-system-focus.png`.
   - Same-viewport search evidence: `/private/tmp/beanflow-refresh-qa/final/search-design-system-current.png`, `/private/tmp/beanflow-refresh-qa/final/search-design-system-comparison.png`, `/private/tmp/beanflow-refresh-qa/final/search-design-system-focus.png`.
   - Cross-surface evidence: `/private/tmp/beanflow-refresh-qa/final/operations-design-system-current.png`, `/private/tmp/beanflow-refresh-qa/final/operations-shell-design-system-current.png`.
   - P2 found and fixed: the first canonical coral was too muted against the visual source. The palette was split into a bright visual accent and a WCAG AA dark coral for readable text/filled actions, then the full Storybook accessibility pass was repeated.
   - Post-fix browser console check on a fresh home-story load contained no warnings or errors. No actionable P0/P1/P2 issue remains.

## Primary interactions checked

- Search helper/query submission and current-location permission handling.
- Menu configuration, quantity change, cart add, pickup selection, quote display, and order CTA enablement.
- Checkout payment CTA failure semantics and reservation states through canonical variants.
- Order cancel/reorder controls and lifecycle states through canonical variants.
- Board transitions, rejection flow, refresh, overflow, and conflict variants.
- Refund quantity repricing, stale preview, unknown/reconciling/manual-review outcomes, and submission enablement.
- Canonical component and shell variants across customer, store, operations, and support routes.
- Live Storybook interaction and accessibility sweep: 40 files and 156 stories passed with no reported accessibility violation.

## Final result

final result: passed

## Merchant workspace chrome follow-up — 2026-08-29

- Source sidebar: `/var/folders/rg/k27jblsn7sn4qsddc_5ckkvw0000gn/T/codex-clipboard-367ff598-ef6a-4f68-b7e8-7415c6331a02.png`
- Source topbar: `/var/folders/rg/k27jblsn7sn4qsddc_5ckkvw0000gn/T/codex-clipboard-12b01c16-9a60-438d-83d6-061552925d80.png`
- Browser-rendered implementation: `patterns-navigation-app-shells--store-chrome`
- Matched viewport: `1600 × 1000`; final browser capture: `/private/tmp/beanflow-merchant-shell-story-final.png`

The source crops and final Storybook capture were reviewed together at original resolution. The implementation preserves the 216px white sidebar, 70px white topbar, grouped Korean navigation, coral active row, bottom collapse control, store selector, notification/help area, and actor control. The first visual pass exposed excess navigation height, mismatched representative icons, and a missing bordered collapse affordance; all three were corrected before the final capture.

Exact store reference, notification state, and actor copy remain contract-driven at runtime. The Storybook reference states use deterministic `A-142`, unread notification, and actor fixtures only; the real `/store` shell does not invent values that the merchant API does not provide. Unsupported destinations are visible but non-interactive and do not create placeholder routes.

Focused Storybook interaction and accessibility validation passed for the reusable shell states and the actual `ConsoleShell kind="store"` composition. No actionable P0, P1, or P2 visual difference remains.

merchant chrome final result: passed

## Customer-support workspace chrome follow-up — 2026-08-29

- Source sidebar: `/var/folders/rg/k27jblsn7sn4qsddc_5ckkvw0000gn/T/codex-clipboard-9e65f840-d696-4518-9af5-1f610a648b01.png`
- Browser-rendered implementation: `patterns-support-support-workspace-shell--sidebar-reference`
- Matched viewport: `260 × 991`

The source image and the live Storybook sidebar were reviewed together at the same `260 × 991` viewport. The implementation preserves the cool gray support surface, 260px wide chrome, BeanFlow brand placement, coral queue selection, grouped support tools, separated settings row, and bottom actor control. The first pass exposed a Storybook-only 320px body minimum that introduced scrollbars at the source width; the reference story now removes that preview constraint without changing production layout.

The support shell shares only `WorkspaceFrame` geometry tokens with the merchant shell. Navigation, active-state meaning, actor copy, unavailable destinations, and topbar context remain support-owned. Unsupported destinations render with disabled semantics and no placeholder route; runtime actor copy remains derived from operations authentication state rather than the reference fixture.

Focused Storybook interaction and accessibility validation passed for the workspace frame, merchant shell, support shell, and actual `ConsoleShell` compositions; the final live-MCP sweep passed all 206 indexed stories. Lucide icons are the closest available library matches to the raster source; no handcrafted SVG or screenshot-derived asset was introduced. No actionable P0, P1, or P2 visual difference remains.

support chrome final result: passed

## Reference-screen composition follow-up — 2026-08-29

### Scope and evidence

- Customer references: payment success/failure, login, signup, help, orders, coupons, favorites, and points.
- Store references: disputes, login, first-password change, settlements, and operating region.
- Operations references: compensation lookup, dashboard, merchant accounts, and policy management.
- Same-viewport per-screen comparisons: `/private/tmp/beanflow-reference-qa-*-comparison.png`.
- Favorite-store replacement follow-up: `/private/tmp/beanflow-reference-qa-customer-favorites-comparison.png`.
- Customer contact sheet: `/private/tmp/beanflow-reference-qa-customer-contact.png`.
- Store/operations contact sheet: `/private/tmp/beanflow-reference-qa-workspace-contact.png`.

Every comparison places the supplied reference and the browser-rendered canonical story side by side at the same viewport. The review therefore covers the actual route/story source rather than a detached mock.

### Findings

- The 18 target routes and canonical stories now compose reusable customer result/auth/list layouts or the shared workspace page pattern. They do not reproduce each screenshot as an isolated page.
- Favorite stores now composes the same `RefreshStoreCard` used by the new customer discovery presentation. The retired `features/discovery/StoreCards` file and its unscoped global selectors were removed, and the active-reference import boundary now covers the 18 page/story entry files directly.
- Store pages always use the user-selected `MerchantWorkspaceShell`; conflicting sidebar or topbar chrome shown in individual screen references is intentionally ignored. Support remains owned by `SupportWorkspaceShell`, while operations uses an operations-owned shell. All three share only `WorkspaceFrame` geometry and foundation tokens.
- The cool-white surface, navy hierarchy, coral active/action accent, card treatment, input treatment, status colors, and workspace spacing are consistent across customer, store, support, and operations surfaces.
- Reference-only capabilities without an OpenAPI/runtime contract were not fabricated. This includes help search/contact metadata, point QR use, aggregate operations KPIs, merchant account lists, public compensation lists, and settlement-wide totals. These screens therefore have intentionally lower content density than their reference images while preserving the supported task hierarchy and explicit failure semantics.
- Logo and interface icons use repository assets and Lucide. No screenshot crop, CSS/inline-SVG drawing, emoji, fake QR, or production mock provider was introduced.
- Customer login, signup, and transaction result stories expose individual runtime states instead of simultaneously rendering the multiple mutually exclusive states shown in composite design references.

The comparison shows no reusable-shell ownership defect or actionable P0/P1 difference. Exact pixel/content parity is not claimed for unsupported reference capabilities; those gaps remain contract work rather than frontend placeholders.

### Validation

- `npm run typecheck`: passed.
- `npm test`: passed — 24 files, 176 tests; presentation boundary 15 tests; product-copy audit 11 tests.
- `npm run check:design`: passed — 193 tokens, 57 story files, 36 route components.
- `npm run build-storybook`, `npm run test:storybook:docs`, `npm run build`, and `npm run test:sites`: passed.
- Live Storybook MCP focused run: 23 representative stories passed with accessibility enabled.
- Live Storybook full run: all indexed stories passed with accessibility enabled after the settlement empty-state handoff was made stable.
- `git diff --check`: passed.
- Pixel baseline automation: not configured; same-viewport combined-image review is the recorded visual evidence.

reference-screen composition final result: passed within current runtime contracts

## Customer-support S130 screen suite — 2026-08-29

### Scope and visual evidence

- Eight runtime-backed screens: queue, intake, case detail, verification/reveal, order action, compensation, profile change, and approvals/audit.
- Reference viewport: `1586 × 992`.
- Combined reference/current evidence: `/private/tmp/beanflow-support-all-comparisons.png`.
- Per-screen evidence: `/private/tmp/beanflow-support-*-comparison-1586x992.png`.
- Responsive browser checks: `1440`, `1280`, and `1024` CSS-pixel widths; the representative queue and order-action routes had `scrollWidth === clientWidth` and exactly one `main` landmark at every width.
- Narrow desktop evidence: `/private/tmp/beanflow-support-queue-1024x900.png` and `/private/tmp/beanflow-support-order-action-1024x900.png`.

The supplied raster references and the live runtime-route stories were reviewed side by side. The implementation keeps the user-selected `SupportWorkspaceShell` as the sole customer-support sidebar and topbar owner, then composes the same cool-white, navy, coral, card, dense-table, filter, metric, stepper, and timeline foundations across all eight screens. No reference crop is rendered as product UI.

### Findings and intentional differences

- The shell, content hierarchy, primary accent roles, master/detail structure, and dense operational patterns match the reference family. The black regions in some references are treated as canvas outside the application surface.
- Runtime screens use only fields and states exposed by the OpenAPI contracts. Reference-only page-number pagination, generic upload/download helpers, unassigned-case workflow, and invented customer or merchant fields are intentionally absent; signed cursors, `MINE|ALL`, evidence digests, and masked owner summaries are used instead.
- Storybook handlers provide deterministic contract-shaped examples only. Runtime routes do not import MSW, story fixtures, or fallback data.
- The order-action story exercises server policy evaluation. Verification and sensitive reveal remain route-local and are cleared by navigation/session/permission lifecycle handling.
- At `1024px`, dense multi-column areas collapse into a readable vertical flow without horizontal clipping; the fixed support navigation remains available and the canonical topbar remains the sole header.

### Validation

- Live Storybook MCP focused run: all eight runtime-route stories passed with accessibility enabled.
- Live Storybook MCP full run: all indexed stories passed with accessibility enabled.
- Frontend unit, presentation-boundary, product-copy, design, application build, Storybook static build, Storybook docs smoke, and Sites tests passed.
- Presentation boundary reports zero legacy presentation imports across active routes, stories, and the support-center root.
- Same-viewport visual comparison: passed for reusable shell ownership and supported contract-backed task hierarchy. Exact content density is not claimed where the reference depends on unsupported contracts.
- Automated pixel-diff threshold: not configured; the combined-image review above is the recorded visual evidence.

customer-support S130 visual result: passed within current contracts
