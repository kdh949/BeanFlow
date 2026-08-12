# 제조 이후 주문 사실을 보존하는 사후 해결을 조정한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s70-order-cancellation-pickup-reschedule.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

PREPARING, READY, COMPLETED 주문의 제조·준비·완료 사실은 그대로 유지하면서 Support가 승인된 exact action
revision에 따라 현금 환불, 사용 포인트·쿠폰 복구와 확정 정산의 불변 조정을 조정한다. 각 owner 결과는 별도
step으로 남고, 일부만 성공하거나 PG 결과가 불명확하면 `RESOLVED`로 위장하지 않는다. 책임이 미확정이어도
고객 가치 복구는 진행할 수 있지만 비용 귀속과 Settlement 조정은 자동 Store/Platform fallback 없이
`MANUAL_REVIEW`로 남긴다.

## Current State

- S60은 `POST_ACCEPTANCE_RESOLUTION` SupportActionRequest, immutable revision digest, Support Manager 승인,
  actor separation, one-time terminal execution과 reassignment를 제공한다.
- S70은 latest Order가 PREPARING/READY/COMPLETED이면 직접 취소·slot 변경 없이
  `RESOLUTION_REQUIRED` handoff를 기록한다. V45와 833-test regression은 완료됐고 writer lease는 release됐다.
- Payment는 누적 환불 상한, Provider REQUEST/LOOKUP, `UNKNOWN/RECONCILING/MANUAL_REVIEW`를 가진 Refund를
  소유하지만 Support Resolution 전용 typed command와 projection은 없다.
- Loyalty/Promotion은 주문 종료 및 partial Refund 복구 경로를 갖지만 Order fact를 종료 상태로 바꾸지 않는
  resolution source contract가 없다.
- SettlementAdjustment는 confirmed Item에 append-only로 생성되고 source replay를 지원하지만 order/resolution
  bound command와 responsibility guard가 없다.
- Notification은 durable delivery/retry/manual-review를 갖지만 resolution outcome template와 owner port가 없다.
- `docs/api/support-api-surface.md`의 S80 네 operation은 DRAFT inventory다. 별도 S80 approval operation은
  S60의 Accepted approval source와 중복되므로 canonical runtime으로 승격하지 않는다.
- 현재 마지막 Flyway migration은 V45다. open PR 중 schema writer인 Productization PR #57은 commit
  `8aa3704`에서 `Implementation-Ready=false`와 S100 뒤 재번호화를 기록했고, Analytics active plan은 아직
  branch/PR/number를 획득하지 않았다. 사용자의 Support 우선 결정에 따라 이 plan이 V46 sole writer lease를 갖는다.

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

V46 forward migration will add:

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

- `./gradlew test --tests '*PostAcceptanceResolution*' --tests '*SupportResolution*'`
- `./gradlew test --tests '*Payment*Refund*' --tests '*Loyalty*Restoration*' --tests '*Coupon*Restoration*'`
- `./gradlew test --tests '*SettlementAdjustment*' --tests '*Notification*'`
- `./gradlew test --tests '*Support*Postgres*' --tests '*Migration*'`
- `./gradlew test --tests '*ModularityTests' --tests '*Architecture*'`
- `./gradlew clean build`
- `bash scripts/verify-docs.sh`
- `git diff --check`

모든 명령은 exact test count/result와 함께 기록한다. 실행하지 않은 검증은 `Not run`, 성능은 동일 fixture의
측정 전까지 `Not measured`로 둔다.

## Observability

- `beanflow.support.resolution.command.count{operation,outcome}`
- `beanflow.support.resolution.step.count{step_type,state}`
- `beanflow.support.resolution.claim.lag{step_type}`
- Payment 기존 closed refund mode/outcome metric과 Notification delivery metric을 재사용한다.

metric tag는 closed enum만 사용한다. case/order/customer/payment/refund/adjustment ID, amount, reason, evidence,
Provider reference는 tag/log에 넣지 않는다.

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
- [ ] contract/domain RED tests and state machine implementation
- [ ] V46 persistence and PostgreSQL constraint/concurrency evidence
- [ ] Payment/Loyalty/Promotion/Settlement owner commands and result projections
- [ ] orchestration/retry/reconciliation/notification/API/OpenAPI implementation
- [ ] focused/full validation, review, completion move, successor readiness and PR

## Surprises & Discoveries

- Draft S80 inventory included a separate approval endpoint, but S60 already owns exact revision approval and actor
  separation. Promoting both would create conflicting authorization truth.
- Existing Payment Refund has the required Provider uncertainty state machine, but its current non-partial callbacks are
  coupled to pre-acceptance OrderCompensation and cannot be called as a Support shortcut.
- Existing Loyalty/Promotion restoration contracts require termination semantics. S80 needs owner-local source contracts
  that preserve the current Order lifecycle instead of fabricating a termination trigger.

## Decision Log

| Date | Decision | Rationale | Record |
|---|---|---|---|
| 2026-08-12 | S60 exact revision is the sole approval source; S80 has no duplicate approval API | preserves stale/one-time/separation invariants | SP-20, ADR-085 |
| 2026-08-12 | `UNDETERMINED` permits customer-value steps but blocks attribution/Settlement | makes customer remediation independent without defaulting cost owner | SP-20, ADR-085 |
| 2026-08-12 | V46 lease assigned to S80 | S70 released V45, Productization is frozen and user prioritized Support | this plan, orchestration |

## Outcomes & Retrospective

Not completed. Runtime behavior and validation evidence must not be inferred from this plan.

## Revision Notes

- 2026-08-12: authored from S70 actual outcome, accepted S60-reuse/attribution-only-block model and acquired V46 lease.
