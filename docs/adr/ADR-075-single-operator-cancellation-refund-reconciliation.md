# ADR-075: 고객 취소 terminal Refund의 단일 운영자 LOOKUP 재개

- **Status:** Accepted
- **Date:** 2026-08-03

## Context

ADR-045/046은 고객 취소 Refund의 자동 처리가 끝나 지연 알림이 생성된 뒤 운영 복구로
실제 환불 성공이 확인되면 성공 event와 별도 성공 알림도 생성하도록 정했다. 그러나 기존
Refund aggregate의 `FAILED`와 `MANUAL_REVIEW`는 terminal이며 다시 claim할 수 없고, 이를
여는 운영 API·권한·멱등성·감사 계약이 없었다. Notification listener 수준에서 지연과 성공
logical source가 서로 다름을 검증하는 것만으로는 금융 원장의 수동 복구 경로가 완성되지
않는다.

운영자가 성공을 직접 입력하거나 새 Refund REQUEST를 만들면 Provider 부수효과와 실제
원장을 추측할 수 있다. 반면 기존 Provider idempotency key의 LOOKUP은 새 환불을 요청하지
않고 Provider가 보유한 결과만 확인한다.

## Decision

- 활성 `CUSTOMER_CANCELLATION_REFUND_RECONCILE` persistent grant를 가진
  `PLATFORM_OPERATOR` 한 명이 고객 취소 terminal Refund의 LOOKUP 재개를 요청할 수 있다.
- API는
  `POST /api/v1/operations/orders/{orderId}/customer-cancellation-refund-reconciliations`다.
  `Idempotency-Key`와 1~500자의 운영 사유만 받으며 금액, Refund/Payment/Provider ID,
  Provider key/reference, 상태와 성공 여부는 입력받지 않는다. 알 수 없는 JSON 필드는
  fail-closed `400 INVALID_REQUEST`다.
- 허용 source는 다음을 모두 만족해야 한다.
  - Order가 `CANCELLED + CUSTOMER_REQUEST`이고 terminal version이 현재 version이다.
  - recovery snapshot, Payment와 고객 취소 Refund가 존재하고 source/version/key/금액이
    tie-out한다.
  - Refund reason은 `CUSTOMER_ORDER_CANCELLED`, 금액은 양수이며 state는 `FAILED` 또는
    `MANUAL_REVIEW`다.
  - OrderCompensationCase trigger/version과 PAYMENT step이 같은 고객 취소를 가리킨다.
- 명령은 Refund를 `UNKNOWN`, next action `LOOKUP`, 즉시 due로 바꾸고 PAYMENT step을
  `UNKNOWN`으로 다시 연다. 자동 LOOKUP 5회 count는 보존한다. 별도
  `operator_reconciliation_pending` marker가 다음 claim 한 번만 자동 budget과 독립된
  operator-authorized LOOKUP으로 허용한다.
- Payment worker는 기존 Provider idempotency key로 LOOKUP만 실행한다. 새 REQUEST,
  운영자 수기 성공과 Provider reference 입력은 금지한다.
- LOOKUP 성공은 기존 result transaction에서 Refund/Payment/step과
  `CustomerCancellationRefundSucceededV1`을 원자적으로 확정한다. 이미 존재하는 지연
  event·Delivery는 유지되고 성공 logical source의 event·Delivery가 별도로 한 번 생긴다.
- LOOKUP 실패 또는 불명 결과는 `FAILED` 또는 `MANUAL_REVIEW`로 다시 종결한다. 자동 retry
  budget을 초기화하거나 추가 자동 LOOKUP을 예약하지 않는다. 필요하면 새 운영 명령과 새
  Audit가 있어야 한다.
- 권한 확인, Order/version 조회, Refund와 PAYMENT step 재개, 운영 명령 멱등 레코드와
  `CUSTOMER_CANCELLATION_REFUND_RECONCILIATION_SCHEDULED` Audit는 한 local transaction에
  commit한다. Provider 호출은 이 transaction 밖의 기존 worker가 수행한다.
- Audit는 actor, Order target, 고정 reason, terminal version과 전후 상태만 기록한다.
  고객·금액·Provider 식별자, raw Idempotency-Key와 자유 입력 운영 사유는 Audit summary에
  복제하지 않는다.
- 멱등 key store는 actor+key unique, payload hash와 최초 응답 replay, 다른 payload의
  `409 IDEMPOTENCY_KEY_REUSED`, 90일 retention과 batch-100 cleanup을 사용한다.
- setup 손상은 ADR-051 detector로 내구 기록하고 reconciliation은 실행하지 않는다.
  권한·Audit·멱등성·DB 저장 실패는 성공으로 위장하지 않고 transaction을 rollback한다.

## Alternatives Considered

### 서로 다른 두 운영자 승인

- 금융 오조작과 권한 남용 방어가 강하다.
- Provider 결과를 읽기만 하는 단일 LOOKUP에도 제안·승인 지연과 proposal 상태가 필요하다.

### 별도 후속 계획으로 이연

- terminal Refund를 다시 여는 이번 변경이 없다.
- ADR-045/046의 지연 후 성공 알림이 실제 금융 경로 없이 남는다.

### 운영자 수기 성공 또는 새 REQUEST

- Provider 조회가 불가능해도 업무를 종결할 수 있다.
- 실제 성공을 추측하거나 중복 환불을 만들 수 있어 허용하지 않는다.

## Rationale

사용자는 단일 운영자 복구를 선택했다. LOOKUP-only, 전용 persistent grant, 원천 재검증,
멱등성과 append-only Audit를 결합하면 승인 대기 없이 실제 Provider 결과를 다시 확인하면서
새 금융 부수효과와 수기 성공을 차단할 수 있다. 자동 budget과 operator-authorized claim을
구분하면 기존 1 REQUEST+5 LOOKUP 경계를 왜곡하지 않는다.

## Consequences

- Operations에 새 권한, API, 90일 멱등 command store와 maintenance 대상이 추가된다.
- Payment Refund에 operator-authorized LOOKUP pending marker와 terminal 재개 전이가
  추가된다.
- 한 운영자가 terminal Refund를 다시 열 수 있으므로 grant bootstrap/revoke와 Audit alert가
  운영 통제의 핵심이다.
- API 성공은 환불 성공이 아니라 LOOKUP 예약을 뜻하며 `202 Accepted`를 반환한다.

## Failure Scenarios

- marker 없이 lookup count를 초기화하면 자동 retry 예산과 이력이 사라진다.
- 운영자 명령 transaction 안에서 Provider를 호출하면 장시간 lock과 불명 commit 경계가 생긴다.
- setup이 손상된 Refund를 열면 다른 source나 금액을 Provider 결과와 연결할 수 있다.
- Audit 저장 실패 뒤 Refund만 `UNKNOWN`이면 무감사 금융 복구가 된다.
- 같은 key의 다른 payload를 replay하면 다른 Order를 승인한 것처럼 보일 수 있다.
- LOOKUP 실패 뒤 자동 retry를 다시 시작하면 운영 명령 한 번이 무제한 복구로 확대된다.

## Verification

- active grant만 202, role-only/revoked grant는 403
- 같은 actor/key 응답 replay와 다른 payload 409
- `FAILED`/`MANUAL_REVIEW`만 LOOKUP 재개, 동시 요청 중 하나만 성공
- setup/source/amount/version mismatch에서 금융 상태 변화 0
- API transaction에서 Provider call 0, worker의 같은-key LOOKUP 정확히 1회와 REQUEST 0회
- LOOKUP 불명·실패의 terminal 지연 재수렴과 성공의 Refund/Payment/step/event 원자성
- 지연 뒤 성공 event와 서로 다른 두 NotificationDelivery
- Audit·멱등 저장 실패의 Refund/step rollback
- raw key, 운영 사유, 금융·Provider 식별자의 Audit/응답 부재
- 90일 retention, batch 100 상한과 재실행

## Metrics

- `beanflow.operations.customer_cancellation_refund_reconciliation.count{outcome}`
- `beanflow.payment.refund.attempts{reason,mode,outcome}`의 `mode=operator_lookup`
- `beanflow.operations.customer_cancellation_refund_reconciliation.retention.deleted`

Order, operator, customer, Payment, Refund와 Provider 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** 운영자 LOOKUP 요청부터 Provider 성공 확인까지의 실제 운영 latency

## Revisit Conditions

단일 운영자 오조작, 과도한 반복 LOOKUP 또는 Provider webhook/manual settlement API가
도입되면 2인 승인이나 provider-specific recovery workflow로 대체한다.

## Related Decisions

- BR-14, BR-27
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-022](ADR-022-audit-record.md)
- [ADR-037](ADR-037-customer-cancellation-refund-reconciliation-budget.md)
- [ADR-045](ADR-045-cancellation-refund-customer-notifications.md)
- [ADR-046](ADR-046-cancellation-refund-notification-events.md)
- [ADR-050](ADR-050-setup-incomplete-customer-projection.md)
- [ADR-051](ADR-051-setup-integrity-detection.md)
- [ADR-064](ADR-064-risk-based-idempotency-model-selection.md)
- [ADR-069](ADR-069-operator-permission-grants-and-audited-policy-read.md)
