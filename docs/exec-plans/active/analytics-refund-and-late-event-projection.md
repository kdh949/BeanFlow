# 환불 지표와 늦은 이벤트 재집계를 명시적 Analytics Read Model로 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/active/customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md`, `docs/exec-plans/active/customer-order-cancellation-20-settlement-foundation.md`, `docs/exec-plans/active/settlement-batch-adjustment-and-dispute.md`, `docs/exec-plans/active/loyalty-point-adjustment-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

Analytics는 거래 Aggregate를 소유하지 않는 read-only projection Context다.
`refundAmountByRefundDate`는 실제 성공 환불일의 운영 흐름을, `adjustedRevenueByOrderCompletionDate`는
원 주문 완료일의 수익성 보정을 나타낸다. 7일 이내 late/replayed event는 멱등 delta와 야간
rebuild로 수렴시키고, 7일을 넘은 event는 자동으로 과거 지표를 바꾸지 않고 승인 가능한
`BACKFILL_REQUIRED` `ReprocessingCase`로 남긴다.

완료 후 projection lag/freshness와 failure state는 지표와 함께 관찰된다. consumer 장애,
source conflict, 오래된 event가 0, 빈 결과 또는 stale value를 정상 데이터처럼 보이게 하지 않는다.

## Current State

- BR-31~32와 ADR-023은 두 지표 귀속일, 7일 automatic correction window, late event/backfill을 확정했다.
- ADR-068은 `OrderCompletedV2`, `PaymentRefundedV1`, `PointsAccruedV1`, `PointsRestoredV1`,
  `PointsAdjustedV1`, `SettlementItemCreatedV1`, `SettlementAdjustmentCreatedV1`의 exact immutable
  payload, logical source와 producer checkpoint를 고정했다. module, migration, listener,
  freshness projection, ReprocessingCase integration은 아직 없다.
- `PointsAdjustedV1`의 listener, receipt/idempotency and metric projection은 이 plan의 단독
  consumer checkpoint다. point-adjustment plan은 producer/outbox contract만 소유한다.
- Plan 16은 Refund allocation/recovery 결과의 Payment·Loyalty event producer를, Plan 20은 completion/Settlement Item
  event 기반을, point-adjustment 및 Settlement lifecycle 계획은 나머지 producer를 만든다. 이 계획은
  활성화할 producer row의 actual event/version contract가 모두 완료된 뒤 시작한다.
- OpenAPI에는 public Analytics query endpoint가 없다. audience/인가/freshness contract를 추정하지 않고
  owner projection, Operations query와 metric/runbook을 먼저 완성한다.

## Definitions

- **Source event:** versioned envelope와 immutable business payload를 가진 persistent fact다.
- **Refund-date metric:** `Refund.state=SUCCEEDED`가 확정된 날짜에 더하는 현금 환불액이다.
- **Completion-date adjusted revenue:** 원 `Order.completedAt` 날짜의 수익에서 success refund/
  settlement adjustment effect를 반영한 별도 지표다.
- **Event date:** envelope `occurredAt`의 `Asia/Seoul` 날짜이며 correction window 기준이다.
- **Late window:** event date 기준 `now - 7 calendar days` 이내의 automatic correction/rebuild 구간이다.
- **Backfill:** approved case가 date/key keyset chunk로 재계산하는 작업이며 original event를 수정하지 않는다.
- **Freshness:** 마지막 success projection/rebuild time과 pending/failed state다. metric value 자체가 아니다.

## Scope

### In Scope

- Analytics Modulith module, immutable source receipt/deduplication, metric-day and freshness schema
- completion/refund date split, partial/full refund delta, Settlement adjustment and point event projection
- 7-day late correction, nightly rebuild, >7-day `BACKFILL_REQUIRED`, approved chunk backfill/restart
- producer event contract validation, source/version conflict, Operations projection, runbook/closed metrics
- `PointsAdjustedV1` listener, receipt/idempotency, delta/freshness projection의 단독 ownership
- Testcontainers, Modulith, duplicate/replay/late/backfill/failure tests and actual measurement

### Non-goals

- Order/Payment/Refund/Loyalty/Settlement write ownership or financial correction
- public dashboard/API, export, warehouse, Kafka, cache or analytics provider
- 7일 초과 automatic correction, unbounded table rebuild, original event mutation
- projection을 결제/정산/환불 API의 source of truth로 쓰는 동작

## Business Rules and Invariants

- refund-date amount와 completion-date adjusted revenue는 같은 column/view/label로 합치지 않는다.
- `SUCCEEDED` Refund만 cash delta를 만든다. `REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`,
  `FAILED`, `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`는 success delta나 0 success가 아니다.
- business source, payload version, metric date/key는 unique하다. duplicate/replay는 exactly one delta,
  same source의 amount/version/date conflict는 existing metric overwrite가 아닌 projection failure다.
- event date 7일 이내는 correction을 한 번 적용하고 nightly rebuild candidate로 둔다. 7일 초과는
  automatic mutation 없이 source/date unique `BACKFILL_REQUIRED` case를 만든다.
- approved backfill만 bounded keyset chunk로 rebuild한다. restart는 같은 result/receipt checkpoint로
  수렴하며 failed/unknown freshness를 0/stale healthy result로 위장하지 않는다.
- monetary value는 integer KRW, date grouping은 `Asia/Seoul`, time test는 injected fixed `Clock`이다.

## Architecture and Transaction Boundaries

- producer transaction은 original financial fact와 Spring Modulith persistent publication을 함께 commit한다.
  Analytics listener는 producer repository의 latest state를 추측하지 않고 ADR-068 immutable payload를 소비한다.
- `AnalyticsProjectionService` transaction은 receipt, metric-day delta, freshness marker, 필요한
  `ReprocessingCase` command를 함께 저장하거나 rollback한다. Analytics failure가 source transaction을
  rollback하지 않는다.
- listener failure는 publication pending/retry를 유지한다. retry exhaustion은 Operations case와 failed
  freshness를 남기며 event completion을 거짓으로 기록하지 않는다.
- rebuild/backfill은 claim transaction → bounded projection chunk transaction → checkpoint transaction으로
  분리한다. one chunk failure가 committed chunk를 rollback하지 않으며 restart는 receipt/checkpoint를 쓴다.
- Operations가 case approval/state를 소유한다. Analytics는 source/date range만 제출하고 승인 endpoint를 만들지 않는다.

## Alternatives Considered

- 환불을 원 주문일에만 귀속: 당일 refund operation 흐름을 잃으므로 제외한다.
- 모든 late event 자동 수정: 오래된 대규모 correction의 운영 위험 때문에 제외한다.
- projection failure를 stale/0으로 반환: BR-31/32와 failure semantics에 반한다.
- event ID 하나만 dedup: 새 event ID의 replay/source conflict를 막지 못한다.
- Kafka/warehouse 선도입: 독립 consumer/scale evidence가 없어 제외한다.

## Failure Semantics

- missing/invalid payload field, version/source conflict는 value/date를 추정하지 않는다. listener retry 후
  `MANUAL_REVIEW` case와 failed freshness로 남긴다.
- DB/query/worker failure는 `FAILED|UNKNOWN` freshness로 관측된다. metric/day를 0으로 reset하거나
  stale value에 healthy flag를 붙이지 않는다.
- >7-day case 저장 실패는 event completion이 아니다. case 없는 automatic update는 하지 않는다.
- backfill approval/claim/chunk conflict는 retry or `MANUAL_REVIEW`로 보존한다. original fact를 재발행,
  수정하거나 full-table delete로 복구하지 않는다.

## Data and Migration

forward migration은 Analytics owner 아래 다음을 만든다.

- `analytics_event_receipt`: event/business source, payload version, metric key/date, applied state unique
- `analytics_metric_day`: metric name, 서울 date, recomputable dimension, integer KRW value, freshness/version
- `analytics_projection_freshness`: metric/date range last success, failed/unknown reason, pending receipt count
- `analytics_backfill_checkpoint`: approved case, metric/date range, keyset cursor/chunk state, source unique

customer/order/payment/refund/account raw identity를 public dimension으로 저장하지 않는다. receipt의 opaque
source reference는 API/log/metric tag로 노출하지 않는다. migration은 historical fact를 추정 생성하지 않으며
existing data backfill은 approved case만 수행한다.

## API and Event Contracts

- producer plan은 ADR-068 table의 immutable source, occurrence/result time, integer amount,
  completion-date/settlement effect와 exact payload version을 제공해야 한다. 기존 V1 required field
  의미를 바꾸지 않고 `OrderCompletedV1 -> V2`는 cutover inventory가 통과한 atomic checkpoint에서만 한다.
- customer cancellation exclusion Audit/notification은 analytics cash source가 아니다. effect는 Payment result fact에만 근거한다.
- Operations owner query/API만 analytics freshness/case를 노출한다. public endpoint는 별도 policy/ADR 없이는 만들지 않는다.
- event catalog는 producer, payload, dedup source, backfill action을 exact version으로 갱신한다. 검증 없는 dual publish는 없다.

## Milestones

1. Plan 16/20, point-adjustment, Settlement lifecycle의 ADR-068 producer payload, publication durability,
   source/version uniqueness와 cutover outcome을 audit한다.
2. Analytics module, receipt/day/freshness schema, fixed-Clock date conversion, duplicate/conflict domain tests를 만든다.
3. completion/successful Refund/adjustment/point event를 두 날짜 metric으로 projection하는 vertical slice를 완성한다.
4. 7-day correction/nightly rebuild, older-event case, approval/checkpointed backfill/restart를 구현한다.
5. failure/freshness query, metric, alert/runbook, Testcontainers/contract/measurement evidence를 완성한다.

## Required Tests

- same source duplicate/replay, new event ID same source, same source different payload conflict
- past completion order same-day partial/full Refund의 refund-date vs completion-date separation/tie-out
- non-success Refund, customer-cancellation exclusion disposition, Settlement adjustment,
  PointsAccruedV1/RestoredV1/AdjustedV1 effects
- 7-day exact boundary, inside correction, 8-day one-time case, duplicate old event/case-save failure
- approved/unapproved case, 101+ keyset chunk, claim crash, stop/restart, parallel worker
- failed/unknown freshness, producer/listener DB failure, no zero/stale/cache fallback
- event version compatibility, raw identity allowlist, Testcontainers constraint, Modulith boundary, fixed Clock

## Validation Commands

- `./gradlew test --tests '*Analytics*' --tests '*Refund*' --tests '*Settlement*' --tests '*Loyalty*'`
- `./gradlew test --tests '*EventPublication*' --tests '*ModularityTests'`
- `./gradlew clean build`
- `bash scripts/verify-docs.sh`
- `git diff --check`

동일 fixture/event count/date range/chunk size에서 projection lag, rebuild duration, execution plan/lock wait를
실측한다. 측정 전에는 target 또는 Not measured로만 기록한다.

## Observability

- `beanflow.analytics.projection.count{event_type,outcome}`, projection lag, freshness age, pending receipt count
- `beanflow.analytics.late_event.count{window,outcome}`
- `beanflow.analytics.backfill.count{state,outcome}`, chunk duration

tag는 closed event type/window/state/outcome만 사용한다. event/source/customer/order/payment/refund/account ID,
amount, evidence는 metric tag에 넣지 않으며 log에는 closed failure reason과 correlation ID만 둔다.

## Documentation Updates

- BR-31~32, ADR-023/068 and event-publication failure implementation evidence
- context map, invariants, transaction boundaries, event catalog, test strategy
- Operations reprocessing/backfill runbook, measurement plan, quality evidence map
- public Analytics API가 non-goal임을 API conventions/OpenAPI와 대조하고 this ExecPlan을 갱신한다.

## Progress

- [ ] producer payload/publication input contract gate
- [ ] Analytics schema, dedup and freshness foundation
- [ ] completion/refund/adjustment/point metric slice
- [ ] late event, case approval, checkpointed backfill
- [ ] operations/runbook/measurement evidence
- [ ] full validation

## Surprises & Discoveries

- 2026-08-01: event catalog에는 Analytics consumer가 있지만 immutable delta payload, receipt/freshness owner,
  backfill checkpoint 구현은 없다. ADR-068 producer checkpoint를 소비하고 source of truth를 복제하지 않는다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted existing | refund-date와 completion-date adjusted revenue 분리 | 운영 흐름과 원 거래 수익성 혼합 방지 | BR-31, ADR-023 |
| 2026-08-01 | Accepted existing | 7일 이내 correction, 그 외 approved backfill | 오래된 대규모 변경 통제 | BR-32, ADR-023 |
| 2026-08-01 | Plan boundary | public endpoint 없이 Operations projection으로 시작 | 미정 audience/인가/freshness contract 추정 금지 | OpenAPI |
| 2026-08-01 | Accepted | Analytics는 ADR-068 immutable event payload만 소비하고 latest owner state를 재조회하지 않음 | 정책 변경 뒤 과거 metric drift 방지 | ADR-068 |
| 2026-08-01 | Accepted | `PointsAdjustedV1` consumer는 Analytics plan만 구현하고 Loyalty point-adjustment plan은 producer/outbox만 구현 | listener/receipt/projection duplicate ownership 방지 | ADR-068 |

## Outcomes & Retrospective

미구현 상태다. ADR-068에서 활성화할 Plan 16/20, point-adjustment, Settlement lifecycle producer의
actual validation이 완료된 뒤 listener를 활성화한다. 완료 시 fixture tie-out, seven-day/backfill
recovery, freshness failure, measurement 결과를 actual value로 기록한다.

## Revision Notes

- 2026-08-01: BR-31/32와 ADR-023에 대응하는 누락 ExecPlan을 최초 작성.
- 2026-08-01: ADR-068의 event version/payload/producer checkpoint를 Analytics activation gate로 고정.
