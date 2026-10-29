# ADR-006: 외부 PG 호출과 DB 트랜잭션 분리

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

PG 응답을 기다리는 동안 DB 연결과 잠금을 유지하면 연결 풀이 고갈되고 트랜잭션이 길어진다.

## Decision

Payment READY/APPROVING과 결제 멱등 레코드를 Tx1에서 커밋하고 PG를 호출한 뒤
별도 Tx2에서 결과를 기록한다.

2026-07-29 결제 승인 구현부터 Ordering Application Service가 Tx2를 처리한다.
승인 결과, Order `PAID`, 슬롯·쿠폰·포인트 확정과 AuditRecord를 하나의 PostgreSQL
트랜잭션에 저장한다. PG 응답을 기다리는 동안에는 DB 트랜잭션과 연결을 유지하지 않는다.

Tx2가 끝난 뒤 Payment 승인 이벤트를 발행한다. Settlement 같은 후속 처리에서 사용하며,
Tx2에 저장한 예약 상태는 이벤트 소비 과정에서 다시 변경하지 않는다.

2026-08-09 PaymentMethod 등록·폐기 변경 사항:

- Payment 승인 Tx1은 검증·잠근 ACTIVE PaymentMethod에서 비공개 immutable
  `PaymentProviderRequestSnapshot`을 Payment·멱등 레코드와 함께 저장한다.
- Tx1 이후 PG 승인·조회·복구에는 저장된 snapshot을 사용한다. 이후 PaymentMethod가 폐기돼도
  이미 시작된 결제의 승인 이력은 유지한다.
- deactivation Tx D1이 먼저 commit하면 새 Payment Tx1은 snapshot과 Payment를 만들지 않는다.
- snapshot이나 binding이 없으면 오류 처리한다. 현재 PaymentMethod를 다시 조회하거나 기본 결제수단을 적용하지 않는다.

## Alternatives Considered

- 외부 호출을 DB 트랜잭션 내부에서 실행
- 트랜잭션 분리와 reconciliation
- 완전 비동기 승인

## Rationale

PG 응답을 기다리는 동안 DB 연결과 lock을 반환하고, PG 승인 뒤 DB 저장에 실패한 결제는 별도 조회와 재처리로 처리한다.

## Consequences

- 중간 UNKNOWN 상태와 reconciliation이 필요하다.
- PG 호출 이후 DB 저장 전까지의 상태를 별도 기록하고 조회·재처리해야 한다.
- Ordering coordinator가 Payment와 네 reservation owner의 공개 Application API에
  동기 의존한다.

## Verification

- Provider timeout
- PG 성공 후 DB write 실패
- Hikari pending/active 측정

## Revisit Conditions

Provider가 원자적 callback 또는 다른 보장 방식을 제공할 때

## Related Decisions

- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-079](ADR-079-payment-method-token-management.md)
