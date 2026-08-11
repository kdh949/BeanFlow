# Customer Support S10~S140 program을 순서화하고 release gate를 관리한다

> **Status:** `ACTIVE`
> **Kind:** `ORCHESTRATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s00-documentation-contracts.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 이 파일은 implementation candidate가 아니며 dependency,
scheduling, plan-authoring gate와 release evidence만 관리한다.

## Purpose / Big Picture

Customer Support를 한 번에 구현하거나 placeholder plan을 미리 Accepted하지 않는다. completed S10의 actual
outcome을 기준으로 direct successor S20 detailed plan을 만들었고, S30~S140은 predecessor actual outcome 뒤
최신 main에서 새 detailed ExecPlan을 작성한다. 각 future plan은 자체 owner model, typed API와 정확한 검증을
가져야 한다.

## Current State

- S00 planning audit/repair가 completed path에 있고 document/manual validation evidence를 기록했다.
- S10은 V39 Audit category/class/immutable policy snapshot, 기존 financial Audit expiry 불변과 신규 5년,
  PII access Audit 2년, concurrent retention worker와 persistent 42-permission vocabulary를 구현·검증했다.
  runtime Support endpoint는 추가하지 않았다.
- S20 detailed plan은 completed path에 있으며 exact Case transition/requester/category/no-reopen policy를
  Aggregate/DB/API로 구현했다. Support module, V40 schema, target/runtime Case API, full Testcontainers/runtime-parity
  regression과 documentation validation evidence가 있다; S20 migration-writer lease는 release됐다.
- S30~S140 code/schema/API는 없고 future summary만 존재한다.
- ADR-083 crypto/KMS와 ADR-090 frontend boundary는 Proposed다.
- 55개 endpoint 중 S20의 9개 Case operation은 canonical target/runtime contract에 구현됐고, 나머지는 DRAFT
  inventory다.

## Definitions

- **Stage summary:** future scope와 authoring gate이며 implementation plan이 아니다.
- **Plan-authoring gate:** predecessor의 actual code/schema/API/test outcome을 최신 main에서 읽을 수 있는 조건.
- **Release gate:** capability를 runtime/target/UI에 노출하기 위한 contract, security, failure와 evidence 조건.

## Scope

### In Scope

S10~S140 logical graph, direct output handoff, migration scheduling, future ExecPlan authoring와 capability release gate.

### Non-goals

Stage implementation, Flyway number reservation, queue priority를 dependency로 표현, feature flag로 incomplete 2xx
path 노출, branch/PR/deployment 생성.

## Business Rules and Invariants

SP-01~SP-15와 ADR-081/082/084~089의 Accepted decisions를 유지한다. Proposed ADR-083/090을 승인된 것으로
추정하지 않는다. Owner Context만 자신의 상태와 data를 변경하며 partial/unknown은 terminal success가 아니다.

## Architecture and Transaction Boundaries

각 future Stage는 owner-local transaction과 cross-context intent/result 경계를 상세화해야 한다. External
Provider call은 long DB transaction 밖에서 실행하고 durable intent와 reconciliation을 갖춘다. Orchestration
자체는 transaction을 만들지 않는다.

## Alternatives Considered

- 모든 future Stage plan을 지금 작성: current model과 predecessor outcome을 추측하게 되어 제외.
- one large implementation plan: migration/API/owner boundaries와 검증 단위를 과도하게 결합해 제외.
- 모든 Stage를 직렬 dependency로 고정: 직접 output을 소비하지 않는 branch까지 fake dependency가 되어 제외.

## Failure Semantics

Predecessor가 완료돼도 model/API/decision gap이 남으면 successor plan을 만들거나 ready로 표시하지 않는다.
Migration lease 미획득은 dependency failure가 아니라 execution preflight failure다. Incomplete Stage는 target/runtime
OpenAPI 또는 UI success path에 노출하지 않는다.

## Data and Migration

Writes-Migration Stage는 실제 시작 시 ADR-072 lease를 획득하고 latest main의 마지막 Flyway 번호 다음을
선택한다. Active/ready metadata, scheduling priority 또는 released lane은 lease evidence가 아니다. 같은 시점에
하나의 holder만 허용한다.

## API and Event Contracts

`docs/api/support-api-surface.md`의 row는 future contract input이다. Owning Stage는 endpoint-specific request,
response/page/error/security와 필요 시 cursor amendment를 만든다. Runtime spec은 Controller/contract/parity test가
있는 operation만 포함한다.

## Milestones

### Completed Stage

- **S10 — Retention/Audit/permission foundation:** 기존 financial Audit 5년 보존, immutable retention policy
  version과 exact Support permission grant vocabulary를 V39로 구현·검증했다. Runtime Support endpoint는 없다.

### Completed detailed Stages

- **S20 — SupportCase foundation:** lightweight Case lifecycle, append-only assignment/state history, bounded
  interaction/note와 identifier-only subject link. exact Case policy, V40 migration, target/runtime Case API와 full
  Testcontainers/contract/architecture evidence가 완료됐다. terminal-Case DataAccessGrant 안전성은 S40 scope다.

### Future Stage summaries and authoring gates

| Stage | Future outcome | Direct inputs required before detailed plan authoring | Known gate |
|---|---|---|---|
| S30 | owner profile + protected exact masked search | completed S20 Case boundary and Accepted ADR-083 | customer/contact/crypto model gap |
| S40 | purpose-bound verification, grant and reveal | completed S20 Case boundary and S10 Audit/permission | challenge Provider contract and atomic terminal-Case Grant revocation/activation-denial design not chosen |
| S50 | bounded timelines and typed ActionPolicy | completed S30 masked owner DTO and S40 verification/grant | endpoint-specific cursor contract required |
| S60 | immutable revisions, sequential approval, Operations investigation/reassignment | completed S50 action evaluation | actor separation DB model required |
| S70 | lifecycle-aware cancellation and atomic pickup reschedule | completed S60 approval/execution lineage | owner typed commands and state-race contract required |
| S80 | post-acceptance resolution with partial/unknown outcomes | completed S70 owner command outcomes | responsibility/step persistence required |
| S90 | versioned goodwill compensation | completed S60 approval/investigation foundation | policy/bucket/cost-owner schema required |
| S100 | R0-R4 purpose-specific profile change | completed S60 approval plus actual S30 owner models | customer/legal/payout/rider models incomplete |
| S110 | canonical DeliveryFulfillment, Provider inbox/reconciliation | completed relevant S80/S90/S100 owner contracts only | Provider selection/auth contract and Delivery module absent |
| S120 | LegalHold and component deletion automation | completed owner retention ports from S20~S110 | legal review and backup replay procedure required |
| S130 | Support Console | implemented server contracts needed by selected UX and Accepted ADR-090 | credential/CORS/CSRF/trust boundary open |
| S140 | integrated security/resilience/retention/UI/load evidence | completed implemented capability set | environment/fixture/release owner must be fixed |

This table is not a canonical `Depends-On` graph for files that do not yet exist. Each detailed plan records only the
actual producer outcomes it directly consumes when authored.

## Required Tests

Every future detailed plan must name exact Domain/Application/PostgreSQL/API/Modulith/security/failure tests relevant to
its own slice. No generic test paragraph is inherited from this orchestration file.

## Validation Commands

- `./scripts/verify-docs.sh`
- `git diff --check`
- future implementation plans add only commands that exist at authoring time

## Observability

This plan records stage status, evidence and unresolved gates only. It does not invent endpoint/table/metric names before
implementation.

## Documentation Updates

After each Stage completion, update this table with actual outcome and create only the now-eligible successor plan from
latest main. Update target/runtime OpenAPI, ADR/Business Policy and operational evidence only to match implementation.

## Progress

- [x] S20~S140 placeholder plans removed
- [x] S10 implemented, fully validated and moved to completed
- [x] S00 repair completed and S10 readiness recalculated
- [x] direct successor S20 detailed plan authored from S10 actual outcome; initial Case policy accepted and readiness true
- [x] S20 execution preflight/sole migration-writer lease, V40 implementation and focused evidence completed
- [x] S20 full validation, completion move, migration-writer lease release and successor readiness handoff
- [ ] S30 detailed plan authoring — blocked until ADR-083 is Accepted and customer/contact/crypto owner model exists

## Surprises & Discoveries

The original 13 successor files repeated the same architecture, failure, validation and outcome shell despite different
owner models. File presence therefore overstated execution readiness.

S10's actual outcome confirmed that policy rows and permission vocabulary can precede Support runtime safely only when
documents mark them dormant. S20 therefore consumes the foundation without treating grants as released capabilities.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-10 | Plan boundary | author future plans after predecessor outcome | avoid stale implementation guesses | this plan |
| 2026-08-10 | Scheduling | S10 and Analytics have no direct dependency edge | lease order is a separate product priority | ADR-072/091 |
| 2026-08-11 | Stage handoff | complete S10 and author direct successor S20 | V39 outcome plus initial Case policy enable the Case foundation | completed S10, active S20 |
| 2026-08-11 | S20 scope | keep DataAccessGrant outside S20 and assign terminal-Case Grant revocation/activation denial to S40 | no Grant Aggregate exists in S20; future grant safety must be fail-closed | SupportCase Policy, SP-16 |
| 2026-08-11 | Stage handoff | complete S20 and retain S30 as not-ready | V40/full validation is complete, but ADR-083 and the owner model remain unavailable | completed S20, ADR-083 Proposed |

## Outcomes & Retrospective

S10 foundation and the S20 runtime Case capability are complete and validated. This plan remains active while later
approved stages are delivered and capability-specific release evidence is accumulated. S30 is not ready to author; its
ADR-083 and owner-model gate prevents a speculative successor plan.

## Revision Notes

- 2026-08-10: consolidated S10~S140 graph and removed placeholder implementation files.
- 2026-08-11: recorded completed S10 outcome and created the S20 direct successor with readiness false, without acquiring
  its migration lease or reserving a Flyway number.
- 2026-08-11: completed S20 V40/Case API validation and moved its plan to completed; the migration-writer lease is
  released. S30 remains un-authored because ADR-083 and the customer/contact/crypto owner model are not Accepted.
