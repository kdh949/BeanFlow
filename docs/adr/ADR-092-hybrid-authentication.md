# ADR-092: 고객·점주 Session과 운영자 Keycloak의 Hybrid 인증

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owners:** [Authentication foundation](../exec-plans/active/productization-20-authentication-foundation.md), [P0 operations console](../exec-plans/active/productization-100-operations-work-queues.md)

## Context

현재 `SecurityConfiguration.kt`는 단일 `SecurityFilterChain`이고, 외부 JWK Set으로 검증한 Bearer
JWT만 받는다. `SessionCreationPolicy.STATELESS`, `csrf().disable()`이며, `sub` claim을 고객 ID로,
`roles` claim을 역할로 사용한다.

이 구성에는 **토큰을 발급하는 주체가 저장소 안에 없다**. 고객 계정 테이블도, 점주 자격증명
테이블도 없다. `identity_store_membership`만 존재하고, `identity_customer_support_profile`은
Support 목적의 보호 프로필이지 로그인 계정이 아니다. 그래서 로컬 데모는 `.demo-runtime/`이
만든 역할별 JWT를 사람이 화면 입력창에 붙여넣는 방식으로 동작한다.

즉 현재 시스템은 "인증된 사용자가 있다고 가정한 거래 백엔드"이고, 사용자가 계정을 만들고
로그인하는 경로가 없다. 이 결정 없이는 `/me` 계열 API, 주문 목록, 매장 전환, 운영자 감사 어느
것도 만들 수 없다.

## Decision

인증 방식을 사용자 유형별로 나눈다.

```text
Customer Web       아이디·비밀번호 → BeanFlow Session Cookie
Merchant Console   아이디·비밀번호 → 최초 비밀번호 변경 → BeanFlow Session Cookie
Operations Console Keycloak OIDC/JWT (현재 Resource Server 유지)
```

- 고객과 점주는 BeanFlow가 소유하는 자체 계정과 Secure·HttpOnly Session Cookie를 사용한다.
- 운영자와 고객센터는 기존 외부 IdP(Keycloak) JWT를 그대로 사용한다. `OperatorPermissionGrant`
  기반 권한 모델([ADR-069](ADR-069-operator-permission-grants-and-audited-policy-read.md))은
  바뀌지 않는다.
- 운영자 브라우저는 BR-41의 Authorization Code + PKCE `S256` public-client 흐름으로 JWT를 얻는다.
  access/ID/refresh token은 브라우저 영구 저장소나 Cookie에 저장하지 않고 access token은 메모리에만
  둔다. redirect 검증용 state·nonce·code verifier만 일회성 `sessionStorage`에 둘 수 있다. P0는
  `offline_access`와 지속 refresh token을 사용하지 않는다.
- `SecurityFilterChain`을 URI 기준으로 분리한다.

| Chain | 경로 | 인증 |
|---|---|---|
| Customer | `/api/v1/auth/customer/**`, `/api/v1/me/**`, 고객 주문·탐색 경로 | Session |
| Merchant | `/api/v1/auth/merchant/**`, `/api/v1/merchant/**`, 매장 주문·관리 경로 | Session |
| Operations | `/api/v1/operations/**`, `/api/v1/support/**` | JWT Resource Server |
| Public | `/actuator/health`, `/api/v1/payment-config` | 없음 |

Public Chain은 운영자 SPA가 로그인 전에 읽는 `GET /api/v1/auth/operations/config`도 포함한다. 응답은
`issuerUri`, `authorizationServerUrl`, `realm`, public `clientId`, exact `redirectUri`,
`postLogoutRedirectUri`와 `scopes`뿐이며 secret은 없다. 서버는
`issuerUri = authorizationServerUrl + /realms/{realm}`의 canonical 일치, backend JWT issuer/audience와
client 설정의 일치를 기동 시 검증한다. 필수 설정 누락·불일치는 기동 또는 로그인 시작 실패이고 demo
token이나 hard-coded localhost로 대체하지 않는다.

현재 actor 조회와 Session 로그아웃도 Chain별 경로를 사용한다.

```text
Customer   GET /api/v1/me
           DELETE /api/v1/auth/customer/sessions/current
Merchant   GET /api/v1/merchant/me
           GET /api/v1/merchant/me/stores
           DELETE /api/v1/auth/merchant/sessions/current
Operator   GET /api/v1/operations/me
```

하나의 `/me`나 공통 logout endpoint에서 고객 Cookie, 점주 Cookie와 운영자 JWT를 동시에 판별하는
복합 인증 Chain을 만들지 않는다.

- Session 저장소는 PostgreSQL 기반 Spring Session JDBC를 사용한다. Redis는 도입하지 않는다.
- 인증 구현체는 Application·Domain 계층에 노출하지 않는다. 세 인증 방식은 모두
  [ADR-095](ADR-095-unified-current-actor.md)의 `CurrentActor`로 번역된다.
- 기존 JWT `roles` claim 기반 고객·점주 인증 경로는 P0 완료 시점에 제거한다. 두 인증 방식을
  같은 경로에서 동시에 허용하지 않는다.

### Actor-exclusive legacy API split amendment (2026-08-13)

기존 runtime에는 한 URI에서 actor role을 분기하던 API가 있었지만 네 Chain 전환 뒤에는 같은 URI가
Session과 운영자 JWT를 동시에 받아서는 안 된다. 기존 소비자 URI를 고객·점주 계약으로 유지하고
운영자용 URI를 `/operations/**` 아래에 additive하게 분리한다.

| 기능 | Customer/Merchant URI | Operations URI |
|---|---|---|
| PointAccount summary | `GET /api/v1/point-accounts/{accountId}` | `GET /api/v1/operations/point-accounts/{accountId}` |
| PointAccount ledger | `GET /api/v1/point-accounts/{accountId}/transactions` | `GET /api/v1/operations/point-accounts/{accountId}/transactions` |
| legacy UUID refund | `POST /api/v1/payments/{paymentId}/refunds` | `POST /api/v1/operations/payments/{paymentId}/refunds` |

- PointAccount 소비자 URI는 Customer Session과 자기 소유권만 허용한다. 운영자 URI는 Bearer JWT,
  `PLATFORM_OPERATOR`, active `POINT_ACCOUNT_READ`, required `X-Access-Reason`과 접근 Audit을 요구한다.
- legacy refund 소비자 URI는 Merchant Session만 허용하고 현재 membership 검증을 유지한다. 운영자
  URI는 Bearer JWT의 `PLATFORM_OPERATOR` branch를 유지한다. 두 URI는 같은 Refund Application
  Service와 멱등성 source를 사용하므로 URI 분리가 중복 Refund나 새 Provider key를 만들지 않는다.
- 이전 URI에 잘못된 actor 인증을 보내면 다른 Chain으로 재해석하거나 fallback하지 않고 403이다.
  기존 운영자 client는 새 `/operations/**` URI로 전환해야 한다.
- 이 분리는 인증 경계 변경이며 PointAccount, Refund 원장·계산·권한 정책을 변경하지 않는다.

### 전환 순서와 중간 가용성

- `productization-20`은 고객·점주 보호 경로를 Session-only로 먼저 전환한다.
- 이 시점에는 계정·로그인 endpoint가 아직 없으므로 고객·점주 보호 경로는 각각
  `productization-30`, `productization-40`이 완료될 때까지 인증 Session을 만들 수 없고 401을
  반환한다. 이 중간 가용성 중단은 프로그램의 명시적 결정이다.
- 중단 구간을 숨기기 위해 같은 URI에 기존 JWT를 병행 허용하거나 fake Session, local account,
  기본 actor를 제공하지 않는다.
- 운영자·Support JWT 경로는 이 전환과 무관하게 계속 동작한다.

## Alternatives Considered

### 1. 모든 사용자를 Keycloak으로 관리

- 장점: 표준 기능이 많고 자체 인증 구현 부담이 없다. 사용자 저장소를 하나로 둘 수 있다.
- 단점: 고객 가입 UX와 점주 최초 비밀번호 강제 변경을 제품 요구대로 표현하려면 Keycloak 확장
  또는 Admin API 연동이 필요하다. 인증 실패·잠금·시도 제한 정책이 제품 도메인 밖에 있어
  감사와 지표를 저장소 안에서 검증하기 어렵다. 로컬 데모와 테스트가 외부 IdP에 계속 묶인다.

### 2. 고객·점주에게 자체 JWT를 발급

- 장점: 모바일 앱 확장과 다중 클라이언트에 유리하다.
- 단점: Refresh Token 회전·폐기·탈취 대응·저장 위치를 모두 직접 구현해야 한다. 브라우저에서
  토큰을 JavaScript가 접근 가능한 곳에 두면 XSS 노출면이 커진다. 현재 클라이언트는 동일 출처
  React 웹 하나뿐이라 이 비용을 정당화할 요구가 없다.

### 3. 모든 사용자를 Session으로 통일

- 장점: 인증 계층이 하나다.
- 단점: 운영자 권한 모델과 감사가 이미 외부 IdP 신원 위에 세워져 있다. Keycloak을 제거하면
  `OidcWorkloadIdentityVerifier` 기반 permission bootstrap 경로까지 다시 설계해야 한다.

## Rationale

현재 클라이언트는 동일 출처 React 웹이다. 이 조건에서 Cookie Session은 토큰을 JavaScript에
노출하지 않고, 서버가 언제든 무효화할 수 있으며, 구현 범위가 Refresh Token 체계보다 작다.
CSRF 대응이라는 비용이 추가되지만, 이는 표준 대응책이 명확하고 테스트로 검증 가능한 위험이다.

운영자만 Keycloak을 유지하는 이유는 운영자 신원이 이미 조직 IdP에 있고, 권한 grant·감사·bootstrap
경로가 그 신원을 전제로 완성돼 있기 때문이다. 잘 동작하는 부분을 바꾸지 않는다.

## Consequences

- Security 설정이 단일 Chain에서 네 개로 늘어난다. Chain 간 경로 중복과 우선순위를 테스트로 고정해야 한다.
- 자체 인증의 보안 책임을 진다. 시도 제한, 잠금, Session 회전, CSRF, Account Enumeration 방지를
  직접 구현하고 검증해야 한다. 상세는 [ADR-094](ADR-094-browser-session-security.md)다.
- `spring-session-jdbc` production dependency가 추가되고 Session 테이블 2개가 스키마에 들어온다.
- 로컬 데모의 토큰 입력 UI를 제거할 수 있다. 데모 seed는 계정 생성으로 바뀐다.
- 운영자 콘솔에는 OIDC client와 callback route가 추가된다. access token 만료 시 stale 운영 화면을
  계속 쓰지 않고 Keycloak SSO 재인증으로 돌아간다.
- 고객·점주 API의 통합 테스트가 Bearer 헤더 대신 로그인 후 Cookie를 사용하도록 바뀐다.
- Plan 20과 각 계정 plan 사이에는 고객·점주 보호 경로가 401인 의도된 중간 상태가 존재한다.
  배포·데모 문서는 이 중단을 정상 가용 상태로 표현하지 않는다.

## Verification

- Chain 분리: 각 경로 그룹에 대해 잘못된 인증 유형(고객 경로에 운영자 JWT, 운영 경로에 고객 Cookie)이 401/403인지 검증한다.
- 세 분리 API 쌍에서 소비자 URI와 Operations URI의 응답·실패 계약은 유지되고, 반대 actor 인증은
  403이며 같은 refund idempotency key가 URI를 바꿔 중복 Provider 요청을 만들지 않는지 검증한다.
- 인증되지 않은 요청이 모든 보호 경로에서 401인지 검증한다.
- Session Fixation: 로그인 전후 Session ID가 달라지는지 검증한다.
- 로그아웃 후 같은 Session ID 재사용이 401인지 검증한다.
- CSRF 토큰 누락 상태 변경 요청이 403인지 검증한다.
- 운영자 경로에서 기존 permission grant 테스트가 회귀 없이 통과하는지 확인한다.
- 운영자 callback의 state·nonce·PKCE 검증, open redirect 거부와 token 영구 저장 부재를 검증한다.
- token 만료·logout 뒤 보호 화면과 API 요청이 중단되고 Token Editor가 없는지 브라우저에서 검증한다.
- PostgreSQL Testcontainers에서 Session 저장·만료·삭제를 검증한다.

## Metrics

측정 전 목표값을 정하지 않는다. 다음을 수집한다.

- 인증 성공률과 실패 유형별 분포
- 활성 Session 수와 폐기 수
- Lockout 발생 수
- Session 조회 지연 p50·p95
- Chain별 401/403 발생 수

## Implementation Outcome (2026-08-13)

`productization-20`은 중앙 경로 registry와 정확히 네 `SecurityFilterChain`을 구현했다. Public과
Operations는 stateless이고, Customer/Merchant는 서로 다른 PostgreSQL Session·CSRF Cookie만 수용한다.
미배정 또는 중복 mapping은 startup 검증 실패다. 기존 혼합 actor API는 소비자 URI를 유지하면서
Operations 전용 PointAccount·refund URI로 분리했으며, 반대 actor credential은 403이다. 고객·점주
계정 loader가 아직 없을 때 보호 경로를 401/503 외의 가짜 성공으로 바꾸지 않는다.

## Revisit Conditions

- 모바일 네이티브 앱 또는 제3자 클라이언트가 실제 요구가 될 때
- 애플리케이션 인스턴스가 여러 개가 되어 Session 저장소 부하가 실제로 측정될 때
- 고객 인증에 휴대전화 OTP 또는 소셜 로그인을 추가할 때
- Session 테이블의 조회 지연이 실제 측정에서 문제가 될 때
- 같은 브라우저 세션에서 actor별 introspection을 하나의 URI로 합쳐야 할 검증된 클라이언트 요구가 생길 때

## Related Decisions

- [ADR-093](ADR-093-merchant-credential-lifecycle.md)
- [ADR-094](ADR-094-browser-session-security.md)
- [ADR-095](ADR-095-unified-current-actor.md)
- [ADR-027](ADR-027-store-membership-authorization.md)
- [ADR-069](ADR-069-operator-permission-grants-and-audited-policy-read.md)
- [Design Contract Conflicts C-1](../product/design-contract-conflicts.md)
