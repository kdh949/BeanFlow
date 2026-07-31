# ADR-037: 고객 취소 환불의 요청·조회 예산

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

고객 취소 Refund는 외부 Provider 호출 전에 `REQUESTED`로 내구 저장되고 worker가
transaction 밖에서 처리한다. 요청 timeout, 응답 유실 또는 claim 직후 프로세스 종료는
Provider 부수효과가 이미 발생했을 수 있으므로 같은 환불 요청을 다시 보내면 안 된다.

기존 환불 운영 문서는 불명 결과를 10초, 30초, 2분, 5분, 15분 간격으로 최대 다섯 번
조회한다고 규정한다. 그러나 현재 `RejectionRefundService.MAX_ATTEMPTS = 5`는 최초
`REQUEST`도 attempt에 포함한다. 따라서 실제로는 최초 요청 1회와 `LOOKUP` 4회만
가능하고 다섯 번째 `15분` delay는 사용되지 않는다. 고객 취소 환불을 구현하기 전에
요청과 조회의 정확한 예산을 하나로 맞춰야 한다.

## Decision

- 결과 불명 경로의 Provider 작업 예산은 마지막 `REQUEST` 1회와 그 결과가 불명확할
  때의 `LOOKUP` 최대 5회다. 이 경로의 Provider 상호작용은 최대 6회다.
- 최초 `REQUEST`가 시작됐다는 사실은 외부 호출 전에 `provider_request_started_at`으로
  commit한다.
- 요청의 timeout, transport failure, 응답 유실 또는 claim 중단 뒤에는 같은
  Provider idempotency key로 상태를 조회할 뿐 `REQUEST`를 다시 보내지 않는다.
- 다섯 `LOOKUP`은 앞선 불명 결과 뒤 각각 10초, 30초, 2분, 5분, 15분에 due가 된다.
  다섯 번째 조회도 불명이면 Refund를 `MANUAL_REVIEW`로 전환한다.
- ADR-038이 허용하는 명시적 무부수효과 실패의 같은-key REQUEST 재시도는 별도
  `request_attempt_count` 예산을 사용한다. 어느 요청이든 불명 결과가 발생한 뒤에는
  이 ADR의 `lookup_attempt_count` 5회 예산으로 비가역 전환한다.
- 마지막 허용 claim 뒤 프로세스가 결과를 저장하지 못하면 claim lease 만료 후 새
  Provider 호출 없이 `MANUAL_REVIEW`로 종결한다.
- 재조회마다 Refund source reference와 Provider idempotency key를 바꾸지 않는다.
- 이 예산은 고객 취소 Refund와 기존 매장 거절 Refund에 동일하게 적용한다. 서로 다른
  retry budget을 운영하지 않는다.
- 현재 코드의 단일 `MAX_ATTEMPTS = 5`와 사용되지 않는 마지막 delay는 구현 결함이다.
  구현 시 request와 lookup 예산을 분리하고 다섯 lookup delay가 모두 도달 가능한지
  테스트한다.

## Alternatives Considered

### 최초 요청 포함 총 5회 유지

- 현재 구현과 가장 가깝고 마지막 `15분` 조회를 제거하면 된다.
- 운영 문서가 약속한 다섯 조회를 네 조회로 줄이며, 결과가 늦게 확정되는 금융 작업을
  자동으로 복구할 기회를 줄인다.

### 시간 경과만으로 무제한 조회

- Provider가 늦게 확정하는 결과를 계속 자동 확인할 수 있다.
- 장애나 Provider 데이터 결함이 영구 worker 부하와 무기한 불명 상태로 남고 수동
  책임 전환 시점이 없다.

## Rationale

외부 환불은 중복 요청보다 조회 비용이 작고, 이미 문서화된 다섯 간격은 유한한 약
22분 40초의 자동 확인 창을 제공한다. 최초 요청과 조회 예산을 분리해 표현하면
`15분` delay가 죽은 설정으로 남는 오류를 막고 운영자가 attempt 수를 오해하지 않는다.

## Consequences

- Refund domain은 request와 lookup attempt count를 별도로 보존해야 한다.
- retry delay 배열의 다섯 값이 순서대로 모두 사용 가능해야 한다.
- metric과 운영 화면은 `mode = REQUEST | LOOKUP`을 구분해야 한다.
- 기존 매장 거절 Refund도 같은 수정과 회귀 검증 대상이다.
- 다섯 번째 lookup까지의 누적 대기 시간은 22분 40초이며 Provider 호출 시간과 claim
  lease 대기는 별도다.

## Failure Scenarios

- 상한 5를 유지하면 네 번째 lookup 뒤 곧바로 수동 검토가 되어 15분 delay가 절대
  실행되지 않는다.
- timeout 뒤 `REQUEST`를 재전송하면 Provider idempotency 보장이 약하거나 key 적용이
  잘못된 경우 중복 환불이 발생할 수 있다.
- 마지막 claim lease 만료 뒤 여섯 번째 lookup을 보내면 합의된 예산을 초과한다.
- `attempt_count`를 lookup 횟수로 표시하면 최초 요청을 포함한 저장 값과 운영 판단이
  하나씩 어긋난다.

## Verification

- 명시적 요청 성공은 추가 lookup 없이 terminal 상태가 된다. 명시 실패는 ADR-038의
  retryable/terminal 분류를 따른다.
- 요청 결과 불명 뒤 동일 key lookup이 정확히 최대 다섯 번 수행된다.
- delay가 10초, 30초, 2분, 5분, 15분 순서로 모두 사용된다.
- 여섯 번째 Provider 상호작용 뒤에도 불명이면 `MANUAL_REVIEW`가 된다.
- 마지막 claim 직후 종료하면 lease 만료 뒤 추가 Provider 호출 없이
  `MANUAL_REVIEW`가 된다.
- 매장 거절과 고객 취소 Refund가 같은 예산을 사용한다.

## Required Tests

- 최초 REQUEST 1회와 LOOKUP 5회의 mode·attempt count
- 각 불명 결과의 next attempt schedule
- timeout 뒤 REQUEST 재전송 부재
- worker crash 뒤 lease 만료 lookup 전환
- 마지막 claim crash 뒤 추가 lookup 부재와 manual review
- Provider key와 Refund source reference의 전 시도 불변
- 기존 매장 거절 Refund의 동일 예산 회귀

## Metrics

- `beanflow.payment.refund.attempts{reason,mode,outcome}`
- `beanflow.payment.refund.unknown.count{reason}`
- `beanflow.payment.refund.manual_review.count{reason,cause}`

Payment, Order, customer ID, Provider reference와 idempotency key는 metric tag로 사용하지
않는다.

- **Not measured:** Provider 결과 확정 지연 분포와 다섯 번째 lookup의 자동 복구 기여도

## Revisit Conditions

Provider가 webhook 또는 장기 조회 SLA를 제공하거나, 측정된 결과 확정 지연 때문에
자동 확인 창을 늘리거나 줄여야 할 때

## Related Decisions

- BR-14
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
- [ADR-036](ADR-036-cancellation-after-partial-refund.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
