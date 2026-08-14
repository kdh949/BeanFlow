# Session and Authentication Runbook

## Scope

이 runbook은 Public, Operations, Merchant, Customer 네 인증 Chain과 PostgreSQL Spring Session 저장소를
진단한다. Customer/Merchant 계정·로그인 API는 후속 Plan 30/40 소유다. 그 전까지 보호 경로의 401은
의도된 전환 상태이며 fake 계정, in-memory Session 또는 기존 JWT로 우회하지 않는다.

## Contract

| Chain | Credential | CSRF | Cookie |
|---|---|---|---|
| Public | 없음 | 없음 | 없음 |
| Operations | Bearer JWT | 없음 | 없음 |
| Customer | PostgreSQL Session | `BEANFLOW_CUSTOMER_XSRF` + `X-BEANFLOW-CSRF` | `BEANFLOW_CUSTOMER_SESSION` |
| Merchant | PostgreSQL Session | `BEANFLOW_MERCHANT_XSRF` + `X-BEANFLOW-CSRF` | `BEANFLOW_MERCHANT_SESSION` |

두 Session Cookie는 `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`다. XSRF Cookie만 JavaScript가 읽을
수 있고 인증정보를 담지 않는다. 다른 actor의 Cookie, Bearer 또는 CSRF token이 들어오면 403이며
적합한 credential로 fallback하지 않는다.

Session에는 framework principal index 외에 다음 세 값만 저장한다.

- `beanflow.actorId`
- `beanflow.authenticatedAt`
- `beanflow.credentialVersion`

계정 상태, 현재 credential version, 권한과 매장 membership은 Session에서 읽지 않고 요청마다 현재
source of truth를 조회한다.

## Expected status semantics

- `401`: credential 없음, 폐기·만료 Session, invalid account state 또는 credential version 불일치
- `403`: actor/Chain 불일치, CSRF 실패, coarse role 또는 resource authorization 실패
- `503`: Session 조회·저장·삭제 실패, 현재 계정 조회 실패, Operations JWK 의존성 실패

503을 401, 빈 결과 또는 local Session으로 바꾸지 않는다. 외부 JWK나 PostgreSQL 장애 중 인증 성공을
추정하지 않는다.

## Metrics

- `beanflow.authentication.failure.count{chain,status,reason}`: Chain별 401/403과 CSRF·actor mismatch 사유
- `beanflow.session.active{actor_type}`: 만료되지 않은 PostgreSQL Session 수
- `beanflow.session.lifecycle.count{actor_type,action,outcome}`: 생성·폐기·만료·logout 수
- `beanflow.session.lookup{actor_type}`: Session 조회 timer와 p50/p95 percentile
- `beanflow.session.store.error.count{actor_type,operation}`: 조회·저장·삭제·계정 검증 오류
- `beanflow.session.cleanup.count{outcome}`: 매분 실행되는 만료 정리 성공·실패

목표값이나 정상 규모는 아직 정하지 않았다. 실제 운영 기준선 없이 성능 향상이나 허용 가능한 실패율을
주장하지 않는다.

## Triage

### 401 증가

1. `chain`과 `reason`을 나눈다. Plan 30/40 전 Customer/Merchant의
   `missing_or_invalid_credential`은 예상 상태다.
2. `invalid_or_expired_session`이면 idle/absolute expiry와 계정의 현재 `credentialVersion`·상태를 확인한다.
3. Session 행을 되살리거나 version을 낮추지 않는다. 정상 credential로 다시 로그인하게 한다.

### 403 또는 CSRF 증가

1. browser client가 actor별 CSRF endpoint에서 새 token을 받은 뒤 같은 actor XSRF Cookie 값을
   `X-BEANFLOW-CSRF`로 보냈는지 확인한다.
2. Customer/Merchant Cookie 두 개 또는 Bearer와 browser Cookie를 섞어 보내지 않았는지 확인한다.
3. Operations permission 실패는 JWT role만으로 우회하지 말고 active DB grant와 Audit 경계를 확인한다.

### 503 또는 lookup 지연 증가

1. PostgreSQL health, connection pool, `spring_session` query 지연과 lock 대기를 확인한다.
2. Operations만 실패하면 JWK endpoint reachability와 issuer 설정을 함께 확인한다.
3. `beanflow.session.store.error.count`의 operation tag로 lookup/delete/create/account validation을 분리한다.
4. 저장소가 회복되기 전 in-memory 또는 stale Session fallback을 켜지 않는다.

## PostgreSQL checks

다음 쿼리는 actor 식별자를 출력하지 않는 집계 전용이다.

```sql
SELECT split_part(principal_name, ':', 1) AS actor_type, count(*)
FROM spring_session
WHERE expiry_time > (extract(epoch FROM clock_timestamp()) * 1000)::bigint
GROUP BY 1;

SELECT count(*) AS expired_rows
FROM spring_session
WHERE expiry_time <= (extract(epoch FROM clock_timestamp()) * 1000)::bigint;
```

Session attribute payload와 principal 전체 값을 로그나 incident 문서에 복사하지 않는다. 만료 행은 매분
`BrowserSessionCleanupWorker`가 Spring Session JDBC의 공식 cleanup을 호출해 삭제한다. 실패는 scheduled
error log와 `beanflow.session.cleanup.count{outcome="failure"}`에 남으며 성공으로 기록하지 않는다.

## Login transaction invariant

로그인 owner는 account row를 `FOR UPDATE`로 잠근 transaction 안에서 `LoginSessionCoordinator`를
호출해야 한다. coordinator는 현재 Session 회전, 상한 초과 oldest 폐기와 새 JDBC Session 저장을 같은
transaction에 참여시킨다. Spring Session 기본 `REQUIRES_NEW`는 사용하지 않고
`springSessionTransactionOperations`를 `REQUIRED`로 고정한다. delete·insert 중 하나라도 실패하면 전체
rollback 후 503이며, Cookie는 commit 뒤에만 응답에 기록한다.

Customer는 idle 7일·absolute 30일·최대 5개, Merchant는 idle 30분·absolute 12시간·최대 3개다.
동률 oldest 순서는 `(authenticatedAt, sessionId)`다. 상한을 늘려 장애를 숨기지 않는다.

## Recovery and rollback

- PostgreSQL 복구 뒤 cleanup failure와 active count가 수렴하는지 확인한다.
- credential version 불일치 행은 인증 안전성에 영향을 주지 않으며 cleanup 대상으로만 남는다.
- Session schema를 제거하면 browser 인증 전체가 실패한다. V52를 되돌리거나 dependency를 제거하기 전에
  동등한 중앙 저장소, 회전·만료·상한·장애 의미와 migration 증거가 필요하다.
- 운영 중 강제 전체 logout이 필요하면 후속 account plan의 credential version 증가 명령을 사용한다.
  SQL로 Session attribute를 수정하지 않는다.
