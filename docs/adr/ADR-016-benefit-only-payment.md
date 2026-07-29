# ADR-016: 0원 혜택 전용 결제

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-11은 쿠폰 적용 후 남은 금액 전부를 포인트로 사용할 수 있게 하고 최종 결제액이
0원이면 외부 PG를 호출하지 않도록 정한다. PG 호출이 없더라도 주문의 결제 완료 근거와
멱등성이 필요하다.

## Decision

- 최종 외부 결제액이 0원이면 Payment `type=BENEFIT_ONLY`를 생성한다.
- 외부 Provider Adapter를 호출하지 않는다.
- 주문별 payment intent와 idempotency scope를 Unique Constraint로 보호한다.
- Payment는 승인 금액 0, `APPROVED` 상태와 혜택 배분 snapshot reference를 보존한다.
- 주문 생성 Feature가 이 경로를 소유한다. 주문 생성 로컬 트랜잭션에서 필요한 자원을
  먼저 예약한 뒤 Payment를 승인하고 같은 트랜잭션에서 예약을 확정하며 Order를
  `PAID`로 전환한다.
- Aggregate 전이는 `DRAFT -> PENDING_PAYMENT -> PAID`와
  `RESERVED -> CONFIRMED` 순서를 지키지만 중간 상태는 외부에 커밋하지 않는다.
- 커밋된 0원 주문에는 active `reservationExpiresAt` lease가 남지 않으며 만료 worker
  대상이 아니다.
- 취소·환불은 외부 환불 없이 사용 포인트와 쿠폰 예약을 owner Context 규칙에 따라
  복원한다.

이 범위 확장은 2026-07-28 주문 생성과 예약 lease Feature의 결정 게이트에서
확정했다. 외부 PG 승인, `UNKNOWN`과 reconciliation은 후속 Feature 범위로 유지한다.

## Alternatives Considered

- Payment를 만들지 않고 Order만 `PAID` 처리
- PG에 0원 승인 요청
- 명시적인 BENEFIT_ONLY Payment

## Rationale

결제 완료 근거와 후속 event 계약을 일반 주문과 통일하면서 불필요한 외부 의존성을
제거한다.

## Consequences

- Payment type별 Provider 필수 설정 검증이 필요하다.
- 운영 profile의 일반 결제가 BENEFIT_ONLY 경로로 우회하지 않도록 금액·혜택 합계를
  검증해야 한다.

## Verification

- 0원 주문에서 Provider 호출 0회
- 같은 키 동시 요청에서 Payment 한 건
- 1원 이상 주문의 BENEFIT_ONLY 거부
- Order, Payment와 네 자원 확정의 전체 commit 또는 rollback
- 0원 주문이 lease 만료 후보에 포함되지 않음
- 취소 시 포인트·쿠폰 복원 tie-out

## Metrics

- **Not measured:** 0원 주문 비율

## Revisit Conditions

포인트 사용 비율 제한 또는 Provider 최소 승인 정책이 도입될 때

## Related Decisions

- BR-08, BR-11
- [ADR-004](ADR-004-order-price-snapshot.md)
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
