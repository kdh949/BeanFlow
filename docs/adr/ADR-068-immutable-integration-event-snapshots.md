# ADR-068: Immutable integration event snapshot 계약

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owners:** [Plan 10](../exec-plans/active/customer-order-cancellation-10-partial-refund-allocation-foundation.md), [Plan 20](../exec-plans/active/customer-order-cancellation-20-settlement-foundation.md), [Settlement lifecycle plan](../exec-plans/active/settlement-batch-adjustment-and-dispute.md), [Point adjustment plan](../exec-plans/active/loyalty-point-adjustment-foundation.md), [Analytics plan](../exec-plans/active/analytics-refund-and-late-event-projection.md)

## Context

Settlement은 완료 시점의 수수료율과 혜택 비용 snapshot으로 과거 결과를 재현해야 하고,
Analytics는 late/replayed event를 현재 Aggregate나 현재 정책을 다시 읽지 않고 집계해야
한다. 현재 `OrderCompletedV1`은 식별자와 완료 시각만 가지며, `PaymentRefunded`,
`PointsAccrued`, `PointsRestored`, `SettlementItemCreated`, `SettlementAdjustmentCreated`는
catalog 이름만 있고 Kotlin 계약·producer checkpoint가 없다.

consumer가 최신 Order, Campaign, Merchant 계약 또는 PointLot을 다시 조회하면 정책·계약
변경 뒤 과거 Settlement와 Analytics 결과가 달라진다. event ID만으로 중복을 막아도 같은
논리 source가 새 event ID로 재발행되거나 payload가 달라진 경우를 식별할 수 없다.

## Decision

금전적 Settlement·Analytics input은 **immutable snapshot을 payload에 포함**한다. consumer는
원본 Aggregate의 최신 상태나 live policy를 조회하여 누락 값을 보완하지 않는다. 각 event의
logical source, event type version, payload version, payload field set과 producer transaction은
아래 표가 canonical이다.

`eventType`의 `Vn` suffix와 envelope의 `payloadVersion`은 같은 `n`을 사용한다. event ID는
publication 추적용이고, consumer idempotency key는 아래 logical source와 payload version이다.
같은 logical source/version에 다른 payload hash가 오면 기존 data를 덮어쓰지 않고
publication failure, bounded retry와 `MANUAL_REVIEW`로 남긴다.

### Financial snapshot payloads

모든 행은 공통 `EventEnvelope`를 포함한다. 아래 field set 외의 provider reference, raw
Idempotency-Key, evidence, 자유 입력 reason/detail 또는 live policy value를 넣지 않는다.

| Event / envelope version | Exact immutable payload (envelope 외) | Producer transaction / logical source | Consumer checkpoint |
|---|---|---|---|
| `OrderCompletedV2` / 2 | `orderId`, `customerId`, `storeId`, `completedAt`, `settlementDate`, `currency`, `grossPaidKrw`, `feeRateBps`, `feeKrw`, `couponCostKrw`, `pointCostKrw`, `benefitCostKrw`, `netSettlementKrw`, `completionSource` | Ordering의 `COMPLETED` guarded transition과 같은 transaction. `order:{orderId}:completed:{aggregateVersion}` | Plan 20: SettlementItem; Loyalty: accrual; Analytics: completion-date input |
| `PaymentRefundedV1` / 1 | `refundId`, `refundSource`, `orderId`, `customerId`, `refundSucceededAt`, `currency`, `cashRefundedKrw`, `completionDisposition`; `COMPLETED_ORDER`일 때만 `orderCompletedAt`, `settlementDate`, `settlementItemSource`, `settlementRefundEffect { grossPaidDeltaKrw, feeDeltaKrw, benefitCostDeltaKrw, netSettlementDeltaKrw }` | Payment의 `Refund -> SUCCEEDED` result transaction과 같은 transaction. `refund:{refundId}:succeeded` | Plan 10: Loyalty restore/recovery; Settlement: Item 반영/Adjustment; Analytics: refund-date와 completion-date delta |
| `PointsAccruedV1` / 1 | `pointTransactionSource`, `orderCompletionSource`, `orderId`, `orderCompletedAt`, `amountKrw`, `currency` | Loyalty의 `ACCRUAL` ledger transaction과 같은 transaction. `point-transaction:{source}` | Analytics |
| `PointsRestoredV1` / 1 | `pointTransactionSource`, `refundSource`, `orderId`, `refundSucceededAt`, `orderCompletedAt`(없는 경우 null), `amountKrw`, `currency`, `restorationDisposition` (`RESTORE`, `COMPENSATION`, `SKIPPED`) | Loyalty의 Refund owner result transaction과 같은 transaction. `point-transaction:{source}` | Analytics |
| `PointsAdjustedV1` / 1 | `adjustmentSource`, `accountId`, signed `amountKrw`, `issuerType`(CREDIT일 때만) | Loyalty point-adjustment command transaction과 같은 transaction. `point-adjustment:{adjustmentSource}` | Analytics |
| `SettlementItemCreatedV1` / 1 | `settlementItemId`, `settlementBatchId`, `itemSource`, `orderId`, `storeId`, `completedAt`, `settlementDate`, `currency`, `grossPaidKrw`, `feeKrw`, `benefitCostKrw`, `netSettlementKrw` | SettlementItem/Audit/outbox를 저장하는 Plan 20 Settlement transaction. `settlement-item:{itemSource}` | Analytics |
| `SettlementAdjustmentCreatedV1` / 1 | `settlementAdjustmentId`, `adjustmentSource`, `settlementItemId`, `settlementBatchId`, `reasonCode`, `effectiveAt`, `orderCompletedAt`, `settlementDate`, `currency`, signed `amountKrw` | SettlementAdjustment/Audit/outbox를 저장하는 lifecycle transaction. `settlement-adjustment:{adjustmentSource}` | Analytics |

명시적으로 conditional 또는 nullable이라고 적힌 field 외에는 모든 field가 required다. 식별자는
canonical UUID string, `*At`은 offset을 가진 `Instant`, `settlementDate`는 `Asia/Seoul`의
`YYYY-MM-DD`, `currency`는 `KRW`다. `grossPaidKrw`, `feeKrw`, `couponCostKrw`, `pointCostKrw`,
`benefitCostKrw`, `netSettlementKrw`, `cashRefundedKrw`와 일반 accrual/restoration `amountKrw`는
non-negative 64-bit integer KRW다. delta, `PointsAdjustedV1.amountKrw`,
`SettlementAdjustmentCreatedV1.amountKrw`는 signed 64-bit integer KRW이고,
`feeRateBps`는 `0..10000` integer다. `*Source`와 `reasonCode`는 producer가 만든 stable opaque
identifier/closed vocabulary이며, consumer가 자유 text 또는 current state로 채우지 않는다.

`completionDisposition`은 `COMPLETED_ORDER` 또는 `PRE_ACCEPTANCE_CANCELLATION`이다.
후자에는 SettlementItem source와 `settlementRefundEffect`를 넣지 않는다. Settlement는 이를
0원 Adjustment로 바꾸지 않고 ADR-048의 `NOT_APPLICABLE` Audit 경로로 처리한다. Analytics는
두 disposition 모두 실제 `cashRefundedKrw`의 refund-date fact로 사용하되,
`COMPLETED_ORDER`만 completion-date adjustment에 사용한다.

`SettlementRefundEffect`의 모든 delta는 signed KRW이며, Refund로 감소하는 값은 음수다.
Payment는 이 snapshot을 Refund 요청 때 저장한 immutable line allocation과 completion snapshot으로
만 계산한다. consumer가 Item의 현재 summary, fee 계약 또는 Campaign을 재조회해 계산하지 않는다.

Analytics persistence는 event payload의 customer, account, order, payment, refund, Batch와 Item
식별자를 public dimension, log 또는 metric tag에 저장하지 않는다. receipt에는 opaque logical
source만 보존한다.

### Producer ownership and cutover

| Checkpoint | Owner plan | Required outcome before Analytics enables the consumer |
|---|---|---|
| `OrderCompletedV2` and `SettlementItemCreatedV1` | Plan 20 | Ordering producer, Settlement consumer, source unique and outbox contract tests pass |
| `PaymentRefundedV1`, `PointsAccruedV1`, `PointsRestoredV1` | Plan 10 | Payment/Loyalty producer transaction and allocation snapshot tests pass |
| `PointsAdjustedV1` | Point adjustment plan | adjustment transaction, permission gate and outbox contract tests pass |
| `SettlementAdjustmentCreatedV1` | Settlement lifecycle plan | adjustment source/reason unique and outbox contract tests pass |
| receipt/delta/freshness projection | Analytics plan | every enabled producer row above has actual outcome evidence |

`OrderCompletedV1` is frozen. Plan 20 may replace its producer with `OrderCompletedV2` only after a
version-cutover inventory proves that no deployed consumer or incomplete `OrderCompletedV1` publication
needs the old payload. The replacement is one producer/consumer/fixture checkpoint and does **not**
dual-publish V1 and V2. If the inventory is nonzero or unknown, Plan 20 stops and records a separate
forward-compatibility ADR before changing the producer. Existing V1 payload semantics are not silently
extended with required fields.

All new producer transactions persist the original fact and Spring Modulith publication atomically.
External Provider calls remain outside those transactions. A missing required snapshot, source, version
or publication row rolls back the producer result transaction when that fact is being committed; a
consumer failure does not roll back the original business fact.

## Alternatives Considered

### Consumers read the current Aggregate or policy

Current data is easy to query but changes historical fee, benefit and attribution results. This violates
ADR-017 and late-event convergence.

### Event carries only a locator to an immutable owner projection

This can keep payloads smaller, but it requires a new immutable projection schema, versioned cross-context
read API and availability/failure contract for every consumer. No such owner projection exists today.

### Add fields to `OrderCompletedV1` in place

New required fields change a frozen V1 contract and make old persisted publications ambiguous. V2 gives a
separate compatibility gate and exact payload meaning.

## Rationale

The completion, Refund and ledger transactions already know the immutable financial result when they commit.
Putting that fact in the same durable publication lets Settlement and Analytics converge from a stable source
without cross-context latest-state reads. A named owner checkpoint prevents Analytics from guessing which
unimplemented event shape to consume.

## Consequences

- Plan 20 owns the `OrderCompletedV1 -> V2` cutover gate and must supply settlement input snapshots.
- Plan 10 must materialize Refund allocation-derived Settlement effect before the success event is stored.
- Event catalog, Kotlin event API and producer tests must change together at each checkpoint; the catalog is
  not proof that a producer has already been implemented.
- Analytics starts with only the producers whose exact contract and validation evidence are complete.

## Verification

- contract tests reject a payload with a missing required snapshot or a payload version/type mismatch;
- fee/coupon/point cost and `netSettlementKrw` in `OrderCompletedV2` tie out to immutable Order snapshots;
- `PaymentRefundedV1` for completed and pre-acceptance-cancellation sources takes the correct Settlement and
  Analytics branch without live Aggregate reads;
- same logical source with a new event ID is idempotent, while same source with a changed payload fails;
- delayed event after a policy or contract change reproduces the original date and amount;
- V1 cutover inventory blocks V2 producer activation when old publication or consumer evidence is nonzero.

## Metrics

- `beanflow.event.contract.producer.count{event_type,payload_version,outcome}`
- `beanflow.event.contract.consumer.conflict.count{event_type}`
- `beanflow.analytics.projection.count{event_type,outcome}`

IDs, raw amounts, policy values, customer and evidence data are not metric tags.

## Revisit Conditions

An immutable owner projection with explicit availability and retention semantics, an external event broker,
or a need to retain a prior event version after the cutover inventory has nonzero evidence requires a new
compatibility decision.

## Related Decisions

- BR-02, BR-10, BR-13, BR-16, BR-18, BR-19, BR-20, BR-31, BR-32
- [ADR-010](ADR-010-initial-event-publication.md)
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-023](ADR-023-analytics-refund-and-late-events.md)
- [ADR-048](ADR-048-preacceptance-cancellation-settlement-exclusion.md)
- [ADR-061](ADR-061-refund-requested-and-confirmed-amounts.md)
- [ADR-066](ADR-066-audited-loyalty-point-adjustment.md)
