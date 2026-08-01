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

## Customer cancellation refund identity

고객 취소 Refund는 `reason = CUSTOMER_ORDER_CANCELLED`이고 고객 신고 사유 code를
별도 저장한다. source reference와 Provider idempotency key는 다음 형식이다.

```text
order:{orderId}:customer-cancellation:{aggregateVersion}:payment
refund:customer-cancellation:{orderId}:{aggregateVersion}
```

Provider 최초 요청과 결과 불명 이후 lookup은 같은 Provider key를 사용한다. event ID,
client `Idempotency-Key`, customer ID와 자유 입력 상세 사유로 새 key를 만들거나
structured log에 기록하지 않는다. 같은 Payment의 고객 취소 Refund가 두 건 보이면
Provider를 다시 호출하지 말고 Unique Constraint 위반과 migration 상태를 먼저
조사한다.

Provider adapter 코드 allowlist가 같은-key 재실행 안전을 보장한 명시 실패만 10초와
30초 뒤 같은 key REQUEST로 최대 두 번 재시도한다. allowlist 밖 code와 세 번째
retryable failure는 Refund `FAILED`, PAYMENT step과 Case `MANUAL_REVIEW`다.

어느 REQUEST든 결과가 불명확하면 추가 REQUEST를 금지하고 10초, 30초, 2분, 5분,
15분 뒤 같은 key로 최대 다섯 번 조회한다. request와 lookup count는 별도이며 최대
Provider 상호작용은 3 + 5 = 8회다. 마지막 lookup도 불명이면 `MANUAL_REVIEW`로
전환한다. 마지막 허용 claim 뒤 worker가 종료된 경우 lease 만료 후 새 Provider 호출
없이 수동 검토로 종결한다. V15부터 `request_attempt_count`, `lookup_attempt_count`와
`next_action`이 독립 3/5 예산과 REQUEST→LOOKUP 비가역 전이를 보존한다.

고객은 내부 `RETRY_SCHEDULED`, `UNKNOWN`, `RECONCILING`을 `PROCESSING`으로만
본다. 내부 `FAILED`·`MANUAL_REVIEW`도 고객 state는 `PROCESSING`이며
`noticeCode = REFUND_DELAYED`로 지연 정보 아이콘만 표시한다. 운영자 화면은 실제
state, 두 attempt count와 마지막 실패 code를 표시한다.

## Store/platform partial Refund and point restoration

`POST /payments/{paymentId}/refunds`는 매장 또는 플랫폼 거래 조정이다. 고객 self-service
명령이 아니며, 매장 역할은 Order의 활성 membership을 함께 검증한다. 요청 transaction은
Order→Payment→성공 allocation→원 PointReservation allocation 순서로 잠그고
`PARTIAL_REFUND×POINTS` policy version을 snapshot한 뒤 commit한다. Provider 호출 중에는
DB transaction을 유지하지 않는다.

같은 actor와 `Idempotency-Key`는 같은 canonical payload에서만 최초 response status/body를
그대로 replay한다. 다른 payload면 `IDEMPOTENCY_KEY_REUSED`, 최초 Provider claim이 진행
중이면 `IDEMPOTENCY_REQUEST_IN_PROGRESS`다. 운영자가 다른 key로 같은 unit을 다시 요청해
우회하지 않는다.

```sql
SELECT r.id, r.payment_id, r.state,
       r.requested_amount_krw AS cash_requested_krw,
       r.requested_points_krw AS points_requested_krw,
       r.request_attempt_count, r.lookup_attempt_count, r.next_action,
       r.point_restoration_policy_version_id, r.point_restoration_policy_mode,
       r.response_status, r.updated_at, r.last_failure_code
FROM payment_refund r
WHERE r.reason = 'PARTIAL_REFUND'
  AND r.payment_id = :payment_id
ORDER BY r.created_at, r.id;
```

성공 cash/coupon/point attribution은 request snapshot과 동일해야 한다. coupon은 귀속 감사
금액일 뿐 Promotion 복원 명령이 아니다. 다음 합계가 Payment 승인액, OrderLine snapshot 또는
원 PointReservation allocation을 넘으면 새 Provider/Loyalty 호출을 중지하고 migration과
원장을 조사한다. V15 deferred constraint trigger도 commit을 거부한다.

```sql
SELECT l.order_line_id,
       sum(l.cash_refunded_krw) AS cash_refunded_krw,
       sum(l.points_restored_krw) AS points_attributed_krw,
       sum(l.coupon_attribution_krw) AS coupon_attribution_krw,
       sum(l.quantity) AS refunded_quantity
FROM payment_refund_line_allocation l
JOIN payment_refund r ON r.id = l.refund_id
WHERE r.payment_id = :payment_id
GROUP BY l.order_line_id
ORDER BY l.order_line_id;
```

현금 성공 뒤 포인트 복원은 Payment의 별도 durable work와 Loyalty local transaction으로
수행한다. Payment transaction에서 Loyalty Entity를 직접 수정하지 않는다. Loyalty commit 뒤
Payment ack가 실패해도 같은 Refund source replay가 기존 원장을 확인하므로 가치를 두 번 만들지
않는다. 자동 시도는 최대 5회며 source/payload conflict, 잘못된 USED 상태 또는 예산 소진은
`MANUAL_REVIEW`다.

```sql
SELECT w.refund_id, w.state, w.requested_amount_krw, w.restored_amount_krw,
       w.attempt_count, w.next_attempt_at, w.claim_until, w.last_failure_code,
       r.point_restoration_policy_version_id, r.point_restoration_policy_mode
FROM payment_refund_restoration_work w
JOIN payment_refund r ON r.id = w.refund_id
WHERE w.state <> 'SUCCEEDED'
ORDER BY w.next_attempt_at NULLS FIRST, w.id;
```

`loyalty_partial_refund_restoration`과 `loyalty_point_transaction`에서 Refund/OrderLine/원
allocation source를 함께 확인한다. 원 Lot은 `refundSucceededAt < expiresAt`일 때만 되살린다.
같거나 지난 시각은 snapshot mode에 따라 같은 issuer의 보상 Lot 또는
`RESTORE_SKIPPED_EXPIRED`다. 부분 환불 뒤 `loyalty_point_reservation.state`는 항상 `USED`다.
Refund, allocation, work, PointTransaction, PointLot balance를 SQL로 직접 수정하지 않는다.

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
- `beanflow.payment.refund.restoration.count{state,outcome}`
- `beanflow.loyalty.partial_refund_restoration.count{disposition,policy_mode,outcome}`

metric tag에는 customer, Order, Payment, Provider transaction과 idempotency key를
넣지 않는다. structured log의 Payment ID와 correlation ID로 개별 흐름을 연결한다.
