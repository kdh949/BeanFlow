# 운영자가 토큰·UUID 입력 없이 장애·정산·감사·고객 문의를 처리한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/productization-20-authentication-foundation.md`, `docs/exec-plans/completed/productization-10-public-order-reference.md`, `docs/exec-plans/completed/productization-40-merchant-account-and-initial-password.md`, `docs/exec-plans/active/productization-90-merchant-financial-workflows.md`, `docs/exec-plans/completed/customer-support-s40-verification-data-access-grant.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

운영자가 Keycloak으로 로그인하고 실패 유형별 작업 큐, 정산 대사, 감사 로그, 기존 Support Case와
점주 credential 발급을 처리한다. 브라우저의 수동 Access Token·내부 UUID 입력을 제거하되 각
Context의 상태, 기존 재처리 명령, 명시적 권한과 감사 원장을 우회하지 않는다.

## Current State

- Backend Operations endpoint는 검증된 Keycloak JWT를 받지만 frontend에 OIDC 로그인·callback이 없고
  Token Editor에 붙여넣은 bearer token을 사용한다.
- Payment에는 one-time attempt의 `UNKNOWN|RECONCILING|MANUAL_REVIEW`와 reconciliation, Notification에는
  `RETRY_SCHEDULED|MANUAL_REVIEW`, Settlement에는 lifecycle·late item·adjustment·dispute case가 있다.
- `operations_reprocessing_case`는 수동 복구 case source이고 모든 owner state의 mirror가 아니다.
- 정산 batch 합계와 immutable item, Adjustment와 Dispute는 구현돼 있으나 운영자 전역 Projection은 없다.
- AuditRecord는 append-only·보존 정책·target/correlation index가 있지만 P0 목록·상세 API는 없다.
- Support S40은 검색·Case·verification·time-bound PII reveal을 별도 권한과 Audit으로 제공한다.
- productization-40은 `MERCHANT_CREDENTIAL_MANAGE`로 보호된 점주 계정 exact 조회·발급·초기화·잠금
  해제 API와 임시 비밀번호 1회 응답을 제공한다. Operations UI는 아직 없다.

## Definitions

- **Failure queue type:** `PAYMENT | NOTIFICATION | SETTLEMENT` 중 하나이며 독립 cursor와 source mapping을
  갖는 탭이다.
- **Work reference:** 목록에서 상세·기존 command로 전달하는 opaque 안정 식별자다. 사용자가 UUID를
  입력하지 않으며 권한 증명으로 사용하지 않는다.
- **Settlement reconciliation projection:** batch snapshot, item 합계, adjustment/carry-forward와 dispute
  hold를 대조해 불일치 여부와 원인을 표시하는 read DTO다. 새 금전 원장이 아니다.
- **Audited read:** permission lock, Projection read와 접근 Audit append가 한 local transaction에서 모두
  성공한 뒤 body를 반환하는 조회다.
- **Operations OIDC client:** Keycloak Authorization Code + PKCE S256 public browser client다.

## Scope

### In Scope

- Keycloak `keycloak-js` 기반 Operations SPA login/callback/logout과 memory-only access token
- `GET /auth/operations/config`의 public non-secret OIDC 설정과 startup/runtime fail-closed validation
- ADR-110의 Payment·Notification owner port와 Operations typed ReprocessingCase query
- failure queue summary, type별 목록·상세, exact correlation ID 검색
- 운영자 전역 settlement batch 목록·item·reconciliation Projection
- BR-44의 감사 로그 목록·상세와 reason/access Audit
- 기존 Support S40 검색·Case·verification·reveal UI 연결
- BR-46 점주 계정 exact 조회·계정+최초 membership 발급·임시 비밀번호 초기화·잠금 해제 UI
- 세 P0 read permission의 DB vocabulary·offline grant lifecycle 연결
- 운영자 frontend의 token/UUID form 제거와 loading/empty/forbidden/retryable/terminal 상태

### Non-goals

- 중앙 failure mirror table, Kafka/Elasticsearch 또는 전 유형 단일 cursor
- Event publication 전용 P0 queue
- 새 재처리·환불·정산 변경 권한을 read grant에서 파생
- 실제 은행 지급, KYC, 대량 export 또는 background report
- access/refresh token을 localStorage, sessionStorage, IndexedDB, cookie나 URL에 저장
- 점주 임시 비밀번호를 frontend storage·URL·log·error report에 저장하거나 다시 조회하는 기능
- Support의 verification·PII reveal 정책 재구현
- 감사 record 수정·삭제 API와 retention worker 우회

## Business Rules and Invariants

1. Operations web는 BR-41의 Authorization Code + PKCE S256 public client만 사용한다. implicit/password
   grant, `offline_access`와 token editor는 금지한다.
2. Backend는 기존 Resource Server의 signature·issuer·audience·expiry 검증을 유지한다. OIDC config
   endpoint가 credential이나 client secret을 반환하지 않는다.
3. failure read는 BR-43/ADR-110을 따른다. source state를 공통 table에 복제하거나 attempt count 없음은
   0으로 표시하지 않는다.
4. failure read, settlement reconciliation, audit record는 각각 BR-39의 서로 다른 active grant를
   요구한다. `PLATFORM_OPERATOR` role이나 다른 grant는 fallback이 아니다.
5. read grant는 command permission이 아니다. `allowedActions`는 source capability와 현재 actor의 기존
   command grant 교집합이며 목록 endpoint가 상태를 변경하지 않는다.
6. settlement tie-out은 저장된 batch snapshot과 immutable item·adjustment·carry-forward·dispute hold를
   읽어 비교한다. 차이를 0으로 덮거나 batch를 조회 중 재계산·수정하지 않는다.
7. 감사 목록은 BR-44, 정산 대사 목록은 BR-45의 기본 30일·요청당 최대 90일을 적용한다. 보존 중인
   과거 조회 자체에는 별도 상한이 없다.
8. 감사 read의 reason과 접근 Audit는 ADR-069를 따른다. Audit append 실패 시 결과를 반환하지 않는다.
9. 고객 문의 화면은 Support S40의 Case/verification/grant를 사용한다. 전화번호·PII를 failure queue나
   audit search로 우회 검색하지 않는다.
10. 모든 목록은 Query Projection이다. Controller가 다른 Context Repository를 직접 호출하지 않는다.
11. 점주 credential UI는 BR-46을 따른다. create/reset mutation을 자동 retry하지 않고 성공 응답의
    `temporaryPassword`를 route-local memory에서 한 번만 보여준다. 사라진 값은 재조회하지 않고 reset한다.

## Architecture and Transaction Boundaries

```text
Operations SPA
  Keycloak login + PKCE S256
  access token in memory
        |
        v
Operations Resource Server FilterChain
  Jwt -> OperatorActor
        |
        +-- failure summary/search/list/detail
        |     OperationsFailureQueryService
        |       +-- PaymentFailureQueryOperations
        |       +-- NotificationFailureQueryOperations
        |       +-- OperationsReprocessingCaseQuery
        |
        +-- settlement reconciliation
        |     SettlementOperationsQueryOperations
        |
        +-- merchant credential administration
        |     productization-40 Operations API
        |     exact login ID query + create/reset/unlock
        |
        +-- audit query (local transaction)
              permission row lock
              AuditRecordQueryRepository
              append AUDIT_RECORD_READ
              commit -> response
```

- PAYMENT 목록·상세는 Payment port와 Operations의 payment case query, NOTIFICATION은 Notification
  port와 notification case query, SETTLEMENT는 Operations의 세 settlement case query를 사용한다.
  같은 typed owner source와 case는 하나의 work item으로 합치고 중복 집계하지 않는다.
- summary와 exact correlation search는 Payment, Notification과 Operations case query를 fan-out하며
  어느 하나라도 실패하면 전체를 503으로 실패시킨다.
- Operations는 owner DTO를 공통 response로 normalize하지만 owner entity/repository를 import하지 않는다.
- settlement query는 Settlement public query port가 소유한다. batch/item/adjustment/dispute의 동일 DB
  consistent snapshot에서 식별된 불일치를 반환하고 쓰기를 하지 않는다.
- audit read transaction은 grant row를 lock하고 Projection을 읽은 뒤 접근 Audit를 append한다. 같은
  요청이 추가한 access record는 현재 page snapshot에 포함하지 않고 다음 새 조회에서 보일 수 있다.
- 기존 repair/reconciliation command는 각 문서의 외부 호출 분리·멱등성·reason·Audit 경계를 그대로
  사용한다. 이 plan이 Provider call을 DB transaction 안으로 옮기지 않는다.

## OIDC Client Dependency Decision

- production dependency는 Keycloak 공식 browser adapter `keycloak-js@26.2.4`를 exact version으로
  `package.json`과 `package-lock.json`에 고정한다. Keycloak JS는 server와 release cycle이 분리돼 있으므로
  임의 caret 범위로 자동 major/minor 변경하지 않고 보안·호환성 검증 뒤 갱신한다.
- 설정은 `flow: 'standard'`, `pkceMethod: 'S256'`, exact redirect URI와 public client다. token은 adapter의
  memory 상태로만 보관하며 reload 뒤 필요하면 새 login을 수행한다.
- 대안인 OIDC protocol 직접 구현은 state/nonce/PKCE/callback 검증과 갱신 오류의 보안 책임이 커서
  기각한다. generic `oidc-client-ts`는 provider portability가 장점이나 P0 provider가 Keycloak이고 공식
  adapter가 필요한 계약을 더 작게 충족하므로 선택하지 않는다.
- Keycloak init, discovery/config, callback 또는 token refresh 실패는 Operations route를 차단하고
  재로그인 가능 오류를 표시한다. local token/fake principal로 대체하지 않는다.
- 제거 비용은 동일 OIDC 계약을 제공하는 BFF 또는 다른 검증된 client로 login/callback/token 갱신
  boundary를 교체하고 browser·backend 회귀 테스트를 다시 통과하는 것이다.

공식 근거:

- Keycloak JavaScript adapter: <https://www.keycloak.org/securing-apps/javascript-adapter>
- Keycloak downloads/version source: <https://www.keycloak.org/downloads.html>
- 비교 대안 `oidc-client-ts`: <https://github.com/authts/oidc-client-ts>

## API and Query Contracts

### Public OIDC configuration

```http
GET /api/v1/auth/operations/config
```

- response: `issuerUri`, `authorizationServerUrl`, `realm`, public `clientId`, exact `redirectUri`,
  `postLogoutRedirectUri`, `scopes`만 반환한다.
- 서버는 `issuerUri = authorizationServerUrl + /realms/{realm}`의 canonical 일치와 backend의 required
  issuer/audience가 client 설정과 같은 realm/client를 가리키는지 기동 시 검증한다. 값이 없거나
  불일치하면 application startup을 실패시킨다. client secret, signing key, admin URL은 반환하지 않는다.

### Failure queues

```http
GET /api/v1/operations/failure-queues/summary
GET /api/v1/operations/failure-queues/{queueType}?attentionState=&cursor=&limit=
GET /api/v1/operations/failure-queues/{queueType}/{workReference}
GET /api/v1/operations/failure-search?correlationId=
```

- queueType은 `PAYMENT|NOTIFICATION|SETTLEMENT`, 기본 limit 20, 최대 100이다.
- type 목록 정렬은 공통 `(updatedAt DESC, stableId DESC)`이며 각 source query가 bounded candidate와
  scan boundary를 반환한다. Operations가 결정적으로 dedupe한 뒤 cursor가 source별 boundary,
  queueType, attentionState와 endpoint를 서명한다. 무제한 결과를 메모리 병합하지 않는다.
- summary는 type/attentionState별 `count`, `oldestOccurredAt`을 반환한다. count를 조회할 수 없는 source는
  부분 summary가 아니라 503이다.
- exact correlation search는 trim 1..160자, 부분 검색·전화번호 검색을 허용하지 않는다. type별 결과는
  각각 최대 20건이며 잘림 여부를 명시한다.
- item은 `workReference`, `sourceState`, `attentionState`, `attemptCount`,
  `attemptCountAvailable`, occurredAt, updatedAt, correlationId, sanitizedSummary와 `allowedActions`다.
  Provider key, raw payload, notification destination과 내부 stack trace를 포함하지 않는다.

### Settlement reconciliation reads

```http
GET /api/v1/operations/settlement-batches?storeId=&state=&from=&to=&cursor=&limit=
GET /api/v1/operations/settlement-batches/{settlementBatchId}
GET /api/v1/operations/settlement-batches/{settlementBatchId}/items?cursor=&limit=
GET /api/v1/operations/settlement-batches/{settlementBatchId}/reconciliation
```

- 목록은 BR-45에 따라 서울 정산일 양 끝 포함 기본 30일, 최대 90일 window와
  `(settlementDate DESC,id DESC)` cursor를 사용한다. storeId는 선택 filter이고 store membership을 요구하지 않되
  `SETTLEMENT_RECONCILIATION_READ`를 요구한다.
- reconciliation은 batch stored totals와 item sum, effective adjustment sum, carry-forward in/out,
  active dispute hold를 별도 필드로 반환하고 `CONSISTENT|MISMATCH|INCOMPLETE`를 서버가 계산한다.
- OPEN batch 또는 계산 필수 source가 아직 terminal이 아니면 `INCOMPLETE`다. 0으로 보정하거나
  `CONSISTENT`로 표시하지 않는다. mismatch 조회는 자동 repair를 실행하지 않는다.
- item cursor는 `(completedAt DESC,id DESC)`와 batch ID를 서명한다. 기본 20, 최대 100이다.

### Audit records

```http
GET /api/v1/operations/audit-records?from=&to=&category=&action=&correlationId=&cursor=&limit=
GET /api/v1/operations/audit-records/{auditRecordId}
X-Access-Reason: <1..200 printable characters>
```

- BR-44에 따라 생략한 `to`는 request clock, 생략한 `from`은 `to - 30일`, 최대 window는 90일이다.
- 목록 정렬은 `(occurredAt DESC,id DESC)`, 기본 limit 20, 최대 100이다. cursor는 확정 기간·filter·
  endpoint를 서명한다.
- response는 actor type과 masked actor reference, action/category, target type과 opaque target reference,
  occurredAt, reason code, sanitized before/after summary, correlationId를 반환한다. source secret과 내부
  permission row를 반환하지 않는다.
- 상세도 목록과 같은 권한·reason·access Audit를 요구하며 다른 retention class의 PII reveal API가 아니다.

### Existing Support integration

- frontend는 S40의 `POST /support/searches`, Case, verification session, DataAccessGrant와 reveal endpoint를
  그대로 사용한다. PII는 masked-by-default이며 전화·email은 query string이 아닌 POST body로 전달한다.
- Support permission과 `REPROCESSING_CASE_READ`/`AUDIT_RECORD_READ`를 서로 대체하지 않는다.

### Merchant credential administration

- frontend는 productization-40의 `GET /operations/merchant-accounts?loginId=`, create/reset/lock-release
  endpoint를 그대로 사용한다. login ID는 exact canonical 검색이며 사용자가 account UUID를 입력하지
  않는다. 상세 화면이 응답의 opaque account reference를 후속 명령 URL에 전달한다.
- create/reset 성공 응답의 `temporaryPassword`는 route-local memory에만 두고 저장·새로고침 복원·다시
  보기 기능을 만들지 않는다. response 화면은 24시간 만료, 최초 변경 gate와 안전한 전달 책임을 함께
  표시한다.
- create/reset mutation은 자동 retry를 끈다. network outcome이 불명확하면 exact login ID 조회로
  생성·state를 확인하고, secret을 잃었으면 명시적 새 reset과 새 idempotency key를 요구한다.
- copy 버튼은 Clipboard API 성공/실패만 화면에 표시하고 clipboard 내용을 telemetry에 넣지 않는다.
  route 이탈 전에 secret을 별도 API로 전송하거나 browser storage에 보존하지 않는다.

## Failure Semantics

- OIDC config 누락·issuer/audience mismatch: startup failure. SPA init/callback 실패: 인증 화면의 명시적
  terminal/retryable 상태이며 token editor fallback 없음.
- access token 만료·401: 자동 command 재호출 없이 인증을 차단하고 Keycloak 재인증을 요구한다.
- role mismatch·active grant 부재: 403. grant query/lock 장애: 503.
- invalid queue type, cursor/filter mismatch, audit/settlement 기간 역전·상한 초과: 400.
- source query 장애: type list/detail 503, summary/search도 partial 200 없이 503.
- work reference 없음: 404. 다른 permission으로 상세를 추론 가능하게 만들지 않는다.
- settlement source 미완료: 200 `INCOMPLETE`; DB/query 장애: 503. 둘을 섞지 않는다.
- audit access append 실패: 503이며 audit rows를 반환하지 않는다.
- 점주 credential mutation outcome 불명확: 성공·실패로 추정하거나 같은 요청을 자동 재호출하지 않는다.
  exact 조회 → 필요 시 reset으로 수렴한다. `TEMPORARY_PASSWORD_NOT_REPLAYABLE`을 일반 validation 오류로
  숨기지 않는다.
- Support provider/verification/grant failure는 S40의 explicit state를 유지하고 masked fake 결과로 대체하지
  않는다.

## Data and Migration

1. `operations_operator_permission_grant`의 closed vocabulary에
   `REPROCESSING_CASE_READ`, `SETTLEMENT_RECONCILIATION_READ`, `AUDIT_RECORD_READ`를 추가한다. seed/default
   grant는 만들지 않고 기존 offline bootstrap만 사용한다.
2. owner query는 아래 후보 index를 실제 PostgreSQL fixture와 `EXPLAIN (ANALYZE, BUFFERS)`로 검증한 뒤
   필요한 것만 migration에 포함한다.

```sql
CREATE INDEX ix_payment_attempt_failure_queue
    ON payment_one_time_attempt (state, updated_at DESC, payment_id DESC)
    WHERE state IN ('UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW');

CREATE INDEX ix_notification_failure_queue
    ON notification_delivery (state, updated_at DESC, id DESC)
    WHERE state IN ('RETRY_SCHEDULED', 'MANUAL_REVIEW');

CREATE INDEX ix_reprocessing_case_failure_queue
    ON operations_reprocessing_case (case_type, status, updated_at DESC, id DESC)
    WHERE status IN ('OPEN', 'RUNNING', 'MANUAL_REVIEW');

CREATE INDEX ix_settlement_batch_operations_list
    ON settlement_batch (settlement_date DESC, id DESC);

CREATE INDEX ix_audit_operations_list
    ON operations_audit_record (occurred_at DESC, id DESC);
```

- 기존 `idx_payment_one_time_attempt_state`, `idx_notification_delivery_due`, settlement store list,
  correlation/target Audit index와 중복이면 새 index를 만들지 않는다. 측정 결과와 선택 근거를 plan에 남긴다.
- P0 failure mirror table, materialized view와 backfill은 없다.
- permission constraint 변경과 index migration은 ADR-072의 새 writer lease를 획득한 하나의 plan이 소유한다.
- `MERCHANT_CREDENTIAL_MANAGE` vocabulary와 merchant credential idempotency table은 각각 선행
  productization-20/40이 소유한다. 이 plan은 frontend를 위해 중복 migration을 만들지 않는다.

## P0 Operations Screen Coverage

| 화면 | 소유 capability | 이 plan의 완료 증거 |
|---|---|---|
| `3a 장애 추적` | ADR-110 typed queues | summary, type tab, empty/error/oldest age |
| `3b 장애 상세·재처리` | owner query + existing command | correlation, sanitized error, actual allowedActions |
| `3c 정산 대사` | Settlement Projection | batch/item/tie-out `CONSISTENT|MISMATCH|INCOMPLETE` |
| `3d 감사 로그` | BR-44/ADR-022 | period/filter/cursor, reason, access Audit |
| `3e 고객 문의` | Support S40 | masked search, Case, verification, time-bound reveal |
| `P0 신규 점주 계정 발급` | BR-46/productization-40 | exact 조회, account+membership 발급, 1회 secret, reset/unlock |

## Milestones

1. migration writer lease, permission vocabulary와 필요한 owner/query index migration.
2. Payment·Notification owner query ports, Operations typed case query와 exhaustive source map/state mapping.
3. Operations orchestrator, summary/search/list/detail API와 signed cursor.
4. Settlement operations list/item/reconciliation Projection과 tie-out tests.
5. AuditRecord QueryRepository, reason/permission/access-Audit transaction과 목록·상세 API.
6. public OIDC config validation과 frontend `keycloak-js` login/callback/logout/token-memory client.
7. failure·settlement·audit·Support routes와 점주 credential 관리 route 연결, Token Editor/UUID form 제거.
8. 점주 임시 비밀번호 1회 화면, mutation outcome-unknown 조회→reset 흐름과 storage 비사용 browser 검증.
9. runtime OpenAPI, Error Catalog, Authorization Matrix, runbook와 browser/plan evidence 갱신.

## Required Tests

- Keycloak standard flow/PKCE S256 config, state/nonce/callback rejection, token memory-only와 reload/expiry/401.
- OIDC config missing/mismatch startup failure, public config의 secret 부재, local/fake token fallback 부재.
- 세 failure type의 모든 source state→attentionState·attempt availability·allowedActions mapping,
  같은 owner source+case dedupe와 source map 밖 case의 P0 제외.
- type/filter/cursor keyset 무누락·무중복, summary/search의 dependency failure가 partial 200 아닌 503.
- read grant만 있는 actor가 command를 실행하거나 action을 보지 못하고 revoke commit 뒤 즉시 403.
- settlement stored total/item/adjustment/carry-forward/dispute hold tie-out의 consistent/mismatch/incomplete.
- Audit 기본 30일, 정확히 90일, 초과/역전 400, 과거 window와 filter-bound cursor.
- Audit permission lock/query/access append 원자성, reason validation, 저장 장애 503와 sensitive field absence.
- Support S40 masked/search/verification/reveal regression과 Operations permission 비대체.
- 점주 account+membership 발급·reset·unlock UI의 permission/reason/idempotency 오류, one-time secret과
  outcome-unknown 조회→reset 수렴을 실제 browser에서 검증한다.
- temporaryPassword가 localStorage/sessionStorage/IndexedDB/URL/console/error telemetry에 없고 route
  이동·새로고침 뒤 재표시되지 않으며 copy 실패가 secret을 log하지 않는지 검증한다.
- frontend loading/empty/forbidden/unauthorized/retryable/terminal, XSS-safe summary rendering, UUID/token input 부재.
- Modulith/ArchUnit으로 Operations가 owner internal Repository/entity에 접근하지 않는지 검증.

## Validation Commands

```bash
./gradlew test --tests '*OperationsFailure*' --tests '*Reconciliation*' --tests '*AuditRecord*' --tests '*OperatorPermission*'
./gradlew test --tests '*SupportVerification*' --tests '*DataAccessGrant*'
cd frontend && npm test
cd frontend && npm run typecheck
cd frontend && npm run build
./gradlew spotlessCheck
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

## Observability

- OIDC init/callback/refresh/logout result와 bounded failure code; token·authorization code는 기록하지 않는다.
- type/attentionState별 queue depth, oldest age, query p50·p95·p99와 port dependency failure.
- settlement reconciliation result count, mismatch reason과 query latency.
- audit query window/row count/latency, permission denial, access-Audit failure; reason·PII는 metric tag에 넣지 않는다.
- Support S40의 기존 verification/grant/reveal metric을 재사용하고 별도 PII tag를 만들지 않는다.
- 점주 credential 화면의 create/reset/unlock outcome과 response-lost recovery 수. login ID·reason·
  temporaryPassword를 metric tag나 frontend telemetry에 넣지 않는다.

## Documentation Updates

- [ADR-092](../../adr/ADR-092-hybrid-authentication.md)
- [ADR-110](../../adr/ADR-110-federated-operations-failure-queues.md)
- [ADR-022](../../adr/ADR-022-audit-record.md), [ADR-069](../../adr/ADR-069-operator-permission-grants-and-audited-policy-read.md)
- [BR-39, BR-41, BR-43, BR-44, BR-45, BR-46](../../product/business-policy-decisions.md)
- [Authorization Matrix](../../security/authorization-matrix.md)
- `docs/api/api-conventions.md`, `docs/api/error-catalog.md`
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml`
- Operations OIDC·permission bootstrap·failure queue·audit access runbook

## Progress

아직 시작하지 않았다. 선행 CurrentActor/merchant finance/Support S40과 migration writer lease 후 준비
상태를 전환한다.

## Surprises & Discoveries

- `operations_reprocessing_case`는 공통 queue로 보이지만 owner 실제 state와 attempt를 완전히 담지 않아
  P0 source of truth로 확장하면 이중 상태가 된다.
- SettlementBatch는 이미 계산 시점 합계 snapshot을 저장하고 SettlementItem은 immutable이므로 대사는
  새 원장이 아니라 source 간 비교 Projection으로 충분하다.
- Keycloak 공식 browser adapter가 standard code flow와 PKCE S256, memory-only token 계약을 직접 지원한다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-12 | Operations SPA는 Keycloak Authorization Code + PKCE S256 | [BR-41](../../product/business-policy-decisions.md), [ADR-092](../../adr/ADR-092-hybrid-authentication.md) |
| 2026-08-12 | failure queue는 세 source-owned typed Projection | [BR-43](../../product/business-policy-decisions.md), [ADR-110](../../adr/ADR-110-federated-operations-failure-queues.md) |
| 2026-08-12 | P0 read permission은 세 grant로 분리 | [BR-39](../../product/business-policy-decisions.md), [ADR-069](../../adr/ADR-069-operator-permission-grants-and-audited-policy-read.md) |
| 2026-08-12 | 감사 목록 기본 30일·요청 최대 90일 | [BR-44](../../product/business-policy-decisions.md), [ADR-022](../../adr/ADR-022-audit-record.md) |
| 2026-08-12 | 정산 대사 목록 서울 정산일 기본 30일·요청 최대 90일 | [BR-45](../../product/business-policy-decisions.md) |
| 2026-08-12 | 운영 콘솔에서 account+최초 membership을 발급하고 임시 비밀번호는 최초 응답에서 1회만 표시 | [BR-46](../../product/business-policy-decisions.md), [ADR-093](../../adr/ADR-093-merchant-credential-lifecycle.md) |

## Outcomes & Retrospective

아직 없다.

## Revision Notes

- 2026-08-12: 최초 작성.
