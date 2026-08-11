# S20 SupportCase, Interaction과 SubjectLink foundation을 구현한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s10-retention-audit-permission.md`
> **Completed-At:** `2026-08-11`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. S10의 실제 V39 Audit/retention/permission 결과를 direct input으로
사용한다. S20의 migration-writer lease는 `feature/support-case-foundation`이 획득했고 V40을 선택했다.
PR #52 review remediation은 V40이 아직 main에 병합되지 않은 상태에서 같은 branch가 다시 lease를 획득해
수정한다. 다른 schema writer는 이 remediation lease가 release될 때까지 시작할 수 없다.

`Implementation-Ready=true`인 이유는 direct dependency인 S10의 actual outcome과 S20 initial Case policy가 모두
확정됐기 때문이다. `docs/product/support-case-policy.md`가 exact state transition, requester/category vocabulary,
S20 no-reopen scope와 S40 DataAccessGrant terminal-Case 의무를 Accepted initial policy로 기록한다.

## Purpose / Big Picture

독립 Support Context에 lightweight `SupportCase` 생명주기, assignment/state history, bounded interaction/note와
identifier-only subject link를 구현한다. 모든 privileged Support action이 연결할 수 있는 active Case 경계를
만들되 검색, verification, PII reveal, owner action execution과 UI는 구현하지 않는다.

완료 후 Case 상태와 assignment는 Aggregate와 DB constraint가 함께 보호하고, 대량 history는 Case JPA
collection이 아닌 append-only query model로 조회한다. S10의 dormant permissions와 Audit category/policy를
사용하되 role이나 grant만으로 object-level authorization을 대체하지 않는다.

## Current State

- Support module, V40 tables, Controller와 canonical target/runtime SupportCase operations가 구현·검증됐다.
  PR #52 review에서 결제정보 차단, object authorization, idempotency scope/retention, JSON null contract를
  깨는 결함이 확인돼 remediation validation이 진행 중이다.
- ADR-081은 독립 Support Context, owner public API와 ID reference boundary를 Accepted했다.
- `docs/product/support-case-policy.md`는 exact state/requester/category vocabulary와 S20 no-reopen scope를
  Accepted initial policy로 확정했고, S20은 이를 계약과 PostgreSQL constraint로 검증한다.
- V39는 `SUPPORT_CASE` close-based 3년 policy version과 `SUPPORT_CASE_READ/WRITE/ASSIGN` persistent permission을
  등록했지만 Case row, expiry 계산이나 deletion은 만들지 않았다.
- Operations Audit append는 category/class/immutable policy version을 caller transaction에서 snapshot하고
  실패 시 caller write를 rollback한다.
- execution preflight에서 latest `main`/`origin/main` `26880ecce2a865e84696fa62c909ffa50fb5bd17`의 last migration이
  V39임을 확인했고, S20 lease holder가 V40을 선택해 fresh PostgreSQL에서 적용했다. V1–V39는 수정하지 않았다.
  2026-08-11 remediation preflight에서도 latest `main`/`origin/main`은 같은 SHA이고 open migration PR은
  current S20 PR뿐이므로 V40 writer lease를 재획득했다.

## Scope

### In Scope

- new Support module API/internal boundary and Modulith declaration
- SupportCase state/version, optional external reference, requester/category/priority와 current assignee
- append-only assignment and state history
- bounded interaction and secret-filtered note records, Case collection과 분리된 cursor projection
- identifier-only customer/store/order/delivery subject links with relationship and unlink history
- create/list/get/assign/state-transition/interaction/note/link/unlink endpoint-specific DTO와 canonical target/runtime
  OpenAPI parity
- persistent `SUPPORT_CASE_READ/WRITE/ASSIGN` authorization, current-assignment/version checks and typed ID-link checks
- Support lifecycle Audit using S10 category/policy snapshot and PII-free summaries
- PostgreSQL constraints, idempotency, concurrency, migration and API contract tests

### Non-goals

- exact subject search or masked owner profile projection
- VerificationSession, DataAccessGrant, PII reveal or break-glass
- Support action evaluation/approval/execution, order/delivery/profile mutation or compensation
- LegalHold and SupportCase content deletion worker
- UI, Elasticsearch, external contact-center integration or generic attachment storage

## Readiness Gate

The initial Case state transition matrix, requester/category closed vocabulary and S20 no-reopen scope are Accepted in
`docs/product/support-case-policy.md`. The policy fixes the `OPEN | IN_PROGRESS | WAITING | RESOLVED | CLOSED` vocabulary,
Aggregate transition table, `OTHER` structured-detail requirement and terminal `CLOSED` behavior. It applies to Case
state/API/DB CHECK and history fixtures. Reopen stays out of scope until its authorization/Audit contract is separately
accepted; ADR-081 needs no amendment because the Context boundary remains unchanged.

## Business Rules and Invariants

1. Case transition legality is owned by `SupportCase`; direct state field updates are forbidden.
2. `CLOSED` rejects ordinary assignment, interaction, note, link and privileged action. Reopen is not exposed until its
   exact authorization/Audit contract is accepted.
3. assignment and state changes append history in the same transaction as Case version/state update.
4. subject links store type plus opaque owner ID only. Support does not write another Context's table or create JPA
   relationships to owner entities.
5. reason/note/interaction rejects password, OTP, token, full account and unnecessary address content before persistence.
   Embedded 13–19-digit PAN candidates are separator-normalized then Luhn-checked; CVC/CVV/security-code expressions
   are rejected. Rejected input is not logged or copied into Case rows, idempotency response, Audit or test snapshots.
6. large interaction, note and history sets are not `@OneToMany` collections on SupportCase. Cursor queries use bounded
   DTO projections and a stable endpoint-specific tuple.
7. every command assumes duplicate delivery. `(actorId, operation, Idempotency-Key)` scopes replay; same scoped key/same
   typed length-prefixed canonical payload returns the stored terminal response, while a different payload is a conflict.
   Different actors or operations may reuse the key text. No duplicate history/Audit row is written.
8. terminal idempotency response expires exactly 90 days after creation. Support cleanup uses bounded
   `(retention_expires_at, id)` keyset claims; a DB failure is propagated and retried, never counted as empty success.
9. persistent permission is necessary but not sufficient: current assignment where required and expected Case version are
   revalidated in the same transaction. S20 typed ID links deliberately do not imply owner existence or subject relation.
10. Audit failure rolls back privileged Case mutation. JWT role, cache, in-memory grant or no-op Audit is never a fallback.
11. SupportCase 3-year retention policy is snapshot/reference input only in S20; deletion automation remains S120 scope.
12. S20 does not create or revoke DataAccessGrant. S40 must make a terminal Case revoke active Grants and reject Grant
    activation/reveal for a terminal Case in its own detailed plan; no S20 transition may claim that non-existent work ran.

## Architecture and Transaction Boundaries

### Case command transaction

Controller calls a Support Application Service, never a Repository. The service validates authenticated actor and
request, locks the persistent Operations permission through its public API, loads/locks the SupportCase when present,
checks visibility/assignment/version, invokes Aggregate transition, appends history and PII-free Audit, then flushes in
one local database transaction. Any permission, policy, Audit or persistence failure rolls back the command and terminal
idempotency response.

Create uses a `(actorId, operation, Idempotency-Key)` advisory transaction lock before inserting Case and initial state
history. A shared typed, length-prefixed canonicalizer hashes every command field without delimiter ambiguity. Assignment,
transition and unlink use expected Case version; all Case commands use Case-scoped serialization. Transition checks
current assignment before the Aggregate, so an object authorization mismatch is 403 rather than state conflict. Do not
hold the transaction across an external Provider or owner Context network call.

### Query transaction

List/detail are bounded Support projections. They run in a local transaction, rather than a database read-only
transaction, because Operations persistent grant authorization locks its row. Object visibility predicates belong in
the query/application boundary so another queue or Case does not leak existence or data. Dependency failure is 503,
not an empty page or 404.

### Cross-context boundary

S20 stores typed owner IDs only; it neither validates an owner object nor calls an owner Context for a subject link.
Support never imports owner Repository/Entity or updates owner tables. A future owner validation dependency timeout
remains unavailable/unknown; it is not converted to a valid subject link.

## Data and Migration

The acquired execution-time lease added `V40__create_support_case_foundation.sql`, which:

1. creates SupportCase with closed state/requester/category/priority vocabulary, version and retention policy reference;
2. creates append-only assignment/state history with sequence/uniqueness and actor/reference constraints;
3. creates interaction/note tables separate from the Aggregate row, with bounded lengths and retention class/version;
4. creates subject link rows with Case/type/opaque ID/relationship uniqueness and unlink lifecycle;
5. creates command idempotency records with actor/operation/key scope, typed canonical payload hash, exact 90-day expiry
   and bounded-keyset cleanup index;
6. adds FK/CHECK/UNIQUE/index constraints for state, closed-time reconstitution order, expected query order, current
   assignment and duplicate prevention;
7. grants no Support permission by default and does not modify V1..V39.

The implementation must define whether Case content expiry is materialized at close or derived by a later S120 owner
port. It may reference the seeded immutable `SUPPORT_CASE` policy version, but must not delete content in S20.

### Migration-writer lease evidence

- **Acquired:** 2026-08-11 by `feature/support-case-foundation` from latest
  `main`/`origin/main` `26880ecce2a865e84696fa62c909ffa50fb5bd17`.
- **Inventory:** the repository worktree inventory has no executing Support or Analytics migration branch. The other
  ready migration plan is metadata only; its `Migration lane released` note records a prior release, not a current lease.
- **Selection:** current last Flyway migration is V39, so this sole lease holder selects `V40__create_support_case_foundation.sql`.
- **Remediation lease:** PR #52 review remediation 동안 `feature/support-case-foundation`이 sole writer다. V40이
  main에 병합되기 전이므로 forward V41을 만들지 않고 V40을 수정한다. remediation validation/PR update 뒤 lease를
  release한다. Existing V1–V39 migrations remain unchanged; no reservation manifest, checksum repair or migration
  renumbering is allowed.

## API and Event Contracts

S20 owns the first nine rows in `docs/api/support-api-surface.md`: create/list/get Case, assignment, state transition,
interaction, note, subject link and unlink. Each operation gets a distinct request/response schema, stable error mapping,
`additionalProperties: false`, operation security and target/runtime parity only after Controller implementation.

List/history cursors bind endpoint scope, filters, sort tuple and direction using the existing signed cursor convention.
The actual Case-list tuple `(openedAt DESC, caseId DESC)`, optional state/assignee filter scope and 15-minute expiry are
recorded in the ADR-070 S20 amendment. Generic Support command/resource schemas are forbidden. No event is required
merely for local history; introduce an event only for a named consumer and record duplicate-delivery behavior.

## Failure Semantics

- missing/revoked permission or object authorization mismatch (including non-assignee transition): 403 without revealing
  unrelated Case details;
- absent visible Case: 404; stale expected version or illegal transition: 409;
- invalid/secret-bearing input or malformed cursor/idempotency contract: 400;
- DB, owner validation, policy or Audit failure: 503 and no partial Case/history/idempotency success;
- duplicate same-payload request within `(actorId, operation, key)`: original terminal response; same scoped key with a
  different payload: 409; terminal replay response expires after 90 days;
- concurrent assignment/transition loser: explicit 409, not last-write-wins;
- no local/in-memory/fake/no-op fallback in production profiles.

## Alternatives Considered

- put Case lifecycle in Operations: rejects ADR-081 owner separation and mixes reconciliation with support workflow.
- model history as eager Case collections: makes load cost grow with every interaction and weakens bounded queries.
- copy owner entities/PII into Support: creates stale truth and expands privacy/retention scope.
- authorize by Support role alone: bypasses S10 persistent grants and object/assignment checks.
- implement all 55 Support endpoints in one module: couples unimplemented owner and security models to Case foundation.

## Required Tests

- pure domain state-transition table, closed-case rejection and version invariants
- Application Service permission/object/assignment matrix, non-assignee transition 403, idempotent replay/conflict,
  free-text canonical-boundary collision and Audit rollback
- PostgreSQL fresh migration, CHECK/FK/UNIQUE, append-only history, actor/operation/key scope, 90-day cleanup
  boundary/retry and no default grants
- concurrent assignment/transition/idempotency winner tests with deterministic terminal outcomes
- bounded cursor order/page/tampering/filter-scope tests and an architecture check that Case has no interaction/note
  collection; no performance measurement is claimed
- secret/PII negative corpus (embedded/multiple PAN candidates and CVC/CVV/security-code variants) proving no Case,
  child row, idempotency, log, metric, Audit or snapshot leakage
- Controller/OpenAPI request/response/error/security, optional-field JSON omission and target/runtime parity tests
- Spring Modulith/ArchUnit tests proving Controller→Service and Support→owner public API boundaries
- production-profile startup failure for missing mandatory permission/Audit dependencies; no H2 substitution

## Validation Commands

### Completion evidence (2026-08-11)

- `./gradlew test --tests SupportCaseTest --tests SupportContentPolicyTest --tests SupportCaseMigrationTest --tests SupportCaseIntegrationTest --tests SupportCaseOpenApiContractTest --tests SupportArchitectureTest --tests ModularityTests --tests RuntimeOpenApiParityTest`
  — exit 0, `BUILD SUCCESSFUL in 26s`.
- First `./gradlew test` — exit 1: `AuditRetentionPolicyMigrationTest` expected the final Flyway version to be V39 at
  line 40, while V40 was correctly present. The historical inventory expectation was updated from 39 to 40; no migration
  was changed or removed.
- Re-run `./gradlew test` — exit 0, `BUILD SUCCESSFUL in 8m 30s`; JUnit XML reports 144 suites, 672 tests, 0 failures,
  0 errors and 1 skipped.
- `./gradlew spotlessApply` — exit 0; `./gradlew spotlessCheck` — exit 0, `BUILD SUCCESSFUL in 774ms`.
- `./scripts/verify-docs.sh` — exit 0; target/runtime OpenAPI 42 paths/46 operations and 115 schemas, plus 33 business
  policies, 91 ADRs, 225 Markdown files and 36 ExecPlans validated.
- `git diff --check` — exit 0.
- After the `active/` → `completed/` move: `./scripts/verify-docs.sh` — exit 0 with the same 42/46/115 and
  33/91/225/36 inventory; `./gradlew spotlessCheck` — exit 0, `BUILD SUCCESSFUL in 1s`; `./gradlew build` — exit 0,
  `BUILD SUCCESSFUL in 1s` (2 executed, 9 up-to-date).

The full suite exercises the actual `ModularityTests`; the focused command includes the S20 ArchUnit boundary test. The
remediation fixture measurement documents an index choice only; it makes no production performance claim.

### PR #52 remediation evidence (completed, 2026-08-11)

- Red regression command: `./gradlew test --tests SupportContentPolicyTest --tests SupportCaseMigrationTest --tests
  SupportCaseIntegrationTest` — exit 1, 14 tests with 7 expected failures before the remediation: embedded PAN/CVC
  rejection, non-assignee transition 403, invalid `OTHER` reason 400, idempotency scope/canonical collision, and the
  two V40 constraints.
- Focused remediation command: `./gradlew test --tests SupportContentPolicyTest --tests SupportCaseMigrationTest --tests
  SupportCaseIntegrationTest` — exit 0, `BUILD SUCCESSFUL in 24s` after implementation. The suite also corrected a
  pre-existing cursor-tamper fixture whose final Base64URL character could decode to the original signature; it now
  modifies the first character deterministically.
- S20 focused command: `./gradlew test --tests SupportCaseTest --tests SupportContentPolicyTest --tests
  SupportCaseMigrationTest --tests SupportCaseIntegrationTest --tests SupportCaseOpenApiContractTest --tests
  SupportArchitectureTest --tests ModularityTests --tests RuntimeOpenApiParityTest` — exit 0,
  `BUILD SUCCESSFUL in 28s`.
- Index decision measurement: PostgreSQL Testcontainers executed the exact list projection for all four filter shapes
  with `EXPLAIN (ANALYZE, BUFFERS)` on 20,000 synthetic rows. The retained/added indexes and raw before/after timings
  are recorded in `docs/architecture/support-query-model.md`; these fixture values are not a production performance
  claim.
- Initial simultaneous `cleanTest test --rerun-tasks` attempts were invalid because each process removed the shared
  `build/test-results` binary while the other was writing it (`NoSuchFileException`). They were not used as evidence.
  After both were idle, a single `./gradlew test --rerun-tasks` run passed in `9m 48s`: JUnit XML reports 144 suites,
  678 tests, 0 failures, 0 errors and 1 skipped.
- `./gradlew spotlessCheck build` — exit 0, `BUILD SUCCESSFUL in 3s`; its test task reused the passing single-process
  result above. `./scripts/verify-docs.sh` and `git diff --check` are re-run after this completed-path update.

## Observability and Privacy

S20 emits no Support-specific metric or log payload. Its Audit summary is limited to action/state/version; actor,
requester, subject, Case, external reference, note, interaction content, reason and cursor are not included in the Audit
payload. Future telemetry must use only closed outcome labels and preserve this exclusion.

## Documentation Updates

- finalize exact S20 state/requester/category/reopen rules in `docs/product/support-case-policy.md`
- amend ADR-081/070 only for concrete persistence/query/cursor decisions
- add implemented operations to both canonical OpenAPI files and update draft inventory status
- update Support aggregate/transaction/authorization docs, traceability and an actual Case runbook
- update this living plan, move it to completed only after validation, then author only eligible direct successors

## Progress

- [x] S10 direct input completed with V39 Audit/retention/permission evidence
- [x] S20 plan authored from current repository and Accepted ADR-081 boundary
- [x] Writes-Migration and execution-time lease requirement recorded
- [x] execution preflight: latest main/origin main, worktree inventory and sole migration-writer lease verified; V40 selected
- [x] exact Case state/requester/category/no-reopen policy accepted and readiness recalculated
- [x] SupportCase domain, persistence, Application Service, Controller and target/runtime OpenAPI implementation
- [x] V40 migration and focused PostgreSQL/domain/integration/runtime-parity tests
- [x] initial full validation, documentation consistency review and completion handoff
- [x] PR #52 review remediation: security/authorization/idempotency/JSON corrections, execution-plan measurements and
  full validation; remediation migration-writer lease release

## Surprises & Discoveries

- S10 seeded the Case retention policy and exact permissions without creating a Support capability, so S20 can consume
  stable foundations without treating dormant grants as role bundles.
- The draft API inventory has nine distinct S20 operations; a generic command DTO would erase different authorization,
  idempotency and error contracts.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-11 | Plan boundary | independent Support Context with identifier-only owner links | preserve ADR-081 ownership and Aggregate boundaries | ADR-081, this plan |
| 2026-08-11 | Readiness | S20 is active but not implementation-ready | S10 direct input exists, but exact Case transition/vocabulary/reopen policy remains draft | SupportCase Policy, this plan |
| 2026-08-11 | Product policy | initial state matrix and closed vocabulary accepted; S20 exposes no reopen endpoint | persist/API contract needs a fixed policy; reopen requires future authorization and Audit decision | SupportCase Policy, SP-16 |
| 2026-08-11 | Migration lease | `feature/support-case-foundation` is the sole S20 migration writer; V40 selected from latest main/origin main V39 inventory | ADR-072 requires one execution-time holder and branch-local next number selection | lease evidence above, ADR-072 |
| 2026-08-11 | Scope | exact search, verification/reveal and action execution stay out of S20 | those features require later owner/security models | program orchestration |
| 2026-08-11 | Product policy | S20 has no DataAccessGrant; S40 must revoke active Grants on terminal Case and block terminal Case grant activation/reveal | preserve terminal Case safety without inventing an out-of-scope Aggregate or a no-op revocation | SupportCase Policy, SP-16 |
| 2026-08-11 | Completion | V40 Case foundation, API contract and full regression completed; migration-writer lease released | V1–V39 remain unchanged and all required S20 validation has evidence | validation evidence above |
| 2026-08-11 | Review remediation | accept eight valid PR #52 findings and correct V40/code/test/document contracts before merge | amendment retains the original S20 scope while restoring payment-data, object authorization, idempotency, retention and JSON guarantees | remediation evidence above |

## Outcomes & Retrospective

V40 establishes the independent SupportCase boundary: Aggregate-protected state transitions, current assignment and
append-only assignment/state histories; separate bounded interaction/note rows; typed opaque subject links; endpoint-
specific target/runtime OpenAPI; and persistent permission plus object/version checks. Closed Cases reject ordinary
mutations, content policy rejects secret/high-risk PII before persistence, and Audit failure rolls back the mutation.

The initial S20 validation passed, but PR #52 review found valid Critical/Important defects in payment-card detection,
object authorization, canonical idempotency, idempotency scope/retention and JSON null omission. The remediation added
embedded PAN/CVC/CVV rejection before any persistence; assigned transition authorization; typed length-prefixed canonical
payloads; actor/operation/key idempotency scope with 90-day bounded cleanup; non-null JSON omission; close-time DB
alignment; and measured list-index selection. Focused and single-process full validation now pass, so this plan is
completed again and its V40 writer lease is released. S30 is not authored or ready: it still requires an Accepted ADR-083
and a customer/contact/crypto owner model. S40 remains a future plan and must implement the explicitly recorded
terminal-Case DataAccessGrant revocation and activation/reveal denial. No production performance result is claimed.

## Revision Notes

- 2026-08-11: user accepted the initial Case state matrix, closed requester/category vocabulary and S20 no-reopen scope;
  readiness is true. Execution still requires ADR-072 migration-writer lease and V-next selection.
- 2026-08-11: `feature/support-case-foundation` acquired the sole migration-writer lease after latest-main/worktree preflight
  and selected V40. The active Analytics plan is not an executing holder.
- 2026-08-11: user confirmed that DataAccessGrant remains outside S20; policy now assigns terminal-Case Grant revocation and
  terminal activation/reveal denial to the future S40 Grant implementation.
- 2026-08-11: completed V40 fresh PostgreSQL/full-suite/format/document validation, corrected the historical V39 latest-
  migration test expectation to V40, released the migration-writer lease, and moved this plan to completed path.
- 2026-08-11: reopened this plan for valid PR #52 review remediation. V40 remains unmerged, latest main is
  `26880ecce2a865e84696fa62c909ffa50fb5bd17`, and this branch is the sole open migration writer; it therefore reacquired
  the writer lease before modifying V40.
- 2026-08-11: completed PR #52 remediation validation, restored the completed path, and released the V40 writer lease.
