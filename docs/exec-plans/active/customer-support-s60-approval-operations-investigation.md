# S60 exact revision 승인과 Operations 조사를 구현한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s50-integrated-timeline-action-policy.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`, `Decision Log`,
`Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

상담원이 제안한 privileged action을 immutable revision에 묶고, Support Manager와 Operations가 서로 다른 actor로
검토한 뒤 승인된 정확한 revision만 owner execution으로 넘길 수 있게 한다. 승인자는 payload를 수정하거나 직접
실행하지 않고, Operations decision은 조사 Aggregate에 기록된 뒤 Support request의 같은 revision으로 반환된다.

## Current State

- branch는 S50 verified head `f7880cbc7810bbb9147e8aebaec8e041e67a90a8`에서 이어받은
  `feature/support-approval-operations-investigation`이며 최종 PR base는 parent S50 branch다.
- Flyway inventory는 V1~V43이고 마지막은 `V43__add_support_timeline_and_action_scope.sql`이다.
- S20은 Case/assignment history, S40은 Case+Subject+actor+purpose+scope bound VerificationSession, S50은 current
  Order state/version 기반 typed ActionPolicy를 제공한다. ActionRequest, revision, approval step과 investigation
  persistence는 아직 없다.
- target/runtime OpenAPI에는 S20~S50의 25개 Support operation이 있다. S60은 request/read/revise/Support decision/
  reassignment와 Operations decision을 구현하고 generic execution endpoint는 활성화하지 않는다.
- S50 V43 writer lease는 완료 검증 뒤 해제됐다. 2026-08-12 현재 open schema PR은 이 Support stack뿐이고 다른
  worktree/active plan에 현재 acquisition evidence가 없다. 이 branch가 sole V44 migration-writer lease를 획득했다.

## Definitions

- **Action request:** 한 Case, action, target과 실행 후보 상담원을 소유하는 Support Aggregate Root.
- **Revision:** action/target, action payload digest, VerificationSession, policy/target version, amount, reason,
  evidence digest와 expiry를 담은 immutable snapshot.
- **Approval route:** `NONE | SUPPORT_MANAGER | OPERATIONS | SUPPORT_MANAGER_THEN_OPERATIONS` closed plan.
- **Approval step:** exact request revision에 귀속되는 append-only Support Manager 또는 Operations decision lineage.
- **Investigation:** Operations가 소유하며 Support request/revision 식별자만 참조하는 조사 Aggregate.

## Scope

### In Scope

- SupportActionRequest, immutable revision, approval step, reassignment history와 command idempotency
- S50 live evaluation에서 파생되는 `NONE` 또는 `SUPPORT_MANAGER` route
- S90/S100이 사용할 dormant `OPERATIONS`와 `SUPPORT_MANAGER_THEN_OPERATIONS` internal typed route
- OperationsInvestigationCase open/approve/deny/return/escalate와 Support callback
- exact expiry/stale/permission/verification/target-version/actor-separation recheck
- V44 constraints/indexes, no-store API, target/runtime OpenAPI와 security/traceability evidence

### Non-goals

- Ordering/compensation/profile owner 실행, generic execution endpoint, payload-specific owner validation
- generic JSON payload, generic rule engine, raw PII/evidence/payload storage
- reviewer payload editing, automatic reassignment, Support direct Operations table mutation

## Business Rules and Invariants

ADR-084를 따른다. requester, Support approver와 Operations approver는 모두 다르고 승인자는 executor가 될 수 없다.
같은 actor는 두 approval step을 맡을 수 없다. action/target 자체 변경은 새 request이며 material payload·version·reason·
evidence 변경은 새 revision이다. 새 revision은 이전 unused approval을 `STALE`로 만든다.

Revision expiry는 별도 제품 숫자를 만들지 않고 bound action VerificationSession의 exact `expiresAt`을 사용한다.
`now >= expiresAt`은 expired다. 승인 시 requester/approver persistent grant, Case/subject binding, verification state,
immutable policy identifier와 owner target version을 다시 확인한다. 승인은 한 revision에서 한 번만 결정할 수 있다.

S60 API는 raw action payload 대신 lowercase 64-hex SHA-256 digest를 받아 binding만 소유한다. S70/S80/S90/S100의
typed owner command는 실행 직전 자기 canonical payload digest를 다시 계산해 revision과 비교해야 한다. amount가 없는
action은 null을, 있는 action은 정수 KRW를 exact binding에 포함한다.

Operations는 decision reason/evidence digest를 조사 기록에 쓰되 Support payload를 수정하지 않는다. `RETURN_FOR_REVISION`
은 request를 `REVISION_REQUIRED`로 돌리고 새 revision만 다음 심사를 시작한다. 원 executor가 active execute/capability
grant를 잃으면 `REASSIGNMENT_REQUIRED`이며 `SUPPORT_CASE_ASSIGN` actor가 적격 target으로 Case와 request를 함께 명시적으로
재배정한다.

## Architecture and Transaction Boundaries

Controller는 Application Service만 호출한다. Support는 Support tables만, Operations는 investigation tables만 직접
쓴다. Support는 `OperationsSupportInvestigationOperations` public port로 investigation을 열고, Operations decision은
Operations API에 선언된 required callback을 Support 구현이 받아 자기 request/step을 갱신한다. 이 dependency inversion은
Operations→Support static module cycle을 만들지 않는다.

create/revise/Support decision/reassignment은 각각 Case/request/verification row와 permission grant를 잠그는 짧은 Support
transaction이다. manager approval이 Operations route를 이어야 하면 같은 transaction에서 Operations open port를 호출한다.
Operations decision은 investigation lock, permission/separation check, Support callback, investigation/Audit 저장을 한 DB
transaction으로 commit한다. 외부 Provider 호출은 없다. Audit append 실패는 privileged 상태 변경 전체를 rollback한다.

Action create/approval recheck는 owner current snapshot을 읽은 뒤 Support request를 다시 잠가 observed request version,
revision과 target version을 검증한다. owner lookup 실패는 decision이나 stale로 축소하지 않고 503이다.

## Alternatives Considered

- generic JSON payload 또는 DB rules engine: future owner schema를 추정하고 typed/default-deny 계약을 약화해 제외했다.
- raw payload 저장: PII/evidence 복제 위험 때문에 digest-only binding을 선택했다.
- Support의 Operations repository 직접 사용: Context ownership을 위반해 public port/callback을 선택했다.
- event-only eventual return: decision response 직후 request lineage가 불일치할 수 있어 같은 DB transaction callback을 선택했다.
- approval+execution 결합: reviewer direct execution과 owner recheck를 깨므로 S60은 `READY_FOR_EXECUTION`에서 끝낸다.

## Failure Semantics

validation은 400, permission/object scope는 403, missing resource는 404다. self approval, dual role, wrong revision/state,
stale target/policy/verification, expired approval, changed idempotency payload는 closed 409 code다. DB/Audit/required callback
failure는 503이며 state를 성공/빈 값/fallback으로 바꾸지 않는다. Expiry/stale terminalization과 그 Audit은 commit한 뒤
동일 terminal response/error를 idempotently replay한다.

## Data and Migration

V44는 다음 owner tables를 만든다.

- `support_action_request`: Case/action/target/requester/executor/current revision/state/route/approver lineage/version
- `support_action_revision`: immutable binding snapshot, `(request_id, revision_number)` unique
- `support_action_approval_step`: request/revision/step unique, append-only decision binding
- `support_action_reassignment`: append-only previous/new executor and Case version
- `support_action_command_idempotency`: actor/operation/key unique, canonical hash와 terminal response
- `operations_support_investigation_case`: request/revision binding, actor separation, decision/state/version
- `operations_support_investigation_idempotency`: Operations decision exact replay

Request row는 requester/Support approver/Operations approver pairwise distinct와 approver/executor distinct check를 갖는다.
Operations row도 requester/Support approver/Operations reviewer separation을 DB와 service 양쪽에서 보호한다. 기존 V1~V43은
수정하지 않는다.

## API Contracts

- `POST /api/v1/support/cases/{caseId}/action-requests`
- `GET /api/v1/support/action-requests/{requestId}`
- `POST /api/v1/support/action-requests/{requestId}/revisions`
- `POST /api/v1/support/action-requests/{requestId}/support-manager-decisions`
- `POST /api/v1/support/action-requests/{requestId}/reassignments`
- `POST /api/v1/operations/investigations/{investigationId}/decisions`

모든 write는 `Idempotency-Key`가 필요하고 unknown body field를 거부한다. Client는 approval route, role, permission,
verification level/state, target current state, policy decision/state 또는 approver identity를 보내지 않는다. 응답은 raw
reason/evidence를 되돌리지 않고 digest, closed state/step, immutable version/time만 노출하며 `Cache-Control: no-store`다.

## Milestones

1. ADR-084/action/API/security contract와 living plan, V44 lease를 확정한다.
2. ActionRequest/revision/approval domain state machine을 RED-GREEN으로 구현한다.
3. V44 Support persistence와 create/read/revise/manager decision을 PostgreSQL에서 구현한다.
4. Operations investigation public port/callback, decision과 explicit reassignment를 구현한다.
5. concurrency/revoke/stale/Audit failure/API/OpenAPI/architecture tests를 완성한다.
6. focused/full/build/docs validation 후 plan 완료 이동, V44 lease release와 S70/S90/S100 readiness를 원자 갱신한다.

## Required Tests

- `SupportActionRequestTest`: self/dual-role, route/state matrix, new revision stales old step, expiry boundary
- `SupportActionRequestIntegrationTest`: create/revise, target/policy/verification stale, permission revoke, concurrent approval,
  one-time/idempotency, Audit rollback
- `OperationsInvestigationIntegrationTest`: no self/same manager reviewer, approve/deny/return/escalate, returned revision,
  concurrent decision, permission revoke, callback/Audit rollback
- `SupportActionReassignmentIntegrationTest`: inactive executor, target eligibility, Case+request atomic reassignment
- `SupportActionRequestMigrationTest`: DB actor/revision/step/idempotency constraints
- `SupportActionRequestOpenApiContractTest`: strict closed schema, errors, no-store, target/runtime parity
- `SupportArchitectureTest`, `ModularityTests`, `RuntimeOpenApiParityTest`

## Validation Commands

- `./gradlew test --tests '*SupportActionRequestTest'`
- `./gradlew test --tests '*SupportActionRequestIntegrationTest' --tests '*OperationsInvestigationIntegrationTest'`
- `./gradlew test --tests '*SupportActionReassignmentIntegrationTest' --tests '*SupportActionRequestMigrationTest'`
- `./gradlew test --tests '*SupportActionRequestOpenApiContractTest' --tests '*SupportArchitectureTest' --tests '*ModularityTests' --tests '*RuntimeOpenApiParityTest'`
- `./gradlew spotlessCheck test`
- `./gradlew build`
- `./scripts/verify-docs.sh`
- `git diff --check`

## Observability

closed route/step/state/outcome/reason counters와 approval/investigation duration만 허용한다. actor/request/Case/target ID,
payload/evidence digest, reason과 amount를 metric tag나 log payload에 넣지 않는다. Audit summary도 closed state/revision
number만 포함한다.

## Documentation Updates

ADR-084, Support ActionPolicy/approval controls/API/error/traceability, target/runtime OpenAPI, orchestration과 이 living
plan을 actual implementation/evidence에 맞게 갱신한다. actual execution은 S70/S80/S90/S100 owner plans로 남긴다.

## Progress

- [x] mandatory documents/current branch/schema/open PR/worktree inspected
- [x] no Accepted ADR/Business Policy conflict; DRAFT execution ownership mismatch resolved as non-runtime owner scope
- [x] S60 branch and sole V44 migration-writer lease acquired
- [ ] ActionRequest/revision/Support approval domain slice
- [ ] V44 persistence and Support API slice
- [ ] Operations investigation/callback/reassignment slice
- [ ] concurrency/security/failure/OpenAPI/architecture slice
- [ ] focused/full/build/document validation
- [ ] completion move, lease release and direct successor readiness handoff

## Surprises & Discoveries

`docs/api/support-api-surface.md`의 DRAFT generic execution row는 S60으로 표시됐지만 user Stage contract는 actual
execution을 Non-goal로 두고 S70이 evaluation-to-execution을 소유한다. DRAFT inventory는 runtime contract가 아니므로
S60은 approved lineage까지만 구현하고 owner execution activation은 후속 Stage에 둔다.

Existing S50 evaluation은 durable evaluation token을 저장하지 않는다. 따라서 create/decision은 UI 결과를 신뢰하지 않고
current Order snapshot, permission, Case relation과 action-bound VerificationSession을 다시 평가해야 한다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-12 | Accepted existing | exact revision binding and actor separation | stale/self approval and reviewer execution prevention | ADR-084 |
| 2026-08-12 | S60 contract | revision expires at bound VerificationSession `expiresAt` | reuse accepted security boundary without inventing policy number | ADR-084 amendment, this plan |
| 2026-08-12 | S60 contract | raw payload is not stored; SHA-256 action payload digest is bound and owner recomputes it | preserve PII boundary and future typed owner ownership | ADR-084 amendment, this plan |
| 2026-08-12 | Module boundary | Operations public open port plus required Support callback in one transaction | owner-only writes without a static module cycle or inconsistent return | ADR-084 amendment, this plan |
| 2026-08-12 | Stage boundary | S60 stops at `READY_FOR_EXECUTION` | S70/S80/S90/S100 own latest-state validation and actual effects | user Stage scope, API inventory |
| 2026-08-12 | Migration lease | S60 owns V44 on S50 stacked head | V43 released and no competing current holder evidence | this plan |

## Outcomes & Retrospective

Implementation is in progress. Completion will record exact test counts, failure/concurrency evidence, OpenAPI path/schema
counts and migration lease release. No execution success is claimed by this Stage.

## Revision Notes

- 2026-08-12: authored from verified S50 head, fixed S60 state/module/payload/expiry boundaries and acquired V44 lease.
