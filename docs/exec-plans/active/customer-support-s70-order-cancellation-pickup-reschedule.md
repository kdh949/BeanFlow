# 상담원 주문 취소와 픽업 예약 변경을 owner command로 실행한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s60-approval-operations-investigation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과와 명령 증거로 계속 갱신한다.

## Purpose / Big Picture

S50은 상담 작업을 advisory policy로 평가하고 S60은 exact revision에 묶인 승인·Operations 조사와
명시적 executor를 만들었지만, `READY_FOR_EXECUTION` 뒤 owner Aggregate를 변경하는 경로는 없다.
S70은 상담원이 고객 API를 가장하지 않고 전용 Support execution contract를 통해 다음 작업을 실행하게
한다.

- `PENDING_PAYMENT`, `PAID`, 조건부 `ACCEPTED` 주문 취소
- 같은 주문 사실을 유지하면서 픽업 시간만 바꾸는 예약 변경
- `ACCEPTED → PREPARING` race에서 최신 owner 상태에 따른 resolution-required 전환
- 로컬 owner 변경 뒤 환불의 `PROCESSING | UNKNOWN | RECONCILING` 상태 노출

완료 결과는 “승인 준비 완료”가 아니라 Ordering/Fulfillment owner가 최신 잠금 상태와 DB 불변식을
검사해 변경을 commit하고 Support request가 그 exact owner outcome을 한 번만 기록하는 것이다.

## Current State

- 현재 branch는 S60 verified head `065dae69842d4545b465ccd7032efce00c46a478`에서 분기했다.
- Flyway inventory는 V1~V44이고 마지막은
  `V44__create_support_action_approval_and_investigation.sql`이다.
- S60 `SupportActionRequest`는 immutable revision, payload/evidence digest, verification, policy,
  target version, executor와 approval lineage를 보호하지만 실행 terminal state와 outcome은 없다.
- S50 ActionPolicy는 PENDING_PAYMENT/PAID cancel·reschedule을 BASIC `ALLOWED`, ACCEPTED를
  ENHANCED `APPROVAL_REQUIRED`로 평가하며 실행 시 재평가를 요구한다.
- 기존 customer cancellation은 PENDING_PAYMENT/PAID에서 Order row를 잠그고 예약·재고·혜택 release,
  payment snapshot/refund intent, compensation case와 event를 한 transaction에 만든다. Customer actor
  authorization과 `CUSTOMER_REQUEST` cause에 고정돼 Support가 재사용할 public command는 없다.
- `PickupReservationOperations.reserve`는 order당 reservation 하나를 강제하고 기존 reservation이
  있으면 거부한다. slot swap, reservation version, reschedule history가 없다.
- `OrderEntity.pickupSlotId`는 immutable field이며 owner reschedule transition이 없다.
- Productization PR #57은 commit `8aa3704014c0943aa7e80e8205c007caaf3a28d2`에서 Plan 20
  readiness를 해제하고 S70~S100에 migration writer를 양보했다. Productization V43/V44는 별도 Draft
  branch의 산출물이고 Support stack과 병합하지 않는다.

## Resolved Implementation Gate

사용자는 2026-08-12에 권장안을 선택했다. SP-19/ADR-085가
`support-order-change-policy/2026-08-12/v1` 아래 다음 값을 소유한다.

- ACCEPTED cancellation delegation: 10분, successful owner execution 1회
- ACCEPTED pickup reschedule delegation: 30분, successful owner execution 3회
- `now >= expiresAt` expiry, exact idempotent replay는 추가 소비 없음
- exact confirmation은 request/revision/action payload digest/target version/request expiry bound
- store actor가 STORE 비용 책임을 명시 수락하며 unknown/PLATFORM 책임은 S80 resolution

정책, ADR, migration lane과 predecessor가 모두 확정돼 `Implementation-Ready=true`다.

## Business Rules and Domain Invariants

1. Controller는 Repository를 호출하지 않고 Support Application Service가 use case와 transaction을
   조정한다.
2. execution은 current executor만 수행하며 requester/Support approver/Operations approver와 분리한다.
3. `SUPPORT_ACTION_EXECUTE`와 action별 persistent capability, active Case assignment/link,
   action-bound verification, exact revision/payload digest/policy/target version을 모두 다시 확인한다.
4. client가 role, permission, decision, policy version, current Order state 또는 cost owner를 주장하지
   못한다.
5. 같은 actor+operation+Idempotency-Key+canonical payload는 같은 terminal outcome을 replay하고 같은
   key의 다른 payload는 409다.
6. PENDING_PAYMENT 취소는 Order와 예약·재고·쿠폰·포인트 자원을 같은 transaction에서 해제하며
   하나라도 실패하면 전부 rollback한다.
7. PAID 취소는 기존 refund/restoration/settlement exclusion 흐름을 owner command로 재사용하고 누적
   성공 refund가 approved amount를 넘지 않게 Payment owner가 잠금 아래 검증한다.
8. ACCEPTED 취소·예약 변경은 exact request confirmation 또는 active versioned delegation과 명시적
   cost responsibility가 없으면 거부한다.
9. PREPARING/READY/COMPLETED는 직접 cancel/reschedule하지 않는다. ACCEPTED lock 대기 뒤 최신 상태가
   PREPARING이면 어떤 rollback도 하지 않고 `RESOLUTION_REQUIRED` outcome을 남긴다.
10. pickup reschedule은 새 slot capacity를 먼저 확보하고 같은 transaction에서만 기존 slot count를
    해제한다. 새 slot 실패 시 기존 예약과 Order는 변하지 않는다.
11. PAID/ACCEPTED reschedule은 store, line/item/option/quantity와 amount를 바꾸지 않고 slot/time만
    바꾼다. 다른 변경은 취소+재주문이다.
12. Ordering과 Fulfillment owner가 최신 state/version을 최종 검증하며 Support approval은 owner
    불변식을 우회하지 않는다.
13. external Provider를 local DB transaction 안에서 호출하지 않는다. refund intent까지 commit한 뒤
    worker가 Provider를 호출하고 timeout/ACK loss는 `UNKNOWN` 또는 `RECONCILING`이다.
14. Audit commit 실패는 owner change와 Support execution outcome 전체를 rollback한다.
15. notification failure는 확정된 owner change를 rollback하지 않고 durable retry/manual-review 상태로
    남긴다.
16. raw reason, 고객 PII, payment credential, provider token을 log/metric/Audit/idempotency response에
    넣지 않는다.

## Affected Modules and Aggregates

- Support: `SupportActionRequest`, 새 `SupportActionExecution`, store confirmation/delegation
- Ordering: `Order`, customer cancellation owner core, cancellation/reschedule history
- Fulfillment: `PickupReservation`, `PickupSlot`, atomic slot swap
- Payment: cancellation refund snapshot/intent와 durable projection
- Loyalty/Promotion/Inventory: 기존 release/restoration owner ports
- Settlement: Support cancellation cause와 responsibility evidence
- Merchant: store actor membership 확인 public API
- Operations: persistent permission과 PII-free Audit
- Notification: 확정 변경 이후 독립 notification intent/worker

## Files Likely to Change

- `src/main/kotlin/.../support/internal/domain/SupportActionRequest.kt`
- `src/main/kotlin/.../support/internal/SupportActionRequestApplicationService.kt`
- `src/main/kotlin/.../support/internal/SupportActionRequestController.kt`
- `src/main/kotlin/.../support/internal/SupportActionRequestPersistence.kt`
- 새 Support order-change authorization/execution domain·persistence·service
- `src/main/kotlin/.../ordering/api/`의 Support cancellation/reschedule owner contract
- `src/main/kotlin/.../ordering/internal/CustomerCancellationService.kt`
- `src/main/kotlin/.../ordering/internal/OrderingPersistence.kt`
- `src/main/kotlin/.../fulfillment/api/PickupReservationOperations.kt`
- `src/main/kotlin/.../fulfillment/internal/PickupReservationService.kt`
- `src/main/kotlin/.../fulfillment/internal/PickupReservationPersistence.kt`
- `src/main/kotlin/.../payment/api/CustomerCancellationPaymentOperations.kt`와 owner implementation
- `src/main/resources/db/migration/V45__create_support_order_change_execution.sql`
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml`
- 관련 Support/Ordering/Fulfillment/Payment PostgreSQL/API/architecture tests

## Schema and Migration-writer Lease

- `Writes-Migration=true`.
- 현재 Support stacked schema의 마지막 migration은 V44다.
- 사용자는 Productization보다 Support를 우선한다고 결정했다.
- Productization PR #57의 ADR-111/orchestration/Plan 20은 commit `8aa3704`에서 lease 해제,
  `Implementation-Ready=false`, S100 뒤 재번호화 gate를 기록했고 docs validation이 통과했다.
- S70은 sole writer로 V45를 사용한다. 다른 schema writer가 나타나면 구현을 중단한다.
- V45는 Support execution/authorization, Ordering cancellation/reschedule evidence, Fulfillment
  reservation version/history와 필요한 closed constraint를 한 forward-only migration에서 추가한다.
  구현 결과가 이 범위보다 작거나 크면 migration 작성 전에 이 plan을 갱신한다.

## Alternatives Considered

### 고객 취소 API를 Support가 대신 호출

- 장점: 기존 Controller/Service를 그대로 사용할 수 있다.
- 단점: customer ownership을 가장하고 Support permission·verification·approval·Audit 의미를 잃는다.
- 결론: 기각. Support 전용 owner command를 둔다.

### Support가 Ordering/Fulfillment table을 직접 갱신

- 장점: 한 service에서 구현이 짧다.
- 단점: Context ownership과 Aggregate 불변식을 우회하고 향후 owner 변경에 취약하다.
- 결론: 기각. Support는 public owner Application API만 호출한다.

### 기존 slot을 먼저 release한 뒤 새 slot reserve

- 장점: 현재 `reserve` API를 조합하기 쉽다.
- 단점: 마지막 slot 경쟁에서 새 slot을 잃으면 고객의 기존 예약도 사라진다.
- 결론: 기각. Fulfillment owner가 두 slot을 deterministic order로 잠그고 새 capacity를 먼저 차감한다.

### Support와 owner를 두 transaction/saga로 분리

- 장점: Context transaction이 완전히 분리된다.
- 단점: 현재 단일 PostgreSQL/local process에서 owner success와 approval consume 사이에 crash gap이
  생기며 별도 outbox/command inbox durability ADR이 필요하다.
- 결론: 이번 단계에서는 기각. public owner API를 통한 같은 local transaction을 사용하되 외부 PG와
  notification은 분리한다.

### 승인 request 없이 direct order endpoint만 제공

- 장점: API가 단순하다.
- 단점: S50 evaluation/S60 exact revision approval lineage와 stale detection을 우회한다.
- 결론: 기각. execution은 S60 request/revision이 canonical source이며 action-specific payload를 받는다.

## Selected Approach and Rationale

Support는 `POST /support/action-requests/{requestId}/executions`의 one-of typed body를 소유한다.
body는 request revision/version, expected owner version, exact action payload와 structured reason을
포함하고 action type은 request의 immutable type과 일치해야 한다. 서버 canonicalizer가 payload digest를
다시 계산해 S60 revision digest와 비교한다.

`ORDER_CANCELLATION`은 reason code, expected Order version과 ACCEPTED에서 필요한 store authorization
reference를 사용한다. `PICKUP_RESCHEDULE`은 new slot ID, expected Order/reservation version과 같은
authorization reference를 사용한다. 별도 customer identity나 금액, item/store 변경 필드는 받지 않는다.

건별 confirmation/delegation은 Support-owned `SupportOrderChangeAuthorization`으로 기록하되 store actor
membership은 Merchant public API에서 검증한다. 건별 confirmation은 exact request/revision/action payload
digest/target version에 고정한다. delegation은 store/action/policy version/expiry/use budget에 고정하고
owner execution과 같은 transaction에서 successful use를 소비한다.

Ordering public owner service는 Order를 잠그고 expected version과 latest lifecycle을 최종 판정한다.
cancellation은 기존 customer cancellation의 공통 owner core를 추출해 actor/cause/evidence만 typed하게
분리한다. reschedule은 Ordering이 Order invariants를 확인한 뒤 Fulfillment public swap command를 같은
transaction에서 호출하고 Order pickup slot/history를 갱신한다.

Fulfillment swap은 old/new slot UUID를 정렬해 잠그고 reservation state에 맞춰 new slot의 reserved 또는
confirmed count를 먼저 증가시킨 뒤 old count를 감소시킨다. reservation row의 slot/version과 append-only
history는 같은 transaction에서 갱신한다. 같은 execution replay는 stored owner outcome을 반환한다.

Support execution row는 exact request/revision/executor/idempotency/payload digest, owner before/after
version, result, refund projection summary와 occurredAt만 저장한다. raw reason/PII/provider payload는
저장하지 않는다. 성공 뒤 ActionRequest는 `EXECUTED`, PREPARING race는
`RESOLUTION_REQUIRED`, owner 결과를 확정할 수 없는 local durability gap만 `UNKNOWN` 또는
`MANUAL_REVIEW`로 남긴다.

## Transaction Boundaries

### TxE — Support execution + local owner command

1. actor-scoped idempotency advisory lock과 prior outcome replay 확인
2. SupportActionRequest → SupportCase → verification/permission → store authorization 순서로 잠금
3. exact executor/revision/request version/payload digest/policy/expiry/approval separation 재검사
4. Ordering public owner command 호출
5. Ordering은 Order row를 잠그고 latest state/version과 cancellation/reschedule matrix 재평가
6. cancellation이면 owner resource release와 payment refund intent/evidence를 local transaction에 기록
7. reschedule이면 Fulfillment가 deterministic slot locks 아래 atomic swap 후 Ordering history 갱신
8. Operations PII-free Audit, Support execution terminal outcome, authorization use와 idempotency response
   commit

1~8 중 하나라도 실패하면 owner/Support/Audit/idempotency/authorization use가 모두 rollback한다.
Controller는 commit 뒤에만 terminal DTO를 반환한다. owner validation 409는 request를 성공으로
consume하지 않는다. PREPARING race만 제품상 terminal `RESOLUTION_REQUIRED`로 commit한다.

### TxP — Refund Provider

TxE는 Payment refund intent/idempotency key까지만 commit한다. 기존 refund worker가 transaction 밖에서
PG를 호출하고 별도 transaction으로 `SUCCEEDED | FAILED | UNKNOWN | RECONCILING`을 기록한다. Support
조회/응답은 Payment durable projection을 사용하며 timeout을 확정 실패로 바꾸지 않는다.

### TxN — Notification

TxE의 local transaction event/outbox intent 뒤 worker가 provider를 호출한다. provider failure는
`RETRY_SCHEDULED | MANUAL_REVIEW | SENT` 같은 explicit state로 남고 이미 확정된 order change를
rollback하지 않는다.

## Failure Semantics

- invalid/extra/mismatched typed payload: 400
- missing actor authentication: 401
- permission, assignment, relation, executor 또는 store membership mismatch: 403
- request/order/slot/authorization 없음: 404
- same key/different payload, stale request/revision/owner/reservation version, disallowed state, slot full,
  approval/authorization expiry: 409 with stable closed code
- latest state PREPARING: 409-compatible `RESOLUTION_REQUIRED` resource with durable outcome; direct
  cancellation/reschedule side effect 없음
- Audit/permission/owner DB unavailable: 503 and full TxE rollback
- payment provider timeout/ACK loss after TxE: 200/202 owner outcome with explicit refund
  `UNKNOWN | RECONCILING`, never guessed success
- notification failure: owner result remains, notification state remains retry/manual review
- no local/in-memory/fake/no-op/cached fallback

## Security and Privacy Impact

- endpoint는 bearer authentication과 persistent `SUPPORT_ACTION_EXECUTE` plus action capability를
  요구한다.
- actor/requester/approver/Operations reviewer/executor separation을 service와 DB constraints로 보호한다.
- object authorization은 Case assignment, active RELATED_ORDER link, exact Order ID와 store binding을
  검증한다.
- client-supplied role/permission/decision/policy/state/cost-owner는 스키마에서 금지한다.
- payload는 strict DTO와 `additionalProperties: false`, UUID/enum/길이/숫자 bounds로 제한한다.
- idempotency와 authorization reference는 다른 case/order/revision/store/action에서 재사용할 수 없다.
- Audit/idempotency/metric/log는 IDs, closed reason/result/policy version만 사용하고 PII·raw reason·
  payment credential·provider payload를 포함하지 않는다.
- 모든 Support 성공 응답은 `Cache-Control: no-store`다.

## Implementation Plan

### Task 1 — policy와 contract gate 확정

Acceptance:

- 사용자 선택을 BR/ADR-085/order-change policy에 기록한다.
- store confirmation/delegation binding, expiry/use boundary와 cost-responsibility closed vocabulary가
  모순 없이 정의된다.
- 이 plan을 `Implementation-Ready=true`로 전환하고 V45 sole lease evidence를 고정한다.

Verification:

- `bash scripts/verify-docs.sh`
- `git diff --check`

Dependencies: Open decision.

### Task 2 — execution/authorization domain RED tests와 V45

Acceptance:

- ActionRequest terminal execution/resolution states와 one-time transition 단위 테스트가 먼저 실패한다.
- confirmation/delegation exact binding, expiry boundary, use budget/concurrency 테스트가 먼저 실패한다.
- V45가 closed state/cause/responsibility constraints, unique execution, authorization budget,
  reservation version/history를 PostgreSQL에서 강제한다.

Verification:

- `./gradlew test --tests '*SupportActionExecution*' --tests '*SupportOrderChangeAuthorization*'`
- `./gradlew test --tests '*MigrationTest*'`

Dependencies: Task 1.

### Checkpoint A — policy/domain/schema

- domain RED→GREEN evidence
- PostgreSQL migration/constraint tests
- no duplicate migration version
- staged diff/secret scan and atomic commit

### Task 3 — PENDING_PAYMENT/PAID Support cancellation vertical slice

Acceptance:

- Support execution service가 exact S60 binding을 다시 검사하고 Ordering public command만 호출한다.
- PENDING_PAYMENT resource release rollback과 PAID refund/restoration setup이 existing invariants를
  보존한다.
- same key replay, different payload conflict, refund unknown projection이 API test로 증명된다.

Verification:

- `./gradlew test --tests '*SupportOrderCancellation*' --tests '*CustomerCancellation*'`

Dependencies: Task 2.

### Task 4 — atomic pickup reschedule vertical slice

Acceptance:

- PENDING_PAYMENT/PAID의 new-slot-first swap이 old reservation을 실패 시 보존한다.
- same-store/time-only, expected Order/reservation version과 last-slot concurrency를 owner가 검사한다.
- Ordering/Fulfillment history와 Support outcome이 같은 transaction으로 commit/rollback된다.

Verification:

- `./gradlew test --tests '*SupportPickupReschedule*' --tests '*PickupReservation*'`

Dependencies: Task 2.

### Checkpoint B — pre-acceptance owner commands

- cancellation state matrix와 resource rollback
- last slot/concurrent replay
- idempotency/refund projection
- focused integration tests and atomic commits

### Task 5 — ACCEPTED confirmation/delegation과 PREPARING race

Acceptance:

- store actor membership을 owner public API로 검증한 exact confirmation/delegation만 실행에 사용한다.
- approved Support executor, store authorizer와 reviewer separation 및 use-budget concurrency가
  보호된다.
- ACCEPTED lock 경쟁에서 PREPARING winner 뒤 owner side effect 없이
  `RESOLUTION_REQUIRED`가 durable하게 남는다.

Verification:

- `./gradlew test --tests '*AcceptedSupportOrderChange*' --tests '*SupportOrderChangeAuthorization*'`

Dependencies: Tasks 3 and 4.

### Task 6 — runtime API, Audit, notification와 documentation

Acceptance:

- target/runtime OpenAPI에 typed execution과 store authorization operations, closed errors와 no-store
  response가 구현과 일치한다.
- Audit failure rollback, notification independent failure/retry와 PII canary가 통과한다.
- traceability, permission matrix, transaction/invariant/API docs가 IMPLEMENTED evidence로 갱신된다.

Verification:

- `./gradlew test --tests '*SupportActionExecutionOpenApiContractTest' --tests '*RuntimeOpenApiParityTest'`
- `./gradlew test --tests '*Support*Security*' --tests '*Notification*'`
- `bash scripts/verify-docs.sh`

Dependencies: Task 5.

### Checkpoint C — completion

- focused PostgreSQL/domain/API/security/resilience suites
- full `./gradlew test`, `spotlessCheck`, `build`
- docs/OpenAPI validation and `git diff --check`
- self-review against every S70 invariant and required test
- active→completed move, direct successor/readiness and orchestration atomic update
- V45 lease release, push, S60 branch base 상세 PR

## Required Tests

- state matrix: PENDING_PAYMENT, PAID, ACCEPTED, PREPARING, READY, COMPLETED, terminal states
- ACCEPTED/PREPARING lock race with resolution-required outcome
- new-slot-first last-capacity concurrency and old reservation preservation
- actor+operation+key idempotent replay and different-payload 409
- refund `UNKNOWN`/reconciliation projection without guessed outcome
- release/swap/Audit failure transaction rollback
- exact confirmation/delegation binding, expiry boundary, use-budget concurrency
- stale Order/reservation/request/revision/payload/policy cases
- permission revoke, approver execution denial and explicit reassignment
- field/DTO strict validation, stable API errors and no-store headers
- PostgreSQL Testcontainers only; no H2

## Validation Commands

```bash
./gradlew test --tests 'io.github.kdh949.beanflow.support.internal.*'
./gradlew test --tests 'io.github.kdh949.beanflow.ordering.internal.*'
./gradlew test --tests 'io.github.kdh949.beanflow.fulfillment.internal.*'
./gradlew test --tests 'io.github.kdh949.beanflow.payment.internal.*'
./gradlew test --tests 'io.github.kdh949.beanflow.architecture.*'
./gradlew test
./gradlew spotlessCheck
./gradlew build --stacktrace
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
git diff --check
git diff --cached --check
```

각 명령은 exit code, test count/failure/skipped와 핵심 결과를 Progress/Outcomes에 정확히 기록한다.
실행하지 않은 명령은 `Not run`, 실패는 그대로 남긴다. 비교 측정 없이 성능 향상을 주장하지 않는다.

## Documentation Updates

- `docs/product/business-policy-decisions.md`
- `docs/product/support-order-change-policy.md`
- `docs/product/support-action-policy.md`
- `docs/adr/ADR-085-lifecycle-aware-support-order-resolution.md`
- `docs/architecture/support-aggregate-invariants.md`
- `docs/architecture/support-transaction-boundaries.md`
- `docs/architecture/support-requirement-traceability.md`
- `docs/security/support-role-permission-matrix.md`
- `docs/api/support-api-surface.md`
- `docs/api/error-catalog.md`
- `docs/testing/support-test-strategy.md`
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml`
- `docs/exec-plans/active/customer-support-program-orchestration.md`

새 장기 구조 결정이 생기면 Accepted ADR을 먼저 개정한다. 현재 예상 구조는 ADR-084/085의 public
owner command와 same-local-transaction 규칙 안에 있으므로 신규 ADR은 계획하지 않는다.

## Progress

- 2026-08-12: S60 head `065dae6`에서
  `feature/support-order-cancellation-pickup-reschedule` branch를 생성했다.
- 2026-08-12: 사용자가 Support migration 우선권을 선택했다. Productization PR #57에 commit
  `8aa3704`를 push해 Plan 20 readiness/lease를 해제하고, 상세 PR 본문과 docs validation을 확인했다.
- 2026-08-12: current code/schema/OpenAPI를 재검사하고 V44 다음 V45 sole writer 범위, owner command,
  new-slot-first transaction, refund/notification failure semantics와 test slices를 이 plan에 기록했다.
- 2026-08-12: 사용자가 권장 delegation policy를 선택했다. SP-19/ADR-085/order-change policy에
  cancellation 10분/1회와 reschedule 30분/3회, exact confirmation, STORE 책임과 S80 fallback 금지를
  기록하고 `Implementation-Ready=true`로 전환했다.

## Surprises & Discoveries

- Productization Draft branch도 V43/V44를 사용하고 있었지만 Support stack의 V43/V44와 서로 다른
  migration이다. 사용자가 Support를 우선해 Productization lease/readiness를 명시적으로 해제했다.
- 기존 CustomerCancellation flow는 Payment/Loyalty/Promotion/Inventory/Settlement semantics가 이미
  강하지만 customer authorization과 cause에 결합돼 있다. 복사보다 owner core를 typed initiator로
  추출하는 편이 invariant drift를 줄인다.
- Fulfillment reservation은 order당 한 row와 immutable slot ID를 전제로 해 reschedule에 필요한
  version/history/atomic swap이 새 owner capability다.

## Decision Log

| 일자 | 결정 | 근거/기록 위치 |
|---|---|---|
| 2026-08-12 | Productization보다 Support S70~S100 migration을 우선 | 사용자 결정, Productization ADR-111 commit `8aa3704` |
| 2026-08-12 | S70은 Support stacked V44 다음 V45 sole writer | 이 plan, S60 completed outcome |
| 2026-08-12 | Support는 customer API를 가장하지 않고 public owner command만 호출 | ADR-081/084/085 |
| 2026-08-12 | 새 slot capacity를 먼저 확보하고 old slot은 같은 transaction에서만 해제 | ADR-085, support order-change policy |
| 2026-08-12 | ACCEPTED cancellation은 10분/1회, reschedule은 30분/3회; exact replay는 추가 소비하지 않고 committed direct change만 소비 | SP-19, ADR-085 |
| 2026-08-12 | confirmation/delegation은 STORE 책임 명시 수락만 허용하고 unknown/PLATFORM 책임은 S80로 전달 | SP-19, ADR-085 |

## Outcomes & Retrospective

아직 완료되지 않았다. V45/domain/API 구현, focused/full validation, atomic
completion/readiness handoff와 S60 base PR이 남아 있다.

## Revision Notes

- 2026-08-12: initial S70 preflight, migration lease transfer와 implementation gate 기록.
