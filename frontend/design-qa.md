# BeanFlow design QA

## Target and evidence

- Reference: the supplied BeanFlow design archive, including the customer 420px frame, desktop console screenshots, brand assets, and design-system tokens.
- Implementation: `/app`, `/store`, and `/ops` from the local Vite preview.
- Comparison: the operator reference and implementation were inspected together at the same 909×525 crop. The customer application was inspected in its 420px product frame.

## Findings resolved

- P1 · Behavior: missing `lat` and `lng` query parameters were coerced to zero and started an unintended store query. The client now requires both parameters before using URL coordinates.
- P2 · Color and hierarchy: the first customer pass used a dark hero that drifted from the warm, light source screen. The hero, stage, location control, typography, and spacing now use the supplied crema/espresso/caramel tokens and source hierarchy.
- P2 · Accessibility: selectable menus and pickup slots now expose pressed state; unavailable stores are removed from keyboard traversal; forms retain explicit labels, disabled states, and visible focus rings.

## Final pass

- Typography: Pretendard and IBM Plex Mono, source weights, compact title tracking, and Korean line-height are consistent.
- Layout and spacing: 420px customer frame, 236px desktop sidebar, card radii, warm surfaces, page gutters, and console grids match the source system without overlaps or clipping in the checked frames.
- Colors and surfaces: brand literal colors and semantic success/warning/danger states come from the supplied tokens; cards use the source borders and low elevation.
- Assets and icons: supplied raster logos are used without stretching; Lucide provides one consistent icon family; no handcrafted SVG, CSS illustration, placeholder avatar, or fake product image was introduced.
- Content and states: customer, store, and operator routes include real loading, empty, error, selected, disabled, unknown, reconciliation, and success treatments. Product data is never fabricated when the API has no list result.
- Interaction: primary navigation, customer order lookup, store lookup and transition controls, refund mode switch, and operator compensation lookup are reachable and behave as represented.

Final result: passed

## Customer P0 integration pass (2026-08-16)

- Scope: the 13 customer P0 screens after the Session/CSRF client, route guard, client cart, payment
  recovery, order actions, points and reorder work.
- Method: Vitest + Testing Library on the jsdom DOM, plus `npm run build`, then a 420px browser pass
  against the `scripts/demo` stack with a real customer Session. Pixel geometry was not re-measured, so no
  new pixel comparison is claimed.
- Navigation: the customer tab bar is 홈 · 매장 · 주문 · 마이. The token strip was removed from the
  customer shell and now belongs to the console shell only.
- Forms: login, signup, cancellation and search fields keep explicit labels; the login and signup errors
  are `role="alert"` and are referenced from the field through `aria-describedby`.
- Motion: `.spin` is used for pending payment and refund states and is disabled under
  `prefers-reduced-motion: reduce`, so a pending state stays readable without animation.
- States: every customer screen renders loading, empty, failure and conflict separately. A failed read is
  never drawn as an empty list or a zero balance.

- Status wording: the browser pass found customer screens printing raw enum codes (`NOT_REQUIRED`,
  `SUCCEEDED`) and a pickup-counter instruction on cancelled and unpaid orders. `StatusBadge` now takes an
  explicit label wherever one code means different things to different lifecycles, and the pickup number is
  hidden unless the store is actually going to hand the order over.
- Payment result: the success URL now reports an unapproved (`READY`) or declined (`FAILED`) payment as
  stopped instead of drawing the success mark, and the approval read no longer stalls on a remount.
- Store identity: the store screen and the cart read the store name from the server rather than from
  navigation state, so a pasted URL, a deep link and a reload all name the store the same way. A store
  that is gone gets a customer-facing empty state instead of the server's own English sentence.

Result: automated state coverage passed, and the 420px browser pass ran end to end against a live backend.
It surfaced four defects that the jsdom tests missed; all four are fixed with regression tests. Store
search with non-empty results was not exercised because the demo fixture has no search index.
