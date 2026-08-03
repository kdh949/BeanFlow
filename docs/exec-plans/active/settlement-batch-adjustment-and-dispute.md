# 일별 정산 Batch, 사후 조정과 이의제기를 수렴시킨다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-20-settlement-foundation.md`, `docs/exec-plans/completed/signed-cursor-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

완료 주문의 불변 `SettlementItem`을 매장·완료일 기준 일별 `SettlementBatch`로 계산·확정하고,
확정 뒤 환불 또는 이의제기 금액 변화는 과거 Batch를 수정하지 않는 `SettlementAdjustment`로
다음 Batch에 이월한다. 점주는 확정 Item만 `Asia/Seoul`의 정해진 기간에 이의제기하며,
진행 중 분쟁은 해당 Item의 예상 조정액만 `HELD`로 표시한다. 실제 계좌 지급은 범위가 아니다.

완료 조건은 동일 store/date 재실행·중단·동시 실행이 하나의 `OPEN → CALCULATED → CONFIRMED`
원장을 만들고, 사후 변경은 source당 Adjustment 하나로 수렴하는 것이다. 실패는 0원 정산,
빈 목록 또는 확정 성공으로 바뀌지 않고 publication retry, `ReprocessingCase`, 503 또는
명시적 작업 상태로 남는다.

## Current State

- BR-16~24 및 ADR-008, ADR-017, ADR-018은 기준일, 비용 snapshot, 불변 원장, 이월과 분쟁을 확정했다.
- Plan 20은 최소 `OPEN` Batch, SettlementItem source unique, Batch Item cursor, 미수락 고객
  취소 `NOT_APPLICABLE` Audit을 소유하는 direct phase predecessor다. signed-cursor foundation은
  Batch list와 Item cursor를 이 계획이 직접 소비하는 독립 input이다. 그 merged baseline에는 Plan 10의
  Refund allocation, Coupon attribution, PointLot issuer snapshot과 `RECOVERY` ledger가 포함된다.
- Settlement package와 V21 최소 Batch/Item migration, V2 consumer, signed Item query와 고객 취소
  제외 Audit은 completed Plan 20에 존재한다. Dispute package, Batch lifecycle summary와 Adjustment
  migration은 아직 없으며 이 계획이 ADR-072 lease 뒤 forward migration으로 확장한다.
- OpenAPI에는 Batch 목록, Batch Item 조회, `POST /settlement-items/{itemId}/disputes`가 있다.
  Batch 계산·확정과 Dispute 판정은 내부 Application Service/worker이며 공개 운영 endpoint를 만들지 않는다.

## Definitions

- **Settlement date:** `Order.completedAt`을 `Asia/Seoul`로 변환한 날짜이며 결제 승인일이 아니다.
- **SettlementBatch:** 한 매장·하루의 내부 정산 명세 Aggregate이며 실제 계좌 지급은 나타내지 않는다.
- **SettlementAdjustment:** 확정 결과를 덮어쓰지 않고 이후 Batch에 반영하는 append-only 금액 원장이다.
- **Carry-forward:** 조정 후 순정산이 음수일 때 다음 Batch에서 한 번만 상계할 잔액이다.
- **Held amount:** 활성 Dispute의 예상 조정액이다. MVP에서 실제 지급 보류나 Batch 상태 변경이 아니다.
- **Refile:** 새 evidence reference와 이전 Dispute ID로 한 번만 만드는 새 Dispute Aggregate다.

## Scope

### In Scope

- Plan 20 위의 Batch calculation/confirmation Application Service와 bounded date worker
- immutable Item snapshot 합산, 서울 날짜 경계, Adjustment source unique, success refund/dispute,
  negative carry-forward
- Dispute-owned `SettlementDispute`, idempotency, held amount, one-refile guard와 Settlement
  Adjustment command handoff
- 기존 OpenAPI Batch/Item/Dispute contract, membership authorization, persistent publication
- runbook, closed-outcome metric, Testcontainers/contract/동시성/장애 검증

### Non-goals

- 실제 은행 송금, 세금·회계 전표, 외부 지급 hold, 현재 Merchant/Campaign 계약으로 과거 재계산
- 미수락 고객 취소의 0원 Adjustment 또는 별도 제외 원장
- Plan 12/13 allocation·point recovery, Plan 20 Item 생성·제외 Audit schema의 재구현
- Dispute 판정 UI와 증빙 파일 저장소. API에는 evidence reference만 보존한다.

## Business Rules and Invariants

- `(storeId, settlementDate)` Batch는 하나이며 `OPEN`, `CALCULATED`, `CONFIRMED`만 전이한다.
- Batch는 해당 서울 날짜의 `COMPLETED` Item만 포함한다. `PAID`, `REJECTED`, `CANCELLED`,
  `EXPIRED` Order는 포함하지 않는다.
- fee/coupon/point cost는 Item의 거래 당시 immutable snapshot으로만 계산한다.
- `CONFIRMED` Batch와 Item은 UPDATE/DELETE하지 않는다. success Refund 또는 accepted Dispute는
  source/reason unique Adjustment 하나만 만든다.
- `SUCCEEDED` 외 Refund는 금액 0 Adjustment로 기록하지 않는다. 동일 negative carry-forward를
  중복 적용하거나 확정 Batch에 소급 반영하지 않는다.
- Dispute는 `CONFIRMED` Item에만 접수한다. 활성(`FILED`, `UNDER_REVIEW`) Dispute는 Item당 하나,
  종결 뒤 refile은 새 evidence와 immediate previous ID를 요구하며 한 번만 허용한다.
- `ACCEPTED` Dispute는 Adjustment가 commit되기 전에는 terminal success가 아니다.

## Architecture and Transaction Boundaries

- Item input은 Plan 20의 `OrderCompletedV2` consumer가 소유한다. 이 계획은 Ordering, Payment,
  Promotion, Loyalty repository를 직접 호출하지 않고 Settlement Item projection만 읽는다.
- calculation은 Batch row lock 뒤 Item을 DTO projection/keyset chunk로 읽고 summary와 carry-forward
  reference를 한 local transaction에서 저장한다. Item JPA collection은 추가하지 않는다.
- confirmation은 별도 transaction에서 `CALCULATED → CONFIRMED`, `confirmedAt`, persistent event와
  AuditRecord를 함께 commit한다. 실패는 `OPEN`/recoverable `CALCULATED`로 남긴다.
- Payment Refund fact consumer는 confirmed Item/Batch를 조회해 Adjustment, source Audit, publication을
  한 Settlement transaction에 저장한다. unconfirmed Item에는 Adjustment를 만들지 않는다.
- Dispute Context transaction은 idempotency row, Dispute, held amount와 filed publication을 함께
  commit한다. 결정 worker는 Dispute를 잠그고 Settlement 공개 Application API로 Adjustment를
  요청한다. Adjustment commit 전 Dispute를 terminal success로 표시하지 않는다.
- workers는 claim/result의 짧은 transaction으로 분리한다. 외부 evidence/file provider와 계좌 지급은
  이 계획 범위 밖이며 DB transaction에 넣지 않는다.

## Alternatives Considered

- 확정 Batch 직접 수정: 감사 재현성을 잃으므로 제외한다.
- 고객 취소 Refund의 0원 Adjustment: 정산된 적 없는 거래를 원장에 넣으므로 제외한다.
- Batch 전체 hold: BR-23의 item-level hold와 충돌한다.
- 현재 계약·Campaign 재조회: 과거 결과가 바뀌므로 제외한다.
- 모든 Item entity graph 로드: 대량 Batch lock/memory 위험 때문에 projection/keyset 처리로 대체한다.

## Failure Semantics

- snapshot/source/ownership 누락·불일치는 금액 추정이나 플랫폼 부담 fallback으로 바꾸지 않는다.
  publication retry 후 `MANUAL_REVIEW`/`ReprocessingCase`로 남긴다.
- calculation/confirmation DB 실패는 Batch가 `CONFIRMED`로 보이지 않게 하고 stale summary를
  성공으로 반환하지 않는다.
- duplicate logical source는 기존 result를 반환하되 amount/reason/target conflict는 기존
  Adjustment/Dispute를 덮어쓰지 않고 explicit failure로 남긴다.
- Dispute deadline, active duplicate, second refile은 stable 409이다. DB/provider 장애를
  business conflict로 바꾸지 않는다.

## Data and Migration

Plan 20과 signed-cursor actual outcome 뒤 ADR-072 migration-writer lease를 얻은 latest main에서 forward migration으로 다음을 DB 제약으로 만든다. ADR-067의
Plan 20-owned Batch identity/scope fields, state CHECK, Item table/FK/source unique/cursor index를
다시 만들거나 변경하지 않는다.

- `settlement_batch`: Plan 20 Batch에 KRW summary, carry-forward source, `calculated_at`,
  `confirmed_at`, Batch-list keyset index를 추가한다. `OPEN -> CALCULATED -> CONFIRMED`는
  guarded transition code로만 수행한다.
- `settlement_adjustment`: immutable source/reason, confirmed Item/Batch reference, signed amount,
  source/reason unique와 다음 Batch discovery index
- Dispute-owned `settlement_dispute`: Item/state/expected·held amount/previous ID/evidence/
  idempotency response, active Item partial unique와 one-refile guard
- Batch/Adjustment/Dispute publication 및 Audit query keyset/retry index

기존 행은 migration 전 read-only inventory로 검증한다. 누락 snapshot/source를 추정 backfill하지
않고 verified input만 backfill하며, 그렇지 않으면 deployment를 중단해 운영 case로 남긴다.
금액은 `BIGINT` integer KRW, 시각은 `Instant`, settlement date는 서울 날짜로 저장한다.

## API and Event Contracts

- `GET /stores/{storeId}/settlements`는 `(settlementDate DESC, settlementBatchId DESC)` ADR-070
  signed cursor와 `limit=20` default/`100` maximum으로 Batch summary를 반환하며 membership으로
  store ownership을 확인한다.
- Plan 20 Item endpoint의 `(completedAt ASC, settlementItemId ASC)` signed cursor와 item ID를 유지한다.
- `POST /settlement-items/{itemId}/disputes`는 `Idempotency-Key`, signed expected amount, reason,
  evidence references, optional previous ID를 기존 contract 그대로 쓴다. 201은 `FILED` 저장 성공만 뜻한다.
- `SettlementAdjustmentCreatedV1`은 ADR-068의 envelope, immutable source, date/state/amount만
  둔다. raw evidence, actor, Idempotency-Key, customer/order/payment ID와 DB error는 payload/
  metric에 넣지 않는다.
- V1 event/API 호환을 깨는 변경은 새 version 또는 ADR 없이는 하지 않는다.

## Milestones

1. Plan 20/signed-cursor Outcome, migration state, Item snapshot과 `NOT_APPLICABLE` evidence를 검증한다.
2. Settlement Batch/Adjustment schema와 별도 Dispute Context schema checkpoint를 분리 commit으로
   만들고, constraint·immutable transition·carry-forward projection을 검증한다.
3. daily calculation·confirmation worker와 Batch list API를 완성한다.
4. confirmed Item refund adjustment, unconfirmed path, carry-forward, Audit/publication/retry를 구현한다.
5. membership-protected filing, deadline, held, refile, decision-to-Adjustment handoff를 구현한다.
6. reprocessing, metric, runbook, full contract/architecture validation과 actual measurement를 기록한다.

## Required Tests

- same store/date concurrent/restart calculation, multi-store parallel, Seoul midnight boundary
- duplicate complete event, immutable snapshot change attempt, calculated/confirmed guard와
  closed-Batch late Item reprocessing handoff
- fee/coupon/point cost/rounding tie-out, adjustment, carry-forward, source conflict
- confirmed/non-confirmed Item Refund, non-success Refund Adjustment 0건, Plan 20 exclusion regression
- D+1 00:00 allow/D+15 00:00 reject, active duplicate, missing evidence/second refile, handoff retry
- Batch/Item signed cursor scope/order/signature/expiry, membership and other-store access, OpenAPI contract
- Dispute Context가 held amount와 `SettlementDisputeFiled/Decided` producer를 소유하고 Settlement는
  public Adjustment command만 받는 Modulith dependency direction
- Testcontainers CHECK/unique/index, Modulith boundary, publication/Audit failure, fixed Clock and 503 no-fallback

## Validation Commands

- `./gradlew test --tests '*Settlement*' --tests '*Dispute*'`
- `./gradlew test --tests '*Refund*' --tests '*ModularityTests'`
- `./gradlew clean build`
- `bash scripts/verify-docs.sh`
- `git diff --check`

Testcontainers image, fixture size, chunk size, `EXPLAIN (ANALYZE, BUFFERS)`, duration과 lock wait는
Outcomes에 실제 값으로 기록한다. 기준선 없는 성능 수치는 주장하지 않는다.

## Observability

- `beanflow.settlement.batch.count{state,outcome}`, item count, calculation lag/chunk count
- `beanflow.settlement.adjustment.count{reason,outcome}`와 carry-forward age
- `beanflow.settlement.dispute.count{state,outcome}`, held amount
- `beanflow.settlement.reprocessing.count{reason,outcome}`

metric tag/log에는 store/order/customer/payment/item/dispute ID, actor, key, evidence와 raw amount
breakdown을 넣지 않는다. closed reason/state와 correlation ID만 관측한다.

## Documentation Updates

- BR-16~24, ADR-008/017/018/067/068/070 implementation evidence
- context map, invariants, transaction boundaries, state machines, event catalog
- OpenAPI, error catalog, settlement/dispute runbook, test strategy, quality evidence, measurement plan
- 이 ExecPlan의 Progress, Decision Log, Outcomes

## Progress

- [x] Plan 16/20 precondition evidence
- [x] Batch/Adjustment/Dispute schema와 domain invariant — V28, PostgreSQL 17.6 migration 3 tests와 domain 8 tests
- [x] calculation/confirmation과 Batch query — 500건 keyset 계산, 서울 날짜, 확정 Audit/publication, owner signed cursor
- [x] refund adjustment/carry-forward — confirmed Refund append-only Adjustment, unconfirmed retry, source conflict Case
- [x] dispute filing/decision handoff — owner membership, half-open window, advisory lock, one refile, Adjustment 선커밋
- [ ] recovery/observability/runbook
- [ ] full validation/measurement

## Surprises & Discoveries

- 2026-08-01: Plan 20은 최소 OPEN Batch, Item 생성과 고객 취소 제외 증적만 소유한다. Batch
  계산·확정, Adjustment와 Dispute를 이 후속 계획으로 분리해 migration/consumer 소유권 중복을 막는다.
- 2026-08-03: V28 적용 뒤 기존 Settlement 회귀 40개 중 두 fixture가 summary 없이
  `CALCULATED` 상태를 직접 주입해 새 lifecycle CHECK에 실패했다. 두 fixture를 실제 계산 완료
  summary로 고친 뒤 같은 40-test suite가 통과했다. 제품 fallback이나 기존 migration 수정은 없었다.
- 2026-08-03: V28의 재이의 trigger는 evidence 배열 전체가 달라지는지만 비교해 기존 reference의
  순서 변경도 새 증빙으로 오인할 수 있었다. V30에서 이전 배열에 없던 reference가 최소 하나인지
  검사하도록 교체하고 DB·Application 양쪽에 같은 규칙을 적용했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted existing | 완료일·snapshot Batch, 불변 원장과 다음 Batch Adjustment | 과거 정산 재현 | BR-16~21, ADR-008/017 |
| 2026-08-01 | Accepted existing | item-level held, 14일 half-open window, one refile | 전체 hold·무기한 재이의 방지 | BR-22~24, ADR-018 |
| 2026-08-01 | Plan boundary | 최소 Batch/Item/제외 Audit은 Plan 20, lifecycle summary/Adjustment는 Settlement checkpoint, Dispute schema/workflow는 별도 Dispute checkpoint | 소유권·commit 경계 혼동 방지 | ADR-067, Plan 20 |
| 2026-08-01 | Accepted | direct phase dependency는 Plan 20과 signed-cursor foundation이며 Plan 10/15 artifact는 merged baseline으로 소비 | Batch list가 공통 codec을 직접 소비하되 ancestor path 중복과 branch-base 추측은 방지 | ADR-070, ADR-072 |
| 2026-08-01 | Accepted existing | Dispute Context가 SettlementDispute/held amount/decision event를 소유하고 Settlement는 Adjustment command만 제공 | Context Map·용어집·ADR-018과 일치 | ADR-018, Context Map |
| 2026-08-03 | Ready | Plan 20의 V21 Batch/Item, V2 consumer, signed query와 exclusion evidence를 verified input으로 소비 | 두 direct dependency completion과 migration writer 선행 순서를 확인 | completed Plan 20, ADR-072 |
| 2026-08-03 | Accepted implementation | Batch가 이전 confirmed Batch의 carry source와 calculation 시각 기반 Adjustment ingestion high-watermark를 summary에 고정 | 늦게 전달된 과거 effective-time Adjustment도 생성 시각 기준 다음 Batch에서 한 번 소비하고 Adjustment row는 갱신하지 않음 | ADR-008, ADR-017, V28~V29 |
| 2026-08-03 | Accepted implementation | Dispute filing을 Item과 actor/idempotency advisory lock으로 직렬화하고 refile은 새 evidence reference를 DB와 Application에서 이중 검증 | 동시 active 중복과 단순 evidence 순서 변경 우회를 안정적인 409로 수렴 | ADR-018, V30 |

## Outcomes & Retrospective

미구현 상태다. Plan 20과 signed-cursor foundation의 actual validation evidence가 모두 통과해
`Implementation-Ready=true`다. ADR-072 migration-writer lease 뒤 시작하며, 완료 시 정상·실패·중복·
restart 경로와 남은 운영 제한을 실제 검증 결과로 기록한다.

## Revision Notes

- 2026-08-01: Accepted 정산·이의제기 결정에 대응하는 누락 ExecPlan을 최초 작성.
- 2026-08-01: ADR-067의 최소 Batch ownership, ADR-068 event contract, ADR-070 signed cursor와
  Dispute Context ownership을 반영했다.
- 2026-08-03: Plan 20 completed dependency와 실제 V21/consumer/query/exclusion outcome을 반영하고
  implementation-ready로 전환했다.
