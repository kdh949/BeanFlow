# 점주가 발급받은 계정으로 로그인하고 비밀번호를 바꾼다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/active/productization-20-authentication-foundation.md`, `docs/exec-plans/active/productization-30-customer-account-and-login.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

점주는 스스로 가입하지 않는다. 운영팀이 계정을 발급하고 점주가 첫 로그인에서 비밀번호를 바꾼다.
비밀번호를 바꾸기 전에는 매장 기능을 쓸 수 없다.

이 plan이 끝나면 점주는 자기 계정으로 로그인해 **자신이 접근 가능한 매장 목록**까지 볼 수 있다.
주문보드는 다음 plan이다.

## Current State

- `productization-20`이 Merchant Chain, Session, CSRF, `MerchantActor`와 Session lifecycle 공통
  기반을 제공한다. 점주 현재 actor와 매장 목록 endpoint는 이 plan이 구현한다.
- `identity_store_membership`과 `StoreAccessOperations.requireStoreAccess`가 존재한다.
  membership role은 `OWNER`, `STAFF` 2종이다.
- 점주 자격증명 테이블이 없다. `StoreMembership.actorId`를 만드는 주체가 없다.
- 현재 `StoreOrderController`는 JWT `roles`와 membership을 함께 확인한다. productization-20 전환 뒤
  JWT role은 제거되고 `MerchantActor` 유형 + 요청 시점의 authoritative membership role로 대체된다
  ([ADR-027](../../adr/ADR-027-store-membership-authorization.md)).
- 접근 가능한 매장 목록 endpoint가 없다.
- DB-backed `OperatorPermissionGrant`, idempotent command와 Audit 패턴이 이미 있다.
  `productization-20`이 점주 credential 관리 전용 `MERCHANT_CREDENTIAL_MANAGE` vocabulary를 먼저 추가한다.

## Definitions

- **Merchant account:** 점주·직원의 로그인 자격증명을 소유하는 Identity Aggregate다.
  `StoreMembership.actorId`와 같은 UUID다.
- **Login ID:** 운영자가 발급하는 사용자명이다. 고객과 같은 5~32자 canonical 규칙을 쓰지만
  CustomerAccount와는 분리된 점주 namespace에서 유일하다([BR-34](../../product/business-policy-decisions.md)).
- **Temporary password:** 운영자가 발급한 초기 비밀번호다. 유효기간이 있다.
- **INITIAL_PASSWORD:** 임시 비밀번호로 로그인했으나 아직 변경하지 않은 상태다.
- **Password change gate:** `INITIAL_PASSWORD` 상태에서 비밀번호 변경과 `/merchant/me` 외 모든
  요청을 막는 규칙이다.

## Scope

### In Scope

- `identity_merchant_account` 테이블과 자격증명(Hash만)
- 계정 lifecycle `INITIAL_PASSWORD` / `ACTIVE` / `EXPIRED`와 시간 제한 `lockedUntil` overlay
- `POST /auth/merchant/sessions` 로그인
- `POST /auth/merchant/password-changes` 비밀번호 변경
- `GET /merchant/me`, `DELETE /auth/merchant/sessions/current`
- Password change gate를 Chain 인가 규칙과 Application Service 양쪽에서 적용
- 로그인 실패 누적과 잠금
- `GET /merchant/me/stores` 접근 가능 매장 목록
- 운영 콘솔용 exact 계정 조회·계정+최초 membership 발급·비밀번호 초기화·잠금 해제 API와 `AuditRecord`
- 임시 비밀번호의 성공 응답 1회 표시와 secret 비저장 경계
- credential 관리 command idempotency와 retention worker
- 비밀번호 변경 시 기존 Session 전체 폐기

### Non-goals

- 점주 자체 회원가입, 입점 심사
- `MANAGER` 역할과 4자리 PIN step-up([C-6](../../product/design-contract-conflicts.md), P1)
- 직원 초대 화면과 권한 편집 API(P1)
- 비밀번호 재설정 발송 채널
- 주문보드와 매장 관리 기능

## Business Rules and Invariants

1. 비밀번호는 Hash만 저장한다. 임시 비밀번호도 평문으로 저장·로그하지 않는다.
2. `INITIAL_PASSWORD` 상태에서는 비밀번호 변경과 `/merchant/me` 외 모든 매장 API가 403이다.
3. 만료된 임시 비밀번호로는 로그인할 수 없다. 운영자 재발급이 필요하다.
4. `now < lockedUntil`이면 lifecycle 상태와 무관하게 올바른 비밀번호도 실패한다. 잠금 만료는
   lifecycle을 바꾸지 않으므로 `INITIAL_PASSWORD`가 조용히 `ACTIVE`가 되지 않는다.
5. 비밀번호 변경·초기화·잠금은 `credentialVersion`을 같은 transaction에서 증가시킨다. 기존
   Session 행이 남아 있어도 version 불일치로 즉시 401이다.
6. 계정 상태가 `ACTIVE`라고 매장 접근 권한이 생기지 않는다. `StoreMembership`이 소유한다.
7. 계정 생성·초기화·잠금 해제는 `AuditRecord`를 남기고 비밀번호 값·Hash를 감사에 남기지 않는다.
8. 매장 목록은 `ACTIVE` membership만 반환한다. `REVOKED`는 즉시 제외된다.
9. 비밀번호 길이·hash, 실패 창·잠금·IP 제한과 임시 비밀번호 24시간 만료는
   [BR-35](../../product/business-policy-decisions.md)를 적용한다.
10. 로그인 ID는 [BR-34](../../product/business-policy-decisions.md)의 고객과 같은 canonicalization과
    형식을 적용하고, 점주 계정끼리만 유일하다.
11. 운영자 발급은 [BR-46](../../product/business-policy-decisions.md)에 따라 MerchantAccount와 최초
    ACTIVE StoreMembership을 같은 transaction에서 만든다. 임시 비밀번호는 성공 응답에서 한 번만
    표시하고 조회·복호화할 persistent 값을 만들지 않는다.

## Architecture and Transaction Boundaries

```text
로그인 — transaction 밖
  productization-30과 같은 canonicalization·source IP·HMAC·account snapshot·Argon2id 검증

로그인 — Tx1
  productization-30과 같은 LOGIN_ID/IP attempt row 결정적 잠금 → account row 잠금 순서
  snapshot hash/version 재확인, 임시 비밀번호 만료와 lockedUntil 평가
  실패: 공통 카운터 갱신; 5번째면 lifecycle은 유지하고 lockedUntil + credentialVersion 증가
  성공: LOGIN_ID attempt row만 삭제; lifecycle이 INITIAL_PASSWORD 또는 ACTIVE인지 확인;
        LoginSessionCoordinator가 Session 상한 조정·ID 회전·JDBC 저장을 같은 transaction에서 완료

비밀번호 변경 — transaction 밖
  현재 account snapshot 조회, 현재 비밀번호 검증과 새 Argon2id Hash 계산

비밀번호 변경 — Tx1
  계정 row lock과 snapshot hash/version 재확인, temporaryPasswordExpiresAt·lockedUntil 재검사,
  새 Hash 저장, 상태 ACTIVE 전이,
       credentialVersion 증가
  Tx1: AuditRecord 저장
  이후: 증가한 credentialVersion으로 새 Session 발급
  이후: 이전 Spring Session 행 정리 예약(인가 안전성의 전제 아님)

계정 발급 (Operations Web)
  Tx 밖: 입력 canonicalization, CSPRNG 24 byte → Base64URL 32자, Argon2id Hash 계산
  Tx1: MERCHANT_CREDENTIAL_MANAGE grant row 잠금 + idempotency key/payload 선점
       → StorePolicyScopeOperations.requireExisting(storeId)
       → MerchantAccount INSERT + 최초 ACTIVE StoreMembership INSERT
       → 같은 MERCHANT LOGIN_ID HMAC attempt row 삭제
       → command idempotency outcome + AuditRecord 저장
  commit 뒤: temporaryPassword를 Cache-Control: no-store 응답에 한 번 포함

비밀번호 초기화 (Operations Web)
  Tx 밖: 새 CSPRNG 임시 비밀번호와 Argon2id Hash 계산
  Tx1: permission/idempotency/account row 잠금 + 새 Hash·24시간 기한·INITIAL_PASSWORD
       + lockedUntil NULL + credentialVersion 증가 + LOGIN_ID attempt row 삭제
       + command idempotency outcome + AuditRecord 저장
  commit 뒤: 새 temporaryPassword를 응답에 한 번 포함

잠금 조기 해제 (Operations Web)
  Tx1: permission/idempotency/account row 잠금 + lockedUntil NULL
       + LOGIN_ID attempt row 삭제 + command idempotency outcome + AuditRecord 저장
```

- `temporaryPasswordExpiresAt`이 authoritative boundary다. `INITIAL_PASSWORD`여도 `now >=` 기한이면
  로그인·기존 초기 Session·비밀번호 변경을 401로 거부한다. 로그인 transaction은 상태를 `EXPIRED`로
  materialize하지만, 그 write가 지연돼도 Security 계층이 timestamp로 즉시 거부한다. 재발급 CLI만
  새 Hash·기한·version과 `INITIAL_PASSWORD` 상태를 원자적으로 만든다.
- 로그인 attempt window·포화·차단 경계, 없는 ID 처리, snapshot 변경과 결정적 잠금 순서는
  `productization-30`의 공통 `LoginAttemptOperations`를 재사용한다. 점주 경로가 별도 알고리즘을 복제하지
  않는다.
- Operations Controller는 Operations Application Service만 호출한다. Service는 permission·idempotency·
  Audit를 조정하고, Identity public `MerchantCredentialAdministrationOperations`와 Merchant public
  `StorePolicyScopeOperations`를 호출한다. Controller가 Identity Repository를 직접 사용하거나
  MerchantAccount와 StoreMembership 사이 JPA 연관관계를 만들지 않는다. 내부 public port는 같은
  PostgreSQL transaction에 `MANDATORY`로 참여한다.
- CSPRNG·Hash는 DB lock 밖에서 계산하되 transaction 안에서 active permission을 다시 잠가 검증한다.
  선택적 read precheck는 CPU 낭비를 줄이는 최적화일 뿐 권한 source나 성공 조건이 아니다.
- 성공 응답 전에 transaction이 실패하면 평문 buffer를 폐기하고 비밀번호를 반환하지 않는다. JVM의
  immutable String으로 secret을 장기 보관하는 DTO·event를 만들지 않고 응답 직전 최소 범위에서만
  직렬화한다.
- Password change gate는 두 곳에서 적용한다. Chain 인가 규칙이 1차, Application Service가 2차다.
  한 곳만 두면 새 endpoint 추가 시 누락된다.
- 이전 Spring Session 행 정리 실패는 retry 상태·로그·metric으로 남긴다. version 검증이 이전
  Session을 이미 거부하므로 비밀번호 변경 성공을 503으로 뒤집지 않는다. 새 Session 발급이 실패하면
  503이며, 변경된 비밀번호로 다시 로그인할 수 있다.
- 외부 호출은 없다.

## Alternatives Considered

### 1. 임시 비밀번호 없이 운영자가 최종 비밀번호 설정

- 장점: 상태가 하나 줄어든다.
- 단점: 운영자가 점주의 최종 비밀번호를 안다. 점주 행위와 운영자 행위를 감사에서 분리할 수 없다.

### 2. 최초 로그인 시 경고만 표시

- 장점: 점주가 즉시 매장을 운영한다.
- 단점: 임시 비밀번호가 유출된 상태로 환불·정산 접근이 열린다. 경고는 통제가 아니다.

### 3. 계정 발급을 CLI에만 제공

- 장점: credential 관리 HTTP surface와 임시 비밀번호 UI가 없다.
- 단점: 운영자가 웹 업무 중 별도 배포 환경의 CLI로 이동해야 하고 최초 membership을 함께 발급하는
  제품 흐름이 완결되지 않는다. [BR-46](../../product/business-policy-decisions.md)에 따라 explicit
  permission·reason·idempotency·Audit가 있는 Operations Web을 선택한다.

### 4. 임시 비밀번호를 암호화 저장해 나중에도 조회

- 장점: 운영자가 응답을 놓쳐도 다시 볼 수 있다.
- 단점: 복호화 키·회전·조회 감사와 장기 credential exposure가 생긴다. Hash-only 불변식을 지키기 위해
  최초 성공 응답에서만 표시하고, 놓친 경우 새 초기화로 수렴한다.

### 5. Password change gate를 Application Service에만 적용

- 장점: 설정이 단순하다.
- 단점: 새 매장 endpoint를 추가할 때 gate 적용을 잊으면 조용히 뚫린다. 2중 적용이 안전하다.

## Failure Semantics

- 자격증명 불일치·잠금·만료: 401. 응답으로 원인을 구분하지 않는다. 원인은 지표와 로그로만 관찰한다.
- `INITIAL_PASSWORD` 상태의 매장 API 요청: 403과 전용 오류 코드. 클라이언트가 비밀번호 변경 화면으로
  보낼 수 있도록 코드는 구분한다.
- 시도 카운터 저장 실패: 503. 카운터 없이 로그인을 허용하지 않는다.
- 로그인 Session 폐기·회전·저장 실패: 로그인 transaction 전체 rollback 후 503. attempt 초기화와
  잠금 만료 전이도 commit하지 않는다.
- 비밀번호 변경 후 새 Session 발급 실패: 503. 자격증명 변경과 version 증가는 커밋됐으므로 변경된
  비밀번호로 재로그인해 수렴한다.
- 이전 Session 행 정리 실패: retry 상태·로그·metric을 남긴다. 이전 Session은 version 불일치로
  계속 401이며 성공 권한으로 복구하지 않는다.
- permission·idempotency·`AuditRecord` 저장 실패: 503 또는 403. 감사·권한·멱등 기록 없이 계정을
  생성·초기화·해제하지 않는다.
- 계정 발급·초기화·조기 해제 중 LOGIN_ID attempt row 정리 실패: 전체 rollback. account만 풀어 놓고
  attempt 차단을 남기지 않는다.
- account와 최초 membership 중복·검증 실패: 409 또는 400. 둘 중 하나를 고아 상태로 남기지 않는다.
- Merchant의 store 존재 조회 실패: 503. 없는 store로 해석하거나 membership만 만들지 않는다.
- create/reset 같은 idempotency key의 terminal replay: 부수효과와 secret 재생성 없이
  `409 TEMPORARY_PASSWORD_NOT_REPLAYABLE`. lock release의 같은 payload replay는 204를 재생한다.
  payload hash가 다르면 모두 `409 IDEMPOTENCY_KEY_REUSED`다.
- commit 후 HTTP 응답 유실: 성공 또는 실패로 추정하지 않는다. exact login ID 조회로 생성 여부를
  확인한 뒤 새 key의 password reset으로 수렴한다.
- membership 조회 실패: 503. 빈 매장 목록으로 대체하지 않는다.

## Data and Migration

```sql
CREATE TABLE identity_merchant_account (
    id                    uuid         PRIMARY KEY,
    login_id              varchar(32)  NOT NULL,
    password_hash         varchar(255) NOT NULL,
    credential_version    bigint       NOT NULL,
    display_name          varchar(100) NOT NULL,
    state                 varchar(32)  NOT NULL,
    temporary_password_expires_at timestamptz,
    password_changed_at   timestamptz,
    locked_until          timestamptz,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    version               bigint       NOT NULL,
    CONSTRAINT ck_identity_merchant_login_id
      CHECK (login_id ~ '^[a-z0-9][a-z0-9._-]{3,30}[a-z0-9]$'),
    CONSTRAINT ck_identity_merchant_password_hash
      CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_identity_merchant_state
      CHECK (state IN ('INITIAL_PASSWORD', 'ACTIVE', 'EXPIRED')),
    CONSTRAINT ck_identity_merchant_state_shape
      CHECK ((state = 'INITIAL_PASSWORD'
              AND temporary_password_expires_at IS NOT NULL
              AND password_changed_at IS NULL)
          OR (state = 'ACTIVE'
              AND temporary_password_expires_at IS NULL
              AND password_changed_at IS NOT NULL)
          OR (state = 'EXPIRED'
              AND temporary_password_expires_at IS NOT NULL
              AND password_changed_at IS NULL)),
    CONSTRAINT ck_identity_merchant_versions
      CHECK (credential_version >= 0 AND version >= 0),
    CONSTRAINT ck_identity_merchant_timestamps
      CHECK (created_at <= updated_at
         AND (temporary_password_expires_at IS NULL
           OR temporary_password_expires_at > created_at)
         AND (password_changed_at IS NULL OR password_changed_at >= created_at))
);
CREATE UNIQUE INDEX ux_identity_merchant_account_login_id
    ON identity_merchant_account (login_id);

CREATE TABLE operations_merchant_credential_command_idempotency (
    id                  uuid         PRIMARY KEY,
    operator_id         uuid         NOT NULL,
    operation           varchar(32)  NOT NULL,
    idempotency_key     varchar(128) NOT NULL,
    payload_hash        char(64)     NOT NULL,
    merchant_account_id uuid         NOT NULL,
    outcome             varchar(32)  NOT NULL,
    created_at          timestamptz  NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    CONSTRAINT ux_operations_merchant_credential_idempotency
      UNIQUE (operator_id, operation, idempotency_key),
    CONSTRAINT ck_operations_merchant_credential_operation
      CHECK (operation IN ('CREATE', 'RESET_TEMPORARY_PASSWORD', 'RELEASE_LOCK')),
    CONSTRAINT ck_operations_merchant_credential_outcome
      CHECK ((operation = 'CREATE' AND outcome = 'ACCOUNT_CREATED')
          OR (operation = 'RESET_TEMPORARY_PASSWORD' AND outcome = 'PASSWORD_RESET')
          OR (operation = 'RELEASE_LOCK' AND outcome = 'LOCK_RELEASED')),
    CONSTRAINT ck_operations_merchant_credential_payload_hash
      CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_operations_merchant_credential_retention
      CHECK (retention_expires_at = created_at + interval '90 days')
);
CREATE INDEX ix_operations_merchant_credential_idempotency_retention
    ON operations_merchant_credential_command_idempotency (retention_expires_at, id);
```

- 로그인 시도 카운터는 `productization-30`의 `identity_login_attempt`를 공유하고 `actor_type`을
  `MERCHANT`로 둔다. 사용자명과 IP는 [BR-35](../../product/business-policy-decisions.md)의 keyed
  HMAC으로만 저장한다.
- 기존 `identity_store_membership.actor_id`는 이 테이블의 `id`를 참조하지만 FK를 걸지 않는다.
  membership은 계정보다 먼저 만들어질 수 있고, 두 Aggregate는 ID로 참조한다
  ([ADR-003](../../adr/ADR-003-aggregate-reference-by-id.md)).
- 기존 데모 membership 행은 계정이 없으므로 seed를 다시 만든다.
- command idempotency row에는 평문·Hash·응답 body를 저장하지 않는다. terminal row는 UPDATE를 거부하는
  trigger로 immutable하게 만들고, Operations-owned worker가 기본 1시간마다 due row 최대 100개를
  `(retention_expires_at, id)` keyset 순서로 삭제한다. 정리 실패는 다음 실행에서 retry하고 metric·log로
  남기며 credential 명령의 권한·멱등 검사를 우회하지 않는다.

## API and Event Contracts

```http
POST /api/v1/auth/merchant/sessions
POST /api/v1/auth/merchant/password-changes
GET  /api/v1/merchant/me
GET  /api/v1/merchant/me/stores
DELETE /api/v1/auth/merchant/sessions/current
GET  /api/v1/operations/merchant-accounts?loginId={exactCanonicalLoginId}
POST /api/v1/operations/merchant-accounts
POST /api/v1/operations/merchant-accounts/{merchantAccountId}/temporary-password-resets
POST /api/v1/operations/merchant-accounts/{merchantAccountId}/lock-releases
```

```text
POST /auth/merchant/sessions
  request  { loginId, password }
  response 200 { actorType: "MERCHANT", merchantId, displayName, accountState }
  실패     401 { code: "AUTHENTICATION_FAILED" }

POST /auth/merchant/password-changes
  request  { currentPassword, newPassword }
  response 204 + 새 Session Cookie
  실패     400 { code: "PASSWORD_POLICY_VIOLATION" }

GET /merchant/me/stores
  response 200 [ { storeId, storeName, membershipRole } ]

INITIAL_PASSWORD 상태의 매장 API
  403 { code: "INITIAL_PASSWORD_CHANGE_REQUIRED" }

POST /operations/merchant-accounts
  header   Idempotency-Key
  request  { loginId, displayName, storeId, membershipRole: "OWNER"|"STAFF", reason }
  response 201 { merchantAccountId, loginId, accountState: "INITIAL_PASSWORD",
                 membership: { storeId, role }, temporaryPassword, temporaryPasswordExpiresAt }

GET /operations/merchant-accounts?loginId=
  header   X-Access-Reason
  response 200 { merchantAccountId, loginId, displayName, accountState, lockedUntil,
                 temporaryPasswordExpiresAt, memberships[] }
  없음     404 { code: "MERCHANT_ACCOUNT_NOT_FOUND" }

POST /operations/merchant-accounts/{merchantAccountId}/temporary-password-resets
  header   Idempotency-Key
  request  { reason }
  response 200 { merchantAccountId, accountState: "INITIAL_PASSWORD",
                 temporaryPassword, temporaryPasswordExpiresAt }

POST /operations/merchant-accounts/{merchantAccountId}/lock-releases
  header   Idempotency-Key
  request  { reason }
  response 204
```

- 세 Operations 명령 응답은 `Cache-Control: no-store`다. create/reset의 `temporaryPassword`는 같은
  idempotency key replay에서도 다시 반환하지 않는다. Operations UI는 mutation 자동 retry를 끄고,
  network outcome이 불명확하면 exact login ID 조회 후 reset으로 안내한다.
- exact account 조회는 `MERCHANT_CREDENTIAL_MANAGE`, `X-Access-Reason`과 같은 transaction의 접근
  Audit를 요구한다. 조회 결과와 Audit 중 하나라도 실패하면 503이며 빈 결과로 대체하지 않는다.
- 오류 코드는 [Error Catalog](../../api/error-catalog.md)에 추가한다.
- 이벤트 계약 변경 없음.

## Milestones

1. migration writer lease 획득, 계정·credential command idempotency 테이블 migration. 로그인 시도
   테이블의 MERCHANT scope는 선행 productization-30 계약을 그대로 사용한다.
2. 계정 Aggregate와 상태 전이 구현.
3. 로그인 endpoint와 만료·잠금 처리.
4. 비밀번호 변경 endpoint와 Session 전체 폐기.
5. Password change gate를 Chain과 Application Service에 적용.
6. `GET /merchant/me`, `GET /merchant/me/stores`와 점주 logout 구현.
7. Operations exact 조회·계정+최초 membership 발급·초기화·잠금 해제 API, permission/reason/idempotency와
   `AuditRecord` 구현.
8. 기존 `StoreOrderController` 인가가 `MerchantActor` + operation별 허용 membership role만 사용하는지 확인.
9. 로컬 데모 seed 전환, runtime OpenAPI와 계약 테스트 갱신.

## Required Tests

- `INITIAL_PASSWORD` Session으로 매장 주문 조회·상태 전이·환불·정산 요청이 모두 403인지 검증한다.
- 같은 상태에서 비밀번호 변경과 `/me`는 성공하는지 검증한다.
- 만료된 임시 비밀번호 로그인이 실패하는지 고정 `Clock`으로 검증한다.
- 실패 누적이 임계값에서 잠금으로 전이하고, 잠금 중 올바른 비밀번호도 실패하는지 검증한다.
- 잠금 만료 뒤 `INITIAL_PASSWORD`와 `ACTIVE` 계정이 각각 원래 lifecycle로 로그인하는지 검증한다.
- 로그인 실패 응답이 원인별로 구분되지 않는지 검증한다.
- 임시 비밀번호의 24시간 -1ns/at/+1ns에서 저장 상태 materialization 여부와 무관하게 at부터 기존
  INITIAL_PASSWORD Session·로그인·비밀번호 변경이 모두 401인지 검증한다.
- 계정 발급 시 고객과 같은 로그인 ID 길이·허용 문자·첫끝 문자·ASCII 대소문자 canonicalization을
  적용하고, 고객의 같은 ID와 무관하게 점주 namespace 중복만 거부하는지 검증한다.
- 비밀번호 변경 후 이전 Session ID가 401인지 검증한다.
- 이전 Session 행 정리 실패를 주입해도 `credentialVersion` 불일치로 401인지 검증한다.
- 계정 생성·초기화·잠금 해제가 `AuditRecord`를 남기고 비밀번호가 저장되지 않는지 검증한다.
- 없는 점주 ID에 5회 실패한 뒤 같은 ID로 계정을 발급해도 이전 LOGIN_ID attempt를 상속하지 않는지,
  초기화·조기 해제에서 account overlay와 attempt 차단이 함께 제거되는지 검증한다.
- account와 최초 ACTIVE membership, attempt 삭제, command outcome과 Audit가 모두 commit되거나 모두
  rollback하는지 PostgreSQL 통합 테스트로 검증한다.
- 존재하지 않는 store, 잘못된 role, account/membership unique 충돌과 Merchant store 조회 장애가 고아
  account/membership 없이 각각 4xx/503인지 검증한다.
- CSPRNG 24-byte/Base64URL 32자, Argon2id Hash만 저장, 응답 `no-store`와 DB·Audit·log·metric·error의
  평문 부재를 검증한다.
- create/reset 동일 key 동시 요청·같은 payload replay·다른 payload replay에서 부수효과가 한 번이고
  replay에 secret이 없으며, 응답 유실 뒤 조회→새 key reset으로 수렴하는지 검증한다.
- idempotency terminal row immutable trigger, 90일 -1ns/at/+1ns와 100개 bounded keyset cleanup,
  cleanup 장애의 retry·metric·명령 비우회를 검증한다.
- exact 계정 조회가 permission·reason·접근 Audit를 원자적으로 요구하고 temporaryPassword를 절대
  반환하지 않는지 검증한다.
- `AuditRecord` 저장 실패 시 계정이 생성되지 않는지 검증한다.
- `GET /merchant/me/stores`가 `ACTIVE` membership만 반환하고 `REVOKED`를 즉시 제외하는지 검증한다.
- membership 없는 점주가 다른 매장 API를 호출하면 403인지 검증한다.
- 동시 로그인 실패 요청에서 카운터가 유실·중복되지 않는지 PostgreSQL Testcontainers로 검증한다.
- 점주 4번째 동시 로그인에서 가장 오래된 Session 폐기와 새 Session 저장이 같은 transaction이며,
  삭제·회전·저장 장애가 전체 rollback과 503을 만드는지 검증한다.
- 24시간 임시 비밀번호와 5회 계정 잠금·30회 IP 차단의 경계를 고정 `Clock`으로 검증한다.
- 기존 매장 주문 lifecycle 테스트가 회귀 없이 통과하는지 확인한다.

## Validation Commands

```bash
./gradlew test --tests 'io.github.kdh949.beanflow.identity.*'
./gradlew test --tests '*StoreOrder*'
./gradlew spotlessCheck
./gradlew build --stacktrace
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

## Observability

- 최초 로그인부터 비밀번호 변경까지의 소요 시간 분포
- 임시 비밀번호 만료율과 재발급 수
- 계정 잠금 발생·해제 수
- `INITIAL_PASSWORD` 상태에서 차단된 요청 수
- 매장 권한 거부 수
- 계정 발급·초기화·잠금 해제 result, idempotency replay·mismatch와 dependency failure 수
- 임시 비밀번호 1회 응답 수(값은 기록하지 않음), 응답 유실 후 reset 수
- credential idempotency retention deleted count·oldest due age·failure 수

## Documentation Updates

- ADR-093 구현 결과 반영
- `docs/product/business-policy-decisions.md`(BR-34~35, BR-46)
- ADR-069의 `MERCHANT_CREDENTIAL_MANAGE` permission amendment
- `docs/security/authorization-matrix.md`(계정 상태와 매장 접근)
- `docs/api/error-catalog.md`
- `openapi/beanflow-v1-runtime.yaml`
- 신규 `docs/operations/merchant-account-administration-runbook.md`
- `docs/operations/local-demo-runbook.md`

## Progress

아직 시작하지 않았다.

## Surprises & Discoveries

아직 없다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-11 | P0는 `OWNER`/`STAFF` 2역할만. `MANAGER`와 PIN은 P1 | [C-6](../../product/design-contract-conflicts.md) |
| 2026-08-12 | 운영 콘솔이 계정+최초 membership을 원자 발급하고 임시 비밀번호는 성공 응답에서 1회만 표시 | [BR-46](../../product/business-policy-decisions.md) |
| 2026-08-11 | Password change gate는 Chain과 Service 양쪽에 적용 | 이 plan |
| 2026-08-12 | 자격증명 변경은 `credentialVersion` 증가로 기존 Session을 즉시 무효화 | [ADR-094](../../adr/ADR-094-browser-session-security.md) |
| 2026-08-12 | 고객·점주 공통 비밀번호와 로그인 제한 초기값 적용 | [BR-35](../../product/business-policy-decisions.md) |
| 2026-08-12 | 점주 로그인 ID는 고객과 같은 canonical 규칙, 별도 namespace 사용 | [BR-34](../../product/business-policy-decisions.md) |
| 2026-08-12 | 점주 잠금은 lifecycle 상태가 아니라 15분 `lockedUntil` overlay | [BR-35](../../product/business-policy-decisions.md), [ADR-093](../../adr/ADR-093-merchant-credential-lifecycle.md) |

## Outcomes & Retrospective

아직 없다.

## Revision Notes

- 2026-08-11: 최초 작성.
