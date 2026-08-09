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
