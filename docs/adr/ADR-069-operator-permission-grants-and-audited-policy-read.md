# ADR-069: Operator permission grant와 감사형 정책 조회

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owners:** [Plan 11 policy/grants](../exec-plans/completed/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md), [ordinary accrual policy/snapshot foundation](../exec-plans/completed/ordinary-point-accrual-policy-management.md), [Plan 14 point-account read](../exec-plans/completed/customer-order-cancellation-14-point-account-read-vertical-slice.md), [Point adjustment plan](../exec-plans/completed/loyalty-point-adjustment-foundation.md)

## Context

Authorization Matrix와 ADR-066은 Platform Operator role만으로는 만료 혜택 정책을 조회·변경하거나
포인트 조정을 실행할 수 없고, 명시적 permission과 reason을 요구한다. 현재 JWT converter는
`roles` claim만 권한으로 바꾸며 Operations policy controller도 role만 검사한다. 정책 GET은
reason을 전달하거나 access audit을 남길 공개 계약이 없다.

role을 permission으로 자동 대체하면 Accepted 보안 규칙이 무의미해진다. 반대로 권한 grant
조회 장애를 role 또는 JWT의 임의 claim으로 대체하면 revoke와 장애를 성공으로 위장한다.

## Decision

explicit operator permission의 source of truth는 Operations가 소유하는 DB-backed
`OperatorPermissionGrant`다. JWT의 `sub`와 `roles`는 인증과 coarse role gate에만 사용하며,
`permissions` claim은 권한 source 또는 fallback으로 사용하지 않는다.

### Grant model

- `OperatorPermissionGrant`는 `actor_id`, closed `permission`, `ACTIVE|REVOKED` state,
  `granted_at`, `revoked_at`, version과 Audit source를 저장한다.
- `(actor_id, permission)`는 unique다. 활성 grant가 없으면 permission은 없다. Platform Operator
  role 또는 다른 grant에서 기본 permission을 seed하거나 추론하지 않는다.
- MVP permission vocabulary는 `EXPIRED_BENEFIT_POLICY_READ`,
  `EXPIRED_BENEFIT_POLICY_WRITE`, `POINT_ACCOUNT_READ`, `POINT_ADJUSTMENT`다. 새 privileged operation은 별도
  ADR 또는 vocabulary amendment 없이 이 권한을 재사용하지 않는다.
- **2026-08-01 ordinary accrual policy amendment:** ordinary-accrual policy/snapshot foundation은
  `POINT_ACCRUAL_POLICY_READ`와 `POINT_ACCRUAL_POLICY_WRITE`를 closed vocabulary에 forward
  migration으로 추가한다. 일반 적립 policy current/history 조회와 version 변경은 이 두 grant를
  사용하고 expired-benefit grant를 재사용하지 않는다. 기존 offline grant bootstrap은 새 enum과 DB
  vocabulary가 적용된 뒤 두 permission의 grant/revoke/regrant에도 그대로 사용한다.
- **2026-08-03 order compensation read amendment:** Plan 30은 운영자 전용
  `GET /operations/orders/{orderId}/compensation`에만 쓰는
  `ORDER_COMPENSATION_READ`를 closed vocabulary에 forward migration으로 추가한다.
  active grant, 1..200자의 control-character 없는 `X-Access-Reason`, target Case access
  Audit를 같은 local transaction에서 요구한다. 기존 policy·point permission이나 role은
  fallback이 아니다.
- **2026-08-12 productization P0 read amendment:** 운영 실패 case, 정산 대사와 감사 로그 조회에만
  사용하는 `REPROCESSING_CASE_READ`, `SETTLEMENT_RECONCILIATION_READ`, `AUDIT_RECORD_READ`를
  closed vocabulary에 forward migration으로 추가한다. 세 permission은 서로 대체하지 않고 어떤
  role bundle이나 default grant에도 포함하지 않는다. 기존 offline bootstrap은 enum과 DB vocabulary가
  적용된 뒤 각각의 grant/revoke/regrant에 그대로 사용한다.
- **2026-08-12 merchant credential amendment:** 점주 계정 발급·exact 관리 조회·임시 비밀번호 초기화·
  잠금 조기 해제에만 쓰는 `MERCHANT_CREDENTIAL_MANAGE`를 closed vocabulary에 forward migration으로
  추가한다. `PLATFORM_OPERATOR` role이나 Support·read grant는 이 permission을 대체하지 않는다.
  명령은 reason·idempotency·Audit와 [BR-46](../product/business-policy-decisions.md)의 임시 비밀번호
  1회 표시 경계를 적용한다. 어떤 role bundle이나 default grant에도 포함하지 않고 기존 offline
  bootstrap으로만 grant/revoke/regrant한다.
- **2026-08-01 migration ownership amendment:** Plan 11이
  `operator_permission_grant` schema와 위 네 값을 허용하는 closed DB vocabulary를 한
  migration에서 단독 생성한다. Plan 14와 point adjustment plan은 새 permission 값이나
  grant constraint migration을 만들지 않고 각 endpoint의 enforcement만 구현한다. endpoint가
  아직 없다는 이유로 해당 permission을 default grant하거나 seed하지 않는다.
- JWT `sub`는 UUID actor ID여야 하고 `roles`에는 `PLATFORM_OPERATOR`가 있어야 한다.
  이 둘 중 하나가 없으면 permission lookup 전에 403이다. Authentication signature, issuer,
  audience, expiry는 resource-server의 기존 JWT validation으로 계속 검증한다.

### Enforcement and revocation boundary

- Controller/Method Security는 role을 빠른 진입 gate로 사용할 수 있지만, privileged use case는
  반드시 Application Service에서 `OperatorPermissionAuthorization.requireActive(actorId, permission)`을
  호출한다. Controller가 Repository를 직접 조회하지 않는다.
- require call은 해당 command 또는 audited read의 local transaction에 참여하고 활성 grant row를
  잠근다. revoke와 command/read 중 하나가 먼저 commit되어 선형화된다. revoke commit 뒤 새
  privileged transaction은 허용되지 않는다.
- grant query, lock 또는 audit persistence가 실패하면 403으로 번역하거나 role/claim으로
  fallback하지 않고 `503 DEPENDENCY_UNAVAILABLE`을 반환한다. active grant 부재와 role mismatch는
  `403 ACCESS_DENIED`다.
- 권한 결과를 process cache, in-memory map 또는 long-lived JWT claim으로 cache하지 않는다.
  따라서 grant revoke의 적용 지연은 DB commit 경합 이외에는 없다.

### 최초 grant bootstrap과 lifecycle

- default seed, role-derived grant 및 직접 SQL DML은 금지한다. `OperatorPermissionGrant`가 처음
  비어 있는 환경도 동일하다.
- Plan 11은 HTTP/API/UI와 분리된 offline
  `operator-permission-bootstrap` command를 제공한다. 이 command는 controlled deployment job의
  verified release principal로만 실행하며, application JWT/role이나 request header로 release
  principal을 대체하지 않는다. job identity 또는 command authorization이 확인되지 않으면 시작/실행을
  실패시킨다.
- **2026-08-01 trust-model amendment:** verified release principal은 controlled deployment
  job이 제공하는 단기 OIDC workload identity다. command는 required issuer, audience와
  allowed subject configuration을 사용해 token signature, issuer, audience, subject,
  `exp`와 `nbf`를 검증한다. trust configuration, token file 또는 검증 key를 읽을 수 없거나
  어떤 claim이라도 일치하지 않으면 grant transaction을 시작하지 않는다. application JWT,
  Platform Operator role, static bootstrap secret, local profile과 unsigned manifest는 fallback이
  아니다.
- workload token은 command line argument가 아니라 deployment job이 read-only로 mount한
  token file에서만 읽고 raw token·claim 전체·file path를 DB, stdout/stderr, log 또는
  `AuditRecord`에 남기지 않는다. immutable release-principal reference는 검증된
  `issuer + subject + audience + deployment-run reference`의 whitelist projection이며 raw
  credential hash를 principal reference로 사용하지 않는다.
- command input은 `action`, target `actorId`, closed `permission`, non-blank reason,
  non-blank evidence reference와 correlation ID다. 결과는 `APPLIED`, `INVALID_INPUT`,
  `IDENTITY_VERIFICATION_FAILED`, `GRANT_STATE_CONFLICT`, `DEPENDENCY_UNAVAILABLE` 중 하나다.
  `APPLIED`만 exit code 0이며 나머지는 non-zero이고 grant/Audit partial state를 남기지
  않는다. stdout result에는 action, permission, redacted principal reference와 결과만
  포함하고 token, reason 원문과 evidence body를 포함하지 않는다.
- command의 자유 입력 reason은 validation gate이며 DB/Audit에 복제하지 않는다. Audit reason은
  `VERIFIED_RELEASE_OPERATOR_PERMISSION_CHANGE` 표준 code이고, evidence는 body가 아닌 immutable
  reference만 after summary에 기록한다.
- `grant`, `revoke`, `regrant` action은 `actorId`, closed permission, non-blank reason,
  non-blank evidence reference, immutable release-principal reference와 correlation ID를 요구한다.
  command는 grant row state/version과 target `AuditRecord`를 같은 local transaction에 기록한다.
  raw job credential, secret 또는 evidence body는 저장/로그하지 않는다.
- `grant`는 absent row만 ACTIVE로 만든다. existing ACTIVE는 idempotent success가 아니라
  `ALREADY_ACTIVE` failure다. `revoke`는 ACTIVE만 REVOKED로 바꾸며 repeated revoke는 failure다.
  `regrant`는 REVOKED row만 새 version/새 Audit source로 ACTIVE로 바꾼다. 모든 action의 source는
  `operator-permission-grant:{actorId}:{permission}:{version}:{action}`이고 Audit source unique로
  보호한다.
- bootstrap command의 Audit/grant transaction failure는 partial state, direct DB repair 또는
  role-only privileged access로 대체하지 않는다. first grant는 Plan 11 migration 결과가 main에
  적용된 뒤, policy/point endpoint activation 전에 운영 runbook evidence와 함께 수행한다.

### Expired-benefit policy API reason contract

- `GET /operations/policies/expired-benefit-restoration`는
  `EXPIRED_BENEFIT_POLICY_READ` grant와 required `X-Access-Reason` header를 요구한다.
- header는 trim 뒤 1..200자이며 control character를 금지한다. 원문 header를 application log,
  metric tag 또는 event payload에 복제하지 않는다.
- Operations Application Service는 grant 검증, current five policy-head query와
  `EXPIRED_BENEFIT_POLICY_READ` AuditRecord를 같은 local transaction에서 저장한다. Audit target은
  `EXPIRED_BENEFIT_POLICY_HEAD_SET`의 안정적 singleton ID다. Audit save가 실패하면 policy body를
  반환하지 않는다.
- PATCH는 `EXPIRED_BENEFIT_POLICY_WRITE` grant와 기존 request body의 non-blank `reason`을
  요구한다. read header를 PATCH의 새/중복 reason으로 사용하지 않는다.
- 일반 적립 policy current/history GET은 `POINT_ACCRUAL_POLICY_READ` grant와 같은
  `X-Access-Reason` validation/Audit commit gate를 사용한다. GLOBAL/STORE version 생성·변경과
  `INHERIT_GLOBAL` 전환은 `POINT_ACCRUAL_POLICY_WRITE`, request body reason,
  `Idempotency-Key`와 expected current version을 요구한다.
- `POST /operations/point-accounts/{accountId}/adjustments`는 `POINT_ADJUSTMENT` grant와 기존
  request body reason/evidence를 요구한다. grant 검증은 PointAccount lock 뒤 같은 command
  transaction에 참여하며 Account/Lot/ledger/Audit/outbox/201과 분리되지 않는다.

### Point account read contract

- customer는 `GET /point-accounts/{accountId}`와 하위 ledger URI에서 자신의 PointAccount와 ledger만
  reason 없이 읽을 수 있다. 다른 customer ownership은 403이고 Store/Settlement role은 조회할 수 없다.
- **2026-08-13 actor-exclusive URI amendment:** Platform Operator support read는
  `GET /operations/point-accounts/{accountId}`와 하위 ledger URI로 분리한다. Customer URI에서 JWT를
  병행 허용하지 않고 Operations URI에서 Customer Session을 해석하지 않는다.
- Platform Operator support read는 `POINT_ACCOUNT_READ` active grant와 `X-Access-Reason`을
  요구한다. header는 policy GET과 같은 trim 1..200/control-character rule을 쓰며, account/ledger
  projection과 `POINT_ACCOUNT_READ` AuditRecord가 같은 local transaction에 저장된 경우에만 200이다.
  Operations OpenAPI parameter에서 header는 required이고 Customer URI에는 선언하지 않는다.
- ledger order는 `(occurredAt DESC, transactionId DESC)`이고, cursor filter hash는 endpoint와
  account ID를 bind한다. this read does not expose issuer reference, raw evidence, idempotency key,
  internal recovery case or grant state.
- **2026-08-01 full-projection amendment:** PointAccount 응답의 `recoveryPendingKrw`는 Plan 13이
  만든 Account pending summary의 실제 값만 사용한다. Plan 13 outcome 전 0으로 대체하거나
  PointRecoveryPending을 실시간 추측 집계하지 않는다. 현재 PointAccount에는 진실한 변경 시각과
  backfill source가 없으므로 target API의 `updatedAt`은 제거한다. migration 적용 시각이나 조회
  시각을 account 변경 시각처럼 반환하지 않는다.
- Plan 14는 Plan 11 grant, Plan 13 recovery/pending schema와 signed-cursor foundation을 직접
  소비한다. permission vocabulary는 Plan 11만 만들고, Plan 14 migration은
  `loyalty_point_transaction(point_account_id, occurred_at DESC, id DESC)` 조회 index만 소유한다.

Plan 11은 grant schema, Operations public authorization API, policy GET header/audit contract와
policy PATCH enforcement, offline bootstrap command를 구현한다. Plan 14는 customer/operator point-account
read vertical slice를 구현한다. Point adjustment plan은 Plan 10 issuer, Plan 11 grant와 Plan 13 ledger outcome을 소비하고
`POINT_ADJUSTMENT` enforcement을 구현한다. Plan 11만 네 값의 closed permission vocabulary와 grant
migration을 만들고, 두 후속 계획은 같은 grant/vocabulary migration을 만들지 않는다.

### Productization P0 operations read contract

- `GET /operations/failure-queues/summary`, 유형별 목록·상세와 exact correlation 검색은 active
  `REPROCESSING_CASE_READ`만 요구한다. source-owned 연합 조회와 cursor 계약은 ADR-110을 따른다.
- `GET /operations/settlement-batches`와 상세 Projection은 active
  `SETTLEMENT_RECONCILIATION_READ`만 요구한다.
- `GET /operations/audit-records`와 상세는 active `AUDIT_RECORD_READ`와 trim 뒤 1..200자이고 control
  character가 없는 `X-Access-Reason`을 요구한다. permission lock, 결과 Projection과 한 건의
  `AUDIT_RECORD_READ` 접근 Audit를 같은 local transaction에 묶는다. Audit 저장 실패 시 body를
  반환하지 않는다. 기간과 cursor는 BR-44와 ADR-022를 따른다.
- 세 permission은 조회 전용이며 기존 repair, reconciliation command, SettlementAdjustment,
  Refund, grant lifecycle 권한을 만들지 않는다. command endpoint는 자신의 별도 grant와 사유·멱등성
  계약을 계속 검증한다.
- 실패 case와 정산 대사 조회는 P0에서 per-request access Audit을 추가하지 않는다. 대신 허용·거부·
  dependency failure metric을 bounded tag로 기록한다. 어떤 조회도 grant 장애를 빈 page로 바꾸지 않는다.

## Alternatives Considered

### JWT `permissions` claim만 사용

구현이 작지만 grant revoke는 token expiry까지 지연되고, 현재 claim이 없을 때 role fallback으로
흐르기 쉽다. 즉시 revoke가 필요한 금전성 운영 명령에는 부족하다.

### Short-lived claim과 server grant를 조합

claim과 DB version을 모두 검증하면 defense-in-depth가 가능하다. 그러나 MVP에 필요한 grant
source와 revocation semantics만으로도 충분한데 token issuance/version mismatch라는 추가 장애
경로를 만든다.

### 정책 GET에 reason을 요구하지 않음

기존 API는 유지되지만 Authorization Matrix의 조회·변경 reason 요구를 조용히 약화시키고
민감 운영 정책 접근의 목적을 감사할 수 없다.

## Rationale

Operations-owned grant는 역할과 세분 권한을 분리하고 revoke를 persistence transaction으로
정확하게 표현한다. Grant, reason과 audit을 같은 Application Service boundary에 두면 current
role-only controller가 보안 source of truth를 우회하지 못한다. 조회도 감사 실패를 숨기지 않기
위해 body 반환의 commit gate로 둔다.

## Consequences

- role만 가진 Platform Operator는 명시 grant가 생기기 전 policy, point-account support read 또는
  point adjustment를 실행·조회할 수 없다.
- Plan 11은 Operations schema/API scope가 늘며 Plan 14와 point-adjustment plan의 선행조건이 된다.
- Plan 14는 Plan 13의 실제 `recoveryPendingKrw` outcome도 필요하므로 Plan 11과 cursor만으로
  implementation-ready가 되지 않는다.
- 새 환경은 audited offline command로 first grant를 만들며, unrecorded SQL seed가 필요하지 않다.
- policy GET은 새로운 required header와 400 validation contract를 가지며, existing clients는
  header를 보내도록 변경해야 한다.
- Operations DB 장애는 privileged API를 503으로 실패시킨다. 읽기 성공 또는 role-only access로
  대체하지 않는다.

## Verification

- **Plan 11 implementation evidence (2026-08-01):** Flyway `V13`, audited keyed policy API와
  offline bootstrap command가 구현됐다. PostgreSQL integration test가 role/claim-only 거부,
  grant/revoke/regrant, revoke row-lock 경쟁, GET/PATCH Audit commit gate, grant/Audit rollback과
  invalid signature/issuer/audience/subject/expiry/nbf/token-file의 transaction-before rejection을 검증한다.
  운영 절차는 [bootstrap runbook](../operations/operator-permission-bootstrap-runbook.md)에 고정한다.
- role만 있음, grant만 있음, role+active grant, revoked grant와 malformed UUID subject를 구분한다.
- grant revoke와 policy PATCH/point adjustment의 동시 실행에서 commit 순서에 맞는 하나의 결과만
  나온다.
- permission repository/Audit failure가 403·200·201으로 위장되지 않고 503 및 rollback을 남긴다.
- GET의 missing/blank/control-character `X-Access-Reason`은 400이며 Audit이 없다.
- 정상 GET은 five heads와 exactly one access Audit을 같이 commit한다.
- policy PATCH 및 point adjustment가 각각 올바른 explicit permission과 body reason/evidence를
  요구하고, 다른 permission으로 통과하지 않는다.
- release-principal missing/invalid, absent/active/revoked/regranted grant, repeated action과
  Audit save failure가 bootstrap command에서 exact terminal result와 no partial state를 남긴다.
- customer own/other account, operator `POINT_ACCOUNT_READ` with/without reason, cursor account scope
  mismatch와 point-read Audit failure가 ownership/403/400/503 contract를 각각 지킨다.
- Customer Session과 운영자 JWT가 각각 상대 actor의 PointAccount URI에서 403이고, 두 URI가 같은
  projection·cursor·Audit 불변식을 지키는지 검증한다.
- PointAccount 응답이 Plan 13 summary를 그대로 사용하고 `updatedAt` 또는 임의 0 fallback을
  포함하지 않는지 검증한다.
- **Point adjustment enforcement evidence (2026-08-04):** endpoint method security는
  `PLATFORM_OPERATOR` coarse role을 요구하고 Application Service는 PointAccount lock 뒤
  Operations `requireActive(POINT_ADJUSTMENT)`를 같은 transaction에서 호출한다. customer/store/
  settlement role, revoked grant는 403이고 grant relation failure는 503이며 어떤 adjustment write도
  남지 않는 것을 PostgreSQL HTTP 통합 테스트로 검증했다.
- **Plan 14 point-account read evidence (2026-08-06):** Loyalty query service가 customer ownership read-only
  branch와 operator write-capable branch를 분리했다. operator branch는 Operations public
  `requireActive(POINT_ACCOUNT_READ)` 뒤 projection과 one `POINT_ACCOUNT_READ` target Audit을 같은 local
  transaction에서 flush한다. PostgreSQL MockMvc tests는 own/other/missing account의 200/403/404,
  missing·blank reason의 400, role-only 거부, success audit 한 건과 injected Audit failure의 503/no
  additional audit를 검증했다. Account summary는 Plan 13의 persisted `recoveryPendingKrw`를 그대로
  반환하며 grant/Audit/ledger failure를 empty body나 role fallback으로 바꾸지 않는다.
- **Plan 14 commit-failure correction (2026-08-06):** transaction proxy 바깥 orchestration이
  commit-time `TransactionException`을 `DEPENDENCY_UNAVAILABLE`로 번역한다. PostgreSQL
  `DEFERRABLE INITIALLY DEFERRED` Audit constraint trigger는 method body와 `saveAllAndFlush()` 뒤 실제
  commit에서 실패하도록 만들며, MockMvc test는 503, 성공 body 및 Audit 부재, failure metric 한 번만
  기록됨을 검증한다.

## Metrics

- `beanflow.operations.permission.check.count{permission,outcome}`
- `beanflow.operations.policy.read.count{outcome}`
- `beanflow.operations.permission.grant.revoke.count{permission,outcome}`
- `beanflow.operations.permission.bootstrap.count{action,outcome}`
- `beanflow.loyalty.point_account.read.count{actor_type,outcome}`

actor ID, access reason, evidence, Idempotency-Key와 policy content는 metric tag나 log field에
넣지 않는다.

## Revisit Conditions

Identity provider가 authoritative permission claim과 individual revocation version을 제공하거나,
break-glass access, two-person approval 또는 external authorization service가 필요해질 때
claim/server-grant 조합을 별도 ADR로 재검토한다.

## Related Decisions

- BR-10, BR-25, BR-26, BR-30
- [ADR-022](ADR-022-audit-record.md)
- [ADR-064](ADR-064-risk-based-idempotency-model-selection.md)
- [ADR-066](ADR-066-audited-loyalty-point-adjustment.md)
