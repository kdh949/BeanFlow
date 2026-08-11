# S50 통합 거래 timeline과 typed ActionPolicy를 구현한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s30-protected-profile-search.md`, `docs/exec-plans/completed/customer-support-s40-verification-data-access-grant.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

상담원이 한 Case 또는 연결된 Order의 Ordering, Payment, Loyalty, Promotion, Fulfillment, Settlement,
Notification, Operations 사실을 한 화면에서 최신순으로 확인하고, 서버가 현재 권한·관계·verification·Order
state/version을 근거로 action 가능성을 설명한다. Support는 owner table/Repository를 읽거나 write Aggregate graph를
확장하지 않고 bounded public query DTO만 조합한다.

## Current State

- branch는 S40 verified head `ae9fa0b9c97a75134131106a1818f04315611860`에서 분기한
  `feature/support-integrated-timeline-action-policy`다. 최종 PR base는 parent S40 branch다.
- Flyway inventory는 V1~V42이며 마지막은 `V42__create_support_verification_and_data_access_grant.sql`이다.
- S20은 Case/history/interaction/note/identifier-only SubjectLink와 signed Case list를, S30은 masked owner exact search를,
  S40은 Case+Subject+Purpose+action-bound verification과 field-scoped reveal을 제공한다.
- target/runtime OpenAPI에는 22개 Support operation이 있고 timeline/action-evaluation 3개는 draft inventory뿐이다.
- payment refund와 notification delivery는 Order timeline key가 있으나 supporting order/time index가 없다.
- migration-writer lease는 2026-08-12에 이 branch가 S40 head/V42 inventory, open PR #54만 존재함, 다른 worktree와
  active plan에 explicit current acquisition evidence가 없음을 확인하고 획득했다. S50은 V43을 사용한다.
- shared working tree의 productization 문서 변경은 사용자 소유이며 S50 commit에 포함하지 않는다.

## Definitions

- **Owner fact:** owner Context의 public query API가 반환하는 masked, closed-vocabulary transaction fact.
- **Timeline tuple:** `(occurredAt DESC, sourceRank ASC, itemId DESC)`.
- **Action evaluation:** UI 안내용 server decision. 실행 권한이나 승인 기록이 아니며 2분 후 만료된다.
- **Action-bound verification:** `SUPPORT_ACTION` scope와 `CASE_RESOLUTION` purpose에 bound된 S40 session.

## Scope

### In Scope

- Case/Order timeline endpoint, source/type filters, common HMAC cursor와 bounded merge
- owner별 typed timeline DTO/query API와 fixed query count
- Ordering current state/version/customer/store snapshot
- three initial typed action policies and server-side permission/Case/relation/verification checks
- V43 action scope check constraint and two timeline indexes
- target/runtime OpenAPI, query/security/performance/documentation evidence

### Non-goals

- action execution/request/approval, generic rules engine, Elasticsearch, materialized timeline projection
- raw PII, free-form owner payload, write-model relationship, cross-context write
- profile/compensation/delivery action activation before their owning Stage

## Business Rules and Invariants

ADR-081/084와 ADR-070 S50 amendment를 따른다. Unknown policy input은 DENIED다. Owner query failure는 empty 200이나
DENIED로 바꾸지 않는다. UI evaluation은 advisory이고 policy/target version과 expiry를 노출한다. 모든 timeline item은
masked closed DTO이며 cursor/log/metric/Audit에 PII를 넣지 않는다. `PERSONAL_DATA_REVEAL` session은 action에 재사용하지
않는다.

## Architecture and Transaction Boundaries

Controller는 Support Application Service만 호출한다. Support는 local Case/subject timeline projection과 active Order
links를 bounded query로 읽고 owner public APIs를 source당 한 번 호출해 memory에서 global tuple로 merge한다. Owner
APIs는 각자의 JdbcTemplate query만 소유한다. persistent permission/Case/verification validation은 짧은 transaction에서
수행하며 external Provider call은 없다. Action evaluation은 Ordering public snapshot을 읽은 뒤 typed evaluator를 호출한다.

## Alternatives Considered

- Support direct SQL/JPA join: ownership과 module boundary를 깨므로 제외.
- write Aggregate association: query 편의를 위해 write graph를 확장하므로 제외.
- materialized projection: lag/rebuild/inbox failure model이 현재 필요보다 크므로 제외.
- typed live owner composition: fresh, bounded, fixed query count이고 current owner schema를 재사용하므로 선택.

## Failure Semantics

Invalid cursor/filter/limit은 400, permission/object authorization은 403, missing Case/Order는 404다. Target version
mismatch는 `DENIED`+closed reason으로 보이며 dependency/DB query failure는 503이다. 어느 실패도 partial timeline,
empty success, stale/cache/fake fallback으로 바꾸지 않는다.

## Data and Migration

V43은 `support_verification_session.action_scope`에 `SUPPORT_ACTION`을 추가하고
`payment_refund(order_id, updated_at DESC, id DESC)`,
`notification_delivery(order_id, updated_at DESC, id DESC)` index를 추가한다. V1~V42를 수정하지 않는다. Policy
version은 immutable typed Kotlin identifier이며 generic DB rule table을 만들지 않는다.

## API and Cursor Contracts

- `GET /api/v1/support/cases/{caseId}/timeline`
- `GET /api/v1/support/orders/{orderId}/timeline?caseId=...`
- `POST /api/v1/support/cases/{caseId}/action-evaluations`

Timeline cursor는 ADR-070의 endpoint ID/filter hash/15분 TTL/default 20/max 100을 사용한다. Response는 source/type,
public state, masked summary, correlation/causation reference, occurredAt, itemId만 포함한다. Action evaluation request는
typed action, Order target/version과 action-bound verification session ID만 받으며 role/verification level/history/
decision은 받지 않는다.

## Milestones

1. ADR-070/084와 living plan을 확정하고 V43 lease를 기록한다.
2. ActionPolicy domain test를 RED로 만들고 immutable typed evaluator를 구현한다.
3. Owner public query contracts와 mapping tests를 source별로 구현한다.
4. Support merge/cursor/query-count/masking tests와 timeline API를 구현한다.
5. Action evaluation authorization/stale/API/OpenAPI tests를 구현한다.
6. PostgreSQL identical-fixture EXPLAIN baseline/re-measure, full build와 docs validation을 완료한다.
7. plan을 completed로 이동하고 S60 readiness/orchestration을 같은 commit에서 갱신한다.

## Required Tests

- `SupportActionPolicyTest`: state/verification matrix, default deny, version/expiry
- `SupportActionEvaluationIntegrationTest`: permission/Case/relation/scope/stale version/revoke
- `SupportTimelineOwnerQueryIntegrationTest`: all owner facts, masking, fixed owner query count
- `SupportTimelineIntegrationTest`: global order, cursor stability/filter binding/expiry, Case multi-Order no N+1
- `SupportTimelineQueryPlanTest`: V43 index definition과 identical-fixture EXPLAIN baseline/re-measure
- `SupportTimelineOpenApiContractTest`: closed schema/errors/no-store/target-runtime parity
- `SupportArchitectureTest`, `ModularityTests`, `RuntimeOpenApiParityTest`

## Validation Commands

- `./gradlew test --tests '*SupportActionPolicyTest'`
- `./gradlew test --tests '*SupportActionEvaluationIntegrationTest'`
- `./gradlew test --tests '*SupportTimelineOwnerQueryIntegrationTest' --tests '*SupportTimelineIntegrationTest'`
- `./gradlew test --tests '*SupportTimelineQueryPlanTest'`
- `./gradlew test --tests '*SupportTimelineOpenApiContractTest' --tests '*SupportArchitectureTest' --tests '*ModularityTests' --tests '*RuntimeOpenApiParityTest'`
- `./gradlew spotlessCheck test`
- `./gradlew build`
- `./scripts/verify-docs.sh`
- `git diff --check`

## Observability

PII-free source/type/page-size/query-failure와 policy decision/reason/version counter만 허용한다. Case/Order/Subject ID,
cursor/filter hash, summary, actor, raw value는 metric tag나 log payload에 넣지 않는다.

## Documentation Updates

ADR-070/084, Support query/action/API/transaction/security/test/traceability, target/runtime OpenAPI, orchestration과 이
living plan을 actual implementation/evidence에 맞게 갱신한다.

## Progress

- [x] mandatory documents, current branch/schema/owner model inspected
- [x] endpoint cursor contract and initial action matrix accepted from existing policy constraints
- [x] V43 migration-writer lease preflight and acquisition evidence recorded
- [ ] policy/domain RED-GREEN slice
- [ ] owner query API and V43 PostgreSQL slice
- [ ] timeline composition/cursor/API slice
- [ ] action evaluation integration/API slice
- [ ] focused/full/document validation
- [ ] completion move, S60 readiness handoff and lease release

## Surprises & Discoveries

Existing owner tables already have direct Order lookup indexes except refund and notification history. S40 stores an explicit
action-scope column but the only allowed/runtime value is reveal, so S50 must add a distinct action scope before using
verification in ActionPolicy. Reusing reveal verification would violate the exact binding invariant.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-12 | Accepted | global tuple, two endpoint IDs, sorted canonical filters and 15m cursor | stable cross-source keyset and scope binding | ADR-070 |
| 2026-08-12 | Accepted | three initial typed Order actions and immutable `support-action-policy/2026-08-12/v1` | enable S70/S80 inputs without generic engine | ADR-084 |
| 2026-08-12 | Security | add `SUPPORT_ACTION` verification scope | reveal proof cannot authorize state mutation | ADR-084, V43 |
| 2026-08-12 | Migration lease | S50 owns V43 after S40 V42 head | stacked parent inventory and no competing holder | this plan |

## Outcomes & Retrospective

진행 중이다. 구현·검증 evidence 없이 완료나 성능 향상을 주장하지 않는다.

## Revision Notes

- 2026-08-12: authored from S40 verified head, fixed the cursor/action-scope contract, acquired the V43 lane and began S50.
