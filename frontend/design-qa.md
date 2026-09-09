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
