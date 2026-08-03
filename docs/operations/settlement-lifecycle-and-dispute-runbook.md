# Settlement Lifecycle and Dispute Runbook

## Scope and safety boundary

이 문서는 일별 `SettlementBatch` 계산·확정, 확정 후 `SettlementAdjustment`, OWNER
`SettlementDispute`와 관련 persistent publication/ReprocessingCase를 진단한다. 실제 계좌 지급,
외부 evidence 저장소와 공개 판정 API는 구현 범위가 아니다.

운영자는 `settlement_batch`, `settlement_item`, `settlement_adjustment`, `settlement_dispute`,
`event_publication`, `operations_audit_record` 또는 `operations_reprocessing_case`를 SQL로
생성·수정·삭제하지 않는다. 원천이나 금액을 추정해 0원 row, fake success, 강제 completion을
만들지 않는다. 아래 SQL은 모두 read-only 진단용이다.

## Daily Batch lifecycle

정상 Batch는 같은 store/date에 하나이고 다음 상태만 따른다.

```text
OPEN -> CALCULATED -> CONFIRMED
```

공개 Batch 목록은 summary가 존재하는 `CALCULATED`와 `CONFIRMED`만 반환한다. `OPEN`은 빈
명세나 0원 정산으로 노출하지 않는다. 계산 worker는 서울 기준 전일 이전 `OPEN` Batch를 한 번에
최대 100개 선택하고 각 Batch의 Item/Adjustment를 500건 keyset chunk로 합산한다. 같은 매장의
이전 날짜 Batch가 미확정이면 다음 날짜 계산을 시작하지 않는다.

```sql
SELECT id, store_id, settlement_date, state, item_count,
       gross_paid_krw, fee_krw, benefit_cost_krw,
       item_net_settlement_krw, adjustment_krw,
       carry_forward_in_krw, carry_forward_out_krw,
       carry_forward_source_batch_id,
       calculated_at, confirmed_at
FROM settlement_batch
WHERE id = :settlement_batch_id;
```

`OPEN`인데 summary가 있거나 `CALCULATED`/`CONFIRMED`인데 summary가 없으면 정상 운영 상태가
아니며 V28 CHECK를 우회한 데이터 가능성을 조사한다. summary를 직접 채우지 않는다.
`CALCULATED`가 장시간 확정되지 않으면 confirmation Audit/publication dependency를 먼저 본다.

```sql
SELECT id, action, target_type, target_id, occurred_at,
       correlation_id, source_reference
FROM operations_audit_record
WHERE action = 'SETTLEMENT_BATCH_CONFIRMED'
  AND target_id = :settlement_batch_id;

SELECT id, listener_id, status, completion_attempts,
       publication_date, last_resubmission_date, completion_date
FROM event_publication
WHERE event_type = 'io.github.kdh949.beanflow.eventing.api.SettlementBatchConfirmedV1'
  AND completion_date IS NULL
ORDER BY publication_date, id;
```

DB/Audit/outbox 장애를 복구한 뒤 기존 bounded publication recovery와 Batch worker가 재시도하게
한다. `CALCULATED` summary를 바꾸거나 `CONFIRMED`를 되열지 않는다.

## Refund and Dispute Adjustment

completed-order Refund 또는 accepted Dispute는 confirmed Item/Batch에 stable source의
append-only Adjustment 하나를 만든다. pre-acceptance customer cancellation은 이 경로가 아니라
기존 `NOT_APPLICABLE` Audit 경로다. unconfirmed Item Refund는 publication이 미완료로 남아야 하며
0원/no-op Adjustment로 완료하지 않는다.

```sql
SELECT id, settlement_item_id, source_settlement_batch_id,
       adjustment_source, reason_code, effective_at,
       settlement_date, currency, amount_krw, created_at
FROM settlement_adjustment
WHERE settlement_item_id = :settlement_item_id
ORDER BY created_at, id;
```

같은 `adjustment_source`가 다른 target/reason/amount로 재사용되면 기존 row를 수정하지 않고
`SETTLEMENT_ADJUSTMENT` Case와 미완료 Refund publication을 함께 조사한다.

```sql
SELECT id, case_type, owner_reference, status, reason,
       resolution, correlation_id, created_at, updated_at
FROM operations_reprocessing_case
WHERE case_type IN ('SETTLEMENT_ADJUSTMENT', 'SETTLEMENT_DISPUTE')
  AND status = 'MANUAL_REVIEW'
ORDER BY updated_at, id;
```

## Dispute filing and decision

접수는 confirmed Batch의 서울 날짜 D 기준 `[D+1 00:00, D+15 00:00)`에서 active OWNER
membership만 가능하다. 같은 Item의 `FILED`/`UNDER_REVIEW`는 하나다. terminal 뒤 재이의는
immediate previous dispute ID와 이전 배열에 없던 evidence reference를 제시한 별도 Aggregate로
한 번만 가능하다. reason/evidence/actor/idempotency key는 민감 운영 입력이므로 일반 조회·로그·
metric에 복제하지 않는다.

```sql
SELECT id, settlement_item_id, store_id, previous_dispute_id,
       refile_count, state, expected_adjustment_krw,
       held_amount_krw, settlement_adjustment_id,
       filed_at, decided_at, version
FROM settlement_dispute
WHERE settlement_item_id = :settlement_item_id
ORDER BY filed_at, id;
```

review worker는 `FILED → UNDER_REVIEW`만 수행한다. 이번 범위에는 자동 판정 정책과 공개 판정
endpoint가 없으므로 운영자가 DB state를 직접 terminal로 만들지 않는다. `REJECTED`와
`WITHDRAWN`은 held 0, Adjustment 부재여야 한다. `ACCEPTED`는
`dispute:{disputeId}:accepted` Adjustment가 먼저 commit된 뒤에만 held 0/terminal이 된다.

Adjustment가 있는데 Dispute가 `UNDER_REVIEW`이면 decision transaction의 Audit/publication
실패 가능성을 확인한다. 이 상태는 부분 성공을 숨긴 것이 아니라 재시도 가능한 명시 상태다.
`settlement-dispute:{disputeId}:decision` owner reference의 Case가 `MANUAL_REVIEW`여야 하며,
같은 decision 명령 재시도는 기존 Adjustment를 exact 검증하고 Dispute terminal commit 뒤 Case를
`RESOLVED`로 바꾼다.

```sql
SELECT id, listener_id, status, completion_attempts,
       publication_date, last_resubmission_date, completion_date
FROM event_publication
WHERE event_type IN (
    'io.github.kdh949.beanflow.eventing.api.SettlementDisputeFiledV1',
    'io.github.kdh949.beanflow.eventing.api.SettlementDisputeDecidedV1'
)
  AND completion_date IS NULL
ORDER BY publication_date, id;
```

정상 listener는 filing/decision 각각
`beanflow.operations.settlement-dispute-filed-v1`,
`beanflow.operations.settlement-dispute-decided-v1`이다. listener mismatch, Audit/outbox 저장 실패,
confirmed Item view 불일치는 성공으로 완료하지 않고 dependency/source를 복구한 뒤 bounded retry한다.

## Metrics and alerts

- `beanflow.settlement.batch.count{state,outcome}`
- `beanflow.settlement.batch.chunk_count`, `beanflow.settlement.batch.item_count`
- `beanflow.settlement.batch.calculation_lag_seconds`
- `beanflow.settlement.carry_forward.age_seconds`
- `beanflow.settlement.adjustment.count{reason_code,outcome}`
- `beanflow.settlement.dispute.count{state,outcome}`
- `beanflow.settlement.dispute.held_amount_abs_krw`
- `beanflow.settlement.reprocessing.count{reason,outcome}`
- `beanflow.settlement.batch_query.count{outcome}`, `beanflow.settlement.batch_query.page_size`

tag는 closed state/reason/outcome만 사용한다. store/order/customer/payment/Batch/Item/Dispute ID,
actor, source, correlation, evidence, client key와 raw amount를 tag에 넣지 않는다. 개별 사건은 제한된
Audit/publication/Case access와 correlation ID로 진단한다.

## Escalation and exit conditions

- source/target/amount mismatch, missing immutable Item 또는 unexplained summary tie-out은 자동 수정
  대상이 아니다. source-unique Case를 보존하고 코드·migration owner에게 이관한다.
- 같은 publication의 bounded retry가 소진되면 성공 completion을 수기로 만들지 않고
  `EVENT_PUBLICATION` 또는 정산 Case의 `MANUAL_REVIEW` 증적을 유지한다.
- 실제 지급 hold, 운영자 판정 API, evidence file provider 또는 자동 late-Item 재귀속이 필요하면
  현재 상태를 확장하지 말고 Business Policy/ADR/ExecPlan을 먼저 갱신한다.
