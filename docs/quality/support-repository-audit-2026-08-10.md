# Customer Support planning diff audit and repair report

> Audit date: 2026-08-10 Asia/Seoul
> Baseline commit: `e0025cdcf0a78697ea6bd7ff4ae6690d9dd1e0b8`
> Working branch: `refactor/check-repo`
> Scope: documentation and planning contracts only; no application/frontend code or Flyway migration

## 1. Audit basis and source limits

`HEAD`, local `main` and local `origin/main` all resolve to the baseline commit above. The pre-existing uncommitted
Customer Support planning diff was treated as an unaudited draft, not a source of truth.

The required repository policy, current code, OpenAPI, ADR-072, active Analytics plan and current uncommitted diff were
read. The named planning-pack source files `01_MASTER_SPEC.md`, `03_EXECUTION_GRAPH_AND_RELEASE_STRATEGY.md`,
`05_API_CONTRACT_AND_UI_SCOPE.md` and `08_COMPLETENESS_MATRIX.md` are not present in this worktree, local main or the
searched temporary roots. This repair therefore does not claim to have re-read them. It uses the user's correction list,
the current 236-row traceability draft and the 51-operation draft skeleton to preserve requirements, and explicitly
records the four operations supplied by the user.

Source priority used by this repair is:

1. current main code and existing Accepted repository policy/ADR;
2. Customer Support product decisions explicitly confirmed by the user;
3. S00 planning documents only after their status and evidence were independently checked.

## 2. P0/P1 findings and repairs

| Priority | Finding | Repair | Resulting status |
|---|---|---|---|
| P0 | An active/ready migration-writing plan was treated as the current lease holder, and Analytics was used as a queue-only S10 dependency. | Restored ADR-072 direct-output dependency semantics; removed Analytics from S10 `Depends-On`; rejected ADR-091's fake graph; separated scheduling from dependency. | No current holder can be proven from local evidence. |
| P0 | Existing `frontend/` `/support` integration was recorded as Accepted without credential/CORS/CSRF/trust/deployment choice. | Changed ADR-090 to Proposed; SP-14 now accepts only that Support Console is final scope. | Three boundary alternatives remain open. |
| P0 | ADR-070 and the verifier encoded a speculative common Support cursor tuple. | Reverted the S00 ADR-070 amendment and matching verifier enforcement. | Each implemented endpoint must define its own typed sort/filter contract. |
| P0 | Canonical target OpenAPI contained 51 generic operations, 39 generic request bodies and one generic success resource that could not represent required values. | Reverted all S00 Support/Delivery/LegalHold target OpenAPI additions; created a 55-row DRAFT inventory with endpoint-specific contract inputs. | Canonical target remains 34 paths/37 operations; Support semantic contract completeness is 0/55. |
| P1 | Four planning operations were missing. | Added subject-link deletion, verification-session read, action-request reassignment and post-acceptance approval to the DRAFT inventory. | Inventory coverage is 55 operations. |
| P1 | S10-S140 files were repeated placeholder implementation shells. | Kept one detailed S10 plan; consolidated S20-S140 into a non-ready orchestration roadmap. | One detailed Support implementation plan remains. |
| P1 | Short policy summaries were labelled runbooks and tests without runnable prerequisites, commands or implemented surfaces. | Consolidated them as planned operational procedures and a planned test strategy. | No unimplemented endpoint/table/metric is presented as runnable evidence. |
| P1 | `236/236` traceability was described as contract/implementation completeness. | Introduced evidence statuses and reclassified rows. | 236 IDs means traceability coverage only; implemented Support contracts remain zero. |
| P1 | S00 was marked completed before correction and independent validation. | Returned S00 to active orchestration during repair. | Completion is allowed only after every required validation is recorded. |

## 3. Decision disposition

### Accepted and preserved

- lightweight hybrid SupportCase and Support/Operations boundary;
- masked-by-default reveal with reason, fields, expiry and Audit-before-reveal;
- UNVERIFIED/BASIC/ENHANCED verification and a separate BREAK_GLASS path;
- ALLOWED/APPROVAL_REQUIRED/DENIED risk evaluation;
- lifecycle-aware direct change and post-acceptance resolution;
- immutable versioned compensation and distinct sources;
- R0-R4 field classification, R3 sequential Support Manager/Operations approval and actor separation;
- exceptional compensation Operations investigation followed by agent execution;
- canonical DeliveryFulfillment, Provider ACL/reconciliation and no silent cross-provider failover;
- purpose-based initial retention classes and the product non-goals;
- Support Console as final product scope.

ADR-081, ADR-082 and ADR-084 through ADR-089 remain Accepted after review. ADR-081 keeps the Context and bounded query
boundary but no longer fixes PostgreSQL projection or a common Support cursor tuple. ADR-088 requires provider-specific
raw-body authentication but does not preselect HMAC headers or algorithm.

### Proposed

- ADR-083 encryption/blind-index design until KMS/provider, key hierarchy, rotation/backfill and outage recovery are
  concretely accepted;
- ADR-090 frontend boundary: separate operator app, existing application route or server-rendered UI, together with the
  browser credential, token storage, CORS, CSRF, origin, trust and deployment model;
- unimplemented Support entries in the error catalog are draft candidates, not stable runtime codes.

### Rejected

- ADR-091's use of `Depends-On` to encode migration queue priority;
- generic `SupportCommandRequest`/`SupportOperationResource` canonical schemas;
- S00-wide fixed Support cursor tuple;
- pre-authoring S20-S140 placeholder implementation plans.

### Draft or blocked

- 55 API operations are `DRAFT_API_SURFACE`, not canonical contracts;
- Support/Delivery/customer contact/KMS/Provider owner-model gaps remain `BLOCKED_BY_MODEL_GAP` where applicable;
- planned operational and test procedures are not runtime/runbook/test evidence.

## 4. Migration lease evidence and scheduling

Local evidence shows four other worktrees, one detached worktree, the active Analytics ExecPlan and the detailed S10
candidate. The Analytics plan says the migration lane was released so that the plan may start. It does not record a
current execution branch/worktree/task/PR identity plus an explicit lease acquisition. No such acquisition record was
found for S10 either.

The final `gh pr list --state open --json number,title,headRefName,baseRefName,url` query returned `[]`. Together with
local worktree/plan inspection this finds no current holder evidence, but absence of evidence is not itself a lease or a
durable queue claim.

Analytics and S10 do not consume one another's schema/output. Their ordering is a scheduling decision:

- schedule Analytics first, acquire and record one ADR-072 lease, merge/release it, then rebase and schedule S10; or
- schedule S10 first under the same process, then rebase and schedule Analytics.

They must not start concurrently. The selected executor must re-check latest main, current PR/task/worktree evidence and
the latest Flyway number, then record branch/worktree/task/PR identity, acquisition time, base commit and last migration.
Active/ready metadata, plan priority and `Migration lane released` are not substitutes.

## 5. Canonical OpenAPI versus DRAFT inventory

| Artifact | Role | Current Support state |
|---|---|---|
| `openapi/beanflow-v1.yaml` | canonical target contract | 34 paths/37 operations; no Support/Delivery/LegalHold planning operation |
| `openapi/beanflow-v1-runtime.yaml` | implemented runtime contract | 34 paths/37 operations; unchanged |
| `docs/api/support-api-surface.md` | planning inventory | 55 operations, all DRAFT, 0 semantically complete implementation contracts |

Each owning Stage must define endpoint-specific requests, resources/pages, errors, security and stable sort/filter tuple
before moving an operation into target OpenAPI. The Provider webhook must use operation-level `security: []`, authenticate
the exact raw bytes with the selected Provider contract and commit the unique inbox before returning 2xx.

## 6. File disposition

### Kept and audited

- Accepted ADR-081/082/084/085/086/087/088/089 and Proposed ADR-083;
- product policies, Context/aggregate/state/transaction/privacy architecture and security planning documents that encode
  the confirmed product decisions;
- 236-ID traceability, with evidence status corrected;
- target/runtime OpenAPI separation and existing application contracts.

### Changed or added

- ADR-090 Proposed, ADR-091 Rejected, ADR index and SP-14;
- DRAFT Support API inventory, API conventions, error candidates and capability counts;
- completed S00 repair plan, active program orchestration and detailed S10 plan;
- planned operational procedures, consolidated planned test strategy, documentation index and this audit report.

### Removed from the initial draft working set

- placeholder implementation plans `customer-support-s20` through `customer-support-s140`;
- pseudo-runbooks for Support Case, high-risk change, exceptional compensation, Delivery reconciliation, LegalHold,
  retention deletion, PII review and security incident response;
- split placeholder test documents for authorization, concurrency, retention and load testing;
- speculative S00 modifications to ADR-070, canonical target OpenAPI and `scripts/verify-docs.sh`.

No application source, frontend implementation or Flyway migration was added.

## 7. Remaining ExecPlans

- `analytics-refund-and-late-event-projection.md`: pre-existing active implementation candidate; not a proven lease holder.
- `customer-support-program-orchestration.md`: active, non-ready, non-migration roadmap and release-gate owner.
- `customer-support-s10-retention-audit-permission.md`: the sole detailed Support implementation plan; false-ready and no
  runtime endpoint.

`customer-support-s00-documentation-contracts.md` is completed because the planning repair and independent checks are
finished. Its Outcomes preserve the full-suite failure rather than treating S00 completion as application correctness.

S20-S140 do not have implementation plan files. Their next plan is authored from latest main only after actual direct
predecessor output and open decisions are available.

## 8. Traceability meaning

The traceability table retains IDs 1-236. That count proves only that every imported identifier has a row. Status means:

- `DECISION_RECORDED`: a policy/architecture decision is recorded;
- `DRAFT_API_SURFACE`: an endpoint need exists but its canonical typed contract does not;
- `BLOCKED_BY_MODEL_GAP`: required owner model or accepted implementation decision is absent;
- `IMPLEMENTATION_PLANNED`: executable work is planned, not implemented;
- `IMPLEMENTED`: code/schema exists but the row does not claim all verification;
- `VERIFIED`: cited evidence was actually checked.

Therefore `236 IDs retained` and `0 semantically complete Support implementation contracts` are both true and describe
different properties.

## 9. Validation record

| Command/check | Result |
|---|---|
| `git diff --check` | exit 0 |
| `./scripts/verify-docs.sh` | exit 0; target 34 paths/37 operations, runtime 34 paths/37 operations, 91 schemas; 33 policies, 91 ADRs, 223 Markdown files, 35 ExecPlans |
| first sandboxed `./gradlew spotlessCheck test` | exit 1 before Gradle: user-cache `.lck` access was not permitted |
| approved `./gradlew spotlessCheck test` | exit 1 after 8m 1s: 637 tests completed, 1 failed, 1 skipped; `PaymentMethodControllerIntegrationTest.kt:212` expected 400 but received 200 |
| `./gradlew test --tests '*PaymentMethodControllerIntegrationTest'` | exit 0, `BUILD SUCCESSFUL in 14s`; isolated rerun passed, so the observed full-suite instability remains unresolved rather than reclassified as success |
| `./gradlew test --tests '*ModularityTests'` | exit 0, `BUILD SUCCESSFUL in 4s`; 7 tasks, 1 executed and 6 up-to-date |
| canonical target generic Support operation/schema scan | target/runtime planned Support operations 0; `SupportCommandRequest`/`SupportOperationResource` schemas 0 |
| DRAFT inventory count/missing/duplicate scan | 55 rows, 55 unique, missing required four 0, duplicates 0, semantic completeness declared 0/55 |
| traceability scan | numeric rows 236, unique 236, missing/duplicate 0; status counts over 236+12 REPO rows: Blocked 145, Decision 14, Draft API 15, Implementation planned 65, Verified 9 |
| dependency/readiness/private-context/scope scans | active Support implementation plans 1; Analytics in Support `Depends-On` 0; prohibited private-context hits 0; application/frontend/migration changed paths 0 |
| open PR evidence | `gh pr list ...` exit 0, `[]` |

No performance, production, operational, legal or compliance readiness is inferred from documentation/build checks.

## 10. S10 readiness conclusion

S10 describes the current classes/files, preserves legacy five-year expiry, proposes a concrete immutable policy model
and backfill order, names exact permission changes and transaction/failure boundaries, and names repository-backed
Testcontainers/concurrency/Modulith validation. Its direct S00 dependency is completed, but it remains
`Implementation-Ready=false` because the required base full-suite regression was observed failing and has not been made
stable or otherwise dispositioned by an Accepted repository decision.

Do not recommend or start S10. Before reconsidering readiness, resolve or formally disposition the cursor-test
instability and rerun the full required validation. Execution would additionally require a separate scheduling choice,
latest-main/remote recheck and explicit ADR-072 migration lease acquisition record. No S10 implementation, migration
number, branch, commit, push or PR is part of S00.
