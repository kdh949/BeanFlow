# ADR-007: 결제 멱등성과 reconciliation

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

클라이언트 재시도, 응답 유실과 서버 장애로 같은 결제가 여러 번 요청될 수 있다.

## Decision

actor+operation+Idempotency-Key 범위, payload hash, Unique Constraint와 결과 저장을 사용한다. timeout은 UNKNOWN으로 보존하고 Provider 조회 reconciliation을 실행한다.

2026-07-29 amendment:

- Provider 승인 idempotency key는 안정적인 Payment ID에서 파생한다.
- `UNKNOWN`은 승인 요청을 다시 보내지 않고 10초, 30초, 2분, 5분, 15분에 상태를
  조회한다.
- 다섯 번째 조회 후에도 불명이면 `MANUAL_REVIEW`와 ReprocessingCase를 한 번
  생성한다.
- 조회 worker는 짧은 transaction에서 due work를 claim하고 외부 조회는 transaction
  밖에서 실행한다. 만료된 claim은 재획득할 수 있다.
- 늦은 승인은 Order를 복구하지 않고 void를 먼저 시도한다. void 결과가 불명이면
  상태를 다시 조회하고 승인 금액이 남아 있음이 확인된 뒤에만 refund를 실행한다.

2026-08-09 PaymentMethod registration amendment:

- 등록은 `actorId + REGISTER_PAYMENT_METHOD_V1 + Idempotency-Key` scope의 사전등록 모델이다.
- Tx R1은 intended PaymentMethod, `authKey` SHA-256, alias, CSPRNG provider reference와 work를
  commit하고 Provider는 transaction 밖에서 claim당 한 번 호출한다. raw authKey는 저장하지 않는다.
- 같은 key/payload는 최초 terminal 또는 현재 non-terminal result를 반환하고, 다른 payload와
  다른 key의 같은 authKey hash는 Provider 호출 전에 409로 거부한다.
- claim 뒤 timeout·응답 유실·process loss는 authKey를 재전송하지 않는다. Provider lookup이
  없으면 새 side effect 없이 `MANUAL_REVIEW`로 종결한다.
- registration 설정·인증 결함이 side effect 부재를 확인한 경우만 같은 key가 설정 수정 뒤 새
  claim을 획득할 수 있다. deactivation 설정 결함은 DELETE 재호출 없이 manual review다.

2026-08-09 PaymentMethod deactivation amendment:

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

중복 승인과 결과 불명 상태를 모두 다룬다.

## Consequences

- 멱등 레코드 보존·정리와 운영 job이 추가된다.
- 상태 머신이 복잡해진다.

## Verification

- 같은 키 같은 payload
- 같은 키 다른 payload 409
- 동시 재시도
- UNKNOWN 복구
- PaymentMethod 폐기 same-key replay와 cross-target conflict
- Provider DELETE claim 뒤 재호출 부재와 webhook/manual-review 수렴

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

Provider의 idempotency 보장과 보존 정책이 확정될 때

## Related Decisions

- BR-25, BR-26
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
- [ADR-079](ADR-079-payment-method-token-management.md)
