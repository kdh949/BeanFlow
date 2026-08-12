# 제조 이후 주문 사실을 보존하는 사후 해결을 조정한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s70-order-cancellation-pickup-reschedule.md`
> **Completed-At:** `2026-08-12`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

PREPARING, READY, COMPLETED 주문의 제조·준비·완료 사실은 그대로 유지하면서 Support가 승인된 exact action
revision에 따라 현금 환불, 사용 포인트·쿠폰 복구와 확정 정산의 불변 조정을 조정한다. 각 owner 결과는 별도
step으로 남고, 일부만 성공하거나 PG 결과가 불명확하면 `RESOLVED`로 위장하지 않는다. 책임이 미확정이어도
고객 가치 복구는 진행할 수 있지만 비용 귀속과 Settlement 조정은 자동 Store/Platform fallback 없이
`MANUAL_REVIEW`로 남긴다.

## Current State

- S60 `POST_ACCEPTANCE_RESOLUTION` exact revision은 별도 승인 source 없이 assigned executor가 한 번 소비한다.
- `PostAcceptanceResolutionCase`와 closed step model은 PREPARING/READY/COMPLETED Order fact를 변경하지 않고
  `PLANNED | EXECUTING | PARTIALLY_RESOLVED | RECONCILING | RESOLVED | MANUAL_REVIEW` 결과를 보존한다.
- Payment는 resolution-bound Refund, 누적 성공 상한, unresolved 중복 차단과 Provider UNKNOWN/LOOKUP 재조정을
  제공한다. Loyalty/Promotion은 원혜택만 exact source로 복구하고 만료 혜택을 `SKIPPED_EXPIRED`로 기록한다.
- Settlement는 STORE/SHARED approved amount만 append-only adjustment로 기록한다. `UNDETERMINED`는 고객 회복을
  허용하면서 비용 귀속을 차단하며 어떤 Store/Platform fallback도 만들지 않는다.
- Notification delivery는 financial outcome과 독립된 durable intent/result다. Support API는 plan 생성·조회·실행과
  Payment safe reconciliation 네 operation을 strict/no-store 계약으로 공개한다.
- V46은 Support/owner binding, claim/command idempotency와 audit mapping을 생성하는 마지막 Flyway migration이며
  full validation 뒤 sole migration-writer lease가 release됐다.

## Definitions

- **PostAcceptanceResolutionCase:** Order fact를 변경하지 않고 승인 lineage, immutable plan과 owner step 결과를
  소유하는 Support Aggregate.
- **Resolution plan:** outcome, cash Refund amount, points/coupon restoration 선택, responsibility, evidence digest,
  owner target version을 exact canonical payload로 묶은 immutable revision snapshot.
- **Customer-value step:** Payment Refund, Loyalty points restoration, Promotion coupon restoration.
- **Cost-attribution step:** confirmed SettlementItem에 append-only adjustment를 만드는 step.
- **Resolution state:** `PLANNED | EXECUTING | PARTIALLY_RESOLVED | RECONCILING | RESOLVED | MANUAL_REVIEW`.
- **Step state:** `PENDING | PROCESSING | RETRY_SCHEDULED | SUCCEEDED | NOT_REQUIRED | UNKNOWN |
  RECONCILING | MANUAL_REVIEW | BLOCKED`.
- **Responsibility:** `CUSTOMER | STORE | PLATFORM | SHARED | UNDETERMINED`. `UNDETERMINED`는 cost owner가 아니다.
- **Resolution outcome:** `FULL_REFUND | PARTIAL_REFUND | NO_MONETARY_RESOLUTION |
  MANUAL_SETTLEMENT_REVIEW`. Point/coupon restoration은 goodwill이 아닌 원혜택 복구 step이다.

## Scope

### In Scope

- Support ResolutionCase/plan/step Aggregate, PostgreSQL persistence, exact request/revision binding와 idempotency
- create/get/execute/reconcile no-store API와 target/runtime OpenAPI parity
- PREPARING/READY/COMPLETED latest Order/version recheck without lifecycle mutation
- Payment Support Refund request/result projection and Provider retry/lookup integration
- Loyalty used-point restoration and Promotion used-coupon restoration under resolution source
- responsibility-aware immutable Settlement adjustment or explicit blocked/manual-review result
- durable customer notification independent of financial completion
- partial success, PG timeout, reconciliation, duplicate/replay, claim lease recovery and observability

### Non-goals

- Order historical state rewrite, PREPARING/READY/COMPLETED -> CANCELLED transition
- goodwill point/coupon issuance or compensation policy bands owned by S90
- free-form benefit terms, hidden Platform/Store cost fallback, external Provider replacement
- duplicate Resolution approval source or generic workflow/rules engine

## Business Rules and Invariants

- ResolutionCase is bound to one SupportCase, Order, S60 request, exact revision/payload digest/policy/verification and
  trigger Order state/version. One action request creates at most one ResolutionCase.
- Only PREPARING, READY, COMPLETED trigger facts are valid. Creation and every first owner execution recheck latest
  owner state/version; Order is never updated by Support.
- S60 approval is the only approval source. A case can be planned only from a `READY_FOR_EXECUTION`
  `POST_ACCEPTANCE_RESOLUTION` request; the assigned executor consumes it once when execution starts.
- requester, Support approver, Operations approver and executor separation remains enforced. Permission revocation and
  Case reassignment fail closed at execution/reconciliation boundaries.
- Customer-value steps may run for `UNDETERMINED`; Settlement step is `BLOCKED` and case becomes
  `PARTIALLY_RESOLVED` or `MANUAL_REVIEW` until explicit responsibility revision in a future approved action.
- No automatic Store or Platform cost owner is inferred. STORE/SHARED adjustment requires exact signed amount in plan;
  PLATFORM/CUSTOMER produces `NOT_REQUIRED` Settlement step unless manual review was requested.
- Payment cumulative succeeded Refund may not exceed approved cash. Existing unresolved Refund prevents a new one.
- An owner step has one immutable source and payload hash. Same source/same payload replays; same source/different payload
  fails. A succeeded step is never executed again or rolled back after another step fails.
- `PARTIALLY_RESOLVED`, `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW` are not success. `RESOLVED` requires every required
  customer-value and attribution step to be `SUCCEEDED|NOT_REQUIRED`; notification success is independent.
- Audit commit precedes visible state transition response. Audit payload/log/metric contains no reason text, evidence,
  customer profile, payment reference, amount-derived high-cardinality tag or raw Provider response.

## Architecture and Transaction Boundaries

- Controller validates closed DTOs and calls `PostAcceptanceResolutionApplicationService`; no Controller accesses a
  Repository or owner table.
- plan transaction locks SupportActionRequest -> SupportCase -> ResolutionCase command key, validates exact revision,
  latest Ordering snapshot and saves immutable case/steps plus Audit atomically. It performs no external call.
- execution-start transaction locks ResolutionCase and S60 request, rechecks permission/assignment/revision/Order, marks
  S60 terminal consumption and claims the next step. Each owner command executes in the owner Context's local transaction.
- Payment intent transaction creates Refund; Provider call occurs without DB transaction; Payment result transaction
  records `SUCCEEDED|FAILED|UNKNOWN|RECONCILING` and cumulative amount. Support only projects the typed owner result.
- Loyalty, Promotion and Settlement each commit independently with source uniqueness. A later failure never rolls back a
  prior owner success; Support records each outcome in a short step-result transaction.
- notification intent is requested after the financial case state transaction through Notification's local durable
  transaction. Delivery retry/manual review does not turn a financially resolved case back into unresolved.
- worker flow is claim transaction -> owner/provider call outside Support transaction -> result transaction. Expired
  claims become retry/reconciliation/manual review, never assumed failure or success.

## Alternatives Considered

- **S80 independent approval:** rejected because it creates a second source of truth beside S60 exact revision and permits
  disagreement about stale payload, reviewer separation and one-time use.
- **Block all work for UNDETERMINED:** rejected because customer remediation need not wait for internal cost attribution;
  the dangerous action is automatic cost allocation, which remains blocked.
- **Default cost to Platform:** rejected by ADR-085 and explicit no-fallback policy.
- **Reuse customer/store HTTP cancellation or partial-refund Controller:** rejected because Support must not impersonate
  another actor or bypass exact permission/approval/Audit lineage.
- **One cross-context transaction:** rejected because Provider calls and partial owner success need durable independent
  outcomes; local owner APIs and step result transactions make recovery explicit.
- **Generic saga/rule engine:** rejected because the step vocabulary is closed and current scope does not justify a new
  runtime dependency or abstraction.

## Failure Semantics

- stale request/revision/payload/policy/verification/Order version returns stable conflict and creates no owner intent.
- a Payment timeout is `UNKNOWN`, then lookup claim is `RECONCILING`; exhaustion becomes `MANUAL_REVIEW`.
- owner deterministic validation failure is `MANUAL_REVIEW`; transient persistence failure schedules bounded retry.
- Support result-write failure after owner success is recovered by replaying the same owner source and projecting its
  existing result. It must not issue a second benefit/adjustment/refund.
- notification failure stays `RETRY_SCHEDULED|MANUAL_REVIEW` and is visible without changing financial success.
- Audit failure rolls back the Support state/step transaction. No response reports the uncommitted transition.
- unknown responsibility blocks only attribution; no amount or cost owner is synthesized.

## Data and Migration

V46 forward migration adds:

- `support_post_acceptance_resolution`: immutable request/revision/order/trigger/plan/responsibility binding, state,
  command idempotency and version.
- `support_post_acceptance_resolution_step`: closed type/state, immutable source/payload hash, owner result reference,
  claim lease/attempt/next attempt/failure code and version.
- S60 terminal ResolutionCase binding and constraints ensuring direct execution and resolution consumption are exclusive.
- Payment resolution Refund source fields/index/closed reason where existing Refund schema needs extension.
- Loyalty/Promotion resolution restoration source/result rows, immutable exact source uniqueness and payload hash.
- Notification resolution template constraint and any owner result lookup index required by bounded workers.

No historical ResolutionCase is inferred or backfilled. Existing confirmed SettlementAdjustment remains append-only and is
not overwritten. V46 is the sole writer number; another schema writer appearance pauses migration changes.

## API and Event Contracts

- `POST /api/v1/support/orders/{orderId}/post-acceptance-resolutions`: create exact plan bound to S60 request/revision,
  expected Order state/version, outcome, restoration flags, responsibility/evidence digest and Idempotency-Key.
- `GET /api/v1/support/post-acceptance-resolutions/{resolutionId}`: closed case/step result, no reason/evidence/raw Provider
  response; `Cache-Control: no-store`.
- `POST /api/v1/support/post-acceptance-resolutions/{resolutionId}/executions`: exact case/request/order versions;
  idempotently starts or advances due steps and returns partial/unknown state.
- `POST /api/v1/support/post-acceptance-resolutions/{resolutionId}/reconciliations`: exact case version and closed step
  selection; schedules safe lookup/replay only for UNKNOWN/RECONCILING/MANUAL_REVIEW eligible steps.
- Draft `/approvals` is removed from inventory rather than added to runtime; clients use S60 manager/Operations decisions.
- Public owner interfaces use typed commands/results only; no JPA Entity or arbitrary JSON crosses modules.

## Milestones

1. Accept S60-reuse/attribution-only-block policy, author this plan, acquire V46 lease and define contract-first domain/API.
2. TDD ResolutionCase/step state matrix, exact lineage, responsibility blocking and idempotent claim/result transitions.
3. Add PostgreSQL V46 and Testcontainers constraints/repository concurrency tests.
4. Implement Payment Refund owner intent/result/projection plus timeout/reconciliation integration.
5. Implement Loyalty/Promotion original-benefit restoration and Settlement immutable adjustment owner ports.
6. Implement Support orchestration workers, notification integration, create/get/execute/reconcile APIs and OpenAPI parity.
7. Run security/PII/Audit/failure/concurrency/full regression, review diff, complete plan/handoff and create stacked PR.

## Required Tests

- PREPARING/READY/COMPLETED matrix and proof that Order state/version never rolls back
- exact S60 request/revision/payload/policy/verification/target binding, approver/executor separation, permission revoke
- full/partial Refund and cumulative over-refund; PG success/failure/timeout/lookup/reconciliation
- partial owner success followed by failure; replay after Support result-write failure
- points/coupon restoration exact source replay/conflict and no S90 goodwill issuance
- confirmed Settlement adjustment idempotency/immutability and STORE/SHARED/PLATFORM/CUSTOMER mapping
- `UNDETERMINED` customer-value progress with attribution `BLOCKED`, no automatic Store/Platform fallback
- same idempotency replay, different payload conflict, parallel plan/create/execute/claim and expired claim recovery
- notification accepted/retry/manual review independent of financial resolution
- Audit failure rollback, no-store API, strict unknown-field rejection, permission/assignment visibility, PII allowlist
- PostgreSQL Testcontainers migration/constraint/repository tests, Modulith and ArchUnit boundaries, target/runtime parity

## Validation Commands

- PASS — `./gradlew test --tests '*PostAcceptanceResolutionIntegrationTest' --rerun-tasks`: 11 tests,
  0 failures, `BUILD SUCCESSFUL in 30s`.
- PASS — `./gradlew test --tests '*PostAcceptanceResolutionPaymentIntegrationTest' --tests
  '*PostAcceptanceResolutionBenefitOwnerIntegrationTest' --tests '*SettlementRefundAdjustmentIntegrationTest'
  --rerun-tasks`: Payment 4, Benefit 2, Settlement 8 tests, 0 failures, `BUILD SUCCESSFUL in 35s`.
- PASS — `./gradlew test --tests '*PostAcceptanceResolution*' --tests '*SupportActionRequest*' --tests
  '*SettlementRefundAdjustmentIntegrationTest' --tests '*RuntimeOpenApiParityTest' --rerun-tasks`: 62 tests,
  0 failures, `BUILD SUCCESSFUL in 1m 17s`.
- PASS — `./gradlew test --tests '*ModularityTests' --tests '*ArchitectureTest' --tests
  '*RuntimeOpenApiParityTest' --rerun-tasks`: 0 failures, `BUILD SUCCESSFUL in 19s`.
- PASS — `./gradlew test --tests '*AuditRetentionPolicyMigrationTest' --rerun-tasks`: 4 tests, 0 failures,
  `BUILD SUCCESSFUL in 23s`.
- PASS — final `./gradlew clean build`: 865 tests, 864 passed, 1 skipped, 0 failures/errors;
  Spotless/assemble/check included, `BUILD SUCCESSFUL in 9m 19s`.
- PASS — `bash scripts/verify-docs.sh`: target/runtime 70 paths/74 operations, 200 schemas; 33 business policies,
  92 ADRs, 235 Markdown files and 42 ExecPlans validated.
- PASS — `git diff --check`: no whitespace errors before completion commit.
- NOT MEASURED — no performance improvement claim or benchmark is part of S80.

Two full-build preflight failures were corrected before the final pass: Spotless identified 14 S80 Kotlin files and the
fresh-migration test still expected V45 instead of the leased V46. Owner fixture binding failures found by the 62-test
suite were also corrected and the entire suite was rerun. No failed result is treated as completion evidence.

## Observability

Support case/step/command rows, committed PII-free Operations Audit, no-store case projection과 owner result reference가
durable operational evidence다. Payment의 closed refund mode/outcome metric과 Notification delivery metric을 재사용한다.
새 Support 전용 meter는 S80에서 추가하지 않았으므로 별도 counter/lag metric 제공을 주장하지 않는다. 기존 metric
tag와 Audit에는 case/order/customer/payment/refund/adjustment ID, amount, reason, evidence나 Provider response를 넣지
않는다.

## Documentation Updates

- `docs/product/support-post-acceptance-resolution-policy.md`, `docs/product/business-policy-decisions.md`
- `docs/adr/ADR-085-lifecycle-aware-support-order-resolution.md`
- `docs/architecture/support-aggregate-invariants.md`, `docs/architecture/support-transaction-boundaries.md`
- `docs/architecture/support-requirement-traceability.md`, `docs/security/support-role-permission-matrix.md`
- `docs/api/support-api-surface.md`, `docs/api/error-catalog.md`
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml`
- 이 ExecPlan과 program orchestration readiness/evidence

## Progress

- [x] mandatory policies/ADRs/S00/S70/current owner API/schema inspection
- [x] S70 stacked head에서 `feature/support-post-acceptance-resolution` branch 생성
- [x] S60 approval reuse and attribution-only blocking policy selected and documented
- [x] V46 sole migration-writer lease acquired under Support-priority scheduling
- [x] contract/domain RED tests and state machine implementation
- [x] V46 persistence and PostgreSQL constraint/concurrency evidence
- [x] Payment/Loyalty/Promotion/Settlement owner commands and result projections
- [x] orchestration/retry/reconciliation/notification/API/OpenAPI implementation
- [x] focused/full validation, review, completion move, successor readiness and PR preparation

## Surprises & Discoveries

- Draft S80 inventory included a separate approval endpoint, but S60 already owns exact revision approval and actor
  separation. Promoting both would create conflicting authorization truth.
- Existing Payment Refund has the required Provider uncertainty state machine, but its current non-partial callbacks are
  coupled to pre-acceptance OrderCompensation and cannot be called as a Support shortcut.
- Existing Loyalty/Promotion restoration contracts require termination semantics. S80 needs owner-local source contracts
  that preserve the current Order lifecycle instead of fabricating a termination trigger.
- Owner fixture inserts exposed a command-actor column binding shift only when all owner suites ran together; exact
  PostgreSQL types caught the defect before completion.
- The first full build correctly failed the formatting gate, and the next run caught a stale V45 migration assertion.
  Applying Spotless and updating the explicit V46 expectation made the final 865-test build pass without suppressing gates.

## Decision Log

| Date | Decision | Rationale | Record |
|---|---|---|---|
| 2026-08-12 | S60 exact revision is the sole approval source; S80 has no duplicate approval API | preserves stale/one-time/separation invariants | SP-20, ADR-085 |
| 2026-08-12 | `UNDETERMINED` permits customer-value steps but blocks attribution/Settlement | makes customer remediation independent without defaulting cost owner | SP-20, ADR-085 |
| 2026-08-12 | V46 lease assigned to S80 | S70 released V45, Productization is frozen and user prioritized Support | this plan, orchestration |

## Outcomes & Retrospective

S80 is complete. V46, `PostAcceptanceResolutionCase`, four no-store Support operations and typed Payment/Loyalty/
Promotion/Settlement/Notification owner contracts preserve Order history while making partial and unknown outcomes
visible. S60 exact approval remains the sole authorization truth; executor/approver separation, latest versions,
idempotency keys, owner source hashes, claim leases and Audit commits are rechecked at their respective boundaries.

The recommended policy proved implementable without a cost-owner fallback: `UNDETERMINED` customer value recovery can
finish while Settlement remains blocked/manual, and expired original benefits end as `SKIPPED_EXPIRED` rather than being
silently replaced by S90 goodwill. Final evidence is 865 tests with 864 passed, 1 skipped and no failures/errors, plus
OpenAPI/document verification. V46 lease is released. S90 is ready for detailed authoring from S60 approval/investigation
and the now-completed S80 refund/restoration separation; its immutable policy, rolling bucket and cost-owner decisions
remain S90 work rather than hidden S80 defaults.

## Revision Notes

- 2026-08-12: authored from S70 actual outcome, accepted S60-reuse/attribution-only-block model and acquired V46 lease.
- 2026-08-12: implemented V46/domain/owner/orchestration/runtime, passed focused and 865-test full validation, released
  the migration lease, moved the plan to completed and marked S90 ready to author.
