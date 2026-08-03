# Customer Order Cancellation and Refund Recovery Runbook

## Scope

이 문서는 고객 주문 취소의 정상 처리, 비동기 owner 보상, 환불 reconciliation,
setup 무결성 탐지와 제한 복구를 운영하는 절차다. 직접 DB 수정, snapshot 추정 복원과
새 Provider REQUEST를 만드는 break-glass 조치는 포함하지 않는다.

## Success semantics

| HTTP result | Durable meaning | Not guaranteed |
|---|---|---|
| `200` | `PENDING_PAYMENT` Order 취소, 예약 해제, 접수 알림과 Audit commit | Provider 작업 없음 |
| `202` | `PAID` Order 취소와 Refund/보상/알림 작업의 원자적 착수 | 환불·owner 복원·알림 성공 |
| `409 PAYMENT_REFUND_UNRESOLVED` | 선행 Refund가 확정되지 않아 Order 무변경 | 자동 재시도 성공 |
| `409 ORDER_STATE_CONFLICT` | 취소 불가 상태거나 deadline timeout work가 별도 접수됨 | 즉시 `REJECTED` 전이 |
| `503 DEPENDENCY_UNAVAILABLE` | 필수 DB·Audit·publication 경계 실패, 성공 아님 | fallback 처리 |

동일 고객·동일 `Idempotency-Key`·동일 payload는 최초 terminal body를 재생한다. 이미
rollback된 요청은 멱등 레코드를 남기지 않는다. 고객의 자유 입력 `detail`은 API,
event, log, metric과 Audit summary에 노출하지 않는다.

## Initial triage

Order와 compensation 상태를 먼저 확인한다.

```sql
SELECT o.id, o.state, o.version, o.cancelled_at, o.cancellation_cause,
       c.id AS compensation_case_id, c.state AS compensation_state
  FROM ordering_order o
  LEFT JOIN operations_order_compensation_case c ON c.order_id = o.id
 WHERE o.id = :order_id;
```

운영자 API의 step 상세 조회에는 `PLATFORM_OPERATOR` role, active
`ORDER_COMPENSATION_READ` grant와 `X-Access-Reason`이 모두 필요하다.

```text
GET /api/v1/operations/orders/{orderId}/compensation
```

- `CANCELLED`가 아니면 고객 취소 recovery를 실행하지 않는다.
- `CANCELLED`지만 `cancellation_cause != CUSTOMER_REQUEST`이면 이 runbook 대상이 아니다.
- 고객 응답의 `PROCESSING + REFUND_DELAYED`는 내부 실패 은폐가 아니라 별도 운영자
  상태 확인이 필요한 안전 projection이다.

## Refund reconciliation

```sql
SELECT r.id, r.state, r.request_attempt_count, r.lookup_attempt_count,
       r.next_action, r.next_attempt_at, r.claim_until, r.last_failure_code,
       s.cancellation_order_version
  FROM payment_refund r
  LEFT JOIN payment_cancellation_recovery_snapshot s
    ON s.cancellation_refund_id = r.id
 WHERE r.order_id = :order_id
 ORDER BY r.created_at, r.id;
```

- REQUEST는 adapter allowlist의 부수효과 없음·same-key 안전 실패만 최초 포함 최대 3회다.
- 결과 불명 또는 만료 claim 뒤에는 REQUEST를 다시 보내지 않고 LOOKUP만 최대 5회다.
- `request_attempt_count + lookup_attempt_count = attempt_count`여야 한다.
- 다섯 번째 LOOKUP 뒤 불명, 최종 claim 저장 단절 또는 비허용 명시 실패는
  `MANUAL_REVIEW`/`FAILED`로 남고 성공으로 바뀌지 않는다.
- Provider reference와 key는 승인된 Provider 조사 채널에서만 사용하고 ticket, metric,
  일반 log나 repair API 응답에 복사하지 않는다.

### Single-operator terminal LOOKUP

Refund가 `FAILED` 또는 `MANUAL_REVIEW`로 종결됐지만 Provider의 authoritative 결과를 다시
확인해야 하면 active `CUSTOMER_CANCELLATION_REFUND_RECONCILE` grant를 가진
`PLATFORM_OPERATOR` 한 명이 다음 명령을 사용할 수 있다.

```text
POST /api/v1/operations/orders/{orderId}/customer-cancellation-refund-reconciliations
Idempotency-Key: <8..128 characters>
{"reason":"provider result investigation completed"}
```

- `202 LOOKUP_SCHEDULED`는 환불 성공이 아니다. Refund를 `UNKNOWN + LOOKUP`, PAYMENT
  step을 `UNKNOWN`으로 다시 열었다는 뜻이다.
- 서버는 Order/version, Payment, recovery snapshot, Refund source/key/금액과 Case/PAYMENT
  step을 다시 검증한다. setup issue가 있으면 실행하지 않고 기존 detector Case/Audit로
  수렴한다.
- API transaction은 권한, Refund/step, actor+key 멱등 command와 Audit만 commit한다.
  Provider 호출은 transaction 밖의 Refund worker가 기존 key로 정확히 한 번 LOOKUP한다.
- 자동 LOOKUP count는 초기화하거나 증가시키지 않는다. 운영 marker가 claim 한 번만 별도
  허용하며, 성공은 기존 result transaction으로 수렴한다.
- 명시 실패·불명·claim 만료는 추가 자동 retry 없이 다시 terminal 상태로 돌아간다. 기존
  delayed event/Delivery를 유지하고 같은 logical source의 지연 알림을 새로 만들지 않는다.
- 같은 actor/key와 같은 canonical payload는 최초 202 body를 재생하고 다른 payload는
  `409 IDEMPOTENCY_KEY_REUSED`다. 서로 다른 key의 동시 명령도 Refund lock에서 하나만
  예약된다.
- request/response/Audit에 금액, Refund/Payment/Provider 식별자, raw key와 자유 입력 사유를
  복제하지 않는다. 운영 사유 원문은 90일 command store에만 보존된다.

다음 필드로 예약과 결과를 확인한다.

```sql
SELECT state, next_action, operator_reconciliation_pending,
       request_attempt_count, lookup_attempt_count, next_attempt_at,
       claim_until, last_failure_code
  FROM payment_refund
 WHERE order_id = :order_id
   AND reason = 'CUSTOMER_ORDER_CANCELLED';
```

marker가 `true`인 동안 새 운영 명령을 보내지 않는다. LOOKUP 결과가 terminal이면 새 증거와
새 `Idempotency-Key`로만 재검토한다. Provider 성공을 수기로 입력하거나 REQUEST endpoint를
대용하지 않는다.

## Owner compensation

`OrderCancelledV1`은 Pickup, Stock, Coupon, Points 네 stable listener target을 갖는다.
한 target의 publication retry가 소진되면 해당 step만 `MANUAL_REVIEW`로 전환한다. 다른
owner step을 함께 실패시키거나 이미 확정된 Order `CANCELLED`를 되돌리지 않는다.

owner replay는 terminal 상태만으로 성공 처리하지 않는다. terminal Order version에서
파생된 source, trigger와 benefit policy version이 모두 같아야 한다. 충돌은 기존 owner
state를 덮어쓰지 않고 `COMPENSATION_SOURCE_CONFLICT`로 조사한다.

## Setup integrity

양수 고객 취소 Refund 또는 recovery snapshot이 누락되거나 source/금액 tie-out이
어긋나면 inline 조회와 1분·batch 100 scanner가 같은
`PAYMENT_CANCELLATION_SETUP` Case/Audit로 수렴한다.

```sql
SELECT id, owner_reference, status, reason, resolution, created_at, updated_at
  FROM operations_reprocessing_case
 WHERE case_type = 'PAYMENT_CANCELLATION_SETUP'
   AND status IN ('OPEN', 'MANUAL_REVIEW')
 ORDER BY created_at, id;
```

다음 조합만 application repair 대상이다.

- 같은 terminal Order version의 `CANCELLED + CUSTOMER_REQUEST`
- 승인된 외부 Payment와 일치하는 완전한 recovery snapshot
- 양수 요청액과 승인액/취소 전 성공 환불액 tie-out
- snapshot에 원 Refund ID, source와 Provider key가 모두 존재
- 해당 ID/source/key를 점유한 Refund와 다른 unresolved Refund가 없음
- 같은 version의 setup Case가 `OPEN`

snapshot 누락·손상, amount/source 불일치와 충돌은 현재 값으로 채우지 않는다. Case를
열린 engineering remediation 대상으로 유지하고 새 proposal을 만들지 않는다.

## Two-person missing-Refund repair

제안자와 결정자는 서로 다른 actor여야 하고 둘 다 실행 시점에
`PLATFORM_OPERATOR` role과 active `PAYMENT_CANCELLATION_SETUP_REPAIR` grant를 가져야
한다. grant는 controlled offline bootstrap으로만 변경한다.

1. 제안자는 Case ID, `Idempotency-Key`와 수동 사유만 보낸다.

   ```text
   POST /api/v1/operations/reprocessing-cases/{caseId}/repair-proposals
   {"reason":"verified missing Refund row"}
   ```

2. proposal은 30분 동안만 유효하다. 응답의 Case/proposal ID와 만료 시각만 인계하고
   금융 값이나 Provider key를 별도 전달하지 않는다.
3. 다른 운영자가 승인 또는 거절한다.

   ```text
   POST /api/v1/operations/reprocessing-repair-proposals/{proposalId}/decisions
   {"decision":"APPROVE","reason":"independent evidence verified"}
   ```

4. 승인 transaction은 Order → Payment → proposal 순서로 잠그고 모든 guard를 다시
   검증한다. 만료는 `EXPIRED`, 데이터 변경은 `STALE`, 명시 거절은 `REJECTED`다.
5. 성공하면 원 Refund ID/source/key/amount를 가진 row가 `RECONCILING`, next action
   `LOOKUP`으로 생성되고 PAYMENT step은 `UNKNOWN`, setup Case는 `RESOLVED`가 된다.
   승인 HTTP transaction 안에서 Provider는 호출되지 않는다.
6. commit 뒤 Refund worker의 LOOKUP 결과가 기존 성공·retry·manual-review 전이로
   수렴하는지 확인한다. 새 REQUEST를 수동 실행하지 않는다.

동시 승인/거절은 terminal proposal 하나만 만든다. 같은 actor/operation/key와 같은
payload는 최초 결과를 재생하고 다른 payload는 `409 IDEMPOTENCY_KEY_REUSED`다.

## Settlement exclusion

미완료 고객 취소는 SettlementItem/Adjustment를 만들지 않는다. 검증된 Order/Refund
source마다 reason이 `ORDER_NOT_COMPLETED_CUSTOMER_CANCELLATION`인
`SETTLEMENT_REFUND_EXCLUDED` Audit 한 건과
`settlementDisposition=NOT_APPLICABLE`을 남긴다. 기존 SettlementItem이 있거나 Audit
commit이 실패하면 publication을 완료 처리하지 않는다.

자세한 검증 SQL과 publication 재처리는
[Settlement Foundation Runbook](settlement-foundation-runbook.md)을 따른다.

## Scheduled maintenance and retention

- setup scanner: 기본 60초, 최대 batch 100
- repair proposal expiry/idempotency cleanup: 기본 60초, 최대 chunk 100
- repair proposal: 삭제하지 않고 terminal 상태 보존
- repair idempotency: terminal response 90일 보존 후 bounded cleanup
- terminal Refund reconciliation command: actor+key response 90일 보존 후 최대 chunk 100 cleanup
- cancellation/store command idempotency: table별 90일 bounded cleanup
- timeout work: terminal 90일 보존, nonterminal 삭제 금지

## Metrics and alert inputs

- `beanflow.payment.refund.attempts{reason,provider,mode,outcome}`
- `beanflow.operations.payment_setup.scan.count{outcome}`
- `beanflow.operations.payment_setup.scan.duration`
- `beanflow.operations.payment_setup.scan.candidates`
- `beanflow.operations.payment_setup.oldest_age.seconds`
- `beanflow.operations.payment_setup.case.count{reason,state}`
- `beanflow.operations.payment_setup.proposal.count{state}`
- `beanflow.operations.payment_setup.proposal.age.seconds{state}`
- `beanflow.operations.payment_setup.approval.count{outcome}`
- `beanflow.operations.payment_setup.repair.count{outcome,reason}`
- `beanflow.operations.payment_setup.repair.lookup.count{outcome}`
- `beanflow.operations.payment_setup.maintenance.failure`
- `beanflow.operations.customer_cancellation_refund_reconciliation.count{outcome}`
- `beanflow.operations.customer_cancellation_refund_reconciliation.retention.deleted`

alert는 scanner/maintenance failure, 계속 증가하는 open-case oldest age, Refund
`MANUAL_REVIEW`, notification/publication retry exhaustion을 입력으로 구성한다. 현재
non-local monitoring 환경과 실제 SLA가 없으므로 임의의 page threshold나 성능 수치를
이 문서에서 주장하지 않는다. 최초 배포 전에 운영 SLO와 alert routing을 별도 승인한다.

metric tag에는 operator, Order, Payment, Refund, Provider 식별자와 correlation ID를 넣지
않는다.

## Forbidden actions

- `UNKNOWN`, `RECONCILING` 또는 repair 직후 새 Provider REQUEST 전송
- terminal Refund LOOKUP 명령에 금액·Provider 결과·금융 식별자 입력 또는 성공 수기 확정
- 현재 Payment 합계로 누락 snapshot/Refund 금액 추정
- 기존 Refund ID/source/key 충돌 row 덮어쓰기
- 한 운영자의 제안과 승인
- Audit 실패를 무시하고 proposal/Refund/Case를 commit
- fake/local/no-op Provider로 운영 실패 대체
- direct DB repair 또는 checksum repair

위 조치가 필요해 보이면 자동 복구를 중단하고 별도 break-glass 정책·ADR·Audit 설계를
승인받는다.
