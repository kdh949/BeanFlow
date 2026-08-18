# 고객 결제 결과를 공개 주문번호로 추적하고 수동 검토 상태를 안전하게 종료한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/active/core-journey-05-customer-coupon-wallet.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`와 [ADR-080](../../adr/ADR-080-toss-v2-one-time-payment-window.md)을
따른다. Stage 05의 backend/API outcome은 이 plan의 direct input이지만 wallet selector UI는 Storybook
MCP 부재로 아직 ACTIVE다. 따라서 canonical metadata는 automatic candidate가 아님을 나타내는
`Implementation-Ready: false`를 유지한다. Goal Controller가 소비하는 exact remote-green backend/API
dependency는 별도 Progress evidence로 기록한다.

## Purpose / Big Picture

고객이 Toss callback 뒤 기존 Payment를 다시 승인하지 않고, 사람용 `orderReference`로 주문을
확인하게 한다. Provider 결과가 bounded lookup 뒤에도 불명확해 `MANUAL_REVIEW`가 되면 고객 화면은
자동 polling을 끝내고 안전한 주문 추적과 도움 경로만 제공한다. 이 plan은 기존 one-time Payment
attempt, callback validation, reconciliation, cancellation, reorder와 point read model을 다시 쓰지
않는다.

## Current State

- Stage 05 Draft PR #93의 head `3211fffd5d4b3b4f527ac0e296313b39ce5d5b73`에서 preflight, frontend,
  backend-build, build, test shard 0~5가 모두 remote green이다. Stage 05 backend/API coupon wallet
  contract는 integrated input이며, selector UI completion은 이 plan의 dependency가 아니다.
- `PaymentConfirmation`과 `OneTimePaymentAttempt` source/view에는 internal `orderId` UUID가 존재한다.
  특히 `PaymentConfirmationResponseFactory`, `PaymentResultTransaction`, `PaymentReconciliationService`가
  status, idempotent replay 또는 reconciliation response body에 이를 직렬화한다.
- Customer payment result page는 `payment.orderId`를 축약해 주문 번호로 표시하고 checkout link에도
  사용한다. 반면 customer order list/detail는 이미 `orderReference`를 customer tracking value로 사용한다.
- Payment reconciliation은 bounded lookup 뒤 Payment와 work를 `MANUAL_REVIEW`로 전환하고 operator
  reprocessing case를 만든다. 그러나 current customer hook은 `MANUAL_REVIEW`를 pending polling state로
  취급한다.
- [ADR-096](../../adr/ADR-096-public-order-reference.md)는 새 human-facing customer/store API가
  `publicReference`만 노출하도록 정한다. [ADR-080](../../adr/ADR-080-toss-v2-one-time-payment-window.md)은
  owner-scoped `paymentId` status path와 Toss `providerOrderId` binding을 이미 정한다.

## Definitions

- **orderReference:** Order가 소유하는 `BF-XXXX-XXXX` public reference다. 고객의 주문 번호 표시와
  주문 추적에만 쓴다.
- **orderId:** `ordering_order.id` internal UUID다. Payment, foreign key, audit와 server transaction에서
  유지하지만 customer status/confirmation response 또는 UI display에는 쓰지 않는다.
- **paymentId:** customer owner authorization이 반드시 결합된 Payment status/confirmation correlation
  locator다. order reference나 권한 증명이 아니다.
- **providerOrderId:** Toss request/callback/lookup binding에만 쓰는 서버 생성 order identifier다.
  BeanFlow human order number가 아니다.
- **MANUAL_REVIEW:** bounded reconciliation이 자동 수렴을 중단했고 operator case가 필요한 terminal
  recovery state다. 결제 성공·명시적 거절·재결제 가능 상태가 아니다.

## Scope

### In Scope

- Customer `GET /payments/{paymentId}`와 `POST /payments/{paymentId}/confirmations` response에서
  `orderReference`를 제공하고 internal `orderId`를 제거하는 target/runtime OpenAPI, generated client,
  backend response projection과 regression tests
- status read, same-callback replay, provider unknown/reconciliation/late-result body가 같은 public
  response boundary를 지키는지 검증
- `MANUAL_REVIEW`를 customer UI의 terminal recovery state로 분리하는 implementation, canonical stories,
  accessibility and interaction tests
- existing customer order detail의 public-reference tracking link와 result page의 transition 정합화

### Non-goals

- Toss Standard Payment Window, callback parameter, provider confirm/query/cancel request 또는 stable
  Provider idempotency key 변경
- Payment aggregate rewrite, Payment/Order primary-key 변경, public reference 재생성 또는 Flyway migration
- PaymentMethod, billing, BrandPay, multi-PG, webhook, provider fallback 또는 automatic ambiguous-confirm retry
- coupon wallet selector UI, campaign/issuance behavior, merchant finance, program orchestration, release gate,
  README 또는 shared route/Shell ownership 변경

## Business Rules and Invariants

1. Customer payment status/confirmation response는 `orderReference`를 human display/track field로
   반환하고 internal `orderId`를 반환하지 않는다. Payment ownership authorization은 계속 server-side로
   검증하며 `paymentId` knowledge만으로 다른 customer Payment를 읽을 수 없다.
2. `providerOrderId`, callback `orderId`, `paymentKey`, amount와 stable Provider idempotency key의 exact
   binding은 그대로 유지한다. public `orderReference`는 Provider confirm input을 대체하지 않는다.
3. `APPROVED`만 결제 완료를 의미한다. `UNKNOWN`과 `RECONCILING`은 기존 payment status lookup으로만
   수렴하며 새 confirmation 또는 새 payment attempt를 만들지 않는다.
4. `MANUAL_REVIEW`는 customer automatic polling과 automatic confirmation retry를 종료한다. 고객 UI는
   tracking/help만 제공하고 checkout/new-payment action, raw provider failure, payment key, internal IDs와
   operator case details를 표시하지 않는다.
5. explicit Provider decline과 `READY`의 existing retryability semantics는 unchanged다. UI는 실제
   server approval state와 allowed route contract를 앞질러 새로운 retry authority를 만들지 않는다.

## Architecture and Transaction Boundaries

```text
Customer status/confirmation request
  -> Customer actor + owner check
  -> Payment view (paymentId/internal orderId remains server-only)
  -> Ordering-owned public order-reference projection
  -> customer PaymentConfirmation response

Tx A  prepare snapshot/idempotency/reconciliation work commit
Tx B  callback binding + APPROVING claim commit
No Tx Toss confirm or lookup
Tx C  Payment result + Order/resource transition + idempotent response + Audit commit
```

- Controller는 Repository를 직접 호출하지 않는다. Application service가 customer actor, Payment view와
  Ordering public-reference projection을 조정한다.
- `orderReference` projection read는 Order aggregate state transition이나 Payment state를 변경하지 않는다.
- Existing Tx C는 result, Order/resource state, idempotent response와 Audit의 atomic commit boundary를
  보존한다. external provider 호출을 transaction 안으로 옮기지 않는다.
- reconciliation/response replay가 persisted response body를 사용하면 새 public boundary도 같은 body에
  적용한다. internal `orderId` body를 hidden legacy replay로 반환하지 않는다.

## Alternatives Considered

### UI에서만 UUID를 축약하지 않기

- 장점: 변경 파일이 작다.
- 단점: customer API가 계속 internal Order UUID를 노출하고 replay/reconciliation body와 UI contract가
  어긋난다.
- 결정: 기각한다.

### Payment에 public reference를 중복 저장하고 migration 추가

- 장점: Payment response를 단일 table에서 읽을 수 있다.
- 단점: Order가 이미 소유한 immutable public reference를 복제하고 schema writer lease와 backfill을
  불필요하게 연다.
- 결정: 기각한다. Ordering-owned read projection을 사용한다.

### MANUAL_REVIEW를 기존 pending polling으로 유지

- 장점: UI 분기가 없다.
- 단점: bounded automatic recovery 종료를 숨기고 customer browser가 영구 polling하며 support escalation
  경계가 사라진다.
- 결정: 기각한다.

### paymentId를 public order reference로 교체

- 장점: URL의 UUID가 줄어든다.
- 단점: ADR-080의 owner-scoped payment correlation, callback recovery, idempotency association을
  불필요하게 재설계한다.
- 결정: 기각한다. paymentId는 locator로만 유지한다.

## Failure Semantics

- status/confirmation owner check 실패는 existing 403/404 contract를 유지한다. 다른 owner의
  `orderReference` 또는 Payment 존재를 response/body timing으로 추론하게 하지 않는다.
- Ordering public-reference projection 또는 Payment persistence가 실패하면 `orderReference`를 UUID,
  compact ID, stale cache, local map 또는 guessed value로 대체하지 않는다. typed dependency failure로
  끝내고 business success를 반환하지 않는다.
- callback mismatch, amount mismatch, transport failure, response loss와 Tx C failure는 existing
  `UNKNOWN`/`RECONCILING` and reconciliation semantics를 유지한다. 새 Provider key로 confirm하지 않는다.
- `MANUAL_REVIEW`는 backend worker retry exhaustion 뒤 남는 explicit terminal state다. UI는 success,
  terminal decline 또는 silently retrying loading state로 번역하지 않는다.
- Storybook MCP가 unavailable인 동안 UI route, component, story, a11y/browser proof는 구현하지 않는다.
  backend/API contract는 fake UI or fallback data로 대신 증명하지 않는다.

## Data and Migration

`Writes-Migration: false`다. `ordering_order.public_reference`와 V50/V51 public-reference constraints가
이미 존재하며, 이 plan은 Payment schema에 public reference를 복제하지 않는다. new index, backfill,
Flyway file, migration-writer lease는 추가하지 않는다. source가 existing reference lookup으로 public
response를 만들 수 없음을 증명할 때만 separate successor plan과 current inventory/lease audit를 요구한다.

## API and Event Contracts

The subsequent backend/API slice changes these customer response semantics atomically in target/runtime OpenAPI
and generated frontend schema:

```text
GET  /api/v1/payments/{paymentId}
POST /api/v1/payments/{paymentId}/confirmations

PaymentConfirmation
  paymentId: owner-scoped correlation locator
  orderReference: human display and tracking field
  approvalState, approvedAmountKrw, currency, recovery, updatedAt, correlationId
  no internal orderId
```

- `paymentId` endpoint path and existing customer Session authorization remain unchanged.
- Toss success callback request keeps its `paymentKey`, Provider `orderId` and amount fields. Its Provider
  `orderId` is not the removed BeanFlow response field.
- `MANUAL_REVIEW` remains a valid `PaymentApprovalState` and recovery state, but it gains the documented
  client terminal behavior; no success-shaped payload or automatic command is added.
- No new event, payment state, error code, schema migration or provider endpoint is created by this contract.

## Milestones

1. Record this ADR-080 public-boundary amendment and this owner ExecPlan before source/API edits.
2. Add a failing customer response contract/integration test for public `orderReference`, absent internal
   `orderId`, owner isolation and response replay/reconciliation consistency.
3. Acquire `SHARED_CONTRACT_WRITER`; update target/runtime OpenAPI, generated schema and Ordering/Payment
   application response projection in one backend/API slice.
4. Run focused PostgreSQL Testcontainers, OpenAPI parity/auth registry, modularity/format and documentation
   validation. Keep `FULL_GRADLE_GATE` exclusive for the later combined gate.
5. Only after the Storybook MCP is restored and leased to this exact worktree, create/update result-page
   stories for approved, declined, ready, unknown, reconciling and manual-review states; implement UI and run
   story/a11y/browser evidence.
6. Re-run dependency/combined validation and record actual remote Draft PR evidence. Do not mark this plan
   completed while the required UI validation is blocked or Not run.

## Required Tests

- customer Payment status/confirmation returns `orderReference` and no internal `orderId` for 200, 202,
  exact callback replay and reconciliation-generated response bodies
- other customer cannot read a Payment or infer its `orderReference`
- callback Provider `orderId`/amount/paymentKey tampering still rejects before Provider call
- unknown response loss uses status/read reconciliation without a second confirmation and eventually produces
  the same public response shape
- bounded lookup exhaustion persists `MANUAL_REVIEW`, opens one operator case and stops automatic backend calls
- frontend manual-review state stops timers/online wake automatic polling and confirm, does not show checkout,
  uses only safe order tracking/help behavior, and exposes no raw Provider/internal identifiers
- existing order list/detail, cancellation, reorder and point transitions continue to use public order reference
  and server `allowedActions`

## Validation Commands

```bash
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
git diff --check
git diff --cached --check

./gradlew test --tests '*OneTimeCheckoutIntegrationTest' --tests '*PaymentConfirmationIntegrationTest' \
  --tests '*RuntimeOpenApiParityTest' --tests '*AuthenticationPathRegistryTest'
./gradlew spotlessCheck
npm run typecheck
npm run test:unit
npm run build-storybook
npm run test:storybook:docs
```

The documentation-first slice runs only the first three checks. Backend/API, frontend, Storybook, browser,
Toss sandbox and full Gradle validation are **Not run** until their corresponding implementation scope exists.

## Observability

- Keep existing `beanflow.payment.confirm.*` and `beanflow.payment.reconciliation.*` metrics. Do not add
  `paymentId`, `orderId`, `orderReference`, provider order ID, payment key, amount or raw Provider code as metric
  tags or structured log fields.
- The existing manual-review outcome and operator reprocessing case remain the durable operational signal.
  Customer rendering must not expose the case reason or Provider diagnostic.
- No latency, error-rate, conversion or polling-reduction performance claim is made before a comparable
  measurement exists.

## Documentation Updates

- [ADR-080](../../adr/ADR-080-toss-v2-one-time-payment-window.md) records public payment response and
  `MANUAL_REVIEW` terminal behavior.
- This ExecPlan records Stage 05 backend/API dependency evidence and the completed Stage 06 contract, backend and
  customer UI slices. Program orchestration, core journey and release-gate documents remain integrator-owned and
  are not modified by this worker.

## Progress

- 2026-08-18: Stage 05 dependency re-audited. Draft PR #93 head
  `3211fffd5d4b3b4f527ac0e296313b39ce5d5b73` completed remote preflight, frontend, backend-build, build and
  test shards 0~5 green. The consumed input is its coupon wallet backend/API contract, not the Storybook-blocked
  wallet selector UI.
- 2026-08-18: Documentation-first contract created. No production source, OpenAPI, generated schema, frontend,
  migration, test fixture or Storybook source changed in this slice.
- 2026-08-18: Storybook MCP remains unavailable. UI implementation and corresponding story/a11y/browser proof
  are **Not run**; this does not authorize a fake/manual-review fallback surface.
- 2026-08-18: Backend/API slice changed `PaymentConfirmation` in the target contract and generated client from
  internal `orderId` to `orderReference`. Runtime OpenAPI continues to delegate these paths to the target contract.
  Ordering now re-renders status and replay responses from the current Order-owned reference, and supplies public
  response bodies for reconciliation persistence; no migration or frontend UI source changed. The host-isolated RED
  contract test executed and failed as expected on the predecessor `orderId` schema. The corrected Stage 6-local
  focused Gradle suite then passed: OpenAPI 1, checkout 7, public-tracking 1, order-controller 12,
  payment-confirmation 19 and auth-registry 3 tests, all with zero failures/errors.
- 2026-08-18: `scripts/verify-docs.sh` passed target/runtime local-contract validation and documentation checks.
  After lockfile-consistent dependencies were restored, the frontend typecheck regenerated the authoritative schema
  successfully, then `tsc --noEmit` failed only at `PaymentResultPages.tsx` lines 91, 110 and 168 because that
  Storybook-governed UI still reads removed `payment.orderId` instead of the public `orderReference`. The required
  UI correction and its story/a11y/browser proof are **Blocked** while Storybook MCP is unavailable; no UI source,
  dependency or compatibility-field change is authorized in this backend/API slice.
- 2026-08-18: Storybook MCP was restored for the Stage 6 worktree. Customer payment result pages now render and
  track only `orderReference` at `/app/orders/{orderReference}`; payment-result fixtures, focused callback tests and
  success/fail stories no longer supply a compatibility `orderId`. `MANUAL_REVIEW` is a distinct terminal client phase that
  suppresses automatic confirmation/polling (including online wake) and shows only the documented tracking/help
  actions. Typecheck, 142 frontend unit tests, design adherence, static Storybook build, product build and Sites
  tests passed; the elevated focused browser Storybook run passed 8 affected stories with a11y enabled. The MCP
  `run-story-tests` runner is **Blocked** after Vitest initialization failed and its state remained "Tests are already
  running" without a runner process. The controller compared the static Docs smoke against the clean Stage 05
  baseline: the same `ENOENT` requests are non-fatal, while Stage 06 had moved a lazy-loaded dependency-error
  iframe below the Docs viewport. The smoke harness now activates every isolated iframe before reading it; a fresh
  static build then passed 29 Docs entries, 14 stateful Docs and 49 state surfaces. No fake handler or fallback was
  added.

## Surprises & Discoveries

- Existing backend reconciliation correctly persists `MANUAL_REVIEW` and opens an operator case after its
  bounded lookup budget. The gap is the customer presentation loop, which currently treats that terminal state
  as indefinitely pending.
- Existing public Order reference storage makes a migration unnecessary, but response replay and reconciliation
  serialization require the same public-boundary contract as direct status rendering.

## Decision Log

- 2026-08-18: retain owner-scoped `paymentId` as Payment status/confirmation locator; use `orderReference` only
  for human display/tracking; never reinterpret Toss `providerOrderId` as a BeanFlow order number. Source:
  ADR-080 amendment and ADR-096.
- 2026-08-18: `MANUAL_REVIEW` is terminal for automatic client confirmation/polling and exposes only safe
  tracking/help behavior. Source: bounded reconciliation failure semantics and ADR-080 amendment.
- 2026-08-18: do not copy public reference into Payment or create a migration; consume Ordering-owned reference
  projection in the later backend slice.

## Outcomes & Retrospective

The Stage 06 contract, backend/API and customer payment-result UI slices are locally implemented and validated.
This ExecPlan remains ACTIVE until the stacked Draft PR receives remote evidence and the Goal's release-level gates
run; full Gradle and a healthy Storybook MCP `run-story-tests` execution remain **Not run/Blocked** as recorded
above.

## Revision Notes

- 2026-08-18: initial Stage 06 public payment tracking and terminal recovery plan.
