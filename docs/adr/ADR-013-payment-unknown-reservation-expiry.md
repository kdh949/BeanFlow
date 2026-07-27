# ADR-013: 결제 결과 불명과 예약 만료의 경계

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-03은 주문 생성 후 슬롯, 재고, 쿠폰과 포인트 예약을 5분간 유지하고 그 안에
결제가 승인되지 않으면 해제하도록 한다. BR-25와 ADR-007은 Provider timeout을
실패가 아닌 `UNKNOWN`으로 보존한다. 5분 시점에 Payment가 `UNKNOWN`이면 Provider가
실제로 승인했을 수 있으므로 예약 해제와 뒤늦은 승인 반영이 서로 충돌할 수 있다.

## Decision

5분 lease 만료 시 Payment가 `UNKNOWN`이더라도 Order를 `EXPIRED`로 전환하고 슬롯,
재고, 쿠폰과 포인트 예약을 해제한다.

만료의 API·worker materialization은 다음을 따른다.

- deadline 판단은 `now >= reservationExpiresAt`이다.
- scheduled worker, `GET /orders/{orderId}`와 결제 명령은 같은 idempotent
  expiry Application Service를 사용한다.
- 조회 시 due `PENDING_PAYMENT` Order를 발견하면 응답 전에 Order 만료와 네 자원
  해제를 하나의 로컬 transaction으로 실행한다. 성공한 뒤 `EXPIRED` Order를
  반환한다.
- expiry transaction의 일부가 실패하면 전체를 rollback하고 조회는
  `503 DEPENDENCY_UNAVAILABLE`를 반환한다. DB의 stale `PENDING_PAYMENT`를 정상
  response에서 `EXPIRED`로 계산해 가장하거나 부분 해제를 성공으로 표현하지 않는다.
- 결제 명령도 같은 guarded expiry를 먼저 수행한다. 만료가 성공하면
  `409 RESERVATION_EXPIRED`, 만료 materialization 자체가 실패하면 503이며 결제를
  진행하지 않는다.
- 조건부 GET write는 시간이 만든 이미 예정된 상태 전이를 materialize하는
  동작으로 한정한다. 같은 요청을 반복해도 추가 부수효과를 만들지 않는다.

이 clarification은 2026-07-28 주문 생성과 예약 lease Feature의 결정 게이트에서
확정했다.

이후 reconciliation에서 Provider 승인이 확인되면:

1. 만료 Order를 `PAID`로 되살리지 않는다.
2. 해제된 예약을 다시 확정하지 않는다.
3. Payment가 Provider void를 우선 시도하고 불가능하거나 결과가 불명확하면 전액 환불
   reconciliation을 시작한다.
4. void/refund 성공이 확인되기 전까지 `RECONCILING`, 반복 자동 복구 범위를 벗어나면
   `MANUAL_REVIEW`와 ReprocessingCase를 유지한다.
5. expiration, late approval과 void/refund 각각의 source reference와 idempotency key로
   부수효과를 한 번만 실행한다.

## Alternatives Considered

### Five-minute expiry with late-approval refund

- 고정 lease와 자원 회수를 유지한다.
- 고객에게 일시 승인 금액이 보일 수 있고 명시적인 refund recovery가 필요하다.

### Hold until reconciliation

- 뒤늦게 승인된 주문을 이행할 수 있다.
- Provider 장애 동안 자원을 무기한 점유해 capacity가 고갈될 수 있다.

### Bounded grace period

- 짧은 Provider 지연을 흡수하면서 상한을 둔다.
- 측정되지 않은 새 정책 숫자와 추가 timer race가 생긴다.

## Rationale

고정 5분 lease를 유지해 자원 무기한 점유를 막고, 이미 해제된 슬롯·재고를 뒤늦은
승인으로 다시 확정하여 oversell을 만드는 것을 방지한다. 고객에게 일시 승인 금액이
보일 수 있는 비용은 명시적 void/refund 상태, 운영 case와 수동 복구로 다룬다.

## Consequences

- Payment 승인 reconciliation은 Order가 여전히 `PENDING_PAYMENT`인지 확인한 뒤에만
  `PAID` 전이를 요청한다.
- `EXPIRED`이면 승인 성공 event가 정상 주문 완료 경로로 전달되지 않고 void/refund
  recovery 경로로 라우팅된다.
- void/refund 실패나 결과 불명은 만료 성공에 묻히지 않으며 고객·운영자에게 별도 상태로
  노출된다.
- Order 조회가 조건부 expiry transaction을 실행할 수 있으므로 read path의 DB
  장애도 빈 값이나 stale 성공이 아니라 503으로 노출된다.

## Verification

- 결제 timeout과 5분 만료 작업의 동시 실행
- worker 전·후 due Order 조회의 동일한 EXPIRED 결과
- 조회가 시작한 expiry transaction의 owner release 실패와 전체 rollback
- Provider 승인 후 응답 유실
- reconciliation이 만료 전·후 도착하는 두 경계
- 중복 승인·환불·예약 해제 부작용이 각각 한 번인지 검증

## Metrics

- **Not measured:** Provider reconciliation 지연 분포, lease 점유율, 뒤늦은 승인 비율
- **Revisit when:** 측정값이나 실제 Provider 계약이 현재 선택의 비용을 바꿀 때

## Revisit Conditions

Provider callback/idempotency 보장, 예약 capacity 또는 고객 승인취소 요구가 달라질 때

## Related Decisions

- BR-03, BR-04, BR-05, BR-25
- [ADR-005](ADR-005-reservation-transaction-strategy.md)
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- Decision confirmed 2026-07-28: five-minute expiry with late-approval void/refund
