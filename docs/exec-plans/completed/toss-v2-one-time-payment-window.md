# Toss V2 Standard Payment Window 일회성 결제 완성

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/payment-confirmation-and-reconciliation.md`
> **Completed-At:** `2026-08-10`

이 ExecPlan은 `.agent/PLANS.md`와 ADR-080을 따른다. 진행 중 `Progress`, `Surprises &
Discoveries`, `Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신한다.

## Purpose / Big Picture

고객이 서버가 준비한 주문 금액으로 BeanFlow checkout에서 Toss V2 Standard Payment Window를
열고, 인증 callback을 서버가 검증·승인한 뒤 주문 추적과 취소/환불까지 실제 runtime API로
이어지게 한다. 저장형 PaymentMethod와 billing은 active checkout에서 제거한다. 결과가 불명확한
승인과 취소는 query reconciliation 또는 manual review로 남고 성공처럼 보이지 않는다.

## Current State

- `main`과 `origin/main`은 `1bbbe8f`로 같고 시작 worktree는 clean했다.
- runtime 결제 API는 server-owned `payment-attempts`, callback `confirmations`와 owner status query를
  제공하고 legacy `payment-confirmations` mapping은 제거됐다.
- Payment/OneTimePaymentAttempt는 `READY → CONFIRMING/APPROVING`과 승인/UNKNOWN/query
  reconciliation/late void-refund 경계를 구현한다.
- PaymentMethod lifecycle과 runtime CRUD는 보존되지만 one-time checkout에서 조회되지 않는다.
- ADR-078과 기존 active Toss plan의 billing 방식은 ADR-080으로 Superseded됐다.
- V38이 one-time attempt schema와 constraints를 추가했고 migration writer lane을 해제했다.
- `frontend/`에 React 19+TypeScript, Runtime OpenAPI 생성 client, `/app`·`/store`·`/ops`, 제공 디자인
  token과 BeanFlow logo 자산이 있다.
- 전체 Gradle build, frontend checks, local HTTP smoke와 in-app browser 검증이 통과했다.
- API 개별 연동 Toss test client/secret key로 실제 sandbox auth/confirm/provider query와
  full·partial cancel을 통과했다. 키와 paymentKey는 증거에 복사하지 않았다.

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

- 이 plan은 migration writer로 V37 뒤 V38 하나를 썼고 완료 시 writer lease를 해제했다.
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
- customer store/menu/slot/order/checkout/callback/tracking과 ops refund/compensation의 supported journey
- checkout에서 saved card/wallet/add method 제거, Toss 일회성 결제 하나만 표시
- callback exact binding 유지, submit lock, reload/back/multi-tab replay와 live status refetch
- product bundle에 fixture/fake success/secret 없음; local demo data는 별도 test support

## Milestones

1. 결정·계약: ADR-078/079 amendment, BR-33, ADR-080, active plan과 target API를 확정한다.
2. Payment attempt: 실패하는 domain/DB/API tests부터 작성하고 V38, prepare/status/callback claim을 구현한다.
3. Toss adapter: confirm/query/cancel와 Basic auth, key/profile guard, redaction, HTTP fault tests를 구현한다.
4. Frontend foundation: runtime generated client, routes, design tokens/assets와 real loading/error surfaces를 만든다.
5. Standard Window: prepare/SDK/success/fail/status polling과 replay UX를 구현한다.
6. E2E: approval/fail/tamper/mix-up/timeout/Tx C recovery/full·partial refund/settlement/points를 검증한다.
7. Release: local-demo/browser/accessibility/clean build, diff/security/fallback scan과 final docs/evidence.

각 milestone은 관련 테스트를 실제로 통과하기 전에 다음 milestone 완료로 기록하지 않는다.

## Required Validation

- domain, service, PostgreSQL migration/repository/concurrency/idempotency/fault tests
- Payment/Refund/Cancellation/Reconciliation/Settlement/Loyalty scoped suites
- OpenAPI parity, Spring Modulith, ArchUnit, `clean build`
- frontend typecheck/unit/build/browser E2E와 mobile/keyboard/accessibility
- secret/paymentKey/fallback/fixture scan, production bundle/source-map inspection
- Docker PostgreSQL/security local-demo smoke
- Toss test key가 있으면 auth/confirm/query/full·partial cancel; 없으면 Not run과 정확한 rerun command

## Progress

- [x] (2026-08-10) 시작 audit, 기준선 test, 공식 Toss V2 계약과 디자인 zip 검토
- [x] (2026-08-10) ADR-080, BR-33와 implementation-ready ExecPlan 결정
- [x] (2026-08-10) Payment one-time attempt schema/domain/API와 V38
- [x] (2026-08-10) Toss confirm/query/cancel adapter와 fault/profile guard
- [x] (2026-08-10) React/TypeScript route와 Standard Payment Window callback UX
- [x] (2026-08-10) correctness/accessibility/local-demo/browser 검증
- [x] (2026-08-10) 실제 Toss Payment Window auth/confirm/provider query/full·partial cancel 검증
- [x] (2026-08-10) sandbox runtime profile 합성, API/Widget key guard와 callback URL history 정리 회귀 수정
- [x] (2026-08-10) 최종 문서와 release evidence

## Surprises & Discoveries

- ADR-078은 billing이 일회성 제품에 맞지 않음을 이미 적었지만 active plan은 여전히 그 구현을
  기다리고 있었다. 이 plan이 billing 경로를 대체한다.
- 제공 디자인 checkout의 지갑·저장 카드 UI는 새 제품 결정과 충돌한다. 레이아웃과 디자인 언어는
  유지하되 해당 선택지는 Toss 일회성 결제 안내로 교체한다.
- clean build가 신규 PNG를 기존 repository secret scan의 unknown binary로 탐지했다. 검사를 끄지 않고
  BeanFlow logo 세 경로만 명시 allowlist로 추가했다.
- local smoke가 복수 환불의 Provider reference 충돌과 one-time UNKNOWN lookup의 reference 선택 오류를
  발견했다. Provider idempotency key 기반 reference와 transaction-reference 우선 lookup으로 수정했다.
- `toss-sandbox` 단독 기동은 datasource·OIDC·notification을 제공하지 않고, `local,toss-sandbox`를
  직접 합치면 legacy PaymentMethod provider safety guard가 scripted overlap으로 기동을 막았다.
  `toss-sandbox-runtime` group과 명시적 unavailable lifecycle provider로 one-time checkout만 열었다.
- 처음 제공된 `test_gck_`/`test_gsk_`는 Payment Widget 키라 Standard Payment Window가
  `NotSupportedWidgetKeyError`로 거부했다. API 개별 연동 `test_ck_`/`test_sk_`만 startup에서 허용한다.
- 실제 callback에서 paymentKey query가 승인 뒤 URL history에 남는 ADR-080 위반을 발견했다.
  layout effect로 즉시 제거하고 clean URL reload는 owner status query로 복구하도록 수정했다.
- Toss 공식 문서는 국내 공개 테스트 카드번호를 제공하지 않는다. 개인 결제정보를 사용하지 않기 위해
  V2 공식 `sandbox.paymentResult=SUCCESS`를 검증 브라우저에만 임시 적용하고 source에서는 원복했다.

## Decision Log

- (2026-08-10) 별도 CheckoutSession 대신 Payment가 attempt를 소유한다. 기존 reconciliation과
  Refund reference를 재사용하고 owner 중복을 피하기 위함이다.
- (2026-08-10) public client key는 server config endpoint가 제공한다. frontend와 server의 서로 다른
  key source drift를 막되 실제 MID pair 오류는 Provider가 fail-closed로 판정한다.
- (2026-08-10) migration V38 writer lane을 이 plan이 소유한다. 다른 active migration plan과 동시에
  실행하지 않는다.
- (2026-08-10) local actual-Toss 실행은 `toss-sandbox-runtime` group으로만 구성한다. 기존
  PaymentMethod lifecycle은 fake 성공 대신 명시적 `Misconfigured`로 닫는다.

## Outcomes & Retrospective

Payment가 일회성 attempt owner가 되어 PaymentMethod/billing 경로 없이 서버 canonical snapshot으로
Toss V2 Standard Payment Window를 준비한다. callback은 exact binding과 stable Provider key를 고정하고
confirm/query/cancel은 DB transaction 밖에서 실행된다. React 고객·매장·운영 화면은 fixture fallback 없이
Runtime OpenAPI client로 같은 API를 사용한다.

`./gradlew clean build --stacktrace --no-daemon`은 626 tests(1 skipped), frontend typecheck·9 unit tests·production/Sites
build와 production dependency audit는 통과했다. local HTTP smoke는 callback replay/tamper, 매장 완료,
부분·전액 환불과 `UNKNOWN → APPROVED` query 복구를 통과했고 browser는 결제 완료 새로고침과 주문
추적을 확인했다. 상세 결과는 [release evidence](../../quality/toss-one-time-payment-window-release-evidence.md)에
있다.

실제 Toss sandbox에서 4,500원과 9,000원 결제를 server-to-server confirm했고, 정리된 success URL
새로고침과 owner status 조회가 `APPROVED`로 수렴했다. 4,500원 전액 취소와 9,000원의 4,500원
부분 취소·잔액 4,500원 취소는 모두 내부 `SUCCEEDED`였고, Toss 직접 조회는 잔액 0원과 두 `DONE`
취소를 반환했다. 지연 Provider 부하·connection pool·worker 처리량은 별도 측정 대상으로 남긴다.
