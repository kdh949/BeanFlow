# Remove page-heading descriptions and repeated helper copy

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/frontend-product-copy-density-cleanup.md`
> **Completed-At:** `2026-08-28`

This ExecPlan follows `.agent/PLANS.md`.

## Purpose / Big Picture

Make product pages quieter by limiting the canonical page heading to a title and optional action. Remove repeated helper copy while keeping safety, permission, monetary, expiry, failure, and next-action guidance close to the state or control it explains.

## Scope

- Remove `description` from the `PageHeading` and refresh wrapper public contracts.
- Migrate every customer, store, operations, and support page to title-only headings.
- Remove repeated page, shell, and account helper messages and preserve essential guidance at its point of use.
- Update canonical stories, CSS ownership, inventory, AST guard, and affected assertions.

## Business Rules and Invariants

- Pending or unknown payment must still prevent duplicate payment attempts.
- A forced merchant password change must remain clearly actionable before store access.
- Refund order identity, monetary meaning, permission failures, expiries, destructive consequences, and empty/error next actions remain visible.
- Field, feedback-state, and Storybook developer descriptions remain separate contracts and are not removed.

## Architecture and Public Interface

`PageHeadingProps` becomes `{ title: string; action?: ReactNode }`. `RefreshPageHeading` mirrors that contract. TypeScript and the product-copy AST guard both reject a page-heading `description` prop. No API, aggregate, database, transaction, or dependency changes occur.

## Milestones

1. Narrow the canonical PageHeading API, Storybook stories, styles, and inventory.
2. Migrate product consumers and relocate the four essential pieces of guidance.
3. Remove repeated account and shell helper copy.
4. Add structural and exact-copy guard regressions.
5. Run focused and full Storybook/frontend validation, then move this plan to completed.

## Required Tests

- PageHeading renders one h1, supports an action, handles a long Korean title, and renders no description paragraph.
- Product source rejects `description` on `PageHeading` or `RefreshPageHeading` even if the copy itself is otherwise allowed.
- MyPage, authentication, customer transaction/help, store workspace, operations, and shell stories preserve their actions and required state guidance.

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

- [x] Live Storybook inventory and PageHeading documentation inspected.
- [x] Canonical API, product consumers, and guard updated.
- [x] Focused and full validation complete.
- [x] Plan moved to completed with outcomes.

## Decision Log

- Remove the heading description API instead of relying on consumer convention.
- Keep feedback and field descriptions because they explain state or input constraints rather than page purpose.
- Ban only the structural heading prop and selected repeated phrases; do not ban general Korean helper vocabulary.

## Revision Notes

- 2026-08-28: Created before implementation from the approved plan.
- 2026-08-28: Completed after product migration and full frontend validation.

## Surprises & Discoveries

- Removing the cart heading description exposed that the saved store name disappeared when the current store lookup failed. The existing store summary now owns both the current name and the saved-name failure state, preserving orderability and product context without restoring heading copy.
- The first broad Storybook run dropped its MCP connection because an old Storybook Vitest process still held port 63320. Stopping that exact process and restarting one Storybook server restored the live index; the rerun passed the complete interaction and accessibility suite.

## Outcomes & Retrospective

- Reduced `PageHeading` and `RefreshPageHeading` to title plus optional action, migrated every product consumer, and removed the obsolete paragraph styles.
- Removed repeated MyPage, workspace, and role-choice helper copy while keeping duplicate-payment prevention, forced password-change access guidance, refund order identity, and cart store identity at their decision points.
- Added structural AST enforcement for heading descriptions and exact regressions for the removed repeated phrases.
- Validation passed: TypeScript, 176 unit tests, 9 presentation-boundary tests, 11 product-copy tests, design adherence, 55-entry Storybook Docs smoke, focused and full Storybook interaction/a11y, Storybook/application builds, 4 Sites smoke tests, and `git diff --check`.
