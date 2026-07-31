# ADR-057: 멱등 명령 응답의 replay 표시 제거

- **Status:** Accepted
- **Date:** 2026-07-31
- **Amends:** ADR-032와 기존 매장 전이 응답의 replay 표시 계약

## Context

고객 취소와 주문 생성은 같은 key·payload 재요청에 저장된 최초 HTTP status/body를
그대로 반환하고 business response에 `replayed`를 넣지 않는다. 반면 매장 주문 전이
응답 `StoreOrderTransitionResult`는 required `replayed` boolean을 갖는다. 이 필드는
재생 호출마다 바뀌는 transport metadata라 저장된 최초 body 불변 원칙과 다르며
클라이언트가 같은 business 결과를 호출 이력에 따라 다르게 처리하게 만들 수 있다.

Payment 승인·환불의 `UNKNOWN`은 별도 문제다. 같은 key 재요청이 새 Provider
부수효과를 만들지 않으면서 reconciliation의 현재 202/200/422 representation을
반환하도록 이미 확정돼 있다. 이를 terminal response replay와 같게 만들면 최초 202가
영구 고정된다.

## Decision

- 모든 business response schema에서 `replayed` 필드를 제거한다.
- 매장 전이 응답은 `StoreOrderTransitionResult` wrapper를 제거하고
  `StoreOrderResult`를 직접 반환한다.
- terminal 명령 record는 같은 key·payload 재요청에 저장된 최초 HTTP status와 body를
  byte-equivalent 의미로 그대로 반환한다. replay 호출을 표시하기 위해 body를
  덧붙이거나 바꾸지 않는다.
- 이 규칙은 고객 취소 200/202, 매장 전이 200/202, 주문 생성 terminal 201/4xx/503,
  정책 version 생성과 repair proposal/decision처럼 해당 command 실행 자체가
  terminal인 결과에 적용한다.
- 외부 결과가 아직 non-terminal인 Payment 승인·환불 `UNKNOWN`은 예외다. 같은
  key·payload 재요청이 Provider 호출을 다시 만들지 않고 현재 durable
  representation을 반환한다. 상태가 terminal이 되면 이후 그 terminal 결과를
  반환한다.
- `UNKNOWN` 예외에도 `replayed` 필드를 넣지 않는다. “현재 상태 조회”와 “호출이
  replay였는가”를 business payload에서 합성하지 않는다.
- HTTP response header에도 replay indicator를 추가하지 않는다. 클라이언트는 replay
  여부와 무관하게 같은 명령 결과를 동일하게 처리한다.
- 서버는 replay 여부를 다음 내부 원천에서만 관측한다.
  - IdempotencyRecord hit/miss와 stored/current result mode
  - `outcome=REPLAY` structured log
  - operation·status class별 replay metric
- log와 metric에는 raw Idempotency-Key, actor/customer, Order와 Payment 식별자를
  넣지 않는다.
- 기존 매장 응답은 pre-release 계약이므로 V2 response나 compatibility field 없이
  OpenAPI, controller DTO, 저장 response fixture와 contract test를 같은 변경에서
  갱신한다.

## Alternatives Considered

### 모든 응답에 replayed 추가

- 클라이언트가 호출별 replay 여부를 즉시 안다.
- 재생 때 저장 body를 동적으로 수정하고 모든 schema가 transport concern을 갖는다.

### 현재 operation별 비대칭 유지

- 기존 매장 DTO를 바꾸지 않는다.
- 멱등 응답의 의미와 테스트가 operation마다 달라진다.

### UNKNOWN도 최초 202 영구 재생

- 모든 POST response가 완전히 불변이다.
- reconciliation 성공·실패 뒤에도 같은 명령으로 결과를 확인할 수 없어 별도 GET
  계약이 필요하다.

## Rationale

replay는 서버의 요청 처리 경로이지 business 결과의 속성이 아니다. terminal 결과는
불변 재생하고 외부 불명 결과만 현재 durable 상태를 반환하면 client payload를
단순하게 유지하면서 기존 reconciliation 의미도 보존한다.

## Consequences

- OpenAPI에서 `StoreOrderTransitionResult`와 `replayed`가 제거된다.
- replay 관측은 운영 metric/log에만 존재한다.
- Payment UNKNOWN과 terminal replay의 차이는 API conventions에 명시해야 한다.

## Failure Scenarios

- replay 때 body만 바꾸면 저장 response hash/fixture와 실제 응답이 갈린다.
- UNKNOWN에 최초 202를 고정하면 완료된 환불을 계속 진행 중으로 표시한다.
- raw key를 replay log에 남기면 고객 제공 token이 로그로 확산된다.
- 클라이언트가 replay=false일 때만 side effect를 처리하면 네트워크 재시도에 따라
  동작이 달라진다.

## Verification

- 모든 OpenAPI response schema의 replayed 필드 부재
- terminal command same-key replay의 최초 status/body 불변
- Payment UNKNOWN same-key의 Provider 호출 0회와 현재 상태 반환
- operation별 replay metric/log와 민감 field 부재
- pre-release store response fixture 일괄 변경

## Required Tests

- 매장 수락·거절 최초/replay response equality
- 고객 취소 PENDING/PAID 최초/replay equality
- 주문 생성 terminal success/failure replay
- Payment approval/refund UNKNOWN→terminal 전이 중 same-key 응답
- replay structured log/metric과 raw key·ID 부재
- DTO serialization에서 replayed property 부재

## Metrics

- `beanflow.idempotency.request.count{operation,outcome,status_class}`

Raw key, actor와 aggregate 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** replay 응답 비율

## Revisit Conditions

API gateway가 표준화된 비즈니스 payload 밖의 replay tracing을 제공하거나 외부 계약이
명시적 replay indicator를 요구할 때

## Related Decisions

- BR-25
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-025](ADR-025-order-creation-idempotency-transaction.md)
- [ADR-032](ADR-032-customer-cancellation-idempotency.md)
