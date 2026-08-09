# ADR-080: Toss V2 일회성 결제창과 Payment 시도 경계

- **Status:** Accepted
- **Date:** 2026-08-10
- **Supersedes:** ADR-078의 자동결제·빌링키 기반 승인/등록/폐기 경로

## Context

BeanFlow의 커피 선주문은 고객이 주문할 때 한 번 결제하는 거래다. ADR-078은 sandbox에서
실제 Provider 실패를 검증하려고 Toss 자동결제와 `PaymentMethod.tokenReference`를 승인
소스로 선택했지만, 자동결제는 구독형 서비스용이고 일회성 고객 checkout의 제품 계약이
아니다. 현재 runtime `POST /orders/{orderId}/payment-confirmations`도 `paymentMethodId`를
받아 서버가 즉시 Provider 승인을 시도하므로 브라우저 인증, success/fail callback과
Standard Payment Window를 표현하지 못한다.

Toss V2의 일반결제는 브라우저가 서버가 준비한 `amount`, `orderId`, `orderName`과
`customerKey`로 인증하고, success callback의 `paymentKey`, `orderId`, `amount`를 서버가
검증한 뒤 `/v1/payments/confirm`으로 승인한다. 인증과 승인은 분리되며 승인 결과가
불명확하면 새 승인 요청이 아니라 결제 조회로 수렴해야 한다.

## Decision

### 제품과 Aggregate owner

- 기본 제품은 Toss Web SDK V2 **Standard Payment Window**의 one-time `CARD` 통합결제창이다.
  Payment Widget, BrandPay, billing, 가상계좌, 지급대행은 구현하지 않는다.
- 기존 `Payment` Aggregate가 one-time 결제 시도를 소유한다. 별도 CheckoutSession
  Aggregate를 만들지 않고 Payment와 1:1인 immutable provider checkout snapshot을 둔다.
- `PaymentMethod` lifecycle과 과거 token snapshot은 보존하지만 one-time checkout 준비,
  인증, 승인, 조회, 취소의 인가·입력·fallback에 사용하지 않는다. 공개 checkout UI는
  저장 카드, 지갑, 결제수단 추가를 보여주지 않는다.

### 서버 준비 계약

- `POST /api/v1/orders/{orderId}/payment-attempts`는 owner와 주문 상태를 검증하고
  Payment, 준비 멱등 원장, reconciliation work와 immutable checkout snapshot을 같은
  local transaction에서 저장한다.
- 서버가 다음 값을 한 번 생성하고 snapshot에 고정한다.
  - `providerOrderId`: Payment ID에서 만든 6..64자의 추측 불가능하고 unique한 Toss 주문번호
  - `customerKey`: CSPRNG 32 bytes를 padding 없는 Base64URL로 표현한 opaque 46자 값
  - `amount`: Order의 현재 `payableKrw`, 통화 `KRW`
  - `orderName`: 서버가 Order snapshot에서 만든 1..100자 표시 이름
  - 성공·실패 callback URL과 인증 만료 시각
- 브라우저는 서버 응답을 Toss SDK에 그대로 전달하고 값을 다시 계산하거나 사용자 입력으로
  덮어쓰지 않는다. public client key는 같은 server configuration의 read-only config endpoint가
  제공하고 matching secret key는 서버에만 둔다.
- prepare 멱등 scope는 `actorId + PREPARE_ONE_TIME_PAYMENT_V1 + Idempotency-Key`다. canonical
  payload는 `orderId`만 포함한다. 같은 key/same payload는 같은 Payment와 snapshot을 반환하고,
  다른 payload는 409다. 한 Order에는 하나의 active external Payment만 허용한다.

### callback과 승인 계약

- `POST /api/v1/payments/{paymentId}/confirmations`는 Toss success callback의
  `paymentKey`, `orderId`, `amount`를 받는다. path Payment의 owner, snapshot
  `providerOrderId`, server amount, 상태와 callback payload를 모두 검증한다.
- fail callback은 Provider confirm을 호출하지 않는다. 브라우저는 허용된 `code`를 고객 문구로
  변환하고 Payment status를 다시 조회한다. raw Provider message를 신뢰하거나 서버·관측 데이터에
  저장하지 않는다.
- confirmation은 callback payload hash와 stable Provider idempotency key를 Payment에 고정한다.
  같은 Payment·같은 payload replay는 저장된 현재/terminal 결과로 수렴하고 Provider를 다시
  호출하지 않는다. 같은 Payment의 다른 paymentKey/orderId/amount, 다른 Payment에 이미 묶인
  paymentKey와 교차 owner callback은 409 또는 403/404로 거부한다.
- `paymentKey`는 secret과 동일하게 취급하지 않지만 공개 응답, URL 정리 이후 브라우저 history,
  log, trace, metric tag, Audit detail에 노출하지 않는다. DB에는 Provider 조회·취소에 필요한
  원문을 암호화 없이 최소 보존하되 application error에 echo하지 않는다.

### 트랜잭션과 외부 실패

```text
Tx A  Order lock -> Payment/prepare idempotency/snapshot/reconciliation commit
Browser  Toss SDK 인증과 success/fail redirect
Tx B  Payment lock -> callback 검증 -> paymentKey/stable key/APPROVING claim commit
No Tx Toss POST /v1/payments/confirm
Tx C  Payment result + Order/resource transition + idempotent response + Audit commit
```

- Toss HTTP 호출 동안 DB transaction과 connection을 유지하지 않는다.
- confirm timeout, 연결 실패, 5xx, 응답 유실·파싱 실패, 성공 필드 누락과 Tx C 실패는 성공/거절로
  추정하지 않는다. Payment를 `UNKNOWN` 또는 `RECONCILING`으로 남기고 같은 `paymentKey` 또는
  `providerOrderId`를 조회한다.
- Provider confirm을 시작한 뒤에는 새 key로 confirm을 재전송하지 않는다. Provider
  `Idempotency-Key`는 Payment별 stable UUID이고 confirm·cancel의 각 logical operation마다 분리한다.
- query가 정확한 승인과 금액·통화를 확인하면 기존 Tx C를 재실행한다. 만료/취소 Order의 늦은
  승인은 Order를 되살리지 않고 기존 late void/refund 경로로 보낸다. 유한 조회 예산 소진과
  불일치는 `MANUAL_REVIEW`다.

### Provider adapter와 취소

- Toss adapter는 API 개별 연동 test client/secret key 쌍만 `toss-sandbox & !prod`에서 허용한다.
  client key는 `test_ck_`, secret은 `test_sk_`여야 한다. Payment Widget용 `test_gck_`/`test_gsk_`,
  missing/live/profile overlap은 startup failure다. 키 쌍의 실제 MID 일치는 Provider의 인증 응답으로
  fail-closed 검증한다.
- 로컬 실제 호출은 `toss-sandbox-runtime` profile group이 `local`과 `toss-sandbox`를 합성한다.
  scripted `PaymentGateway`와 scripted PaymentMethod provider는 제외하고 Toss gateway 하나와
  legacy PaymentMethod 요청을 `Misconfigured`로 끝내는 명시적 unavailable provider를 선택한다.
  일회성 checkout을 위해 기존 등록 API를 fake 성공으로 대체하지 않는다.
- Authorization은 UTF-8 `secretKey + ":"`를 Base64 인코딩한 Basic header다.
- 승인 `POST /v1/payments/confirm`, 조회 `GET /v1/payments/{paymentKey}` 또는
  `GET /v1/payments/orders/{providerOrderId}`, 취소
  `POST /v1/payments/{paymentKey}/cancel`만 구현한다.
- 전액 취소는 `cancelAmount`를 보내지 않고, 부분 취소만 snapshot이 정한 현금 금액을 보낸다.
  기존 Refund/SettlementAdjustment/Point restoration owner와 배분 규칙을 바꾸지 않는다.
- Adapter 결과는 Approved, Declined/TerminalFailed, RetryableFailed, Unknown의 닫힌 합으로
  번역한다. 등록되지 않은 Provider code는 fail-closed다. local/test scripted adapter는
  명시적 profile 전용이며 운영 fallback이 아니다.

### 공개 상태와 frontend

- `GET /api/v1/payments/{paymentId}`는 owner에게 `READY`, `APPROVING`, `APPROVED`, `FAILED`,
  `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`와 금액·통화·시각·correlation만 투영한다.
- React+TypeScript 앱은 `/app`, `/store`, `/ops` route boundary를 갖고 runtime OpenAPI에서
  타입을 생성한다. 제품 build는 fixture, in-memory 성공, stale/local fallback을 포함하지 않는다.
- success route는 URL query를 메모리에 읽은 직후 history에서 제거하고 server confirmation을
  한 번 요청한 다음 Payment status를 refetch한다. reload/back/multi-tab은 같은 Payment와
  idempotency key에 수렴한다.

## Alternatives Considered

### Payment Widget

결제수단 UI와 약관을 위젯으로 통합하기 쉽지만 제공된 디자인의 단일 결제 CTA와 Standard
Payment Window 요구보다 범위가 넓다. backend one-time 계약은 호환되지만 이번 제품으로
선택하지 않았다.

### 별도 CheckoutSession Aggregate

인증 수명이 Payment보다 짧다는 의미를 잘 표현하지만 Order당 활성 session과 Payment의
멱등·reconciliation·환불 reference를 중복 소유하게 된다.

### 기존 payment-confirmations에 paymentMethodId 대신 callback 필드 추가

파일 변경은 작지만 Provider 호출 전에 내구 준비 resource가 없어 server-authoritative SDK 입력,
다중 탭 replay와 callback mix-up을 안전하게 방어할 수 없다.

### 자동결제와 PaymentMethod 유지

기존 코드 재사용은 크지만 BeanFlow의 일회성 주문을 구독형 billing 계약에 맞추며 저장 카드라는
거짓 UX를 만든다. ADR-078의 해당 결정을 Supersede한다.

## Rationale

Payment는 이미 주문별 승인·불명·reconciliation·late approval과 Refund의 기준 fact를 소유한다.
이를 browser auth 전의 READY 상태까지 확장하면 새 owner 없이 one-time lifecycle을 완전하게
표현하고 기존 정산·포인트 불변식을 유지할 수 있다. prepare/callback을 별도 local transaction으로
두면 외부 지연과 결과 불명을 성공으로 위장하지 않는다.

## Consequences

- Payment의 one-time snapshot과 nullable legacy `paymentMethodId`를 구분하는 migration이 필요하다.
- 기존 저장 결제수단 API는 호환을 위해 남지만 active checkout route와 UI에서 사용하지 않는다.
- success callback은 10분 Provider 인증 유효 시간 안에 confirm해야 하므로 즉시 claim하고,
  실패하면 고객에게 새 성공을 약속하지 않은 채 status polling/recovery를 제공해야 한다.
- test key가 없으면 실제 Toss auth/confirm/cancel smoke는 Blocked로 남고 HTTP fault/local-demo 증거로
  대체했다고 주장하지 않는다.

## Verification

- prepare same-key replay, cross-payload, 한 Order 동시 prepare와 DB unique
- callback amount/orderId/paymentKey tampering, owner mix-up, replay와 multi-tab
- fail callback의 Provider confirm 0회
- confirm timeout/response loss, Provider success 뒤 Tx C failure와 query convergence
- 만료 뒤 late approval의 void/refund 및 manual review
- customer full cancellation과 owner partial refund의 provider key·금액·멱등성
- SettlementAdjustment, Point restoration/recovery와 기존 snapshot tie-out
- one-time path의 PaymentMethod repository/Port 호출 0회
- profile/key guard, Basic colon auth, secret/paymentKey redaction과 HTTP fault matrix
- mobile/keyboard/focus/status announcement, reload/back과 console/network audit

## Implementation Evidence (2026-08-10)

- 공개 API 개별 연동 test key 쌍으로 `toss-sandbox-runtime`이 `local,toss-sandbox`를 활성화하고
  health `UP`까지 기동했다. Widget key 쌍은 SDK의 `NotSupportedWidgetKeyError` 관측 뒤 startup
  guard가 더 이르게 거부하도록 고정했다.
- 실제 Toss V2 Payment Window가 4,500원과 9,000원 서버 snapshot을 표시했다. 국내 공개 테스트
  카드가 없으므로 개인 카드 정보를 사용하지 않고 Toss가 제공하는 V2 `sandbox.paymentResult`
  인증 시뮬레이션을 브라우저 검증에만 임시 적용했다. 이 옵션은 제품 source에 남기지 않았다.
- Toss가 발급한 테스트 callback을 BeanFlow가 server-to-server confirm해 두 Payment를
  `APPROVED`로 확정했다. 성공 query는 메모리에 읽힌 직후 browser history에서 제거됐고, 정리된
  URL 새로고침은 owner status query로 같은 승인 결과에 수렴했다.
- 4,500원 매장 수락 timeout 전액 취소, 9,000원 결제의 4,500원 부분 취소와 남은 4,500원 취소가
  모두 Provider `DONE`과 내부 Refund `SUCCEEDED`로 끝났다. Provider 직접 조회는
  `PARTIAL_CANCELED`, `balanceAmount=0`, 두 cancel 각각 4,500원을 반환했다.

## Metrics

- `beanflow.payment.checkout.prepare.count{outcome}`
- `beanflow.payment.confirm.attempts{provider,outcome}`
- `beanflow.payment.confirm.callback_rejected.count{reason}`
- `beanflow.payment.reconciliation.count{kind,outcome}`

ID, key, amount, raw Provider code와 callback query는 metric tag로 사용하지 않는다. 실제 성능·성공률은
아직 측정하지 않았다.

## Revisit Conditions

결제수단 UI를 상점 계약으로 운영할 필요가 생기거나, Payment Widget/BrandPay가 제품 요구가 되거나,
Toss webhook이 승인 결과의 검증된 source로 도입되거나, one-time 준비 시도 재생성이 필요해질 때
owner와 API를 다시 검토한다.

## Related Decisions

- BR-03, BR-14, BR-15, BR-25, BR-29, BR-33
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-037](ADR-037-customer-cancellation-refund-reconciliation-budget.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
- [ADR-078](ADR-078-toss-payments-sandbox-gateway-adapter.md)
- [ADR-079](ADR-079-payment-method-token-management.md)
