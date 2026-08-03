# 감사형 Loyalty 포인트 조정 foundation을 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md`, `docs/exec-plans/completed/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md`, `docs/exec-plans/completed/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

운영자가 확인된 포인트 원장 불일치를 직접 DB 변경 없이 안전하게 바로잡을 수 있게 한다.
양수 조정은 입력한 issuer와 미래 만료일의 PointLot을 만들고, 음수 조정은 유효한
available Lot을 결정적으로 차감한다. 어느 방향이든 append-only `ADJUSTMENT` transaction,
target AuditRecord, IdempotencyRecord와 최초 응답이 함께 저장되어 고객 잔액, 비용 귀속과
운영 감사가 재현 가능해야 한다.

## Current State

- OpenAPI `PointTransaction` enum에는 `ADJUSTMENT`가 있으나 의미·write API·DB direction
  표현이 없다.
- Kotlin `PointTransactionType`과 migration type CHECK에는 `ADJUSTMENT`가 없다.
- PointLot persistence에는 아직 BR-20 issuer type/reference snapshot이 없다. Plan 10이
  만료 부분 환불 compensation에 필요한 schema와 legacy issuer precheck/migration gate를
  먼저 소유하며, 이 계획은 그 gate evidence를 선행조건으로 소비한다.
- AuditRecord, Idempotency-Key와 PointAccount row lock의 공통 규칙은 존재하지만 manual
  point adjustment command는 구현되지 않았다.
- Loyalty에는 이 terminal command의 최초 `201` body를 90일 보존할 idempotency table이나
  Context-owned retention worker가 없다. Ordering의 retention worker를 다른 Context table에
  재사용할 수 없다.
- completed Plan 13 V17은 `ACCRUAL`/`RECOVERY`, PointRecoveryPending과 PointTransaction base
  contract를 구현했다. 이 계획은 해당 CHECK를 재구현하지 않고 후속 `ADJUSTMENT` vocabulary만
  단일 migration-writer lease에서 확장한다.
- current JWT role은 인증의 coarse gate일 뿐 `POINT_ADJUSTMENT` permission source가 아니다.
  Plan 11이 ADR-069의 Operations `OperatorPermissionGrant`와 public authorization API를 먼저
  구현해야 한다.

## Definitions

- **Point adjustment:** 운영자의 사유와 증빙에 근거한 signed `ADJUSTMENT` correction.
  환불, 일반 적립, PointRecoveryPending 또는 SettlementAdjustment가 아니다.
- **Credit adjustment:** 양수 effect. 입력 issuer snapshot과 future expiry를 가진 새
  PointLot 하나를 만든다.
- **Debit adjustment:** 음수 effect. account의 미예약 available PointLot만
  `expiresAt > now`와 `(expiresAt, pointLotId)` 순서로 줄인다.
- **Issuer snapshot:** `PLATFORM|BRAND|STORE` type과 non-blank immutable reference.
  Platform Operator가 command에서 명시하며 server가 추론하지 않는다.
- **Balance effect:** storage magnitude와 분리한 `CREDIT|DEBIT|NONE` direction. 공개
  PointTransaction `amountKrw`의 sign 원천이다.
- **Adjustment command source:** Idempotency scope와 canonical payload에서 만든 immutable
  command identity. Audit/outbox는 이 source를 사용하고, Lot별 PointTransaction은 command
  source와 affected Lot을 결정적으로 묶은 opaque child source를 사용한다.

## Scope

### In Scope

- `POST /operations/point-accounts/{accountId}/adjustments` OpenAPI, Controller,
  Application Service와 ADR-069 grant authorization
- PointAccount row lock 기반 명령 transaction idempotency와 stored 201 response
- `loyalty_point_adjustment_command_idempotency`와 90일 keyset retention worker
- Plan 10 issuer snapshot precheck/migration evidence 확인, PointTransaction
  `ADJUSTMENT`/balance_effect persistence, target AuditRecord와 PointsAdjusted outbox event
- 양수의 new Lot/credit transaction과 음수 FIFO Lot debit/transaction
- Plan 10의 existing PointLot issuer data precheck와 non-guessing migration gate 검증
- DTO projection의 signed adjustment amount, `PointsAdjustedV1` producer/outbox와 producer contract test

### Non-goals

- SettlementAdjustment, 환불 `RECOVERY`, PointRecoveryPending 또는 일반 `ACCRUAL`의
  의미·상태 변경
- 고객·매장 직원·Settlement Operator의 point adjustment 권한
- 금액 구간별 2인 승인, 외부 ticket/provider 호출, 고객 알림
- issuer reference의 별도 master registry 또는 non-expiring Lot 정책
- 기존 unresolvable Lot issuer를 PLATFORM으로 추정하는 backfill

## Business Rules and Invariants

- active `PLATFORM_OPERATOR`의 Operations-backed explicit `POINT_ADJUSTMENT` grant, reason,
  evidenceReferences와 Idempotency-Key가 없으면 command를 시작하지 않는다. JWT role 또는
  permission claim은 grant query failure/absence의 fallback이 아니다.
- amount는 signed nonzero다. credit에는 issuer와 `expiresAt > now`가 반드시 있고,
  debit에는 issuer/expiry가 존재하면 안 된다.
- credit은 하나의 새 Lot과 CREDIT `ADJUSTMENT`; debit은 하나 이상의 기존 Lot과 DEBIT
  `ADJUSTMENT`를 만든다. 모든 transaction magnitude 합의 signed effect가 요청 amount와
  일치한다.
- Account and Lot available/reserved 잔액은 음수가 될 수 없고 debit은 reserved 금액을
  건드리지 않는다. expiration worker 지연과 무관하게 `expiresAt <= now` Lot은 debit
  후보가 아니다.
- caller가 명시한 issuer snapshot은 immutable하고 이후 point 사용의 BR-20 비용 귀속
  입력이다. actor/customer/current Lot에서 issuer 또는 expiry를 추론하지 않는다.
- AuditRecord와 PointsAdjusted는 command source 하나를 사용하며, Lot별 transaction은
  unique child source를 사용한다. event signed effect는 child transaction signed effect의
  합과 일치한다.
- manual adjustment credit은 PointRecoveryPending을 상계하지 않는다.
- 같은 actor/operation/key/payload는 same 201 body를 재생하고, 다른 payload 또는
  account는 `409 IDEMPOTENCY_KEY_REUSED`다.
- terminal idempotency record는 `createdAt + 90일`까지 보존한다. account와 hash가 같은
  record만 최초 201을 재생하며, account/hash가 다른 record는 write 전에 409이다.
- Account/Lot/transaction/Idempotency/Audit/outbox 중 하나라도 저장하지 못하면 전부
  rollback한다.

## Architecture and Transaction Boundaries

- Controller는 authorization principal과 DTO만 Application Service에 전달하고 Loyalty
  Repository를 직접 호출하지 않는다.
- Application Service는 PointAccount를 먼저 잠그고, 같은 command transaction에서 Operations
  public authorization API로 actor의 active `POINT_ADJUSTMENT` grant를 잠가 검증한다. 그 뒤
  terminal idempotency record를 조회한다. record가 없을 때만 credit은 new Lot을 만들고,
  debit은 `expiresAt > now`인 `(expiresAt, pointLotId)` 순서의 selected available Lot을 잠가
  command를 실행하며 Account/Lot/ledger/Audit/outbox/201과 같은 transaction에서 저장한다.
  서로 다른 account의 같은 key가 동시에 insert되어 UNIQUE가 충돌하면 loser transaction은
  전부 rollback하고, 별도 read transaction에서 winner의 account/hash를 다시 비교해 stored
  201 또는 409을 반환한다. 재실행으로 경쟁을 해결하지 않는다.
- 같은 local transaction이 Account summary, affected Lot, one-or-more PointTransaction,
  terminal IdempotencyRecord, AuditRecord와 PointsAdjustedV1 outbox를 commit한다. external
  Provider, Analytics consumer와 notification은 transaction 밖이다.
- lock 순서는 항상 PointAccount → `POINT_ADJUSTMENT` grant → terminal idempotency record →
  ordered PointLot이다. issuer reference는 immutable value snapshot이므로 Merchant Aggregate를
  JPA association으로 로드하지 않는다.
- Analytics listener, receipt, delta/freshness projection은 Analytics plan의 단독 소유다. Loyalty는
  producer transaction/outbox contract만 구현하며 Analytics consumer를 등록하거나 projection table을 만들지 않는다.

## Alternatives Considered

- PointAccount summary 직접 수정: Lot·원장·cost snapshot·감사를 잃어 제외한다.
- `RECOVERY`/SettlementAdjustment 재사용: 환불 또는 정산의 원천을 오염시켜 제외한다.
- issuer/expiry default: 사용자 결정과 BR-20 비용 추적을 숨겨 제외한다.
- 2인 proposal: 보안 강화 장점은 있지만 현 결정의 단일 권한 운영 command를 별도
  workflow로 확장하므로 향후 Revisit으로 남긴다.

## Failure Semantics

- issuer/expiry 누락, zero amount, 잘못된 role/active grant, debit issuer 포함은 400/403으로
  명시적 거부한다.
- grant lookup/lock failure는 role-only success 또는 403으로 바꾸지 않고 503이며 command
  write 전에 rollback한다.
- debit 가능한 Lot 합이 부족하면 `409 POINT_ADJUSTMENT_INSUFFICIENT_AVAILABLE`이고
  부분 debit·pending·음수 fallback은 없다.
- lock contention, DB/Audit/outbox 저장 실패는 503 또는 transaction rollback이며 201·0원
  성공으로 대체하지 않는다. command transaction에는 외부 결과 불명 구간이 없다.
- idempotency UNIQUE 충돌 뒤 winner record를 읽지 못하면 201/409을 추측하지 않고 503이다.
  retention worker 실패는 due row를 남기고 다음 tick에 재시도하며, 삭제 0건 성공으로
  위장하거나 API 삭제 endpoint를 제공하지 않는다.
- Plan 10 issuer precheck 또는 Plan 11 grant/Plan 13 ledger evidence가 없으면
  endpoint를 활성화하지 않는다. 추정 PLATFORM backfill은 금지한다.
- Analytics listener failure는 Analytics plan의 publication retry/receipt state로 남고, 이미 확정된
  adjustment를 되돌리거나 Loyalty command의 201을 바꾸지 않는다.

## Data and Migration

Plan 10/11/13 완료 뒤 최신 migration 번호를 다시 계산한다.

1. Plan 10이 `loyalty_point_lot.issuer_type`/`issuer_reference` final schema와 legacy
   issuer precheck gate를 이미 완료했는지 release evidence로 확인한다. missing 또는
   unresolvable 결과면 이 계획의 migration/endpoint activation을 시작하지 않는다. 이
   계획은 PointLot issuer migration을 다시 만들지 않는다.
2. `loyalty_point_transaction`에 `balance_effect`를 추가하고 known type을 deterministic
   mapping으로 backfill한다. `ADJUSTMENT`를 type CHECK/Kotlin enum에 추가하며 type/effect
   combination CHECK와 `amount_krw > 0`을 강제한다.
3. `loyalty_point_adjustment_command_idempotency`를 만든다. `actor_id`, `point_account_id`
   FK, `operation=POINT_ADJUSTMENT`, key 8..128, SHA-256 payload hash, terminal 201 response
   body/version, `created_at`, `retention_expires_at`과 `(actor_id, operation, idempotency_key)`
   UNIQUE, `(retention_expires_at, id)` index를 강제한다. row는 처음부터 terminal이며
   `retention_expires_at = created_at + 90일`이다.
4. `LoyaltyPointAdjustmentIdempotencyRetentionWorker`는 기본 1시간마다 최대 100개 due row를
   `(retention_expires_at, id)` keyset 순서로 독립 transaction에서 제거한다. raw key,
   actor, account와 response body는 metric/log에 넣지 않는다.
5. Plan 10 issuer precheck evidence, command/child transaction source relation, terminal
   command idempotency retention index와 Audit source unique를 PostgreSQL Testcontainers로
   검증한다.
6. current source가 Plan 13에서 만든 `ACCRUAL`/`RECOVERY` fields/type CHECK와 충돌하면
   migration을 작성하지 않고 ADR-065/066의 migration ownership conflict로 보고한다.
7. `OperatorPermissionGrant` table이나 policy read Audit migration은 만들지 않는다. Plan 11의
   ADR-069 outcome, 네 값의 closed vocabulary와 Operations public authorization API를 activation
   prerequisite로 소비한다. `POINT_ADJUSTMENT` permission 또는 grant constraint migration을
   다시 만들지 않는다.

## API and Event Contracts

- `POST /operations/point-accounts/{accountId}/adjustments`는 Idempotency-Key를 요구하고
  201 `PointAdjustmentResult`, 400/401/403/404/409/503을 정의한다.
- `PointAdjustmentRequest.amountKrw`는 0이 아닌 SignedMoneyKrw다. 양수 branch만 issuer와
  expiresAt을 require하고 음수 branch는 둘을 forbid한다. request의 issuer reference,
  expiry, reason과 evidence는 canonical payload에 포함한다.
- Result는 changed PointAccount와 실제 생성·차감 PointTransaction 목록을 반환한다. replay
  header/field는 없다.
- `PointsAdjustedV1` envelope은 PointAccount ID와 commit 뒤 Account version, initial
  `payloadVersion = 1`을 사용한다. payload는 `adjustmentSource`, `accountId`, signed
  `amountKrw` (child transaction signed effect 합)을 가진다. `issuerType`은 CREDIT에만 넣고, 여러 issuer Lot을
  차감할 수 있는 DEBIT에서는 생략한다. raw evidence, actor, Idempotency-Key와 issuer
  reference는 event에 넣지 않는다. Analytics만 소비하고 customer notification event는
  만들지 않는다. consumer listener, receipt/idempotency and projection은 Analytics plan만 구현한다.

## Milestones

1. ADR-066/068/069, OpenAPI/API conventions/authorization/event catalog의 contract test를 고정한다.
2. Plan 10/11/13 완료 evidence, issuer precheck와 OperatorPermissionGrant authorization outcome을 확인해 activation
   precondition을 닫는다.
3. PointTransaction balance_effect/type migration·entities를 구현하고, Plan 10 issuer snapshot을
   사용하는 credit/debit flow를 구현한다.
4. Loyalty terminal idempotency persistence/retention worker와 credit/debit command
   transaction, Audit/outbox를 구현한다.
5. Operations endpoint/DTO projection과 role+Operations grant enforcement를 구현한다.
6. `PointsAdjustedV1` producer/outbox contract, same logical-source conflict와 Analytics-plan
   consumer handoff evidence를 완료한다.

## Required Tests

- OpenAPI conditional positive/negative request validation and required Idempotency-Key
- customer/store/settlement role denial, Platform Operator active grant/reason/evidence, revoked grant
  and grant/Audit DB failure 503
- credit issuer/expiry snapshot and future boundary `now - 1ns`, `now`, `now + 1ns`
- debit deterministic multi-Lot selection, reserved/expired Lot exclusion and insufficient rollback
- Account/Lot/transaction/Audit/outbox/201 commit atomicity and each persistence failure injection
- same-key same-payload replay, different payload/account conflict, cross-account unique-race
  rollback/re-read and concurrent debit safety
- terminal adjustment idempotency의 90일 경계, keyset chunk 100, worker 중단·재실행과
  cleanup failure의 due row 보존
- Plan 10 issuer precheck의 empty/verified/unresolvable fixture와 endpoint activation
  precondition, balance_effect/type CHECK 및 current-type deterministic backfill
- public signed amount projection, CREDIT/DEBIT PointsAdjustedV1 payload condition/version, producer
  logical-source conflict and Analytics handoff contract
- Modulith/ArchUnit, PostgreSQL Testcontainers, OpenAPI contract and migration empty/nonempty fixtures

## Validation Commands

```bash
./gradlew test --tests '*PointAdjustment*' --tests '*Loyalty*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

`direction`, `outcome`, `issuer_type`만 닫힌 metric tag로 사용한다. account, Lot, actor,
issuer reference, Idempotency-Key와 evidence reference는 tag나 log field에 넣지 않는다.

- `beanflow.loyalty.point_adjustment.command.count{direction,outcome}`
- `beanflow.loyalty.point_adjustment.amount_krw{direction}`
- Plan 10 소유 `beanflow.loyalty.issuer_precheck.count{outcome}` (`EMPTY|VERIFIED|UNRESOLVABLE`)
- `beanflow.loyalty.point_adjustment.idempotency_retention.count{outcome}`

## Documentation Updates

- ADR-011, ADR-017, ADR-022, ADR-064, ADR-065/066/068/069 implementation evidence
- BR-10/BR-20/BR-25/BR-26, authorization matrix, aggregate invariants, transaction boundaries,
  state machines, event catalog, OpenAPI and API conventions
- Operations runbook, producer contract handoff and migration release evidence

## Progress

- [x] contract/ADR/OpenAPI validation
- [x] Plan 10 issuer, Plan 11 grant, Plan 13 ledger prerequisite evidence
- [x] persistence and migration
- [x] command transaction/idempotency/audit/outbox
- [x] endpoint and authorization
- [x] producer/concurrency/failure validation and Analytics handoff
- [ ] full build and documentation evidence

## Surprises & Discoveries

- public `ADJUSTMENT` enum은 source code와 migration보다 앞서 있었고 amount direction을
  저장할 column도 없었다.
- BR-20은 PointLot issuer cost를 요구하지만 current PointLot persistence에는 issuer
  snapshot이 없다. Plan 10의 data precheck 없이 default issuer를 채우면 과거 비용 귀속을
  왜곡하므로 adjustment plan은 그 gate를 다시 구현하지 않고 evidence를 소비한다.
- 구현 preflight에서 이 plan의 기존 문장이 grant를 PointAccount보다 먼저 잠그도록 서술해
  ADR-069의 명시적 `PointAccount lock 뒤 grant 검증`과 충돌했다. Accepted ADR을 우선해
  PointAccount → grant → idempotency → ordered PointLot 순서로 정정했다.
- Account update의 명시적 `EntityManager.flush()`에서 발생한 Hibernate
  `PersistenceException`은 Spring `DataAccessException`으로 자동 번역되지 않았다. 원시
  예외가 HTTP 500으로 새지 않도록 command 경계에서 명시적 503으로 변환하고 전체 rollback을
  failure injection으로 검증했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | `ADJUSTMENT`는 Platform Operator의 active explicit grant, reason/evidence/audit 필수 signed Loyalty correction | 직접 DB 수정과 Settlement/Refund 의미 혼합 방지 | ADR-066, ADR-069 |
| 2026-08-01 | Accepted | 양수 조정은 issuer와 future expiry를 입력하고, 음수 조정은 existing available Lot을 FIFO 차감 | 비용 귀속과 만료를 숨은 기본값 없이 재현 | ADR-066, BR-20 |
| 2026-08-01 | Accepted | Loyalty가 terminal adjustment idempotency row와 독립 90일 retention worker를 소유 | Context 경계를 지키면서 최초 201 재생과 BR-26 보존을 함께 충족 | ADR-066, BR-26 |
| 2026-08-01 | Accepted existing | PointLot issuer snapshot migration은 Plan 10의 선행 책임이고 adjustment는 evidence를 소비 | 만료 부분 환불 compensation이 같은 schema를 먼저 필요로 하며 중복 migration을 막음 | ADR-063, ADR-066 |
| 2026-08-01 | Accepted | `POINT_ADJUSTMENT`는 Operations DB grant로 판정하고 role/JWT claim fallback을 금지 | revoke와 permission dependency failure를 명시적으로 보존 | ADR-069 |
| 2026-08-01 | Accepted | Analytics event name/version은 `PointsAdjustedV1`/1로 고정 | event catalog 이름만으로 payload를 추측하지 않음 | ADR-068 |
| 2026-08-01 | Accepted | Loyalty는 `PointsAdjustedV1` producer/outbox만 소유하고 Analytics listener·receipt·projection은 구현하지 않음 | 같은 consumer를 두 plan이 만들지 않게 함 | ADR-068, Analytics plan |
| 2026-08-04 | Applied existing | 잠금 순서는 PointAccount → `POINT_ADJUSTMENT` grant → terminal idempotency → ordered PointLot으로 고정 | Accepted ADR-069을 우선하고 revoke/command 선형화와 Loyalty lock 순서를 하나의 transaction에서 재현 | ADR-069, 사용자 확인 |

## Outcomes & Retrospective

Plan 10 issuer precheck, Plan 11 ADR-069 grant와 Plan 13 PointTransaction base를 verified completed
input으로 소비했다. V31은 current type의 deterministic `balance_effect` backfill, `ADJUSTMENT`
조합 CHECK와 terminal idempotency relation을 추가한다. PostgreSQL 통합 테스트에서 credit/debit,
FIFO·만료·reserved 제외, replay/conflict, 동시 요청, 모든 owner persistence rollback, retention
경계와 `PointsAdjustedV1` producer contract가 통과했다. Analytics consumer 구현은 이 plan의
completion condition이 아니라 Analytics plan의 own checkpoint다. 전체 build와 문서 completion
evidence는 마지막 checkpoint에 기록한다.

## Revision Notes

- 2026-08-01: documentation consistency audit에서 public ADJUSTMENT enum의 미정의 계약을
  ADR-066과 독립 foundation plan으로 분리했다.
- 2026-08-01: PointLot issuer snapshot migration은 만료 부분 환불 compensation을 먼저
  구현하는 Plan 10이 소유하고, 이 계획은 precheck evidence를 소비하도록 명확화했다.
- 2026-08-01: ADR-069 Operations grant와 ADR-068 `PointsAdjustedV1` contract를 activation
  prerequisite로 추가했다.
- 2026-08-02: completed Plan 13 V17/PointTransaction owner outcome을 반영해 마지막 direct
  dependency와 implementation readiness를 닫았다.
