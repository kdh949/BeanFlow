# S20 SupportCase, Interaction과 SubjectLink foundation을 구현한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s10-retention-audit-permission.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. S10의 실제 V39 Audit/retention/permission 결과를 direct input으로
사용한다. S20의 migration-writer lease는 획득하지 않았고 Flyway 번호도 선택하지 않았다. 실행자는 latest
main과 concurrent migration work를 다시 확인한 뒤 ADR-072 lease를 별도로 획득해야 한다.

`Implementation-Ready=false`인 이유는 `docs/product/support-case-policy.md`가 exact state transition,
requester/category vocabulary와 reopen policy를 아직 draft implementation policy로 두기 때문이다. 이 제품
동작을 Accepted policy로 기록하기 전에는 migration/API 구현을 시작하지 않는다.

## Purpose / Big Picture

독립 Support Context에 lightweight `SupportCase` 생명주기, assignment/state history, bounded interaction/note와
identifier-only subject link를 구현한다. 모든 privileged Support action이 연결할 수 있는 active Case 경계를
만들되 검색, verification, PII reveal, owner action execution과 UI는 구현하지 않는다.

완료 후 Case 상태와 assignment는 Aggregate와 DB constraint가 함께 보호하고, 대량 history는 Case JPA
collection이 아닌 append-only query model로 조회한다. S10의 dormant permissions와 Audit category/policy를
사용하되 role이나 grant만으로 object-level authorization을 대체하지 않는다.

## Current State

- Support module, table, Controller와 canonical Support OpenAPI operation은 없다.
- ADR-081은 독립 Support Context, owner public API와 ID reference boundary를 Accepted했다.
- `docs/product/support-case-policy.md`의 exact state/requester/category/reopen vocabulary는 S20에서 계약과
  PostgreSQL constraint로 검증해야 하는 draft implementation policy다.
- V39는 `SUPPORT_CASE` close-based 3년 policy version과 `SUPPORT_CASE_READ/WRITE/ASSIGN` persistent permission을
  등록했지만 Case row, expiry 계산이나 deletion은 만들지 않았다.
- Operations Audit append는 category/class/immutable policy version을 caller transaction에서 snapshot하고
  실패 시 caller write를 rollback한다.
- latest migration inventory는 V39다. S20은 실행 전 V-next를 다시 선택해야 한다.

## Scope

### In Scope

- new Support module API/internal boundary and Modulith declaration
- SupportCase state/version, optional external reference, requester/category/priority와 current assignee
- append-only assignment and state history
- bounded interaction and secret-filtered note records, Case collection과 분리된 cursor projection
- identifier-only customer/store/order/delivery subject links with relationship and unlink history
- create/list/get/assign/state-transition/interaction/note/link/unlink endpoint-specific DTO와 canonical target/runtime
  OpenAPI parity
- persistent `SUPPORT_CASE_READ/WRITE/ASSIGN` authorization, queue/assignment/Case visibility and object relation checks
- Support lifecycle Audit using S10 category/policy snapshot and PII-free summaries
- PostgreSQL constraints, idempotency, concurrency, migration and API contract tests

### Non-goals

- exact subject search or masked owner profile projection
- VerificationSession, DataAccessGrant, PII reveal or break-glass
- Support action evaluation/approval/execution, order/delivery/profile mutation or compensation
- LegalHold and SupportCase content deletion worker
- UI, Elasticsearch, external contact-center integration or generic attachment storage

## Readiness Gate

S20 implementation 전 Business Policy에서 다음 하나의 결정을 확정한다: initial Case state transition matrix,
requester/category closed vocabulary와 reopen 허용 여부/권한/Audit contract. 권고안은 현재 draft의
`OPEN | IN_PROGRESS | WAITING | RESOLVED | CLOSED`와 requester/category vocabulary를 initial policy로
승격하되 S20에서는 reopen endpoint를 노출하지 않는 것이다. 이 결정은 SupportCase 상태/API/DB CHECK와
history fixture 전체에 영향을 주며 `docs/product/support-case-policy.md`와 필요 시 ADR-081 amendment에 기록한다.

## Business Rules and Invariants

1. Case transition legality is owned by `SupportCase`; direct state field updates are forbidden.
2. `CLOSED` rejects ordinary assignment, interaction, note, link and privileged action. Reopen is not exposed until its
   exact authorization/Audit contract is accepted.
3. assignment and state changes append history in the same transaction as Case version/state update.
4. subject links store type plus opaque owner ID only. Support does not write another Context's table or create JPA
   relationships to owner entities.
5. note/interaction rejects password, OTP, token, PAN/CVC, full account and unnecessary address content before persistence.
   rejected input is not logged or copied into Audit/test snapshots.
6. large interaction, note and history sets are not `@OneToMany` collections on SupportCase. Cursor queries use bounded
   DTO projections and a stable endpoint-specific tuple.
7. every command assumes duplicate delivery. same key/same canonical payload returns the stored terminal response;
   same key/different payload is a conflict, and no duplicate history/Audit row is written.
8. persistent permission is necessary but not sufficient: Case visibility, current assignment where required, expected
   Case version and subject relation are revalidated in the same transaction.
9. Audit failure rolls back privileged Case mutation. JWT role, cache, in-memory grant or no-op Audit is never a fallback.
10. SupportCase 3-year retention policy is snapshot/reference input only in S20; deletion automation remains S120 scope.

## Architecture and Transaction Boundaries

### Case command transaction

Controller calls a Support Application Service, never a Repository. The service validates authenticated actor and
request, locks the persistent Operations permission through its public API, loads/locks the SupportCase when present,
checks visibility/assignment/version, invokes Aggregate transition, appends history and PII-free Audit, then flushes in
one local database transaction. Any permission, policy, Audit or persistence failure rolls back the command and terminal
idempotency response.

Create uses an idempotency key/advisory transaction lock before inserting Case and initial state history. Assignment,
transition, note/interaction and subject-link commands use Case-scoped serialization plus expected version. Do not hold
the transaction across an external Provider or owner Context network call.

### Query transaction

List/detail/history queries are read-only Support projections, except privileged reads that require an accepted access
Audit contract. Object visibility predicates belong in the query/application boundary so another queue or Case does not
leak existence or data. Dependency failure is 503, not an empty page or 404.

### Cross-context boundary

S20 stores owner IDs and validates only through typed public owner Application APIs where validation is necessary.
Support never imports owner Repository/Entity or updates owner tables. A dependency timeout remains unavailable/unknown;
it is not converted to a valid subject link.

## Data and Migration

After acquiring an execution-time lease, add one V-next forward migration that:

1. creates SupportCase with closed state/requester/category/priority vocabulary, version and retention policy reference;
2. creates append-only assignment/state history with sequence/uniqueness and actor/reference constraints;
3. creates interaction/note tables separate from the Aggregate row, with bounded lengths and retention class/version;
4. creates subject link rows with Case/type/opaque ID/relationship uniqueness and unlink lifecycle;
5. creates command idempotency records with canonical payload hash and terminal response bounds;
6. adds FK/CHECK/UNIQUE/index constraints for state, expected query order, current assignment and duplicate prevention;
7. grants no Support permission by default and does not modify V1..V39.

The implementation must define whether Case content expiry is materialized at close or derived by a later S120 owner
port. It may reference the seeded immutable `SUPPORT_CASE` policy version, but must not delete content in S20.

## API and Event Contracts

S20 owns the first nine rows in `docs/api/support-api-surface.md`: create/list/get Case, assignment, state transition,
interaction, note, subject link and unlink. Each operation gets a distinct request/response schema, stable error mapping,
`additionalProperties: false`, operation security and target/runtime parity only after Controller implementation.

List/history cursors bind endpoint scope, filters, sort tuple and direction using the existing signed cursor convention.
The exact tuple is recorded in ADR-070 only after the actual projection is selected. Generic Support command/resource
schemas are forbidden. No event is required merely for local history; introduce an event only for a named consumer and
record duplicate-delivery behavior.

## Failure Semantics

- missing/revoked permission or object authorization mismatch: 403 without revealing unrelated Case details;
- absent visible Case: 404; stale expected version or illegal transition: 409;
- invalid/secret-bearing input or malformed cursor/idempotency contract: 400;
- DB, owner validation, policy or Audit failure: 503 and no partial Case/history/idempotency success;
- duplicate same-payload request: original terminal response; duplicate different payload: 409;
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
- Application Service permission/object/assignment matrix, idempotent replay/conflict and Audit rollback
- PostgreSQL fresh migration, CHECK/FK/UNIQUE, append-only history and no default grants
- concurrent assignment/transition/idempotency winner tests with deterministic terminal outcomes
- bounded cursor order/page/tampering/filter-scope tests and statement-count evidence for large history fixtures
- secret/PII negative corpus proving no persistence, log, metric, Audit or snapshot leakage
- Controller/OpenAPI request/response/error/security and target/runtime parity tests
- Spring Modulith/ArchUnit tests proving Controller→Service and Support→owner public API boundaries
- production-profile startup failure for missing mandatory permission/Audit dependencies; no H2 substitution

## Validation Commands

- focused Support domain/application/PostgreSQL/API test selectors chosen from implemented class names
- `./gradlew test --tests '*ModularityTests' --rerun-tasks`
- `./gradlew spotlessCheck test`
- `./scripts/verify-docs.sh`
- `git diff --check`
- migration inventory and checksum review proving V1..V39 unchanged and exactly one V-next added

Record every command, exit status and relevant count/duration in this plan. Do not claim performance without a comparable
baseline and identical measurement conditions.

## Observability and Privacy

Use closed state/category/outcome labels for Case create/transition/assignment/error and bounded query latency/count.
Actor, requester, subject, Case, external reference, note, interaction content, reason and cursor are forbidden labels.
Logs record stable outcome/error class and opaque correlation only where policy permits; rejected secrets are never echoed.

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
- [ ] exact Case state/requester/category/reopen policy accepted and readiness recalculated
- [ ] execution preflight/lease and V-next selection
- [ ] domain/API tests and implementation
- [ ] migration, full validation, documentation and completion handoff

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
| 2026-08-11 | Scope | exact search, verification/reveal and action execution stay out of S20 | those features require later owner/security models | program orchestration |

## Outcomes & Retrospective

Not implemented. This plan describes the now-eligible direct successor after S10, but the exact Case policy gate keeps
readiness false. No Support code/table/endpoint, migration number, lease, performance result or capability release is
claimed.

## Revision Notes

- 2026-08-11: authored from completed S10 actual outcome and current Support policy/ADR/API inventory; readiness remains
  false until exact Case transition/vocabulary/reopen policy is Accepted.
