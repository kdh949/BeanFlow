# 다중 FilterChain과 CurrentActor로 인증 기반을 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/productization-00-design-capability-contract.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

현재 모든 Controller가 외부에서 발급된 JWT를 전제로 동작한다. 그 JWT를 만드는 주체가 저장소에
없으므로, 사람은 토큰을 화면에 붙여넣어야 한다.

이 plan은 **인증 계층 자체**를 만든다. 계정 생성과 로그인 화면은 다음 두 plan이 담당하고, 여기서는
그 두 plan이 올라탈 기반을 만든다.

- 경로별 `SecurityFilterChain` 4개
- PostgreSQL 기반 Session과 CSRF
- `CurrentActor` 추상화와 argument resolver
- Chain별 현재 actor 조회·로그아웃을 구현할 수 있는 공통 기반과 운영자 `GET /operations/me`

## Current State

- `shared/internal/SecurityConfiguration.kt`가 단일 `SecurityFilterChain`이다.
- `csrf().disable()`, `SessionCreationPolicy.STATELESS`다.
- `oauth2ResourceServer`가 `roles` claim을 `ROLE_` 권한으로 변환한다.
- 여러 Controller가 `Jwt` 파라미터를 직접 받는다.
- Session 테이블이 없다. `spring-session-jdbc` 의존성이 없다.
- `identity_store_membership`과 `StoreAccessOperations`는 존재하며 그대로 사용한다.

## Definitions

- **Chain:** URI 패턴으로 분리된 `SecurityFilterChain`이다. 요청은 정확히 하나의 Chain에 속한다.
- **CurrentActor:** 인증 구현과 무관한 행위자 표현이다. `CustomerActor`, `MerchantActor`, `OperatorActor`.
- **Session rotation:** 로그인 시 Session ID를 교체해 fixation을 막는 동작이다.
- **Step-up:** 이미 인증된 Session 위에서 특정 명령에만 추가 확인을 요구하는 것이다. 이 plan 범위 밖이다.

## Scope

### In Scope

- Customer / Merchant / Operations / Public 4개 `SecurityFilterChain`
- `spring-session-jdbc`와 Session 테이블 migration
- Cookie 속성(`HttpOnly`, `Secure`, `SameSite=Lax`), 콘솔별 Cookie 이름 분리
- CSRF 토큰 발급·검증(운영자 Chain 제외)
- Session rotation, 로그아웃, 유휴·절대 만료, 계정당 동시 Session 상한
- `CurrentActor` sealed interface와 `HandlerMethodArgumentResolver`
- 계정 로그인 transaction 안에서 Session 회전·상한 조정·JDBC 저장을 명시적으로 끝내는
  `LoginSessionCoordinator` infrastructure port/adapter
- 기존 Controller의 `Jwt` 파라미터를 `CurrentActor`로 교체
- 운영자 `GET /api/v1/operations/me`
- 후속 점주 계정 관리 명령 전용 `MERCHANT_CREDENTIAL_MANAGE` closed permission vocabulary
- 고객·점주 `/me`와 logout endpoint가 다음 계정 plan에서 사용할 공통 Session lifecycle component
- ArchUnit 규칙: Application·Domain의 Spring Security 참조 금지

### Non-goals

- 고객·점주 계정 테이블과 자격증명(다음 plan)
- 회원가입·로그인 endpoint(다음 plan)
- 소셜 로그인, 휴대전화 OTP
- Redis Session
- 프론트엔드 Token Editor 제거(준비만 하고 제거는 화면 plan에서)

## Business Rules and Invariants

1. 요청은 정확히 하나의 Chain에 속한다. Chain 경로가 겹치면 기동을 실패시킨다.
2. 인증이 없으면 401, actor 유형이 맞지 않으면 403이다.
3. 로그인 성공 시 Session ID를 회전한다.
4. Session에는 actor 식별자, 인증 시각과 로그인 시점 `credentialVersion`만 둔다. 계정 상태·현재
   version·권한과 membership은 캐시하지 않고 매 요청 조회한다.
5. Session 저장소 조회 실패는 익명 요청으로 강등하지 않는다. 503이다.
6. Application·Domain 계층은 Spring Security 타입을 참조하지 않는다.
7. 행위자 자신의 ID를 요청 Body에서 받지 않는다.
8. 이 plan 완료 뒤 Plan 30/40 전까지 고객·점주 보호 경로가 401인 중간 단절을 허용한다. 기존 JWT,
   fake Session 또는 기본 actor로 가용성을 가장하지 않는다.
9. Session 수명·동시 한도·초과 시 폐기 순서는 [BR-36](../../product/business-policy-decisions.md)를
   적용한다. 다른 profile 기본값으로 조용히 완화하지 않는다.
10. `MERCHANT_CREDENTIAL_MANAGE`는 active DB grant만 source of truth다. role·JWT claim·다른 grant를
    fallback으로 쓰거나 default grant로 seed하지 않는다([BR-46](../../product/business-policy-decisions.md)).

## Architecture and Transaction Boundaries

```text
요청
 └─ Chain 선택 (URI)
     ├─ Customer   : Session + CSRF
     ├─ Merchant   : Session + CSRF
     ├─ Operations : Bearer JWT (기존)
     └─ Public     : 인증 없음
 └─ ArgumentResolver → CurrentActor
 └─ Controller → Application Service (CurrentActor)
```

- Session 조회는 요청 스코프의 짧은 읽기다. 애플리케이션 트랜잭션과 분리한다.
- 로그인 성공 경계는 account row를 잠그고 `LoginSessionCoordinator`가 활성 Session을 조회한다.
  상한이면 가장 오래된 Session 폐기, 현재 브라우저 Session ID 회전과 새 Session JDBC 저장을 같은
  PostgreSQL transaction에서 명시적으로 끝낸다. Spring Session filter의 response-commit 시점 암묵적
  저장에 원자성을 맡기지 않는다. transaction commit 뒤에만 회전된 Cookie를 응답하며, 삭제·회전·저장
  중 하나라도 실패하면 rollback하고 503을 반환한다. 동시 로그인과 저장 장애에도 상한을 넘기지 않는다.
- 매장 membership 확인은 Application Service의 트랜잭션 안에서 수행한다(기존 동작 유지).
- 외부 호출은 인증 경로에 없다.

Chain 경로 배정.

| Chain | 패턴 |
|---|---|
| Public | `/actuator/health`, `/api/v1/payment-config`, `/api/v1/auth/operations/config` |
| Operations | `/api/v1/operations/**`, `/api/v1/support/**` |
| Merchant | `/api/v1/auth/merchant/**`, `/api/v1/merchant/**`, `/api/v1/stores/*/orders/**`, `/api/v1/stores/*/settlements/**` |
| Customer | 나머지 `/api/v1/**` |

경로 배정은 중앙 registry와 구조 테스트로 고정한다. 새 endpoint를 추가할 때 어느 Chain에
속하는지 명시하지 않으면 검증을 실패시킨다. Customer Chain을 암묵적 기본값으로 사용하지 않는다.

## Alternatives Considered

상세는 [ADR-092](../../adr/ADR-092-hybrid-authentication.md),
[ADR-094](../../adr/ADR-094-browser-session-security.md),
[ADR-095](../../adr/ADR-095-unified-current-actor.md)에 있다. 요약은 다음과 같다.

- 전원 Keycloak: 고객 가입 UX와 점주 강제 변경 표현의 어려움, 감사·지표가 저장소 밖에 위치
- 자체 JWT + Refresh Token: 회전·폐기·탈취 대응 구현 부담, 브라우저 저장 위치 위험
- 전원 Session: 운영자 권한 grant와 bootstrap 경로를 다시 설계해야 함
- `Principal` 직접 전달: Application 계층이 Spring Security에 결합
- `ThreadLocal` 컨텍스트: 의존이 숨겨지고 worker 경로에서 값이 사라짐

## Production Dependency Decision

- 추가 artifact는 `org.springframework.boot:spring-boot-starter-session-jdbc`이며 버전은 저장소의 Spring
  Boot 4.1.0 dependency management를 따른다. 별도 임의 버전을 섞지 않는다.
- 이 dependency가 해결하는 문제는 다중 인스턴스와 재시작 뒤에도 revoke·만료 가능한 공유 HttpSession을
  기존 PostgreSQL에 저장하는 것이다. 공식 PostgreSQL schema를 Flyway가 소유하고 framework 자동 DDL은
  끈다.
- in-memory/sticky Session은 재시작·라우팅에 인증 정확성이 의존하고, Redis는 새 인프라·장애 정책이
  필요해 기각한다. 자체 Session repository는 fixation, index, expiry cleanup과 serialization을 다시
  구현하므로 기각한다.
- 운영 비용은 요청별 Session read/write, 두 table·index와 만료 cleanup이다. 저장소 장애는 익명 또는
  local Session fallback이 아니라 503이다.
- 제거하려면 동등한 중앙 Session 저장소, rotation·credentialVersion·만료·동시 상한과 migration/runbook
  증거를 먼저 제공해야 한다. dependency만 제거해 container memory Session으로 돌아가지 않는다.

공식 근거: <https://docs.spring.io/spring-session/reference/configuration/jdbc.html>

## Failure Semantics

- Session 저장소 조회·저장 실패: 503. in-memory 대체 금지.
- 계정 상태·`credentialVersion` 조회 실패: 503. 익명 또는 401로 강등하지 않는다.
- Session의 version이 현재 계정 version과 다르거나 계정 상태가 인증 불가: 401.
- Chain 경로 중복 또는 미배정: 기동 실패.
- CSRF 토큰 누락·불일치: 403.
- 만료·폐기된 Session 사용: 401.
- 동시 Session 조정 중 기존 행 삭제 또는 새 행 저장 실패: 전체 rollback 후 503.
- actor 유형 불일치: 403.
- Operations Chain의 JWK 조회 실패: 기존 동작 유지(503).
- 로컬 개발에서 `Secure` Cookie를 완화하는 경우 기동 로그에 명시적으로 남긴다. 조용히 완화하지 않는다.
- 고객·점주 계정과 로그인 endpoint는 이 plan에 없으므로 Plan 30/40 전까지 해당 보호 경로의
  Session을 만들 수 없다. 이 기간의 401은 의도된 전환 상태이며 2xx fallback을 제공하지 않는다.

## Data and Migration

```sql
-- Spring Session JDBC 표준 스키마
CREATE TABLE spring_session (...);
CREATE TABLE spring_session_attributes (...);
```

- Spring Session이 제공하는 PostgreSQL DDL을 Flyway migration으로 옮긴다. 애플리케이션 자동
  생성에 의존하지 않는다.
- 인덱스는 표준 스키마의 것을 그대로 사용하고, 만료 정리 쿼리의 실행계획을 확인한다.
- 기존 데이터 backfill은 없다.
- 같은 migration writer lease에서 `OperatorPermission` enum과 `operator_permission_grant`의 closed DB
  vocabulary에 `MERCHANT_CREDENTIAL_MANAGE`를 추가한다. 현재 active grant를 자동 생성하지 않으며,
  기존 offline permission bootstrap만 grant/revoke/regrant에 사용한다.

## API and Event Contracts

```http
GET /api/v1/auth/customer/csrf
GET /api/v1/auth/merchant/csrf
GET /api/v1/operations/me
```

- customer CSRF endpoint는 `BEANFLOW_CUSTOMER_XSRF`, merchant endpoint는
  `BEANFLOW_MERCHANT_XSRF` JS-readable token Cookie를 발급한다. 두 Chain 모두 unsafe method에
  `X-BEANFLOW-CSRF` header를 요구하며 서로의 Cookie를 수용하지 않는다.
- 운영자 public config endpoint의 구현은 Plan 100이 소유한다. 이 plan은 Public Chain 경로 registry에
  먼저 예약해 나중에 Customer Chain으로 잘못 분류되지 않게 한다.
- 고객·점주 actor·logout endpoint는 계정 schema를 소유한 후속 plan에서 추가한다.

```text
OPERATOR  { actorType, operatorId, roles }
```
- 이벤트 계약 변경 없음.

## Milestones

1. migration writer lease 획득, Session 테이블과 `MERCHANT_CREDENTIAL_MANAGE` vocabulary migration.
2. `spring-boot-starter-session-jdbc` 의존성 추가와 Cookie·만료 설정.
3. 4개 Chain 분리와 경로 배정 테스트.
4. CSRF 설정과 프론트엔드 API client의 헤더 처리 준비.
5. `CurrentActor`와 argument resolver 구현.
6. 기존 Controller의 `Jwt` 파라미터 일괄 교체.
7. ArchUnit 규칙 추가.
8. 운영자 `GET /operations/me`와 후속 계정 plan이 사용할 Session lifecycle component 구현.
9. runtime OpenAPI와 계약 테스트 갱신.

## Required Tests

- 인증 없는 요청이 모든 보호 경로에서 401인지 검증한다.
- 고객 Cookie로 운영 경로, 운영자 JWT로 고객 경로 호출이 403인지 검증한다.
- 로그인 전후 Session ID가 달라지는지 검증한다.
- 로그아웃 후 같은 Session ID 재사용이 401인지 검증한다.
- CSRF 토큰 없는 `POST`가 403, actor별 endpoint/cookie/header 조합만 성공하고 교차 token은 403인지 검증한다.
- 운영자 Chain이 CSRF 없이 동작하는지 검증한다.
- Session 저장소 장애 주입 시 익명 통과가 아니라 503인지 검증한다.
- 유휴·절대 만료 경계를 고정 `Clock`으로 검증한다.
- 계정당 동시 Session 상한 초과 시 오래된 Session이 폐기되는지 검증한다.
- 같은 계정 동시 로그인과 Session 저장·삭제 장애에서도 상한을 넘지 않는지 PostgreSQL 통합 테스트로
  검증한다.
- Chain 경로가 겹치거나 미배정이면 기동이 실패하는지 검증한다.
- ArchUnit: Application·Domain이 `org.springframework.security`를 참조하지 않는지 검증한다.
- 요청 Body에 `customerId`를 넣어도 무시되고 actor가 사용되는지 검증한다.
- 기존 운영자 permission grant 테스트가 회귀 없이 통과하는지 확인한다.
- `MERCHANT_CREDENTIAL_MANAGE`가 enum·DB constraint·offline bootstrap에서만 추가되고 default grant·
  role/claim fallback이 없는지 검증한다.
- Spring Modulith 구조 검증이 통과하는지 확인한다.

## Validation Commands

```bash
./gradlew test --tests '*Security*' --tests '*Modulith*' --tests '*ArchUnit*'
./gradlew spotlessCheck
./gradlew build --stacktrace
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

## Observability

- Chain별 401/403 발생 수와 사유 분포
- 활성 Session 수, 생성·폐기·만료 수
- Session 조회 지연 p50·p95
- CSRF 실패 수
- Session 저장소 오류 수
- Session 정리 작업 성공·실패 수

## Documentation Updates

- ADR-092, ADR-094, ADR-095를 구현 결과로 갱신
- ADR-069의 `MERCHANT_CREDENTIAL_MANAGE` vocabulary amendment
- `docs/security/authorization-matrix.md`의 Enforcement layers 절
- `docs/api/api-conventions.md`(Chain 배정과 CSRF 규칙)
- `openapi/beanflow-v1-runtime.yaml`
- `README.md`의 실행 방법(JWK 필수 조건 변경)
- 신규 `docs/operations/session-and-authentication-runbook.md`

## Progress

- 2026-08-12: 사용자 Support 우선 결정에 따라 Plan 10 뒤 Stack A migration lease를 해제했다.
  Support S70~S100 completion, lease release와 productization migration 재번호화 기준이 기록될 때까지
  이 plan은 실행 후보가 아니며 schema/code 구현을 시작하지 않는다.
- 2026-08-13: `origin/main`의 Support S50~S100/PR #63 completion과 V49 lease release를 Plan 10에
  history-preserving merge했다. Plan 10을 V50/V51로 재번호화하고 Ordering 231 tests, 최종 full build
  964 tests(0 failures, 0 errors, 1 skipped), Spotless와 문서/OpenAPI 검증을 통과했으므로
  `Implementation-Ready=true`로 복원했다. 이 plan의 migration 번호는 구현 시작 preflight의 combined
  inventory에서 V51 다음으로 할당한다.
- 2026-08-13: 기존 Customer/Merchant URI를 유지하고 운영자 branch를 `/operations/**`로 분리하는
  actor-exclusive API 결정을 ADR-069/070/092/108, API conventions, authorization matrix와 target/runtime
  OpenAPI에 먼저 기록했다. 최초 문서 검증은 새 Operations ledger cursor가 shared pagination inventory에
  없어 실패했으며 ADR-070과 검증기를 갱신한 뒤 target 153 paths/159 operations, runtime 114 paths/118
  operations, 305 schemas, 46 policies, 111 ADRs, 273 Markdown, 57 ExecPlans 검증이 통과했다.
- 2026-08-13: V52 migration RED에서 Spring Session 두 table 부재, latest version 51,
  `MERCHANT_CREDENTIAL_MANAGE` enum/DB vocabulary 부재로 새 테스트 3건이 모두 실패했다. Spring Session
  JDBC 4.1.0 공식 PostgreSQL DDL, Flyway-owned `initialize-schema=never`, closed permission 확장과
  default grant 0건을 구현한 뒤 신규 migration 3 tests와 AuditRetention/SupportCompensation/
  OperatorPermission 회귀를 합친 focused suite가 통과했다.

## Surprises & Discoveries

- Support completion 뒤 Plan 10과 `origin/main`을 결합하자 V51 주문 표시 제약이 Support S80 direct-order
  fixture 16건을 깨뜨렸다. 공통 유효 fixture로 교정하고 전체 회귀를 다시 통과했으며, 실패를 Plan 10
  completion evidence에 기록했다.
- 기존 PointAccount read와 legacy UUID refund는 한 URI에서 Customer/Platform Operator 또는
  Merchant/Platform Operator를 role로 분기하고 있어 ADR-092의 actor별 단일 Chain과 양립하지 않았다.
  API를 제거하지 않고 소비자 URI와 Operations URI로 분리했으며, 새 cursor URI는 ADR-070 binding과
  문서 검증기 inventory도 함께 확장해야 했다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-11 | Session 저장소는 PostgreSQL. Redis는 측정 후 재검토 | [ADR-094](../../adr/ADR-094-browser-session-security.md) |
| 2026-08-11 | 권한·membership을 Session에 캐시하지 않는다 | [ADR-095](../../adr/ADR-095-unified-current-actor.md) |
| 2026-08-11 | Chain 미배정 경로는 구조 검증 실패 | 이 plan |
| 2026-08-12 | Plan 20 직후 고객·점주 API의 중간 401 단절을 허용하고 JWT 병행 경로를 두지 않는다 | [ADR-092](../../adr/ADR-092-hybrid-authentication.md) |
| 2026-08-12 | 공통 `/me` 대신 actor별 경로를 사용하고 고객·점주 endpoint는 계정 plan이 소유한다 | [ADR-092](../../adr/ADR-092-hybrid-authentication.md) |
| 2026-08-12 | 고객·점주별 Session 수명과 동시 한도 적용 | [BR-36](../../product/business-policy-decisions.md) |
| 2026-08-12 | 점주 credential 웹 관리용 explicit permission을 foundation migration에서 선등록 | [BR-46](../../product/business-policy-decisions.md), [ADR-069](../../adr/ADR-069-operator-permission-grants-and-audited-policy-read.md) |
| 2026-08-12 | Support S70~S100을 우선하고 Plan 20 readiness와 migration lease를 일시 해제 | [ADR-111](../../adr/ADR-111-productization-stack-a-draft-release.md) |
| 2026-08-13 | Support V43~V49 main 통합과 Plan 10 V50/V51 전체 검증 뒤 Plan 20 readiness와 Stack A lease를 복원 | [ADR-111](../../adr/ADR-111-productization-stack-a-draft-release.md), [Plan 10](../completed/productization-10-public-order-reference.md) |
| 2026-08-13 | 혼합 actor API는 기존 Customer/Merchant URI를 유지하고 운영자 branch를 `/operations/**` URI로 분리 | [ADR-092](../../adr/ADR-092-hybrid-authentication.md), [ADR-069](../../adr/ADR-069-operator-permission-grants-and-audited-policy-read.md), [ADR-108](../../adr/ADR-108-merchant-partial-refund-preview.md) |

## Outcomes & Retrospective

Plan 10 resume completion이 Support 통합 schema와 전체 회귀를 통과해 이 plan의 모든 직접 실행 gate가
해소됐다. 구현 자체는 아직 시작하지 않았고, 다음 branch는 verified Plan 10 completion head에서만 만든다.

## Revision Notes

- 2026-08-11: 최초 작성.
- 2026-08-12: Support 우선 migration lane 결정으로 `Implementation-Ready=false` 전환.
- 2026-08-13: Support V43~V49 통합과 Plan 10 V50/V51 재검증 완료로 `Implementation-Ready=true` 복원.
