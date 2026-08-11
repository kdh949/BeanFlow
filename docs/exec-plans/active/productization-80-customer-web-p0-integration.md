# 고객 P0 화면을 Session과 실제 거래 API에 연결한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/active/productization-30-customer-account-and-login.md`, `docs/exec-plans/active/productization-50-customer-order-read-model.md`, `docs/exec-plans/active/productization-70-customer-store-discovery.md`
> **Completed-At:** `—`

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
GET  /api/v1/me/points
GET  /api/v1/me/point-transactions?cursor=&limit=
POST /api/v1/me/orders/{orderReference}/reorders
```

- `/me/points`는 `{ availablePointsKrw, recoveryPendingKrw, expiring[] }`를 반환하고 내부 accountId를
  반환하지 않는다. expiring은 PointLot의 `(expiresAt, amountKrw)` 최소 Projection이며 조회 실패를
  생략하지 않는다.
- transaction page는 signed cursor를 쓰고 내부 pointAccountId를 cursor filter 밖으로 노출하지 않는다.
- reorder 요청은 source ID나 가격을 받지 않고 기존 Idempotency-Key 계약을 사용한다.
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

아직 시작하지 않았다. 모든 선행 backend plan의 runtime OpenAPI가 완료된 뒤 준비 상태를 전환한다.

## Surprises & Discoveries

- 기존 프론트 API client는 한 Middleware에서 모든 actor Bearer token을 주입하므로 Session 전환 시 client
  분리가 보안 경계의 일부다.
- 신규 고객 PointAccount가 자동 존재하지 않아 BR-42와 ADR-109에서 가입 원자 provisioning을
  선행 결정했다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-12 | customer API는 HttpOnly Session + 별도 CSRF client를 사용 | [ADR-092](../../adr/ADR-092-hybrid-authentication.md), [ADR-094](../../adr/ADR-094-browser-session-security.md) |
| 2026-08-12 | cart는 한 매장 client state이며 server가 checkout에서 재검증 | [Capability Map](../../product/design-to-capability-map.md) |
| 2026-08-12 | PointAccount는 가입과 원자 생성하고 actor-scoped 경로로 조회 | [ADR-109](../../adr/ADR-109-customer-point-account-provisioning.md) |
| 2026-08-12 | payment network ambiguity에서는 confirm이 아니라 기존 status를 조회 | [ADR-007](../../adr/ADR-007-payment-idempotency-reconciliation.md) |

## Outcomes & Retrospective

아직 없다.

## Revision Notes

- 2026-08-12: 최초 작성.
