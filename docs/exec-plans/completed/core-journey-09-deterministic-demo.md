# 결정적 로컬 공개 금융 여정 데모

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/local-demo-environment-and-smoke.md`, `docs/exec-plans/completed/productization-90-merchant-financial-workflows.md`
> **Completed-At:** `2026-08-18`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. Goal Controller가 Stage 06+08 통합 SHA
`fd1ec35f8da13bb38a390cca507e4b3fdb90ffef`의 combined green을 확인한 뒤 Stage 09 구현을 허용했다.

## Purpose / Big Picture

새 checkout에서 `start → seed → smoke → reset`만으로 고객 coupon·option·slot·point, Stage 06 public
payment/order reference, Stage 08 public refund, confirmed settlement/partial-refund adjustment/owner dispute를
재현한다. 출력은 login alias와 semantic outcome만 쓰며 UUID, provider key, PAN/CVC, secret은 출력하지 않는다.

## Current State

- 기존 local-demo CLI는 owner JPA fixture와 one transaction rollback을 사용한다.
- 기존 smoke는 일부 legacy internal store/refund path와 fixed compose project/ports를 사용했다.
- financial history는 immutable snapshot/table guard를 충족해야 하며 runtime smoke는 DB를 직접 읽거나 쓰지 않는다.

## Definitions

- **checkout namespace:** canonical checkout path에서 유도한 compose project/container/port block이다.
- **historical financial tail:** live smoke order와 분리된 completed order, approved payment, succeeded partial
  refund, confirmed settlement item/batch, adjustment의 immutable seed history다.
- **public order reference:** `BF-XXXX-XXXX` order alias이며 merchant lifecycle/refund API의 only human locator다.

## Scope

### In Scope

- `scripts/demo`, demo compose, local-demo test fixture/CLI, focused tests, this runbook/ExecPlan
- deterministic historical financial-tail fixture and public runtime smoke
- checkout-owned compose resource/port guards, no-ID output guard

### Non-goals

- Flyway migration, public/debug endpoint, raw DB smoke mutation, production fake fallback, UI/OpenAPI/README/release docs
- Toss sandbox behavior, production payment provider, scheduler payout or operations decision workflow

## Business Rules and Invariants

1. Required configuration/policy is explicit; local-demo never silently creates default policy or substitutes fake provider in prod.
2. `UNKNOWN` stays explicit and lookup/reconciliation-driven; it is never reclassified as decline.
3. Refund requires a fresh server preview and public `orderReference`; internal IDs stay server/runtime-local only.
4. Settlement item/adjustment/batch history is immutable and ties out; dispute is owner-only and within ADR-018 window.
5. Reset affects only the exact checkout container, database identity, compose label and runtime directory.

## Architecture and Transaction Boundaries

The seed is one local-demo CLI transaction. It writes owner entities plus the existing explicit persistence
snapshots required by database guards; on any failure all fixture writes roll back. Provider calls remain outside
transactions. The smoke has no JDBC/psql/docker write path and uses HTTP API calls only after startup.

## Alternatives Considered

- New debug/setup endpoint: rejected; it would widen public attack surface and bypass established commands.
- Raw SQL financial fixture: rejected; it could bypass immutable/transition triggers.
- Owner entity fixture with necessary immutable snapshots: selected; it exercises current constraints while keeping setup local-only.

## Failure Semantics

- A busy unowned derived port aborts before application startup; no existing process is killed.
- compose/inspect ownership ambiguity aborts reset and retains key material.
- seed failure exits non-zero and leaves no partial transaction.
- API unexpected status, timeout, UNKNOWN non-convergence, or missing adjustment/dispute exits smoke non-zero; no body/UUID/key is printed.

## Data and Migration

No migration is written. The fixture adds two synthetic historical completed orders and the required public-reference,
ordinary-accrual, settlement-input snapshots; one order has a succeeded 5,000 KRW partial refund and a later confirmed
batch carries its -5,000 KRW adjustment. No real payment data, key, secret or PII is committed.

## API and Event Contracts

Smoke calls existing runtime OpenAPI operations: coupon wallet, menus/slots/points, `GET/POST /payments`, public store
order transition/refund preview/refund by reference, settlements/items, and dispute create/list. No contract is changed.

## Milestones

1. [x] Re-audit Stage 09 prompt, policies, failure semantics, Stage 06/08 contracts and existing demo constraints.
2. [x] Add isolated compose namespace, port and reset ownership guard.
3. [x] Add historical immutable financial tail and public smoke coverage.
4. [x] Run focused tests, docs verification and isolated runtime sequence; record evidence.
5. [x] Review the bounded Stage 09 diff and prepare its atomic delivery.

## Required Tests

- `LocalDemoSeedIntegrationTest`: idempotency, rollback, no policy fallback, immutable adjustment chain.
- `LocalDemoScriptGuardTest`: reset ownership and no unowned-port/process takeover.
- `LocalDemoRepositorySafetyTest`: runtime OpenAPI smoke inventory and tracked-secret guard.
- isolated `start → seed → seed → smoke → reset` on this checkout only.

## Validation Commands

```bash
./gradlew test --tests io.github.kdh949.beanflow.demo.LocalDemoSeedIntegrationTest \
  --tests io.github.kdh949.beanflow.demo.LocalDemoScriptGuardTest \
  --tests io.github.kdh949.beanflow.demo.LocalDemoRepositorySafetyTest
bash scripts/verify-docs.sh
bash scripts/demo/start.sh && bash scripts/demo/seed.sh && bash scripts/demo/seed.sh && \
  bash scripts/demo/smoke.sh && bash scripts/demo/stop.sh --reset
```

The final command is intentionally not run concurrently with another demo checkout and must report actual outcomes.

## Observability

Each smoke request logs only operation, HTTP status and correlation ID. Response bodies are retained in the untracked
runtime directory on failure rather than printed. Startup/seed logs remain checkout-local under `.demo-runtime`.

## Documentation Updates

- [x] `docs/operations/local-demo-runbook.md`: public financial tail and checkout isolation.
- [x] This ExecPlan records scope, immutable snapshot rationale and validation.
- [x] Moved this file to `completed/` for the completion commit with verified evidence.

## Progress

Implementation and the bounded diff review are complete. The actual clean checkout sequence and focused validation
passed; the release boundary is this atomic Stage 09 change only.

- 2026-08-18 review remediation: the historical settlement input now uses the Ordering owner's canonicalizer and
  `OrderSettlementInputSnapshotOperations.read` verifies hash and amount tie-out after seeding. The runbook follows
  the checkout-derived frontend URL printed by `start.sh`, and resource mismatch failures emit no internal UUID.

## Surprises & Discoveries

- Historical Orders are protected by deferred ordinary-accrual and settlement-input snapshot guards.
- Settlement batch database guard requires a persisted `OPEN → CALCULATED → CONFIRMED` sequence, not a combined flush.

## Decision Log

- 2026-08-18: Use owner entities and required immutable snapshot persistence; no product/ADR decision changes because
  existing local-demo CLI and financial contracts already define the allowable local setup path.

## Outcomes & Retrospective

- Passed: forced focused Gradle tests (`LocalDemoSeedIntegrationTest` 6, `LocalDemoScriptGuardTest` 13,
  `LocalDemoRepositorySafetyTest` 5, `OrderSettlementInputSnapshotIntegrationTest` 8; zero failures/errors),
  `bash scripts/verify-docs.sh` (47 business policies,
  113 ADRs, 294 Markdown files and 63 ExecPlans), shell syntax, and `git diff --check`.
- Passed: in this checkout only, isolated `start → seed (49 rows) → seed (0 rows) → smoke → stop --reset`.
  Smoke covered applicable coupon, menu option, pickup slot, point accrual, public payment/order reference and
  merchant transitions, public refund preview/execution/replay, confirmed settlement with -5,000 KRW adjustment,
  owner dispute, UNKNOWN reconciliation and authorization checks. Reset stopped only the derived process groups
  and removed only the derived compose database/container/key material.
- Not run: the repository-wide Gradle gate; the Goal Controller owns that combined Stage 06+08 result and no
  repository-wide result is inferred here.

## Revision Notes

- 2026-08-18: Created for Stage 09 implementation after Goal Controller confirmed Stage 06+08 combined green.
- 2026-08-18: Marked complete after the isolated runtime and focused/document validation evidence above.
- 2026-08-18: Replaced the placeholder settlement hash with the owner canonicalizer, aligned dynamic frontend URL
  documentation, and added executable UUID non-disclosure coverage after review.
