# ADR-094: 브라우저 Session 보안과 저장소

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Authentication foundation](../exec-plans/active/productization-20-authentication-foundation.md)

## Context

[ADR-092](ADR-092-hybrid-authentication.md)는 고객과 점주에게 Session Cookie를 쓰기로 했다.
현재 설정은 `SessionCreationPolicy.STATELESS`이고 CSRF가 꺼져 있다. Session을 도입하는 순간
이 두 설정은 모두 틀린 값이 된다.

Session을 쓰면 다음 위험이 새로 생긴다.

- CSRF: Cookie는 브라우저가 자동으로 붙이므로 다른 출처의 요청도 인증된다.
- Session Fixation: 로그인 전 Session ID를 유지하면 공격자가 심어둔 ID로 인증이 승격된다.
- Session 탈취: Cookie가 JavaScript나 평문 채널로 노출되면 인증이 통째로 넘어간다.
- Session 저장소 장애: 저장소를 읽지 못할 때 요청을 익명으로 통과시키면 인가가 무너진다.

## Decision

### Cookie 속성

| 속성 | 값 | 이유 |
|---|---|---|
| `HttpOnly` | `true` | JavaScript 접근 차단 |
| `Secure` | `true` | 평문 전송 차단. 로컬 개발 profile에서만 완화하고 그 사실을 기동 로그에 남긴다. |
| `SameSite` | `Lax` | 최상위 GET 내비게이션은 허용하고 교차 출처 상태 변경은 차단 |
| `Path` | `/` | 콘솔별 경로 분리는 Cookie가 아니라 Chain이 담당 |
| 이름 | 고객·점주 별도 | 같은 브라우저에서 두 콘솔을 동시에 열 때 Session이 섞이지 않는다 |

### CSRF

- 상태를 바꾸는 모든 요청(`POST`, `PUT`, `PATCH`, `DELETE`)에 CSRF 토큰을 요구한다.
- `CookieCsrfTokenRepository.withHttpOnlyFalse()`를 actor별로 구성한다. 고객은
  `GET /api/v1/auth/customer/csrf`, `BEANFLOW_CUSTOMER_XSRF` Cookie와 `X-BEANFLOW-CSRF` header,
  점주는 `GET /api/v1/auth/merchant/csrf`, `BEANFLOW_MERCHANT_XSRF` Cookie와 같은 header를 사용한다.
- 두 CSRF Cookie는 인증정보를 담지 않고 JS가 header로 복사할 token만 담는다. 고객 token을 Merchant
  Chain에서, 점주 token을 Customer Chain에서 받아들이지 않는다.
- 운영자 Chain은 Bearer JWT이고 Cookie를 쓰지 않으므로 CSRF를 적용하지 않는다.
- 로그인 endpoint 자체도 CSRF 대상이다. 로그인 폼 진입 시 토큰을 발급한다.

### Session lifecycle

- 로그인 성공 시 기존 Session을 삭제하고 새 JDBC Session ID를 명시적으로 저장해 회전한다. 저장은
  response commit 시점에 맡기지 않는다.
- CustomerAccount와 MerchantAccount에 단조 증가하는 `credentialVersion`을 둔다. Session에는 로그인
  시점의 version을 저장하고, 매 인증 요청에서 현재 계정 version과 상태를 다시 조회한다.
- 비밀번호 변경·운영자 초기화·잠금처럼 기존 자격증명을 무효화하는 상태 전이는 계정 transaction에서
  `credentialVersion`을 증가시킨다. version이 다른 Session은 물리 행이 남아 있어도 즉시 401이다.
- 비밀번호 변경 뒤 새 Session은 증가한 version으로 발급한다. 이전 Spring Session 행 삭제는 보존
  공간 정리이며 인가 안전성의 전제가 아니다. 정리 실패는 retry 상태·로그·metric으로 남긴다.
- 현재 Session logout은 해당 Spring Session 행을 삭제한다. 삭제 저장소가 실패하면 503이고 성공으로
  응답하지 않는다. 전체 Session logout은 `credentialVersion` 증가를 사용한다.
- 유휴 만료, 절대 만료와 계정당 동시 Session 상한은
  [BR-36](../product/business-policy-decisions.md)을 따른다. 상한에서 새 로그인이 성공하면
  `(authenticatedAt, sessionId)` 기준 가장 오래된 Session부터 폐기한다.
- 같은 계정 로그인은 account row lock으로 직렬화한다. 기존 Session 폐기와 새 Session 저장을 같은
  PostgreSQL transaction에서 처리하며, 하나라도 실패하면 rollback하고 503이다.

### 저장소

- Spring Session JDBC와 PostgreSQL을 사용한다. Session 테이블은 Flyway migration으로 만든다.
- Redis는 도입하지 않는다. 다중 인스턴스나 Session 부하가 **실제로 측정된 뒤** 재검토한다.
- Session 저장소 조회 실패는 익명 요청으로 강등하지 않는다. `503`으로 실패시킨다.
  in-memory Session 저장소로의 자동 대체는 금지한다([ADR-009](ADR-009-explicit-failure-semantics.md)).
- Session 만료와 version 불일치 행 정리는 Spring Session의 정리 작업을 사용하고, 정리 실패는 retry
  상태·로그·metric으로 노출한다.

### Session에 저장하지 않는 것

Session에는 actor 식별자, 인증 시각과 로그인 시점의 `credentialVersion`만 둔다. 매장 membership,
권한과 계정 상태는 요청마다 다시 조회한다. 계정 조회 실패는 503이며, 상태가 인증 불가하거나 version이
다르면 401이다. 권한을 Session에 캐시하면 revoke가 즉시 반영되지 않는다.

## Alternatives Considered

### 1. CSRF 대신 `SameSite=Strict`만 사용

- 장점: 구현이 간단하다.
- 단점: 브라우저·버전별 동작 차이에 인가를 의존하게 된다. 외부 링크로 진입하는 정상 흐름이 깨진다.

### 2. Session을 in-memory로 두고 sticky session 사용

- 장점: 스키마가 늘지 않고 조회가 빠르다.
- 단점: 인스턴스 재시작마다 전원 로그아웃된다. 다중 인스턴스에서 로드밸런서 설정에 인가가 의존한다.

### 3. 처음부터 Redis Session

- 장점: 확장성이 좋다.
- 단점: 운영 대상 인프라가 하나 늘고, 장애 시 인증 전체가 멈춘다. `AGENTS.md`는 필요성과 장애
  정책이 문서화되지 않은 Redis 도입을 금지한다.

## Rationale

Session 저장소를 PostgreSQL에 두면 트랜잭션·백업·관측이 이미 있는 인프라 안에서 끝난다.
현재 규모에서 Session 조회는 인덱스 조회 한 번이고, 이 비용이 문제라는 근거가 아직 없다.
근거 없는 최적화 대신 측정 후 재검토 조건을 명시한다.

## Consequences

- Flyway migration에 Spring Session 테이블(`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`)이 추가된다.
- 프론트엔드는 모든 상태 변경 요청에 CSRF 헤더를 붙여야 한다. API client에 공통 처리가 필요하다.
- 로컬 개발에서 `Secure` Cookie 때문에 HTTP 접속이 막히므로 profile별 설정과 기동 로그가 필요하다.
- 요청마다 권한을 재조회하므로 매장 API의 쿼리 수가 요청당 1~2개 늘어난다. 이는 revoke 즉시성의 대가다.
- 자격증명 변경 시 Spring Session 행 삭제 성공 여부와 무관하게 이전 Session을 거부할 수 있다.
  대신 고객·점주 인증 요청마다 계정 상태와 `credentialVersion` 조회가 필요하다.

## Verification

- 로그인 전후 Session ID가 달라지는지 검증한다.
- 로그아웃·비밀번호 변경 후 이전 Session ID 재사용이 401인지 검증한다.
- 비밀번호 변경·초기화·잠금 후 이전 Session 행을 강제로 남겨도 version 불일치로 401인지 검증한다.
- 계정 version 조회 장애가 익명 또는 401이 아니라 503인지 검증한다.
- CSRF 토큰 없는 `POST`가 403인지, actor별 endpoint/cookie/header 조합만 성공하는지 검증한다.
- 고객 token의 점주 Chain 재사용과 점주 token의 고객 Chain 재사용이 403인지 검증한다.
- 운영자 Chain은 CSRF 토큰 없이도 정상 동작하는지 검증한다.
- 고객 Cookie로 점주 경로를 호출하면 403인지 검증한다.
- Session 저장소 장애를 주입해 익명 통과가 아니라 503이 되는지 검증한다.
- 동시 Session 상한 초과 시 오래된 Session이 폐기되는지 검증한다.
- 유휴·절대 만료 경계를 고정 `Clock`으로 검증한다.
- 같은 계정의 동시 로그인과 Session 저장·삭제 장애에서 상한을 초과하지 않는지 PostgreSQL 통합
  테스트로 검증한다.

## Metrics

- 활성 Session 수, 생성·폐기·만료 수
- Session 조회 지연 p50·p95
- CSRF 실패 수
- Session 저장소 오류 수
- 계정당 동시 Session 상한 도달 수

## Implementation Outcome (2026-08-13)

Flyway V52가 Spring Session JDBC 4.1 PostgreSQL 표준 table·index를 소유하고 framework 자동 DDL은
`never`다. Spring Session의 기본 `REQUIRES_NEW` 저장은 로그인 owner transaction의 원자성을 깨뜨리므로
`springSessionTransactionOperations`를 `REQUIRED`로 고정했다. PostgreSQL 통합 테스트에서 동시 점주
로그인 상한, rotation, logout 재사용 401, insert/delete 장애 rollback을 검증했다. 정리 worker와
active/lifecycle/lookup/store-error metrics를 연결했으며 저장소 장애 fallback은 없다.

## Revisit Conditions

- 애플리케이션 인스턴스가 여러 개가 되고 Session 테이블 부하가 실제로 측정될 때
- 교차 출처 클라이언트가 필요해질 때
- Session 조회가 요청 지연의 유의미한 비중을 차지한다고 측정될 때

## Related Decisions

- [ADR-092](ADR-092-hybrid-authentication.md)
- [ADR-093](ADR-093-merchant-credential-lifecycle.md)
- [ADR-095](ADR-095-unified-current-actor.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
