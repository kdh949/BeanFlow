# ADR-065: 환불 적립 포인트 회수 원장과 `RECOVERY` transaction

- **Status:** Accepted
- **Date:** 2026-08-01
- **Clarifies:** BR-13과 ADR-011의 환불 적립 포인트 회수 표현
- **Implementation owner:** [Plan 13](../exec-plans/active/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md)

## Context

BR-13은 환불에 대응하는 적립 포인트를 먼저 회수하고, 회수하지 못한 금액은 이후
적립으로 상계한다고 결정했다. OpenAPI `PointTransaction.type`에는 `RECOVERY`가 이미
있지만, 그 의미·소유자·DB 표현은 정의되지 않았다. 현재 Loyalty 구현과 migration의
PointTransaction type에는 `RECOVERY`가 없고, `POINT_RECOVERY_PENDING`도 persistence
model로 구현되지 않았다.

`RECOVERY`와 `POINT_RECOVERY_PENDING`을 같은 것으로 취급하면 두 문제가 생긴다.
전자는 계정 가용 잔액을 실제로 줄인 append-only 사실이어야 하지만, 후자는 아직
차감하지 못한 잔액과 상계 진행 상태다. 하나의 원장 행에 둘을 섞으면 부족액을 이미
차감한 것처럼 보이거나, 이후 적립 상계에서 이중 차감할 수 있다.

## Decision

### 어휘와 소유권

| Term | Meaning | Owner |
|---|---|---|
| `PointTransaction(type=RECOVERY)` | 환불에 대응해 실제로 가용 PointLot과 PointAccount에서 차감한 금액을 나타내는 append-only debit 사실 | Loyalty |
| `PointRecoveryPending` | 환불 시점에 실제로 회수하지 못해 이후 적립을 우선 상계해야 하는 잔액과 상태를 보존하는 Aggregate | Loyalty |
| `POINT_RECOVERY_PENDING` | `PointRecoveryPending.state = PENDING`을 나타내는 원장/운영 어휘이며 PointTransaction type이 아님 | Loyalty |

`RECOVERY`는 `RESTORE`, `COMPENSATION`, `RESTORE_SKIPPED_EXPIRED`와 같은 복원 결과
type도, SettlementAdjustment도 아니다. Payment는 성공 Refund 사실과 source를
발행할 뿐이고, 어떤 PointLot을 차감하거나 부족액을 보유하지 않는다. Settlement는
원천 refund reference로 금액 보정을 기록할 수 있으나 PointRecoveryPending을
소유하지 않는다.

### 환불 시 회수와 이후 적립 상계

1. Loyalty가 중복되지 않은 성공 Refund source를 받으면 PointAccount를 먼저 잠그고,
   회수 가능한 미예약 available PointLot을 `(expiresAt, pointLotId)` 순서로 잠근다.
2. 실제로 회수한 각 Lot 금액마다 서로 다른 source reference를 가진 `RECOVERY`
   PointTransaction을 기록하고 Lot과 Account의 available summary를 같은 로컬
   transaction에서 줄인다.
3. 회수 대상 금액이 남으면 남은 금액으로 `PointRecoveryPending(PENDING)` 하나를
   만들고 PointAccount의 `recoveryPendingKrw` summary를 같은 transaction에서 늘린다.
   실제 회수분과 부족액 기록은 일부만 commit할 수 없다.
4. 이후 `OrderCompleted` 적립은 PointAccount를 먼저 잠그고 오래된
   `PointRecoveryPending(PENDING)`을 `(createdAt, id)` 순서로 잠근다. 새 PointLot과
   전체 적립액의 `ACCRUAL` transaction을 기록한 뒤, 그 적립액을 pending 잔액에 먼저
   적용한다. 적용한 금액마다 `RECOVERY` transaction을 기록하고 새 Lot/Account
   available summary와 pending summary를 같은 transaction에서 줄인다.
5. pending의 `remainingAmountKrw`가 0이 되면 `PENDING -> SETTLED`로 한 번만
   전이한다. `PENDING`은 항상 양수 잔액, `SETTLED`는 항상 0 잔액을 가진다. 가용
   PointAccount 잔액이나 PointLot 잔액은 음수가 될 수 없다.

동일 Refund source의 같은 회수 결과는 멱등 재생한다. 같은 source에 회수 대상·금액·Lot
mapping이 다르면 덮어쓰거나 추가 차감하지 않고 `POINT_RECOVERY_SOURCE_CONFLICT`로
실패를 보존해 재처리 대상으로 남긴다.

### DB 표현

Plan 13은 구현 직전 최신 migration 번호를 다시 계산한 forward-only migration으로
다음을 구현한다. 이 ADR은 현재 migration이나 Kotlin enum이 이미 구현됐다는 뜻이
아니다.

- `loyalty_point_account`에 `recovery_pending_krw bigint NOT NULL DEFAULT 0 CHECK
  (recovery_pending_krw >= 0)`를 추가한다. 값은 해당 account의 `PENDING`
  PointRecoveryPending `remaining_amount_krw` 합과 같은 transaction에서 tie-out한다.
- `loyalty_point_recovery_pending`을 추가한다. 최소 컬럼은 `id`, `point_account_id`,
  `refund_source_reference`, `initial_amount_krw`, `remaining_amount_krw`, `state`,
  `created_at`, `settled_at`, `version`이다. `point_account_id +
  refund_source_reference`는 UNIQUE이고, initial은 양수, remaining은 `0..initial`,
  `PENDING`이면 remaining 양수/`settled_at` null, `SETTLED`이면 remaining 0/`settled_at`
  non-null을 CHECK로 보호한다. Payment Aggregate를 JPA 연관관계로 참조하지 않고
  refund source reference 값만 보존한다.
- 기존 `loyalty_point_transaction.amount_krw`는 양수 절대값으로 유지한다. type CHECK와
  Kotlin enum에는 `ACCRUAL`, `RECOVERY`를 기존
  `USE`, `EXPIRATION`, `RESTORE`, `COMPENSATION`, `RESTORE_SKIPPED_EXPIRED`에
  추가한다. `ADJUSTMENT`의 type/effect storage와 manual command는 ADR-066이
  별도 소유한다. `RECOVERY` 행은 refund와 PointLot을 구별하는 source reference를
  가져야 한다.
- deferred 상계 `RECOVERY`만 같은 Context의 nullable
  `point_recovery_pending_id`를 보존할 수 있으며, null이 아닌 값은 반드시
  `RECOVERY` type의 PointRecoveryPending ID여야 한다. 즉시 회수 `RECOVERY`는 이
  ID가 null이다. source reference의 UNIQUE 제약은 Lot별/상계별 논리 source로
  이중 차감을 막는다.

### API와 이벤트 계약

공개 `PointTransaction.amountKrw`는 저장한 절대값이 아니라 고객 잔액에 대한 signed
effect를 반환한다.

| Type | 공개 `amountKrw` effect |
|---|---:|
| `ACCRUAL`, `RESTORE`, `COMPENSATION` | 양수 |
| `USE`, `EXPIRATION`, `RECOVERY` | 음수 |
| `RESTORE_SKIPPED_EXPIRED` | 0 |

`ADJUSTMENT`의 생성 권한·원천·방향은 ADR-066이 소유한다. `RECOVERY` 도입은
`ADJUSTMENT`의 manual correction 의미를 바꾸지 않는다.

`PointAccount.recoveryPendingKrw`는 PENDING 잔액 합계이며, `RECOVERY` transaction
목록에서 추측해 계산하지 않는다. `PointRecoveryPendingRecorded`는 새 PENDING
Aggregate의 생성 사실이고, event catalog의 source of truth는 PointTransaction이 아닌
PointRecoveryPending이다. 이 결정은 pending이 settle될 때 새 public event를 추가하지
않으며, Operations와 Analytics의 현재 pending 조회는 Loyalty owner projection을 사용한다.

### 실패와 재처리

- Refund는 이미 성공했더라도 Loyalty 회수 transaction 실패를 0원 회수나 성공으로
  대체하지 않는다. 원본 Refund event는 재시도 가능하게 남고, retry 범위를 넘으면
  owner의 `RETRY_SCHEDULED`/`MANUAL_REVIEW` 또는 ReprocessingCase로 관측한다.
- Account, Lot, Pending, Transaction, summary tie-out 중 하나라도 저장하지 못하면
  해당 Loyalty transaction 전체를 rollback한다. 외부 Provider 호출이나 장시간 네트워크
  호출은 이 transaction에 넣지 않는다.
- source conflict, summary mismatch, 존재해야 할 PointLot/Pending 누락은 stale·cache·0
  fallback으로 숨기지 않고 실패로 남긴다.

## Alternatives Considered

### 부족액도 `RECOVERY` PointTransaction 하나로 기록

- table 수가 적고 조회가 단순해 보인다.
- 실제 차감과 미회수 의무를 구분할 수 없고, 이후 적립 상계에서 중복 차감 위험이 있어
  제외한다.

### PointAccount를 음수로 허용

- 별도 pending Aggregate가 필요 없다.
- BR-13의 음수 잔액 금지와 PointAccount 불변식을 위반하므로 제외한다.

### SettlementAdjustment가 부족액을 소유

- 환불과 관련된 금액을 한 Context에 모을 수 있다.
- 고객 포인트 잔액·Lot·향후 적립 상계의 owner가 Settlement가 되어 Context 경계와
  BR-13을 위반하므로 제외한다.

## Consequences

- Plan 13은 Plan 12 부분 환불 allocation outcome 뒤 Refund 성공 적립 포인트 회수와
  PointRecoveryPending/후속 적립 상계 foundation을 구현해야 한다.
- 현재 source의 PointTransaction enum, type CHECK, PointAccount persistence와
  PointRecoveryPending persistence는 이 target contract보다 뒤에 있다. 계획 완료 전에는
  OpenAPI enum이 구현되었다고 주장하지 않는다.
- PointTransaction public projection은 storage magnitude와 signed effect를 명시적으로
  변환해야 한다. raw JPA Entity를 API에 노출하지 않는다.

## Required Tests

- 전액 회수: Account/Lot 감소, 음수 API `RECOVERY`, pending 없음
- 일부 회수: 실제 `RECOVERY`와 하나의 PENDING residual, Account/Pending summary tie-out
- 동일 Refund/Lot source replay와 다른 payload의 source conflict
- 동시 Refund에서 Account/Lot non-negative와 deterministic lock order
- 이후 적립이 gross `ACCRUAL` 후 오래된 pending부터 `RECOVERY`로 상계하고 net available만
  남기는 경우
- 여러 pending의 부분·완료 상계와 `PENDING -> SETTLED` 단조 전이
- Loyalty local write 실패 때 Refund 성공을 포인트 회수 성공으로 투영하지 않는 retry/manual
  review 경로
- DB CHECK/UNIQUE와 OpenAPI signed amount projection contract

## Metrics

- `beanflow.loyalty.point_recovery.pending.amount_krw`
- `beanflow.loyalty.point_recovery.transaction.count{outcome}`
- `beanflow.loyalty.point_recovery.retry.count{outcome}`

account, refund, lot, order ID와 source reference는 metric tag로 사용하지 않는다.

- **Not measured:** 회수 부족액의 실제 발생률과 상계 완료까지 걸리는 시간

## Revisit Conditions

포인트 부채를 현금으로 청구하거나, 환불을 부족액 때문에 제한하거나, 포인트 양도·통합
프로그램 또는 cross-database Loyalty 저장소를 도입할 때

## Related Decisions

- BR-10, BR-12, BR-13, BR-20
- [ADR-011](ADR-011-point-lot-ledger.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-042](ADR-042-benefit-restoration-ledger-metadata.md)
- [ADR-061](ADR-061-refund-requested-and-confirmed-amounts.md)
- [ADR-066](ADR-066-audited-loyalty-point-adjustment.md)
