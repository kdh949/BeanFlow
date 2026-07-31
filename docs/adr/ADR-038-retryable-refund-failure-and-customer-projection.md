# ADR-038: 재시도 가능 환불 실패와 고객 상태 투영

- **Status:** Accepted
- **Date:** 2026-07-31
- **Amends:** ADR-033의 고객 환불 상태 투영과 ADR-037의 REQUEST 예산

## Context

현재 `GatewayRefundResult.Failed(code)`는 모든 Provider 명시 실패를 Refund `FAILED`로
종결하고 PAYMENT 보상 step을 `MANUAL_REVIEW`로 보낸다. 실제 Provider는 요청을
실행하지 않았음을 확정하면서도 rate limit이나 일시적 서비스 불가처럼 다시 요청할 수
있는 실패를 반환할 수 있다.

ADR-037은 결과가 불명확해진 요청을 절대 재전송하지 않고 조회 다섯 번으로 확인하도록
정한다. 그러나 명시적으로 부수효과가 없다고 확정된 실패까지 최초 요청 한 번으로
종결할지는 정하지 않았다. 자동 재요청을 허용하려면 Provider key 재사용 보장, 허용
오류 분류, 별도 요청·조회 예산과 중간 상태가 필요하다.

또한 ADR-033은 고객 `PaymentRecoverySummary.state`가 내부 Refund state를 그대로
통과한다고 정했다. 이 방식은 `UNKNOWN`, `RECONCILING`, `FAILED`,
`MANUAL_REVIEW`와 새 재시도 상태를 고객에게 노출한다. 고객에게는 자동 복구 구현
상세나 내부 실패 code가 아니라 환불 진행 여부와 장기 지연 안내만 보여야 한다.

## Decision

### Provider failure classification

- `GatewayRefundResult`는 최소한 `Succeeded`, `RetryableFailed`,
  `TerminalFailed`, `Unknown`을 구분한다.
- `RetryableFailed`는 Provider가 이번 호출의 환불 부수효과가 없고 **동일
  idempotency key 재실행이 안전함**을 계약으로 보장할 때만 반환할 수 있다.
- 재시도 허용 raw code 목록은 각 production Provider adapter 코드가 소유한다.
  설정이나 DB 정책으로 동적으로 확장하지 않는다.
- adapter allowlist에 없는 code, 같은-key 재실행 보장이 없는 code와 새로 관측된
  code는 fail-closed로 `TerminalFailed`가 된다.
- allowlist 변경은 코드 리뷰, adapter contract test와 배포를 거쳐야 한다.
- lookup 결과의 명시 실패는 해당 logical refund가 실행되지 않았다는 최종 확인이므로
  request allowlist와 무관하게 terminal이다.

### Request and lookup budgets

- 최초 `REQUEST`가 `RetryableFailed`이면 같은 Provider key로 10초 뒤 한 번,
  다시 같은 결과면 30초 뒤 한 번 더 재요청한다.
- 최초 요청을 포함한 `REQUEST` 예산은 3회다. 세 번째 요청도
  `RetryableFailed`이면 Refund를 `FAILED`로 종결한다.
- 어느 `REQUEST`에서든 결과가 `Unknown`이 되는 순간 REQUEST 경로를 영구 종료하고
  ADR-037의 별도 `LOOKUP` 5회 예산으로 전환한다.
- 따라서 최악의 Provider 상호작용은 REQUEST 3회와 LOOKUP 5회, 총 8회다.
- `REQUEST`와 `LOOKUP` 예산은 독립적이며 한쪽 시도가 다른 쪽 예산을 줄이지 않는다.
- Refund에는 내부 상태 `RETRY_SCHEDULED`를 추가한다. 다음 같은-key REQUEST가
  due인 동안만 사용한다.
- persistence는 `request_attempt_count`, `lookup_attempt_count`와 다음 action
  `REQUEST | LOOKUP`을 구분한다. 기존 전체 `attempt_count`를 유지하면 두 count의
  합과 일치하도록 CHECK 또는 guarded write로 보호한다.
- 마지막 REQUEST claim 뒤 worker가 결과 저장 전에 종료되면 claim lease 만료 후
  결과를 추측해 새 REQUEST를 보내지 않는다. 이미 외부 호출이 시작됐으므로 같은
  key `LOOKUP`으로 전환한다.

### Terminal failure and operations

- allowlist 밖 명시 실패 또는 REQUEST 예산 소진은 내부 Refund `FAILED`다.
- 실패 code는 정규화해 Refund에 보존하고 PAYMENT step과
  OrderCompensationCase를 `MANUAL_REVIEW`로 전환한다.
- 자동으로 새 Refund row, 새 Provider key 또는 추가 REQUEST를 만들지 않는다.
- 운영자 보상 조회는 실제 Refund 상태, PAYMENT step `MANUAL_REVIEW`,
  request·lookup·전체 attempt count와 마지막 실패 code를 제공한다.

### Customer projection

- 고객 `PaymentRecoverySummary.state`는 더 이상 내부 Refund enum을 그대로 통과하지
  않는다. Payment Context의 단일 projection에서 다음처럼 변환한다.

  | 내부 Refund 상태 | 고객 `state` | 고객 `noticeCode` |
  |---|---|---|
  | `REQUESTED` | `REQUESTED` | 없음 |
  | `PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`, `RECONCILING` | `PROCESSING` | 없음 |
  | `FAILED`, `MANUAL_REVIEW` | `PROCESSING` | `REFUND_DELAYED` |
  | `SUCCEEDED` | `SUCCEEDED` | 없음 |

- 요청액 0의 `NOT_REQUIRED` 의미는 유지한다. ADR-050에 따라 commit-gate 손상
  `SETUP_INCOMPLETE`는 운영자에게만 실제 상태를 노출하고 고객에게는
  `PROCESSING + REFUND_DELAYED`로 투영한다.
- `noticeCode`는 nullable/optional enum이며 초기 값은 `REFUND_DELAYED` 하나다.
- 고객 API와 로그에는 내부 재시도 mode, attempt count, 실패 code와
  `MANUAL_REVIEW`를 노출하지 않는다.
- 클라이언트는 `REFUND_DELAYED`일 때 정보 아이콘을 표시하고 locale별 고정 문구를
  사용한다. 한국어 기준 문구는
  “환불 처리가 지연되고 있습니다. 불편을 드려 죄송합니다. 최대한 빠르게
  처리하겠습니다.”다.

## Alternatives Considered

### 모든 명시 실패를 즉시 FAILED로 종결

- 현재 구현과 같고 외부 호출이 가장 적다.
- Provider가 안전한 재실행을 보장하는 일시 장애도 곧바로 운영자에게 넘어간다.

### 실패마다 새 Provider attempt key 발급

- 첫 실패를 cache하는 Provider에서도 새 요청을 실행할 수 있다.
- adapter가 실패를 잘못 분류하면 서로 다른 key의 두 환불이 모두 성공할 수 있다.

### Runtime 설정 또는 DB 정책으로 allowlist 관리

- 배포 없이 Provider code 변화에 대응할 수 있다.
- 오타·승인되지 않은 설정 변경이 금융 부수효과 재요청을 켜며 환경별 동작이 갈린다.

### 고객에게 내부 Refund state 직접 노출

- 서버 변환이 없고 운영 상태를 고객도 자세히 볼 수 있다.
- 재시도와 수동 검토 같은 내부 복구 방식을 노출하고 실패 code를 설명할 별도 UX가
  필요하다.

### 고객 응답에 안내 문자열 직접 반환

- 클라이언트가 그대로 표시할 수 있다.
- 번역과 카피 변경이 API payload와 서버 배포에 결합된다.

## Rationale

명시적으로 부수효과가 없고 같은 key 재실행까지 보장된 경우에만 재요청하면 결과 불명
요청을 중복 실행하지 않으면서 일시 장애를 자동 복구할 수 있다. allowlist를 adapter
코드에 두면 Provider별 의미 번역을 계약 테스트와 함께 검토할 수 있다.

고객에게는 내부 retry 상태보다 환불이 아직 진행 중인지, 평소보다 지연되어 안내가
필요한지가 중요하다. 내부 원장을 축약하지 않고 presentation projection만 단순화하면
운영 관측성과 고객 경험을 동시에 유지할 수 있다.

## Consequences

- Refund state와 DB CHECK에 `RETRY_SCHEDULED`를 추가해야 한다.
- `provider_request_started_at != null`만으로 모든 후속 claim을 LOOKUP으로 정하는 현재
  구현을 request/lookup count와 next action 기반으로 변경해야 한다.
- 전체 attempt 상한 하나로 처리하던 Refund domain API를 두 독립 예산으로 바꿔야
  한다.
- ADR-037의 “최초 REQUEST 1회”는 결과 불명 경로에 한정된다. 안전한 명시 실패는 이
  ADR의 예외로 최대 두 번 재요청할 수 있다.
- ADR-033의 내부 Refund state 직접 통과 규칙은 이 ADR의 고객 projection으로
  개정된다. 고객 state enum에서 `FAILED`, `UNKNOWN`, `RECONCILING`,
  `MANUAL_REVIEW`를 제거하고 `noticeCode`를 추가한다.
- 운영자용 `CompensationSummary`와 Payment 상세 조회는 내부 상태를 축약하지 않는다.

## Failure Scenarios

- allowlist 밖 새 code를 retryable로 기본 처리하면 Provider 계약 변경이 자동
  재요청을 활성화한다.
- 결과 불명 뒤 REQUEST로 돌아가면 이미 성공한 환불을 중복 요청할 수 있다.
- request와 lookup count를 하나로 합치면 명시 실패 재시도가 lookup 5회 예산을
  소모하거나 총 8회 상한을 넘길 수 있다.
- 마지막 REQUEST claim crash를 `RetryableFailed`로 추측하면 실제 성공한 호출을 다시
  보낼 수 있다.
- 내부 `FAILED`를 고객에게 그대로 반환하면 합의한 지연 안내 대신 확정 실패처럼
  보이며 운영 후속 처리 가능성을 숨긴다.
- 고객 projection을 여러 controller에서 중복 구현하면 취소 응답과 Order 조회가 서로
  다른 state 또는 notice를 반환할 수 있다.

## Verification

- allowlist code와 같은-key 보장 조합만 `RetryableFailed`로 변환된다.
- 미등록·보장 없는 code는 `TerminalFailed`로 fail-closed 처리된다.
- REQUEST가 최초, 10초, 30초에 최대 세 번만 실행된다.
- Unknown 이후 REQUEST가 다시 실행되지 않고 LOOKUP 예산 5회가 온전히 남는다.
- claim crash가 새 REQUEST를 만들지 않는다.
- 내부 transient·terminal 상태별 고객 state와 notice projection이 표와 일치한다.
- 운영자 조회에는 내부 상태·attempt·실패 code가 손실 없이 보인다.

## Required Tests

- Provider adapter별 retry allowlist contract test
- 미등록 code의 terminal fail-closed
- 동일 key REQUEST 3회와 10초·30초 schedule
- 세 번째 retryable failure의 Refund FAILED와 PAYMENT MANUAL_REVIEW
- 첫·둘째 REQUEST Unknown 뒤 LOOKUP 5회 전환
- REQUEST와 LOOKUP count·전체 count tie-out
- request claim crash 뒤 lookup 전환과 REQUEST 재전송 부재
- 취소 응답과 `GET /orders/{orderId}`의 동일 고객 projection
- 내부 각 Refund state의 고객 state·notice 매핑
- 고객 응답과 log의 attempt·failure code·manual review 부재
- 운영자 조회의 실제 state·두 attempt count·마지막 실패 code
- `REFUND_DELAYED` 정보 아이콘과 locale copy client contract

## Metrics

- `beanflow.payment.refund.attempts{reason,provider,mode,outcome}`
- `beanflow.payment.refund.retry_scheduled.count{reason,provider}`
- `beanflow.payment.refund.terminal_failure.count{reason,provider}`
- `beanflow.payment.refund.customer_delayed.count`

Provider tag는 닫힌 저카디널리티 adapter 이름만 사용한다. Payment, Order, customer ID,
raw Provider code, Provider reference와 idempotency key는 metric tag로 사용하지 않는다.

- **Not measured:** retryable failure 자동 복구율과 고객 지연 안내 노출 시간

## Revisit Conditions

Provider가 같은-key 실패 재실행 보장을 철회하거나, webhook 기반 결과 확정이
도입되거나, 자동 재요청이 운영자 처리보다 오류율·지연을 악화한다고 측정될 때

## Related Decisions

- BR-14
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-031](ADR-031-customer-cancellation-api-contract.md)
- [ADR-033](ADR-033-order-compensation-case-generalization.md)
- [ADR-036](ADR-036-cancellation-after-partial-refund.md)
- [ADR-037](ADR-037-customer-cancellation-refund-reconciliation-budget.md)
