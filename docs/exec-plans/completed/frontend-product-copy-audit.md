# Replace defensive product copy with audience-calibrated language

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-08-28`

This ExecPlan follows `.agent/PLANS.md`.

## Purpose / Big Picture

Replace defensive or implementation-led copy across customer, store, operations, and support surfaces with language appropriate to each audience. Preserve explicit uncertainty, permission, privacy, and one-time-secret semantics.

## Current State

Customer pages expose phrases such as server calculation, browser authentication data, and request keys. Store and operations pages expose API, exact/canonical, transaction, snapshot, and fallback terminology. Storybook docs smoke and interaction tests assert some of these phrases.

## Definitions

- Product copy: text rendered to a product user from repository-owned TSX.
- Defensive copy: text that leads with internal constraints or implementation details instead of user outcome or next action.
- Safety semantics: copy that prevents duplicate payment, false logout/zero-balance assumptions, personal-data retention, or one-time-secret leakage.

## Scope

### In Scope

- Repository-owned rendered copy in customer, store, operations, and support surfaces.
- Related Storybook/test assertions and docs smoke expectations.
- A TypeScript-AST product-copy guard wired into `npm test`.

### Non-goals

- API, schema, domain-state, transaction, or persistence changes.
- Server-provided notification bodies and dynamic domain values.
- Storybook developer-facing component descriptions.
- Design-system component or token changes.

## Business Rules and Invariants

- Authentication failures do not reveal account existence.
- Session dependency failure is not presented as logout.
- Missing point-account data is not presented as a zero balance.
- Pending/unknown payment results never invite duplicate payment.
- Sensitive search values and one-time passwords retain explicit handling guidance.

## Architecture and Transaction Boundaries

Copy remains owned by feature/product presentation code. Design-system primitives continue to accept presentation-safe text. There are no transaction boundary changes.

## Alternatives Considered

- Customer-only cleanup was rejected because the same internal terminology is visible in store, operations, and support surfaces.
- One uniform conversational tone was rejected because operational precision is required for financial and support workflows.
- A raw text grep guard was rejected because it would inspect comments and code identifiers; the guard parses TSX and inspects only rendered JSX text and string attributes.

## Failure Semantics

Rewrites must not claim success, failure, logout, unchanged settings, or zero balance when the current state is unknown. Existing retry and correlation paths remain unchanged.

## Data and Migration

No data or migration changes.

## API and Event Contracts

No API or event changes.

## Milestones

1. Rewrite customer authentication, account, notification, loyalty, coupon, cart, and payment copy.
2. Rewrite store authentication, dispute, region, and refund copy.
3. Rewrite operations and support implementation jargon while preserving operational warnings.
4. Update assertions, add the AST copy guard, and wire it into the frontend test gate.
5. Run Storybook MCP and package validation, then move this plan to completed.

## Required Tests

- Customer sign-in, session gate, notification failure, point integrity failure, coupon, cart stale quote, and payment unknown stories.
- Store wrong-actor, region assignment, dispute, and refund stories.
- Operations authentication, merchant account, policy, compensation lookup, and support workspace stories.
- AST guard tests proving rendered copy is checked while comments, identifiers, API code, and Storybook developer descriptions are ignored.

## Validation Commands

- `npm run typecheck`
- `npm test`
- `npm run check:design`
- `npm run test:storybook:docs`
- `npm run build-storybook`
- `npm run build`
- `npm run test:sites`
- Storybook MCP preview and `run-story-tests` with accessibility enabled
- `git diff --check`

## Observability

No runtime observability changes. Existing correlation references and explicit domain states remain intact.

## Documentation Updates

This ExecPlan is the only product documentation change; no ADR or Business Policy changes are required.

## Progress

- [x] Live Storybook inventory and affected documentation inspected.
- [x] Product copy rewritten.
- [x] Tests and copy guard updated.
- [x] Validation complete.

## Surprises & Discoveries

- The sign-in copy is also a hard-coded Storybook docs smoke expectation.
- The prior foundation PR is merged, so this work starts from current `origin/main`.
- The first full Storybook run found two stale play assertions in operations stories; both were updated and the full suite passed on rerun.
- The Storybook Docs browser smoke requires execution outside the macOS sandbox because Chromium cannot register its Mach rendezvous service there.

## Decision Log

- Apply audience-specific tone to all surfaces.
- Use warm benefit-led sign-in copy.
- Exclude developer-facing Storybook descriptions from the rewrite.

## Outcomes & Retrospective

- Replaced implementation-led copy across 18 product presentation files and updated the corresponding unit, Storybook interaction, and docs-smoke expectations.
- Added `check-product-copy.mjs`, which parses production TSX with the TypeScript AST and checks only rendered JSX text and user-visible string properties. It ignores comments, identifiers, API implementation code, tests, design-system internals, and Storybook developer descriptions.
- Preserved the explicit no-duplicate-payment warning, unknown/failed session distinction, hidden point balance on integrity failure, personal-data handling guidance, and one-time-password handling guidance.
- Validation passed: TypeScript typecheck, 176 unit tests, 9 presentation-boundary tests, 7 product-copy tests, design adherence, Storybook build, 55-entry Docs smoke, application build, 4 Sites smoke tests, focused Storybook interaction/a11y, and the full Storybook interaction/a11y suite.

## Revision Notes

- 2026-08-28: Created before implementation from the approved user plan.
- 2026-08-28: Completed after product-copy migration, AST guard integration, and full frontend validation.
