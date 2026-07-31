# Store Order Lifecycle and Rejection Recovery Runbook

## Scope

이 문서는 결제 완료 Order의 매장 수락, 제조, 준비 완료, 인도 완료와 거절 보상을
진단한다. Order `REJECTED`는 원본 거절 전이만 확정됐다는 뜻이며 Refund, 자원 복원,
혜택 복원과 고객 알림의 성공을 뜻하지 않는다. Order 또는 owner 자원 row를 직접
수정해 완료 상태로 보이게 하지 않는다.

## Store command and authorization

허용 상태 전이는 다음뿐이다.

```text
PAID -> ACCEPTED -> PREPARING -> READY -> COMPLETED
PAID -> REJECTED
```

`PATCH /api/v1/store-orders/{orderId}/status`는 정상 제조 전이에 `200`, 거절 전이에
`202`를 반환한다. `STORE_OWNER` 또는 `STORE_STAFF` 역할과 해당 Store의 `ACTIVE`
membership이 모두 필요하다. 역할만 있거나 다른 Store membership, `REVOKED`
membership이면 `403`이다.

같은 actor, operation과 Idempotency-Key에 같은 payload를 보내면 최초 응답을
재생한다. 다른 payload는 `409 IDEMPOTENCY_KEY_REUSED`이며, 다른 key로 이미 적용된
전이를 다시 요청하면 `409 ORDER_STATE_CONFLICT`다. 충돌을 우회하려고 idempotency
row를 삭제하지 않는다.

```sql
SELECT actor_id, order_id, operation, idempotency_key,
       response_status, created_at
FROM ordering_store_command_idempotency
WHERE order_id = :order_id
ORDER BY created_at, id;
```

## Acceptance warning and timeout

결제 시 `acceptance_warning_at = paid_at + 2 minutes`,
`acceptance_deadline_at = paid_at + 3 minutes`가 저장된다. 정확히 deadline에 도달한
수락은 허용되지 않으며 timeout 거절이 같은 Order row lock과 상태 guard로 승리한다.

```sql
SELECT id, store_id, paid_at, acceptance_warning_at,
       acceptance_warning_requested_at, acceptance_deadline_at
FROM ordering_order
WHERE state = 'PAID'
ORDER BY acceptance_deadline_at, id;
```

기본 worker 주기는 1초이고 시작 지연은 60초다. 다음 지표와 structured log를 함께
본다.

- `beanflow.order.acceptance.warning.count{outcome}`
- `beanflow.order.acceptance.timeout.count{outcome=rejected}`
- `store_acceptance_warning ... outcome=FAILED`
- `store_acceptance_timeout ... outcome=FAILED`

deadline backlog가 증가하면 PostgreSQL 연결과 row-lock 대기, warning publication
backlog를 먼저 확인한다. worker는 같은 guarded transaction을 재실행하므로 프로세스
재시작은 안전하다. Order를 수동으로 `ACCEPTED` 또는 `REJECTED`로 변경하지 않는다.

## Rejection compensation

Store order 조회 API는 Order와 compensation 전체 상태를 함께 반환한다. DB 진단은
case와 owner별 step을 함께 조회한다.

```sql
SELECT c.order_id, c.state AS case_state, c.policy_version,
       c.policy_mode, c.policy_validity_days,
       s.step_type, s.state AS step_state, s.attempt_count,
       s.last_error_code, s.updated_at
FROM operations_rejection_compensation_case c
JOIN operations_rejection_compensation_step s ON s.case_id = c.id
WHERE c.order_id = :order_id
ORDER BY s.step_type;
```

step은 `PAYMENT`, `PICKUP`, `STOCK`, `COUPON`, `POINTS`,
`CUSTOMER_NOTIFICATION`이다. 주문에서 사용하지 않은 항목은 `NOT_REQUIRED`이고,
나머지가 모두 `SUCCEEDED` 또는 `NOT_REQUIRED`일 때만 case가 `SUCCEEDED`다.
`PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`, `MANUAL_REVIEW`를 완료로 해석하지 않는다.

Pickup, Stock, Coupon과 Points owner는 event source reference Unique Constraint로
중복 delivery를 방어한다. owner 처리는 성공했지만 step 기록이 실패한 경우 같은
publication을 재처리하면 owner가 `ALREADY_APPLIED`를 반환하고 step만 복구된다.
수량이나 원장을 직접 보정하지 않는다.

## Persistent event publications

listener 실패는 10초, 30초, 2분, 5분, 15분 간격으로 다섯 번 재발행한다. 최초
listener 호출 전 프로세스가 종료된 publication도 첫 10초 간격을 사용한다. 다섯 번째
재발행도 실패하면 `EVENT_PUBLICATION` ReprocessingCase를 `MANUAL_REVIEW`로 한 번
생성하고 자동 재발행을 중지한다. `OrderRejectedV1`이면 rejection compensation
case도 `MANUAL_REVIEW`가 된다.

```sql
SELECT id, listener_id, event_type, publication_date,
       completion_attempts, last_resubmission_date
FROM event_publication
WHERE completion_date IS NULL
ORDER BY publication_date, id;
```

```sql
SELECT owner_reference, status, reason, correlation_id, created_at
FROM operations_reprocessing_case
WHERE case_type = 'EVENT_PUBLICATION'
ORDER BY created_at, id;
```

관측 지표:

- `beanflow.event.publication.pending.count`
- `beanflow.event.publication.oldest.age.seconds`
- `beanflow.event.publication.attempt.max`

publication row를 완료 처리하거나 삭제하지 않는다. `MANUAL_REVIEW`에서는 실패한
listener의 owner 상태와 source reference를 read-only 확인하고, 승인된 incident
절차로 원인을 수정한 뒤 별도 재처리 기능을 사용한다.

## Rejection refund

`BENEFIT_ONLY` 주문은 PAYMENT step이 `NOT_REQUIRED`다. 외부 결제 주문은
`payment_refund` 한 건을 만들고 worker가 transaction 밖에서 Provider를 호출한다.

```sql
SELECT r.id, r.order_id, r.payment_id, r.state, r.requested_amount_krw,
       r.succeeded_amount_krw, r.attempt_count, r.next_attempt_at,
       r.provider_request_started_at, r.claim_until, r.last_failure_code
FROM payment_refund r
WHERE r.order_id = :order_id
ORDER BY r.created_at, r.id;
```

최초 refund 요청이 시작된 뒤 timeout이나 프로세스 종료가 발생하면 새 refund 요청을
보내지 않고 같은 provider idempotency key로 조회만 수행한다. 불명 결과는 10초,
30초, 2분, 5분, 15분 간격으로 최대 다섯 번 조회하고 이후 `MANUAL_REVIEW`가 된다.
마지막 claim 직후 종료된 경우에도 lease 만료 후 수동 검토로 종결된다.

- `beanflow.payment.refund.attempts{mode,outcome}`
- `beanflow.payment.refund.unknown.count`

Provider 관리 화면, `provider_idempotency_key`, correlation ID로 실제 환불 결과를
확인한다. `UNKNOWN` 상태에서 새 refund를 호출하거나 Payment의 환불 합계를 직접
수정하지 않는다.

## Notification delivery

매장 경고는 `STORE_OPERATIONS`, 거절과 준비 완료는 `CUSTOMER_APP` logical channel을
사용한다. event, recipient와 logical channel 조합은 유일하다.

```sql
SELECT id, order_id, recipient_type, logical_channel, template,
       state, attempt_count, next_attempt_at, claim_until,
       last_failure_code, correlation_id
FROM notification_delivery
WHERE order_id = :order_id
ORDER BY created_at, id;
```

최초 시도 이후 1분, 5분, 30분에 재시도하고 네 번째 실패 또는 마지막 claim lease
만료 후 `MANUAL_REVIEW`와 `NOTIFICATION_DELIVERY` ReprocessingCase를 남긴다.

- `beanflow.notification.delivery.count{template,outcome}`
- `beanflow.notification.delivery.lag`
- `beanflow.notification.delivery.manual_review.count`

scripted Provider는 `local`이면서 `prod`가 아닌 profile에서만 허용된다. `prod`에서
scripted 설정은 시작 실패하며, 운영 배포에는 실제 `NotificationProvider` adapter가
필수다.

## Expired benefit policy

거절 transaction은 당시 policy version, mode와 validity days를 event와 compensation
case에 snapshot한다. 정책 변경은 이미 거절된 주문에 소급하지 않는다.

- `COMPENSATE_WITH_NEW_ISSUANCE`: 만료된 쿠폰은 같은 Campaign의 새 issuance,
  포인트는 원 allocation별 새 PointLot으로 보상한다.
- `PRESERVE_ORIGINAL_EXPIRY`: 복원 또는 복원 생략 원장을 남기되 이미 만료된 금액을
  가용 잔액으로 만들지 않는다.

정책 변경은 `PLATFORM_OPERATOR`가 version과 Idempotency-Key를 포함한 Operations
API로만 수행한다. policy head 또는 version row를 직접 갱신하지 않는다.

## Deployment and worker activation

V7부터 V12 migration은 순서대로 적용돼야 하며 Hibernate는 `validate`만 사용한다.
worker를 활성화하기 전에 다음을 확인한다.

1. 기존 `PAID` row가 없거나 `paid_at`, warning과 deadline backfill이 분류됐다.
2. 실제 Payment와 Notification Provider 설정이 검증됐다.
3. V7~V12 migration과 PostgreSQL 통합 테스트가 배포 대상 revision에서 통과했다.
4. publication, refund, notification backlog 지표와 alert가 준비됐다.
5. rollback은 migration row 삭제나 상태 직접 변경이 아니라 worker 비활성화와
   incident 절차로 수행한다.

기본 설정은 acceptance 1초, publication 10초, refund와 notification worker 5초
주기다. 이 값은 초기 운영 가정이며 backlog, lock wait, Provider rate limit과
transaction duration을 측정하지 않고 확대하지 않는다.
