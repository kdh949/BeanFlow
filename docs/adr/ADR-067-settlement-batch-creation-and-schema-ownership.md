# ADR-067: Settlement Batch 최소 생성과 스키마 소유권

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owners:** [Plan 20](../exec-plans/completed/customer-order-cancellation-20-settlement-foundation.md), [Settlement lifecycle plan](../exec-plans/active/settlement-batch-adjustment-and-dispute.md)

## Context

ADR-017과 ADR-062는 완료 주문의 `SettlementItem`이 매장·완료일별
`SettlementBatch`에 귀속되고 Batch별 Item 조회가 가능해야 한다고 정한다. 그러나 Plan 20은
Item·Batch·Adjustment migration 전체를 소유한다고 적었고, 후속 정산 계획도 Batch·Adjustment·
Dispute migration을 소유한다고 적었다. 동시에 Plan 20의 Batch 범위 Item API와 필수
`settlementBatchId`는 후속 계획이 만든다고 적힌 Batch에 의존했다.

같은 table, column, constraint 또는 index를 서로 다른 작업 branch가 만들면 Flyway 번호,
DDL checksum과 rebase가 충돌한다. 반대로 Item을 Batch 없이 만들면 ADR-062의 store+Batch
인가 및 stable cursor contract를 구현할 수 없다.

## Decision

Plan 20은 완료 event를 받을 때 필요한 **최소 `OPEN` Batch와 immutable SettlementItem**을
단독 소유한다. 후속 정산 lifecycle 계획은 그 기반 위에서 Batch 계산·확정, summary, Adjustment와
Dispute workflow를 추가한다. 동일 schema object는 둘 이상의 계획에 배정하지 않는다.

### Plan 20 소유: Batch 생성과 Item 귀속

`OrderCompletedV2` consumer는 짧은 Settlement transaction에서 다음을 함께 처리한다.

1. immutable completion snapshot의 `storeId`와 `settlementDate`를 검증한다.
2. `(store_id, settlement_date)` unique key로 `OPEN` Batch를 insert한다.
3. unique 경쟁에 진 consumer는 기존 Batch를 다시 읽어 같은 store/date와 `OPEN` 상태를
   확인한다. 다른 scope 또는 닫힌 Batch를 성공으로 해석하지 않는다.
4. Batch ID를 필수 FK로 가진 SettlementItem, target AuditRecord와
   `SettlementItemCreatedV1` persistent publication을 함께 commit한다.

외부 호출, Batch summary 계산 또는 Item collection loading은 이 transaction에 넣지 않는다.
Batch와 Item 중 하나라도 저장하지 못하면 publication completion을 기록하지 않고 retry 또는
`MANUAL_REVIEW`로 남긴다.

Plan 20이 생성하는 Batch의 초기 상태는 `OPEN`이다. 후속 lifecycle이 `CALCULATED` 또는
`CONFIRMED`로 닫은 Batch에 지연 완료 event가 도착하면 Plan 20은 Item을 조용히 붙이거나
Batch를 되열지 않는다. `SETTLEMENT_LATE_ITEM` ReprocessingCase를 source unique로 남기고
event를 성공 처리하지 않는다. lifecycle 활성화 전에는 모든 Batch가 `OPEN`이므로 이 경로가
정상 ingest를 막지 않는다. closed-Batch 재처리 방식은 lifecycle 계획의 별도 Accepted 결정
없이 추측하지 않는다.

### Migration ownership matrix

| Schema object | 단독 소유 계획 | 책임 |
|---|---|---|
| `settlement_batch.id`, `store_id`, `settlement_date`, `state`, `created_at`, version | Plan 20 | 최소 `OPEN` Batch 식별·생성 |
| `settlement_batch` store/date FK, `UNIQUE(store_id, settlement_date)`, state value CHECK | Plan 20 | 하나의 store/date Batch와 허용 state vocabulary 보호 |
| `settlement_item` 전체와 `settlement_batch_id NOT NULL` FK | Plan 20 | immutable completion snapshot, source unique, Batch 귀속 |
| Item source unique, `(settlement_batch_id, completed_at, id)` cursor index, exclusion Audit lookup index | Plan 20 | 중복 Item 방지와 ADR-062 Batch Item 조회 |
| `settlement_batch`의 calculation/confirmation summary columns, `calculated_at`, `confirmed_at`, carry-forward columns와 Batch-list index | Settlement lifecycle plan | Batch 계산·확정과 명세 projection |
| Batch transition code와 `OPEN -> CALCULATED -> CONFIRMED` guarded transition | Settlement lifecycle plan | 집계·확정 lifecycle |
| `settlement_adjustment` 전체, source/reason unique와 next-Batch discovery index | Settlement lifecycle plan | 확정 후 append-only 조정·이월 |
| `settlement_dispute` 전체, active Item partial unique, refile/idempotency/evidence constraints | Dispute Context checkpoint (lifecycle plan 안의 별도 commit) | Dispute Context의 workflow와 held amount |

### 고객 취소 제외 선행 스키마 소유권

ADR-048의 consumer는 Refund event payload만 신뢰하지 않고 실제 Order의 terminal cause를
읽어야 한다. 2026-08-03 ownership amendment에 따라 Plan 20은 같은 migration-writer lease에서
`ordering_order.cancelled_at`, `ordering_order.cancellation_cause`, 허용 cause CHECK와
terminal-state 존재 조건 CHECK를 단독 추가한다. clean-cutover precheck에서 기존 `CANCELLED`
row가 하나라도 발견되면 값을 추측해 backfill하지 않고 migration을 실패시킨다.

Plan 40은 `cancellation_reason_code`, `cancellation_detail`, cause별 사유/detail CHECK와 실제
고객 취소 command를 계속 소유한다. 따라서 Plan 20은 `CUSTOMER_REQUEST` command를
활성화하지 않으며, 기존 결제 명시 거절 transition만 `PAYMENT_DECLINED` 증거를 함께 기록한다.
두 계획은 같은 column이나 CHECK를 다시 만들지 않는다.

`state` column은 Plan 20이 final vocabulary CHECK를 만들지만 Plan 20은 `OPEN` 이외 전이를
실행하지 않는다. 후속 계획은 그 column을 재생성하거나 CHECK를 다시 만들지 않고 guarded
transition만 구현한다. summary/confirmation fields는 후속 계획이 단독으로 추가한다.

### Execution dependency

Plan 20은 Plan 15 settlement-input snapshot foundation과 signed-cursor foundation의 actual
outcome 뒤에만 시작한다. Plan 15가 Plan 10 issuer/precheck outcome을 직접 소비하므로 Plan 20은
그 ancestor를 다시 branch base로 나열하지 않는다. Batch lifecycle 계획의 direct phase input은
Plan 20의 actual migration/outcome 하나다. 무인 schema execution은 ADR-072 migration-writer lane을
따르므로 같은 Flyway 번호를 병렬 branch에서 선택하지 않는다.

## Alternatives Considered

### Plan 20이 SettlementItem만 소유

Batch API가 필요한 `settlementBatchId`, Batch-store authorization과 Batch-scoped cursor를 만들
수 없다.

### Plan 20이 Batch·Adjustment까지 전부 소유

Plan 20의 고객 취소 제외·Item foundation과 일별 계산·이월·Dispute lifecycle의 배포 및 실패
경로가 한 branch에 섞인다. 후속 계획의 책임도 공허해진다.

### 영속 Batch 없이 논리 ID 사용

store/date uniqueness, object-level authorization, cursor scope와 이후 immutable confirmation을
DB 제약으로 지킬 수 없다.

## Rationale

최소 Batch는 Item의 자연스러운 조회·인가 경계이므로 Item 생성과 같은 consumer transaction에
속해야 한다. 하지만 Batch 계산과 확정은 지연 event, carry-forward, summary와 운영 recovery를
동반하는 별도 lifecycle이다. table을 쪼개지 않고 field·constraint 수준으로 소유권을 나누면
API 선행조건을 충족하면서 migration 중복을 막을 수 있다.

## Consequences

- Plan 20은 이전보다 `settlement_batch`의 최소 schema와 unique 경쟁 처리를 추가로 구현한다.
- `GET /stores/{storeId}/settlements/{settlementBatchId}/items`는 Plan 20 완료 뒤 실제 Batch
  scope에서 동작할 수 있다.
- Batch summary 목록과 calculation/confirmation은 lifecycle 계획 완료 전 활성화하지 않는다.
- closed Batch의 지연 Item은 0원 Adjustment, 다음 날짜 이동 또는 현재 Batch 변경으로
  대체하지 않고 명시적 재처리 상태로 남는다.
- Plan 20 완료 뒤 Settlement는 fixture나 event 추측 없이 Order의 cancellation cause를
  조회할 수 있고, Plan 40은 남은 고객 입력 필드와 command에 집중한다.

## Verification

- 같은 store/date의 동시 완료 event가 Batch 하나와 각 source당 Item 하나만 만든다.
- 다른 store/date는 독립 Batch를 만든다.
- Batch insert, Item insert, Audit 또는 outbox 저장 실패가 partial success를 남기지 않는다.
- Batch-scoped Item cursor, store/Batch mismatch 404와 cross-Batch cursor 400을 검증한다.
- `CALCULATED`/`CONFIRMED` Batch에 새 Item source가 도착하면 Batch를 바꾸지 않고 source당
  하나의 late-item reprocessing path를 남긴다.
- migration inventory에서 matrix의 object가 한 계획의 Flyway migration에만 존재한다.

## Implementation Evidence

- 2026-08-03 V21은 최소 Batch identity/state/store-date unique, immutable Item 전체, Batch FK,
  cursor index와 open-batch/mutation trigger를 Plan 20 단일 migration으로 만들었다.
- 동시 same-store/date completion은 Batch 하나에 수렴하고 source/order 중복은 새 Item을 만들지
  않는다. 닫힌 Batch late Item은 Batch를 변경하지 않고 source-unique
  `SETTLEMENT_LATE_ITEM` case를 남기며 event를 완료하지 않는다.

## Metrics

- `beanflow.settlement.batch.open.create.count{outcome}`
- `beanflow.settlement.item.create.count{outcome}`
- `beanflow.settlement.item.late_closed_batch.count{outcome}`

store, order, Batch, Item과 source ID는 metric tag에 넣지 않는다.

## Revisit Conditions

완료 event의 정상 지연 분포가 측정되어 closed-Batch late Item을 자동으로 안전하게 재처리할
수 있는 watermark 또는 settlement cutoff 정책이 Accepted 될 때, 혹은 실제 계좌 지급이 Batch
state와 결합될 때 재검토한다.

## Related Decisions

- BR-01, BR-16, BR-17, BR-18, BR-21
- [ADR-008](ADR-008-settlement-adjustment-ledger.md)
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-018](ADR-018-settlement-dispute-hold-and-refile.md)
- [ADR-062](ADR-062-settlement-batch-item-discovery.md)
- [ADR-071](ADR-071-settlement-input-snapshot-foundation.md)
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)

## Revision Notes

- 2026-08-03: ADR-048 consumer의 실제 Order 증거를 위해 최소 취소 증거 두 필드와 CHECK의
  소유권을 Plan 20으로 추가하고 Plan 40의 잔여 소유권을 명시했다.
