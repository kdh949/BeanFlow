# BeanFlow Design System & Storybook Governance

> Intended repository path: `frontend/docs/design-system-governance.md`
> Status: Accepted, revised (2026-08-27)
> Scope: every change under `frontend/src/**` that affects rendered UI

## 1. Purpose

BeanFlow uses one design language across the customer app, store console, and operations console. The design system exists to make that language reusable, testable, and understandable by both people and AI agents.

Storybook is the executable catalog and review surface for the design system. It is not the sole source of implementation truth.

## 2. Canonical sources

Use this precedence when sources disagree:

1. Accepted product/design decision and documented accessibility requirement
2. Editable design tokens under `src/design-system/tokens/`
3. Typed, editable React components and patterns under `src/design-system/`
4. Canonical Storybook stories and docs that exercise those sources
5. Product screens that compose canonical components and patterns
6. Selected reference images and archived explorations as design-intent evidence only

The retired generated `_ds_bundle.js` and `_ds_manifest.json` snapshots are deleted and must not be restored.
Static Storybook output and screenshots are never editable component sources.

## 2.1 Canonical visual direction

- Cool white and slate surfaces, navy information hierarchy, and coral primary actions are the sole palette direction.
- Status is text-first. Filled green success badges and the retired `StatusBadge` API are prohibited.
- Customer surfaces are mobile-first and concise; store, operations, and support surfaces use the same language at workspace density.
- Decorative English eyebrow labels are not part of the system. Context labels must carry useful product meaning in normal reading order.
- Espresso, caramel, crema, `.bf-btn`, `components/Ui`, `components/Shells`, and refresh-only primitive/frame APIs are retired and may not return.

## 3. Library layers

```text
Foundations/
  Colors
  Typography
  Spacing
  Radius
  Elevation
  Motion
  Breakpoints
  Content rules

Components/
  Core
  Forms
  Feedback
  Navigation
  Commerce

Patterns/
  Customer
  Store
  Operations

Pages/
  Customer
  Store
  Operations

Explorations/
  <feature-or-ticket>/
```

- **Foundation**: a named token and its usage rule.
- **Component**: reusable UI with a small, typed public API.
- **Pattern**: a recurring composition with product meaning, such as an order summary, payment status panel, or store order card.
- **Page**: a route-level composition and its data states.
- **Exploration**: temporary alternatives used for selection. It is not part of the design system contract.

## 4. Reuse decision

Before adding UI, classify every need using this order:

| Decision | Meaning | Expected action |
|---|---|---|
| `REUSE` | An existing component or pattern already satisfies the need | Use its documented API unchanged |
| `COMPOSE` | Existing parts satisfy the need when combined | Create a local composition or reusable pattern; do not fork primitives |
| `EXTEND` | An existing component owns the concept but lacks a valid state/variant | Extend its typed API, stories, docs, and tests |
| `NEW` | No existing abstraction owns the concept | Add the smallest reusable component or pattern after documenting why the first three options fail |

A similar appearance is not enough reason to create a new component. A new component needs a distinct semantic responsibility, interaction contract, or repeated composition.

## 5. Design-token rules

### Required

- Colors, typography, spacing rhythm, radius, shadow, and motion used as design decisions resolve through existing tokens.
- Product code uses semantic or component tokens rather than raw palette values whenever a semantic alias exists.
- A new token has a semantic name, usage description, owner layer, affected surfaces, and a Storybook foundation example.
- Repeated or identity-bearing values are promoted to a token.

### Prohibited

- New raw hex, RGB, HSL, font-family, shadow, or arbitrary CSS custom properties outside the token layer.
- Inline style objects for static visual styling.
- A second button, card, badge, input, dialog, shell, or status system with different class naming.
- Tokens named after one page or one temporary variant when a semantic name is possible.
- Adding a token merely to hide a one-off arbitrary value.

### Documented exceptions

Literal dimensions may be valid for intrinsic geometry, media assets, hit targets, data visualizations, or breakpoints. The author must explain the exception when it is not self-evident. If the value recurs or defines product identity, promote it to a token.

## 6. Component API rules

- Public props are typed and documented with JSDoc, including purpose and constraints.
- Do not invent undocumented props based on common library conventions.
- Prefer explicit variants and states over unrestricted `className` or `style` escape hatches.
- A component protects its own accessibility semantics, focus behavior, disabled behavior, and loading behavior.
- Product-specific business text and data remain outside primitives unless the component is intentionally a domain pattern.
- Product source는 `input`, `select`, `textarea`를 직접 렌더링하지 않고 canonical field/selection component를 사용한다.
- Feature CSS는 canonical control의 color, border, focus, selected, disabled, touch-target 계약을 재정의하지 않고 배치만 소유한다.
- API error, domain state, 사용자 노출 문구의 변환은 shared/product presentation 계층이 소유하며 design-system은 presentation-safe prop만 받는다.
- Deprecated components are documented with a replacement and removed from the AI manifest.

## 7. Story rules

Each story demonstrates one concept or use case and explains **why** that case exists.

### Components

Cover applicable states:

- default and supported variants
- disabled and loading
- validation/error
- focus and keyboard interaction
- long text, wrapping, overflow, and missing optional content
- small and large viewports when behavior changes

### Patterns and pages

Cover applicable states:

- loading
- normal success
- empty
- recoverable error
- authentication/permission failure
- pending, unknown, reconciling, or manual-review transaction states
- long Korean text and large currency/quantity values
- mobile and desktop layouts when both are supported

Use MSW handlers, router parameters, deterministic clocks, and fixtures so a reviewer can open a state directly without reproducing it through the live application.

### Documentation quality

- Enable Autodocs for canonical components.
- Add component and prop JSDoc so humans and MCP tools receive purpose and usage constraints.
- Add explicit Storybook descriptions for non-obvious stories and patterns.
- Foundation MDX must explicitly materialize important token names, values, and usage guidance. A runtime-only loop over imported tokens is insufficient for an agent-readable static manifest.
- Exclude deprecated, anti-pattern, and temporary exploration stories from the MCP manifest with `tags: ['!manifest']`.

## 8. Exploration and selection workflow

Use explorations when the goal is to compare layouts or visual emphasis before product integration.

1. Define one shared content/data fixture, viewport, required states, and interaction constraints.
2. Create two to four separate stories under `Explorations/<feature>`.
3. Reuse the same tokens and canonical components in every alternative.
4. Vary layout, density, grouping, hierarchy, or emphasis. Do not invent a new brand palette or typography system.
5. Mark every alternative as temporary and exclude it from the MCP manifest.
6. Preview the alternatives in Storybook and record the selected alternative.
7. Promote only the selected composition to `Patterns/` or `Pages/`.
8. Delete rejected stories, source branches, temporary CSS, and dead fixtures.
9. Keep only a short decision record describing the alternatives, selection reason, and trade-off.
10. Implement or update the product route only after selection unless the user explicitly requests a functional prototype first.

Do not keep rejected alternatives in the canonical Storybook. They create conflicting examples for future agents.

## 9. Product-screen workflow

```text
Clarify user outcome and states
→ Query Storybook inventory and documentation through MCP
→ Inspect related product patterns
→ Classify REUSE / COMPOSE / EXTEND / NEW
→ Create or update canonical stories first
→ Preview and test the stories
→ Compose the real product route
→ Run route-level and Storybook validation
→ Update documentation and decision records
```

A product route must compose the same components and patterns shown in Storybook. Do not build a parallel markup/CSS implementation that merely looks similar.

## 10. Mandatory Storybook MCP protocol

For any UI planning, implementation, review, or visual modification:

1. Use `list-all-documentation` to inspect the canonical inventory.
2. Use `get-documentation` for candidate components/patterns and their supported props.
3. Use `get-documentation-for-story` when a specific state or usage requires more context.
4. Use `get-storybook-story-instructions` before authoring or substantially changing stories.
5. Use `preview-stories` for human reviewable output.
6. Use `get-changed-stories` after implementation when change detection is available.
7. Use `run-story-tests` for changed and affected stories, including interaction and accessibility checks.

If Storybook or its MCP server is unavailable, do not silently guess and continue writing UI. Restore the prerequisite or report the blocker. File inspection and diagnosis may continue, but UI implementation waits until the contract can be queried.

## 11. Accessibility and interaction quality

- Canonical new and changed stories use `parameters.a11y.test = 'error'`.
- A temporary `todo` is allowed only with a linked issue/decision and a narrow story-level exception.
- Interactive stories include `play` tests for the behavior that matters to the user.
- Keyboard navigation, focus visibility, accessible labels, and status announcements are part of the component contract.
- Reduced-motion behavior is tested where motion is present.

## 12. Automated guardrails

The repository must expose real executable checks; configuration files that are not wired into scripts and CI do not count as enforcement.

Recommended checks:

- `typecheck`
- unit tests
- Storybook/Vitest browser tests
- Storybook static build
- design-token/adherence lint
- native-control and CSS ownership boundary regression tests
- accessibility tests
- product build and Sites handoff tests
- visual regression for canonical critical stories once a clean baseline is approved

When existing debt prevents an immediate zero-violation rule, baseline the existing violations and fail CI on newly introduced violations. Track removal of the baseline explicitly; do not normalize permanent exceptions.

## 13. Visual regression

Visual regression is the guard against unintentional design drift; Storybook documentation alone does not detect changed rendering.

Choose one path and document it:

- Chromatic for hosted review and approved baselines, or
- Playwright screenshot assertions for a repository-controlled baseline.

Establish the baseline only after duplicate component systems and known style defects are normalized. If credentials or infrastructure are unavailable, report visual regression as `Not configured`; do not claim coverage.

## 14. Definition of done for UI changes

A UI change is complete when:

- the reuse decision is recorded in the work report
- canonical components and props are documented rather than guessed
- all relevant states can be opened directly in Storybook
- rejected explorations and dead styling are removed
- no unapproved token or parallel component system was introduced
- interaction and accessibility tests pass for changed stories
- typecheck, unit tests, Storybook tests/build, product build, and relevant handoff checks report exact results
- affected product, design-system, and decision documentation is updated
- any unavailable or failed validation is reported honestly
