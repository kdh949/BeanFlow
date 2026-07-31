# ADR-061: Refund 요청 금액과 성공 확정 금액 분리

- **Status:** Accepted
- **Date:** 2026-08-01

## Context

`POST /payments/{paymentId}/refunds`는 결과가 확정된 `201`과 결과 불명 또는
reconciliation 진행 중인 `202`에서 같은 `Refund` schema를 사용한다. 기존 schema는
모든 상태에 `cashRefundedKrw`와 `pointsRestoredKrw`를 필수로 요구해, 성공이 아닌
`REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`, `RECONCILING`, `FAILED`와
`MANUAL_REVIEW`에서 요청액, 실제 성공액, 0 또는 마지막 확인액 중 무엇을 반환하는지
정하지 못했다.

API convention과 ADR-009는 `202`와 결과 불명을 성공으로 표현하거나 확인되지 않은
금액을 0으로 대체하는 것을 금지한다. Plan 10이 공개 부분 환불 API를 구현하기 전에
요청 snapshot과 실제 성공 금액의 이름 및 상태별 존재 조건을 고정해야 한다.

현금 환불은 Payment가 외부 Provider 결과를 소유하고, 성공한 `PaymentRefunded` fact를
받은 Loyalty가 별도 transaction에서 포인트를 복원한다. 따라서 현금 환불과 포인트
복원은 서로 다른 시점에 완료될 수 있으며 하나의 Refund state로 두 성공을 묶을 수
없다.

## Decision

- `Refund`는 모든 상태에서 다음 immutable 요청 snapshot을 필수로 제공한다.
  - `cashRefundRequestedKrw`
  - `pointsRestorationRequestedKrw`
- 요청 snapshot은 Refund 생성 transaction에서 서버가 OrderLine allocation으로
  계산해 고정한다. Provider 응답, retry, lookup과 reconciliation은 이 값을 바꾸지
  않는다.
- 실제 성공 금액은 기존 완료형 이름으로 분리한다.
  - `cashRefundedKrw`
  - `pointsRestoredKrw`
- 기존 `Refund.state`는 Payment가 소유한 현금 Provider 요청·조회·reconciliation 상태를
  유지한다. `cashRefundedKrw`는 `Refund.state = SUCCEEDED`일 때만 존재하고
  `cashRefundRequestedKrw`와 같아야 한다.
- 포인트 복원은 별도 `pointsRestorationState`로 공개한다.

  | 상태 | 의미 |
  |---|---|
  | `NOT_REQUIRED` | `pointsRestorationRequestedKrw = 0`이라 owner 작업이 없음 |
  | `REQUESTED` | 요청 snapshot은 있으나 현금 성공 전이라 복원을 시작하지 않음 |
  | `PROCESSING` | 현금 성공 뒤 publication 또는 Loyalty owner 작업이 pending/retry 중 |
  | `SUCCEEDED` | Loyalty 원장에 해당 Refund source의 복원이 확정됨 |
  | `MANUAL_REVIEW` | publication/owner retry 소진 또는 source conflict로 자동 수렴 중단 |

- `pointsRestoredKrw`는 `pointsRestorationState = SUCCEEDED`일 때만 존재하고
  `pointsRestorationRequestedKrw`와 같아야 한다. 그 외에는 생략한다.
- 현금 `SUCCEEDED`와 포인트 `PROCESSING` 조합은 정상적인 비동기 중간 상태다. 현금이
  확정 실패하거나 아직 불명인 동안 포인트를 시작하지 않는다.
- Payment Refund와 Loyalty restoration의 내구 원천을 Query Service/DTO projection이
  조합한다. 쓰기 Aggregate 간 객체 연관관계나 상태 callback으로 두 owner를 합치지
  않는다.
- Loyalty projection 또는 필수 DB 조회가 실패하면 0, `PROCESSING`, cache와 stale 값으로
  대체하지 않고 Refund 조회/응답을 `503 DEPENDENCY_UNAVAILABLE`로 실패시킨다.
- `201`과 `202` 모두 두 요청 snapshot과 `pointsRestorationState`를 제공한다. 각 실제
  성공 금액의 존재 여부는 해당 owner 상태가 결정하며 HTTP status만으로 추정하지 않는다.
- OpenAPI 3.1 조건부 schema로 현금 `SUCCEEDED`와 포인트 `SUCCEEDED` 각각의 성공 필드
  존재 규칙을 독립 검증한다.
- 현재 공개 구현과 production client가 없는 pre-implementation 계약이므로 기존 두
  필드의 의미를 보존하기 위한 compatibility alias를 추가하지 않는다. production
  소비자가 생긴 뒤의 변경은 API versioning 정책을 따른다.

## Alternatives Considered

### 기존 두 필드를 요청 snapshot으로 이름만 변경

- 필드 수가 적고 모든 상태의 body가 단순하다.
- 실제 성공 금액을 응답에서 직접 구분할 수 없고 상태와 결합해 해석해야 한다.

### 성공 상태에서만 기존 두 필드 제공

- 완료형 이름과 `SUCCEEDED` 의미가 일치하고 변경량이 작다.
- `202`에서 서버가 확정한 요청 금액을 고객이 알 수 없다.

### 현금과 포인트가 모두 끝난 뒤 Refund를 SUCCEEDED로 전환

- 하나의 상태와 두 확정 금액 규칙은 단순하다.
- Payment 성공 상태가 Loyalty 완료에 종속되고, 포인트 지연 때문에 확인된 현금 성공도
  처리 중처럼 보이며 기존 `PaymentRefunded` event 경계를 바꾼다.

### 실제 포인트 결과는 PointTransaction API에서만 조회

- Context 소유권과 API가 단순하다.
- client가 Refund source와 PointTransaction을 별도 결합해야 해 한 환불의 전체 진행을
  확인하기 어렵다.

### 비확정 상태에서 완료 금액을 0으로 제공

- 고정 shape를 유지한다.
- 0원 요청과 결과 불명을 구분하지 못하고 실패를 확정된 값처럼 위장하므로 금지한다.

## Rationale

요청 의도와 확인된 부수효과는 서로 다른 사실이다. 두 축을 별도 필드로 표현하면
`202`에서도 예정 금액을 안정적으로 보여 주면서 성공이 확인되지 않은 금액을 실제
환불액으로 오인하지 않는다. 현금과 포인트의 owner 상태도 분리하면 기존 비동기 Context
경계를 보존하면서 부분 완료를 성공 또는 실패 하나로 뭉개지 않는다.

## Consequences

- Refund API DTO와 contract test에 요청 금액 두 필드와 포인트 복원 상태가 추가된다.
- client는 현금 `state`와 `pointsRestorationState`를 각각 확인한 뒤 성공 금액을 읽는다.
- Payment는 요청 snapshot과 현금 결과를, Loyalty는 포인트 복원 원장을 소유한다.
- Query Service는 두 내구 원천을 조합하며 한쪽 장애를 정상 projection으로 대체하지 않는다.

## Failure Scenarios

- `UNKNOWN`에 요청액을 `cashRefundedKrw`로 반환하면 Provider 성공을 확인한 것처럼
  보인다.
- 비확정 상태에 0을 반환하면 실제 0원 요청과 결과 불명을 구분할 수 없다.
- 현금 또는 포인트가 `SUCCEEDED`인데 해당 실제 금액이 요청 snapshot과 다르면
  allocation·Provider 결과 또는 원장 중 하나가 손상된 것이므로 성공 projection을
  계속하면 안 된다.
- 현금 성공 전에 포인트가 `PROCESSING` 또는 `SUCCEEDED`면 event ordering이나 source
  연결이 손상된 것이다.
- Loyalty 조회 실패를 `PROCESSING`으로 바꾸면 실제 manual review와 dependency 장애를
  구분할 수 없다.
- retry 중 요청 snapshot이 바뀌면 같은 idempotency key가 다른 금액의 부수효과를
  가리키게 된다.

## Verification

- 모든 Refund 상태에 두 요청 snapshot이 존재한다.
- 현금 `SUCCEEDED`에만 `cashRefundedKrw`가 존재한다.
- 포인트 `SUCCEEDED`에만 `pointsRestoredKrw`가 존재한다.
- 현금 성공·포인트 처리 중 조합이 두 상태와 조건부 금액으로 표현된다.
- 각 owner의 요청·성공 금액이 일치한다.
- 0원 포인트 요청은 `NOT_REQUIRED`이고 실제 복원액 필드는 생략된다.
- Loyalty projection 조회 실패는 503이며 body를 추정하지 않는다.

## Required Tests

- OpenAPI의 현금·포인트 독립 `SUCCEEDED` 성공 금액 필수 조건
- 각 비성공 owner 상태의 해당 성공 금액 포함 거부
- 현금 SUCCEEDED + 포인트 PROCESSING/SUCCEEDED/MANUAL_REVIEW 조합
- 현금 미성공 상태에서 포인트 PROCESSING/SUCCEEDED 거부
- 포인트 요청 0의 NOT_REQUIRED와 양수 요청의 NOT_REQUIRED 거부
- 201/202의 두 요청 snapshot과 포인트 상태 response contract
- 요청 snapshot 불변과 idempotent replay body 불변
- owner별 요청·성공 금액 mismatch에서 성공 projection 거부
- Loyalty projection dependency 실패의 503과 fallback 부재

## Metrics

- `beanflow.payment.refund.amount_mismatch.count{component}`
- `beanflow.payment.refund.points_projection.count{state,outcome}`

Payment, Refund, Order와 customer 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** 응답 필드 증가에 따른 payload 크기 영향

## Revisit Conditions

포인트 복원에 외부 Provider 또는 별도 reconciliation 상태가 추가되거나, production
client와 호환되는 Refund API version 변경이 필요할 때

## Related Decisions

- BR-12, BR-15
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-023](ADR-023-analytics-refund-and-late-events.md)
- [ADR-036](ADR-036-cancellation-after-partial-refund.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
