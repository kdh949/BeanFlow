# ADR-068: Immutable integration event snapshot 계약

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owners:** [Plan 16](../exec-plans/completed/customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md), [Settlement input snapshot foundation](../exec-plans/completed/customer-order-cancellation-15-settlement-input-snapshot-foundation.md), [Plan 20](../exec-plans/completed/customer-order-cancellation-20-settlement-foundation.md), [Settlement lifecycle plan](../exec-plans/completed/settlement-batch-adjustment-and-dispute.md), [Point adjustment plan](../exec-plans/completed/loyalty-point-adjustment-foundation.md), [Analytics plan](../exec-plans/active/analytics-refund-and-late-event-projection.md)

## Context

Settlement은 완료 시점의 수수료율과 혜택 비용 snapshot으로 과거 결과를 재현해야 하고,
Analytics는 late/replayed event를 현재 Aggregate나 현재 정책을 다시 읽지 않고 집계해야
한다. 현재 `OrderCompletedV1`은 식별자와 완료 시각만 가지며, `PaymentRefunded`,
`PointsAccrued`, `PointsRestored`, `SettlementItemCreated`, `SettlementBatchConfirmed`,
`SettlementAdjustmentCreated`, `SettlementDisputeFiled`, `SettlementDisputeDecided`는
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
| `OrderCompletedV2` / 2 | `orderId`, `customerId`, `storeId`, `completedAt`, `settlementDate`, `currency`, `grossPaidKrw`, `feeRateBps`, `feeKrw`, `couponCostKrw`, `pointCostKrw`, `benefitCostKrw`, `netSettlementKrw`, `completionSource` | Plan 20이 소유하는 Ordering `COMPLETED` guarded transition과 같은 transaction. `order:{orderId}:completed:{aggregateVersion}` | Plan 20: SettlementItem; Loyalty: accrual; Analytics: completion-date input |
| `PaymentRefundedV1` / 1 | `refundId`, `refundSource`, `orderId`, `customerId`, `refundSucceededAt`, `currency`, `cashRefundedKrw`, `completionDisposition`; `COMPLETED_ORDER`일 때 `orderCompletedAt`, `settlementDate`, `settlementItemSource`가 required; `COMPLETED_ORDER`와 `PRE_COMPLETION_ORDER`에는 `settlementRefundEffect { grossPaidDeltaKrw, feeDeltaKrw, benefitCostDeltaKrw, netSettlementDeltaKrw }`가 required | Payment의 `Refund -> SUCCEEDED` result transaction과 같은 transaction. `refund:{refundId}:succeeded` | Plan 12/13: Loyalty restore/recovery; Settlement: Item 반영/Adjustment 또는 pre-completion pending; Analytics: refund-date와 completion-date delta |
| `PointsAccruedV1` / 1 | `pointTransactionSource`, `orderCompletionSource`, `orderId`, `orderCompletedAt`, `amountKrw`, `currency` | Loyalty의 `ACCRUAL` ledger transaction과 같은 transaction. `point-transaction:{source}` | Analytics |
| `PointsRestoredV1` / 1 | `pointTransactionSource`, `refundSource`, `orderId`, `refundSucceededAt`, `orderCompletedAt`(없는 경우 null), `amountKrw`, `currency`, `restorationDisposition` (`RESTORE`, `COMPENSATION`, `SKIPPED`) | Loyalty의 Refund owner result transaction과 같은 transaction. `point-transaction:{source}` | Analytics |
| `PointsAdjustedV1` / 1 | `adjustmentSource`, `accountId`, signed `amountKrw`, `issuerType`(CREDIT일 때만) | Loyalty point-adjustment command transaction과 같은 transaction. `point-adjustment:{adjustmentSource}` | Analytics |
| `SettlementItemCreatedV1` / 1 | `settlementItemId`, `settlementBatchId`, `itemSource`, `orderId`, `storeId`, `completedAt`, `settlementDate`, `currency`, `grossPaidKrw`, `feeKrw`, `benefitCostKrw`, `netSettlementKrw` | SettlementItem/Audit/outbox를 저장하는 Plan 20 Settlement transaction. `settlement-item:{itemSource}` | Analytics |
| `SettlementBatchConfirmedV1` / 1 | `settlementBatchId`, `settlementDate`, `state=CONFIRMED`, signed `netSettlementKrw`, `currency` | Batch `CALCULATED → CONFIRMED`, Audit와 outbox를 저장하는 lifecycle transaction. `settlement-batch:{settlementBatchId}:confirmed` | Dispute의 confirmed Batch observation |
| `SettlementAdjustmentCreatedV1` / 1 | `settlementAdjustmentId`, `adjustmentSource`, `settlementItemId`, `settlementBatchId`, `reasonCode`, `effectiveAt`, `orderCompletedAt`, `settlementDate`, `currency`, signed `amountKrw` | SettlementAdjustment/Audit/outbox를 저장하는 lifecycle transaction. `settlement-adjustment:{adjustmentSource}` | Analytics |
| `SettlementDisputeFiledV1` / 1 | `disputeId`, `settlementItemId`, `previousDisputeId`(최초 접수는 null), `state=FILED`, signed `expectedAdjustmentKrw`, signed `heldAmountKrw`, `currency`, `filedAt` | Dispute/idempotency response/Audit/outbox를 저장하는 filing transaction. `settlement-dispute:{disputeId}:filed` | Operations |
| `SettlementDisputeDecidedV1` / 1 | `disputeId`, `settlementItemId`, terminal `state`, `heldAmountKrw=0`, `settlementAdjustmentId`(`ACCEPTED`만 required), `currency`, `decidedAt` | Dispute terminal/Audit/outbox를 저장하는 decision transaction. `settlement-dispute:{disputeId}:decided` | Operations |

명시적으로 conditional 또는 nullable이라고 적힌 field 외에는 모든 field가 required다. 식별자는
canonical UUID string, `*At`은 offset을 가진 `Instant`, `settlementDate`는 `Asia/Seoul`의
`YYYY-MM-DD`, `currency`는 `KRW`다. `grossPaidKrw`, `feeKrw`, `couponCostKrw`, `pointCostKrw`,
`benefitCostKrw`, `netSettlementKrw`, `cashRefundedKrw`와 일반 accrual/restoration `amountKrw`는
non-negative 64-bit integer KRW다. delta, `PointsAdjustedV1.amountKrw`,
`SettlementAdjustmentCreatedV1.amountKrw`, `SettlementBatchConfirmedV1.netSettlementKrw`와
Dispute의 expected/held amount는 signed 64-bit integer KRW다. Dispute event에는 evidence
reference, 자유 입력 reason, actor와 Idempotency-Key를 포함하지 않는다.
`feeRateBps`는 `0..10000` integer다. `*Source`와 `reasonCode`는 producer가 만든 stable opaque
identifier/closed vocabulary이며, consumer가 자유 text 또는 current state로 채우지 않는다.

`completionDisposition`은 `COMPLETED_ORDER`, `PRE_COMPLETION_ORDER` 또는
`PRE_ACCEPTANCE_CANCELLATION`이다. result transaction에서 이미 immutable completion fact가
있으면 `COMPLETED_ORDER`, 고객 취소·매장 거절처럼 완료 없이 미수락 종료된 Refund source면
`PRE_ACCEPTANCE_CANCELLATION`, 그 밖의 완료 전 품목 Refund면 `PRE_COMPLETION_ORDER`다.

`COMPLETED_ORDER`는 `orderCompletedAt`, 서울 기준 `settlementDate`,
`settlementItemSource`와 effect를 모두 포함한다. `settlementItemSource`는 해당 Order completion의
logical source `order:{orderId}:completed:{aggregateVersion}`와 동일하다. Plan 20은 이 값을
SettlementItem의 immutable `itemSource`로 사용한다. `PRE_COMPLETION_ORDER`는 effect를 포함하지만
아직 존재하지 않는 완료 시각·정산일·Item source를 넣지 않는다. Settlement와 Analytics는 이를
source-aware pending input으로 보존한 뒤 `OrderCompletedV2`와 결합하며 current Order나 policy를
재조회해 완성하지 않는다. 완료 없이 terminal이 되면 terminal source와 일치하는 명시적
exclusion/reconciliation로 끝낸다. `PRE_ACCEPTANCE_CANCELLATION`에는 완료 필드와 effect를 모두
넣지 않으며 Settlement는 이를 0원 Adjustment로 바꾸지 않고 ADR-048의 `NOT_APPLICABLE` Audit
경로로 처리한다. Analytics는 세 disposition 모두 실제 `cashRefundedKrw`의 refund-date fact로
사용하되 completion-date adjustment는 완료 source와 결합된 effect에만 적용한다.

`SettlementRefundEffect`의 모든 delta는 signed KRW이며, Refund로 감소하는 값은 음수다.
Payment는 이 snapshot을 Refund 요청 때 저장한 immutable line allocation과 completion snapshot으로
만 계산한다. consumer가 Item의 현재 summary, fee 계약 또는 Campaign을 재조회해 계산하지 않는다.

부분 Refund 하나의 delta는 같은 Payment lock 아래 그 Refund 직전까지 성공한 immutable allocation을
`before`, 현재 Refund까지 포함한 allocation을 `after`로 두고 다음처럼 계산한다. 이 누적 차분은
여러 Refund에 걸친 원 미만 remainder를 마지막 Refund까지 정확히 보존한다.

```text
grossPaidDeltaKrw = -(after.refundedGrossKrw - before.refundedGrossKrw)
feeDeltaKrw = -(floor(after.refundedCashKrw * feeRateBps / 10_000)
                - floor(before.refundedCashKrw * feeRateBps / 10_000))
couponCostDeltaKrw = -(floor(after.refundedCouponKrw * storeCouponShareBps / 10_000)
                       - floor(before.refundedCouponKrw * storeCouponShareBps / 10_000))
pointCostDeltaKrw = -(after.refundedMatchingStorePointKrw
                      - before.refundedMatchingStorePointKrw)
benefitCostDeltaKrw = couponCostDeltaKrw + pointCostDeltaKrw
netSettlementDeltaKrw = grossPaidDeltaKrw - feeDeltaKrw - benefitCostDeltaKrw
```

쿠폰이 없으면 store share와 coupon cost delta는 0이다. store point는 immutable Refund point
allocation의 `issuerType=STORE`이고 `issuerReference=Order.storeId`인 금액만 센다. 다른 store
reference, allocation/snapshot 합계 불일치, overflow 또는 누적 delta가 원 snapshot의 gross/fee/
coupon/point 상한을 넘으면 event를 만들지 않고 result transaction을 rollback한다.

Analytics persistence는 event payload의 customer, account, order, payment, refund, Batch와 Item
식별자를 public dimension, log 또는 metric tag에 저장하지 않는다. receipt에는 opaque logical
source만 보존한다.

### Producer ownership and cutover

| Checkpoint | Owner plan | Required outcome before Analytics enables the consumer |
|---|---|---|
| immutable `OrderSettlementInputSnapshot`, V2 payload factory/validator/fixture | Plan 15 | Merchant terms, Campaign burden, PointLot issuer and Order snapshot tie-out tests pass |
| `OrderCompletedV2` and `SettlementItemCreatedV1` | Plan 20 | Plan 15 immutable input/factory, Ordering guarded producer/outbox cutover, separate Settlement consumer, source unique and outbox contract tests pass |
| `PaymentRefundedV1`, `PointsAccruedV1`, `PointsRestoredV1` | Plan 16 | Payment/Loyalty producer transaction and allocation/snapshot tests pass; it does not own an Order completion event |
| `PointsAdjustedV1` | Point adjustment plan | adjustment transaction, permission gate and outbox contract tests pass |
| `SettlementAdjustmentCreatedV1` | Settlement lifecycle plan | adjustment source/reason unique and outbox contract tests pass |
| `SettlementBatchConfirmedV1`, `SettlementDisputeFiledV1`, `SettlementDisputeDecidedV1` | Settlement lifecycle plan | guarded transition, Audit/outbox rollback, 실제 Dispute/Operations listener completion과 민감 field 부재 검증 통과 |
| receipt/delta/freshness projection | Analytics plan | every enabled producer row above has actual outcome evidence |

`OrderCompletedV1` is frozen. Plan 20 may replace its producer with `OrderCompletedV2` only after a
version-cutover inventory proves that no deployed consumer or incomplete `OrderCompletedV1` publication
needs the old payload. The replacement is one producer/consumer/fixture checkpoint and does **not**
dual-publish V1 and V2. If the inventory is nonzero or unknown, Plan 20 stops and records a separate
forward-compatibility ADR before changing the producer. Existing V1 payload semantics are not silently
extended with required fields.

Plan 15 owns only the immutable input materialization, V2 payload factory or typed mapper, validation and
contract fixture. It does not store the completion outbox row, cut over/activate the producer, drain V1
publication or operate a Settlement consumer. Plan 20 owns all of those V2 producer/cutover steps and the
Settlement `OrderCompletedV2` consumer. The Ordering producer transaction and Settlement consumer transaction
are separate local transactions; sharing an event never makes them one database transaction. Plan 16 owns only
the named Refund/Loyalty producers in the table and is not an `OrderCompletedV2` producer.

**Plan 15 implementation checkpoint (2026-08-02):** `OrderCompletedV2`의 public Kotlin payload,
factory, validator와 exact JSON fixture가 구현됐다. Factory input은 completed Order fact,
approved Payment fact와 persisted `OrderSettlementInputSnapshot`으로 제한되고 amount/source/time/
currency/version을 fail-closed로 검증한다. production reference inventory에는 factory 외 V2
producer, outbox save, listener 또는 Settlement consumer가 없으므로 이 checkpoint는 Plan 20
activation이나 V1 drain 완료 증거가 아니다.

**Plan 16 implementation checkpoint (2026-08-02):** `PaymentRefundedV1`, `PointsAccruedV1`,
`PointsRestoredV1` public Kotlin payload, validator와 exact JSON fixture가 구현됐다. Payment는
Order-first result orchestration transaction에서 Plan 12 immutable allocation과 Plan 15 snapshot의
누적 차분을 계산하고 Refund owner result와 existing `event_publication` target row를 함께 저장한다.
Loyalty는 gross accrual과 각 restoration owner result에 대응하는 target row를 같은 owner
transaction에 저장한다. exact replay/conflict, 세 disposition, delayed terms change,
snapshot/allocation/publication rollback이 PostgreSQL 테스트를 통과했다. `OrderCompletedV2`,
Settlement/Analytics consumer와 projection은 구현하지 않았다.

**Plan 20 implementation checkpoint (2026-08-03):** V1 incomplete/deployed consumer inventory 0을
확인하고 guarded completion의 publication을 `OrderCompletedV2`로 교체했다. V2 outbox save는 Order
`COMPLETED` transition과 원자적이고 Settlement listener는 별도 local transaction에서 immutable
payload만으로 Batch/Item/Audit/`SettlementItemCreatedV1` target을 저장한다. 고객 취소
`PaymentRefundedV1`은 event 누락을 live state로 채우는 용도가 아니라 ADR-048의 명시적
Order/Refund terminal evidence 확인에만 public typed query를 사용한다.

**Settlement lifecycle implementation checkpoint (2026-08-03):**
`SettlementBatchConfirmedV1`, `SettlementAdjustmentCreatedV1`,
`SettlementDisputeFiledV1`과 `SettlementDisputeDecidedV1` typed payload/validator/persistent producer가
활성화됐다. Batch target은 실제 Dispute listener, 두 Dispute target은 실제 Operations listener가
완료한다. completed Refund consumer는 confirmed Item에만 Adjustment event를 만들고 unconfirmed
target은 publication retry에 남긴다. accepted Dispute Adjustment는 event consumer가 아니라
Dispute의 Settlement public command로 선커밋되며 decision event에는 evidence/actor/client key가 없다.

### Plan 13의 frozen V1 trigger-only boundary

ADR-073은 Plan 13에 한해 `OrderCompletedV1`을 payload가 없는 frozen trigger로 유지하면서
Order 생성 transaction에 저장한 immutable `OrderPointAccrualSnapshot`을 typed Ordering
boundary로 읽는 것을 허용한다. 이것은 현재 Order Aggregate나 live policy를 조회해 event
payload의 누락 값을 채우는 consumer가 아니다. boundary는 completion source/version과
snapshot hash를 검증할 수 있어야 하고, gross accrual·unit allocation·issuer·만료 계산에
필요한 immutable 입력 및 Refund 성공 시각과 완료 시각의 관계를 판정하는 durable fact만
반환한다.

snapshot은 없는 경우를 0원 적립으로 대체하지 않는다. 누락·변조·source 불일치 또는
boundary 실패는 Loyalty source 처리의 retry/manual-review failure이며, public event를
새로 추가하거나 `OrderCompletedV1` field를 확장하지 않는다. Plan 20의 V2 producer/cutover
소유권도 바꾸지 않는다.

2026-08-02 Plan 13 implementation은 이 예외를 frozen `OrderCompletedV1` listener로 활성화했다.
listener는 persisted Order completion version과 V16 snapshot hash를 검증하고 Payment eligibility와
Loyalty accrual owner transaction을 호출한다. Plan 16이 같은 owner transaction에
`PointsAccruedV1` target publication을 추가했으며 frozen V1 payload와 Plan 20의 V2 ownership은
변경하지 않았다.

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
read API and availability/failure contract for every consumer. ADR-073은 Plan 13의 ordinary accrual에
한해 이 계약을 정의한다. 그 밖의 financial consumer에 대한 일반 owner projection은 없다.

### Add fields to `OrderCompletedV1` in place

New required fields change a frozen V1 contract and make old persisted publications ambiguous. V2 gives a
separate compatibility gate and exact payload meaning.

## Rationale

The completion, Refund and ledger transactions already know the immutable financial result when they commit.
Putting that fact in the same durable publication lets Settlement and Analytics converge from a stable source
without cross-context latest-state reads. A named owner checkpoint prevents Analytics from guessing which
unimplemented event shape to consume.

## Consequences

- Plan 15 owns settlement input source/materialization, payload factory/validator/fixture; Plan 20 owns the
  `OrderCompletedV1 -> V2` cutover, guarded completion outbox producer and Settlement consumer, and cannot
  start them before Plan 15 outcome evidence.
- Plan 16 must materialize Refund allocation-derived Settlement effect from Plan 12 allocation and Plan 15 snapshot before the success event is stored.
- Event catalog, Kotlin event API and producer tests must change together at each checkpoint; the catalog is
  not proof that a producer has already been implemented.
- Eventing이 explicit listener target을 JDBC로 직접 enqueue할 때 최초 row는 `FAILED`,
  `completion_attempts=0`, `last_resubmission_date=NULL`로 저장한다. 현재 Modulith의 bounded
  `ResubmissionOptions`는 FAILED publication만 선택하므로, 실제 listener를 호출하지 않은 row를
  `PUBLISHED`로 쓰면 durable하지만 영구히 전달되지 않는다. 이 `FAILED`는 owner fact 실패나
  Provider 실패가 아니라 “아직 consumer completion이 없는 bounded-delivery 대상”이다.
- Analytics starts with only the producers whose exact contract and validation evidence are complete.
- Plan 13은 frozen V1 payload를 확장하지 않고 ADR-073의 immutable snapshot boundary를 통해서만
  ordinary accrual을 materialize한다.

## Verification

**Plan 15 contract evidence (2026-08-02):** exact fixture serialization, envelope type/version,
Payment payable equality, benefit-only zero payable, negative net and delayed immutable mapping tests
passed. Current Merchant/Campaign/PointLot values are absent from the factory API. Plan 20 outbox
atomicity, cutover inventory and Settlement consumer checks remain intentionally not run because those
components are outside this checkpoint.

**Plan 12 boundary evidence (2026-08-01):** V15 and the Payment result transaction now provide the
immutable Refund line/point allocation, succeeded-at and policy lineage that Plan 16 may serialize. Plan 12
does not define or publish `PaymentRefundedV1`/`PointsRestoredV1`, does not add a public event class, and does
not treat the Payment-owned restoration worker handoff as the Plan 16 integration-event producer.

- contract tests reject a payload with a missing required snapshot or a payload version/type mismatch;
- fee/coupon/point cost and `netSettlementKrw` in `OrderCompletedV2` tie out to immutable Order snapshots;
- Plan 15 fixture/validator failure blocks the Plan 20 cutover, while a Plan 20 outbox save failure does not
  become a successful completion publication;
- the Ordering V2 producer and Settlement V2 consumer commit independently and the consumer does not live-read
  Merchant, Campaign, PointLot, Order snapshot or Payment to complete an event payload;
- `PaymentRefundedV1` for completed, pre-completion and pre-acceptance-cancellation sources takes the correct
  Settlement and Analytics branch without live mutable policy or terms reads;
- same logical source with a new event ID is idempotent, while same source with a changed payload fails;
- delayed event after a policy or contract change reproduces the original date and amount;
- V1 cutover inventory blocks V2 producer activation when old publication or consumer evidence is nonzero.

**Plan 16 verification evidence (2026-08-02):** the exact financial-event focused suite and
event-contract/Modulith suite passed. `./gradlew clean build` passed 243 tests with no failure, error or skip.
Missing/failed snapshot, allocation and target-publication writes roll back the owner result; cumulative
partial refunds retain rounding remainder, and a later Merchant terms version does not change the event effect.

**Consumer activation correction (2026-08-03):** 고객 취소 terminal consumer e2e에서 direct JDBC
target가 `PUBLISHED`로 저장되면 Modulith 2.1 bounded recovery 대상이 되지 않는 것을 확인했다.
최초 상태를 `FAILED`/attempt 0으로 수정하고 recovery worker가 Notification과 Settlement listener를
실제로 완료하는 통합 테스트로 검증했다. reserved Analytics target은 기존 filter대로 미완료로 보존한다.

**PointsAdjustedV1 producer evidence (2026-08-04):** Loyalty command transaction이
PointAccount commit version, immutable adjustment source, child transaction signed effect 합과 CREDIT
issuer type을 `beanflow.analytics.points-adjusted-v1` target으로 저장한다. DEBIT은 issuer field를
생략하고 raw actor/evidence/key/issuer reference를 payload에 포함하지 않는다. same command replay는
publication을 추가하지 않는다. Analytics listener/receipt/projection은 후속 Analytics plan에 남겼다.

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
- [ADR-071](ADR-071-settlement-input-snapshot-foundation.md)
- [ADR-073](ADR-073-order-point-accrual-snapshot.md)
