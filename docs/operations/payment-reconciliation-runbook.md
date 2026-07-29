# Payment Approval and Reconciliation Runbook

## Scope

이 문서는 외부 Payment의 `APPROVING`, `UNKNOWN`, `RECONCILING`,
`MANUAL_REVIEW`와 늦은 승인 void/refund 복구를 진단한다. 승인·취소·환불 결과를
DB에서 직접 성공 상태로 수정하지 않는다.

## Stuck APPROVING and UNKNOWN

Tx1 이후 Provider 호출이나 Tx2가 중단되면 Payment는 `APPROVING`이고 최초
`APPROVAL_LOOKUP`은 10초 뒤 due가 된다. Provider 승인 요청을 다시 보내지 않고
조회만 수행한다.

```sql
SELECT p.id, p.order_id, p.approval_state, p.updated_at,
       r.status, r.attempt_count, r.next_attempt_at, r.claim_until,
       p.correlation_id
FROM payment_payment p
JOIN payment_reconciliation r ON r.payment_id = p.id
WHERE r.kind = 'APPROVAL_LOOKUP'
  AND p.approval_state IN ('APPROVING', 'UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW')
ORDER BY r.next_attempt_at, p.id;
```

조회 결과 불명은 10초, 30초, 2분, 5분, 15분 schedule에서 최대 5회 처리한다.
다섯 번째에도 불명이면 Payment와 work를 `MANUAL_REVIEW`로 바꾸고 source
reference당 `operations_reprocessing_case` 한 건을 만든다. 자동 호출은 중지한다.

claim 중 프로세스가 종료되면 `claim_until` 이후 다른 worker가 같은 Provider 조회를
다시 claim할 수 있다. claim token이나 상태를 수동 삭제하지 않는다.

```sql
SELECT id, payment_id, kind, status, attempt_count, next_attempt_at, claim_until,
       last_failure_code
FROM payment_reconciliation
WHERE status = 'PROCESSING'
ORDER BY claim_until, id;
```

## Late approval recovery

Order가 이미 `EXPIRED` 또는 `CANCELLED`인데 승인이 확인되면 Order와 예약을
되살리지 않는다. `LATE_VOID`를 먼저 수행하고 Provider가 void 불가를 명시한 경우에만
`LATE_REFUND`를 만든다. void 결과가 불명확하면 다시 조회·재시도하며 승인 잔액이
확인되지 않은 상태에서 환불을 추정 실행하지 않는다.

```sql
SELECT p.id, p.order_id, o.state AS order_state, p.approval_state,
       r.kind, r.status, r.attempt_count, r.last_failure_code
FROM payment_payment p
JOIN ordering_order o ON o.id = p.order_id
JOIN payment_reconciliation r ON r.payment_id = p.id
WHERE r.kind IN ('LATE_VOID', 'LATE_REFUND')
ORDER BY r.updated_at, r.id;
```

`MANUAL_REVIEW`에서는 Provider 관리 화면과 correlation ID로 실제 승인·void·refund
상태를 read-only 확인하고 incident 절차로 복구 결정을 승인받는다. Payment,
Order, reservation, idempotency row를 직접 갱신하지 않는다.

## Metrics and logs

- `beanflow.payment.approval.attempts{outcome}`
- `beanflow.payment.approval.duration`
- `beanflow.payment.unknown.count`
- `beanflow.payment.unknown.age`
- `beanflow.payment.reconciliation.attempts{outcome}`
- `beanflow.payment.reconciliation.lag`
- `beanflow.payment.late_approval.count`
- `beanflow.payment.void.attempts{outcome}`
- `beanflow.payment.refund.attempts{outcome}`

metric tag에는 customer, Order, Payment, Provider transaction과 idempotency key를
넣지 않는다. structured log의 Payment ID와 correlation ID로 개별 흐름을 연결한다.
