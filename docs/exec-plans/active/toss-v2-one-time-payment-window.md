# Toss V2 Standard Payment Window 일회성 결제 완성

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/payment-confirmation-and-reconciliation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`와 ADR-080을 따른다. 진행 중 `Progress`, `Surprises &
Discoveries`, `Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신한다.

## Purpose / Big Picture

고객이 서버가 준비한 주문 금액으로 BeanFlow checkout에서 Toss V2 Standard Payment Window를
열고, 인증 callback을 서버가 검증·승인한 뒤 주문 추적과 취소/환불까지 실제 runtime API로
이어지게 한다. 저장형 PaymentMethod와 billing은 active checkout에서 제거한다. 결과가 불명확한
승인과 취소는 query reconciliation 또는 manual review로 남고 성공처럼 보이지 않는다.

## Current State

- `main`과 `origin/main`은 `1bbbe8f`로 같고 시작 worktree는 clean했다.
- runtime 결제 API는 `POST /orders/{orderId}/payment-confirmations`에서 `paymentMethodId`를 받아
  token snapshot으로 scripted adapter를 즉시 호출한다.
- Payment는 `APPROVING`부터 시작하고 승인/UNKNOWN/reconciliation/late void/refund 경계는 구현돼 있다.
- PaymentMethod lifecycle과 runtime CRUD는 구현돼 있으나 one-time checkout 인증 소스로 사용하지 않는다.
- ADR-078과 기존 active Toss plan의 billing 방식은 ADR-080으로 Superseded됐다.
- 최신 migration은 V37이다. 이 plan이 ADR-072 migration writer lane을 획득하고 V38을 쓴다.
- frontend toolchain과 `package.json`은 없다. 제공 zip에는 420px 고객 checkout, 1280px console,
  디자인 토큰, Lucide 언어와 BeanFlow logo 자산이 있다.
- Node 25, npm 11, pnpm과 Docker는 사용 가능하다. 기준선 `./gradlew test`는 통과했다.
- Toss test client/secret key가 없어 실제 sandbox smoke만 Blocked 후보며 구현·HTTP fault·local-demo는
  진행 가능하다.

## Business Rules and Invariants

- Standard Payment Window one-time CARD만 구현한다. Widget/billing/BrandPay/virtual account/payout은
  넣지 않는다.
- amount/providerOrderId/customerKey/orderName은 server snapshot이 canonical하다.
- Payment가 attempt owner다. PaymentMethod lookup/Port는 one-time path에서 호출하지 않는다.
- callback은 owner, Payment, provider order, amount와 paymentKey binding을 검증한 뒤 claim한다.
- Provider 호출은 DB transaction 밖이다. confirm 시작 후 불명 결과는 새 confirm이 아니라 query한다.
- 취소/부분 환불은 기존 allocation, Point, Settlement owner를 유지한다.
- fake/scripted 성공은 test 또는 명시적 local profile에만 존재하고 prod fallback은 없다.

## Architecture and Transaction Boundaries

1. Tx A는 Order를 잠그고 Payment READY, prepare idempotency, one-time snapshot과 approval lookup work를
   저장한다.
2. 브라우저는 서버 snapshot과 public client key로 Toss SDK V2 `payment({customerKey})`의
   `requestPayment({method:"CARD", ...})`를 호출한다.
3. Tx B는 Payment를 잠그고 callback exact binding과 replay를 검증한 뒤 stable Provider key와
   paymentKey를 저장하고 APPROVING을 claim한다.
4. transaction 밖에서 confirm하고 Tx C가 기존 주문 자원·Payment·응답·Audit를 원자 확정한다.
5. timeout, response loss와 Tx C 실패는 lookup worker가 paymentKey/orderId로 조회해 같은 Tx C로
   수렴한다. late approval은 Order를 되살리지 않는다.

## Migration Ownership

- 이 plan은 active migration writer이며 최신 main V37 뒤 V38 하나를 쓴다.
- `payment_payment.payment_method_id`는 legacy token payment에는 필수지만 one-time에는 null을 허용한다.
- 1:1 `payment_one_time_attempt`가 provider order/customer/order name/amount/currency, paymentKey,
  callback hash, Provider idempotency key와 claim/state를 보존한다.
- provider order와 paymentKey unique, positive KRW, 상태별 required/null CHECK, immutable prepare field를
  DB constraint/trigger로 보호한다.
- 기존 rows는 추정해 one-time으로 바꾸지 않는다. legacy token path로 그대로 보존한다.

## API Contract

- `GET /payment-config`
- `POST /orders/{orderId}/payment-attempts`
- `POST /payments/{paymentId}/confirmations`
- `GET /payments/{paymentId}`
- 기존 `POST /payments/{paymentId}/refunds`와 cancellation API는 one-time paymentKey를 사용한다.
- 구현과 controller contract test가 생길 때 target/runtime OpenAPI를 함께 승격한다.

## Frontend Scope

- 신규 React+TypeScript 앱 하나와 `/app`, `/store`, `/ops` route boundary
- runtime OpenAPI generated client, auth/loading/empty/error/unknown states
- zip의 로고, warm crema/espresso/caramel tokens, 420px checkout과 console navigation 언어
- customer store/menu/slot/order/checkout/callback/tracking/cancel/points의 supported journey
- checkout에서 saved card/wallet/add method 제거, Toss 일회성 결제 하나만 표시
- callback URL 즉시 정리, submit lock, reload/back/multi-tab replay와 live status refetch
- product bundle에 fixture/fake success/secret 없음; local demo data는 별도 test support

## Milestones

1. 결정·계약: ADR-078/079 amendment, BR-33, ADR-080, active plan과 target API를 확정한다.
2. Payment attempt: 실패하는 domain/DB/API tests부터 작성하고 V38, prepare/status/callback claim을 구현한다.
3. Toss adapter: confirm/query/cancel와 Basic auth, key/profile guard, redaction, HTTP fault tests를 구현한다.
4. Frontend foundation: runtime generated client, routes, design tokens/assets와 real loading/error surfaces를 만든다.
5. Standard Window: prepare/SDK/success/fail/status polling과 replay UX를 구현한다.
6. E2E: approval/fail/tamper/mix-up/timeout/Tx C recovery/full·partial refund/settlement/points를 검증한다.
7. Release: local-demo/browser/accessibility/clean build, diff/security/fallback scan, final docs/evidence, push/PR.

각 milestone은 관련 테스트를 실제로 통과하기 전에 다음 milestone 완료로 기록하지 않는다.

## Required Validation

- domain, service, PostgreSQL migration/repository/concurrency/idempotency/fault tests
- Payment/Refund/Cancellation/Reconciliation/Settlement/Loyalty scoped suites
- OpenAPI parity, Spring Modulith, ArchUnit, `clean build`
- frontend lint/typecheck/unit/component/build/browser E2E와 mobile/keyboard/accessibility
- secret/paymentKey/fallback/fixture scan, production bundle/source-map inspection
- Docker PostgreSQL/security local-demo smoke
- Toss test key가 있으면 auth/confirm/query/full·partial cancel; 없으면 Blocked와 정확한 rerun command

## Progress

- [x] (2026-08-10) 시작 audit, 기준선 test, 공식 Toss V2 계약과 디자인 zip 검토
- [x] (2026-08-10) ADR-080, BR-33와 implementation-ready ExecPlan 결정
- [ ] Payment one-time attempt schema/domain/API
- [ ] Toss confirm/query/cancel adapter
- [ ] React/TypeScript route와 Standard Payment Window
- [ ] correctness/accessibility/local-demo/sandbox 검증
- [ ] 최종 문서, push와 PR

## Surprises & Discoveries

- ADR-078은 billing이 일회성 제품에 맞지 않음을 이미 적었지만 active plan은 여전히 그 구현을
  기다리고 있었다. 이 plan이 billing 경로를 대체한다.
- 제공 디자인 checkout의 지갑·저장 카드 UI는 새 제품 결정과 충돌한다. 레이아웃과 디자인 언어는
  유지하되 해당 선택지는 Toss 일회성 결제 안내로 교체한다.

## Decision Log

- (2026-08-10) 별도 CheckoutSession 대신 Payment가 attempt를 소유한다. 기존 reconciliation과
  Refund reference를 재사용하고 owner 중복을 피하기 위함이다.
- (2026-08-10) public client key는 server config endpoint가 제공한다. frontend와 server의 서로 다른
  key source drift를 막되 실제 MID pair 오류는 Provider가 fail-closed로 판정한다.
- (2026-08-10) migration V38 writer lane을 이 plan이 소유한다. 다른 active migration plan과 동시에
  실행하지 않는다.

## Outcomes & Retrospective

구현과 검증 완료 후 실제 결과, 남은 risk, sandbox evidence와 Revisit Conditions를 기록한다.

