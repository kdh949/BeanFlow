# BeanFlow Frontend Agent Guide

## Scope

These rules apply to all work under `frontend/**`. Read `docs/design-system-governance.md` before any non-trivial UI change.

BeanFlow has three product surfaces: customer, store, and operations. Preserve one shared visual language while respecting each surface's layout and workflow needs.

## Sources of truth

1. Accepted product/design decisions and accessibility requirements
2. Editable tokens in `src/design-system/tokens/`
3. Typed React components and patterns in `src/design-system/`
4. Canonical Storybook stories and docs
5. Product pages that compose those components

Generated bundles, manifests, static Storybook output, screenshots, and rejected explorations are not canonical implementation sources.

## Mandatory Storybook MCP use

For every UI plan, implementation, review, or visual change, use the `beanflow_storybook` MCP before writing UI code.

1. Call `list-all-documentation`.
2. Call `get-documentation` for candidate components and patterns.
3. Call `get-documentation-for-story` when a specific usage needs more detail.
4. Call `get-storybook-story-instructions` before creating or substantially changing stories.
5. Never infer undocumented props or variants.
6. After changes, use `preview-stories`, `get-changed-stories` when available, and `run-story-tests`.

The repository transport is HTTP MCP at `http://localhost:6006/mcp`, declared in `frontend/.mcp.json`.
Start Storybook from `frontend/` with `npm run storybook`; do not point MCP at static `storybook-static/` output.
See `docs/storybook-runbook.md` for startup, recovery, and validation order.

If Storybook or the MCP server is unavailable, do not silently continue by guessing. Restore it or report the blocker. Diagnosis may continue, but UI implementation must wait.

## Before writing UI code

Report a compact reuse plan:

- user outcome and required states
- affected surface and routes
- candidate tokens/components/patterns found through MCP
- classification for each need: `REUSE`, `COMPOSE`, `EXTEND`, or `NEW`
- files likely to change
- validation plan

Prefer, in order: reuse → compose → extend → new. A new component requires a distinct responsibility or recurring pattern, not merely a similar appearance.

## Story-first workflow

Create or update the isolated Storybook state before wiring a new or materially changed product screen. Use deterministic fixtures, MSW, routing parameters, and a fixed clock where relevant.

Stories must be focused and explain why the state exists. Cover applicable loading, success, empty, error, permission, disabled, long-content, responsive, transaction-pending, unknown, reconciling, and manual-review states.

Canonical components use typed props, JSDoc purpose/prop descriptions, Autodocs, interaction tests, and accessibility checks.

## Design-system constraints

- Use existing semantic tokens for color, typography, spacing, radius, elevation, and motion.
- Do not add raw color/font/shadow values or static inline styles outside the token layer.
- Do not create parallel button, card, input, badge, dialog, status, navigation, or shell systems.
- Do not add unrestricted styling props to bypass component APIs.
- A new token must have a semantic name, documented usage, Storybook foundation example, and affected-component review.
- Literal geometry is allowed only when intrinsic or clearly documented; repeated or identity-bearing values become tokens.
- Retired `_ds_bundle.js` and `_ds_manifest.json` snapshots must remain absent. `storybook-static/` is generated output, not editable component source.

## Design explorations

When asked for alternatives, create two to four separate stories under `Explorations/<feature>` using the same fixture, viewport, states, and canonical components. Vary composition, hierarchy, density, or emphasis—not BeanFlow's brand palette or typography.

Explorations are temporary and must use `tags: ['!manifest']`. Do not wire them to a product route before selection unless explicitly requested.

After selection, promote only the chosen design to `Patterns/` or `Pages/`, implement it in the product, delete rejected stories/source/CSS, and add a short decision record.

## Accessibility and testing

- New and changed canonical stories use `parameters.a11y.test = 'error'`.
- A `todo` exception must be narrow and linked to a tracked decision or issue.
- Add `play` tests for user-visible interaction and state transitions.
- Verify keyboard behavior, focus visibility, labels, status announcements, long Korean copy, and reduced motion where applicable.

Run and report exact results for all relevant commands:

- `npm run typecheck`
- `npm run test:unit`
- `npm run check:design`
- `npm run build-storybook`
- `npm run build`
- `npm run test:sites`
- Storybook MCP `run-story-tests`

Do not claim a check passed unless it ran successfully. Report pre-existing and environment-specific failures separately.

## Visual source and Product Design

When a visual source is unclear or no longer matches the goal, use the Product Design plugin's `get-context` workflow before substantial redesign. When a selected mock exists, treat it as the source of design intent, but implement it with BeanFlow's canonical tokens and components rather than copying arbitrary values.

## Sites handoff

Keep `.openai/hosting.json`, `worker/index.js`, `scripts/prepare-sites-build.mjs`, and `tests/sites-worker.test.mjs` intact. Before Sites handoff, `npm run build` and `npm run test:sites` must leave and verify `dist/client/index.html`, `dist/server/index.js`, and `dist/.openai/hosting.json`.
