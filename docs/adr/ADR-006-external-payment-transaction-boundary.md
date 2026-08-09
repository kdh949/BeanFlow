# ADR-006: 외부 PG 호출과 DB 트랜잭션 분리

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

외부 Provider latency 동안 DB connection과 lock을 유지하면 pool 고갈과 긴 트랜잭션이 발생한다.

## Decision

Payment READY/APPROVING과 결제 멱등 레코드를 Tx1에서 커밋하고 PG를 호출한 뒤
별도 Tx2에서 결과를 기록한다.

2026-07-29 payment-confirmation 구현에서는 Ordering Application Service가 Tx2를
조정한다. 승인 결과, Order `PAID`, 슬롯·재고·쿠폰·포인트 확정과 AuditRecord를
하나의 로컬 PostgreSQL transaction으로 커밋한다. Provider latency 동안에는 DB
transaction 또는 connection을 유지하지 않는다.

Payment 승인 fact의 after-commit 발행은 Tx2가 이미 확정한 예약을 다시 변경하는
수단이 아니라 Settlement 등 후속 소비자를 위한 사실 전달로 한정한다.

2026-08-09 PaymentMethod lifecycle amendment:

- Payment 승인 Tx1은 검증·잠근 ACTIVE PaymentMethod에서 비공개 immutable
  `PaymentProviderRequestSnapshot`을 Payment·멱등 레코드와 함께 저장한다.
- Tx1 commit 뒤 Provider approve/lookup/recovery는 current PaymentMethod가 아니라 snapshot을
  사용한다. 뒤에 deactivation이 commit돼도 이미 시작된 Payment fact를 소급 취소하지 않는다.
- deactivation Tx D1이 먼저 commit하면 새 Payment Tx1은 snapshot과 Payment를 만들지 않는다.
- snapshot 또는 binding 누락을 current PaymentMethod 읽기나 default 결제수단으로 보정하지 않는다.

## Alternatives Considered

- 외부 호출을 DB 트랜잭션 내부에서 실행
- 트랜잭션 분리와 reconciliation
- 완전 비동기 승인

## Rationale

DB 자원을 보호하면서 외부 성공·내부 기록 실패를 명시적으로 복구한다.

## Consequences

- 중간 UNKNOWN 상태와 reconciliation이 필요하다.
- 단일 ACID 트랜잭션처럼 보이지 않는다.
- Ordering coordinator가 Payment와 네 reservation owner의 공개 Application API에
  동기 의존한다.

## Verification

- Provider timeout
- PG 성공 후 DB write 실패
- Hikari pending/active 측정

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

Provider가 원자적 callback 또는 다른 보장 방식을 제공할 때

## Related Decisions

- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-079](ADR-079-payment-method-token-management.md)
