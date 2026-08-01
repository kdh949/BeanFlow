# 정산 입력 snapshot을 주문 생성 시점에 물질화한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

Plan 20이 완결 Order를 정산할 때 현재 Merchant 계약, Campaign 또는 PointLot을 다시 읽지 않도록,
각 Order에 immutable settlement input 하나를 만든다. 완료 event와 SettlementItem은 이 row와
matching Payment approval fact만 읽어 재현 가능한 금액을 만들며, source가 누락된 주문은 0원이나
platform default로 성공하지 않는다.

## Current State

- ADR-017/068은 fee, coupon, point cost snapshot을 요구하지만 current Order에는 subtotal,
  coupon discount, points applied, payable amount만 있다.
- Merchant Store에는 settlement terms가 없고, Campaign/CouponReservation에는 burden terms/final
  legs가 없다.
- Plan 10은 PointLot issuer snapshot precheck/migration을 단독 소유한다. 이 plan은 그 schema를
  다시 만들지 않는다.
- Plan 20은 `OrderCompletedV2` consumer와 SettlementItem을 소유하지만 required financial input을
  추측할 수 없다.

## Definitions

- **StoreSettlementTerms:** Merchant가 store별로 versioned하게 보존하는 fee-rate 계약 fact.
- **Coupon cost leg:** CouponReservation의 final discount를 platform/store에 나눈 immutable KRW 값.
- **OrderSettlementInputSnapshot:** `order_id`당 하나인 Ordering-owned settlement input child.
- **Fee base:** `Order.payableKrw`; Payment approved amount가 같아야 하는 fee calculation basis.

## Scope

### In Scope

- Merchant `StoreSettlementTerms` version persistence/read application boundary와 verified initial
  terms readiness gate
- Campaign burden terms, CouponReservation two-leg snapshot과 existing active Campaign inventory
- Plan 10 PointLot issuer snapshot을 읽는 PointReservation allocation contract
- Ordering `OrderSettlementInputSnapshot` domain/persistence, create-order transaction tie-out와
  immutable completion input query
- completion에 필요한 immutable input, `OrderCompletedV2` payload factory 또는 typed mapper,
  payload validation과 contract fixture, Plan 20 handoff

### Non-goals

- SettlementBatch/Item, Batch calculation, Adjustment 또는 payout 구현
- Merchant 계약의 public UI, invoice/tax/PG fee model, cross-store point program
- current contract/Campaign/Lot read를 completion consumer fallback으로 허용
- PointLot issuer schema, partial-refund allocation 또는 Plan 11 policy migration 재구현
- actual completion transaction의 `OrderCompletedV2` outbox 저장, `OrderCompletedV1 -> V2` cutover,
  V2 producer activation, Settlement consumer와 V1 publication drain/deployment gate

## Business Rules and Invariants

- Order snapshot은 exactly one이며 order ID, source version/IDs와 immutable monetary fields가
  complete해야 한다.
- fee rate는 `0..10000`; coupon share bps는 `PLATFORM`, `STORE`, `SHARED` semantics와 합계
  10000을 지킨다.
- store coupon leg + platform coupon leg는 final discount와 같고, store point cost는 matching
  `STORE` issuer allocation의 합이다.
- `grossPaidKrw=Order.subtotalKrw`, `feeBaseKrw=Order.payableKrw`, fee/benefit/net formula는
  ADR-071을 따른다. net이 음수이거나 any tie-out이 깨지면 Order를 생성하지 않는다.
- current terms/Campaign/Lot 변경은 기존 snapshot을 수정하지 않으며, completed event는 그
  snapshot만 사용한다.

## Architecture and Transaction Boundaries

- Ordering Application Service는 Merchant terms, Promotion coupon-reservation cost legs, Loyalty
  point-reservation issuer allocations을 public application boundary로 요청한다. Controller는
  다른 Context repository를 직접 호출하지 않는다.
- Order, reservation state와 `OrderSettlementInputSnapshot`은 create-order local transaction에서
  함께 commit한다. external Payment/Provider call은 그 transaction 밖에 있다.
- 이 plan은 completion transaction이 사용할 immutable snapshot, payload factory 또는 typed mapper,
  validator와 contract fixture를 제공한다. actual outbox producer 교체와 activation은 Plan 20이
  소유한다.
- Merchant terms update와 create-order가 경쟁하면 versioned terms read가 하나의 applicable
  version만 선택하게 한다. no-row/overlap/expired terms는 default fee 없이 실패한다.
- Plan 20의 Ordering guarded completion transaction은 factory가 받은 immutable snapshot과 approved
  Payment payable equality를 다시 guard하고 V2 publication을 atomically 저장한다. Plan 15는 그
  transaction이나 Settlement consumer transaction을 시작하지 않는다.

## Alternatives Considered

- Plan 20에서 current owner Aggregate를 조회: historical settlement가 변하므로 제외한다.
- SettlementItem만 snapshot을 보관: Analytics completion fact와 producer transaction이 먼저
  필요하므로 제외한다.
- missing source를 platform 부담으로 backfill: financial ownership을 추정하므로 제외한다.

## Failure Semantics

- missing/ambiguous StoreSettlementTerms, Campaign burden, PointLot issuer 또는 allocation tie-out은
  `SETTLEMENT_INPUT_UNAVAILABLE`로 create-order transaction을 rollback한다.
- legacy data inventory에서 verified source가 없으면 migration/activation을 중단한다. guessed
  fee, share, issuer, `(0,0)` or null snapshot은 허용하지 않는다.
- snapshot hash/source conflict, Payment approval/payable tie-out precondition 또는 payload validation
  failure는 Plan 20 handoff를 막는다. 해당 input이 없거나 불일치하면 Plan 20은 V2 producer activation이나
  SettlementItem 생성을 진행하지 않는다.

## Data and Migration

Plan 10 issuer outcome 후, migration writer lane을 얻은 최신 main에서 다음 object를 이 plan만
소유한다.

1. Merchant `store_settlement_terms` version table: store FK, immutable version/source, effective
   interval, `fee_rate_bps`, overlap 방지와 applicable-version query index.
2. Promotion Campaign burden fields and CouponReservation final platform/store cost legs with required
   CHECK/sum constraints. active legacy Campaign inventory가 verified value를 제공하지 않으면 stop한다.
3. Ordering `order_settlement_input_snapshot`: order unique FK, terms version/source, gross/fee base,
   fee rate, coupon/point/benefit/net cost fields, immutable created timestamp and CHECK tie-outs.
4. no historical Order/Campaign/terms/lot value를 추정 backfill하지 않는다. clean-cutover gate가
   요구하는 existing-row inventory와 deployment evidence를 Outcomes에 남긴다.

PointLot issuer fields/legacy precheck는 Plan 10-owned이며 여기서 중복하지 않는다. refund allocation은
Plan 12가 소유한다.

## API and Event Contracts

- Merchant, Promotion, Loyalty는 Ordering이 snapshot을 materialize할 exact typed application DTO를
  제공한다. raw entity 또는 live mutable model을 노출하지 않는다.
- `OrderCompletedV2` public payload is unchanged from ADR-068. 이 plan은 required immutable input,
  payload factory 또는 typed mapper, validator와 producer contract fixture를 제공한다. Plan 20은 V1→V2
  cutover, guarded completion transaction의 outbox 저장과 producer activation을 소유한다.
- `PaymentRefundedV1.settlementRefundEffect` uses the same snapshot plus immutable line allocation;
  no new public HTTP endpoint is added by this plan.

## Milestones

1. Plan 10 issuer/precheck outcome and existing Merchant/Campaign/Order inventories를 검증한다.
2. StoreSettlementTerms and Campaign/CouponReservation burden schema/source API를 구현한다.
3. PointReservation issuer allocation boundary and OrderSettlementInputSnapshot transaction을 구현한다.
4. completion event input tie-out, missing source, concurrent terms change and legacy gate tests를 완성한다.
5. Plan 20 handoff evidence, ADR/OpenAPI/architecture documentation과 migration-release record를 갱신한다.

## Required Tests

- fee contract before/after change, no/overlapping/applicable terms and concurrent terms/order race
- PLATFORM/STORE/SHARED coupon final legs, bps remainder, active legacy Campaign missing burden gate
- mixed PLATFORM/BRAND/STORE point allocations, issuer reference mismatch and Plan 10 unresolvable lot
- gross/feeBase/fee/benefit/net tie-out, zero benefit-only payment and negative-net rejection
- duplicate create/replay snapshot hash, snapshot persistence fault rollback and Payment mismatch
- current terms/Campaign/issuer change followed by delayed completion produces unchanged V2 payload
- PostgreSQL CHECK/unique/index, Modulith boundary, OpenAPI/event contract and Testcontainers migration

## Validation Commands

```bash
./gradlew test --tests '*Order*' --tests '*SettlementInput*' --tests '*Merchant*' --tests '*Coupon*' --tests '*Point*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

- `beanflow.settlement.input.snapshot.count{outcome}`
- `beanflow.settlement.input.unavailable.count{source}`
- `beanflow.settlement.input.tie_out.failure.count{reason}`

source/reason은 closed vocabulary만 쓴다. order/store/customer/terms IDs, fee rate, raw cost legs와
issuer reference는 tag/log field에 넣지 않는다.

## Documentation Updates

- ADR-017/068/071, BR-02/18/19/20, aggregate invariants and transaction boundaries
- Context map, event catalog, migration release evidence and Plan 20 Current State/Progress
- this plan의 actual inventory, schema and contract-test Outcomes

## Progress

- [ ] Plan 10 issuer outcome and data inventory
- [ ] Merchant/Campaign burden source schema
- [ ] Ordering snapshot transaction
- [ ] completion/event tie-out
- [ ] migration/architecture/documentation evidence
- [ ] full validation

## Surprises & Discoveries

- 2026-08-01: current Order price snapshot alone does not contain Merchant fee terms, Campaign cost burden
  or PointLot issuer provenance, so completion cannot safely create ADR-068 amounts.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | settlement input is Order-owned immutable snapshot created with order/reservations | current owner data must not change completed settlement | ADR-071 |
| 2026-08-01 | Accepted | Plan 10 issuer outcome is a direct prerequisite; Plan 16 event producer와 Plan 20은 이 plan을 소비 | issuer migration과 immutable refund effect를 순환 없이 분리 | ADR-063, ADR-068, ADR-071 |
| 2026-08-01 | Accepted | Plan 15는 completion input/factory/fixture만 제공하고 Plan 20이 V2 outbox cutover를 소유 | snapshot materialization과 actual producer activation의 transaction owner를 분리 | ADR-068, ADR-071 |

## Outcomes & Retrospective

미구현 상태다. Merchant/Campaign/PointLot input inventory와 Plan 10 issuer evidence가 모두
verified가 되기 전에는 Plan 20의 V2 producer, SettlementItem 또는 settlement endpoint activation을
시작하지 않는다.

## Revision Notes

- 2026-08-01: ADR-068 completion snapshot source가 current model에 없다는 발견을 닫기 위해
  Plan 20 앞의 dedicated foundation으로 작성했다.
- 2026-08-01: Plan 15에서 V2 outbox/cutover ownership을 제거하고 immutable input, mapper, validator와
  contract fixture handoff로 한정했다.
