# ADR-007: 결제 멱등성과 reconciliation

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

클라이언트 재시도, 응답 유실과 서버 장애로 같은 결제가 여러 번 요청될 수 있다.

## Decision

사용자·작업 종류·멱등성 키로 요청을 구분하고, 요청 내용의 해시와 처리 결과를 저장한다. DB 고유 제약으로 중복을 막고, timeout은 `UNKNOWN`으로 기록해 PG 조회로 결과를 확인한다.

2026-07-29 결제 승인 변경 사항:

- PG 승인 요청의 idempotency key는 Payment ID로 생성한다.
- `UNKNOWN`은 승인 요청을 다시 보내지 않고 10초, 30초, 2분, 5분, 15분에 상태를
  조회한다.
- 5회 조회가 끝나도 결과를 알 수 없으면 `MANUAL_REVIEW`로 전환하고 ReprocessingCase를 한 번 생성한다.
- 조회 Worker는 실행할 작업을 짧은 트랜잭션에서 가져온 뒤 트랜잭션 밖에서 PG를 호출한다.
  claim 기한이 지나면 다른 Worker가 해당 작업을 다시 가져올 수 있다.
- 늦은 승인은 Order를 복구하지 않고 void를 먼저 시도한다. void 결과가 불명이면
  상태를 다시 조회하고 승인 금액이 남아 있음이 확인된 뒤에만 refund를 실행한다.

2026-08-09 PaymentMethod 등록 변경 사항:

- 등록은 `actorId + REGISTER_PAYMENT_METHOD_V1 + Idempotency-Key` scope의 사전등록 모델이다.
- Tx R1은 intended PaymentMethod, `authKey` SHA-256, alias, CSPRNG provider reference와 work를
  commit하고 Provider는 transaction 밖에서 claim당 한 번 호출한다. raw authKey는 저장하지 않는다.
- 같은 key/payload는 최초 terminal 또는 현재 non-terminal result를 반환하고, 다른 payload와
  다른 key의 같은 authKey hash는 Provider 호출 전에 409로 거부한다.
- claim 뒤 timeout·응답 유실·process loss는 authKey를 재전송하지 않는다. Provider lookup이
  없으면 새 side effect 없이 `MANUAL_REVIEW`로 종결한다.
- registration 설정·인증 결함이 side effect 부재를 확인한 경우만 같은 key가 설정 수정 뒤 새
  claim을 획득할 수 있다. deactivation 설정 결함은 DELETE 재호출 없이 manual review다.

2026-08-09 PaymentMethod 폐기 변경 사항:

- 폐기는 `actorId + DEACTIVATE_PAYMENT_METHOD_V1 + Idempotency-Key` scope이며 canonical
  payload는 소유권 검증 뒤의 `paymentMethodId`다. 같은 key/target은 최초 terminal 또는 현재
  non-terminal result를 반환하고 같은 key/다른 target은 409로 거부한다.
- Tx D1이 deactivation state, work와 멱등 상태를 commit한 뒤 짧은 transaction에서 claim하고
  Provider DELETE는 transaction 밖에서 한 번만 호출한다.
- claim 뒤 timeout·응답 유실·process loss와 result 저장 실패에서는 Provider가 DELETE 멱등키나
  결과 조회를 보장하지 않으므로 DELETE를 자동 재호출하거나 not-found를 성공으로 간주하지 않는다.
  검증된 `BILLING_DELETED`로만 자동 수렴하고 최초 unknown 판정부터 96시간 뒤에는
  `MANUAL_REVIEW`로 종결한다.

## Alternatives Considered

- 클라이언트 중복 방지만 사용
- paymentKey Unique만 사용
- 요청·응답 멱등 기록과 reconciliation

## Rationale

같은 요청이 반복돼도 승인 요청과 저장 결과를 재사용하고, 응답 유실은 PG 조회로 처리한다.

## Consequences

- 멱등 레코드 보존·정리와 운영 job이 추가된다.
- 승인·조회·재시도·수동 처리 상태를 추가로 관리해야 한다.

## Verification

- 같은 키 같은 payload
- 같은 키 다른 payload 409
- 동시 재시도
- UNKNOWN 복구
- PaymentMethod 폐기 same-key replay와 cross-target conflict
- Provider DELETE claim 뒤 재호출 부재와 webhook/manual-review 수렴

## Revisit Conditions

Provider의 idempotency 보장과 보존 정책이 확정될 때

## Related Decisions

- BR-25, BR-26
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
- [ADR-079](ADR-079-payment-method-token-management.md)
