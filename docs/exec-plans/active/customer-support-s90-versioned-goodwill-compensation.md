# 위험도와 immutable 정책에 묶인 goodwill compensation을 발급한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s60-approval-operations-investigation.md`, `docs/exec-plans/completed/customer-support-s80-post-acceptance-resolution.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 Progress, discoveries, decisions와 실제 validation evidence를
계속 갱신한다.

## Purpose / Big Picture

환불·원혜택 복구·원장 correction과 분리된 고객 불편 goodwill을 immutable policy와 exact Support approval lineage로
평가하고, 한 request에서 Point 또는 Coupon 하나만 발급한다. LOW 정상 상담은 빠르게 처리하고 MEDIUM은 Support Manager,
HIGH/EXCEPTIONAL은 Operations 조사로 보내며, 실행 순간 rolling limit·중복 사건·권한·verification을 다시 검사한다.

## Current State

- branch는 S80 verified head `1ca19f46bf71eb22d7da459dcc424b7784779010`에서 분기한
  `feature/support-versioned-goodwill-compensation`이다.
- Flyway inventory는 V1~V47이다. V46 S80 writer lease는 full validation 뒤 release됐고 S90이 V47을 작성했다.
- S60은 dormant Operations route와 exact revision/callback을, S80은 refund/restoration/goodwill source 분리를 제공한다.
- target/runtime OpenAPI에는 S90의 5개 operation까지 75 paths/79 operations/212 schemas가 동일하게 반영됐다.
- 2026-08-12 사용자가 SP-21의 보수적 차등 rolling hard cap을 선택했다.
- Analytics active plan은 ready metadata만 있고 branch/PR/acquisition evidence가 없다. Productization worktree의 schema
  draft는 S70 scheduling decision으로 동결됐다. 이 branch가 2026-08-12 S90 V47 sole migration-writer lease를 획득한다.

## Definitions

- **Goodwill:** refund/restoration/correction이 아닌 고객 불편에 대한 별도 benefit source.
- **Policy version/head:** immutable band, verification, approval, limits와 template/expiry 설정 및 현재 version pointer.
- **Compensation request:** Case, customer, incident, optional Order/Store, one benefit, exact policy/cost/action revision snapshot.
- **Terminal incident key:** 동일 customer+incident가 성공 benefit을 둘 이상 갖지 못하게 하는 lifetime unique fact.
- **Rolling consumption:** scope/window 안에서 terminal 발급한 immutable amount row.
- **Funding leg:** POINT 비용 책임별 별도 PointLot/transaction. SHARED는 정확히 두 leg다.

## Scope

### In Scope

- immutable Support compensation policy version/head와 seeded v1
- typed evaluation/create/get/execute/notification-retry no-store API and target/runtime parity
- S60 GOODWILL_COMPENSATION typed action target, manager/Operations route/reassignment/one-time execution
- actual rolling scopes, terminal incident uniqueness and execution concurrency
- Loyalty SUPPORT_COMPENSATION PointLot/transaction/funding legs
- Promotion immutable fixed coupon template and goodwill issuance using redemption-time cost snapshot
- durable independent Notification request/retry visibility
- V47 DB constraints/indexes, Audit/security/failure/concurrency tests and documentation

### Non-goals

- refund/restoration/correction reuse, free-form coupon terms, generic rules engine, balance direct update
- Operations direct issuance or payload editing, unidentified cost-owner fallback
- actual cash settlement at issuance, new UI, external notification provider integration

## Business Rules and Invariants

SP-21/ADR-086을 따른다. Band는 LOW<=3k, MEDIUM<=10k, HIGH<=30k, 그 초과·무주문·중복·비용 미확정은
EXCEPTIONAL이다. LOW BASIC/NONE, MEDIUM BASIC/SUPPORT_MANAGER, HIGH/EXCEPTIONAL ENHANCED/OPERATIONS route다.
Terminal duplicate는 DENIED이고 `UNDETERMINED` investigation은 발급 가능 책임으로 바뀌지 않는다.

Hard cap은 CUSTOMER 30d/30k, ORDER 30d/30k, INCIDENT 30d/30k+lifetime one terminal, ACTOR 1d/100k,
STORE 1d/300k다. 동일 request replay는 consumption/benefit을 늘리지 않는다. Policy head 변경은 새 평가/요청에만
적용하고 기존 request는 snapshot version으로 재현한다.

POINT/Coupon은 동시에 존재할 수 없다. STORE/SHARED evidence basis+digest가 필수이고 SHARED bps는 positive 두 leg의
합 10,000이다. Coupon은 approved template exact amount만 허용하고 order/store 없는 request에는 사용할 수 없다.

## Architecture and Transaction Boundaries

Controller는 Application Service만 호출한다. Evaluation은 permission/Case/verification/owner facts/policy/rolling snapshot을
읽는 advisory path다. Create transaction은 current head와 exact facts를 재평가하고 CompensationRequest, S60 typed revision,
필요한 Operations investigation, idempotency와 PII-free Audit를 commit한다.

Execute transaction은 action request→Case→compensation request→canonical limit scopes→owner account/template 순서로 잠근다.
Loyalty/Promotion public API가 shared local transaction에 참여해 owner invariant를 최종 검증한다. terminal incident,
consumptions, owner result, Support/S60 terminal state와 Audit 중 하나라도 실패하면 전체 rollback한다. 외부 호출은 없다.

발급 commit 뒤 outer Application Service가 Notification REQUIRES_NEW owner API를 호출한다. Notification persistence failure는
Support request를 `NOTIFICATION_RETRY`로 남기며 benefit을 rollback하지 않는다. Notification bounded worker는 같은 logical source를
재사용한다.

## Alternatives Considered

- PointAdjustment/restoration reuse: source/audit/cost 의미가 틀려 제외.
- mutable policy row: 과거 요청 재현이 깨져 제외.
- fixed calendar bucket: boundary burst가 실제 rolling limit을 우회해 제외.
- Support owner-table write: Context ownership을 위반해 public owner API를 선택.
- 새 approval workflow: S60 exact lineage와 중복되므로 typed S60 target reuse를 선택.
- free-form coupon: 조건/비용 오남용 때문에 immutable fixed template를 선택.

## Failure Semantics

400 validation, 403 permission/object scope, 404 owner resource/template, 409 stale/approval/duplicate/limit/cost conflict,
503 DB/Audit/owner dependency다. No-order와 UNDETERMINED는 EXCEPTIONAL로 보이지만 자동 benefit을 만들지 않는다.
Owner result는 shared transaction이라 Support terminal write와 갈라지지 않는다. Notification failure는 별도 retry/manual state다.

## Data and Migration

V47은 policy version/head/limit rule, compensation request/idempotency/limit scope+consumption/terminal incident/notification work,
S60 GOODWILL target/action constraints, Loyalty support funding result, Promotion immutable template/goodwill issuance binding,
Notification template와 Audit action mapping을 추가한다. 기존 V1~V46은 수정하지 않는다.

## API and Event Contracts

- `POST /api/v1/support/cases/{caseId}/compensation-evaluations`
- `POST /api/v1/support/cases/{caseId}/compensations`
- `GET /api/v1/support/compensations/{compensationRequestId}`
- `POST /api/v1/support/compensations/{compensationRequestId}/executions`
- `POST /api/v1/support/compensations/{compensationRequestId}/notification-retries`

Write는 Idempotency-Key가 필수이고 unknown field를 거부한다. Client는 band/decision/route/policy/rolling outcome을 선택하지
않는다. Response는 closed state, policy/approval/result references와 Notification state만 제공하고 raw reason/evidence를
반환하지 않는다. 모든 성공은 no-store다.

## Milestones

1. SP-21/ADR-086/this plan과 V47 lease를 기록한다.
2. RED-GREEN policy band/request/limit domain을 구현한다.
3. V47 persistence와 PostgreSQL immutable/duplicate/rolling constraints를 구현한다.
4. S60 manager/Operations/reassignment lineage를 GOODWILL target에 연결한다.
5. Loyalty Point funding legs, Promotion template/issuance/redemption settlement와 Notification owner path를 구현한다.
6. Support API/OpenAPI/security/failure/concurrency를 완성한다.
7. focused/full/build/docs validation, plan completion/S100 readiness와 stacked PR을 완료한다.

## Required Tests

- LOW/MEDIUM/HIGH/EXCEPTIONAL boundaries, paid-ratio/repeat/store/no-order/UNDETERMINED matrix
- old request policy snapshot after head v2, immutable version/head CAS
- one POINT-or-COUPON DB shape and template exact amount
- same incident sequential/concurrent terminal duplicate
- five rolling scopes `-1ns/at/+1ns`, same request replay and parallel last-cap winner
- manager/Operations self/dual-role/reviewer-execute denial, revoke/stale/reassignment
- POINT PLATFORM/STORE/SHARED legs and Account/Lot/transaction/Audit atomicity
- Coupon template immutability, issuance replay and future Order redemption Settlement cost tie-out
- notification persistence/provider failure independence and bounded retry/manual state
- idempotency same/different payload, Audit/DB failure rollback, PII/no-store/strict OpenAPI
- PostgreSQL Testcontainers, Modulith/ArchUnit and runtime parity

## Validation Commands

- `./gradlew test --tests '*SupportCompensation*' --tests '*GoodwillCompensation*'`
- `./gradlew test --tests '*SupportActionRequest*' --tests '*OperationsSupportInvestigation*'`
- `./gradlew test --tests '*RuntimeOpenApiParityTest' --tests '*ArchitectureTest' --tests '*ModularityTests'`
- `./gradlew --no-daemon spotlessCheck test`
- `./gradlew --no-daemon build`
- `bash scripts/verify-docs.sh`
- `git diff --check`

## Observability

Band/benefit/responsibility/route/outcome과 bounded failure code만 metric dimension으로 허용한다. Actor/Case/customer/order/
incident/store/request/issuance/Lot ID, amount, reason/evidence digest, key는 log/metric에 넣지 않는다. Durable request,
consumption, owner result, Notification work와 PII-free Audit가 운영 evidence다.

## Documentation Updates

Business Policy, ADR-086, compensation policy, aggregate/transaction/capability/traceability/security/test/API/error docs,
target/runtime OpenAPI, orchestration과 이 plan을 actual outcome에 맞게 갱신한다.

## Progress

- [x] mandatory docs/Accepted Support ADR/S00/current S80 code/schema/OpenAPI inspection
- [x] S80 head에서 stacked branch 생성
- [x] user selected initial v1 rolling hard caps
- [x] V47 sole migration-writer lease acquired under Support-priority scheduling
- [x] domain and contract RED tests
- [x] V47 persistence and concurrency constraints
- [x] S60/Operations approval integration
- [x] Loyalty/Promotion/Notification owner issuance
- [x] Support API/OpenAPI/security/failure integration
- [ ] focused/full/build/docs validation and completion handoff

## Surprises & Discoveries

Accepted documents named five rolling scopes but only the LOW customer 30-day 10,000 KRW classifier had a number. That
classifier cannot serve as an execution hard cap without making approved HIGH work impossible. The user therefore selected
the separate conservative SP-21 hard-cap set before implementation.

Promotion CouponIssuance is Campaign-backed. S90 will not weaken that owner contract or put free-form terms in Support;
Promotion materializes an immutable approved template into an issuance-specific campaign/cost snapshot so existing future
reservation and Settlement input paths remain authoritative.

The existing Promotion campaign constraint requires a fixed-amount goodwill campaign to keep `maximum_discount_krw` null;
V47 preserves that owner invariant instead of adding a Support exception. Read permission checks use row locking, so their
Application Service path must retain a write-capable transaction even though the returned resource is read-only.

Notification persistence failure handling must wrap only the post-commit Notification request. Catching a broader Support
state transition failure would incorrectly turn a Support consistency defect into `NOTIFICATION_RETRY`. Review also found
that execution initially re-evaluated an old request against the current head. That contradicted ADR-086 non-retroactivity;
execution now loads the request's immutable policy version while new evaluation/create read the head.

One focused test run failed before test execution with a corrupted Kotlin incremental cache (`EOFException`). A clean
focused run rebuilt the cache and passed; this is recorded as tool-state recovery rather than a product failure.

## Decision Log

| Date | Decision | Rationale | Record |
|---|---|---|---|
| 2026-08-12 | select conservative differentiated rolling hard caps | preserve HIGH investigation while bounding actor/store mass issuance | SP-21, ADR-086 |
| 2026-08-12 | reuse S60 typed action revision/Operations callback | one exact approval truth and reviewer/executor separation | ADR-084/086 |
| 2026-08-12 | acquire V47 on S80 stacked head | V46 released, Analytics has no actual lease, Productization remains frozen by Support priority | this plan |

## Outcomes & Retrospective

Not completed. Record only measured implementation and validation results here.

## Revision Notes

- 2026-08-12: authored from verified S80 head, accepted SP-21 initial limits and acquired V47 lease.
