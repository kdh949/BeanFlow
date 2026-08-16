# 고객 P0 화면을 Session과 실제 거래 API에 연결한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/productization-30-customer-account-and-login.md`, `docs/exec-plans/completed/productization-50-customer-order-read-model.md`, `docs/exec-plans/completed/productization-70-customer-store-discovery.md`
> **Completed-At:** `2026-08-16`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

고객이 Access Token이나 UUID를 입력하지 않고 가입·로그인해 매장을 찾고, 메뉴·픽업 시간을 고르고,
결제하고, 주문을 추적·조회·취소·재주문하며 실제 PointAccount를 확인하게 한다. Capability Map의 고객
P0 13화면을 모두 API와 연결하고 공통 loading/empty/error 상태를 화면에서 검증한다.

## Current State

- React 19, React Router 7, `openapi-fetch`, Toss SDK와 Vitest/Testing Library가 설치돼 있다.
- `frontend/src/api/client.ts`는 `localStorage`의 수동 Bearer token을 모든 API에 붙인다.
- `Shells.tsx`는 Token Editor를 노출하고 고객 화면은 `CustomerPages.tsx`에 집중돼 있다.
- 결제 callback과 일부 주문·매장 API 호출은 있지만 UUID 입력 또는 route state에 의존한다.
- `productization-30/50/70`이 Session, 내 주문 Projection과 매장 탐색 API를 제공한다.
- PointAccount 조회는 account UUID 경로, 재주문은 source Order UUID 경로만 있다.

## Definitions

- **Customer session client:** Bearer header를 만들지 않고 same-origin Cookie와 customer CSRF token을
  쓰는 `openapi-fetch` client다.
- **Submit intent:** 사용자가 명시적으로 만든 한 번의 논리 명령과 그 fingerprint·Idempotency-Key다.
- **Client cart:** 한 브라우저의 한 매장에 대한 menu/option ID·수량 선택이다. 서버 Aggregate가 아니다.
- **Payment recovery:** confirm 응답을 잃은 뒤 같은 Payment를 조회해 결과에 수렴하는 흐름이다.
- **Terminal payment failure:** Provider가 명시적으로 거절했고 기존 attempt가 더 이상 승인될 수 없는
  상태다. timeout·응답 유실·202는 terminal failure가 아니다.

## Scope

### In Scope

- customer Session/CSRF API client와 인증 route guard
- 가입·로그인·내 정보·로그아웃 화면
- 홈, 매장 찾기·상세, 한 매장 client cart
- 주문 생성, Toss Payment Window, callback·offline recovery
- 주문 추적·내역·취소 화면과 `allowedActions`
- actor-scoped `GET /me/points`, `GET /me/point-transactions`
- 주문번호 기반 `POST /me/orders/{orderReference}/reorders`
- 기능 단위 디렉터리 분리와 고객 P0 13화면 상태 테스트
- 고객 화면에서 Token Editor·UUID 입력 제거

### Non-goals

- Server Cart·다중 기기 장바구니 동기화
- 저장 결제수단을 checkout 승인 원천으로 사용
- 휴대전화 OTP, 비밀번호 재설정, 소셜 로그인
- 쿠폰 발급·알림함·저장 결제수단 관리(P1)
- 선불 Wallet
- React 전역 상태관리·query cache 라이브러리 추가

## Business Rules and Invariants

1. customer 요청에 Authorization Bearer header를 붙이지 않는다. HttpOnly Session Cookie는 JS로 읽지 않는다.
2. unsafe method는 customer CSRF cookie에 대응하는 header가 없으면 전송하지 않는다.
3. customer ID, account ID와 order UUID를 입력 form이나 request body에서 받지 않는다.
4. cart는 한 매장만 허용하고 가격·판매 가능·재고·슬롯·혜택은 주문 생성에서 서버가 다시 검증한다.
5. `allowedActions`만 버튼 활성화 근거로 사용한다. 프론트가 Order 상태 머신을 복제하지 않는다.
6. 결제 confirm timeout·network error·202 뒤에는 confirm을 새 key로 자동 재호출하지 않고 기존
   `/payments/{paymentId}`를 조회한다.
7. 같은 submit intent의 network retry만 Idempotency-Key를 재사용한다. form 변경·성공·명시적 포기는
   key를 회전한다.
8. `/me/points`는 BR-42의 실제 PointAccount를 조회한다. account 누락·장애를 0으로 표시하지 않는다.
9. 로그아웃은 customer credential state와 client cart·미해결 submit intent를 지운다. 운영자 OIDC
   state나 점주 Session을 함께 지우지 않는다.

## Architecture and Transaction Boundaries

```text
frontend/src/features/
  auth/customer/
  discovery/
  ordering/
  payment/
  loyalty/

customerApi
  credentials: same-origin
  safe request: Accept only
  unsafe request: customer CSRF cookie → X-BEANFLOW-CSRF
  no Authorization middleware

Customer routes
  loader / boundary → GET /me
    200: feature route
    401: login route + sanitized same-origin return path
    403/503: forbidden/dependency state, login으로 위장하지 않음
```

- Plan 20은 `GET /auth/customer/csrf`와 JS-readable CSRF cookie
  `BEANFLOW_CUSTOMER_XSRF`, header `X-BEANFLOW-CSRF`를 제공한다. Session Cookie는 별도 이름이며
  HttpOnly다. CSRF cookie에는 인증 정보가 없다.
- client cart는 versioned JSON으로 localStorage에 보관하되 ID·수량·표시 snapshot만 둔다. server
  price, coupon, point balance 또는 결제 정보의 source of truth로 사용하지 않는다. decode 실패는 cart
  손상 안내 후 사용자가 명시적으로 비우게 하며 조용히 빈 cart로 덮지 않는다.
- backend actor-scoped Loyalty facade는 customer ID로 PointAccount를 찾아 기존 Query Service를
  호출한다. 누락은 `POINT_ACCOUNT_INTEGRITY_FAILURE` 503이다. legacy account UUID endpoint는 운영
  support 경로를 위해 유지한다.
- reorder facade는 owner + publicReference로 source Order를 찾고 기존 FastReorder transaction에 내부
  UUID를 전달한다. 상태·가격·재고·slot 검증은 새로 복제하지 않는다.

## Alternatives Considered

- 기존 Bearer token client 유지: Session 결정과 Token Editor 제거 목표에 맞지 않아 기각한다.
- 하나의 API client가 path를 보고 Cookie/JWT를 추론: 새 endpoint 오분류가 인증정보 오전송으로 이어져
  customer/merchant/operations client를 명시적으로 분리한다.
- Redux/query cache 추가: 현재 화면·상태 규모에서 새 production dependency와 stale 정책이 불필요하다.
- Server Cart: 예약·동기화·만료라는 새 Aggregate가 P0 checkout보다 크므로 기각한다.
- 결제 network error 뒤 confirm 재호출: Provider 중복 승인 위험 때문에 기각한다.

## Failure Semantics

- `/me` 401은 login, 403은 actor mismatch, 503은 인증/Session dependency failure로 각각 렌더링한다.
- signup duplicate 409는 login failure와 분리해 사용자명 수정 상태로 표시한다.
- 검색·주문 목록·포인트 조회 실패를 empty/0으로 표시하지 않는다.
- 주문 생성의 가격·재고·slot conflict는 cart를 자동 수정하지 않고 서버 detail과 재확인 행동을 보여준다.
- Toss fail callback은 confirm을 호출하지 않는다. success callback 검증 실패는 새 결제를 만들지 않는다.
- Payment 조회 202/UNKNOWN은 처리 중이며 backoff polling한다. 명시적 terminal failure일 때만 새 주문
  시작 행동을 노출한다.
- offline recovery polling은 page lifecycle에서 하나만 실행하고 online 복귀 이벤트 중복으로 여러
  loop를 만들지 않는다.

## Data and Migration

DB migration은 없다. 이 plan의 backend 추가는 기존 customer ID, PointAccount unique key,
publicReference와 FastReorder use case를 사용한다.

브라우저 저장 key는 schema version을 포함한다.

```text
beanflow.customer.cart.v1
beanflow.customer.submit-intent.<operation>
```

Session, access token, password, provider key와 point/account UUID를 localStorage에 저장하지 않는다.

## API and Event Contracts

새 actor-scoped facade:

```http
GET  /api/v1/auth/customer/csrf
GET  /api/v1/stores/{storeId}
GET  /api/v1/me/points
GET  /api/v1/me/point-transactions?cursor=&limit=
POST /api/v1/me/orders/{orderReference}/reorders
```

- `/me/points`는 `{ availablePointsKrw, recoveryPendingKrw, expiring[] }`를 반환하고 내부 accountId를
  반환하지 않는다. expiring은 PointLot의 `(expiresAt, amountKrw)` 최소 Projection이며 조회 실패를
  생략하지 않는다.
- transaction page는 signed cursor를 쓰고 내부 pointAccountId를 cursor filter 밖으로 노출하지 않는다.
- reorder 요청은 source ID나 가격을 받지 않고 기존 Idempotency-Key 계약을 사용한다.
- `GET /stores/{storeId}`는 검색·추천이 이미 쓰는 `CustomerStore`를 그대로 반환한다. 좌표를 받지
  않으므로 `distanceMeters`는 없다. 고객이 보면 안 되는 매장과 없는 매장은 모두 404이고, 이름을
  만들어 내지 않는다. 이 endpoint가 없던 동안 화면은 이름을 navigation state에서 받았고, URL로 열면
  일반 명칭이 남았다.
- 기존 주문 생성·payment attempt/confirmation/status endpoint는 브라우저가 API 응답·callback에서 받은
  opaque ID로 호출할 수 있지만, 어떤 화면에도 ID 입력 field를 만들지 않는다.
- 이벤트 계약 변경 없음.

## P0 Customer Screen Coverage

| 화면 | owner capability | 이 plan의 완료 증거 |
|---|---|---|
| `5a 첫 진입` | Plan 30 | 가입·로그인 route와 401/409/429 상태 |
| `5e 오프라인` | Payment 기존 capability | confirm 재호출 없는 status recovery test |
| `1a 홈` | Plans 50, 70 | active Order + recommendations 상태 |
| `1b 매장 찾기` | Plan 70 | search/nearby/permission denied 상태 |
| `1c 매장 상세` | ADR-076 기존 API | menu·sold-out·slot closed 상태 |
| `1d 주문 추적` | Plan 50 | publicReference·pickupNumber·allowedActions |
| `4a 장바구니` | client cart | 한 매장·손상·server revalidation 상태 |
| `2a 결제` | ADR-080 기존 API | Toss prepare/callback/recovery |
| `4c 주문 내역` | Plan 50 | cursor·기간·empty/error 상태 |
| `4d 주문 취소` | Plan 50 + 기존 use case | 202와 refund progress 분리 |
| `4f 마이` | Plan 30 | `/me`·logout·credential clear |
| `3a 포인트` | Loyalty facade | 실제 0원·만료·원장·503 상태 |
| `3c 재주문` | ADR-077 facade | publicReference 재주문과 재검증 실패 |

## Milestones

1. customer/merchant/operations API client를 분리하고 customer Cookie+CSRF client 구현.
2. customer auth feature와 route guard, 가입·로그인·logout 화면 연결.
3. discovery/home/store detail feature를 Plans 50/70 API에 연결.
4. versioned one-store cart와 server validation conflict UI 구현.
5. Toss prepare/success/fail/offline recovery 상태 머신을 단일 hook/service로 분리.
6. order tracking/history/cancel을 publicReference·allowedActions에 연결.
7. actor-scoped points/reorder backend facade와 프론트 화면 구현.
8. `CustomerPages.tsx` 분해, 고객 Token Editor·UUID 입력·관련 CSS/테스트 제거.
9. target/runtime OpenAPI type 생성, Vitest 상태 matrix와 실제 browser smoke 검증.
10. `GET /stores/{storeId}` 추가와 매장 이름의 navigation state 의존 제거.

## Required Tests

- customer client가 Bearer를 붙이지 않고 unsafe method에만 정확한 CSRF header를 보내는지 검증.
- Session 401/403/503 route boundary와 logout 뒤 보호 route 차단.
- cart 한 매장 제약, schema 손상, 가격·재고·slot conflict에서 자동 금액 신뢰 부재.
- 결제 success callback replay, confirm network loss, 202/UNKNOWN, terminal decline와 offline/online 중복 event.
- network loss 뒤 Provider confirm call 수가 증가하지 않고 status GET만 반복되는지 검증.
- order 상태별 allowedActions와 실제 버튼, 202 취소와 환불 완료 분리.
- `/me/points` 실제 0원, PointAccount 손상 503, 원장 cursor 격리와 internal accountId 비노출.
- reorder가 publicReference ownership을 확인하고 현재 가격·판매·재고·slot을 재검증하는지 검증.
- loading/empty/success/validation/conflict/offline/retryable/terminal/401/403을 P0 화면별로 검증.
- DOM·route·request body에 수동 token·customer/order/account UUID 입력 field가 없는지 검증.
- 420px customer viewport에서 keyboard focus, label, error announcement와 reduced-motion 상태 검증.

## Validation Commands

```bash
cd frontend && npm test
cd frontend && npm run typecheck
cd frontend && npm run build
./gradlew test --tests '*Customer*' --tests '*PointAccount*' --tests '*FastReorder*' --tests '*OneTimePayment*'
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

## Observability

- customer route별 401/403/409/422/503와 error code 분포
- signup/login 성공·잠금·rate-limit 지표(기존 backend metric)
- payment recovery 상태·poll 횟수·UNKNOWN 해소 시간
- point facade 조회 지연·integrity failure 수
- reorder 검증 실패 사유
- Web Vitals는 측정값만 기록하고 목표 개선을 사전 주장하지 않는다.

## Documentation Updates

- [Capability Map](../../product/design-to-capability-map.md)의 고객 P0 evidence
- `docs/api/api-conventions.md`의 Session/CSRF client 계약
- `docs/security/authorization-matrix.md`
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml`
- `frontend/design-qa.md`
- `docs/operations/local-demo-runbook.md`

## Progress

Milestones 1–10을 구현과 검증까지 마쳤다. Milestone 9의 browser smoke에서 찾은 매장 단건 조회
계약 공백은 Milestone 10에서 메웠다. 매장 검색 화면만 demo seed 한계로 빈 결과 상태까지만
확인했다.

- 2026-08-15: runtime OpenAPI가 `CustomerPages`의 payment-attempt·payment-confirmation POST에
  `X-BEANFLOW-CSRF`를 요구함을 frontend typecheck/build에서 확인했다. 사용자 선택 A에 따라 이
  consumer 보정은 완료된 Plan 60에 선반영하지 않고, 이 plan의 Customer Session/CSRF client milestone에서
  공통 client와 두 call site를 함께 전환한다. Plan 70 dependency가 아직 active이므로 구현은 시작하지 않았고,
  그때까지 frontend 전체 typecheck/build는 이 두 건과 Plan 90 소유 한 건으로 실패한다.
- 2026-08-16: **Milestone 1 완료.** `customerApi`/`merchantApi`/`operationsApi`를 분리했다. customer
  client는 `Authorization`을 제거하고 unsafe method에 CSRF header가 없으면 네트워크 전에 차단한다.
  payment-attempt·payment-confirmation 두 call site를 공통 CSRF helper로 전환해 시작 전 red였던
  frontend typecheck/build/test가 green으로 돌아왔다.
- 2026-08-16: **Milestone 2 완료.** `GET /me`의 200/401/403/503을 각각 다른 화면 상태로 렌더링하는
  route guard와 가입·로그인·마이 화면을 붙였다. 로그아웃은 customer cart·submit intent·CSRF cookie만
  지우고 운영자 token은 유지한다. Token Editor는 콘솔 shell 전용으로 옮겼다.
- 2026-08-16: **Milestone 3 완료.** 홈은 `/me/orders?status=ACTIVE`와 `/me/store-recommendations`,
  매장 찾기는 `/stores/search`와 `/stores/nearby`를 사용하며 위치 권한 거부와 센서 불가를 구분한다.
- 2026-08-16: **Milestone 4 완료.** `beanflow.customer.cart.v1` versioned cart와 매장 상세를 구현했다.
  decode 실패는 손상 상태로 알리고 사용자가 직접 비운다. 주문 생성 conflict는 cart를 자동 수정하지 않는다.
- 2026-08-16: **Milestone 5 완료.** prepare/success/fail/offline recovery를 `usePaymentResolution`
  하나로 합쳤다. confirm은 page lifecycle당 한 번이고 이후에는 status GET만 backoff로 반복한다.
- 2026-08-16: **Milestone 6 완료.** 주문 추적·내역·취소를 `allowedActions`에 연결하고 202 취소와
  환불 진행 projection을 분리해 표시한다.
- 2026-08-16: **Milestone 7 완료.** `GET /me/points`, `GET /me/point-transactions`,
  `POST /me/orders/{orderReference}/reorders`를 추가하고 runtime OpenAPI parity를 통과시켰다.
- 2026-08-16: **Milestone 8 완료.** `CustomerPages.tsx`를 해체해 `features/{auth,discovery,ordering,payment,loyalty}`로
  옮기고 고객 화면의 Token Editor·UUID 입력을 제거했다. 남은 `pages/`는 콘솔 전용이다.
- 2026-08-16: **Milestone 9 완료.** OpenAPI 생성, Vitest 상태 matrix, typecheck, build와 backend
  검증에 더해 `scripts/demo`로 backend를 띄우고 420px 브라우저에서 로그인 → 홈 → 매장 상세 →
  장바구니 → 주문 생성 → 결제 준비 → 결제 결과 복구 → 주문 상세 → 취소 → 재주문 → 포인트 →
  로그아웃을 실제 HTTP로 확인했다. 이 과정에서 결제 결과 화면 결함 두 건과 주문 화면 표기 결함
  두 건을 찾아 고쳤고 각각 회귀 test를 남겼다(10 files, 98 tests).
- 2026-08-16: **Milestone 10 완료.** `GET /stores/{storeId}`를 추가하고 매장 상세와 장바구니가 매장
  이름을 navigation state나 저장된 문자열이 아니라 서버에서 읽게 했다. 매장 카드는 더 이상 이름을
  route state로 넘기지 않으므로 링크로 열든 URL로 열든 화면이 같다. 장바구니는 저장된 이름을
  fallback으로만 쓰고 서버 응답이 오면 그것을 쓴다. 없는 매장은 서버의 영어 문장 대신 고객용
  안내로 바꿨다.

## Surprises & Discoveries

- `CustomerOrderDetail`에 매장 식별자가 없어 재주문 화면이 필수 `pickupSlotId`를 고를 수 없었다.
  서버가 주는 opaque `storeId`를 응답에 추가해 해결했고 입력 field는 만들지 않았다(MD-2026-029).
- runtime OpenAPI의 `/me/orders/{orderReference}/cancellations`가 customer chain이 실제로 요구하는
  `X-BEANFLOW-CSRF`를 선언하지 않고 있었다. 이 plan에서 runtime 항목을 보정했다.
- `openapi-fetch`는 client 생성 시점의 `globalThis.fetch`를 캡처해 테스트에서 fetch 경계를 관찰할 수
  없었다. client가 호출 시점에 `fetch`를 조회하도록 바꿔 credential 경계를 실제 Request로 검증한다.
- 공용 `.spin` 애니메이션이 `prefers-reduced-motion`을 따르지 않아 결제·환불 대기 상태가 계속
  회전했다. reduced-motion에서 정지하도록 고쳤다.
- browser smoke에서만 드러난 결함 네 건을 찾았다. 모두 Vitest가 `StrictMode` 없이, 그리고 화면이
  실제로 받는 상태 조합 없이 렌더링해서 놓치고 있던 것들이다.
  1. 승인 상태 polling loop가 진행 여부를 component ref에 두어 effect가 다시 실행되면 취소된 이전
     실행의 flag 때문에 새 실행이 첫 조회에서 곧바로 멈췄다. 결제 결과 화면이 "승인하는 중"에서
     영구히 멈춰 고객이 결과를 볼 수 없었다. loop 상태를 effect 안으로 옮겼다.
  2. 성공 URL 화면이 `confirming`·`failed`·`pending`이 아닌 나머지를 모두 성공으로 그려, 승인되지
     않은 `READY`와 거절된 `FAILED`가 "결제가 완료됐어요"로 보였다. 두 상태를 분리했다.
  3. 취소·거절·만료·결제 전 주문에도 픽업 번호와 "픽업대에서 번호를 확인해 주세요."가 남았다.
     상태별로 감추거나 문구를 바꿨다.
  4. 환불 진행 badge와 결제 복구 badge가 공용 상태 어휘에 없는 값을 받아 `NOT_REQUIRED`,
     `SUCCEEDED` 같은 코드를 고객에게 그대로 노출했다. 두 자리에 우리말 label을 넘긴다.
- `READY`는 주문 board에서 "픽업 준비 완료"지만 결제에서는 "아직 미결제"다. 공용 `StatusBadge`가
  한 어휘로 두 lifecycle을 덮을 수 없어, 뜻이 갈리는 자리에서만 label을 덮어쓸 수 있게 했다.
- demo seed는 `discovery_store_search_term`을 채우지 않아 `/stores/search`가 항상 빈 결과를
  돌려준다. 화면은 이를 오류가 아니라 "검색 결과가 없어요"로 올바르게 표시했지만, 결과가 있는
  검색 화면은 이 환경에서 확인하지 못했다. 색인 재생성은 운영자 `STORE_BRAND_MANAGE` 권한 부여가
  필요하고 이는 이 plan 범위 밖이다.
- 고객이 볼 수 있는 매장 단건 조회 endpoint가 target·runtime OpenAPI 어디에도 없었다. 매장 상세를
  링크가 아니라 URL로 직접 열면 매장 이름을 가져올 곳이 없어 화면과 장바구니에 "매장"이라는
  일반 명칭이 남았다. 이름을 지어내지 않는 편이 맞지만 제품 공백이었고, Milestone 10에서
  `GET /stores/{storeId}`로 메웠다. `merchant_store_discovery_profile`에는 이름과 좌표만 있어
  주소·영업시간 같은 값은 이 응답에 넣을 수 없었다.
- 매장 이름을 route state로 넘기면 같은 화면이 도달 경로에 따라 다르게 보인다. 서버에서 읽게
  바꾼 뒤 state 전달을 없애, 링크·URL·새로고침이 모두 같은 화면이 되도록 했다.

- 기존 프론트 API client는 한 Middleware에서 모든 actor Bearer token을 주입하므로 Session 전환 시 client
  분리가 보안 경계의 일부다.
- 신규 고객 PointAccount가 자동 존재하지 않아 BR-42와 ADR-109에서 가입 원자 provisioning을
  선행 결정했다.
- runtime OpenAPI가 unsafe header를 required로 생성한 뒤 기존 manual `Idempotency-Key` call site가
  typecheck/build를 막았다. 두 customer call을 각자 보정하면 actor token fetch·refresh와 실패 의미론이
  분산되므로, 이 plan의 공통 Customer client에서 함께 전환한다. 그 대가로 이 plan 시작 전 frontend 전체
  build는 red이며, Plan 60의 backend·board 검증 성공으로 이를 build 완료로 해석하지 않는다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-12 | customer API는 HttpOnly Session + 별도 CSRF client를 사용 | [ADR-092](../../adr/ADR-092-hybrid-authentication.md), [ADR-094](../../adr/ADR-094-browser-session-security.md) |
| 2026-08-12 | cart는 한 매장 client state이며 server가 checkout에서 재검증 | [Capability Map](../../product/design-to-capability-map.md) |
| 2026-08-12 | PointAccount는 가입과 원자 생성하고 actor-scoped 경로로 조회 | [ADR-109](../../adr/ADR-109-customer-point-account-provisioning.md) |
| 2026-08-12 | payment network ambiguity에서는 confirm이 아니라 기존 status를 조회 | [ADR-007](../../adr/ADR-007-payment-idempotency-reconciliation.md) |
| 2026-08-15 | Customer `payment-attempt`·`payment-confirmation`의 CSRF consumer 보정은 이 plan의 Customer Session/CSRF client milestone이 소유하며, Plan 60에는 선반영하지 않는다 | [MD-2026-014](../../decisions/minor-decisions.md), [ADR-094](../../adr/ADR-094-browser-session-security.md) |
| 2026-08-16 | 고객 주문 상세에 서버가 주는 opaque `storeId`를 추가해 재주문 픽업 시간을 조회한다 | [MD-2026-029](../../decisions/minor-decisions.md) |

## Outcomes & Retrospective

실행한 검증과 결과다. 실행하지 않은 항목은 `Not run`으로 남긴다.

| 검증 | 결과 |
|---|---|
| `cd frontend && npm test` | 10 files, 103 tests 통과 |
| `cd frontend && npm run typecheck` | 통과 |
| `cd frontend && npm run build` | 통과 |
| `./gradlew test --tests '*Customer*' --tests '*PointAccount*' --tests '*FastReorder*' --tests '*OneTimePayment*'` | 통과 |
| `./gradlew test --tests '*RuntimeOpenApiParityTest*'` | 통과 |
| `PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh` | 통과(target 160 paths/169 operations, runtime 146/155) |
| `bash scripts/demo/start.sh && bash scripts/demo/seed.sh` | 통과(6단계 기동, 27행 삽입) |
| `bash scripts/demo/smoke.sh` | 통과(49 call, 적립·부분/전액 환불·UNKNOWN→APPROVED 복구 포함) |
| 실제 browser smoke(backend 포함, 420px) | 실행. 결함 4건 발견·수정. 아래 관측 참고 |
| `./gradlew test --tests '*Customer*' --tests '*Discovery*' --tests '*StoreCatalog*' --tests '*PointAccount*' --tests '*FastReorder*' --tests '*OneTimePayment*' --tests '*RuntimeOpenApiParityTest*'` | 통과 |
| `./gradlew spotlessCheck` | 통과(이 작업 중 발견한 기존 import 정렬 위반 1건 수정 후) |
| 매장 검색 결과가 있는 화면 | Not run(demo seed가 검색 색인을 채우지 않음) |
| Toss 결제창 이후 승인 흐름 | Not run(외부 결제 제공자 화면은 조작하지 않음) |

관측한 결과:

- 시작 시점의 알려진 red(frontend typecheck/build 실패 3건 중 이 plan 소유 2건)는 Milestone 1에서
  해소됐다. 나머지 1건은 Plan 90 소유였고 이 작업 범위에서 다시 나타나지 않았다.
- 고객 화면에서 수동 token·UUID 입력을 제거했고, 이를 회귀로 잡는 검증을
  `features/CustomerSurface.test.tsx`에 남겼다.
- 420px 브라우저에서 backend를 띄우고 확인한 것: `demo.customer` 로그인과 Session 회전 뒤 CSRF
  재발급, 홈의 진행 중 주문·추천 매장, 매장 상세의 품절 메뉴·품절 옵션 비활성화, 옵션 선택에 따른
  금액 갱신, 장바구니 저장과 픽업 시간 선택, `POST /orders` 201과 결제 마감 시각, 결제 준비
  200 뒤 Toss SDK 로드 실패를 조용히 넘기지 않고 오류로 표시하는 것, 결제 결과 화면의 미승인 상태,
  주문 목록의 진행 중·지난 주문 분리, 주문 상세 timeline, `allowedActions` 기반 취소와 재주문,
  재주문이 현재 가격으로 새 주문을 만들어 결제 화면으로 보내는 것, 포인트 화면의 적립 +100P와
  환불 회수 -50P 두 건, 로그아웃이 장바구니·요청 키·CSRF cookie만 지우고 콘솔 token은 남기는 것,
  보호 route가 `?next=`를 붙여 로그인으로 보내는 것.
- 결제창 자체는 외부 제공자 화면이므로 조작하지 않았다. 이 환경에서는 SDK script 요청이 나가지
  못했고, 화면이 성공으로 넘어가지 않고 실패를 표시하는 것까지 확인했다. 결제창 이후의 승인
  callback은 `scripts/demo/smoke.sh`가 API 수준에서 exact replay와 위변조 거부까지 검증한다.
- 매장 검색은 `/stores/search`가 200과 빈 목록을 돌려주는 것까지만 확인했다. demo seed가 검색
  색인을 채우지 않아 결과가 있는 화면은 보지 못했다.
- Milestone 10 뒤 같은 환경에서 다시 확인한 것: `/app/stores/{id}`를 URL로 직접 열었을 때 제목이
  `BeanFlow Demo Roastery`인 것, 그 화면에서 담은 장바구니가 그 이름을 저장하는 것, 저장된 이름을
  일부러 `매장`으로 바꿔 두어도 장바구니 화면이 서버 이름을 보여주는 것, 없는 매장 ID는 서버의
  영어 문장이 아니라 고객용 안내를 보여주는 것.
- Web Vitals는 측정하지 않았으므로 성능 개선을 주장하지 않는다.

## Revision Notes

- 2026-08-12: 최초 작성.
- 2026-08-15: 사용자 선택 A에 따라 Customer CSRF consumer 보정의 소유 범위와 frontend 전체 build의 알려진 실패를 기록. 구현은 시작하지 않음.
- 2026-08-16: Milestones 1–8 구현 완료와 Milestone 9 부분 완료를 실제 검증 결과로 기록. `storeId`
  응답 추가 결정과 runtime CSRF 계약 보정을 반영.
- 2026-08-16: backend를 띄운 browser smoke를 실행하고 결과를 기록. 이 smoke에서만 드러난 결제
  결과 화면 결함 2건과 주문 화면 표기 결함 2건을 고치고 회귀 test를 추가. 실행하지 못한 두 항목
  (검색 결과가 있는 화면, 외부 결제창 이후 흐름)과 그 이유를 `Outcomes`에 남김.
- 2026-08-16: browser smoke가 찾은 매장 단건 조회 계약 공백을 메우기 위해 사용자 결정에 따라 plan을
  다시 `ACTIVE`로 열고 Milestone 10을 추가. `GET /stores/{storeId}`와 매장 이름의 서버 소유를 구현.
