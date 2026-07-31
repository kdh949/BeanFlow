# ADR-052: 고객 취소 Refund 누락의 제한적 안전 복구

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-051은 고객 취소 Refund·recovery snapshot의 누락과 불일치를
`PAYMENT_CANCELLATION_SETUP` ReprocessingCase로 탐지한다. 모든 손상을 현재
Payment·Refund 합계에서 재구성하면 과거 취소 시점의 선행 환불액, 요청액과 source를
추측하게 되어 이중 환불 위험이 있다.

반면 recovery snapshot이 완전하고 그 안의 Refund ID, 요청액, source와 Provider key가
고정돼 있는데 Refund row만 없으면 복구에 필요한 immutable 입력이 남아 있다. 다만
row가 과거 worker 실행 뒤 삭제됐을 가능성을 완전히 배제할 수 없으므로 곧바로 외부
REQUEST를 다시 보내는 것도 안전하지 않다.

## Decision

### Supported repair

- `PLATFORM_OPERATOR`의 application-level setup 복구는 **완전한 recovery snapshot이
  있고 고객 취소 Refund row만 누락된 경우**로 제한한다.
- 복구 전 Order와 Payment를 기존 전역 순서 `Order → Payment`로 잠그고 다음 조건을
  모두 다시 검증한다.
  - Order가 같은 aggregate version의
    `CANCELLED + CUSTOMER_REQUEST`
  - 외부 Payment가 승인됐고 snapshot의 `paymentId`와 일치
  - snapshot의 승인액, 취소 전 성공 환불액과 요청액 tie-out이 유효
  - `cancellationRequestedRefundAmountKrw > 0`
  - snapshot의 `cancellationRefundId`, Refund source와 Provider key가 모두 존재
  - 해당 Refund ID, source와 Provider key를 점유한 다른 row가 없음
  - 다른 unresolved Refund가 없음
  - setup ReprocessingCase가 같은 Order terminal version으로 `OPEN`
- 하나라도 맞지 않으면 `409 REPROCESSING_NOT_SAFE`로 거부하고 아무 row나 외부 작업을
  만들지 않는다.
- 운영자 request body는 non-blank 수동 사유만 받는다. 금액, Refund ID, source,
  Provider key, reason code와 attempt 값을 입력받지 않는다.
- 통과하면 snapshot의 원 `cancellationRefundId`로 Refund row를 복원한다.
  - reason: `CUSTOMER_ORDER_CANCELLED`
  - amount: snapshot의 cancellation requested amount
  - source/provider key: snapshot의 immutable 값
  - request/lookup attempt: 0/0
- row 저장 직후 외부 REQUEST를 실행하지 않는다. 과거 호출 여부가 불명확하므로 내부
  상태를 `RECONCILING`, next action을 `LOOKUP`으로 두고 같은 Provider key 조회부터
  시작한다.
- repair transaction은 Refund, PAYMENT step `UNKNOWN`, setup case resolution과
  AuditRecord를 함께 commit한다. Provider LOOKUP은 commit 뒤 worker가 transaction
  밖에서 실행한다.
- setup case는 `RESOLVED`와
  `MISSING_REFUND_RECREATED_LOOKUP_REQUIRED` resolution을 기록한다. 이후 Provider
  결과 복구는 Refund와 PAYMENT step의 기존 reconciliation 상태에서 추적한다.
- AuditRecord는 operator actor, action
  `PAYMENT_CANCELLATION_MISSING_REFUND_RECREATED`, target Refund, non-blank reason,
  source reference와 before/after state summary를 기록한다. customer ID, 자유 입력
  취소 detail, Provider reference와 client key는 summary에 넣지 않는다.
- 같은 reprocessing Idempotency-Key와 같은 payload는 최초 결과를 재생한다. 다른
  payload는 409이며, source/Refund/provider key Unique Constraint가 최종 중복
  방어선이다.

### Unsupported repair

- 다음 상태는 application API로 자동·수동 재구성하지 않는다.
  - recovery snapshot 누락
  - snapshot의 Refund ID/source/Provider key 누락
  - source 또는 Order aggregate version 불일치
  - 승인·선행 성공·요청액 tie-out 불일치
  - 기존 Refund ID/source/provider key 충돌
  - 다른 unresolved Refund 존재
- unsupported case는 `OPEN`을 유지하고 `REQUIRES_ENGINEERING_REMEDIATION`을
  표시한다. 현재 값으로 snapshot을 채우거나 기존 Refund를 덮어쓰지 않는다.
- 직접 DB 수정 절차는 이번 Feature 범위 밖이며 별도 승인된 break-glass runbook과
  Audit 정책 없이는 허용하지 않는다.
- ADR-053에 따라 서로 다른 두 PLATFORM_OPERATOR의 30분 내 제안·승인과 승인 시점
  전체 guard 재검증을 거쳐 실행한다.

## Alternatives Considered

### 모든 손상을 현재 데이터로 재구성

- 운영 UI에서 더 많은 case를 닫을 수 있다.
- 취소 시점 snapshot과 Provider 호출 이력이 없는 상태에서 금액·source를 추측한다.

### application 복구 없음

- 잘못된 자동 환불 위험이 가장 낮다.
- immutable 입력이 완전한 단순 row 누락도 배포나 직접 DB 조치가 필요하다.

### 복원 후 즉시 같은-key REQUEST

- Provider 결과를 빠르게 얻을 수 있다.
- row 삭제 전에 이미 호출됐을 가능성을 무시하므로 결과 불명 시 재요청 금지 원칙과
  충돌한다.

## Rationale

복구 입력이 이미 immutable snapshot으로 완전히 존재하는 경우만 row를 재생성하면
운영자가 금융 값을 선택하지 않는다. 외부 첫 동작을 LOOKUP으로 고정하면 과거 호출
가능성에도 같은 Provider key 결과로 안전하게 수렴한다.

## Consequences

- recovery snapshot은 Refund ID뿐 아니라 source와 Provider key도 내구 보존해야 한다.
- setup repair 뒤 case는 닫히지만 PAYMENT step과 고객 환불은 reconciliation이 끝날
  때까지 진행 중이다.
- snapshot 자체 손상은 application에서 해결하지 못하고 engineering remediation으로
  남는다.

## Failure Scenarios

- operator가 amount를 입력하면 잘못된 금액을 환불할 수 있다.
- 누락 row 복원 직후 REQUEST를 보내면 과거 성공 호출과 중복될 수 있다.
- case를 닫고 Refund commit이 실패하면 손상은 남았지만 해결로 보인다.
- Refund를 만들고 Audit 저장을 실패 처리하지 않으면 누가 복구했는지 증적이 없다.
- 불일치 snapshot을 최신 Payment 값으로 갱신하면 취소 당시 사실이 사라진다.

## Verification

- 완전한 snapshot+Refund 누락 조합만 repair 성공
- operator 입력이 reason으로 제한됨
- 원 ID/source/key/amount의 정확한 복원
- 외부 첫 동작 LOOKUP과 transaction 안 Provider 호출 0회
- Refund·step·case·Audit의 원자적 commit/rollback
- unsupported issue의 무변경 409

## Required Tests

- 각 safe guard의 단독 위반과 전체 성공
- 같은/다른 Idempotency-Key replay와 payload conflict
- 동시 operator repair의 Refund 한 건
- Refund/Audit/case 저장 실패 주입의 전체 rollback
- Provider 호출 이력이 없거나 불명인 두 경우 모두 LOOKUP 우선
- LOOKUP success/failure/unknown의 기존 recovery 전이
- snapshot 누락·tie-out/source conflict의 REQUIRES_ENGINEERING_REMEDIATION
- request DTO의 금액·ID·key 필드 부재 계약

## Metrics

- `beanflow.operations.payment_setup.repair.count{outcome,reason}`
- `beanflow.operations.payment_setup.repair.lookup.count{outcome}`

Operator, Order, Payment, Refund와 Provider 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** engineering remediation 소요시간

## Revisit Conditions

Provider가 과거 key LOOKUP 보존 기한을 보장하지 않거나 snapshot 재구성을 증명할 별도
append-only 원장이 도입될 때

## Related Decisions

- BR-14, BR-25, BR-30
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-022](ADR-022-audit-record.md)
- [ADR-036](ADR-036-cancellation-after-partial-refund.md)
- [ADR-037](ADR-037-customer-cancellation-refund-reconciliation-budget.md)
- [ADR-051](ADR-051-setup-integrity-detection.md)
