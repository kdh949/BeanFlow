# ADR-050: 환불 준비 손상의 고객 지연 투영과 운영 노출

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

Tx C1은 환불 요청액이 양수인 고객 취소에서 Payment recovery snapshot과 고객 취소
Refund를 Order 취소와 함께 원자적으로 저장한다. 둘 중 하나가 없으면 정상 business
state가 아니라 commit-gate 또는 데이터 무결성 손상인 `SETUP_INCOMPLETE`다.

기존 OpenAPI는 이 내부 용어를 고객 `PaymentRecoverySummary.state` enum에 직접
노출한다. ADR-038은 자동 복구와 수동 검토 상세를 고객에게 숨기면서도
`SETUP_INCOMPLETE`의 고객 표시만 감사·운영 결정으로 보류했다. 고객 조회 전체를
503으로 실패시키면 이미 확정된 취소 사실과 정상 주문 필드까지 볼 수 없다. 반대로
없는 snapshot 금액을 0이나 현재 데이터의 추정값으로 채우면 손상을 정상값으로
위장한다.

## Decision

- `SETUP_INCOMPLETE`는 고객 API enum에서 제거하고 내부 운영 상태로만 유지한다.
- 고객에게는 `state = PROCESSING`, `noticeCode = REFUND_DELAYED`로 투영한다.
- 고객 조회 전체를 `503`으로 실패시키지 않는다. Order `CANCELLED`, 취소 시각과
  reason code 등 독립적으로 확인 가능한 필드는 정상 반환한다.
- PaymentRecoverySummary의 금액은 recovery snapshot과 Refund 원천에서 검증 가능한
  값만 반환한다.
  - snapshot은 완전하지만 Refund만 누락된 경우에는 snapshot 금액과 검증 가능한
    현재 잔액을 반환할 수 있다.
  - snapshot 자체가 없거나 tie-out이 깨졌으면 금액을 0, Order 현재값 또는 Payment
    현재값으로 추정하지 않고 네 금액 필드를 생략한다.
- 따라서 고객 schema는 `state`만 항상 required로 두고 네 금액 필드는 정상 setup에서
  required인 조건부 계약으로 문서화한다. `lastUpdatedAt`도 확인 가능한 원천 시각이
  있을 때만 반환한다.
- 운영자 전용 `OperatorCompensationView`에는 공용 `CompensationSummary`와 optional
  `paymentSetupIssue`, setup ReprocessingCase ID를 제공한다. 문제가 있을 때 다음
  정보를 반환한다.

  | 필드 | 의미 |
  |---|---|
  | `state` | `SETUP_INCOMPLETE` |
  | `missingArtifacts` | `CANCELLATION_REFUND`, `PAYMENT_RECOVERY_SNAPSHOT` 중 누락 |
  | `invariantViolations` | `SOURCE_MISMATCH`, `AMOUNT_TIE_OUT_MISMATCH` 중 확인된 위반 |
  | `detectedAt` | 최초 내구 감지 시각 |
  | `lastErrorCode` | 정규화된 운영 오류 code |

- 고객 응답에는 `paymentSetupIssue`, 누락 artifact, invariant violation과 내부 오류
  code를 넣지 않는다.
- 손상은 ADR-051의 즉시 detector와 1분 bounded scanner가 찾고, 고우선순위 운영
  alert, source당 하나의 `PAYMENT_CANCELLATION_SETUP` ReprocessingCase와
  AuditRecord로 수렴한다. ADR-052에 따라 완전한 snapshot과 Refund row 누락 조합만
  application-level 제한 복구를 허용한다.
- `SETUP_INCOMPLETE`를 `NOT_REQUIRED`, `SUCCEEDED` 또는 금액 0으로 fallback하지
  않는다.
- `BENEFIT_ONLY`의 정상 snapshot 0/0/0, null Refund ID는
  `NOT_REQUIRED`이며 setup issue가 아니다.
- customer projection의 `REFUND_DELAYED` 문구는 ADR-038과 같은 고정 문구를
  사용한다.

## Alternatives Considered

### 고객에게 SETUP_INCOMPLETE 직접 노출

- 내부와 고객 상태 이름이 같다.
- 복구 행동이 없는 구현 용어가 공개 계약과 클라이언트 UX에 고정된다.

### 고객 조회 전체 503

- 손상을 강하게 드러낸다.
- 이미 확정된 취소와 정상 주문 정보까지 숨기며 retry하는 고객에게 복구 시점을
  제공하지 못한다.

### 금액을 현재 원장에서 재구성

- 기존 required 응답 shape를 유지할 수 있다.
- immutable cancellation snapshot이 없는 상황에서 값을 추측해 손상을 가린다.

## Rationale

고객에게 중요한 사실은 환불이 완료되지 않았고 지연 중이라는 점이다. 내부 무결성
손상은 운영자에게 정확히 노출하되 고객에게는 기존 지연 UX로 축약하는 것이 적절하다.
확인할 수 없는 금액을 생략하면 성공·0원으로 위장하지 않으면서 취소 조회 전체는
유지할 수 있다.

## Consequences

- OpenAPI 고객 enum에서 `SETUP_INCOMPLETE`가 제거되고 금액 필드는 조건부가 된다.
- 운영자 보상 조회에 setup issue detail이 추가된다.
- 클라이언트는 지연 상태에서 금액 필드가 없을 수 있음을 처리해야 한다.
- setup 손상은 일반 Refund 지연보다 높은 운영 우선순위를 갖지만 고객 문구는 같다.

## Failure Scenarios

- 금액 0을 반환하면 고객은 환불이 필요 없다고 오인한다.
- 현재 Payment 승인액만으로 네 snapshot 금액을 재구성하면 선행 부분 환불 경계를
  잘못 표현할 수 있다.
- 고객에게 누락 artifact 이름을 노출하면 내부 schema가 공개 계약이 된다.
- 운영 alert 없이 고객 projection만 축약하면 심각한 commit-gate 손상이 장기
  방치된다.
- 정상 BENEFIT_ONLY를 setup 손상으로 분류하면 불필요한 지연 안내와 운영 case가
  생긴다.

## Verification

- 내부 setup 손상의 고객 PROCESSING+REFUND_DELAYED 투영
- 고객 enum과 payload의 내부 detail 부재
- snapshot 부재 시 네 금액의 0 fallback·추정 부재
- 운영자 issue detail과 고우선순위 alert/ReprocessingCase
- 정상 BENEFIT_ONLY와 실제 setup 손상 구분

## Required Tests

- Refund만 누락, snapshot만 누락, 둘 다 누락
- source mismatch와 amount tie-out mismatch
- 손상별 고객 state/notice와 조건부 금액 필드
- 고객 조회에서 Order 취소 사실 유지
- 운영자 조회의 missing/violation enum
- setup ReprocessingCase source unique
- 정상 0원 취소의 NOT_REQUIRED와 네 0원 금액
- 고객 DTO/log의 내부 오류·artifact 정보 부재

## Metrics

- `beanflow.payment.cancellation.setup_incomplete.count{reason}`
- `beanflow.payment.cancellation.setup_incomplete.oldest_age.seconds`
- `beanflow.payment.cancellation.customer_projection.count{state,notice_code}`

Order, Payment, Refund와 Customer 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** 고객이 지연 안내를 본 뒤 문의한 비율

## Revisit Conditions

공개 고객 지원 case ID나 손상 전용 보상 SLA를 제공할 때

## Related Decisions

- BR-14, BR-27, BR-30
- [ADR-031](ADR-031-customer-cancellation-api-contract.md)
- [ADR-033](ADR-033-order-compensation-case-generalization.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
- [ADR-036](ADR-036-cancellation-after-partial-refund.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
- [ADR-039](ADR-039-benefit-only-cancellation-payment-step.md)
