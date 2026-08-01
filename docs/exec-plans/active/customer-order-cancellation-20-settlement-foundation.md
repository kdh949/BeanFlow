# Settlement foundation과 고객 취소 제외 증적을 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/active/customer-order-cancellation-15-settlement-input-snapshot-foundation.md`, `docs/exec-plans/active/customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md`, `docs/exec-plans/completed/signed-cursor-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

`COMPLETED` 주문만 정산 원천이 되고 매장 수락 전 고객 취소 환불은 정상적으로
정산되지 않는다는 BR-16/ADR-048을 실제 Settlement owner 모델과 멱등 consumer로
보호한다. 고객 취소 구현이 존재하지 않는 Settlement를 호출하거나 no-op으로 성공하지
않게 하는 선행 계획이다.

## Current State

- ADR-008/017/048은 SettlementItem/Batch/Adjustment와 고객 취소 `NOT_APPLICABLE`
  Audit을 Accepted로 정의한다. ADR-067은 이 계획이 최소 `OPEN` Batch와 Item 귀속만
  소유하고 lifecycle/Adjustment는 후속 계획이 소유한다고 고정한다.
- ADR-071은 Merchant terms, Campaign burden, PointLot issuer와 net calculation을 Ordering
  `OrderSettlementInputSnapshot`으로 materialize하는 Plan 15를 이 plan의 direct prerequisite로 고정한다.
- OpenAPI에는 Settlement 조회 계약이 있다.
- `src/main/kotlin`과 migration에는 Settlement package/table/consumer가 없다.
- 현재 `OrderCompletedV1`, `PaymentRefunded` 계약은 문서에 있으나 Settlement 소비 구현이 없다.
  Plan 20은 V1을 확장하지 않고 ADR-068 cutover gate 뒤 `OrderCompletedV2`를 생산·소비한다.

## Definitions

- **SettlementItem:** 완료 Order 한 건의 정산 입력 원장.
- **Open Batch:** `(storeId, settlementDate)`당 하나이며 Item 귀속을 위한 최소
  `SettlementBatch` state. summary·calculation·confirmation을 뜻하지 않는다.
- **SettlementAdjustment:** 확정 후 변경을 덮어쓰지 않는 append-only 조정.
- **NOT_APPLICABLE:** 정상 미완료 고객 취소라 Settlement 원장을 만들지 않았음을 source별
  Audit으로 증명한 consumer 결과.

## Scope

### In Scope

- Settlement Context/module과 최소 Aggregate/Repository 경계
- existing Ordering completion producer inventory, incomplete `OrderCompletedV1` publication/deployed V1
  consumer gate, `OrderCompletedV1 -> OrderCompletedV2` cutover와 Ordering guarded completion transaction의
  V2 outbox 저장/activation
- immutable completion snapshot을 소비하는 SettlementItem 생성의 source unique
- Item 생성 transaction의 최소 `OPEN` SettlementBatch creation/unique 경쟁 처리와
  `settlementBatchId` FK 귀속
- 고객 취소 Refund fact의 엄격한 `NOT_APPLICABLE` 판정과 target Audit
- 중복 event, 재시작, source 불일치와 missing dependency 실패
- 운영 조회와 runbook에 필요한 최소 상태
- ADR-062의 store+batch 범위 SettlementItem cursor 조회와 `itemId` discovery
- signed-cursor foundation이 제공한 ADR-070 common codec/configuration을 **소비하는** Batch Item query

### Non-goals

- 실제 매장 계좌 지급, 세금·회계 전표와 채권 관리
- 수수료율이나 비용 부담 정책 재선택
- 고객 취소 command
- Batch calculation/confirmation summary, SettlementAdjustment와 Dispute workflow
- 정상 조건을 0원 Adjustment나 no-op으로 기록
- Plan 15의 snapshot materialization, payload input 재계산 또는 Merchant/Campaign/PointLot 최신 state 조회

## Business Rules and Invariants

- SettlementItem은 `COMPLETED` Order source당 최대 하나다.
- SettlementItem은 반드시 같은 store/date의 `OPEN` Batch 하나를 참조한다. `CALCULATED` 또는
  `CONFIRMED` Batch에 늦게 도착한 Item source는 Batch를 바꾸지 않고 explicit reprocessing path다.
- 미완료 고객 취소에는 SettlementItem/Adjustment를 만들지 않는다.
- `CUSTOMER_REQUEST` cause, 고객 취소 Refund `SUCCEEDED`, source 일치와 Item 부재를
  모두 확인한 경우만 `NOT_APPLICABLE`이다.
- 판정 증거는 append-only AuditRecord source unique로 남는다.
- 조건 불일치나 필수 데이터 누락은 성공 완료가 아니다.

## Architecture and Transaction Boundaries

- Ordering guarded completion transaction은 Plan 15가 제공한 immutable snapshot/payload factory와
  matching Payment approval payable tie-out을 검증한 뒤 `OrderCompletedV2` outbox를 Order `COMPLETED`
  transition과 atomically 저장한다. V1 publication drain/deployed consumer inventory가 0 또는
  unverified이면 producer를 교체하거나 V2를 activate하지 않는다.
- `OrderCompletedV2` Settlement consumer transaction은 immutable event payload를 검증하고 `(storeId,
  settlementDate)`의 `OPEN` Batch를 insert-or-read한 뒤 SettlementItem, Audit과
  `SettlementItemCreatedV1` publication을 함께 commit한다. consumer는 Merchant, Campaign, PointLot,
  OrderSettlementInputSnapshot 또는 Payment의 current state를 재조회해 값을 채우지 않는다.
- Ordering producer transaction과 Settlement consumer transaction은 같은 event를 다루더라도 별도의
  local transaction이다. external Provider 또는 consumer 호출을 Ordering completion transaction 안에 넣지
  않는다.
- 고객 취소 Refund consumer transaction은 Order/Refund source를 읽고 Item 부재를
  확인한 뒤 NOT_APPLICABLE Audit과 publication completion을 연결한다.
- 다른 Aggregate는 ID와 공개 Query/Application API로 참조하고 JPA 관계를 추가하지 않는다.
- Batch Item 목록은 별도 Query Repository/DTO projection으로 읽고 Batch 쓰기 Aggregate에
  Items 객체 연관관계를 추가하지 않는다.

## Alternatives Considered

- 고객 취소 consumer만 no-op 구현: 정상 제외 증거와 향후 정산 불변식이 없어 제외한다.
- 0원 SettlementAdjustment 생성: 정산된 적 없는 거래를 정산 원장에 넣어 제외한다.
- Settlement를 고객 취소 범위에서 제거: Accepted ADR-048/060 변경이므로 별도 사용자
  결정 없이 선택하지 않는다.

## Failure Semantics

- Order/Refund/source 조회 실패는 publication retry 또는 명시적 ReprocessingCase다.
- Ordering completion transaction의 V2 outbox save failure는 completion publication success가 아니며
  Order completion/V2 producer activation을 성공으로 가장하지 않는다. 해당 local transaction은 rollback하고
  request-critical failure로 노출하며, 별도 reconciliation이 필요한 외부 결과가 없는 한 새 success state를
  추정해 만들지 않는다.
- Audit 저장 실패는 consumer 성공이 아니다.
- source mismatch나 예상치 못한 기존 Item은 `MANUAL_REVIEW` 대상이며 덮어쓰지 않는다.

## Data and Migration

Plan 15 snapshot, Plan 16 refund event producer와 signed-cursor foundation actual outcomes가 completed path에 기록되고
`OrderCompletedV1` incomplete publication/deployed consumer inventory가 zero로 검증된 뒤, ADR-072 migration-writer
lease를 얻은 latest main에서만 시작한다. ADR-067 matrix에 따라 이 계획은 `settlement_batch`의 최소 identity/scope/open-state fields,
store/date unique·state CHECK와 `settlement_item` 전체를 단독 migration한다. Item에는
`settlement_batch_id NOT NULL` FK, immutable financial snapshot/source unique와
`(settlement_batch_id, completed_at, id)` cursor index를 둔다. Adjustment, Batch summary/
confirmation fields와 Dispute table은 만들지 않는다. 기존 환경 적용 전략은 00 plan 결과를
따른다.

## API and Event Contracts

- `OrderCompletedV2`는 ADR-068의 exact immutable SettlementItem 생성 원천이다. ADR-071의
  `OrderSettlementInputSnapshot`/matching Payment approval tie-out 없이는 Ordering producer를 활성화하지 않는다.
  Plan 20은 guarded completion transaction의 V2 outbox save, V1 producer replacement와 activation을 소유하며
  incomplete V1 publication/deployed consumer 0 inventory가 통과한 뒤에만 cutover한다.
- `PaymentApproved`만으로 Item을 만들지 않는다.
- 일반 `PaymentRefunded`에서 customer-cancellation source를 식별해 ADR-048 분기로
  처리한다. 고객 알림 event를 정산 근거로 사용하지 않는다.
- `GET /stores/{storeId}/settlements/{settlementBatchId}/items`는
  `(completedAt ASC, settlementItemId ASC)` cursor page로 이의제기용 Item ID와 금액
  snapshot을 제공한다. cursor는 ADR-070의 signed scope/filter binding과 `limit=20` default,
  `100` maximum을 쓴다.

## Milestones

1. Plan 15 snapshot/payload-factory tie-out, Plan 16 refund event producer, signed cursor foundation,
   `OrderCompletedV1 -> V2` cutover inventory와 ADR-068 event contract gate를 닫는다.
2. Settlement module, minimum OPEN Batch/Item schema와 Aggregate/DB 불변식을 만든다.
3. Ordering guarded completion V2 outbox producer/cutover와 별도 Settlement consumer, Batch unique 경쟁과
   Item source unique를 구현한다.
4. signed Batch Item query projection을 OpenAPI와 일치시킨다.
5. 고객 취소 Refund NOT_APPLICABLE consumer와 target Audit을 구현한다.
6. 중복·재시작·closed-Batch late Item·불일치 failure recovery를 검증한다.

## Required Tests

- Completed 주문 Item 단일 생성과 non-completed 거부
- same store/date concurrent completion의 Batch 하나·Item source당 하나와 insert/Audit/outbox rollback
- V1 cutover inventory nonzero/unknown blocking, V2 snapshot missing/source conflict blocking
- completion transition/V2 outbox atomicity, V1 non-dual-publication gate와 producer/consumer local transaction
  분리
- Merchant terms/Campaign burden/PointLot issuer 변경 뒤 ADR-071 snapshot과 V2/Item amount가 unchanged인 tie-out
- customer cancellation Refund의 Item/Adjustment 0건과 Audit 1건
- cause/refund/source mismatch의 비완료 처리
- 기존 Item 존재 시 비덮어쓰기
- duplicate publication과 restart
- Batch별 Item empty/single/multi-page와 동률 cursor 경계, signed cursor scope/signature/expiry와
  `limit` default/max
- store membership, Batch-store mismatch와 다른 Batch cursor·filter reuse 거부
- closed Batch late Item은 Batch mutation/0원 Adjustment 없이 ReprocessingCase로 남음
- 조회한 `settlementItemId`와 dispute path contract 연결
- PostgreSQL constraint, Modulith 경계와 API contract

## Validation Commands

```bash
./gradlew test --tests '*Settlement*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

item creation, NOT_APPLICABLE, mismatch, retry와 manual review를 닫힌 outcome으로
측정한다. store/order/refund ID는 metric tag에 넣지 않는다.

## Documentation Updates

ADR-008/017/048/062/067/068/070/071/072 구현 evidence, context map, aggregate invariants, event catalog,
transaction boundaries, Settlement runbook과 quality evidence를 갱신한다.

## Progress

- [ ] V2 cutover/event contract gate
- [ ] Settlement minimum Batch/Item schema
- [ ] OrderCompletedV2 Batch/Item consumer
- [ ] query contract
- [ ] customer cancellation exclusion consumer
- [ ] recovery/observability
- [ ] 전체 검증

## Surprises & Discoveries

- Accepted 정산 정책과 OpenAPI가 있지만 구현 package와 table은 하나도 없다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Accepted existing | 미수락 고객 취소는 정산 원장 없이 NOT_APPLICABLE Audit | 미완료 거래의 수익·조정 위장 방지 | BR-16, ADR-048 |
| 2026-08-01 | Accepted | 점주는 Batch별 cursor Item 목록에서 dispute용 itemId를 얻음 | 대량 Batch 응답을 피하면서 조회→이의제기 흐름 완결 | ADR-062 |
| 2026-08-01 | Accepted | Plan 20이 최소 OPEN Batch와 Item schema를 단독 소유 | Batch-scoped API를 선행 구현하고 lifecycle migration 중복 방지 | ADR-067 |
| 2026-08-01 | Accepted | Item input은 `OrderCompletedV2` immutable snapshot이며 V1 cutover gate를 거침 | live policy/current Aggregate 재조회와 V1 required-field drift 방지 | ADR-068 |
| 2026-08-01 | Accepted | Plan 15 immutable settlement-input outcome 뒤에만 Plan 20을 시작 | fee/burden/issuer source가 없는 event payload 추측 방지 | ADR-071 |
| 2026-08-01 | Accepted | Plan 20이 Ordering guarded completion V2 outbox/cutover와 Settlement consumer를 함께 소유 | Plan 15의 snapshot/factory handoff와 actual producer activation을 분리하고 producer/consumer transaction을 독립시킴 | ADR-068, ADR-071 |

## Outcomes & Retrospective

미구현 상태다. Plan 15 snapshot/payload-factory, Plan 16 refund producer와 signed-cursor foundation evidence,
V2 cutover inventory가 모두 통과하기 전에는 Ordering V2 producer, Settlement schema/consumer 또는 public Item
endpoint를 시작하지 않는다.

## Revision Notes

- 2026-07-31: readiness audit에서 최초 작성.
- 2026-08-01: Batch별 SettlementItem 조회와 이의제기 식별 경로를 추가.
- 2026-08-01: 최소 OPEN Batch, immutable event V2와 signed cursor contract를 ADR-067/068/070에
  맞춰 분리했다.
- 2026-08-01: Plan 15에서 V2 outbox ownership을 제거하고, Plan 20의 Ordering producer cutover와
  separate Settlement consumer transaction으로 명확화했다.
