# ADR-071: 정산 입력 snapshot의 원천과 주문 시점 물질화

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owner:** [Settlement input snapshot foundation](../exec-plans/completed/customer-order-cancellation-15-settlement-input-snapshot-foundation.md)

## Context

ADR-017과 ADR-068은 완료 주문의 수수료·쿠폰·포인트 비용과 순정산을 거래 당시의
immutable fact로 재현하도록 요구한다. 그러나 현재 `Order`는 상품 가격, coupon discount,
point 사용액과 payable amount만 보존한다. Merchant에는 매장 정산 계약이 없고,
Campaign/CouponReservation에는 비용 부담 비율이 없으며, PointLot issuer snapshot은 Plan 10의
선행 작업이다.

따라서 Plan 20이 완료 event를 소비할 때 현재 Merchant 계약, Campaign 또는 PointLot을
재조회하면 과거 정산이 바뀐다. 값이 없다고 platform 부담, 0원 또는 현재 정책으로 채우는
fallback도 BR-18~20과 failure semantics에 어긋난다.

## Decision

Ordering은 주문 생성의 local transaction에서 `OrderSettlementInputSnapshot` 하나를 함께
저장한다. 이 row는 `order_id` unique인 Ordering-owned immutable child이며, 완료·정산 시점에는
아래 저장값만 읽는다. Settlement, Analytics와 event consumer는 Merchant, Promotion, Loyalty의
현재 state로 snapshot을 보완하거나 재계산하지 않는다.

### 원천 owner와 snapshot field

| 입력 | 원천 owner | 주문 시점에 고정할 값 | `OrderSettlementInputSnapshot` 반영 |
|---|---|---|---|
| 매장 수수료 | Merchant `StoreSettlementTerms`의 immutable version | `termsVersionId`, `feeRateBps (0..10000)` | `feeRateBps` |
| 쿠폰 비용 | Promotion Campaign과 CouponReservation | `costBearer`, platform/store share bps, final KRW legs | store 부담 leg를 `couponCostKrw`로 저장 |
| 포인트 비용 | Loyalty PointReservation allocation과 Plan 10의 PointLot issuer snapshot | allocation별 `issuerType`, `issuerReference`, final KRW | 현재 주문 store가 부담할 합계를 `pointCostKrw`로 저장 |
| 매출·결제 | Ordering Order price snapshot과 Payment approval contract | `subtotalKrw`, `payableKrw` | `grossPaidKrw`와 private `feeBaseKrw` |

`grossPaidKrw`는 ADR-068 event의 historical field name이지만, 의미는 혜택 적용 전
`Order.subtotalKrw`인 **gross merchandise amount**다. 수수료 기준 금액은 별도 immutable
`feeBaseKrw = Order.payableKrw`이며, Payment approval은 그 값과 정확히 일치해야 한다. 이
일치가 깨지면 Order가 `COMPLETED`로 전이하거나 `OrderCompletedV2`를 발행하지 않고 명시적
reconciliation/manual-review 경로로 남는다.

금액은 BR-02의 integer KRW 규칙을 따른다.

```text
feeKrw          = floor(feeBaseKrw * feeRateBps / 10_000)
benefitCostKrw  = couponCostKrw + pointCostKrw
netSettlementKrw = grossPaidKrw - feeKrw - benefitCostKrw
```

모든 항은 0 이상이어야 하고 `netSettlementKrw`도 음수가 아니어야 한다. 조건을 만족하지
않는 source 조합은 0 또는 음수 정산으로 보정하지 않고 주문 생성 transaction을 rollback한다.

쿠폰의 `PLATFORM`은 `(platform=10000, store=0)`, `STORE`는
`(platform=0, store=10000)`, `SHARED`는 두 non-negative bps의 합이 10000이다. final
`storeCouponCostKrw`는 `floor(discountKrw * storeShareBps / 10000)`이고,
`platformCouponCostKrw = discountKrw - storeCouponCostKrw`다. 두 leg의 합은 항상
CouponReservation의 final discount와 같아야 한다. event의 `couponCostKrw`에는 store leg만
넣는다.

Point allocation도 Lot별 final integer KRW로 합산한다. `issuerType=STORE`인 Lot은
`issuerReference`가 Order의 `storeId`와 일치할 때만 해당 store 비용으로 센다. `PLATFORM`과
`BRAND` issuer는 `pointCostKrw`에 넣지 않는다. 다른 store reference, missing issuer 또는
allocation sum mismatch는 cross-store point program을 추정하지 않고 실패한다.

### Materialization transaction과 readiness gate

- Merchant는 store별 immutable `StoreSettlementTerms` version을 소유한다. 주문 생성 시점에
  정확히 하나의 applicable version이 있어야 한다. 기존/새 store에 verified terms가 없으면
  해당 store의 주문 생성을 `SETTLEMENT_INPUT_UNAVAILABLE`로 실패시키며 default fee rate를
  사용하지 않는다.
- Promotion은 Campaign에 burden source를 추가하고 CouponReservation에 final two-leg snapshot을
  저장한다. active Campaign의 기존 row를 임의 부담 주체로 backfill하지 않는다. inventory에서
  값을 확인할 수 없으면 migration/activation을 멈춘다.
- Loyalty는 Plan 10이 verified PointLot issuer snapshot을 먼저 완성한다. Plan 15는 reservation
  allocation 결과에 issuer snapshot을 포함하도록 공개 application boundary를 확장할 수 있지만,
  PointLot issuer migration을 다시 만들지 않는다.
- Ordering은 price, CouponReservation, PointReservation 및 Merchant terms projection이 모두
  검증된 뒤 Order와 `OrderSettlementInputSnapshot`을 같은 local transaction에 저장한다. source
  read, snapshot save 또는 기존 `order_id` snapshot의 hash/tie-out 검증 중 하나라도 실패하면
  Order·reservation의 부분 성공을 반환하지 않는다. 외부 Payment/Provider 호출은 이 transaction
  밖에 남는다.
- snapshot은 create-order idempotency payload가 아닌 order-derived immutable fact다. 동일
  idempotent order replay는 기존 snapshot과 동일한 hash/tie-out일 때만 응답을 재생한다.

### Event와 downstream boundary

Plan 15는 completion transaction이 사용할 immutable input, `OrderCompletedV2` payload factory 또는 typed
mapper, validator와 contract fixture를 제공한다. Plan 20은 Plan 15의 actual migration, source and
contract-test evidence를 직접 선행조건으로 소비하고, Ordering guarded completion transaction에서 factory가
받은 Order snapshot과 matching Payment approval fact만 사용해 ADR-068 field와 V2 outbox를 atomically
저장한다. `feeRateBps`, `feeKrw`, `couponCostKrw`, `pointCostKrw`, `benefitCostKrw`, `netSettlementKrw`를
현재 계약·Campaign·Lot에서 새로 읽는 구현은 금지한다. Plan 15는 outbox save, V1→V2 cutover/activation,
V1 drain/deployment gate 또는 Settlement consumer를 소유하지 않는다.

`PaymentRefundedV1.settlementRefundEffect`도 이 same snapshot과 immutable refund line allocation에서
계산한다. Plan 20 Settlement consumer와 Analytics는 V2 payload가 없는 경우 값을 추론하지 않고
publication retry 또는 `MANUAL_REVIEW`로 남긴다. Ordering producer와 Settlement consumer는 별도의 local
transaction이며 consumer가 mutable source를 재조회해 producer input을 복구하지 않는다.

## Implementation Status

Plan 15는 2026-08-02에 다음 foundation까지 구현하고 완료했다.

- V18 `merchant_store_settlement_terms`: immutable source/version, half-open effective interval,
  store별 overlap 방지와 applicable-version exact-one lookup. 기존 Store에 임의 fee를 채우지
  않으며 terms가 없는 Store의 주문만 명시적으로 실패한다.
- V19 Campaign/CouponReservation: burden bearer/share와 source/version, final platform/store KRW
  legs 및 immutable reservation constraint. active legacy Campaign 또는 기존 reservation은
  verified source 없이 migration하지 않는다.
- V20 `ordering_order_settlement_input_snapshot`: Order FK unique/exactly-one, owner source FK와
  amount formula/tie-out CHECK 및 update/delete 금지 trigger. 기존 Order는 source를 추측하지
  않고 migration activation을 중단한다.
- `StoreSettlementTermsOperations`, `CouponReservationOperations`,
  `PointReservationOperations`, `OrderSettlementInputSnapshotOperations` 공개 DTO 경계와 주문
  생성 transaction 통합. store issuer reference는 Order store UUID와 exact-match한다.
- `OrderCompletedV2` factory/validator/fixture는 구현했지만 outbox row, V1→V2 전환, producer
  activation, Settlement consumer/모델은 구현하지 않았다.

## Alternatives Considered

### SettlementItem 생성 시 현재 계약과 Campaign을 조회

모델 추가는 줄지만 계약 변경 뒤 과거 금액이 달라지고 late event 재생도 재현되지 않는다.

### Plan 20이 임의 fee/burden default를 사용

빠르게 Item을 만들 수 있어 보여도 BR-18~20의 owner 비용과 Accepted event contract를 위반한다.

### event payload에 locator만 넣고 consumer가 source를 조회

별도의 versioned immutable projection과 availability/retention contract가 없으므로 지금은
consumer의 live read로 변질된다.

## Consequences

- Plan 15는 Merchant, Promotion, Loyalty, Ordering을 함께 변경하는 schema-writing foundation이다.
- Plan 20은 snapshot foundation과 payload factory/validator/fixture가 완료되기 전 `OrderCompletedV2`
  cutover, guarded completion outbox producer, SettlementItem migration 또는 endpoint activation을 시작하지 않는다.
- `StoreSettlementTerms`, Campaign burden terms 또는 PointLot issuer가 없는 legacy input은
  guessed backfill 없이 migration/deployment blocker가 된다.
- ADR-068의 `grossPaidKrw` meaning과 net formula가 명시되어 event producer/consumer contract
  tests가 같은 산식을 검증할 수 있다.

## Verification

- **Plan 10 implementation evidence (2026-08-01):** the Loyalty application boundary now
  returns `PointReservationAllocation(pointLotId, issuerType, issuerReference,
  finalAllocationKrw)` from immutable PointLot state. V14 verifies every legacy issuer
  mapping or fails closed before Plan 15 can use it.
- fee terms, Campaign burden 또는 PointLot issuer 변경 뒤 기존 Order snapshot과 V2 payload가
  변하지 않는다.
- `PLATFORM`, `STORE`, `SHARED` coupon의 two-leg sum, mixed issuer PointReservation, 0원
  benefit-only payment와 integer remainder를 tie-out한다.
- missing/ambiguous terms, invalid share sum, cross-store issuer, negative net, approval/payable
  mismatch와 snapshot persistence failure는 success event나 SettlementItem을 만들지 않는다.
- duplicate create/replay와 delayed completion은 같은 `order_id` snapshot 하나와 같은 event
  payload hash로 수렴한다.
- Plan 15는 snapshot/factory validation만 제공하고 Plan 20만 V2 outbox save/cutover를 수행하며,
  producer와 Settlement consumer는 separate local transaction으로 수렴한다.
- **Implementation evidence (2026-08-02):** V18–V20 Testcontainers migration, applicable terms
  전후/overlap/동시 future publication, PLATFORM/STORE/SHARED leg와 remainder, mixed issuer와
  cross-store mismatch, exactly-one/hash/replay/persistence rollback, Payment mismatch/V2 contract가
  통과했다. 외부 runtime DB가 없어 non-local row backfill은 수행하지 않았고, migration은
  active legacy Campaign/reservation 또는 any legacy Order를 fail-closed로 차단한다.

## Related Decisions

- BR-02, BR-18, BR-19, BR-20
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-063](ADR-063-partial-refund-expired-point-restoration.md)
- [ADR-068](ADR-068-immutable-integration-event-snapshots.md)
