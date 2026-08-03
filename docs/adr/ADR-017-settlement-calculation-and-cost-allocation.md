# ADR-017: 완료일 정산과 혜택 비용 배분

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-16~BR-21은 완료 주문의 일별 정산, 거래 당시 수수료율, 쿠폰·포인트 비용 부담과
확정 후 음수 조정을 함께 정의한다.

## Decision

- SettlementItem은 `OrderCompleted`를 원천으로 주문별 한 번 생성한다.
- 매장·`Asia/Seoul` 완료일별 SettlementBatch를 만들며 실제 계좌 지급은 포함하지 않는다.
- 수수료는 최종 실결제액과 주문 당시 매장 계약 수수료율 snapshot으로 계산한다.
- 쿠폰 부담 주체와 분담률, 사용 PointLot의 발급 주체별 비용을 주문 확정 snapshot에서
  가져온다.
- `CONFIRMED` Batch와 Item은 수정하지 않는다. 이후 환불·이의 판정은
  SettlementAdjustment로 기록하고 음수 잔액은 다음 Batch로 이월한다.
- ADR-048에 따라 `COMPLETED`된 적 없는 매장 수락 전 고객 취소와 그 성공 Refund는
  SettlementItem·SettlementAdjustment를 만들지 않는다. Settlement consumer는
  source당 append-only AuditRecord를 남기고 `NOT_APPLICABLE`로 완료한다.
- ADR-049에 따라 만료 원 쿠폰 대신 발급한 보상 쿠폰은 원 Campaign의 비용 부담
  snapshot을 승계하고, 보상 발급 시점이 아니라 미래 완료 주문에서 실제 사용될 때
  그 SettlementItem에 비용을 반영한다.
- ADR-066에 따라 수동 양수 `ADJUSTMENT`로 만든 PointLot도 입력 issuer snapshot을
  보존한다. command 시점에는 SettlementItem이나 SettlementAdjustment를 만들지 않고,
  이후 사용될 때만 해당 snapshot을 비용 배분에 사용한다.
- **Batch foundation amendment (2026-08-01):** `SettlementItem`이 Batch별 Item 조회와
  immutable confirmation 전에 반드시 귀속될 수 있도록, Plan 20은 완료 event consumer에서
  `(storeId, settlementDate)`의 최소 `OPEN` Batch를 멱등 생성한다. Batch calculation,
  confirmation, summary와 Adjustment는 이 foundation을 확장하는 후속 lifecycle의 책임이다.
  schema object별 migration ownership과 closed-Batch late Item failure path는 ADR-067을 따른다.
- **Settlement input amendment (2026-08-01):** 수수료 계약, Coupon burden과 PointLot issuer의
  실제 원천·주문 시점 materialization, `grossPaidKrw`/fee-base/net formula와 missing-source
  failure는 ADR-071이 canonical이다. Plan 20은 해당 snapshot foundation outcome 없이 현재
  Merchant/Campaign/Loyalty 값을 조회해 Item을 만들지 않는다.
- **Implementation evidence (2026-08-02):** V18–V20과 주문 생성 Application Service가
  versioned Merchant terms, CouponReservation final two-leg burden, PointReservation issuer
  allocation을 Order당 하나의 immutable `OrderSettlementInputSnapshot`으로 물질화한다.
  `OrderCompletedV2` factory/validator는 이 snapshot과 matching Payment approval fact만 받아
  exact ADR-068 payload를 만든다. Plan 15는 completion outbox, producer activation, Settlement
  Item/Batch/Adjustment를 추가하지 않았다.
- **Implementation evidence (2026-08-03):** V21과 Plan 20이 V1 inventory 0 gate 뒤
  `OrderCompletedV2` producer/consumer를 활성화했다. consumer는 immutable payload로 store/date
  `OPEN` Batch를 insert-or-read하고 Order/source unique SettlementItem, Audit과
  `SettlementItemCreatedV1` target을 원자 저장한다.
- **Lifecycle implementation evidence (2026-08-03):** V28~V29와 lifecycle service가 매장·서울
  완료일 Batch를 500건 keyset으로 합산하고, immutable Item summary formula, 이전 Batch
  confirmation 선행조건, calculation/confirmation 시각과 negative carry를 DB와 Aggregate 양쪽에서
  보호한다. confirmation은 Audit와 `SettlementBatchConfirmedV1`을 원자 저장한다. completed
  Refund와 accepted Dispute는 Item snapshot을 수정하지 않고 이후 Adjustment로만 반영된다.
- **Point adjustment issuer evidence (2026-08-04):** V31 audited CREDIT command는 caller가
  명시한 issuer type/reference를 새 PointLot의 immutable snapshot으로 보존하며 command 시점에
  SettlementItem/Adjustment를 만들지 않는다. 이후 비용 배분은 기존 PointLot allocation 경계를
  그대로 사용한다.

## Alternatives Considered

- 결제 승인일 정산
- 정산 실행 시 현재 계약·캠페인 조회
- 완료일과 거래 당시 snapshot 기반 불변 원장

## Rationale

실제 상품 인도와 거래 당시 계약을 기준으로 결과를 재현하고 확정 이력을 보존한다.

## Consequences

- Order의 `OrderSettlementInputSnapshot`이 수수료·비용부담의 canonical immutable input이다.
  SettlementItem은 이후 V2 payload를 소비하며 current owner 값을 다시 읽지 않는다.
- 현재 유효 금액 조회는 Item, Adjustment와 이월 잔액을 합산해야 한다.

## Verification

- 결제일과 완료일이 다른 주문
- 계약·캠페인 변경 전후 주문
- PointLot 발급 주체 혼합 사용
- 확정 후 환불과 연속 음수 이월
- 재실행 시 Item/Adjustment 중복 0
- V18–V20 migration의 legacy gate, overlap/exactly-one/immutable/tie-out constraint
- missing terms, burden 또는 issuer source의 503 rollback과 idempotent snapshot replay

## Metrics

- **Measured (2026-08-03, single local run):** PostgreSQL 17.6, 1,000 Item, chunk 500,
  `idx_settlement_item_batch_cursor` index scan에서 500 rows 실행 0.119ms(shared hit 20), Batch
  calculation 36.054ms, confirmation 17.260ms였다. 의도적으로 row lock을 200ms 보유한 동일
  Batch 경쟁의 관측 대기는 203.086ms였다. 기준선·부하·SLA가 없는 재현 fixture 값이다.

## Revisit Conditions

실제 지급, PG 매입일, 세금·회계 또는 외부 파트너 비용 주체가 도입될 때

## Related Decisions

- BR-16, BR-17, BR-18, BR-19, BR-20, BR-21
- [ADR-008](ADR-008-settlement-adjustment-ledger.md)
- [ADR-011](ADR-011-point-lot-ledger.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-067](ADR-067-settlement-batch-creation-and-schema-ownership.md)
- [ADR-066](ADR-066-audited-loyalty-point-adjustment.md)
- [ADR-071](ADR-071-settlement-input-snapshot-foundation.md)
