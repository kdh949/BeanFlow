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

canonical payload는 `orderId`, `targetState`, 정규화한 `reason`이다. 같은 key를 다른
주문에 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`이며 첫 주문의 응답을 재생하지 않는다
(BR-25 Store Command Scope Amendment). `operation`은 `STORE_ORDER_TRANSITION_V2`이며
승격 이전에 저장된 `STORE_ORDER_TRANSITION` 레코드는 조회되지 않고 BR-26 보존 기간
뒤 정리된다. 아래 조사 쿼리에서 두 `operation` 값이 함께 보일 수 있다.

```sql
SELECT actor_id, order_id, operation, idempotency_key,
       response_status, created_at
FROM ordering_store_command_idempotency
WHERE order_id = :order_id
ORDER BY created_at, id;
```

고객 취소와 매장 전이 멱등 레코드는 하나의 Ordering retention worker가 기본
1시간마다 table별 독립 transaction으로 최대 100건씩 정리한다. 두 table 모두
`retention_expires_at = created_at + 90일`과
`(retention_expires_at, id)` keyset을 사용한다. 한 table 실패를 다른 table의 0건
성공으로 기록하지 않는다. 구 `STORE_ORDER_TRANSITION` row도 자체 만료 전에는
삭제하지 않는다.

- `beanflow.ordering.idempotency.retention.deleted{table}`
- `beanflow.ordering.idempotency.retention.failure{table}`
- `beanflow.ordering.idempotency.retention.oldest_due_age.seconds{table}`
- `beanflow.ordering.idempotency.retention.backlog{table}`

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

acceptance deadline 뒤 고객 취소가 도착하면 취소 endpoint는
`AcceptanceTimeoutWork`와 Audit를 저장하고 409를 반환한 뒤 같은 timeout service를
즉시 깨운다. 이 work는 in-memory wakeup이 아니라 DB row가 복구 근거다. periodic
scanner와 경쟁하면 Order lock과 timeout source unique로 한 번만 거절한다.
`PENDING`, `CLAIMED`, `MANUAL_REVIEW` work를 retention cleanup으로 삭제하지 않는다.

- `beanflow.order.acceptance_timeout.work.count{state,outcome}`
- `beanflow.order.acceptance_timeout.work.lag`
- `beanflow.order.acceptance_timeout.work.manual_review.count`

- `beanflow.order.acceptance.warning.count{outcome}`
- `beanflow.order.acceptance.timeout.count{outcome=rejected}`
- `store_acceptance_warning ... outcome=FAILED`
- `store_acceptance_timeout ... outcome=FAILED`

deadline backlog가 증가하면 PostgreSQL 연결과 row-lock 대기, warning publication
backlog를 먼저 확인한다. worker는 같은 guarded transaction을 재실행하므로 프로세스
재시작은 안전하다. Order를 수동으로 `ACCEPTED` 또는 `REJECTED`로 변경하지 않는다.

## Rejection compensation

Store order 조회 API는 Order와 함께 `trigger`, case `state`, `updatedAt`만 담은
축약 보상 요약을 반환한다. step 상세, `attemptCount`, `lastErrorCode`, `caseId`와
policy version은 운영자 전용 `GET /api/v1/operations/orders/{orderId}/compensation`과
아래 DB 진단에서만 확인한다. DB 진단은 case와 owner별 step을 함께 조회한다.

```sql
SELECT c.order_id, c.trigger, c.state AS case_state,
       s.step_type, s.state AS step_state, s.attempt_count,
       s.last_error_code, s.updated_at
FROM operations_order_compensation_case c
JOIN operations_order_compensation_step s ON s.case_id = c.id
WHERE c.order_id = :order_id
ORDER BY s.step_type;
```

```sql
SELECT p.benefit_type, p.policy_version_id
FROM operations_order_compensation_benefit_policy_snapshot p
JOIN operations_order_compensation_case c ON c.id = p.case_id
WHERE c.order_id = :order_id
ORDER BY p.benefit_type;
```

step은 `PAYMENT`, `PICKUP`, `STOCK`, `COUPON`, `POINTS`,
`CUSTOMER_NOTIFICATION`이다. 주문에서 사용하지 않은 항목은 `NOT_REQUIRED`이고,
나머지가 모두 `SUCCEEDED` 또는 `NOT_REQUIRED`일 때만 case가 `SUCCEEDED`다.
`PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`, `MANUAL_REVIEW`를 완료로 해석하지 않는다.

Pickup, Stock, Coupon과 Points owner는 event source reference Unique Constraint로
중복 delivery를 방어한다. owner 처리는 성공했지만 step 기록이 실패한 경우 같은
publication을 재처리하면 owner가 `ALREADY_APPLIED`를 반환하고 step만 복구된다.
수량이나 원장을 직접 보정하지 않는다.

Pickup·Stock은 terminal state `RELEASED_AFTER_TERMINATION`과
`restoration_trigger`를 함께 확인한다. 거절은 `STORE_REJECTION`, 고객 취소는
`CUSTOMER_CANCELLATION`이어야 한다. 같은 source·trigger의 재전달은 수량 변화 없이
멱등 성공이고, 다른 source 또는 trigger가 보이면 row를 수정하지 말고
`COMPENSATION_SOURCE_CONFLICT`와 해당 step 상태를 조사한다.

Coupon·Points는 owner source와 함께 `restoration_trigger`,
`restoration_policy_version_id`를 확인한다. Coupon은
`ORIGINAL_RESTORED | COMPENSATION_ISSUED | SKIPPED_EXPIRED`,
Points는 `RESTORE | COMPENSATION | RESTORE_SKIPPED_EXPIRED` 결과를 보존한다.
`SKIPPED_EXPIRED`는 사용 가능 가치를 만들지 않지만 선택된 정책의 성공 결과다.
source·trigger·policy 중 하나라도 다르면 기존 issuance·lot·잔액을 수정하지 않는다.

`COMPENSATION_ISSUED` Coupon은 원 Campaign ID와 함께 issuance-owned terms snapshot과
eligible menu child row를 가져야 한다. Campaign이 inactive여도 보상 issuance는 이
snapshot으로 계산한다. snapshot 누락 시 live Campaign으로 대체하지 말고 COUPON
step과 publication을 실패 처리한다.

## Persistent event publications

listener 실패는 10초, 30초, 2분, 5분, 15분 간격으로 다섯 번 재발행한다. 최초
listener 호출 전 프로세스가 종료된 publication도 첫 10초 간격을 사용한다. 다섯 번째
재발행도 실패하면 `EVENT_PUBLICATION` ReprocessingCase를 `MANUAL_REVIEW`로 한 번
생성하고 해당 publication의 자동 재발행을 중지한다. `OrderRejectedV1` 또는
`OrderCancelledV1`이면 실패 listener에 대응하는 단일 compensation step만
`EVENT_PUBLICATION_RETRY_EXHAUSTED`와 `MANUAL_REVIEW`가 된다. Case state는
`MANUAL_REVIEW`로 파생되지만 다른 publication과 step은 계속 처리한다. publication
completion attempt를 step의 owner `attempt_count`에 더하지 않는다.

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
고객 취소에서도 같은 원칙을 적용한다. `BENEFIT_ONLY` 취소는 recovery snapshot
0/0/0과 PAYMENT `NOT_REQUIRED`를 Tx C1에 저장하고 Refund·Provider 호출은 만들지
않는다. 다른 다섯 step은 계속 처리한다.

```sql
SELECT r.id, r.order_id, r.payment_id, r.state, r.requested_amount_krw,
       r.succeeded_amount_krw, r.attempt_count,
       r.request_attempt_count, r.lookup_attempt_count, r.next_action,
       r.next_attempt_at,
       r.provider_request_started_at, r.claim_until, r.last_failure_code
FROM payment_refund r
WHERE r.order_id = :order_id
ORDER BY r.created_at, r.id;
```

Provider adapter가 코드 allowlist로 인정한 같은-key 안전 명시 실패만 10초·30초 뒤
최대 두 번 REQUEST 재시도한다. 미등록 code 또는 세 번째 같은 실패는 Refund
`FAILED`, PAYMENT step과 Case `MANUAL_REVIEW`다.

refund 요청 결과가 불명확하거나 요청 claim 뒤 프로세스가 종료되면 새 refund 요청을
보내지 않고 같은 provider idempotency key로 조회만 수행한다. 불명 결과는 10초,
30초, 2분, 5분, 15분 간격으로 최대 다섯 번 조회한다. request와 lookup attempt를
별도로 확인하고 합계가 `attempt_count`와 일치해야 한다. 다섯 번째 조회 뒤에도
불명이면 `MANUAL_REVIEW`가 된다.
마지막 claim 직후 종료된 경우에도 lease 만료 후 수동 검토로 종결된다.

- `beanflow.payment.refund.attempts{mode,outcome}`
- `beanflow.payment.refund.unknown.count`

Provider 관리 화면, `provider_idempotency_key`, correlation ID로 실제 환불 결과를
확인한다. `UNKNOWN` 상태에서 새 refund를 호출하거나 Payment의 환불 합계를 직접
수정하지 않는다.

## Customer cancellation setup integrity

고객 취소 Refund 또는 Payment recovery snapshot 누락, source version 불일치와 금액
tie-out 위반은 `PAYMENT_CANCELLATION_SETUP` ReprocessingCase로 관리한다. 관련 조회와
worker가 즉시 탐지하고, 기본 1분·batch 100의 Operations scanner가 접근 없는 손상을
보완한다. 두 경로는 Order terminal version source unique로 같은 case와
`PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED` AuditRecord 한 건에 수렴한다.

운영 화면은 고객용 `PROCESSING + REFUND_DELAYED` 대신 실제 missing artifact,
invariant violation, 최초 감지 시각과 error code를 표시한다. case 또는 Audit 저장
실패를 성공 탐지로 간주하지 않는다. Refund row를 임의 생성하거나 현재 Payment
금액으로 snapshot을 채우지 않는다.

- `beanflow.operations.payment_setup.scan.count{outcome}`
- `beanflow.operations.payment_setup.scan.duration`
- `beanflow.operations.payment_setup.scan.candidates`
- `beanflow.operations.payment_setup.case.count{reason,state}`
- `beanflow.operations.payment_setup.oldest_age.seconds`

### Missing Refund repair

application-level 복구는 recovery snapshot이 완전하고 Refund row만 누락된 case로
제한한다. 첫 operator가 case에 non-blank 사유로 30분 proposal을 만들고, 다른 활성
PLATFORM_OPERATOR가 승인해야 한다. 승인 시 Order→Payment 잠금 아래 snapshot,
source, 금액, Refund 충돌과 proposal fingerprint를 다시 검증한다.

승인 성공은 원 Refund ID·amount·source·Provider key의 Refund를
`RECONCILING`, next action `LOOKUP`으로 저장한다. Provider REQUEST를 즉시 보내지
않는다. snapshot 누락, tie-out/source 불일치와 기존 Refund 충돌은
`REPROCESSING_NOT_SAFE` 또는 stale proposal로 남기며 현재 값으로 보완하지 않는다.

- proposer와 approver는 같을 수 없다.
- 제안 유효 구간은 `[createdAt, createdAt + 30분)`이다.
- proposal/Refund/PAYMENT step/case/Audit 저장은 한 transaction이다.
- Provider LOOKUP은 commit 뒤 worker가 수행한다.
- 직접 DB 수정은 승인된 별도 break-glass runbook 없이는 허용하지 않는다.

- `beanflow.operations.payment_setup.proposal.count{state}`
- `beanflow.operations.payment_setup.approval.count{outcome}`
- `beanflow.operations.payment_setup.repair.count{outcome,reason}`

## Notification delivery

매장 경고는 `STORE_OPERATIONS`, 거절과 준비 완료는 `CUSTOMER_APP` logical channel을
사용한다. event, recipient와 logical channel 조합은 유일하다.

고객 취소 접수는 `ORDER_CANCELLATION_ACCEPTED` template과 `CUSTOMER_APP` channel을
사용한다. `PENDING_PAYMENT` Tx C0와 `PAID` Tx C1이 delivery `PENDING`을 직접
저장하며, insert 실패는 해당 취소 transaction을 rollback한다. source는
`order:{orderId}:customer-cancellation:{aggregateVersion}:accepted-notification`,
Provider key는
`notification:customer-cancellation-accepted:{orderId}:{aggregateVersion}`다.
`OrderCancelledV1` Notification publication이나 listener로 접수 delivery를 다시
만들지 않는다. 성공 응답은 delivery 저장을 뜻하며 실제 발송 결과는 아래 worker
상태로 확인한다.

주문 보상 Case의 CUSTOMER_NOTIFICATION step은 이 기본 접수 Delivery만 추적한다.
환불 성공·지연 후속 알림은
`CustomerCancellationRefundSucceededV1`,
`CustomerCancellationRefundDelayedV1` publication과 별도 Delivery,
`EVENT_PUBLICATION` 또는 `NOTIFICATION_DELIVERY` ReprocessingCase에서 확인한다.
후속 알림 실패 때문에 성공한 기본 step이나 완료된 Case를 다시 열지 않는다.

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

매장 거절과 고객 취소 transaction은 trigger별 COUPON, POINTS policy head를 이
순서로 잠그고 두 version FK를 compensation case child row에 저장한다. event에는
두 version의 mode와 validity days까지 전체 snapshot한다. 정책 변경은 이미 종료된
주문에 소급하지 않으며 consumer 재시도는 현재 head를 조회하지 않는다.

- `COMPENSATE_WITH_NEW_ISSUANCE`: 만료된 쿠폰은 같은 Campaign의 새 issuance,
  포인트는 원 allocation별 새 PointLot으로 보상한다.
- `PRESERVE_ORIGINAL_EXPIRY`: 복원 또는 복원 생략 원장을 남기되 이미 만료된 금액을
  가용 잔액으로 만들지 않는다.

기존 거절 COUPON·POINTS head는 기존 설정을 이어받고 고객 취소 두 head는
`PRESERVE_ORIGINAL_EXPIRY`로 시작한다. 정책 변경은 `PLATFORM_OPERATOR`가 base
목록 GET과 trigger/benefit keyed PATCH를 사용해 한 head씩 수행한다.
`expectedPolicyVersionId`, Idempotency-Key와 reason이 필수다. policy head 또는
version row를 직접 갱신하거나 과거 version을 수정·삭제하지 않는다.

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
