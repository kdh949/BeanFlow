# ADR-015: 매장 수락 timeout과 보상 흐름

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-06, BR-07과 BR-14는 결제 승인 후 2분 경고, 3분 수락 제한과 자동 거절을 정한다.
거절은 Order 외에도 Payment, 예약, 쿠폰, 포인트와 Notification에 부수효과를 만든다.

## Decision

- Ordering이 수락 deadline과 Order 전이의 owner다.
- 2분 경고와 3분 timeout job은 order/deadline reference로 멱등하게 실행한다.
- `PAID`에서 수락과 거절 중 하나의 guarded transition만 성공한다.
- `REJECTED` 후 각 owner Context에 결제 환불, 재고·슬롯 해제, 쿠폰·포인트 복원과
  고객 알림 명령 또는 영속 event를 전달한다.
- 보상 실패는 Order 전이를 되돌리지 않으며 각 owner 상태, retry와 Operations case로
  남긴다. Order `REJECTED`를 보상 완료로 해석하지 않는다.
- `ACCEPTED` 이후 단순 거절은 허용하지 않는다.

## Alternatives Considered

- 모든 보상을 한 장기 트랜잭션에서 처리
- Order를 거절하고 보상 실패를 로그만 남김
- 원본 전이와 멱등한 owner별 보상을 분리

## Rationale

원본 주문 결과를 한 번만 확정하면서 외부 환불과 비동기 부수효과의 실패를 숨기지 않는다.

## Consequences

- 보상 진행 상태를 조회·운영할 수 있어야 한다.
- Payment `UNKNOWN`과 5분 lease의 경계는 ADR-013 결정이 별도로 필요하다.

## Verification

- 2분·3분 Clock 경계
- 수락과 timeout 동시 실행
- 각 보상 event 중복 전달
- refund timeout과 manual recovery

## Metrics

- **Not measured:** 실제 수락시간 분포와 자동 거절률

## Revisit Conditions

매장별 timeout, 수락 후 취소 또는 실제 운영 분포가 다른 정책을 요구할 때

## Related Decisions

- BR-06, BR-07, BR-14
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
