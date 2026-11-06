# ADR-138: 운영자·고객센터 메모리 token 갱신

- **Status:** Accepted
- **Date:** 2026-10-01
- **Supersedes:** [ADR-092](ADR-092-hybrid-authentication.md)
- **Implementation owner:** [운영자 메모리 token 갱신](../exec-plans/completed/operations-memory-token-refresh.md)

## Context

Operations와 Support는 같은 Keycloak public client, PKCE S256 session과 Bearer API client를 쓴다.
P0 구현은 access token 만료 때 메모리 credential을 지워 짧은 token 수명이 업무 세션 수명으로
작동한다. Keycloak의 refresh token을 이미 메모리에 보관하지만 갱신에는 사용하지 않았다.

## Decision

ADR-092의 Hybrid 인증 분리는 유지한다. Customer/Merchant는 별도 Secure HttpOnly Cookie와
PostgreSQL Spring Session을 사용하며 BR-36의 idle/absolute/concurrency 정책을 유지한다.
Operations/Support는 stateless JWT Resource Server, 기존 issuer/audience/signature 검증과
OperatorPermissionGrant·Support object authorization을 유지한다. 인증 유형 간 fallback은 없다.

운영자 브라우저는 기존 Code Flow + PKCE S256과 공개 OIDC 설정을 유지하며 공식 `keycloak-js`
adapter의 `updateToken(30)`을 보호 API 요청 전에 호출한다. 만료 callback과 clock-skew를 반영한
만료 timer도 갱신을 수행한다. 동시 갱신은 session generation마다 하나의 Promise를 공유한다.
갱신 성공 시 현재 access token과 만료 시각을 메모리에 반영한다. access/ID/refresh token을
브라우저 persistent storage에 쓰거나 `offline_access`를 요청하지 않는다.

갱신 거부로 adapter 인증이 종료되면 unauthenticated, 통신·의존성 실패이면 unavailable로 표시하고
credential과 보호 화면을 제거한다. 현재 token으로 보낸 API의 401도 인증을 종료한다. 이전 token을
사용한 늦은 401은 새 credential을 지우지 않으며 원래 호출의 실패는 그대로 전달한다.
API 401·network error 뒤 업무 요청을 자동 재전송하지 않는다. clear/logout/retry는 generation을
변경하며 늦게 도착한 이전 adapter의 성공·실패는 새 인증을 변경할 수 없다.

Support의 reveal expiry, navigation, grant/permission loss와 logout 시 원문 제거는 ADR-090대로다.
token 갱신이 VerificationSession, DataAccessGrant 또는 reveal 유효 시간을 늘리지 않는다.
로그아웃은 기존 검증된 Keycloak end-session 흐름을 유지한다.

## Alternatives Considered

- access token 수명 연장: refresh 결함을 남기고 bearer 노출·폐기 탐지 창을 늘려 채택하지 않는다.
- 만료 즉시 SSO 재인증: 기존 P0 결정이지만 작업 중 보호 화면을 반복 중단하므로 대체한다.
- BFF/운영자 Cookie Session: credential 노출면을 줄일 수 있으나 새 서버 session·CSRF·배포 경계를
  요구해 현재 변경 범위를 넘는다.
- 메모리 adapter refresh: 기존 dependency·IdP 정책을 사용하고 credential 비저장을 유지해 채택한다.

## Rationale

업무 세션과 access token 수명을 분리하되 인증 판단은 IdP와 Resource Server가 계속 소유한다.
공식 adapter가 token 교환·회전과 서버 clock skew를 관리하며 브라우저가 refresh token을 자체
저장하거나 token endpoint를 별도로 구현하지 않는다.

## Consequences

열린 콘솔의 자동 갱신은 IdP의 session activity로 처리되어 SSO idle을 연장할 수 있다. session max,
logout/revocation과 refresh token 유효성은 Keycloak이 결정하며 이 변경에서 관리자 설정을 바꾸지
않는다. IdP 장애는 업무를 중단한다. refresh 실패 뒤 stale token을 계속 사용하지 않으므로 짧은
일시 장애에도 재인증이 필요할 수 있다. token 갱신 요청이 추가되며 background timer는 browser
throttling의 영향을 받지만 요청 전 갱신이 API credential의 최종 경계를 보호한다.

## Verification

- 만료·요청 전 refresh, 갱신 token 반영, 동시 호출 공유와 clock skew
- refresh 거부/통신 실패, token 비저장, 현재/이전 token의 401과 업무 요청 replay 부재
- clear/logout/retry 뒤 늦은 갱신이 인증을 복원하거나 새 세션을 지우지 않음
- Operations/Support gate failure와 Customer/Merchant credential 분리 회귀
- 기존 Customer/Merchant idle·absolute expiry 단위 테스트
- frontend typecheck/test/design/build, Storybook interaction/a11y, 문서 검증

## Metrics

실행된 테스트와 검증 결과만 ExecPlan에 기록한다. token, refresh token, subject, session ID를
client log/telemetry에 추가하지 않는다. 실제 IdP 장기 세션·revocation 검증은 배포 검증으로 구분한다.

## Revisit Conditions

브라우저 유휴 상태를 IdP SSO idle과 별도로 제한할 제품 요구, BFF, refresh rotation 정책 변경,
shared-origin credential/XSS incident 또는 실제 IdP 장애 관측이 생길 때 재검토한다.

## Related Decisions

BR-36, BR-41, ADR-090, ADR-094, ADR-095.

표준 adapter 근거: [Keycloak JavaScript adapter](https://www.keycloak.org/securing-apps/javascript-adapter).
