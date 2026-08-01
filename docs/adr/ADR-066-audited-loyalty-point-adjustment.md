# ADR-066: 감사형 Loyalty 포인트 조정

- **Status:** Accepted
- **Date:** 2026-08-01
- **Amends:** ADR-011의 PointTransaction 조정 표현
- **Implementation owner:** `docs/exec-plans/active/loyalty-point-adjustment-foundation.md`
- **Schema prerequisite owners:** [Plan 10](../exec-plans/active/customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md)의 PointLot issuer snapshot precheck/migration, [Plan 11](../exec-plans/active/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md)의 grant, [Plan 13](../exec-plans/active/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md)의 PointTransaction base

## Context

공개 OpenAPI는 `PointTransaction.type = ADJUSTMENT`를 선언했지만, 생성 권한, 원천,
금액 방향, PointLot 만료·발급 비용 주체, DB 표현과 구현 계획이 없었다. 현재
`loyalty_point_transaction`은 양수 `amount_krw`만 저장하므로 type만 `ADJUSTMENT`로
추가하면 credit인지 debit인지 재현할 수 없다.

수동 balance 변경을 SettlementAdjustment나 환불 `RECOVERY`로 가장하면 Context 소유권과
원천이 섞인다. 반대로 PointAccount summary만 직접 고치면 Lot, 비용 귀속, 감사와
중복 요청을 재현할 수 없다. Product owner는 수동 조정을 허용하되, 권한이 있는
운영자의 사유·증빙·감사가 필수인 signed Loyalty correction으로 결정했다. 양수 조정의
issuer와 만료일은 호출자가 명시적으로 입력한다.

## Decision

### 의미와 범위

- `ADJUSTMENT`는 원장·summary·Lot의 확인된 불일치를 바로잡는 **수동 Loyalty
  correction**이다. 일반 적립(`ACCRUAL`), 사용, 만료, 환불 복원, `RECOVERY`,
  PointRecoveryPending과 SettlementAdjustment를 대체하지 않는다.
- command는 `POST /operations/point-accounts/{accountId}/adjustments`로만 실행한다.
  활성 `PLATFORM_OPERATOR`의 명시적 `POINT_ADJUSTMENT` permission, non-blank reason,
  적어도 하나의 evidence reference와 `Idempotency-Key`가 모두 필수다.
- command source는 `actorId + operation + Idempotency-Key`와 canonical payload에서
  서버가 만든 immutable adjustment source다. AuditRecord와 `PointsAdjusted`는 이 command
  source를 사용한다. 각 PointTransaction은 command source와 affected PointLot을
  결정적으로 묶은 opaque child source를 사용하므로 하나의 command가 여러 Lot을
  차감해도 source UNIQUE를 위반하지 않는다. raw `Idempotency-Key`는 어느 source에도
  넣지 않는다. 동일 key·payload는 최초 `201` body를 재생하고, 다른 payload 또는 다른
  account 재사용은 `409 IDEMPOTENCY_KEY_REUSED`다.
- 수동 조정은 외부 Provider를 호출하지 않으며, PointAccount lock으로 경쟁을
  직렬화하는 ADR-064 명령 transaction 모델을 사용한다. PointAccount/Lot/
  PointTransaction/IdempotencyRecord/AuditRecord와 최초 response는 하나의 짧은
  로컬 transaction에서 함께 commit하거나 rollback한다.
- PointAccount lock을 얻은 뒤 같은 scope record를 먼저 확인한다. account와 hash가
  모두 같으면 저장된 최초 `201` body를 재생하고, 하나라도 다르면 write 전에
  `409 IDEMPOTENCY_KEY_REUSED`다. 서로 다른 account에 같은 key가 동시에 들어와
  UNIQUE insert 경쟁이 발생하면 loser의 전체 command transaction을 rollback한 뒤 별도
  read transaction에서 winner를 비교해 같은 결과를 반환한다. 이 read가 실패하면 명령을
  재실행하지 않고 `503 DEPENDENCY_UNAVAILABLE`다.

### 양수와 음수 조정

| Direction | Required input | Durable effect |
|---|---|---|
| Credit (`amountKrw > 0`) | 양수 금액, `issuer { issuerType, issuerReference }`, 미래 `expiresAt`, reason, evidence | 입력 issuer snapshot과 expiresAt을 가진 새 PointLot 하나, `balance_effect=CREDIT` `ADJUSTMENT` transaction, Account available 증가 |
| Debit (`amountKrw < 0`) | 음수 금액, reason, evidence. issuer와 expiresAt은 금지 | Account → `expiresAt > now`인 `(expiresAt, pointLotId)` available Lot 순서로 차감, Lot별 `balance_effect=DEBIT` `ADJUSTMENT` transaction, Account available 감소 |

- `amountKrw = 0`은 금지한다. debit 대상 available Lot 합이 부족하면 부분 차감·음수
  Account·PointRecoveryPending 생성 없이 `409 POINT_ADJUSTMENT_INSUFFICIENT_AVAILABLE`
  으로 전체 transaction을 rollback한다.
- credit의 issuer type은 `PLATFORM`, `BRAND`, `STORE` 중 하나이고 issuer reference는
  non-blank immutable cost-owner snapshot이다. 서버는 actor·customer·현재 Lot에서
  issuer를 추측하거나 PLATFORM으로 fallback하지 않는다. 이 선택은 나중에 해당 Lot을
  사용할 때 BR-20 비용 배분의 원천이 된다.
- credit `expiresAt`은 command transaction의 `now`보다 엄격히 뒤여야 한다. 기본
  만료일을 추측하지 않는다. debit은 새 Lot을 만들지 않으며 사용 가능한 기존 Lot만
  줄인다. expiration worker가 아직 실행되지 않았더라도 `expiresAt <= now` Lot은 debit
  후보가 아니다.
- manual credit은 `PointRecoveryPending`을 상계하지 않는다. BR-13이 정한
  `OrderCompleted`의 `ACCRUAL`만 pending 우선 상계를 수행한다.

### 감사와 비용 귀속

- 성공 command는 `POINT_ADJUSTMENT_APPLIED` AuditRecord를 PointAccount target에
  append한다. actor, reason, evidence references, signed requested effect, before/after
  Account summary, 생성 또는 차감 Lot ID, credit issuer snapshot과 expiry를 whitelist
  summary에 보존한다. secret, customer 개인정보, raw Idempotency-Key는 넣지 않는다.
- AuditRecord action/source unique와 adjustment transaction source가 같은 command의
  replay에서 함께 중복되지 않도록 한다. Audit insert 실패는 point change 성공으로
  대체하지 않고 전체 transaction을 rollback한다.
- credit Lot의 issuer snapshot은 point 사용 시 Settlement의 비용 배분 입력이다.
  manual `ADJUSTMENT` command 자체는 SettlementItem이나 SettlementAdjustment를
  만들지 않는다.

### DB 표현

Plan 10은 만료 부분 환불 compensation에 먼저 필요한 PointLot issuer snapshot과 legacy
issuer precheck/migration gate를 구현한다. 이 ADR의 implementation plan은 Plan 10의
gate evidence를 전제하고 adjustment 전용 forward-only migration으로 다음을 구현한다.

- Plan 10은 `loyalty_point_lot`에 `issuer_type` (`PLATFORM|BRAND|STORE`)와
  `issuer_reference`를 추가한다. 새 Lot에는 모두 NOT NULL이며, 기존 Lot의 issuer
  source가 확인되지 않으면 PLATFORM으로 추정 backfill하지 않는다. precheck가
  nonempty·unresolvable row를 발견하면 Plan 10의 만료 보상과 이 ADR의 endpoint 활성화를
  중단하고 source mapping을 먼저 확정한다.
- `loyalty_point_transaction`에 `balance_effect` (`CREDIT|DEBIT|NONE`)를 추가하고
  `amount_krw > 0` absolute magnitude는 유지한다. CHECK는 `ACCRUAL`, `RESTORE`,
  `COMPENSATION`은 CREDIT, `USE`, `EXPIRATION`, `RECOVERY`는 DEBIT,
  `RESTORE_SKIPPED_EXPIRED`는 NONE, `ADJUSTMENT`는 CREDIT 또는 DEBIT만 허용한다.
  기존 type의 backfill은 이 mapping으로만 수행하고 불명 row는 실패시킨다.
- type CHECK와 Kotlin enum에 `ADJUSTMENT`를 추가한다. adjustment direction은 type
  문자열이나 API amount sign을 역추론하지 않고 `balance_effect`에서 읽는다.
- Loyalty는 terminal-only `loyalty_point_adjustment_command_idempotency` table을
  소유한다. 최소 컬럼은 `id`, `actor_id`, `point_account_id`, `operation`,
  `idempotency_key`, `payload_hash`, `response_status`, `response_body`,
  `response_version`, `created_at`, `retention_expires_at`이다. `operation`은
  `POINT_ADJUSTMENT`, response status는 `201`, key는 trim 뒤 8..128자, hash는 SHA-256
  64자, response version은 양수여야 한다. `UNIQUE(actor_id, operation,
  idempotency_key)`가 account를 바꾼 key 재사용까지 막고, `point_account_id`는
  PointAccount FK다.
- `retention_expires_at = created_at + 90일`을 생성 시 materialize하고
  `(retention_expires_at, id)` index를 둔다. `LoyaltyPointAdjustmentIdempotencyRetentionWorker`
  는 기본 1시간마다 최대 100개 due row를 keyset 순서로 한 독립 transaction에서
  삭제한다. cleanup 실패는 0건 성공으로 기록하지 않고 다음 tick에 재시도하며 raw key,
  actor, account, response body를 log/metric에 넣지 않는다. Ordering의 ADR-056 worker는
  다른 Context table을 정리하지 않는다.
- Lot별 PointTransaction child source와 AuditRecord command source는 UNIQUE다. child
  source는 immutable command source와 PointLot discriminator의 관계를 보존한다. 별도
  PointAdjustment JPA Aggregate는 만들지 않고 IdempotencyRecord, immutable source와
  target AuditRecord가 command의 durable identity를 제공한다.

### API와 이벤트 계약

- `PointAdjustmentRequest`는 signed nonzero `amountKrw`, `reason`,
  `evidenceReferences`를 요구한다. 양수일 때만 `issuer`와 `expiresAt`을 required로 하고
  음수일 때는 둘을 거부한다.
- 성공 `201 PointAdjustmentResult`는 수정된 PointAccount summary와 실제로 생성한
  하나 이상 PointTransaction을 반환한다. replay 표시는 제공하지 않는다.
- 공개 PointTransaction의 `amountKrw`는 `balance_effect`를 적용한 signed effect다.
  `ADJUSTMENT`는 CREDIT면 양수, DEBIT이면 음수다.
- Loyalty는 성공 command와 같은 transaction에 `PointsAdjusted` persistent event를
  저장한다. 초기 event envelope의 `aggregateId`는 PointAccount ID, `aggregateVersion`은
  commit 뒤 PointAccount version, `payloadVersion`은 1이다. payload의 `adjustmentSource`와
  signed `amountKrw`는 각각 command source와 그 command의 child PointTransaction signed
  effect 합과 일치한다. `issuerType`은 CREDIT event에만 포함하고, 여러 기존 issuer Lot을 함께
  차감할 수 있는 DEBIT event에서는 생략한다. Analytics는 adjustment source로 멱등 처리한다.
  고객 알림이나 외부 Provider 호출은 만들지 않는다.

## Alternatives Considered

### `ADJUSTMENT`를 OpenAPI에서 제거

- 아직 없는 command와 migration을 만들지 않아 범위가 작다.
- 인정된 운영 correction을 직접 DB 수정이나 다른 transaction type으로 우회하게 하므로
  제외한다.

### PointAccount summary만 수정

- schema 변경이 작다.
- Lot 만료·issuer 비용, 원장, replay와 감사 재현이 불가능하므로 제외한다.

### 단일 `PLATFORM` issuer와 고정 30일 만료

- request shape가 단순하다.
- 사용자가 명시적으로 선택한 issuer·만료 입력을 무시하고 비용·만료를 임의로 결정하므로
  제외한다.

### SettlementAdjustment 재사용

- 조정이라는 이름을 공유한다.
- 고객 포인트가 아닌 확정 정산 원장을 바꾸므로 Context와 금액 의미가 달라 제외한다.

## Rationale

Lot을 만들거나 차감하는 실제 command와 target Audit을 하나의 transaction에 묶으면
수동 correction도 자동 Loyalty 사실과 같은 불변식·멱등성·비용 귀속을 지킨다. issuer와
만료를 입력으로 강제하면 금전적 비용과 고객 가치의 유효기간을 숨은 기본값 없이
명시적으로 검토할 수 있다.

## Consequences

- Operations 권한, API contract, PointLot issuer snapshot, PointTransaction effect
  direction과 persistent event가 추가된다.
- 단일 Platform Operator가 명시적인 권한·reason·evidence 아래 실행할 수 있다.
  금액 구간별 2인 승인 또는 외부 ticket 검증은 이번 결정에 포함하지 않는다.
- 사용 가능한 PointLot 또는 confirmed issuer source가 없으면 command는 성공하지
  않는다. local/fake/default issuer로 대체하지 않는다.
- 기존 PointLot issuer source의 데이터 이력은 migration precheck로 드러나며, 확인
  불가능한 row를 조용히 재분류하지 않는다.

## Required Tests

- role/explicit permission, reason/evidence 누락, zero amount 거부
- credit의 issuer·future expiry required와 debit에서 두 필드 금지
- credit Lot issuer/expiry snapshot, Account/transaction/audit/201 단일 commit
- debit FIFO Lot 선택, 부족 가용금액의 전체 rollback과 음수 잔액 부재
- 같은 key replay body·Audit·transaction 수 불변과 다른 payload/account conflict
- 서로 다른 account의 같은 key 동시 요청에서 winner만 commit하고 loser rollback 뒤
  저장된 201 또는 409을 반환하는 경우
- Audit failure injection의 Account/Lot/transaction/Idempotency 전체 rollback
- `balance_effect` CHECK와 모든 type의 signed public amount projection
- CREDIT/DEBIT별 PointsAdjusted payload 조건, outbox persistence/Analytics idempotency와
  고객 notification 부재
- Plan 10 PointLot issuer precheck의 empty/known/unresolvable fixture와 adjustment
  endpoint 활성화 선행조건

## Metrics

- `beanflow.loyalty.point_adjustment.command.count{direction,outcome}`
- `beanflow.loyalty.point_adjustment.amount_krw{direction}`
- `beanflow.loyalty.point_adjustment.issuer_resolution.failure.count{issuer_type}`

actor, account, Lot, issuer reference, Idempotency-Key와 evidence reference는 metric tag로
사용하지 않는다.

- **Not measured:** 수동 adjustment 발생 빈도, 금액 분포와 승인 처리 시간

## Revisit Conditions

금액 구간별 2인 승인, external ticket/approval 연동, issuer reference의 별도 master
registry, non-expiring PointLot 또는 수동 adjustment의 PointRecoveryPending 상계를
도입할 때

## Related Decisions

- BR-10, BR-20, BR-25, BR-26, BR-30
- [ADR-011](ADR-011-point-lot-ledger.md)
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-022](ADR-022-audit-record.md)
- [ADR-064](ADR-064-risk-based-idempotency-model-selection.md)
- [ADR-065](ADR-065-refund-earned-point-recovery-ledger.md)
- [ADR-056](ADR-056-ordering-idempotency-retention-worker.md)
