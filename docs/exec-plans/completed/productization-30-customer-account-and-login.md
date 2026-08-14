# 고객이 계정을 만들고 로그인한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/productization-20-authentication-foundation.md`
> **Completed-At:** `2026-08-13`

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
- 로컬 데모의 `--customer-checkpoint` smoke가 Customer Session으로 승인 결제 조회까지 토큰
  붙여넣기 없이 통과하고 Merchant endpoint를 호출하지 않는지 확인한다. 매장 전환·환불을 포함한
  기본 전체 smoke는 account-backed Merchant Session이 생기는 productization-40이 소유한다.

## Validation Commands

```bash
./gradlew test --tests 'io.github.kdh949.beanflow.identity.*'
./gradlew test --tests '*Authentication*'
./gradlew spotlessCheck
./gradlew build --stacktrace
bash scripts/demo/start.sh && bash scripts/demo/seed.sh && bash scripts/demo/smoke.sh --customer-checkpoint
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

- 2026-08-13: Plan 20이 V52, 네 FilterChain, PostgreSQL Session/CSRF, typed CurrentActor와 login Session
  lifecycle를 전체 995-test build 및 문서 검증으로 완료했다. exact Plan 20 completion head가 direct
  dependency를 충족하므로 `Implementation-Ready=true`로 전환했다.
- 2026-08-13: `origin/main`을 Plan 20 branch에 history-preserving merge하고 전체 회귀를 통과한 exact
  Plan 20 completion `a6bf720` 위에서 이 plan을 시작했다. combined migration inventory의 마지막 V52를
  확인하고 V53 lease로 `identity_customer_account`와 actor/scope HMAC 전용
  `identity_login_attempt` schema를 추가했다. 신규 CustomerAccount에는 backfill을 만들지 않았다.
- 2026-08-13: BR-34/35의 canonical 사용자명, versioned common-password blocklist, Bouncy Castle
  Argon2id `m=19456,t=2,p=1`, 고정 dummy PHC와 startup stored-hash 검증을 구현했다. 운영 HMAC key는
  필수 설정이며 누락·짧은 key·지원하지 않는 PHC는 startup 실패다. source IP는 명시한 trusted proxy
  CIDR에서 온 forwarding header만 사용한다.
- 2026-08-13: 가입 Application Service가 CustomerAccount INSERT, Loyalty public provisioning port의
  세 잔액 0 PointAccount INSERT와 과거 CUSTOMER LOGIN_ID attempt 삭제를 한 transaction으로 조정한다.
  Loyalty adapter는 `MANDATORY`와 `EntityManager.persist + flush`를 사용해 선행 PointAccount를 merge
  성공으로 숨기지 않는다. trigger write failure와 Unique 충돌 모두 두 Aggregate 0건·503으로 rollback한다.
- 2026-08-13: 로그인은 transaction 밖에서 canonicalization/HMAC/Argon2id를 수행하고, transaction 안에서
  actor/scope/HMAC 정렬 attempt row, account row, Session row 순으로 잠근다. 5회 계정 잠금·30회 IP 차단,
  15분 exact boundary, `credentialVersion` Session 무효화, 검증 중 snapshot 변경 401, 24시간 bounded
  retention과 실패 재전파를 구현했다. 고객/점주 actor를 인자로 받는 공용 attempt 저장소로 Plan 40의
  동일 잠금 순서를 준비했지만 점주 계정·로그인은 구현하지 않았다.
- 2026-08-13: 고객 가입·로그인, `GET /me`, 현재 Session logout을 Customer Chain의 Session/CSRF 계약에
  연결했다. 로그인 transaction의 `LoginSessionCoordinator`가 유일하게 ID를 회전하도록 browser chain의
  중복 fixation rotation을 껐다. PostgreSQL에서 6번째 동시 로그인 5개 상한, session insert 실패 시
  기존 Session 삭제와 만료 잠금 활성화 rollback, 다른 고객 주문 403과 위조 body `customerId` 무시를
  검증했다.
- 2026-08-13: local demo가 고정 고객 계정과 실제 0원 PointAccount를 같은 fixture에 seed하고, smoke가
  고객 XSRF 발급→ID/PW 로그인→Session Cookie로 모든 고객 호출을 수행하도록 바꿨다. 고객 JWT는 더
  생성하지 않는다. 모든 HTTP 호출은 기존 `call` helper를 유지하며 Demo safety/seed/guard 17 tests가
  통과했다.
- 2026-08-13: 첫 집중 인증 묶음은 38 tests 중 HMAC startup failure의 최상위 예외 메시지 검사 1건이
  실패했고 root cause 계약으로 교정한 뒤 통과했다. 필수 경로 보강 뒤 Customer integration/security
  24 tests와 Demo 17 tests가 통과했다. 정책 BR-34/35/42, ADR-092/109, authorization matrix, error
  catalog, local demo runbook과 runtime OpenAPI를 actual outcome에 맞춰 갱신했다.
- 2026-08-13: 첫 전체 build는 1,023 tests 중 7건이 실패했다. 새 Customer Controller 의존성을 반영하지
  않은 runtime OpenAPI parity slice 1건, V53 이후에도 V52를 latest로 단정한 Audit/Support migration
  test 각 1건, production Customer loader와 test loader가 충돌한 browser Session test 4건이었다. fixture와
  latest-ownership assertion을 교정한 두 번째 전체 build는 1,023 tests, 0 failures, 0 errors, 기존 opt-in
  benchmark 1건 skipped로 통과했다.
- 2026-08-13: 필수 demo chain의 첫 실행은 narrow ordinary-accrual-policy bootstrap context가
  `AuditRecordService`의 `RetentionPolicyOperations` 의존성을 포함하지 않아 step 3에서
  `DEPENDENCY_UNAVAILABLE`로 중단됐다. Retention policy entity/repository/service를 같은 narrow context에
  명시하고 그 context 자체를 띄우는 회귀 테스트를 추가했다. 테스트의 첫 시도는 운영 CLI만 적용하던
  Modulith auto-configuration 제외 목록을 공유하지 않아 실패했고, production/test가 같은 상수를
  사용하도록 교정한 뒤 관련 4 tests가 통과했다.
- 2026-08-13: 다음 demo 기동은 `origin/main`에서 유입된 V42 Support entity의 `Int`와 PostgreSQL
  `smallint` 불일치로 Hibernate `ddl-auto=validate`에서 중단됐다. 문자열 `columnDefinition`만 추가한
  첫 교정은 JDBC 기대 타입이 여전히 `INTEGER`여서 실패했다. V42의 세 `smallint` 필드
  (`invalid_attempts`, `max_reveals`, `reserved_reveals`)를 persistence `Short`로 정합화하고 domain/API
  경계에서 `Int`로 변환한 뒤 Support/Bootstrap focused tests와 실제 application health가 통과했다.
- 2026-08-13: 프런트엔드 의존성이 설치되지 않은 환경의 demo 실행은 `openapi-typescript: command not
  found`로 중단됐다. 추적 파일 변경 없이 lockfile 기반 `npm ci`를 실행한 다음 start와 seed가 통과했고,
  smoke는 고객 Session 로그인, discovery, 주문, 멱등 replay와 결제 조회까지 통과했다. 이후 Merchant
  Session 전용 `/store-orders/**`에 legacy `STORE_OWNER` JWT를 보낸 단계는 의도대로 403이었다. fake/JWT
  fallback이나 Plan 40 선행 구현으로 숨기지 않고 실제 sequencing conflict로 기록했다.
- 2026-08-13: latest worktree에서 exact identity test, `*Authentication*`, `spotlessCheck`가 다시 통과했다.
  Support persistence와 narrow bootstrap 회귀를 포함한 전체 build는 11분 33초, 1,024 tests,
  0 failures, 0 errors, 기존 opt-in benchmark 1건 skipped로 통과했고 `git diff --check`도 통과했다.
  문서 검증은 target OpenAPI 153 paths/159 operations, runtime 121/125, schemas 305, Business Policy 46,
  ADR 111, Markdown 274, ExecPlan 57을 검증해 통과했다.
- 2026-08-13: 사용자는 Plan 30 smoke를 승인 결제 조회까지의 명시적 `--customer-checkpoint`로
  분리하고, Merchant 전환·환불 기본 전체 smoke는 Plan 40에서 account-backed Merchant Session으로
  복원하기로 결정했다. RED에서 unknown checkpoint와 Merchant 미호출 계약 2건이 실패했고, strict
  argument parsing과 payment query 직후 성공 terminal을 추가한 뒤 `LocalDemoScriptGuardTest` 11건이
  통과했다. 인자 없는 기본 전체 smoke 본문은 삭제·완화하지 않고 Plan 40 검증으로 보존했다.
- 2026-08-13: clean demo DB에서 `start.sh` → `seed.sh` → `smoke.sh --customer-checkpoint`를 실행했다.
  policy bootstrap, application, frontend 기동과 25-row seed 뒤 실제 고객 가입·로그인 Session, discovery,
  0원 PointAccount, 주문 생성/동일 replay/payload mismatch, 결제 prepare/confirm replay와 tamper 거부,
  승인 결제 조회까지 17 HTTP 단계가 통과했다. 종료 뒤 `stop.sh --reset`으로 demo 상태를 정리했다.
- 2026-08-13: 최종 exact identity tests, `*Authentication*`, `spotlessCheck`와 전체 `build --stacktrace`가
  통과했다. 전체 build는 11분 35초, 1,026 tests, 0 failures, 0 errors, 기존 opt-in benchmark 1건
  skipped였다. 문서 검증은 target OpenAPI 153 paths/159 operations, runtime 121/125, schemas 305,
  Business Policy 46, ADR 111, Markdown 274, ExecPlan 57을 검증했고 `git diff --check`도 통과했다.
- 2026-08-13: 추가 공급망 검토의 첫 `npm audit --audit-level=high`는 기존 OpenAPI 생성 도구의
  transitive `@redocly/openapi-core 1.34.18 → js-yaml 4.3.0`에서 high 2건을 보고했다. 호환 patch인
  `1.34.19`와 `4.3.1`로 lockfile을 갱신한 뒤 audit는 0 vulnerabilities였고 schema 재생성도
  deterministic했다. 이어 실행한 frontend build는 기존 화면 세 호출이 필수 `X-BEANFLOW-CSRF` 타입을
  아직 전달하지 않아 TypeScript에서 실패했다. 고객 Cookie/CSRF client와 화면 전환은 명시적인 Plan 80
  범위이므로 이 Plan에서 placeholder header, JWT fallback 또는 OpenAPI optional 완화로 숨기지 않았다.
- 2026-08-14: PR #65 재검토에서 trusted proxy가 기존 `X-Forwarded-For` 앞부분을 보존·append할 때
  leftmost 값을 source IP로 쓰면 attacker가 로그인 IP limit key를 분산시킬 수 있음을 확인했다. complete
  chain을 right-to-left로 검사해 trusted hop을 제거하고 첫 untrusted literal을 선택하도록 교정했다.
  attacker prefix, multiple trusted hop, IPv4/IPv6 mixed, malformed chain과 untrusted direct peer
  regression이 통과했다.
- 2026-08-14: Stack merge CI가 Plan 20의 `BrowserSessionProbeConfiguration` test loader와 이 Plan의
  account-backed `CustomerBrowserActorLoader`를 같은 bean 이름으로 등록해 `AuthenticationSecurityIntegrationTest`
  context를 시작하지 못한 것을 확인했다. Customer probe가 임시 loader를 덮어쓰지 않고 유효한 ACTIVE account와
  matching credentialVersion Session을 만든 뒤 production loader를 통과하도록 바꿨다. Merchant probe는 아직
  Plan 40의 account-backed loader가 없으므로 유지한다. focused security integration test가 통과했다. 첫
  `--rerun-tasks` 전체 실행은 먼저 시작한 build를 종료하면서 동일 `build/test-results`의 in-progress file이
  사라져 `NoSuchFileException`으로 실패했으므로 통과로 취급하지 않았다. 모든 Gradle process가 끝난 뒤 단일
  `clean build --stacktrace`를 재실행해 1,032 tests, 0 failures, 0 errors, 1 skipped로 12분 54초에 통과했다.

## Surprises & Discoveries

- Spring JDBC raw `JdbcTemplate`에 `Instant`를 직접 넘긴 attempt fixture가 PostgreSQL에서 SQL grammar
  오류로 번역됐다. production/test raw JDBC timestamp 입력을 `Timestamp.from`으로 명시해 timezone
  의미를 유지했다.
- 로그인 성공 transaction에서 Session ID를 이미 회전했는데 Spring Security fixation protection이
  응답 단계에서 한 번 더 회전해 최초 `/me` 뒤 browser Cookie가 stale해졌다. coordinator가 account
  lock과 같은 transaction에서 회전·상한을 소유하므로 Customer/Merchant browser chain의 중복 회전을
  비활성화하고 PostgreSQL 회전·logout 테스트로 확인했다.
- 최초 LoginAttemptRepository와 HMAC 구현이 SQL 문자열에 `CUSTOMER`를 고정해 Required Test의 고객·점주
  공용 잠금 순서를 증명할 수 없었다. actor type을 명시 인자로 올리고 동일 actor의 동시 요청과 서로
  다른 actor namespace를 한 저장소에서 검증했다.
- assigned UUID를 가진 PointAccount에 `saveAndFlush`를 호출하면 Spring Data가 `persist` 대신 `merge`를
  선택해 선행 PointAccount 충돌을 201 성공으로 숨겼다. 실패한 신규 검증이 이 결함을 드러냈고,
  `EntityManager.persist + flush`와 typed public failure로 교정한 뒤 전체 rollback·503을 확인했다.
- 고정 Clock을 통합 테스트에 도입한 첫 실행은 기존 `now()`/`Instant.now()` fixture 세 곳과 DB timestamp
  constraint가 섞여 4/23 tests가 실패했다. 모든 인증 경계 입력을 하나의 mutable fixed Clock으로
  정렬한 뒤 exact boundary와 재실행이 통과했다.
- 새 customer login을 smoke helper 밖의 raw `curl`로 작성하자 `LocalDemoRepositorySafetyTest`가 즉시
  실패했다. CSRF와 Session login도 기존 `call` helper를 통과하도록 확장해 첫 실패 중단·correlation
  규칙을 보존했다.
- seed 전용 Spring context는 17개 Demo 테스트를 통과하지만 종료 때 제외한 Modulith runtime entity를
  조회하려는 기존 `eventPublicationRegistry`의 `UnknownEntityException` warning을 한 번 남긴다. 이는
  non-zero 검증 실패가 아니며 product application context에서는 재현되지 않았지만 최종 결과에서
  warning으로 보존한다.
- narrow ordinary-accrual-policy bootstrap은 전체 application context 테스트가 통과해도 필요한
  Operations bean을 누락할 수 있었다. `AuditRecordService`의 retention dependency가 추가된 뒤에도 CLI가
  예외를 terminal `DEPENDENCY_UNAVAILABLE`로만 노출해 원인을 숨기지는 않았지만 진단 정보는 app log에
  없었다. 실제 narrow context를 기동하는 회귀 테스트를 별도로 둬 production 구성 누락을 검증한다.
- 전체 build가 통과한 뒤에도 local profile의 Hibernate `ddl-auto=validate`는 V42의 세 Support
  `smallint`/`Int` 불일치를 순서대로 발견했다. migration constraint가 허용하는 값은 작지만 domain/API는
  `Int`가 자연스러우므로 persistence만 `Short`로 맞추고 변환 경계를 명시했다. `columnDefinition`은 SQL
  이름만 바꾸고 JDBC type code를 바꾸지 않아 해결책이 아니었다.
- Plan 20은 account-backed Merchant loader가 생기는 Plan 40 전까지 Merchant 보호 경로를 401/403으로
  닫고 JWT·fake Session fallback을 금지한다. 반면 기존 full demo smoke는 고객 주문 뒤 Merchant
  transition과 refund가 있어 Plan 30만으로는 끝까지 진행할 수 없다. 이는 테스트 fixture 실패가 아니라
  두 ExecPlan 완료 조건의 실제 sequencing conflict이며 사용자 결정 전에는 성공으로 축소 기록하지 않는다.
- 이 sequencing conflict는 고객 경로 검증을 약화하지 않고 actor별 availability 시점에 맞춰 smoke
  checkpoint 소유권을 분리해야 해결된다. Plan 30은 결제 승인 상태 조회까지, Plan 40은 Merchant Session
  로그인 뒤 주문 전환·적립·환불과 authorization failure까지 소유한다. default full flow는 유지하므로
  두 checkpoint가 서로 다른 대체 구현으로 갈라지지 않는다.
- latest full build 종료 시 한 test context의 Modulith registry가 `PaymentRefundedV1` publication 두 건을
  unfinished INFO로 보고했다. JUnit XML은 1,026 tests 중 failure/error 0이고 build exit도 0이었지만,
  clean event backlog라고 주장하지 않고 기존 test shutdown 관측값으로 남긴다.
- runtime OpenAPI를 생성한 TypeScript schema는 Customer unsafe operation의 필수 CSRF header를 정확히
  드러냈고, 수동 Bearer token을 쓰는 기존 화면 세 곳의 compile failure를 노출했다. 이는 API 실패를
  성공으로 대체한 것이 아니라 Plan 20이 허용한 브라우저 중간 단절이며, Plan 80 전에는 frontend 전체
  build 통과를 주장하지 않는다.
- configured trusted direct peer만으로 forwarding header 전체가 안전해지지는 않는다. trusted proxy가
  append한 observed source는 chain의 오른쪽에 있으므로 leftmost 값은 client-controlled prefix일 수 있다.
  모든 chain literal을 parse한 뒤 right-to-left로 trusted hop을 제거해야 rate-limit source가 보존된다.
- Plan 20의 same-browser probe는 Customer/Merchant actor loader가 아직 없는 상태에서는 임시 loader로
  충분했지만, Plan 30이 production Customer loader를 추가하자 Spring의 bean override 금지와 충돌했다.
  Customer는 test-only override를 허용하는 대신 실제 account state를 우회하지 않고, 시험 계정과 Session의
  `credentialVersion`을 같은 값으로 저장해 production loader를 검증 경로에 유지했다. Merchant probe는
  account-backed loader가 도입되는 Plan 40에서 같은 방식으로 전환해야 한다.
- 같은 worktree에서 full build를 두 번 겹쳐 실행하면 먼저 종료된 Gradle이 test result binary를 정리해
  다른 실행이 `NoSuchFileException`으로 끝날 수 있다. 이 실행 실패는 product test failure가 아니지만
  전체 검증 근거가 될 수 없으므로, process가 하나도 없는 것을 확인한 뒤 clean build를 단일 실행했다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-11 | P0 고객 인증은 ID/PW. 전화번호 OTP는 P1 | [C-1](../../product/design-contract-conflicts.md) |
| 2026-08-11 | 잠금 사실을 응답으로 알리지 않는다 | 이 plan |
| 2026-08-12 | 고객 로그인 ID는 이메일·전화번호가 아닌 사용자명이며 가입 중복은 409 | [BR-34](../../product/business-policy-decisions.md) |
| 2026-08-12 | 비밀번호 15~128자, 5회 계정 잠금·30회 IP 차단과 임시 비밀번호 24시간 | [BR-35](../../product/business-policy-decisions.md) |
| 2026-08-12 | 가입과 0원 PointAccount를 같은 transaction에서 생성 | [BR-42](../../product/business-policy-decisions.md) |
| 2026-08-13 | Argon2id provider는 production dependency `bcprov-jdk18on:1.84`, exact constructor parameter와 startup self-test로 고정 | 이 plan, [BR-35](../../product/business-policy-decisions.md) |
| 2026-08-13 | LoginAttempt persistence와 HMAC은 CUSTOMER/MERCHANT actor를 명시 인자로 받아 같은 정렬 잠금 구현을 공유 | 이 plan, [BR-35](../../product/business-policy-decisions.md) |
| 2026-08-13 | Session 회전의 단일 authority는 account transaction 안의 LoginSessionCoordinator이며 FilterChain의 별도 fixation rotation은 사용하지 않음 | [ADR-094](../../adr/ADR-094-browser-session-security.md) |
| 2026-08-13 | PointAccount provisioning은 merge가 아닌 명시적 INSERT이며 선행 row는 typed dependency failure와 전체 rollback | [ADR-109](../../adr/ADR-109-customer-point-account-provisioning.md) |
| 2026-08-13 | local demo 고객 호출은 seeded ID/PW와 Customer Session을 사용하고 고객 JWT를 만들지 않음 | [ADR-092](../../adr/ADR-092-hybrid-authentication.md), [local demo runbook](../../operations/local-demo-runbook.md) |
| 2026-08-13 | 기존 customer/merchant URI는 각 Session에 유지하고 운영자 point/refund branch는 `/operations/**`로 분리하며 상대 actor credential fallback을 두지 않음 | [ADR-092](../../adr/ADR-092-hybrid-authentication.md), [authorization matrix](../../security/authorization-matrix.md) |
| 2026-08-13 | Plan 30 demo gate는 승인 결제 조회까지의 Customer Session checkpoint, Merchant 전환·환불 기본 전체 smoke는 Plan 40 gate로 분리 | 이 plan, [productization-40](../active/productization-40-merchant-account-and-initial-password.md), [local demo runbook](../../operations/local-demo-runbook.md) |
| 2026-08-14 | trusted proxy forwarding chain은 right-to-left로 trusted hop을 제거하고 첫 untrusted literal을 IP limit source로 사용. malformed trusted chain은 400, untrusted direct peer header는 무시 | [BR-35](../../product/business-policy-decisions.md) |
| 2026-08-14 | Customer same-browser isolation은 test-only loader override가 아니라 ACTIVE account와 matching credentialVersion을 만든 production BrowserActorLoader 경로로 검증하고, Merchant probe 전환은 Plan 40이 소유 | 이 plan, `AuthenticationSecurityIntegrationTest` |

## Outcomes & Retrospective

- V53 CustomerAccount/login-attempt schema, Argon2id/HMAC credential 경계, 고객 가입·로그인·현재 actor·
  logout과 PostgreSQL Session 회전을 구현했다. 가입은 Loyalty public port를 통해 실제 0원
  PointAccount와 원자적으로 commit되며 실패·동시 중복·잠금·rate limit·snapshot 변경·Session 저장
  장애가 성공으로 위장되지 않는다.
- required identity tests, Authentication tests, Spotless, 전체 build 1,026 tests와 문서/OpenAPI 검증이
  통과했다. clean local demo의 `--customer-checkpoint`도 실제 Customer Session으로 승인 결제 조회까지
  17 HTTP 단계를 통과했다. 전체 build의 기존 opt-in benchmark 1건 skip과 test shutdown의
  `PaymentRefundedV1` unfinished publication INFO 두 건은 그대로 기록했다.
- 추가 supply-chain audit의 high 2건은 dev-only OpenAPI 생성 도구 patch로 해소해 0 vulnerabilities를
  확인했다. 추가 frontend build는 Plan 80이 소유한 Cookie/CSRF client가 아직 없어 기존 호출 세 곳에서
  실패했다. 이를 placeholder header나 JWT fallback으로 숨기지 않았으며 이 Plan의 backend/demo gate
  완료를 frontend 통합 완료로 확대 해석하지 않는다.
- Plan 30 smoke는 승인 결제 조회에서 끝난다. account-backed Merchant Session, 초기 비밀번호 변경,
  매장 전환·포인트 적립·부분/전액 환불의 인자 없는 기본 전체 smoke는 Plan 40 완료 gate로 넘겼다.
- Stack merge CI가 발견한 Customer test bean collision은 actual account-backed loader regression으로 바꿔
  focused security integration test와 단일 clean full build(1,032 tests, 0 failures, 0 errors, 1 skipped)를
  통과시켰다. Merchant probe 전환은 Plan 40에서 이어진다. 앞선 overlapping Gradle rerun의 test-result
  `NoSuchFileException`은 code pass로 처리하지 않고 단일 clean build로 대체 검증했다.

## Revision Notes

- 2026-08-11: 최초 작성.
- 2026-08-13: Plan 20 completion path와 actual validation evidence를 반영해 readiness를 true로 전환.
- 2026-08-13: 고객 계정·로그인과 customer demo checkpoint 검증을 완료하고 completed로 이동.
- 2026-08-14: trusted proxy X-Forwarded-For source IP 경계를 actual review finding과 regression으로 보정.
- 2026-08-14: Stack merge CI의 browser actor loader test bean collision을 production loader regression으로 보정.
