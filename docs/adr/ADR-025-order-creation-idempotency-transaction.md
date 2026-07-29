# ADR-025: 주문 생성 멱등 레코드의 선행 등록과 최초 응답 재생

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-25는 `actorId + operation + Idempotency-Key` scope, canonical payload hash와
응답 저장을 요구한다. ADR-005는 Order와 네 자원 예약 중 하나라도 실패하면 전체
주문 생성 트랜잭션을 롤백하도록 한다. IdempotencyRecord를 주문 트랜잭션에만 넣으면
도메인 실패 때 record도 사라지고, 동시 같은 key의 insert-first arbitration과
서버 crash 뒤 `PROCESSING` 상태를 설명할 수 없다.

## Decision

주문 생성은 다음 transaction을 사용한다.

1. **Tx I1 — idempotency registration:** Ordering이 Order ID를 미리 생성하고
   `(actorId, CREATE_ORDER, key)` scope, canonical payload hash, intended Order ID,
   `PROCESSING`, 시작 시각을 짧은 별도 트랜잭션에서 insert한다. Unique Constraint의
   승자만 이후 주문 생성을 실행한다.
2. 기존 scope가 있으면 payload hash를 먼저 비교한다. 다르면 owner 자원을 조회하거나
   잠그기 전에 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다.
3. 같은 hash의 기존 `COMPLETED` 또는 `FAILED` record는 저장된 최초 HTTP status와
   body를 그대로 재생하며 새 Order나 예약을 만들지 않는다.
4. 같은 hash의 `PROCESSING` record는
   `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`와 `Retry-After`를 반환한다. 주문 생성은
   동기 명령이므로 이 상태를 완료되지 않은 성공이나 `202 Accepted`로 표현하지 않는다.
5. **Tx O — order creation:** Order, 슬롯·재고·쿠폰·포인트 예약, 필요한 AuditRecord와
   IdempotencyRecord의 `COMPLETED + 201 response` 갱신을 하나의 로컬 트랜잭션에서
   커밋한다.
6. 확정된 validation/domain/dependency 실패로 Tx O가 롤백되면
   **Tx I2 — failure completion**에서 원래 stable HTTP status와 Error body를
   IdempotencyRecord `FAILED`로 저장한다. 이후 같은 key/hash는 그 결과를 재생한다.
7. Tx I1 뒤 process가 중단되거나 Tx I2가 실패해 `PROCESSING`이 남으면 새 주문을
   자동 실행하지 않는다. reconciliation은 intended Order ID와 각 owner source
   reference를 조회한다. 커밋된 결과가 있으면 그 결과로 `COMPLETED`하고, 부수효과가
   전혀 없음을 확인한 경우에만 명시적 failure로 종결한다. 일부 결과나 판단 불가는
   `MANUAL_REVIEW`와 ReprocessingCase로 남긴다.
8. stuck scan 시작 threshold는 최대 동기 request timeout보다 길어야 하는 운영
   configuration이다. 측정 없이 비즈니스 보장으로 표현하지 않으며 backlog와 age를
   관측한다.

canonical payload는 JSON object key를 정렬하고 API default를 명시 값으로
materialize한다. OrderLine 배열 순서는 BR-12 배분 tie-breaker라 유지한다.
`optionIds`는 집합 의미이므로 중복을 거부하고 ID 오름차순으로 정렬한 뒤 hash한다.

## Alternatives Considered

### 최초 response 재생

- 같은 key가 시간에 따라 다른 HTTP 의미를 갖지 않는다.
- replay에도 201이 반환되어 새로 생성된 것처럼 보일 수 있으므로 client가 key를
  request identity로 관리해야 한다.

### 완료 replay는 200 current representation

- 조회처럼 직관적이다.
- 최초 response 저장 계약이 약해지고 terminal Order 변화에 따라 create replay
  결과가 달라질 수 있다.

### IdempotencyRecord를 Tx O에만 저장

- transaction 수는 적다.
- 동시에 같은 key가 들어올 때 선행 arbitration과 rollback된 실패 response 재생을
  제공하지 못한다.

### PROCESSING을 202로 반환

- polling 모델과 유사하다.
- 동기 주문 생성을 비동기 접수 성공처럼 보이게 하고 Payment UNKNOWN의 202 의미와
  혼동된다.

## Rationale

선행 record가 동시 실행의 단일 승자를 정하고, Order/예약과 성공 response 완료를 같은
Tx O에 묶어 성공 정합성을 보장한다. 최초 response 재생은 client retry의 결과를
결정적으로 유지하며 `PROCESSING`을 명시적 충돌로 드러낸다.

## Consequences

- 정상 주문 생성은 최소 두 DB transaction을 사용한다.
- 확정 실패 저장과 stuck reconciliation이 필요하다.
- FAILED 503을 replay한 client가 새 시도를 원하면 새 Idempotency-Key를 사용해야
  한다. 기존 key를 재실행해 부수효과를 만들지 않는다.
- 응답 body schema evolution 동안 90일 보존 record를 읽을 수 있어야 하므로 response
  payload version을 함께 저장한다.
- `Retry-After` 값은 처리 중 record의 상태와 운영 설정에 따라 계산하며 고정된
  비즈니스 숫자가 아니다.

## Verification

- 같은 key/hash 순차 replay가 최초 201 body와 status를 그대로 반환
- 같은 key/hash 동시 요청 중 하나만 Tx O 실행
- 같은 key/different hash가 owner 조회 전에 409
- 확정된 domain 409와 dependency 503의 저장·재생
- Tx I1 뒤 crash, Tx O rollback 뒤 Tx I2 failure
- intended Order/source가 없음·전부 있음·일부 있음의 reconciliation 분기
- canonical object key, line order와 option ID order test

## Metrics

- idempotency registration conflict count
- response replay count by stored status class
- PROCESSING count와 oldest age
- reconciliation completion/manual review count
- **Not measured:** 정상 주문 생성의 추가 transaction latency

## Revisit Conditions

DB transaction 비용이 측정된 병목이 되거나 API gateway가 동등한 durable
idempotency 보장을 제공할 때

## Related Decisions

- BR-25, BR-26
- [ADR-005](ADR-005-reservation-transaction-strategy.md)
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
