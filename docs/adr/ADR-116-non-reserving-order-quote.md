# ADR-116: 비예약 주문 quote와 전체 fingerprint 사전조건

- **Status:** Accepted
- **Date:** 2026-08-25
- **Implementation owner:** [고객·점주 화면 재현을 위한 계약 완성](../exec-plans/active/customer-merchant-screen-contract-completion.md)

## Context

고객 장바구니는 현재 client 계산 합계를 표시하고, 최종 `POST /orders`만 현재 메뉴·옵션·쿠폰·포인트·
픽업 슬롯을 검증하고 예약한다. 고객에게 주문 전 authoritative breakdown을 보여 주려면 같은 입력을
server가 계산하는 read 계약이 필요하다. 그러나 quote가 가격 보장이나 자원 예약이 되면 만료, 해제,
중복 사용과 복구 상태가 새 거래 모델로 추가된다.

금액만 비교하는 사전조건도 충분하지 않다. 메뉴 옵션, 쿠폰 비용 부담 source, 포인트 lot provenance 또는
픽업 window가 바뀌어도 최종 결제액은 우연히 같을 수 있다. 이 경우 고객이 확인하지 않은 거래 구성이
주문 snapshot에 저장될 수 있다.

기존 [BR-25](../product/business-policy-decisions.md)는 주문 생성의 확정된 4xx/503을 최초 terminal
멱등 응답으로 저장·재생한다. 처음 작성한 ExecPlan은 stale quote가 idempotency response를 만들지
않는다고 제안해 이 정책 및 현재 주문 생성 원장과 충돌했다. 2026-08-25 제품 결정으로 기존 BR-25를
유지하고 stale quote도 같은 terminal 실패 의미를 사용한다.

## Decision

### 1. quote는 side effect 없는 customer-scoped 계산이다

`POST /api/v1/me/order-quotes`는 customer Session과 CSRF를 사용하고, 최종 주문과 같은 editable
input만 받는다.

```text
storeId
pickupSlotId
lines[{ menuId, quantity, selectedOptionIds }]
couponIssuanceId?
pointsToUseKrw
```

이 endpoint는 owner Context의 coherent read snapshot을 조합해 `OrderQuote`를 반환한다.

```text
OrderQuote
  quotedAt
  quoteFingerprint
  store { storeId, name }
  pickupWindow { startsAt, endsAt }
  lines[{ menuId, menuName, quantity, optionNames, lineTotalKrw }]
  pricing { subtotalKrw, couponDiscountKrw, pointsAppliedKrw, payableKrw, currency }
  guarantee: NONE
```

quote 성공과 실패 모두 Order, 픽업·재고·쿠폰·포인트 reservation, Payment, 주문 생성 idempotency
record, Audit 또는 event를 만들지 않는다. Provider를 호출하거나 lock을 장시간 보유하지 않으며
`Idempotency-Key`도 받지 않는다. 의존성 실패는 typed 5xx로 드러내고 client 합계, 0원 할인,
cached/stale owner 값 또는 가짜 슬롯으로 성공하지 않는다.

### 2. 하나의 versioned canonical fingerprint 함수를 공유한다

quote와 최종 주문 생성은 `order-quote-fingerprint/v1` canonical material과 SHA-256 소문자 hex
함수를 공유한다. transport는 fingerprint를 opaque 문자열로 취급하며 client가 내용을 해석하거나
계산하지 않는다. `quotedAt`은 정보용이므로 material에 포함하지 않는다.

V1 material은 최소한 다음 전체 의미를 결정적으로 포함한다.

- 정규화한 editable input: Store·slot·menu·option 식별자, 수량, coupon issuance, 요청 포인트
- catalog snapshot: 메뉴·옵션 표시명, 가격, 판매 가능성, 선택 구성과 quote에 영향을 주는 owner version
- benefit snapshot: coupon source/terms/version과 line 배분, 실제 적용 포인트와 lot issuer provenance,
  적용 policy version
- pickup snapshot: slot 식별자·시작/종료, Store 주문/pickup policy, 용량 eligibility와 관련 owner version
- authoritative output: Store 표시 snapshot, line snapshot, subtotal·coupon·point·payable·currency

식별자 목록과 option selection은 명시적인 안정 정렬을 사용하고, null과 empty를 같은 값으로
암묵 정규화하지 않는다. canonical serializer나 필드 집합을 바꾸면 prefix version을 올리고 quote와
final-create를 같은 배포에서 원자적으로 바꾼다. `payableKrw`만 같거나 client가 fingerprint 형식을
맞췄다는 사실은 주문 권한이 아니다.

### 3. 최종 주문 transaction이 유일한 serialization point다

`POST /orders`는 같은 editable input과 필수 `expectedQuoteFingerprint`를 받으며 client 계산 금액,
discount, reservation ID 또는 Provider input을 받지 않는다. 기존 주문 생성 idempotency 원장을 먼저
등록한 뒤, 기존 lock 순서를 지키는 짧은 transaction에서 현재 owner state를 다시 읽고 full fingerprint를
계산한다.

- exact match이면 같은 server 계산으로 자원을 예약하고 immutable Order snapshot을 저장한다.
- mismatch이면 transaction을 rollback하고 `409 ORDER_QUOTE_STALE`과
  `currentQuote: OrderQuote`를 반환한다.
- malformed fingerprint는 `400 INVALID_REQUEST`이며 최신 quote를 자동 수락하는 의미가 아니다.

stale transaction은 Order, reservation, Payment, Audit 또는 event를 남기지 않는다. 다만 BR-25에
따라 첫 `ORDER_QUOTE_STALE` status와 body는 주문 생성 idempotency 원장에 `FAILED` terminal
응답으로 별도 저장한다. 같은 key·같은 payload는 그 응답을 byte-equivalent하게 재생하므로, 포함된
`currentQuote`가 시간이 지나도 자동 갱신되지 않는다. 고객은 quote를 다시 조회·검토한 뒤 새
fingerprint와 **새 `Idempotency-Key`**로 주문을 다시 제출해야 한다. 같은 key에 새 fingerprint를
보내면 기존 `IDEMPOTENCY_KEY_REUSED` 409다.

terminal 실패 저장이 실패하면 기존 주문 생성 failure policy대로 503을 반환한다. stale를 성공,
예약 성공 또는 재시도 가능한 같은 command로 표현하지 않는다.

### 4. 기존 결제·세무 범위를 바꾸지 않는다

fingerprint match 뒤 만들어진 Order는 기존 Toss 일회성 결제와 lease를 사용한다. quote는
`PaymentMethod`, billing key, 저장 카드 선택, PG 영수증, VAT 또는 세무 계산을 추가하지 않는다.
BR-05의 reservation 확정 시점과 BR-33의 일회성 결제 경계는 그대로 유지한다.

### 5. 관측 정보는 닫힌 vocabulary만 사용한다

quote와 final-create에는 outcome/latency metric만 추가할 수 있다. customer ID, Store ID, order
reference, menu/option ID, coupon issuance, cart 내용, fingerprint와 금액은 metric tag나 log field에
넣지 않는다. stale와 dependency failure는 서로 다른 outcome이다.

## Alternatives Considered

### quote row와 reservation token 저장

가격 보장은 명확하지만 만료·해제 worker, 미확정 reservation과 cleanup idempotency가 새 source of
truth가 된다. 현재 요구는 고객 확인용 계산이므로 도입하지 않는다.

### payable 금액만 fingerprint에 포함

구성과 benefit source가 달라도 같은 금액이면 통과한다. immutable snapshot과 비용 귀속을 고객이
확인한 quote에서 바꿀 수 있어 채택하지 않았다.

### stale 실패의 idempotency record를 남기지 않음

같은 네트워크 재시도가 현재 상태에 따라 나중에 주문을 만들 수 있고 BR-25의 확정 실패 재생과
충돌한다. 고객 재확인은 새 command identity로 표현해야 하므로 채택하지 않았다.

### stale replay마다 current quote 재계산

멱등 replay가 최초 body를 그대로 반환한다는 계약을 깨고, 같은 key의 의미가 시간에 따라 달라진다.
fresh quote는 quote endpoint와 새 key로 얻는다.

## Rationale

read-only quote는 customer에게 server 계산을 보여 주면서 자원 hold의 운영 비용을 만들지 않는다.
full fingerprint는 금액뿐 아니라 거래 구성과 귀속을 optimistic precondition으로 묶는다. 최종 transaction
안의 재계산과 기존 lock이 authority를 유지하고, BR-25 terminal replay가 네트워크 재시도를 새 주문
시도로 바꾸지 않는다.

## Consequences

- quote는 보장이 아니므로 정상적으로 stale될 수 있고 UI에 명시적인 재확인 상태가 필요하다.
- fingerprint material 변경은 quote와 order-create의 원자적 계약 변경이 된다.
- stale replay의 `currentQuote`는 최초 실패 시점 snapshot이다. 최신 상태가 필요하면 quote를 다시
  요청해야 한다.
- 최종 주문 입력에 `expectedQuoteFingerprint`가 필수가 되므로, 배포된 client가 없다는 전제 아래
  compatibility alias 없이 response/request shape를 한 번에 교체한다.
- quote read가 여러 owner Context에 의존하므로 하나라도 실패하면 전체 quote가 실패한다.

## Verification

- quote 정상·validation·dependency 실패 뒤 모든 Order/reservation/Payment/idempotency/Audit/event row가
  증가하지 않는지 검증한다.
- exact quote로 만든 Order의 line·benefit·pickup·pricing snapshot이 quote와 일치하는지 검증한다.
- price, option, coupon, point lot/source, slot window/capacity 변화와 동일 payable·다른 source가 모두
  `ORDER_QUOTE_STALE`인지 검증한다.
- stale 뒤 거래 row는 없고 주문 생성 idempotency row 하나만 terminal `FAILED`인지 검증한다.
- 같은 key·payload는 최초 stale status/body를 재생하고, 새 fingerprint를 같은 key로 보내면
  `IDEMPOTENCY_KEY_REUSED`, 새 key로 보내면 재검증되는지 검증한다.
- malformed/tampered fingerprint, client money field 거부, 동시 가격·capacity 변경을 계약 및
  PostgreSQL 동시성 테스트로 검증한다.
- quote endpoint가 Provider, reservation service write와 Audit를 호출하지 않는지 검증한다.

## Revisit Conditions

가격 보장 기간, 장시간 cart hold, 사전 결제 승인, 분산 owner 저장소, 저장 quote 감사 증적 또는 quote
전환율 측정에 persistent identity가 필요할 때 reservation/quote Aggregate를 별도 결정으로 재검토한다.

## Related Decisions

- BR-05, BR-25, BR-33, BR-49
- [ADR-004](ADR-004-order-price-snapshot.md)
- [ADR-005](ADR-005-reservation-transaction-strategy.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-025](ADR-025-order-creation-idempotency-transaction.md)
- [ADR-057](ADR-057-idempotent-response-replay-indicator.md)
