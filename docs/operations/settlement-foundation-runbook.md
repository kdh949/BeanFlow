# Settlement Foundation Runbook

## Scope

이 문서는 Plan 20이 구현한 `OrderCompletedV2` → 최소 `OPEN` Batch/SettlementItem과
매장 수락 전 고객 취소 Refund의 `NOT_APPLICABLE` Audit을 진단한다. Batch 계산·확정,
SettlementAdjustment, Dispute와 실제 계좌 지급은 후속 lifecycle 범위다. 운영자가 Batch,
Item, Audit 또는 publication을 SQL로 직접 생성·수정·삭제하지 않는다.

## Completion ingestion

완료 주문의 정상 결과는 같은 store/date의 `OPEN` Batch 한 건, Order/source당 immutable Item
한 건, `SETTLEMENT_ITEM_CREATED` Audit과 `SettlementItemCreatedV1` Analytics target이다.

```sql
SELECT i.id, i.order_id, i.item_source, i.settlement_batch_id,
       i.completed_at, i.settlement_date, i.currency,
       i.gross_paid_krw, i.fee_krw, i.benefit_cost_krw,
       i.net_settlement_krw
FROM settlement_item i
WHERE i.order_id = :order_id;
```

Item이 없으면 먼저 exact `beanflow.settlement.order-completed-v2` publication을 확인한다.
Analytics target 미완료는 Item 생성 실패가 아니므로 listener ID를 함께 본다.

```sql
SELECT id, listener_id, status, completion_attempts,
       publication_date, last_resubmission_date, completion_date
FROM event_publication
WHERE event_type = 'io.github.kdh949.beanflow.eventing.api.OrderCompletedV2'
  AND completion_date IS NULL
ORDER BY publication_date, id;
```

source 또는 payload mismatch를 새 Item이나 0원 row로 보완하지 않는다. owner snapshot과 event
publication을 read-only로 대조하고 bounded retry/`EVENT_PUBLICATION` 수동 검토 절차를 따른다.

## Closed-Batch late Item

Plan 20은 `CALCULATED`/`CONFIRMED` Batch를 다시 열거나 늦은 Item을 다른 날짜로 이동하지 않는다.
다음 case가 source당 하나 존재하는지 확인한다.

```sql
SELECT id, owner_reference, status, reason, correlation_id, updated_at
FROM operations_reprocessing_case
WHERE case_type = 'SETTLEMENT_LATE_ITEM'
  AND status = 'MANUAL_REVIEW'
ORDER BY updated_at, id;
```

자동 재귀속 방식은 아직 Accepted 되지 않았다. Batch state, Item trigger 또는 case status를 직접
수정하지 말고 lifecycle의 late-item 결정 전까지 publication을 미완료로 유지한다.

## Customer-cancellation exclusion

정상 제외는 다음 세 원천이 함께 있어야 한다.

1. Order `CANCELLED`, `cancellation_cause = CUSTOMER_REQUEST`, 완료 lifecycle 시각 부재
2. Refund `SUCCEEDED`, `reason = CUSTOMER_ORDER_CANCELLED`, source/version/금액/성공 시각 일치
3. 같은 Refund/source의 `SETTLEMENT_REFUND_EXCLUDED` Audit 한 건과 SettlementItem 0건

```sql
SELECT o.id AS order_id, o.state, o.cancelled_at, o.cancellation_cause,
       o.accepted_at, o.preparing_at, o.ready_at, o.completed_at, o.version,
       r.id AS refund_id, r.state AS refund_state, r.reason AS refund_reason,
       r.source_reference, r.requested_amount_krw, r.succeeded_amount_krw,
       r.updated_at AS refund_succeeded_at, r.version AS refund_version,
       a.id AS exclusion_audit_id
FROM ordering_order o
JOIN payment_refund r ON r.order_id = o.id
LEFT JOIN operations_audit_record a
  ON a.action = 'SETTLEMENT_REFUND_EXCLUDED'
 AND a.target_type = 'REFUND'
 AND a.target_id = r.id
 AND a.source_reference = r.source_reference
WHERE o.id = :order_id;
```

Audit이 없거나 Item이 있으면 제외 완료로 표시하지 않는다. `SETTLEMENT_SOURCE_CONFLICT`는 원천
불일치이며 no-op 성공 대상이 아니다. Audit insert 실패도 publication을 완료하지 않으므로 DB 원인
복구 뒤 같은 publication을 재시도한다.

```sql
SELECT p.id, p.status, p.completion_attempts, p.last_resubmission_date,
       p.completion_date
FROM event_publication p
WHERE p.listener_id = 'beanflow.settlement.payment-refunded-v1'
  AND p.completion_date IS NULL
ORDER BY p.publication_date, p.id;
```

매장 거절, 완료 전 일반 환불과 완료 후 환불을 고객 취소 제외로 강제 완료하지 않는다. 이 target의
후속 Adjustment 의미가 구현되기 전에는 원천을 바꾸거나 허위 Audit을 넣지 않는다.

## Metrics

- `beanflow.settlement.batch.open.create.count{outcome}`
- `beanflow.settlement.item.create.count{outcome}`
- `beanflow.settlement.item.late_closed_batch.count{outcome}`
- `beanflow.settlement.refund.disposition.count{disposition,reason}`
- `beanflow.settlement.refund.exclusion_conflict.count{reason}`

Store, Order, Batch, Item, Refund와 publication ID는 metric tag에 넣지 않는다. 개별 진단은
Audit/publication의 correlation ID와 structured log를 사용한다.
