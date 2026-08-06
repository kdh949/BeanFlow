# PointAccount 지원 조회 vertical slice를 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md`, `docs/exec-plans/completed/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md`, `docs/exec-plans/completed/signed-cursor-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

고객은 자기 PointAccount summary와 append-only point ledger를 조회하고, Platform Operator는
explicit `POINT_ACCOUNT_READ` grant와 접근 사유를 가진 지원 조회만 수행하게 만든다. ledger는
공통 HMAC cursor로 안정적으로 page를 이동하며, 운영자 조회는 대상 AuditRecord가 같은 로컬
transaction에서 commit된 경우에만 body를 반환한다.

완료 후 `GET /point-accounts/{accountId}`는 실제 available balance와 Plan 13의
`recoveryPendingKrw`를 반환하고, `GET /point-accounts/{accountId}/transactions`는
`(occurredAt DESC, transactionId DESC)` 순서로 원장을 반환한다. grant, Audit 또는 DB 장애는
빈 목록·stale data·role-only 성공으로 위장하지 않는다.

## Current State

- target OpenAPI에는 두 GET 계약이 있지만 runtime OpenAPI, Loyalty controller와 query owner는 없다.
- V17의 `loyalty_point_account.recovery_pending_krw`, `PointRecoveryPending`, `ACCRUAL`/
  `RECOVERY` owner transaction과 PostgreSQL tie-out 제약이 completed Plan 13 outcome으로 존재한다.
- `loyalty_point_transaction`에는 account별 최신순 keyset query를 뒷받침하는
  `(point_account_id, occurred_at DESC, id DESC)` index가 없다.
- Plan 11이 `POINT_ACCOUNT_READ` permission vocabulary, grant persistence와 authorization API를
  소유하고 Plan 14는 이를 재정의하거나 migration하지 않는다.
- signed-cursor foundation이 endpoint/filter/sort-bound HMAC codec, key rotation과 20/100 limit을
  소유한다. Plan 14는 별도 base64 또는 unsigned cursor를 만들지 않는다.
- target `PointAccount.updatedAt`은 current Account schema에 원천 시각이 없고 기존 row를 진실하게
  backfill할 수도 없어 ADR-069 amendment에 따라 계약에서 제거한다.

## Definitions

- **Customer read:** 인증 customer의 UUID subject와 PointAccount `customerId`가 같은 조회다.
- **Support read:** Platform Operator role, active `POINT_ACCOUNT_READ` grant, 정규화된
  `X-Access-Reason`, target AuditRecord commit이 모두 필요한 조회다.
- **PointAccount summary:** `accountId`, 실제 `availablePointsKrw`, Plan 13이 유지하는
  `recoveryPendingKrw`, `KRW` currency의 projection이다. reserved balance는 공개하지 않는다.
- **Ledger cursor:** account ID와 endpoint를 filter hash로 bind하고 마지막
  `(occurredAt, transactionId)` tuple을 담은 ADR-070 HMAC token이다.
- **Signed ledger effect:** 저장 magnitude가 아니라 공개 balance 변화량이다. Plan 13 완료 시점의
  type별 부호/0 규칙을 API conventions와 동일하게 투영한다.

## Scope

### In Scope

- Loyalty PointAccount/PointTransaction Query Repository, Application Service와 API DTO projection
- customer ownership 및 Platform Operator support-read authorization branch
- support read의 `X-Access-Reason` validation, grant lock/check와 target Audit commit gate
- `(occurredAt DESC, transactionId DESC)` keyset query, signed cursor adapter와 20/100 page limit
- `loyalty_point_transaction(point_account_id, occurred_at DESC, id DESC)` 조회 index migration
- target/Runtime OpenAPI 정합화, authorization/error/API documentation과 운영 metric
- PostgreSQL Testcontainers, API/security/failure/pagination/실행계획 검증

### Non-goals

- `OperatorPermissionGrant` schema·permission vocabulary·bootstrap migration 또는 role/JWT fallback
- PointAccount/PointLot/PointTransaction write flow, Plan 13 recovery/pending schema 재구현
- point adjustment command와 `ADJUSTMENT` persistence 도입
- issuer reference, raw evidence, idempotency key, internal recovery case/grant state 노출
- cursor 저장소, snapshot pagination, Account `updated_at` column 또는 임의 timestamp backfill

## Business Rules and Invariants

- customer는 자신의 account/ledger만 reason 없이 읽는다. 다른 customer account는 403이며
  Store/Settlement role은 읽을 수 없다.
- support read는 Platform Operator role과 active `POINT_ACCOUNT_READ` grant를 모두 요구한다.
  role, JWT claim 또는 다른 permission을 grant fallback으로 사용하지 않는다.
- operator `X-Access-Reason`은 trim 뒤 1..200자이고 control character를 금지한다. customer branch는
  header가 없어도 되며, operator branch의 missing/blank/invalid reason은 query/Audit 전 400이다.
- support read는 projection과 exactly-one target AuditRecord가 같은 local transaction에서 성공한
  뒤에만 200이다. Audit 실패 후 body를 반환하거나 로그만 남기지 않는다.
- `recoveryPendingKrw`는 Plan 13 Account summary의 실제 non-negative 값이다. Plan 13 outcome 전 0,
  원장 추측 합계 또는 stale cache로 대체하지 않는다.
- ledger order와 cursor predicate는 정확히 `(occurred_at DESC, id DESC)`다. 같은 timestamp는 UUID
  순서로 결정하며 cursor account/endpoint mismatch, tamper, expiry는 query 전 400이다.
- response는 issuer/evidence/key/internal case/grant detail을 포함하지 않는다. `sourceReference`는
  OpenAPI가 정의한 opaque immutable ledger source로만 반환한다.

## Architecture and Transaction Boundaries

- Loyalty Controller는 `PointAccountQueryService`만 호출하고 JPA Repository나 Operations grant
  Repository를 직접 호출하지 않는다.
- customer branch는 read-only transaction에서 account ownership을 확인하고 summary 또는 ledger
  DTO projection을 읽는다. 다른 customer ownership은 repository 결과를 body로 만들지 않는다.
- operator branch는 write-capable local transaction에서 Operations public authorization API로 active
  grant를 확인/잠근 뒤 Loyalty projection을 읽고 `POINT_ACCOUNT_READ` AuditRecord를 저장한다.
  transaction commit 이후에만 Controller가 body를 반환한다.
- grant revoke와 support read는 Plan 11 grant row lock/transaction order로 선형화한다. revoke가 먼저
  commit되면 조회는 403이고, 조회가 먼저 commit되면 해당 Audit과 body만 성공한다.
- ledger query는 `limit + 1`개만 projection하고 다음 row 존재 여부로 cursor를 생성한다. Account나
  모든 PointLot을 객체 graph로 로딩하지 않는다.
- common signed cursor codec과 Operations authorization/Audit public API만 module boundary를 넘는다.
  Loyalty가 Operations 내부 Repository를 참조하거나 cursor secret을 직접 읽지 않는다.

## Alternatives Considered

- Plan 11에 point read 포함: grant foundation과 고객 data projection을 결합하므로 제외한다.
- Plan 13 전에 `recoveryPendingKrw=0` 반환: 미구현 debt를 정상 잔액처럼 보이므로 제외한다.
- migration/query 시각을 `updatedAt`으로 반환: Account 변경 시각이 아니며 과거 row를 복원할 수 없어
  제외하고 target 계약에서 필드를 제거한다.
- permission vocabulary를 Plan 14에서 다시 migration: Plan 11 single owner와 충돌하므로 제외한다.
- index 없는 keyset query: data-scale 비용을 숨기므로 조회 전용 index를 Plan 14가 소유한다.
- offset pagination 또는 endpoint별 base64 cursor: concurrent ledger에서 중복/누락 및 공통 key failure
  drift가 생기므로 제외한다.

## Failure Semantics

- malformed account ID/reason/cursor/limit은 `400 INVALID_REQUEST`이며 repository query와 Audit을
  실행하지 않는다.
- customer ownership mismatch, operator role/grant 부재와 revoked grant는 `403 ACCESS_DENIED`다.
- 인가된 scope에서 account가 없으면 `404 RESOURCE_NOT_FOUND`다. DB 장애를 404로 바꾸지 않는다.
- grant repository/lock, Account/ledger projection, Audit persistence 또는 transaction commit 실패는
  `503 DEPENDENCY_UNAVAILABLE`이며 empty/stale body를 반환하지 않는다.
- cursor invalidity는 400이고 DB timeout은 503이다. 두 실패를 같은 결과로 합치지 않는다.
- 지원 조회 transaction rollback은 Audit을 남기지 않고 200도 반환하지 않는다. 성공 read는 정확히
  한 Audit을 남긴다.

## Data and Migration

Plan 14는 최신 `main`에서 ADR-072 migration-writer lease를 얻은 뒤 새 Flyway 번호를 선택한다.
forward migration은 아래 index 하나만 만든다.

```sql
CREATE INDEX idx_point_transaction_account_occurred_id
    ON loyalty_point_transaction (point_account_id, occurred_at DESC, id DESC);
```

Plan 11이 소유한 permission/grant schema와 vocabulary constraint, Plan 13이 소유한 Account pending
summary·PointRecoveryPending·ledger type/constraint는 변경하지 않는다. PointTransaction column이나
CHECK를 복제하지 않으며 Account `updated_at`을 추가하지 않는다.

query는 첫 page에서 account ID만 filter하고 `ORDER BY occurred_at DESC, id DESC LIMIT :limitPlusOne`을
사용한다. 다음 page는 `occurred_at < :cursorOccurredAt OR
(occurred_at = :cursorOccurredAt AND id < :cursorId)`를 추가한다. cursor timestamp와 UUID는 typed
parameter로 bind하며 문자열 SQL interpolation을 사용하지 않는다.

고정 PostgreSQL fixture에서 index 전후 동일 row count/distribution으로
`EXPLAIN (ANALYZE, BUFFERS)`를 기록한다. 측정 없이 latency 개선이나 index-only scan을 주장하지
않고, planner가 index를 선택하지 않으면 row distribution/query shape를 조사해 actual evidence를
남긴다.

## API and Event Contracts

- `GET /point-accounts/{accountId}`는 `accountId`, `availablePointsKrw`,
  `recoveryPendingKrw`, `currency=KRW`만 반환한다. `updatedAt`과 reserved balance는 없다.
- `GET /point-accounts/{accountId}/transactions`는 `items`와 `PageInfo`를 반환한다. `limit`은 생략 시
  20, 허용 범위 1..100이고 cursor 최대 길이/서명/만료/scope는 ADR-070을 따른다.
- customer와 operator가 같은 endpoint를 사용한다. optional `X-Access-Reason` parameter는 customer
  때문에 OpenAPI 수준에서 optional이지만 operator branch에는 required다.
- PointTransaction 공개 `amountKrw`는 API conventions의 signed effect다. Plan 13 완료 시 존재하는
  type을 완전 매핑하고 unknown type을 0이나 양수 magnitude로 반환하지 않는다. 후속 point adjustment
  plan은 `ADJUSTMENT` persistence를 만들 때 projection mapper와 contract test를 함께 확장한다.
- 이 read slice는 domain/integration event를 발행하지 않는다. target access AuditRecord만 support-read
  transaction에 저장한다.
- implementation과 contract/security/failure tests가 통과한 같은 변경에서 두 endpoint와 필요한
  component를 Runtime OpenAPI에 반영한다.

## Milestones

1. Plan 11/13/signed-cursor actual Outcomes와 completed dependency path를 확인하고 migration-writer
   lease를 얻는다.
2. ledger index migration과 PostgreSQL Query Repository/DTO projection을 구현한다.
3. customer ownership read-only branch와 400/403/404/503 mapping을 완성한다.
4. operator role/grant/reason/projection/Audit commit gate를 한 transaction으로 구현한다.
5. signed keyset cursor와 API contract를 구현하고 target endpoint를 Runtime OpenAPI에 반영한다.
6. 실행계획, 보안/민감정보, Modulith, 전체 build와 문서 검증 evidence를 기록한다.

## Required Tests

- customer own account summary/ledger 200, other account 403, accessible missing account 404
- Store/Settlement role 거부, malformed UUID subject와 unauthenticated 401
- operator role-only, grant-only, wrong permission, revoked grant 403; active role+grant 성공
- operator missing/blank/trim/control-character reason 400이며 projection/Audit 호출 0회
- support read 성공 시 target별 exactly-one Audit, Audit/projection/commit failure 시 503와 Audit/body
  partial success 부재
- grant revoke 대 support read 경쟁에서 commit 순서에 맞는 단일 결과
- 실제 Plan 13 `recoveryPendingKrw`, 음수/양수/0 signed ledger effect와 내부 field 비노출
- omitted/1/20/100/101 limit, empty/single/multi-page, 같은 timestamp UUID tie, cursor account/endpoint
  mismatch, tamper, unknown key, expiry와 2048자 boundary
- static dataset 완주 시 누락·중복 0, page 사이 새 row 삽입이 이미 반환한 row를 중복시키지 않음
- migration index 존재/재실행 실패 의미와 고정 fixture의 `EXPLAIN (ANALYZE, BUFFERS)` actual evidence
- PostgreSQL Testcontainers, MockMvc/OpenAPI target/runtime contract, Modulith boundary와 fallback 부재

## Validation Commands

```bash
./gradlew test --tests '*PointAccount*' --tests '*PointTransaction*' --tests '*OperatorPermission*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

- `beanflow.loyalty.point_account.read.count{actor_type,outcome}`
- `beanflow.loyalty.point_transaction.page.count{actor_type,outcome}`
- `beanflow.loyalty.point_transaction.page.size{actor_type}`

actor type과 closed outcome만 tag로 사용한다. account/customer/operator ID, access reason, cursor,
filter hash, source reference, permission/grant ID와 query coordinate는 log/trace/metric tag에 넣지 않는다.
grant/Audit/DB failure는 structured internal cause와 correlation ID로 운영자가 구분하되 response는 stable
error code만 노출한다.

## Documentation Updates

- ADR-069/070/072 implementation evidence와 dependency completion path
- target/runtime OpenAPI, API conventions, error catalog와 authorization matrix
- Context Map, aggregate invariants, transaction boundaries와 test strategy
- performance measurement plan의 point-ledger query 조건/actual evidence
- 이 ExecPlan의 Progress, Surprises, Outcomes와 completion successor metadata

## Progress

- [ ] Plan 11/13/signed-cursor outcomes와 migration-writer lease
- [ ] ledger index와 Query Repository/DTO projection
- [ ] customer ownership read
- [ ] operator grant/reason/Audit commit gate
- [ ] signed cursor/API/runtime contract
- [ ] performance/security/failure/full validation evidence

## Surprises & Discoveries

- 2026-08-01: target PointAccount의 required `recoveryPendingKrw`는 current schema에 없고 Plan 13이
  소유하므로 Plan 13을 direct dependency로 추가했다.
- 2026-08-01: target `updatedAt`은 current Account schema와 진실한 legacy backfill source가 없어
  migration/query 시각 fallback 대신 계약에서 제거했다.
- 2026-08-01: permission migration은 Plan 11이 소유하지만 ledger keyset index는 어디에도 owner가
  없어 Plan 14의 `Writes-Migration=true` 근거로 확정했다.
- 2026-08-02: Plan 13 V17/owner flow와 205-test outcome이 completed path로 이동해 마지막 direct
  dependency가 닫혔다. 구현 시작 시 새 ADR-072 migration-writer lease를 별도로 획득해야 한다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | support read를 독립 vertical slice로 분리 | authorization foundation과 customer data projection을 분리 | ADR-069 |
| 2026-08-01 | Accepted | Plan 11, Plan 13과 signed cursor를 direct input으로 사용 | grant, 실제 pending summary와 cursor contract가 모두 필요 | ADR-069, ADR-072 |
| 2026-08-01 | Accepted | `updatedAt` 제거, `recoveryPendingKrw` 실제 summary 유지 | 근거 없는 timestamp/0 fallback 방지 | ADR-065, ADR-069 |
| 2026-08-01 | Accepted | permission migration은 Plan 11, ledger 조회 index는 Plan 14 소유 | migration ownership 모순 제거와 keyset query 보강 | ADR-069, ADR-072 |

## Outcomes & Retrospective

미구현 상태지만 Plan 11 grant, Plan 13 실제 pending/ledger와 signed-cursor가 모두 verified completed
path에 있어 `Implementation-Ready=true`다. Plan 13의 actual source는 Account summary,
PointRecoveryPending과 PointTransaction `ACCRUAL|RECOVERY`이며 Plan 14는 이를 변경하지 않고 query
projection/index만 소유한다. 구현 시작 전 latest main과 단일 migration-writer lease를 다시 확인한다.

## Revision Notes

- 2026-08-01: 기존 Plan 10의 point-account read scope를 분리했다.
- 2026-08-01: Plan 13 dependency, target `updatedAt` 제거, ledger index ownership과 전체
  architecture/failure/test contract를 확정해 self-contained ExecPlan으로 승격했다.
- 2026-08-02: completed Plan 13 V17/owner outcome을 반영해 direct dependency와 readiness를 갱신했다.
