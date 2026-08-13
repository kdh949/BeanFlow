# 고객이 계정을 만들고 로그인한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/productization-20-authentication-foundation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

고객 계정을 만든다. 아이디와 비밀번호로 가입하고 로그인하면 Session이 생기고, 이후 모든 고객 API가
그 Session의 actor로 동작한다. 주문의 `customerId`는 요청이 아니라 Session에서 온다.

## Current State

- `productization-20`이 Chain, Session, CSRF, `CurrentActor`와 Session lifecycle 공통 기반을
  제공한다. 고객 `GET /me`와 logout endpoint는 이 plan이 구현한다.
- 고객 계정 테이블이 없다. `identity_customer_support_profile`은 Support 목적의 보호 프로필이며
  로그인 계정이 아니다.
- 디자인의 `고객 5a/5b`는 전화번호 OTP 기반이다. [C-1](../../product/design-contract-conflicts.md)에서
  P0는 ID/PW로 확정했다.
- `OrderController`가 JWT `sub`를 고객 ID로 사용한다. `productization-20`에서 `CustomerActor`로 교체됐다.

## Definitions

- **Login ID:** 고객이 정하는 사용자명이다. 이메일·전화번호가 아니며 [BR-34](../../product/business-policy-decisions.md)의
  ASCII 소문자 canonical 규칙을 사용한다.
- **Account enumeration:** 응답 차이로 계정 존재 여부를 알아내는 공격이다.
- **Attempt window:** 로그인 실패를 누적하는 시간 구간이다.
- **Customer account:** 로그인 자격증명을 소유하는 Identity Aggregate다. 주문의 `customerId`와 동일한 UUID다.

## Scope

### In Scope

- `identity_customer_account` 테이블과 자격증명 저장(Hash만)
- 고객 가입과 같은 transaction의 0원 `PointAccount` provisioning
- `POST /auth/customer/registrations` 가입
- `POST /auth/customer/sessions` 로그인
- `GET /me`, `DELETE /auth/customer/sessions/current`
- 로그인 시도 제한(아이디별·IP별)과 잠금
- 로그인 단계의 Account enumeration 방지 응답 계약
- 비밀번호 정책(길이·금지 패턴) 검증
- Argon2id PHC hash와 versioned local common-password blocklist
- `GET /me`의 고객 응답 구현
- 로컬 데모 seed를 계정 생성 기반으로 전환

### Non-goals

- 휴대전화 OTP, 소셜 로그인
- 비밀번호 재설정(검증된 발송 채널 없음)
- 프로필 편집, 탈퇴
- 고객 계정과 기존 Support 보호 프로필의 통합
- 이메일·전화번호 수집과 비밀번호 재설정

## Business Rules and Invariants

1. 비밀번호는 Hash만 저장한다. 평문과 되돌릴 수 있는 형태로 저장·로그하지 않는다.
2. 가입 중복은 `409 LOGIN_ID_UNAVAILABLE`로 알린다. 로그인에서는 계정 없음·비밀번호 불일치·잠금을
   같은 `401 AUTHENTICATION_FAILED`로 처리한다.
3. 로그인 실패는 아이디별·IP별로 누적한다. 15분 창의 5번째 계정 실패는 계정을 잠그고, 30번째
   IP 실패는 그 actor의 로그인 endpoint를 차단한다.
4. 로그인 성공 시 Session ID를 회전한다.
5. 고객 ID는 요청 Body에서 받지 않는다.
6. 계정 생성과 첫 Session 생성은 같은 요청에서 처리하지 않는다. 가입 후 로그인한다.
7. 잠금 상태에서는 올바른 비밀번호도 실패한다.
8. 계정 잠금·운영자 자격증명 변경은 `credentialVersion`을 같은 transaction에서 증가시켜 기존
   Session을 즉시 무효화한다.
9. 비밀번호 길이·hash, 실패 창·잠금·IP 제한과 attempt 보존은 [BR-35](../../product/business-policy-decisions.md)를
   그대로 적용한다. 값 누락 시 임의 기본값을 사용하지 않는다.
10. 가입 성공은 [BR-42](../../product/business-policy-decisions.md)에 따라 CustomerAccount와 0원
    PointAccount가 모두 commit됐다는 뜻이다. 둘 중 하나만 남길 수 없다.

## Architecture and Transaction Boundaries

```text
가입
  Tx1: 로그인 ID 중복 확인(Unique Constraint) + CustomerAccount INSERT
  Tx1: CustomerPointAccountProvisioningOperations.create(customerId)
       → Loyalty PointAccount INSERT (MANDATORY, 세 잔액 0)
       중복이면 Unique Constraint를 기준으로 409 LOGIN_ID_UNAVAILABLE
  Tx1: 같은 CUSTOMER LOGIN_ID HMAC의 과거 attempt row가 있으면 삭제

로그인 — transaction 밖
  입력 canonicalization + trusted-proxy 규칙으로 source IP 결정 + 두 scope HMAC 계산
  계정 snapshot(passwordHash, credentialVersion, state, lockedUntil) 조회; 없으면 고정 dummy PHC 사용
  Argon2id 검증을 수행해 DB row lock 중 memory-hard hash를 계산하지 않음

로그인 — Tx1
  LOGIN_ID/IP attempt row를 UPSERT한 뒤 (actorType, scopeType, scopeHmac) 오름차순 FOR UPDATE
  계정이 있으면 account row FOR UPDATE 후 snapshot hash/version과 현재 값을 다시 비교
  snapshot이 바뀌었으면 attempt·Session을 변경하지 않고 동일한 401로 종료
  blockedUntil과 15분 window 경계를 고정 Clock의 now로 평가
  실패: 두 카운터 증가; 5번째면 CustomerAccount LOCKED + lockedUntil + credentialVersion 증가;
        30번째 IP 실패면 IP blockedUntil 설정; commit 뒤 401 또는 429
  성공: 만료된 계정 잠금을 ACTIVE로 복귀하고 LOGIN_ID attempt row 삭제(IP row는 유지)
        LoginSessionCoordinator가 오래된 Session 폐기 + 현재 Session ID 회전 + 새 Session 저장
        전부 commit한 뒤 200과 Cookie 응답
```

- 비밀번호 검증은 계정이 없어도 **동일한 비용**으로 수행한다. 존재하지 않는 아이디에 대해
  더미 Hash를 검증해 타이밍 차이를 줄인다.
- attempt row는 현재 시간이 `blockedUntil`에 도달한 뒤 차단을 해제한다. 차단 중에는 카운터를 더
  올리지 않는다. 차단이 없고 `now >= windowStart + 15분`이면 현재 요청을 새 창의 첫 실패로 센다.
  카운터는 LOGIN_ID 5, IP 30에서 포화한다. 5번째 없는 계정 시도도 LOGIN_ID row를 15분 차단하지만
  이후 고객 가입 transaction이 같은 HMAC row를 삭제하므로 새 계정이 이전 공격 시도를 상속하지 않는다.
- 두 attempt row를 먼저 원자적으로 생성한 뒤 정렬 잠금하고, 그 다음 account row와 Session row를
  잠그는 순서를 모든 고객·점주 로그인에서 공유한다. 잠금 순서를 바꾸는 별도 경로를 만들지 않는다.
- 비밀번호 검증 뒤 account snapshot이 바뀌면 이전 자격증명으로 Session을 만들지 않는다. 이 경로는
  계정 존재를 드러내지 않는 `401 AUTHENTICATION_FAILED`이며 실패 카운터도 변경하지 않는다.
- PointAccount provisioning은 같은 PostgreSQL transaction에 참여하는 Loyalty public port다. Identity
  Repository가 Loyalty table을 직접 쓰지 않고 두 Aggregate 사이 JPA 연관관계도 만들지 않는다.
- 외부 호출은 없다.
- Spring Security `Argon2PasswordEncoder`가 요구하는
  `org.bouncycastle:bcprov-jdk18on:1.84`를 명시적 production dependency로 추가한다. constructor는
  salt 16 byte, hash 32 byte, parallelism 1, memory `19 * 1024` KiB, iterations 2로 BR-35를 정확히
  전달하고 deprecated default factory에 의존하지 않는다.
- 대안인 bcrypt는 72-byte 입력 한계가 있고 PBKDF2는 memory-hard하지 않아 신규 계정 기본값으로
  선택하지 않는다. 별도 native Argon2 binding은 배포 ABI와 native image라는 새 운영 경계를 만들어
  P0에서 기각한다.
- 운영 비용은 동시 hash당 약 19 MiB와 측정된 CPU, provider 보안 업데이트다. 지원되지 않는 provider,
  PHC decode 또는 startup self-test 실패는 기동 실패이며 bcrypt/default hash로 fallback하지 않는다.
- 제거·교체 시 기존 PHC 검증을 유지한 versioned encoder와 로그인 성공 시 재hash migration, 실패·
  rollback 테스트가 먼저 필요하다.

공식 근거:

- Spring Security password storage: <https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html>
- Bouncy Castle Java releases: <https://www.bouncycastle.org/download/bouncy-castle-java/>

## Alternatives Considered

### 1. 가입 성공과 아이디 중복을 같은 202로 숨김

- 장점: 사용자명 점유 여부도 감춘다.
- 단점: 고객이 가입이 실패했다는 사실을 알 수 없어 이후 로그인을 고칠 수 없다. 사용자명은 외부
  이메일·전화번호가 아니므로 이 사용성 손실을 정당화할 PII 보호 이득이 없다.

### 2. 가입과 동시에 로그인

- 장점: 단계가 줄어든다.
- 단점: 계정 생성 transaction은 성공했지만 Session 저장이 실패한 경우 응답과 실제 계정 상태가
  달라진다. P0는 가입 성공을 명확히 반환한 뒤 별도 로그인으로 복구 경계를 단순하게 둔다.

### 3. 전화번호 OTP(디자인 원안)

- 장점: 비밀번호가 없어 UX가 단순하다.
- 단점: SMS Provider가 저장소에 없다. 없는 의존성을 fake로 대체하는 것은 금지다.

### 4. Keycloak에 고객 계정 위임

- 장점: 자체 인증 구현이 없다.
- 단점: [ADR-092](../../adr/ADR-092-hybrid-authentication.md)에서 기각했다.

## Failure Semantics

- 자격증명 불일치: 401. 계정 없음과 비밀번호 불일치를 응답으로 구분하지 않는다.
- 가입 사용자명 중복: 409 `LOGIN_ID_UNAVAILABLE`. 다른 오류나 성공으로 위장하지 않는다.
- 잠금: 401과 동일한 형태. 잠금 사실을 응답으로 알리지 않는다. 잠금 여부는 지표와 로그로만 관찰한다.
- 시도 카운터 저장 실패: 503. 카운터 없이 로그인을 허용하지 않는다.
- IP 실패 한도 도달: 429 `AUTHENTICATION_RATE_LIMITED`와 `Retry-After`. 계정 존재 여부는 노출하지 않는다.
- 비밀번호 정책 위반: 400. 어떤 규칙을 위반했는지는 알려준다(계정과 무관한 정보이므로).
- 로그인 Session 폐기·회전·저장 실패: 로그인 transaction 전체 rollback 후 503. 실패 카운터 초기화나
  잠금 만료 전이도 commit하지 않으며, 같은 자격증명으로 재로그인한다.
- PointAccount provisioning 또는 flush 실패: 가입 전체 rollback 후 503. CustomerAccount만 저장하거나
  zero-balance 응답으로 대체하지 않는다.

## Data and Migration

```sql
CREATE TABLE identity_customer_account (
    id             uuid        PRIMARY KEY,
    login_id       varchar(32)  NOT NULL,
    password_hash  varchar(255) NOT NULL,
    credential_version bigint    NOT NULL,
    display_name   varchar(100) NOT NULL,
    state          varchar(32)  NOT NULL,   -- ACTIVE / LOCKED
    locked_until   timestamptz,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL,
    CONSTRAINT ck_identity_customer_login_id
      CHECK (login_id ~ '^[a-z0-9][a-z0-9._-]{3,30}[a-z0-9]$'),
    CONSTRAINT ck_identity_customer_password_hash
      CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_identity_customer_state
      CHECK (state IN ('ACTIVE', 'LOCKED')),
    CONSTRAINT ck_identity_customer_lock_shape
      CHECK ((state = 'ACTIVE' AND locked_until IS NULL)
          OR (state = 'LOCKED' AND locked_until IS NOT NULL)),
    CONSTRAINT ck_identity_customer_versions
      CHECK (credential_version >= 0 AND version >= 0),
    CONSTRAINT ck_identity_customer_timestamps
      CHECK (created_at <= updated_at)
);
CREATE UNIQUE INDEX ux_identity_customer_account_login_id
    ON identity_customer_account (login_id);

CREATE TABLE identity_login_attempt (
    id            uuid        PRIMARY KEY,
    actor_type    varchar(16) NOT NULL,   -- CUSTOMER / MERCHANT
    scope_type    varchar(16) NOT NULL,   -- LOGIN_ID / IP
    scope_hmac    char(64)    NOT NULL,
    window_start  timestamptz NOT NULL,
    failure_count integer     NOT NULL,
    blocked_until timestamptz,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT ux_identity_login_attempt_scope
      UNIQUE (actor_type, scope_type, scope_hmac),
    CONSTRAINT ck_identity_login_attempt_actor
      CHECK (actor_type IN ('CUSTOMER', 'MERCHANT')),
    CONSTRAINT ck_identity_login_attempt_scope
      CHECK (scope_type IN ('LOGIN_ID', 'IP')),
    CONSTRAINT ck_identity_login_attempt_hmac
      CHECK (scope_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_identity_login_attempt_count
      CHECK ((scope_type = 'LOGIN_ID' AND failure_count BETWEEN 1 AND 5)
          OR (scope_type = 'IP' AND failure_count BETWEEN 1 AND 30)),
    CONSTRAINT ck_identity_login_attempt_block_shape
      CHECK ((scope_type = 'LOGIN_ID'
              AND ((failure_count < 5 AND blocked_until IS NULL)
                OR (failure_count = 5 AND blocked_until IS NOT NULL)))
          OR (scope_type = 'IP'
              AND ((failure_count < 30 AND blocked_until IS NULL)
                OR (failure_count = 30 AND blocked_until IS NOT NULL)))),
    CONSTRAINT ck_identity_login_attempt_timestamps
      CHECK (window_start <= updated_at
         AND (blocked_until IS NULL OR blocked_until > updated_at))
);
CREATE INDEX ix_identity_login_attempt_retention
    ON identity_login_attempt (updated_at, id);
```

- 기존 주문의 `customer_id`는 이 테이블의 `id`와 같은 UUID 공간이다. 기존 데모 데이터는 계정이
  없으므로 seed를 다시 만든다. 실제 사용자 데이터가 없으므로 backfill이 필요하지 않다.
- 신규 가입 transaction은 기존 `loyalty_point_account`에 같은 customer ID의 계정을 생성한다.
  Identity migration이 Loyalty table을 다시 만들거나 컬럼을 소유하지 않는다. customer import가
  추가되면 CustomerAccount/PointAccount coverage preflight를 별도 migration에서 검증한다.
- 이 사실을 migration 주석에 남긴다.

## API and Event Contracts

```http
POST /api/v1/auth/customer/registrations
POST /api/v1/auth/customer/sessions
GET  /api/v1/me
DELETE /api/v1/auth/customer/sessions/current
```

```text
POST /auth/customer/registrations
  request  { loginId, password, displayName }
  response 201 { loginId }
  중복     409 { code: "LOGIN_ID_UNAVAILABLE" }

POST /auth/customer/sessions
  request  { loginId, password }
  response 200 { actorType, customerId, displayName } + Session Cookie
  실패     401 { code: "AUTHENTICATION_FAILED" }
```

- 오류 코드는 [Error Catalog](../../api/error-catalog.md)에 추가한다.
- 이벤트 계약 변경 없음. 계정 생성 이벤트를 발행하지 않는다.

## Milestones

1. migration writer lease 획득, 계정·시도 테이블 migration.
2. 비밀번호 Hash 알고리즘 선택과 설정(강도는 Business Policy 값).
3. 가입 endpoint와 enumeration 방지 응답.
4. Loyalty PointAccount provisioning port와 가입 원자성 구현.
5. 로그인 endpoint와 시도 제한·잠금.
6. `GET /me` 고객 분기 구현.
7. 고객 현재 Session 로그아웃 endpoint 구현.
8. `OrderController` 등 고객 경로가 `CustomerActor`만 사용하는지 확인.
9. 로컬 데모 seed와 smoke를 계정·PointAccount 기반으로 전환.
10. runtime OpenAPI와 계약 테스트 갱신.

## Required Tests

- 존재하는 아이디와 존재하지 않는 아이디의 로그인 실패 응답이 동일한지 검증한다.
- 가입 시 canonical 중복 아이디가 409이고, 동시 가입에서는 Unique Constraint로 한 건만 201인지
  검증한다.
- 사용자명 길이·허용 문자·첫끝 문자·ASCII 대소문자 canonicalization을 계약 테스트로 검증한다.
- 실패 누적이 임계값에서 잠금으로 전이하고, 잠금 중에는 올바른 비밀번호도 실패하는지 검증한다.
- 잠금 해제 시각 이후 로그인이 성공하는지 고정 `Clock`으로 검증한다.
- IP별 카운터와 아이디별 카운터가 독립적으로 동작하는지 검증한다.
- [BR-35](../../product/business-policy-decisions.md)의 5회/30회, 15분 창·잠금·IP 차단 경계를 검증한다.
- attempt row에 사용자명·IP 원문이 없고 HMAC key 누락 시 기동 실패하는지 검증한다.
- 24시간 보존 worker가 bounded·재실행 가능하며 실패 시 인증 fallback을 만들지 않는지 검증한다.
- 동시 로그인 실패 요청에서 카운터가 유실·중복되지 않는지 PostgreSQL Testcontainers로 검증한다.
- LOGIN_ID/IP row의 결정적 잠금 순서에서 고객·점주 동시 로그인에 deadlock과 lost update가 없는지
  PostgreSQL Testcontainers로 검증한다.
- 없는 ID에 5회 실패한 뒤 같은 고객 ID를 가입하면 이전 LOGIN_ID attempt row가 삭제되고 첫 로그인이
  잠금을 상속하지 않는지 검증한다.
- 비밀번호 검증 중 자격증명이 바뀌면 실패 카운터·Session 없이 동일한 401인지 검증한다.
- 로그인 성공 후 Session ID가 회전하는지 검증한다.
- 고객 6번째 동시 로그인에서 가장 오래된 Session 폐기와 새 Session 저장이 한 transaction이며,
  삭제·회전·저장 장애 각각이 전체 rollback과 503을 만드는지 검증한다.
- 비밀번호가 로그·응답·DB 어디에도 평문으로 남지 않는지 검증한다.
- 다른 고객의 Session으로 주문을 조회할 수 없는지 검증한다.
- 잠금 전 발급한 Session 행이 남아 있어도 `credentialVersion` 불일치로 401인지 검증한다.
- 요청 Body의 `customerId`가 무시되는지 검증한다.
- 가입 성공 후 CustomerAccount와 0원 PointAccount가 각각 한 건인지 검증한다.
- Loyalty save/flush 장애가 CustomerAccount까지 rollback하고 503인지 PostgreSQL 통합 테스트로 검증한다.
- Identity와 Loyalty 사이에 Repository 직접 접근·JPA 연관관계가 없는지 Modulith/ArchUnit으로 검증한다.
- 로컬 데모 smoke가 토큰 붙여넣기 없이 통과하는지 확인한다.

## Validation Commands

```bash
./gradlew test --tests 'io.github.kdh949.beanflow.identity.*'
./gradlew test --tests '*Authentication*'
./gradlew spotlessCheck
./gradlew build --stacktrace
bash scripts/demo/start.sh && bash scripts/demo/seed.sh && bash scripts/demo/smoke.sh
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

## Observability

- 가입 성공·`LOGIN_ID_UNAVAILABLE`·dependency failure 수
- 로그인 성공률과 실패 유형 분포
- 잠금 발생 수와 해제 수
- 아이디별·IP별 시도 분포
- 비밀번호 Hash 검증 지연 p95
- 가입 PointAccount provisioning 성공·실패 수

## Documentation Updates

- ADR-092와 고객 PointAccount provisioning ADR 구현 결과 반영
- `docs/product/business-policy-decisions.md`(시도 제한, 잠금 시간, 비밀번호 정책)
- `docs/security/authorization-matrix.md`
- `docs/api/error-catalog.md`
- `openapi/beanflow-v1-runtime.yaml`
- `docs/operations/local-demo-runbook.md`

## Progress

2026-08-13: Plan 20이 V52, 네 FilterChain, PostgreSQL Session/CSRF, typed CurrentActor와 login Session
lifecycle를 전체 995-test build 및 문서 검증으로 완료했다. exact Plan 20 completion head가 direct
dependency를 충족하므로 `Implementation-Ready=true`로 전환했다. 구현은 아직 시작하지 않았다.

## Surprises & Discoveries

아직 없다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-11 | P0 고객 인증은 ID/PW. 전화번호 OTP는 P1 | [C-1](../../product/design-contract-conflicts.md) |
| 2026-08-11 | 잠금 사실을 응답으로 알리지 않는다 | 이 plan |
| 2026-08-12 | 고객 로그인 ID는 이메일·전화번호가 아닌 사용자명이며 가입 중복은 409 | [BR-34](../../product/business-policy-decisions.md) |
| 2026-08-12 | 비밀번호 15~128자, 5회 계정 잠금·30회 IP 차단과 임시 비밀번호 24시간 | [BR-35](../../product/business-policy-decisions.md) |
| 2026-08-12 | 가입과 0원 PointAccount를 같은 transaction에서 생성 | [BR-42](../../product/business-policy-decisions.md) |

## Outcomes & Retrospective

아직 없다.

## Revision Notes

- 2026-08-11: 최초 작성.
- 2026-08-13: Plan 20 completion path와 actual validation evidence를 반영해 readiness를 true로 전환.
