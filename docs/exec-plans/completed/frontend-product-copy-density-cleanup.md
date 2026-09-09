# Remove implementation-led helper copy from product UI

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/frontend-product-copy-audit.md`
> **Completed-At:** `2026-08-28`

This ExecPlan follows `.agent/PLANS.md`.

## Purpose / Big Picture

Reduce copy density across customer, store, operations, and support surfaces after the product-copy audit. Keep only text that changes the user's next action or communicates a monetary, permission, expiry, destructive, or uncertain outcome.

## Scope

- Remove copy that repeats headings and controls or explains storage, logging, retry, and internal processing mechanics.
- Simplify the one-time-password result to its title, value, expiry, and explicit clear action.
- Update affected Storybook interactions, unit assertions, and the rendered-copy AST guard.
- Preserve API contracts, domain states, persistence behavior, and design-system public APIs.

## Business Rules and Invariants

- A temporary password remains in current route memory only and disappears after explicit clearing or remounting.
- Pending, unknown, reconciling, and manual-review outcomes remain distinct from success and failure.
- Monetary, permission, expiry, irreversible-action, and sensitive-data disclosure warnings remain visible when they affect user behavior.

## Architecture and Transaction Boundaries

Product copy remains owned by feature and presentation code. Existing `PageHeading`, `Button`, fields, feedback patterns, and domain status presentation are reused without extension. No API, aggregate, database, transaction, or external-provider boundary changes.

## Milestones

1. Remove redundant customer and store helper copy without changing transaction state rendering.
2. Simplify merchant account, operations policy, and support workspace instructions.
3. Extend the product-copy guard with exact implementation-led phrases and add regression tests.
4. Run focused and broad Storybook interaction/accessibility validation plus all frontend gates.
5. Record outcomes and move this plan to completed.

## Required Tests

- One-time password value, expiry, clear action, remount clearing, and absence from browser storage.
- Removed phrases absent from product rendering while operational documentation and implementation code remain out of the AST guard scope.
- Customer cart/store failure, store region/refund, operations policy/account, and support workspace stories remain interactive and accessible.

## Validation Commands

- `npm run typecheck`
- `npm test`
- `npm run check:design`
- `npm run test:storybook:docs`
- `npm run build-storybook`
- `npm run build`
- `npm run test:sites`
- Storybook MCP changed-story discovery, preview, and focused/full `run-story-tests(a11y=true)`
- `git diff --check`

## Progress

- [x] Live Storybook inventory, page documentation, and canonical component APIs inspected.
- [x] Product copy and tests updated.
- [x] Focused and full validation complete.
- [x] Plan moved to completed with outcomes.

## Decision Log

- Reuse existing canonical components; this change does not introduce or extend a visual primitive.
- Keep security behavior in code and tests instead of narrating storage and logging mechanics in product UI.
- Match only exact implementation-led phrases in the guard; do not ban broad words such as `저장` or `한 번`.

## Revision Notes

- 2026-08-28: Created before implementation from the approved follow-up plan.
- 2026-08-28: Completed after copy density cleanup and full frontend validation.

## Surprises & Discoveries

- The Storybook Docs smoke initially failed because the macOS sandbox denied Chromium's MachPort registration. The same command passed outside the sandbox.
- Existing route-local state already enforced temporary-password disposal; the follow-up added explicit clear and remount assertions without changing persistence code.

## Outcomes & Retrospective

- Removed implementation-led helper copy from customer ordering, store region/refund, operations account/policy, and support workflows while preserving monetary, permission, expiry, and uncertain-state guidance.
- Reduced the temporary-password result to its title, value, expiry, and `화면에서 지우기` action.
- Added exact rendered-copy guard terms and regressions without scanning code comments, API implementation, tests, Storybook descriptions, or operational documentation.
- Validation passed: 176 unit tests, 9 presentation-boundary tests, 9 product-copy tests, design adherence, 55-entry Storybook Docs smoke, focused and full Storybook interaction/a11y suites, Storybook/application builds, and 4 Sites smoke tests.
